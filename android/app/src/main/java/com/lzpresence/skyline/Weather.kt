package com.lzpresence.skyline

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

data class Scene(
    val precip: String?, val thunder: Boolean, val sunny: Boolean,
    val night: Boolean, val windy: Boolean, val mood: String,
)

data class Wx(
    val place: String, val temp: Int?, val text: String, val hi: Int?, val lo: Int?,
    val keys: Set<String>, val scene: Scene,
)

class NoLocationException : Exception("Open Skyline once to set your location")

/** Same forecast logic as the web app, trimmed to what the widget shows. */
object Weather {
    private const val NWS = "https://api.weather.gov"
    private const val UA = "SkylineWeather/1.0 (personal Android widget)"

    private data class H(
        val t: Long, val ymd: String, val hour: Int, val temp: Double, val text: String,
        val pop: Double, val rh: Double?, val dew: Double?, val wind: Double, val isDay: Boolean,
    ) {
        val feels: Double get() = feelsLikeF(temp, rh, wind)
    }

    private fun get(url: String, tries: Int = 3): JSONObject {
        var last: Exception = IOException("No response")
        for (i in 0 until tries) {
            val c = URL(url).openConnection() as HttpURLConnection
            var code = -1
            try {
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Accept", "application/geo+json")
                c.connectTimeout = 15000
                c.readTimeout = 20000
                code = c.responseCode
                if (code in 200..299) return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                last = IOException("HTTP $code from ${c.url.host}")
            } catch (e: IOException) {
                last = e
            } finally {
                c.disconnect()
            }
            if (code in 400..499) break // not worth retrying
            Thread.sleep(800L * (i + 1))
        }
        throw last
    }

    fun load(ctx: Context): Wx {
        val (lat, lon) = Loc.current(ctx) ?: throw NoLocationException()
        val prefs = ctx.getSharedPreferences("skyline", Context.MODE_PRIVATE)
        val key = String.format(Locale.US, "%.3f,%.3f", lat, lon)
        if (prefs.getString("ptKey", null) != key) {
            val p = get(String.format(Locale.US, "%s/points/%.4f,%.4f", NWS, lat, lon)).getJSONObject("properties")
            val rl = p.optJSONObject("relativeLocation")?.optJSONObject("properties")
            prefs.edit()
                .putString("ptKey", key)
                .putString("hourly", p.getString("forecastHourly"))
                .putString("daily", p.getString("forecast"))
                .putString("stations", p.getString("observationStations"))
                .putString("tz", p.getString("timeZone"))
                .putString("place", if (rl != null) "${rl.optString("city")}, ${rl.optString("state")}" else "")
                .apply()
        }
        val zone = ZoneId.of(prefs.getString("tz", "America/New_York"))
        val now = System.currentTimeMillis()

        // hourly forecast
        val periods = get(prefs.getString("hourly", "")!!).getJSONObject("properties").getJSONArray("periods")
        val hours = (0 until periods.length()).map { i ->
            val p = periods.getJSONObject(i)
            val start = OffsetDateTime.parse(p.getString("startTime"))
            val z = start.atZoneSameInstant(zone)
            val tf = p.getDouble("temperature").let { if (p.optString("temperatureUnit") == "C") it * 9 / 5 + 32 else it }
            H(
                t = start.toInstant().toEpochMilli(),
                ymd = z.toLocalDate().toString(), hour = z.hour, temp = tf,
                text = p.optString("shortForecast"),
                pop = p.optJSONObject("probabilityOfPrecipitation")?.optDouble("value", 0.0)?.takeIf { !it.isNaN() } ?: 0.0,
                rh = p.optJSONObject("relativeHumidity")?.optDouble("value")?.takeIf { !it.isNaN() },
                dew = p.optJSONObject("dewpoint")?.optDouble("value")?.takeIf { !it.isNaN() }?.let { it * 9 / 5 + 32 },
                wind = windMax(p.optString("windSpeed")),
                isDay = p.optBoolean("isDaytime", true),
            )
        }.filter { it.t + 3_600_000 > now }
        if (hours.isEmpty()) throw IOException("Forecast is empty")

        // current temperature: nearest station if its reading is fresh
        var temp: Double? = hours[0].temp
        var text = hours[0].text
        try {
            val st = get(prefs.getString("stations", "")!!, 2).getJSONArray("features")
            for (i in 0 until min(3, st.length())) {
                val id = st.getJSONObject(i).getJSONObject("properties").getString("stationIdentifier")
                val o = try { get("$NWS/stations/$id/observations/latest", 1).getJSONObject("properties") } catch (e: Exception) { continue }
                val c = o.optJSONObject("temperature")?.optDouble("value") ?: Double.NaN
                val ts = try { OffsetDateTime.parse(o.getString("timestamp")).toInstant().toEpochMilli() } catch (e: Exception) { 0L }
                if (!c.isNaN() && now - ts < 3 * 3_600_000) {
                    temp = c * 9 / 5 + 32
                    o.optString("textDescription").takeIf { it.isNotBlank() }?.let { text = it }
                    break
                }
            }
        } catch (e: Exception) { /* keep forecast values */ }

        // today's high / low
        var hi: Int? = null
        var lo: Int? = null
        try {
            val dp = get(prefs.getString("daily", "")!!, 2).getJSONObject("properties").getJSONArray("periods")
            val first = dp.getJSONObject(0)
            if (first.optBoolean("isDaytime")) {
                hi = first.getInt("temperature"); lo = dp.getJSONObject(1).getInt("temperature")
            } else lo = first.getInt("temperature")
        } catch (e: Exception) { /* optional */ }

        // conditions for the rest of today (or tomorrow if it's late)
        val today = ZonedDateTime.now(zone).toLocalDate().toString()
        var span = hours.filter { it.ymd == today && it.hour < 22 }
        if (span.size < 3) {
            val tmr = hours.map { it.ymd }.firstOrNull { it != today }
            span = hours.filter { it.ymd == tmr && it.hour in 6..21 }
        }
        if (span.isEmpty()) span = hours.take(6)
        val feels = span.map { it.feels }
        val joined = span.joinToString(" ") { it.text.lowercase() }
        val feelsMin = feels.min()
        val feelsMax = feels.max()
        val popMax = span.maxOf { it.pop }
        val windMax = span.maxOf { it.wind }
        val snow = Regex("snow|sleet|ice|flurr|wintry").containsMatchIn(joined)
        val thunder = Regex("thunder|t-storm").containsMatchIn(joined)
        val sunny = span.count { it.isDay && Regex("sunny|clear").containsMatchIn(it.text.lowercase()) } >= span.size / 2.0

        val keys = mutableSetOf<String>()
        when {
            feelsMin < 15 -> keys += "coat-winter"
            feelsMin < 32 -> keys += "coat-heavy"
            feelsMin < 45 -> keys += "coat-warm"
            feelsMin < 58 -> keys += "jacket-light"
            feelsMin < 66 -> keys += "sleeves"
        }
        if (feelsMin < 25) keys += listOf("hat", "gloves", "scarf") else if (feelsMin < 38) keys += listOf("hat", "gloves")
        when {
            feelsMax >= 85 -> keys += listOf("tee", "shorts")
            feelsMax >= 74 -> keys += "tee"
            feelsMax >= 66 && feelsMin >= 58 -> keys += listOf("tee", "pants")
            feelsMin < 66 -> keys += "pants"
        }
        if (snow) keys += "boots"
        if (popMax >= 50 || thunder) keys += listOf("umbrella", "boots") else if (popMax >= 25) keys += "umbrella-maybe"
        if (windMax >= 20) keys += "wind"
        if (sunny && feelsMax >= 70) keys += "sun"

        val t = text.lowercase()
        val precip = when {
            snow && (popMax >= 30 || Regex("snow|flurr").containsMatchIn(t)) -> "snow"
            popMax >= 50 || Regex("rain|shower|drizzle|thunder").containsMatchIn(t) -> "rain"
            else -> null
        }
        val mood = when {
            feelsMin < 32 -> "cold"
            precip == null && feelsMax in 60.0..85.0 -> "happy"
            else -> "meh"
        }
        val noon = ZonedDateTime.now(zone).toLocalDate().atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val sun = sunTimes(noon, lat, lon)
        val night = sun != null && (now < sun.first || now >= sun.second)

        return Wx(
            place = prefs.getString("place", "") ?: "",
            temp = temp?.roundToInt(), text = text, hi = hi, lo = lo, keys = keys,
            scene = Scene(precip, thunder, sunny, night, windMax >= 20, mood),
        )
    }

    private fun windMax(s: String): Double =
        Regex("\\d+").findAll(s).map { it.value.toDouble() }.maxOrNull() ?: 0.0

    fun feelsLikeF(tF: Double, rh: Double?, mph: Double): Double {
        if (tF <= 50 && mph >= 3) return 35.74 + 0.6215 * tF - 35.75 * mph.pow(0.16) + 0.4275 * tF * mph.pow(0.16)
        if (tF >= 80 && rh != null) {
            return -42.379 + 2.04901523 * tF + 10.14333127 * rh - 0.22475541 * tF * rh - 6.83783e-3 * tF * tF -
                5.481717e-2 * rh * rh + 1.22874e-3 * tF * tF * rh + 8.5282e-4 * tF * rh * rh - 1.99e-6 * tF * tF * rh * rh
        }
        return tF
    }

    /** Sunrise and sunset (epoch ms) for the day containing [epochMs]; NOAA / SunCalc formulas. */
    fun sunTimes(epochMs: Long, lat: Double, lng: Double): Pair<Long, Long>? {
        val rad = PI / 180
        val dayMs = 864e5
        val j1970 = 2440588.0
        val j2000 = 2451545.0
        val e = rad * 23.4397
        val j0 = 0.0009
        val d = epochMs / dayMs - 0.5 + j1970 - j2000
        val lw = rad * -lng
        val phi = rad * lat
        val n = Math.round(d - j0 - lw / (2 * PI)).toDouble()
        val ds = j0 + lw / (2 * PI) + n
        val m = rad * (357.5291 + 0.98560028 * ds)
        val c = rad * (1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m))
        val l = m + c + rad * 102.9372 + PI
        val dec = asin(sin(e) * sin(l))
        val jNoon = j2000 + ds + 0.0053 * sin(m) - 0.0069 * sin(2 * l)
        val h = -0.833 * rad
        val cosW = (sin(h) - sin(phi) * sin(dec)) / (cos(phi) * cos(dec))
        if (cosW > 1 || cosW < -1) return null
        val w = acos(cosW)
        val a = j0 + (w + lw) / (2 * PI) + n
        val jSet = j2000 + a + 0.0053 * sin(m) - 0.0069 * sin(2 * l)
        fun fromJ(j: Double) = ((j + 0.5 - j1970) * dayMs).toLong()
        return fromJ(jNoon - (jSet - jNoon)) to fromJ(jSet)
    }
}
