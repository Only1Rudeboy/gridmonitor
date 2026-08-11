package at.osmovoltaik.uvwarner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class UvWarningLogicTest {

    private val today = "2026-08-11"

    private fun at(hour: String) = LocalDateTime.parse("${today}T$hour")

    private fun snapshot(
        current: Double,
        hours: List<Pair<String, Double>>,
        fetchedAt: Long = System.currentTimeMillis()
    ) = UvSnapshot(
        current = current,
        currentTime = at(hours.first().first),
        utcOffsetSeconds = 7200,
        timezone = "Europe/Vienna",
        hours = hours.map { (time, uv) -> HourUv(at(time), uv) },
        fetchedAt = fetchedAt
    )

    private val sunnyDay = listOf(
        "08:00" to 1.4, "09:00" to 2.6, "10:00" to 4.2, "11:00" to 5.8,
        "12:00" to 6.9, "13:00" to 7.1, "14:00" to 6.2, "15:00" to 4.4,
        "16:00" to 2.8, "17:00" to 1.3
    )

    @Test
    fun `warnt wenn die Schwelle erreicht ist`() {
        val verdict = UvWarningLogic.decide(
            snapshot = snapshot(5.8, sunnyDay),
            threshold = 4,
            now = at("11:10"),
            wasAboveThreshold = false,
            preWarnDate = null
        )

        val decision = verdict.decision as UvDecision.Now
        assertEquals(5.8, decision.uv, 0.001)
        // Bis 15:00 liegt der Wert über 4 — die Meldung nennt das Ende.
        assertEquals(at("15:00"), decision.until?.time)
        assertTrue(verdict.wasAboveThreshold)
    }

    @Test
    fun `warnt nicht zweimal in derselben Ueberschreitung`() {
        val verdict = UvWarningLogic.decide(
            snapshot = snapshot(6.9, sunnyDay),
            threshold = 4,
            now = at("12:10"),
            wasAboveThreshold = true,
            preWarnDate = null
        )

        assertEquals(UvDecision.Nothing, verdict.decision)
        assertTrue(verdict.wasAboveThreshold)
    }

    @Test
    fun `setzt den Zustand zurueck wenn der Wert wieder faellt`() {
        val verdict = UvWarningLogic.decide(
            snapshot = snapshot(1.3, sunnyDay),
            threshold = 4,
            now = at("17:10"),
            wasAboveThreshold = true,
            preWarnDate = today
        )

        assertEquals(UvDecision.Nothing, verdict.decision)
        assertFalse(verdict.wasAboveThreshold)
    }

    @Test
    fun `warnt vor wenn die Schwelle in Kuerze erreicht wird`() {
        val verdict = UvWarningLogic.decide(
            snapshot = snapshot(2.6, sunnyDay),
            threshold = 4,
            now = at("09:10"),
            wasAboveThreshold = false,
            preWarnDate = null
        )

        val decision = verdict.decision as UvDecision.Soon
        assertEquals(at("10:00"), decision.at.time)
        assertEquals(7.1, decision.peak?.uv ?: 0.0, 0.001)
        assertEquals(today, verdict.preWarnDate)
        assertFalse(verdict.wasAboveThreshold)
    }

    @Test
    fun `warnt hoechstens einmal taeglich vor`() {
        val verdict = UvWarningLogic.decide(
            snapshot = snapshot(2.6, sunnyDay),
            threshold = 4,
            now = at("09:10"),
            wasAboveThreshold = false,
            preWarnDate = today
        )

        assertEquals(UvDecision.Nothing, verdict.decision)
        assertEquals(today, verdict.preWarnDate)
    }

    @Test
    fun `warnt nicht Stunden im Voraus`() {
        // Um 06:00 liegt die Ueberschreitung um 10:00 noch zu weit weg.
        val verdict = UvWarningLogic.decide(
            snapshot = snapshot(0.2, sunnyDay),
            threshold = 4,
            now = at("06:00"),
            wasAboveThreshold = false,
            preWarnDate = null
        )

        assertEquals(UvDecision.Nothing, verdict.decision)
        assertNull(verdict.preWarnDate)
    }

    @Test
    fun `hoehere Schwelle verschiebt die Vorwarnung`() {
        val verdict = UvWarningLogic.decide(
            snapshot = snapshot(4.2, sunnyDay),
            threshold = 7,
            now = at("10:10"),
            wasAboveThreshold = false,
            preWarnDate = null
        )

        val decision = verdict.decision as UvDecision.Soon
        assertEquals(at("13:00"), decision.at.time)
    }

    @Test
    fun `spart den Abruf wenn nachts nichts zu erwarten ist`() {
        val night = snapshot(
            current = 0.0,
            hours = listOf("20:00" to 0.0, "21:00" to 0.0, "22:00" to 0.0, "23:00" to 0.0),
            fetchedAt = System.currentTimeMillis()
        )

        assertTrue(UvWarningLogic.canSkipFetch(night, at("20:10"), cacheAgeMillis = 0L))
    }

    @Test
    fun `ruft ab sobald wieder Sonne vorhergesagt ist`() {
        val morning = snapshot(
            current = 0.0,
            hours = listOf("06:00" to 0.0, "07:00" to 0.4, "08:00" to 1.4),
            fetchedAt = System.currentTimeMillis()
        )

        assertFalse(UvWarningLogic.canSkipFetch(morning, at("06:10"), cacheAgeMillis = 0L))
    }

    @Test
    fun `ruft ab wenn die gespeicherte Vorhersage zu alt ist`() {
        val night = snapshot(
            current = 0.0,
            hours = listOf("20:00" to 0.0, "21:00" to 0.0, "22:00" to 0.0, "23:00" to 0.0)
        )
        val twoDays = 48L * 60L * 60L * 1000L

        assertFalse(UvWarningLogic.canSkipFetch(night, at("20:10"), cacheAgeMillis = twoDays))
        assertFalse(UvWarningLogic.canSkipFetch(null, at("20:10"), cacheAgeMillis = 0L))
    }

    @Test
    fun `ruft ab wenn die Vorhersage nicht weit genug reicht`() {
        val short = snapshot(
            current = 0.0,
            hours = listOf("22:00" to 0.0, "23:00" to 0.0)
        )

        assertFalse(UvWarningLogic.canSkipFetch(short, at("22:10"), cacheAgeMillis = 0L))
    }
}
