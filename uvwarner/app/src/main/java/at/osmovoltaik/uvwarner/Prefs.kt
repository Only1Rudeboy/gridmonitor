package at.osmovoltaik.uvwarner

import android.content.Context

/** Einstellungen und zuletzt bekannter Zustand. */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Ab diesem UV-Index wird gewarnt (Standard 4). */
    var threshold: Int
        get() = sp.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD)
        set(value) = sp.edit().putInt(KEY_THRESHOLD, value.coerceIn(1, 11)).apply()

    var warningsEnabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Prüfintervall im Hintergrund in Minuten (mindestens 15). */
    var intervalMinutes: Int
        get() = sp.getInt(KEY_INTERVAL, DEFAULT_INTERVAL)
        set(value) = sp.edit().putInt(KEY_INTERVAL, value.coerceAtLeast(15)).apply()

    val hasLocation: Boolean get() = sp.contains(KEY_LAT) && sp.contains(KEY_LON)

    val latitude: Double? get() = readDouble(KEY_LAT)
    val longitude: Double? get() = readDouble(KEY_LON)

    var placeName: String?
        get() = sp.getString(KEY_PLACE, null)
        set(value) = sp.edit().putString(KEY_PLACE, value).apply()

    val locationUpdatedAt: Long
        get() = sp.getLong(KEY_LOC_TIME, 0L)

    fun saveLocation(lat: Double, lon: Double) {
        sp.edit()
            .putLong(KEY_LAT, java.lang.Double.doubleToRawLongBits(lat))
            .putLong(KEY_LON, java.lang.Double.doubleToRawLongBits(lon))
            .putLong(KEY_LOC_TIME, System.currentTimeMillis())
            .apply()
    }

    /** true, solange eine laufende Überschreitung schon gemeldet wurde. */
    var wasAboveThreshold: Boolean
        get() = sp.getBoolean(KEY_WAS_ABOVE, false)
        set(value) = sp.edit().putBoolean(KEY_WAS_ABOVE, value).apply()

    /** Datum (ISO), an dem zuletzt vorgewarnt wurde — max. eine Vorwarnung pro Tag. */
    var preWarnDate: String?
        get() = sp.getString(KEY_PREWARN_DATE, null)
        set(value) = sp.edit().putString(KEY_PREWARN_DATE, value).apply()

    /** Zuletzt gemessener Wert, damit die App sofort etwas anzeigen kann. */
    var lastUv: Double
        get() = readDouble(KEY_LAST_UV) ?: Double.NaN
        set(value) = sp.edit().putLong(KEY_LAST_UV, java.lang.Double.doubleToRawLongBits(value)).apply()

    var lastCheckAt: Long
        get() = sp.getLong(KEY_LAST_CHECK, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_CHECK, value).apply()

    /** Hinweis auf Hintergrund-Standort wurde schon gezeigt. */
    var backgroundHintShown: Boolean
        get() = sp.getBoolean(KEY_BG_HINT, false)
        set(value) = sp.edit().putBoolean(KEY_BG_HINT, value).apply()

    private fun readDouble(key: String): Double? =
        if (sp.contains(key)) java.lang.Double.longBitsToDouble(sp.getLong(key, 0L)) else null

    companion object {
        const val NAME = "uvwarner"
        const val DEFAULT_THRESHOLD = 4
        const val DEFAULT_INTERVAL = 60

        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INTERVAL = "interval"
        private const val KEY_LAT = "lat"
        private const val KEY_LON = "lon"
        private const val KEY_PLACE = "place"
        private const val KEY_LOC_TIME = "loc_time"
        private const val KEY_WAS_ABOVE = "was_above"
        private const val KEY_PREWARN_DATE = "prewarn_date"
        private const val KEY_LAST_UV = "last_uv"
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_BG_HINT = "bg_hint"
    }
}
