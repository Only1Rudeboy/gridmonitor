package at.osmovoltaik.uvwarner

import android.Manifest
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import at.osmovoltaik.uvwarner.databinding.ActivityMainBinding
import at.osmovoltaik.uvwarner.databinding.ItemHourBinding
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private var snapshot: UvSnapshot? = null
    private var snapshotIsLive = false
    private var loading = false
    private var spinnerInitialised = false

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            updatePermissionUi()
            if (result.values.any { it }) refresh()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) showStatus(getString(R.string.notifications_needed))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        Notifier.ensureChannel(this)

        binding.txtThreshold.text = prefs.threshold.toString()
        binding.swEnabled.isChecked = prefs.warningsEnabled

        binding.btnThresholdMinus.setOnClickListener { changeThreshold(-1) }
        binding.btnThresholdPlus.setOnClickListener { changeThreshold(+1) }
        binding.btnRefresh.setOnClickListener { onRefreshClicked() }
        binding.btnPermission.setOnClickListener { requestLocationPermission() }
        binding.btnBattery.setOnClickListener { openBatterySettings() }

        binding.swEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.warningsEnabled = checked
            if (checked) {
                UvScheduler.schedule(this, prefs.intervalMinutes)
                askNotificationPermissionIfNeeded()
                maybeSuggestBackgroundLocation()
            } else {
                UvScheduler.cancel(this)
            }
        }

        setupIntervalSpinner()
        renderCached()

        if (prefs.warningsEnabled) {
            UvScheduler.schedule(this, prefs.intervalMinutes)
            askNotificationPermissionIfNeeded()
        }
    }

    override fun onStart() {
        super.onStart()
        updatePermissionUi()
        // Beim Öffnen nur nachladen, wenn der gespeicherte Stand alt genug ist.
        val stale = System.currentTimeMillis() - prefs.lastCheckAt > REFRESH_AFTER_MILLIS
        if (LocationHelper.hasLocationPermission(this) && !loading && stale) {
            refresh()
        }
    }

    // ---------------------------------------------------------------- Aktionen

    private fun onRefreshClicked() {
        if (!LocationHelper.hasLocationPermission(this)) {
            requestLocationPermission()
            return
        }
        refresh()
    }

    private fun refresh() {
        if (loading) return
        loading = true
        binding.btnRefresh.isEnabled = false
        showStatus(getString(R.string.loading), error = false)

        lifecycleScope.launch {
            try {
                val location = LocationHelper.currentLocation(this@MainActivity)
                if (location == null) {
                    showStatus(
                        if (LocationHelper.isLocationEnabled(this@MainActivity)) {
                            getString(R.string.no_location)
                        } else {
                            getString(R.string.location_off)
                        }
                    )
                    return@launch
                }

                prefs.saveLocation(location.latitude, location.longitude)
                // Auch ein leerer Name wird übernommen, sonst bliebe der alte Ort stehen.
                prefs.placeName =
                    LocationHelper.placeName(this@MainActivity, location.latitude, location.longitude)
                binding.txtPlace.text = placeLabel(location.latitude, location.longitude)

                val result = UvRepository.fetch(location.latitude, location.longitude)
                snapshot = result
                snapshotIsLive = true
                prefs.lastUv = result.current
                prefs.lastCheckAt = result.fetchedAt
                UvRepository.saveCache(prefs, result)

                // Auch beim manuellen Abruf warnen, wenn die Schwelle erreicht ist.
                UvCheckWorker.evaluate(this@MainActivity, prefs, result)

                hideStatus()
                renderSnapshot(result)
            } catch (e: Exception) {
                showStatus(getString(R.string.error_generic, e.message ?: e.javaClass.simpleName))
            } finally {
                loading = false
                binding.btnRefresh.isEnabled = true
            }
        }
    }

    private fun changeThreshold(delta: Int) {
        val value = (prefs.threshold + delta).coerceIn(1, 11)
        if (value == prefs.threshold) return
        prefs.threshold = value
        // Zustand zurücksetzen, damit die neue Schwelle sofort greift.
        prefs.wasAboveThreshold = false
        prefs.preWarnDate = null
        binding.txtThreshold.text = value.toString()
        snapshot?.let { renderSnapshot(it, snapshotIsLive) }
    }

    private fun setupIntervalSpinner() {
        val adapter = ArrayAdapter.createFromResource(
            this, R.array.interval_labels, android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spInterval.adapter = adapter

        val index = INTERVALS.indexOf(prefs.intervalMinutes).takeIf { it >= 0 }
            ?: INTERVALS.indexOf(Prefs.DEFAULT_INTERVAL)
        binding.spInterval.setSelection(index, false)

        binding.spInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!spinnerInitialised) {
                    spinnerInitialised = true
                    return
                }
                prefs.intervalMinutes = INTERVALS[position]
                if (prefs.warningsEnabled) {
                    UvScheduler.schedule(this@MainActivity, prefs.intervalMinutes)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    // ------------------------------------------------------------ Darstellung

    /**
     * Zeigt sofort den zuletzt gespeicherten Stand — dadurch ist die App auch
     * ohne Netz und noch vor dem ersten Abruf brauchbar.
     */
    private fun renderCached() {
        binding.txtPlace.text = if (prefs.hasLocation) {
            placeLabel(prefs.latitude ?: 0.0, prefs.longitude ?: 0.0)
        } else {
            getString(R.string.your_location)
        }

        lifecycleScope.launch {
            val cached = withContext(Dispatchers.Default) { UvRepository.loadCache(prefs) }
            // Ein zwischenzeitlich gelaufener Abruf hat Vorrang.
            if (cached != null && snapshot == null && !loading) {
                snapshot = cached
                snapshotIsLive = cached.isFresh()
                renderSnapshot(cached, snapshotIsLive)
                return@launch
            }

            val last = prefs.lastUv
            if (last.isNaN() || snapshot != null) return@launch
            applyCurrentValue(last)
            binding.txtUpdated.text = if (prefs.lastCheckAt > 0L) {
                getString(R.string.updated_cached, formatClock(prefs.lastCheckAt))
            } else {
                getString(R.string.updated_never)
            }
        }
    }

    private fun renderSnapshot(snap: UvSnapshot, live: Boolean = true) {
        applyCurrentValue(snap.displayUv())
        binding.txtUpdated.text = if (live) {
            getString(R.string.updated_at, formatClock(snap.fetchedAt))
        } else {
            getString(R.string.updated_cached, formatClock(snap.fetchedAt))
        }
        renderToday(snap)
        renderTomorrow(snap)
        renderHours(snap)
    }

    private fun applyCurrentValue(uv: Double) {
        val category = UvCategory.of(uv)
        val color = ContextCompat.getColor(this, category.colorRes)
        binding.txtUvValue.text = UvCheckWorker.format(uv)
        binding.txtUvValue.setTextColor(color)
        binding.txtUvCategory.text = getString(category.labelRes)
        binding.txtUvCategory.setTextColor(color)
        binding.txtAdvice.text = getString(category.adviceRes)
        binding.viewUvStrip.setBackgroundColor(color)
    }

    private fun renderToday(snap: UvSnapshot) {
        val threshold = prefs.threshold
        val today = snap.localNow().toLocalDate()
        val peak = snap.maxOn(today)
        val first = snap.hoursOn(today).firstOrNull { it.uv >= threshold }
        val last = snap.lastAtOrAbove(threshold.toDouble(), today)

        binding.txtToday.text = if (first != null && last != null && peak != null) {
            getString(
                R.string.today_summary,
                threshold,
                first.time.format(timeFormat),
                last.time.plusHours(1).format(timeFormat),
                UvCheckWorker.format(peak.uv),
                peak.time.format(timeFormat)
            )
        } else {
            getString(R.string.today_below, threshold, UvCheckWorker.format(peak?.uv ?: 0.0))
        }
    }

    private fun renderTomorrow(snap: UvSnapshot) {
        val threshold = prefs.threshold
        val tomorrow = snap.localNow().toLocalDate().plusDays(1)
        val peak = snap.maxOn(tomorrow)

        if (peak == null) {
            binding.txtTomorrow.visibility = View.GONE
            binding.lblTomorrow.visibility = View.GONE
            return
        }

        binding.txtTomorrow.visibility = View.VISIBLE
        binding.lblTomorrow.visibility = View.VISIBLE

        val base = getString(
            R.string.tomorrow_summary,
            UvCheckWorker.format(peak.uv),
            peak.time.format(timeFormat)
        )
        val first = snap.hoursOn(tomorrow).firstOrNull { it.uv >= threshold }
        binding.txtTomorrow.text = if (first != null) {
            base + getString(R.string.tomorrow_threshold, threshold, first.time.format(timeFormat))
        } else {
            base
        }
    }

    private fun renderHours(snap: UvSnapshot) {
        binding.boxHours.removeAllViews()
        val threshold = prefs.threshold

        snap.upcoming(HOURS_SHOWN).forEach { hour ->
            val row = ItemHourBinding.inflate(layoutInflater, binding.boxHours, false)
            val color = ContextCompat.getColor(this, UvCategory.of(hour.uv).colorRes)

            row.txtHour.text = hour.time.format(timeFormat)
            row.txtHour.setTypeface(null, if (hour.uv >= threshold) Typeface.BOLD else Typeface.NORMAL)
            row.txtHourValue.text = UvCheckWorker.format(hour.uv)
            row.txtHourValue.setTextColor(color)

            val filled = hour.uv.coerceIn(0.0, BAR_MAX).toFloat()
            row.viewBar.layoutParams = (row.viewBar.layoutParams as LinearLayout.LayoutParams)
                .apply { weight = filled }
            row.viewBarRest.layoutParams = (row.viewBarRest.layoutParams as LinearLayout.LayoutParams)
                .apply { weight = BAR_MAX.toFloat() - filled }
            row.viewBar.background?.mutate()?.setTint(color)

            binding.boxHours.addView(row.root)
        }
    }

    private fun placeLabel(latitude: Double, longitude: Double): String {
        val name = prefs.placeName ?: getString(R.string.your_location)
        return String.format(Locale.GERMAN, "%s · %.3f, %.3f", name, latitude, longitude)
    }

    private fun formatClock(epochMillis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
            .format(timeFormat)

    private fun showStatus(message: String, error: Boolean = true) {
        binding.txtStatus.text = message
        binding.txtStatus.visibility = View.VISIBLE
        binding.txtStatus.setTextColor(
            MaterialColors.getColor(
                binding.txtStatus,
                if (error) {
                    com.google.android.material.R.attr.colorError
                } else {
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                }
            )
        )
    }

    private fun hideStatus() {
        binding.txtStatus.visibility = View.GONE
    }

    // ---------------------------------------------------------- Berechtigungen

    private fun updatePermissionUi() {
        val granted = LocationHelper.hasLocationPermission(this)
        binding.btnPermission.visibility = if (granted) View.GONE else View.VISIBLE
        if (!granted) showStatus(getString(R.string.permission_needed))
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (Notifier.canPostNotifications(this)) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Ohne Hintergrund-Standort funktioniert die Warnung weiterhin — dann eben
     * mit der zuletzt in der App ermittelten Position. Deshalb nur ein Hinweis.
     */
    private fun maybeSuggestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!LocationHelper.hasLocationPermission(this)) return
        if (LocationHelper.hasBackgroundPermission(this)) return
        if (prefs.backgroundHintShown) return

        prefs.backgroundHintShown = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.background_title)
            .setMessage(R.string.background_message)
            .setPositiveButton(R.string.background_open) { _, _ -> openAppSettings() }
            .setNegativeButton(R.string.background_later, null)
            .show()
    }

    private fun openAppSettings() {
        startActivitySafely(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            // Nicht jedes Gerät hat diesen Bildschirm — dann die App-Details öffnen.
            openAppSettings()
        }
    }

    private fun startActivitySafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            showStatus(getString(R.string.error_generic, e.message ?: e.javaClass.simpleName))
        }
    }

    companion object {
        private val INTERVALS = listOf(15, 30, 60, 180)
        private const val HOURS_SHOWN = 12
        private const val BAR_MAX = 12.0
        private const val REFRESH_AFTER_MILLIS = 10L * 60L * 1000L
    }
}
