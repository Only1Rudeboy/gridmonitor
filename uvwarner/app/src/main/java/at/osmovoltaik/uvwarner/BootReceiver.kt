package at.osmovoltaik.uvwarner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Stellt die periodische Prüfung nach Neustart bzw. App-Update wieder her. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val prefs = Prefs(context)
        if (prefs.warningsEnabled) {
            UvScheduler.schedule(context, prefs.intervalMinutes)
        }
    }
}
