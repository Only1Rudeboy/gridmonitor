package at.osmovoltaik.uvwarner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.util.Locale

/**
 * Holt den UV-Index von Open-Meteo (frei nutzbar, kein API-Schlüssel,
 * https://open-meteo.com — Datenquelle CAMS/ECMWF).
 */
object UvRepository {

    private const val ENDPOINT = "https://api.open-meteo.com/v1/forecast"
    private const val TIMEOUT_MS = 20_000

    @Throws(IOException::class)
    suspend fun fetch(latitude: Double, longitude: Double): UvSnapshot = withContext(Dispatchers.IO) {
        val url = URL(
            String.format(
                Locale.US,
                "%s?latitude=%.4f&longitude=%.4f&current=uv_index&hourly=uv_index&forecast_days=2&timezone=auto",
                ENDPOINT, latitude, longitude
            )
        )

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "UV-Warner/1.0 (Android)")
        }

        val body = try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("Open-Meteo antwortete mit HTTP $code")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }

        parse(body)
    }

    internal fun parse(body: String): UvSnapshot {
        val root = JSONObject(body)
        if (root.optBoolean("error", false)) {
            throw IOException(root.optString("reason", "Unbekannter Fehler der UV-Schnittstelle"))
        }

        val utcOffset = root.optInt("utc_offset_seconds", 0)
        val timezone = root.optString("timezone", "")

        val hours = mutableListOf<HourUv>()
        root.optJSONObject("hourly")?.let { hourly ->
            val times = hourly.optJSONArray("time")
            val values = hourly.optJSONArray("uv_index")
            if (times != null && values != null) {
                for (i in 0 until minOf(times.length(), values.length())) {
                    if (values.isNull(i)) continue
                    val time = parseLocal(times.optString(i)) ?: continue
                    hours += HourUv(time, values.optDouble(i, 0.0))
                }
            }
        }
        hours.sortBy { it.time }

        val currentObj = root.optJSONObject("current")
        val currentTime = parseLocal(currentObj?.optString("time"))
            ?: LocalDateTime.ofInstant(
                java.time.Instant.now(),
                java.time.ZoneOffset.ofTotalSeconds(utcOffset)
            )
        val currentUv = currentObj
            ?.takeIf { !it.isNull("uv_index") }
            ?.optDouble("uv_index")
            ?.takeIf { !it.isNaN() }
            ?: nearestHourValue(hours, currentTime)
            ?: throw IOException("Keine UV-Daten für diesen Standort erhalten")

        if (hours.isEmpty()) {
            throw IOException("Keine UV-Vorhersage für diesen Standort erhalten")
        }

        return UvSnapshot(
            current = currentUv.coerceAtLeast(0.0),
            currentTime = currentTime,
            utcOffsetSeconds = utcOffset,
            timezone = timezone,
            hours = hours
        )
    }

    private fun nearestHourValue(hours: List<HourUv>, at: LocalDateTime): Double? =
        hours.minByOrNull { kotlin.math.abs(java.time.Duration.between(it.time, at).toMinutes()) }?.uv

    private fun parseLocal(value: String?): LocalDateTime? {
        if (value.isNullOrBlank()) return null
        return try {
            LocalDateTime.parse(value)
        } catch (e: Exception) {
            null
        }
    }
}
