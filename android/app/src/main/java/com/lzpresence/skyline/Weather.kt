package com.lzpresence.skyline

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
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

/** Temperatures are always °F here; the widget converts for display. */
data class Wx(
    val place: String, val temp: Int?, val text: String, val hi: Int?, val lo: Int?,
    val keys: Set<String>, val scene: Scene,
)

class NoLocationException : Exception("Open Kikker Forecast once to set your location")
class HttpException(val code: Int, host: String) : IOException("HTTP $code from $host")

/**
 * Same forecast logic as the web app, trimmed to what the widget shows.
 * US: National Weather Service. Elsewhere: Open-Meteo, using the Met Office model in the UK and Ireland.
 */
object Weather {
    private const val NWS = "https://api.weather.gov"
    private const val UA = "KikkerForecast/1.0 (personal Android widget)"

    private data class H(
        val t: Long, val ymd: String, val hour: Int, val temp: Double, val text: String,
        val pop: Double, val rh: Double?, val wind: Double, val isDay: Boolean,
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
                c.setRequestProperty("Accept", "application/geo+json, application/json")
                c.connectTimeout = 15000
                c.readTimeout = 20000
                code = c.responseCode
                if (code in 200..299) return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                last = HttpException(code, c.url.host)
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
            try {
                val p = get(String.format(Locale.US, "%s/points/%.4f,%.4f", NWS, lat, lon)).getJSONObject("properties")
                val rl = p.optJSONObject("relativeLocation")?.optJSONObject("properties")
                prefs.edit()
                    .putString("ptKey", key).putString("provider", "nws")
                    .putString("hourly", p.getString("forecastHourly"))
                    .putString("daily", p.getString("forecast"))
                    .putString("stations", p.getString("observationStations"))
                    .putString("tz", p.getString("timeZone"))
                    .putString("place", if (rl != null) "${rl.optString("city")}, ${rl.optString("state")}" else "")
                    .apply()
            } catch (e: HttpException) {
                if (e.code != 404) throw e
                // outside NWS coverage
                prefs.edit().putString("ptKey", key).putString("provider", "model")
                    .putString("place", reversePlace(lat, lon) ?: "").apply()
            }
        }
        return if (prefs.getString("provider", "nws") == "model") loadModel(ctx, lat, lon) else loadNws(ctx, lat, lon)
    }

    private fun loadNws(ctx: Context, lat: Double, lon: Double): Wx {
        val prefs = ctx.getSharedPreferences("skyline", Context.MODE_PRIVATE)
        val zone = ZoneId.of(prefs.getString("tz", "America/New_York"))
        val now = System.currentTimeMillis()

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
                wind = windMax(p.optString("windSpeed")),
                isDay = p.optBoolean("isDaytime", true),
            )
        }.filter { it.t + 3_600_000 > now }
        if (hours.isEmpty()) throw IOException("Forecast is empty")

        // current temperature: nearest station if its reading is fresh
        var temp: Double = hours[0].temp
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

        var hi: Int? = null
        var lo: Int? = null
        try {
            val dp = get(prefs.getString("daily", "")!!, 2).getJSONObject("properties").getJSONArray("periods")
            val first = dp.getJSONObject(0)
            if (first.optBoolean("isDaytime")) {
                hi = first.getInt("temperature"); lo = dp.getJSONObject(1).getInt("temperature")
            } else lo = first.getInt("temperature")
        } catch (e: Exception) { /* optional */ }

        return finish(prefs.getString("place", "") ?: "", hours, temp, text, hi, lo, zone, lat, lon)
    }

    private fun loadModel(ctx: Context, lat: Double, lon: Double): Wx {
        val prefs = ctx.getSharedPreferences("skyline", Context.MODE_PRIVATE)
        val uk = lat > 49.8 && lat < 61 && lon > -11 && lon < 2
        val vars = "temperature_2m,relative_humidity_2m,precipitation_probability,precipitation,weather_code,wind_speed_10m,wind_gusts_10m,is_day"
        val url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f", lat, lon) +
            (if (uk) "&models=ukmo_seamless" else "") +
            "&timezone=auto&timeformat=unixtime&forecast_days=3&current=temperature_2m,weather_code,is_day&hourly=$vars" +
            "&daily=temperature_2m_max,temperature_2m_min"
        val f = get(url)
        val zone = ZoneId.of(f.optString("timezone", "UTC"))
        val now = System.currentTimeMillis()
        val h = f.getJSONObject("hourly")
        val time = h.getJSONArray("time")
        fun arr(name: String): JSONArray? = h.optJSONArray(name)
        val tempA = arr("temperature_2m")!!
        val hours = (0 until time.length()).map { i ->
            val t = time.getLong(i) * 1000
            val z = Instant.ofEpochMilli(t).atZone(zone)
            val pop = arr("precipitation_probability")?.let { if (it.isNull(i)) null else it.getDouble(i) }
                ?: arr("precipitation")?.let { if (it.isNull(i)) 0.0 else if (it.getDouble(i) >= 0.5) 70.0 else if (it.getDouble(i) > 0) 35.0 else 0.0 } ?: 0.0
            val day = arr("is_day")?.optInt(i, 1) == 1
            H(
                t = t, ymd = z.toLocalDate().toString(), hour = z.hour,
                temp = tempA.optDouble(i) * 9 / 5 + 32,
                text = wmoText(arr("weather_code")?.optInt(i, 3) ?: 3, day),
                pop = pop,
                rh = arr("relative_humidity_2m")?.optDouble(i)?.takeIf { !it.isNaN() },
                wind = (arr("wind_speed_10m")?.optDouble(i) ?: 0.0).let { if (it.isNaN()) 0.0 else it * 0.621371 },
                isDay = day,
            )
        }.filter { it.t + 3_600_000 > now }
        if (hours.isEmpty()) throw IOException("Forecast is empty")
        val cur = f.optJSONObject("current")
        val temp = cur?.optDouble("temperature_2m")?.takeIf { !it.isNaN() }?.let { it * 9 / 5 + 32 } ?: hours[0].temp
        val text = cur?.let { wmoText(it.optInt("weather_code", 3), it.optInt("is_day", 1) == 1) } ?: hours[0].text
        val d = f.optJSONObject("daily")
        val hi = d?.optJSONArray("temperature_2m_max")?.optDouble(0)?.takeIf { !it.isNaN() }?.let { (it * 9 / 5 + 32).roundToInt() }
        val lo = d?.optJSONArray("temperature_2m_min")?.optDouble(1)?.takeIf { !it.isNaN() }?.let { (it * 9 / 5 + 32).roundToInt() }
        return finish(prefs.getString("place", "") ?: "", hours, temp, text, hi, lo, zone, lat, lon)
    }

    /** Clothing keys and Kikker's scene for the rest of today (or tomorrow if it's late). */
    private fun finish(
        place: String, hours: List<H>, temp: Double, text: String, hi: Int?, lo: Int?,
        zone: ZoneId, lat: Double, lon: Double,
    ): Wx {
        val now = System.currentTimeMillis()
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
            place = place, temp = temp.roundToInt(), text = text, hi = hi, lo = lo, keys = keys,
            scene = Scene(precip, thunder, sunny, night, windMax >= 20, mood),
        )
    }

    private fun reversePlace(lat: Double, lon: Double): String? = try {
        val j = get(String.format(Locale.US, "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=%.4f&longitude=%.4f&localityLanguage=en", lat, lon), 1)
        val town = listOf("city", "locality", "principalSubdivision").map { j.optString(it) }.firstOrNull { it.isNotBlank() }
        val country = j.optString("countryName").let { if (it.contains("United Kingdom")) "UK" else it }
        listOfNotNull(town, country.takeIf { it.isNotBlank() }).joinToString(", ")
    } catch (e: Exception) { null }

    private fun wmoText(code: Int, day: Boolean): String = when (code) {
        0 -> if (day) "Sunny" else "Clear"
        1 -> if (day) "Mostly Sunny" else "Mostly Clear"
        2 -> "Partly Cloudy"
        3 -> "Cloudy"
        45 -> "Fog"
        48 -> "Freezing Fog"
        51 -> "Light Drizzle"
        53 -> "Drizzle"
        55 -> "Heavy Drizzle"
        56, 57 -> "Freezing Drizzle"
        61 -> "Light Rain"
        63 -> "Rain"
        65 -> "Heavy Rain"
        66, 67 -> "Freezing Rain"
        71 -> "Light Snow"
        73 -> "Snow"
        75 -> "Heavy Snow"
        77 -> "Snow Grains"
        80 -> "Light Rain Showers"
        81 -> "Rain Showers"
        82 -> "Heavy Rain Showers"
        85 -> "Snow Showers"
        86 -> "Heavy Snow Showers"
        95 -> "Thunderstorms"
        96, 99 -> "Thunderstorms with Hail"
        else -> "Cloudy"
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
