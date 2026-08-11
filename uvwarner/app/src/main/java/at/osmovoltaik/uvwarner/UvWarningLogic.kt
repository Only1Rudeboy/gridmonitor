package at.osmovoltaik.uvwarner

import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Was aufgrund der Messwerte zu melden ist. */
sealed interface UvDecision {

    /** Nichts zu melden. */
    data object Nothing : UvDecision

    /** Die Schwelle ist gerade überschritten. */
    data class Now(val uv: Double, val until: HourUv?) : UvDecision

    /** Die Schwelle wird heute in Kürze überschritten. */
    data class Soon(val at: HourUv, val peak: HourUv?) : UvDecision
}

/**
 * Ergebnis einer Bewertung: was gemeldet wird und wie sich der gespeicherte
 * Zustand danach ändert.
 */
data class UvVerdict(
    val decision: UvDecision,
    val wasAboveThreshold: Boolean,
    val preWarnDate: String?
)

/**
 * Die eigentliche Entscheidungslogik — bewusst ohne Android-Abhängigkeiten,
 * damit sie sich als reiner Unit-Test prüfen lässt.
 */
object UvWarningLogic {

    /** Wie lange im Voraus gewarnt wird, bevor die Schwelle erreicht wird. */
    const val PRE_WARN_MINUTES = 180L

    /** Eine bereits laufende Stunde darf noch so weit zurückliegen. */
    private const val PRE_WARN_GRACE_MINUTES = -60L

    /** Unterhalb dieses Werts gilt es als „keine Sonne". */
    private const val NIGHT_UV = 0.5

    /** So weit voraus muss die Vorhersage bei 0 liegen, um den Abruf zu sparen. */
    private const val SKIP_LOOKAHEAD_HOURS = 2L

    /** Älter darf die gespeicherte Vorhersage dafür nicht sein. */
    private const val MAX_CACHE_AGE_MILLIS = 18L * 60L * 60L * 1000L

    /**
     * Entscheidet, ob gewarnt wird. Es wird nur einmal pro Überschreitung
     * gemeldet; die Vorwarnung höchstens einmal pro Tag.
     */
    fun decide(
        snapshot: UvSnapshot,
        threshold: Int,
        now: LocalDateTime,
        wasAboveThreshold: Boolean,
        preWarnDate: String?
    ): UvVerdict {
        val today = now.toLocalDate()

        if (snapshot.current >= threshold) {
            // Schon gemeldet: Zustand halten, nicht erneut warnen.
            if (wasAboveThreshold) {
                return UvVerdict(UvDecision.Nothing, true, preWarnDate)
            }
            val until = snapshot.lastAtOrAbove(threshold.toDouble(), today)
            return UvVerdict(UvDecision.Now(snapshot.current, until), true, preWarnDate)
        }

        // Unter der Schwelle: nächste Überschreitung darf wieder gemeldet werden.
        val next = snapshot.firstAtOrAbove(threshold.toDouble(), now)
        if (next == null || next.time.toLocalDate() != today || preWarnDate == today.toString()) {
            return UvVerdict(UvDecision.Nothing, false, preWarnDate)
        }

        val minutes = Duration.between(now, next.time).toMinutes()
        if (minutes > PRE_WARN_MINUTES || minutes < PRE_WARN_GRACE_MINUTES) {
            return UvVerdict(UvDecision.Nothing, false, preWarnDate)
        }

        return UvVerdict(
            UvDecision.Soon(next, snapshot.maxOn(today)),
            false,
            today.toString()
        )
    }

    /**
     * Nachts ist ein Abruf sinnlos. Sagt die gespeicherte Vorhersage für die
     * nächsten Stunden praktisch 0 voraus, wird der Netzabruf gespart —
     * das schont Akku und Datenvolumen.
     */
    fun canSkipFetch(cached: UvSnapshot?, now: LocalDateTime, cacheAgeMillis: Long): Boolean {
        if (cached == null || cacheAgeMillis > MAX_CACHE_AGE_MILLIS) return false

        val from = now.truncatedTo(ChronoUnit.HOURS)
        val until = from.plusHours(SKIP_LOOKAHEAD_HOURS)
        val window = cached.hours.filter { !it.time.isBefore(from) && !it.time.isAfter(until) }

        // Reicht die Vorhersage nicht weit genug, lieber abrufen.
        if (window.size <= SKIP_LOOKAHEAD_HOURS.toInt()) return false
        return window.all { it.uv < NIGHT_UV }
    }
}
