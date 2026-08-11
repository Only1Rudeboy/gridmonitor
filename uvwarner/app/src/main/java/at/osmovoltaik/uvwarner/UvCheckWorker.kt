package at.osmovoltaik.uvwarner

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.LocalDateTime
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

        // Nachts sagt die gespeicherte Vorhersage 0 voraus — dann kein Abruf.
        val cached = UvRepository.loadCache(prefs)
        if (cached != null &&
            UvWarningLogic.canSkipFetch(cached, cached.localNow(), cached.ageMillis())
        ) {
            prefs.wasAboveThreshold = false
            return Result.success()
        }

        val snapshot = try {
            UvRepository.fetch(lat, lon)
        } catch (e: Exception) {
            return if (runAttemptCount < 3) Result.retry() else Result.success()
        }

        prefs.lastUv = snapshot.current
        prefs.lastCheckAt = snapshot.fetchedAt
        UvRepository.saveCache(prefs, snapshot)
        evaluate(context, prefs, snapshot)

        return Result.success()
    }

    companion object {

        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun format(uv: Double): String = String.format(Locale.GERMAN, "%.1f", uv)

        /**
         * Bewertet den Messwert und zeigt gegebenenfalls die Meldung an.
         * Die Entscheidung selbst trifft [UvWarningLogic].
         */
        fun evaluate(
            context: Context,
            prefs: Prefs,
            snapshot: UvSnapshot,
            now: LocalDateTime = snapshot.localNow()
        ) {
            val threshold = prefs.threshold
            val verdict = UvWarningLogic.decide(
                snapshot = snapshot,
                threshold = threshold,
                now = now,
                wasAboveThreshold = prefs.wasAboveThreshold,
                preWarnDate = prefs.preWarnDate
            )

            prefs.wasAboveThreshold = verdict.wasAboveThreshold
            prefs.preWarnDate = verdict.preWarnDate

            val place = prefs.placeName ?: context.getString(R.string.your_location)

            when (val decision = verdict.decision) {
                is UvDecision.Nothing -> Unit

                is UvDecision.Now -> {
                    val category = UvCategory.of(decision.uv)
                    val untilText = decision.until?.let {
                        context.getString(
                            R.string.notif_until,
                            threshold,
                            it.time.plusHours(1).format(TIME_FORMAT)
                        )
                    } ?: ""

                    Notifier.showCurrentWarning(
                        context,
                        context.getString(R.string.notif_now_title, format(decision.uv)),
                        context.getString(
                            R.string.notif_now_text,
                            place,
                            format(decision.uv),
                            context.getString(category.labelRes),
                            untilText,
                            context.getString(category.adviceRes)
                        )
                    )
                }

                is UvDecision.Soon -> {
                    val peakText = decision.peak?.let {
                        context.getString(
                            R.string.notif_peak,
                            format(it.uv),
                            it.time.format(TIME_FORMAT)
                        )
                    } ?: ""

                    Notifier.showForecastWarning(
                        context,
                        context.getString(
                            R.string.notif_soon_title,
                            threshold,
                            decision.at.time.format(TIME_FORMAT)
                        ),
                        context.getString(
                            R.string.notif_soon_text,
                            place,
                            decision.at.time.format(TIME_FORMAT),
                            threshold,
                            peakText
                        )
                    )
                }
            }
        }
    }
}
