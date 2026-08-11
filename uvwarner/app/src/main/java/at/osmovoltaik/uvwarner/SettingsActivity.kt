package at.osmovoltaik.uvwarner

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import at.osmovoltaik.uvwarner.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setUpWarningSwitch()
        setUpThreshold()
        setUpInterval()

        binding.btnBattery.setOnClickListener { openBatterySettings() }
        binding.btnBackground.setOnClickListener { openAppSettings() }
        binding.txtVersion.text = getString(R.string.version_label, BuildConfig.VERSION_NAME)
    }

    override fun onResume() {
        super.onResume()
        updateBackgroundSection()
    }

    private fun setUpWarningSwitch() {
        binding.swEnabled.isChecked = prefs.warningsEnabled
        binding.swEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.warningsEnabled = checked
            if (checked) {
                UvScheduler.schedule(this, prefs.intervalMinutes)
                askNotificationPermissionIfNeeded()
            } else {
                UvScheduler.cancel(this)
            }
        }
    }

    private fun setUpThreshold() {
        binding.sliderThreshold.value = prefs.threshold.toFloat()
        binding.txtThreshold.text = getString(R.string.threshold_value, prefs.threshold)

        binding.sliderThreshold.addOnChangeListener { _, value, fromUser ->
            val threshold = value.toInt()
            binding.txtThreshold.text = getString(R.string.threshold_value, threshold)
            if (!fromUser || threshold == prefs.threshold) return@addOnChangeListener
            prefs.threshold = threshold
            // Zustand zurücksetzen, damit die neue Schwelle sofort greift.
            prefs.wasAboveThreshold = false
            prefs.preWarnDate = null
        }
    }

    private fun setUpInterval() {
        val checkedId = when (prefs.intervalMinutes) {
            15 -> R.id.btnInterval15
            30 -> R.id.btnInterval30
            180 -> R.id.btnInterval180
            else -> R.id.btnInterval60
        }
        binding.groupInterval.check(checkedId)

        binding.groupInterval.addOnButtonCheckedListener { _, id, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.intervalMinutes = when (id) {
                R.id.btnInterval15 -> 15
                R.id.btnInterval30 -> 30
                R.id.btnInterval180 -> 180
                else -> 60
            }
            if (prefs.warningsEnabled) {
                UvScheduler.schedule(this, prefs.intervalMinutes)
            }
        }
    }

    /**
     * Der Hintergrund-Standort ist optional: ohne ihn nutzt die Prüfung die
     * zuletzt in der App ermittelte Position.
     */
    private fun updateBackgroundSection() {
        val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !LocationHelper.hasBackgroundPermission(this)

        binding.btnBackground.visibility = if (needed) View.VISIBLE else View.GONE
        binding.txtBackgroundHint.text = if (needed) {
            getString(R.string.background_hint)
        } else {
            getString(R.string.background_granted)
        }
    }

    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (Notifier.canPostNotifications(this)) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            // Nicht jedes Gerät hat diesen Bildschirm — dann die App-Details öffnen.
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        } catch (e: Exception) {
            // Ohne Einstellungs-App bleibt nichts zu tun.
        }
    }
}
