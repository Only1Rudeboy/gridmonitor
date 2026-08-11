package at.osmovoltaik.uvwarner

import android.Manifest
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import at.osmovoltaik.uvwarner.databinding.ActivityMainBinding
import at.osmovoltaik.uvwarner.databinding.ItemHourBinding
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private var snapshot: UvSnapshot? = null
    private var snapshotIsLive = false
    private var loading = false

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) {
                hideNotice()
                refresh()
            } else {
                showNotice(getString(R.string.need_location), withAction = true)
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) showNotice(getString(R.string.need_notifications))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    onRefreshRequested(); true
                }

                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java)); true
                }

                else -> false
            }
        }

        binding.swipeRefresh.setOnRefreshListener { onRefreshRequested() }
        binding.btnNotice.setOnClickListener { requestLocationPermission() }
        binding.cardGuard.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        renderCached()

        if (prefs.warningsEnabled) {
            UvScheduler.schedule(this, prefs.intervalMinutes)
            askNotificationPermissionIfNeeded()
        }
    }

    override fun onResume() {
        super.onResume()
        renderGuard()
        // Die Schwelle kann in den Einstellungen geändert worden sein.
        snapshot?.let { renderSnapshot(it, snapshotIsLive) }

        if (!LocationHelper.hasLocationPermission(this)) {
            showNotice(getString(R.string.need_location), withAction = true)
            return
        }

        val stale = System.currentTimeMillis() - prefs.lastCheckAt > REFRESH_AFTER_MILLIS
        if (!loading && stale) refresh()
    }

    // ---------------------------------------------------------------- Abruf

    private fun onRefreshRequested() {
        if (!LocationHelper.hasLocationPermission(this)) {
            binding.swipeRefresh.isRefreshing = false
            requestLocationPermission()
            return
        }
        refresh()
    }

    private fun refresh() {
        if (loading) return
        loading = true
        binding.swipeRefresh.isRefreshing = true

        lifecycleScope.launch {
            try {
                val location = LocationHelper.currentLocation(this@MainActivity)
                if (location == null) {
                    showNotice(
                        if (LocationHelper.isLocationEnabled(this@MainActivity)) {
                            getString(R.string.no_location)
                        } else {
                            getString(R.string.location_off)
                        }
                    )
                    return@launch
                }

                prefs.saveLocation(location.latitude, location.longitude)
                prefs.placeName =
                    LocationHelper.placeName(this@MainActivity, location.latitude, location.longitude)
                binding.txtPlace.text = prefs.placeName ?: getString(R.string.place_unknown)

                val result = UvRepository.fetch(location.latitude, location.longitude)
                snapshot = result
                snapshotIsLive = true
                prefs.lastUv = result.current
                prefs.lastCheckAt = result.fetchedAt
                UvRepository.saveCache(prefs, result)

                // Auch beim manuellen Abruf warnen, wenn die Schwelle erreicht ist.
                UvCheckWorker.evaluate(this@MainActivity, prefs, result)

                hideNotice()
                renderSnapshot(result)
            } catch (e: Exception) {
                showNotice(getString(R.string.error_generic, e.message ?: e.javaClass.simpleName))
            } finally {
                loading = false
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    // ---------------------------------------------------------- Darstellung

    /** Zeigt sofort den gespeicherten Stand — auch ohne Netz. */
    private fun renderCached() {
        binding.txtPlace.text = prefs.placeName ?: getString(R.string.place_unknown)

        lifecycleScope.launch {
            val cached = withContext(Dispatchers.Default) { UvRepository.loadCache(prefs) }
            if (cached == null || snapshot != null || loading) return@launch
            snapshot = cached
            snapshotIsLive = cached.isFresh()
            renderSnapshot(cached, snapshotIsLive)
        }
    }

    private fun renderSnapshot(snap: UvSnapshot, live: Boolean = true) {
        val uv = snap.displayUv()
        val category = UvCategory.of(uv)
        val color = ContextCompat.getColor(this, category.colorRes)

        binding.txtUvValue.text = UvCheckWorker.format(uv)
        binding.txtUvValue.setTextColor(color)
        binding.txtUvCategory.text = getString(category.labelRes)
        binding.txtUvCategory.setTextColor(color)
        binding.txtAdvice.text = getString(category.adviceRes)

        binding.ringUv.setIndicatorColor(color)
        binding.ringUv.trackColor = MaterialColors.compositeARGBWithAlpha(color, TRACK_ALPHA)
        binding.ringUv.setProgressCompat(
            ((uv / BAR_MAX) * 100.0).toInt().coerceIn(0, 100),
            live
        )

        binding.txtUpdated.text = getString(R.string.updated_relative, relativeTime(snap.fetchedAt))

        renderToday(snap)
        renderHours(snap)
        renderGuard()
    }

    private fun renderToday(snap: UvSnapshot) {
        val threshold = prefs.threshold
        val today = snap.localNow().toLocalDate()
        val peak = snap.maxOn(today) ?: run {
            binding.cardToday.visibility = View.GONE
            return
        }
        binding.cardToday.visibility = View.VISIBLE

        val first = snap.hoursOn(today).firstOrNull { it.uv >= threshold }
        val last = snap.lastAtOrAbove(threshold.toDouble(), today)

        binding.txtToday.text = if (first != null && last != null) {
            getString(
                R.string.today_span,
                threshold,
                first.time.format(timeFormat),
                last.time.plusHours(1).format(timeFormat)
            )
        } else {
            getString(R.string.today_calm, threshold)
        }

        binding.txtTodayPeak.text = getString(
            R.string.today_peak,
            UvCheckWorker.format(peak.uv),
            peak.time.format(timeFormat)
        )

        renderTomorrow(snap)
    }

    private fun renderTomorrow(snap: UvSnapshot) {
        val threshold = prefs.threshold
        val tomorrow = snap.localNow().toLocalDate().plusDays(1)
        val peak = snap.maxOn(tomorrow)

        if (peak == null) {
            binding.txtTomorrow.visibility = View.GONE
            return
        }
        binding.txtTomorrow.visibility = View.VISIBLE

        val first = snap.hoursOn(tomorrow).firstOrNull { it.uv >= threshold }
        binding.txtTomorrow.text = if (first != null) {
            getString(
                R.string.tomorrow_span,
                threshold,
                first.time.format(timeFormat),
                UvCheckWorker.format(peak.uv)
            )
        } else {
            getString(
                R.string.tomorrow_peak,
                UvCheckWorker.format(peak.uv),
                peak.time.format(timeFormat)
            )
        }
    }

    private fun renderHours(snap: UvSnapshot) {
        val hours = snap.upcoming(HOURS_SHOWN)
        if (hours.isEmpty()) {
            binding.cardHours.visibility = View.GONE
            return
        }
        binding.cardHours.visibility = View.VISIBLE
        binding.boxHours.removeAllViews()

        val trackHeight = dp(BAR_HEIGHT_DP)
        val minHeight = dp(BAR_MIN_DP)
        val currentHour = snap.localNow().hour
        val subtle = MaterialColors.getColor(binding.boxHours, com.google.android.material.R.attr.colorOnSurfaceVariant)

        hours.forEach { hour ->
            val row = ItemHourBinding.inflate(layoutInflater, binding.boxHours, false)
            val color = ContextCompat.getColor(this, UvCategory.of(hour.uv).colorRes)
            val isNow = hour.time.hour == currentHour

            row.txtHour.text = hour.time.format(timeFormat)
            row.txtHour.setTextColor(subtle)
            row.txtHourValue.text = UvCheckWorker.format(hour.uv)
            row.txtHourValue.setTextColor(color)
            if (isNow) {
                row.txtHour.setTypeface(null, Typeface.BOLD)
                row.txtHour.setTextColor(color)
            }

            val filled = ((hour.uv / BAR_MAX) * trackHeight).toInt().coerceIn(minHeight, trackHeight)
            row.viewBar.layoutParams = (row.viewBar.layoutParams as FrameLayout.LayoutParams)
                .apply { height = filled }
            row.viewBar.background?.mutate()?.setTint(color)
            row.boxBar.background?.mutate()
                ?.setTint(MaterialColors.compositeARGBWithAlpha(color, TRACK_ALPHA))

            binding.boxHours.addView(row.root)
        }
    }

    private fun renderGuard() {
        binding.txtGuard.text = if (prefs.warningsEnabled) {
            getString(R.string.guard_on, prefs.threshold, intervalLabel(prefs.intervalMinutes))
        } else {
            getString(R.string.guard_off)
        }
    }

    private fun intervalLabel(minutes: Int): String = when (minutes) {
        15 -> getString(R.string.interval_15_long)
        30 -> getString(R.string.interval_30_long)
        180 -> getString(R.string.interval_180_long)
        else -> getString(R.string.interval_60_long)
    }

    private fun relativeTime(epochMillis: Long): CharSequence {
        val age = System.currentTimeMillis() - epochMillis
        if (age < DateUtils.MINUTE_IN_MILLIS) return getString(R.string.just_now)
        return DateUtils.getRelativeTimeSpanString(
            epochMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // -------------------------------------------------------------- Hinweise

    private fun showNotice(message: String, withAction: Boolean = false) {
        binding.txtNotice.text = message
        binding.btnNotice.visibility = if (withAction) View.VISIBLE else View.GONE
        binding.cardNotice.visibility = View.VISIBLE
    }

    private fun hideNotice() {
        binding.cardNotice.visibility = View.GONE
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
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        if (Notifier.canPostNotifications(this)) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        private const val HOURS_SHOWN = 12
        private const val BAR_MAX = 12.0
        private const val BAR_HEIGHT_DP = 104
        private const val BAR_MIN_DP = 6
        private const val TRACK_ALPHA = 38
        private const val REFRESH_AFTER_MILLIS = 10L * 60L * 1000L
    }
}
