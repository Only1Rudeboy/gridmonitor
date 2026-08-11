package at.osmovoltaik.uvwarner

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Prüft im Hintergrund regelmäßig den UV-Index und warnt bei Überschreitung. */
class UvCheckWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val prefs = Prefs(context)

        if (!prefs.warningsEnabled) return Result.success()

        var latitude = prefs.latitude
        var longitude = prefs.longitude

        // Frische Position nur, wenn der Standort auch im Hintergrund erlaubt ist.
        // Sonst wird die zuletzt in der App ermittelte Position verwendet.
        if (LocationHelper.hasBackgroundPermission(context)) {
            LocationHelper.currentLocation(context, timeoutMs = 15_000L)?.let { location ->
                latitude = location.latitude
                longitude = location.longitude
                prefs.saveLocation(location.latitude, location.longitude)
            }
        }

        val lat = latitude ?: return Result.success()
        val lon = longitude ?: return Result.success()

        val snapshot = try {
            UvRepository.fetch(lat, lon)
        } catch (e: Exception) {
            return if (runAttemptCount < 3) Result.retry() else Result.success()
        }

        prefs.lastUv = snapshot.current
        prefs.lastCheckAt = snapshot.fetchedAt
        evaluate(context, prefs, snapshot)

        return Result.success()
    }

    companion object {

        /** Wie lange im Voraus gewarnt wird, bevor die Schwelle erreicht wird. */
        private const val PRE_WARN_MINUTES = 180L

        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun format(uv: Double): String = String.format(Locale.GERMAN, "%.1f", uv)

        /**
         * Entscheidet anhand des Messwerts, ob gewarnt wird. Es wird nur einmal
         * pro Überschreitung gemeldet (und höchstens einmal täglich vorgewarnt).
         */
        fun evaluate(context: Context, prefs: Prefs, snapshot: UvSnapshot) {
            val threshold = prefs.threshold
            val now = snapshot.localNow()
            val today = now.toLocalDate()
            val place = prefs.placeName ?: context.getString(R.string.your_location)

            if (snapshot.current >= threshold) {
                if (prefs.wasAboveThreshold) return

                val category = UvCategory.of(snapshot.current)
                val lastHour = snapshot.lastAtOrAbove(threshold.toDouble(), today)
                val untilText = if (lastHour != null) {
                    context.getString(
                        R.string.notif_until,
                        threshold,
                        lastHour.time.plusHours(1).format(TIME_FORMAT)
                    )
                } else {
                    ""
                }

                Notifier.showCurrentWarning(
                    context,
                    context.getString(R.string.notif_now_title, format(snapshot.current)),
                    context.getString(
                        R.string.notif_now_text,
                        place,
                        format(snapshot.current),
                        context.getString(category.labelRes),
                        untilText,
                        context.getString(category.adviceRes)
                    )
                )
                prefs.wasAboveThreshold = true
                return
            }

            // Unter der Schwelle: Zustand zurücksetzen und ggf. vorwarnen.
            prefs.wasAboveThreshold = false

            val next = snapshot.firstAtOrAbove(threshold.toDouble(), now) ?: return
            if (next.time.toLocalDate() != today) return
            if (prefs.preWarnDate == today.toString()) return

            val minutes = snapshot.minutesUntil(next)
            if (minutes > PRE_WARN_MINUTES || minutes < -60L) return

            val peak = snapshot.maxOn(today)
            val peakText = if (peak != null) {
                context.getString(R.string.notif_peak, format(peak.uv), peak.time.format(TIME_FORMAT))
            } else {
                ""
            }

            Notifier.showForecastWarning(
                context,
                context.getString(
                    R.string.notif_soon_title,
                    threshold,
                    next.time.format(TIME_FORMAT)
                ),
                context.getString(
                    R.string.notif_soon_text,
                    place,
                    next.time.format(TIME_FORMAT),
                    threshold,
                    peakText
                )
            )
            prefs.preWarnDate = today.toString()
        }
    }
}
