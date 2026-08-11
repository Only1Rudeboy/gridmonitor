package at.osmovoltaik.uvwarner

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Ein Stundenwert der UV-Vorhersage (Zeit in lokaler Zeit des Standorts). */
data class HourUv(val time: LocalDateTime, val uv: Double)

/**
 * Ergebnis eines Abrufs bei Open-Meteo. Alle Zeiten sind lokale Zeiten am
 * Standort; [utcOffsetSeconds] erlaubt, "jetzt" in dieser Zone zu bestimmen.
 */
data class UvSnapshot(
    val current: Double,
    val currentTime: LocalDateTime,
    val utcOffsetSeconds: Int,
    val timezone: String,
    val hours: List<HourUv>,
    val fetchedAt: Long = System.currentTimeMillis()
) {
    fun localNow(): LocalDateTime =
        LocalDateTime.ofInstant(Instant.now(), ZoneOffset.ofTotalSeconds(utcOffsetSeconds))

    fun hoursOn(date: LocalDate): List<HourUv> = hours.filter { it.time.toLocalDate() == date }

    /** Höchster Stundenwert des Tages. */
    fun maxOn(date: LocalDate): HourUv? = hoursOn(date).maxByOrNull { it.uv }

    /** Erste Stunde ab [from], in der die Schwelle erreicht wird. */
    fun firstAtOrAbove(threshold: Double, from: LocalDateTime): HourUv? =
        hours.firstOrNull { !it.time.isBefore(from.withMinute(0).withSecond(0).withNano(0)) && it.uv >= threshold }

    /** Letzte Stunde des Tages von [date], in der die Schwelle noch erreicht wird. */
    fun lastAtOrAbove(threshold: Double, date: LocalDate): HourUv? =
        hoursOn(date).lastOrNull { it.uv >= threshold }

    /** Die nächsten [count] Stunden ab der aktuellen Stunde. */
    fun upcoming(count: Int): List<HourUv> {
        val from = localNow().withMinute(0).withSecond(0).withNano(0)
        return hours.filter { !it.time.isBefore(from) }.take(count)
    }

    fun minutesUntil(hour: HourUv): Long =
        Duration.between(localNow(), hour.time).toMinutes()
}

/** WHO-Kategorien des UV-Index. */
enum class UvCategory(val labelRes: Int, val colorRes: Int, val adviceRes: Int) {
    LOW(R.string.uv_low, R.color.uv_low, R.string.advice_low),
    MODERATE(R.string.uv_moderate, R.color.uv_moderate, R.string.advice_moderate),
    HIGH(R.string.uv_high, R.color.uv_high, R.string.advice_high),
    VERY_HIGH(R.string.uv_very_high, R.color.uv_very_high, R.string.advice_very_high),
    EXTREME(R.string.uv_extreme, R.color.uv_extreme, R.string.advice_extreme);

    companion object {
        fun of(uv: Double): UvCategory = when {
            uv < 3.0 -> LOW
            uv < 6.0 -> MODERATE
            uv < 8.0 -> HIGH
            uv < 11.0 -> VERY_HIGH
            else -> EXTREME
        }
    }
}
