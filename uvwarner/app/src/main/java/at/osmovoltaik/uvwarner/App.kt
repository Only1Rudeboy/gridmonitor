package at.osmovoltaik.uvwarner

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // Ab Android 12 übernimmt die App die Systemfarben des Geräts.
        DynamicColors.applyToActivitiesIfAvailable(this)
        Notifier.ensureChannel(this)
    }
}
