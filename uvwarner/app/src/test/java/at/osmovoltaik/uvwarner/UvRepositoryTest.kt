package at.osmovoltaik.uvwarner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/** Prüft das Auswerten der Open-Meteo-Antwort gegen realistische Nutzlasten. */
class UvRepositoryTest {

    private fun response(
        current: String? = """"current":{"time":"2026-08-11T12:15","interval":900,"uv_index":6.85},""",
        times: List<String>,
        values: List<String>
    ): String = """
        {
          "latitude":47.5,"longitude":9.75,"generationtime_ms":0.12,
          "utc_offset_seconds":7200,"timezone":"Europe/Vienna",
          "timezone_abbreviation":"CEST","elevation":420.0,
          ${current ?: ""}
          "hourly_units":{"time":"iso8601","uv_index":""},
          "hourly":{
            "time":[${times.joinToString(",") { "\"$it\"" }}],
            "uv_index":[${values.joinToString(",")}]
          }
        }
    """.trimIndent()

    private val dayTimes = (0..23).map { String.format(Locale.US, "2026-08-11T%02d:00", it) }
    private val dayValues = listOf(
        "0.0", "0.0", "0.0", "0.0", "0.0", "0.05", "0.4", "1.1",
        "2.2", "3.6", "5.1", "6.4", "7.0", "6.85", "5.9", "4.3",
        "2.7", "1.4", "0.5", "0.05", "0.0", "0.0", "0.0", "0.0"
    )

    @Test
    fun `liest aktuellen Wert und Stundenvorhersage`() {
        val snapshot = UvRepository.parse(response(times = dayTimes, values = dayValues))

        assertEquals(6.85, snapshot.current, 0.001)
        assertEquals(LocalDateTime.parse("2026-08-11T12:15"), snapshot.currentTime)
        assertEquals(7200, snapshot.utcOffsetSeconds)
        assertEquals("Europe/Vienna", snapshot.timezone)
        assertEquals(24, snapshot.hours.size)
        assertTrue(snapshot.raw.isNotBlank())
    }

    @Test
    fun `findet Hoechstwert und Ueberschreitungsfenster des Tages`() {
        val snapshot = UvRepository.parse(response(times = dayTimes, values = dayValues))
        val day = LocalDate.parse("2026-08-11")

        assertEquals(7.0, snapshot.maxOn(day)?.uv ?: 0.0, 0.001)
        assertEquals(LocalDateTime.parse("2026-08-11T12:00"), snapshot.maxOn(day)?.time)
        assertEquals(
            LocalDateTime.parse("2026-08-11T10:00"),
            snapshot.firstAtOrAbove(4.0, LocalDateTime.parse("2026-08-11T06:00"))?.time
        )
        assertEquals(
            LocalDateTime.parse("2026-08-11T15:00"),
            snapshot.lastAtOrAbove(4.0, day)?.time
        )
    }

    @Test
    fun `ueberspringt Luecken in der Vorhersage`() {
        val values = dayValues.toMutableList().apply {
            this[10] = "null"
            this[11] = "null"
        }
        val snapshot = UvRepository.parse(response(times = dayTimes, values = values))

        assertEquals(22, snapshot.hours.size)
        assertTrue(snapshot.hours.none { it.time.hour == 10 || it.time.hour == 11 })
    }

    @Test
    fun `faellt ohne current-Block auf die naechste Stunde zurueck`() {
        val snapshot = UvRepository.parse(
            response(current = null, times = dayTimes, values = dayValues)
        )

        // Ohne "current" wird die Stunde genommen, die dem Zeitstempel am nächsten liegt.
        assertNotNull(snapshot.currentTime)
        assertTrue(snapshot.current >= 0.0)
        assertEquals(24, snapshot.hours.size)
    }

    @Test(expected = IOException::class)
    fun `meldet Fehler der Schnittstelle`() {
        UvRepository.parse("""{"error":true,"reason":"Latitude must be in range of -90 to 90"}""")
    }

    @Test(expected = IOException::class)
    fun `meldet fehlende Vorhersage`() {
        UvRepository.parse(
            """{"utc_offset_seconds":7200,"timezone":"Europe/Vienna",
               "current":{"time":"2026-08-11T12:15","uv_index":3.0},
               "hourly":{"time":[],"uv_index":[]}}"""
        )
    }

    @Test
    fun `uebernimmt den Zeitstempel des gespeicherten Standes`() {
        val cachedAt = 1_760_000_000_000L
        val snapshot = UvRepository.parse(response(times = dayTimes, values = dayValues), cachedAt)

        assertEquals(cachedAt, snapshot.fetchedAt)
    }
}
