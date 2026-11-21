package com.example.ja

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

internal data class JaWeatherAlert(
    val event: String, val severity: String, val headline: String,
    val area: String, val description: String, val instruction: String, val expires: String
)
internal data class JaForecastPeriod(val name: String, val start: String, val end: String, val day: Boolean,
    val temperature: String, val description: String, val icon: String?, val rainChance: String)
internal data class JaWeatherData(
    val zip: String, val place: String, val station: String, val observationTime: String,
    val temperature: String, val conditions: String, val icon: String?,
    val humidity: String, val dewpoint: String, val ceiling: String, val visibility: String,
    val pressure: String, val windChill: String, val wind: String, val alerts: List<JaWeatherAlert>,
    val fetchedAt: Long, val latitude: Double = Double.NaN, val longitude: Double = Double.NaN,
    val forecast: List<JaForecastPeriod> = emptyList(), val forecastAvailable: Boolean = false, val stale: Boolean = false, val alertsAvailable: Boolean = true
)

/** Small independent client: no TMDB/player work, API keys or location permission. */
internal class JaWeatherRepository(context: Context) {
    private val prefs = context.getSharedPreferences("ja_weather", Context.MODE_PRIVATE)
    private val client = http(context)
    companion object {
        @Volatile private var sharedClient: OkHttpClient? = null
        @Synchronized private fun http(context: Context): OkHttpClient = sharedClient ?: OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, "nws-http"), 4L * 1024 * 1024))
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).build().also { sharedClient = it }
    }
    fun weatherStar()=prefs.getBoolean("weatherstar",false)
    fun saveWeatherStar(value:Boolean){prefs.edit().putBoolean("weatherstar",value).apply()}
    fun sound()=prefs.getBoolean("weatherstar-sound",true)
    fun saveSound(value:Boolean){prefs.edit().putBoolean("weatherstar-sound",value).apply()}
    fun volume()=prefs.getFloat("weatherstar-volume",0.5f)
    fun saveVolume(value:Float){prefs.edit().putFloat("weatherstar-volume",value).apply()}
    fun musicChoice()=prefs.getString("music-choice","sunny")?:"sunny"
    fun saveMusicChoice(value:String){prefs.edit().putString("music-choice",value).apply()}
    fun customMusic()=prefs.getString("custom-music","")?:""
    fun saveCustomMusic(value:String){prefs.edit().putString("custom-music",value).apply()}
    fun savedZip(): String = prefs.getString("zip", "") ?: ""
    fun saveZip(zip: String) { prefs.edit().putString("zip", zip).apply() }
    private fun get(url: String, force: Boolean = false): JSONObject {
        val uri = java.net.URI(url)
        require(uri.scheme == "https" && uri.host in setOf("api.weather.gov", "api.zippopotam.us"))
        val builder = Request.Builder().url(url)
            .header("User-Agent", "JA/2.3.5 (https://github.com/jdaraver2011/ja_app)")
            .header("Accept", "application/geo+json, application/json")
        if (force) builder.header("Cache-Control", "no-cache")
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Weather service returned ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty weather response")
            return JSONObject(body)
        }
    }
    private fun quantity(p: JSONObject, key: String): Pair<Double?, String> {
        val q = p.optJSONObject(key) ?: return null to ""
        val v = if (q.isNull("value")) null else q.optDouble("value").takeIf { it.isFinite() }
        return v to q.optString("unitCode")
    }
    private fun field(p: JSONObject, key: String, convert: (Double?, String) -> Double?): Double? {
        val (value, unit) = quantity(p, key)
        return convert(value, unit)
    }
    private fun current(p: JSONObject): Boolean = runCatching {
        val time = Instant.parse(p.optString("timestamp"))
        val age = Instant.now().epochSecond - time.epochSecond
        age in -300L..10800L && field(p, "temperature", JaWeatherRules::fahrenheit) != null
    }.getOrDefault(false)
    suspend fun load(zip: String, force: Boolean): JaWeatherData = withContext(Dispatchers.IO) {
        require(JaWeatherRules.validZip(zip))
        // Persistent raw responses are keyed by ZIP; changing location cannot show the old city.
        val key = "snapshot_$zip"
        val cached = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val cachedData = cached?.let { runCatching { parse(zip, it) }.getOrNull() }
        if (!force && cachedData != null && cached?.has("forecast") == true && JaWeatherRules.cacheFresh(cachedData.fetchedAt, System.currentTimeMillis())) {
            return@withContext cachedData
        }
        try {
            val locationKey = "location_$zip"
            val saved = prefs.getString(locationKey, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            val location = saved ?: run {
                val result = get("https://api.zippopotam.us/us/$zip")
                val place = result.getJSONArray("places").getJSONObject(0)
                JSONObject().put("lat", place.getString("latitude")).put("lon", place.getString("longitude"))
                    .put("place", "${place.getString("place name")}, ${place.getString("state abbreviation")}")
                    .also { prefs.edit().putString(locationKey, it.toString()).apply() }
            }
            val lat = location.getString("lat").toDouble()
            val lon = location.getString("lon").toDouble()
            require(lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0)
            val point = "$lat,$lon"
            val points = get("https://api.weather.gov/points/$point", force).getJSONObject("properties")
            val stations = get(points.getString("observationStations"), force).getJSONArray("features")
            var observation: JSONObject? = null
            var stationName = ""
            // Some nearby stations have missing or old observations; try up to three.
            for (i in 0 until minOf(3, stations.length())) {
                val station = stations.getJSONObject(i).getJSONObject("properties")
                val id = station.getString("stationIdentifier")
                if (!id.matches(Regex("[A-Za-z0-9_-]+"))) continue
                val candidate = runCatching { get("https://api.weather.gov/stations/$id/observations/latest", true)
                    .getJSONObject("properties") }.getOrNull() ?: continue
                if (observation == null) { observation = candidate; stationName = station.optString("name", id) }
                if (current(candidate) && (!current(observation!!) || runCatching {
                    Instant.parse(candidate.optString("timestamp")).isAfter(Instant.parse(observation!!.optString("timestamp")))
                }.getOrDefault(false))) { observation = candidate; stationName = station.optString("name", id) }
            }
            val obs = observation ?: throw IOException("No nearby station observations available")
            val alerts = runCatching { get("https://api.weather.gov/alerts/active?point=$point", true) }.getOrNull()
            val forecast = runCatching { get(points.getString("forecast"), true) }.getOrNull()
            val raw = JSONObject().put("latitude", lat).put("longitude", lon)
                .put("forecast", forecast ?: cached?.optJSONObject("forecast") ?: JSONObject())
                .put("forecastAvailable", forecast != null)
                .put("place", location.getString("place")).put("station", stationName)
                .put("observation", obs).put("alerts", alerts ?: JSONObject())
                .put("alertsAvailable", alerts != null).put("fetchedAt", System.currentTimeMillis())
            prefs.edit().putString(key, raw.toString()).apply()
            parse(zip, raw)
        } catch (e: Exception) {
            if (cachedData != null) cachedData.copy(stale = true)
            else throw IOException("Weather unavailable. Check the ZIP code, connection, and NWS coverage.", e)
        }
    }
    private fun parse(zip: String, raw: JSONObject): JaWeatherData {
        val p = raw.getJSONObject("observation")
        fun format(key: String, digits: Int, suffix: String, convert: (Double?, String) -> Double?) =
            JaWeatherRules.number(field(p, key, convert), digits, suffix)
        val layers = p.optJSONArray("cloudLayers")
        val bases = (0 until (layers?.length() ?: 0)).mapNotNull { i ->
            val layer = layers!!.getJSONObject(i)
            if (layer.optString("amount") !in listOf("BKN", "OVC", "VV")) null
            else field(layer, "base") { v, u -> JaWeatherRules.distance(v, u, true) }
        }
        val features = raw.optJSONObject("alerts")?.optJSONArray("features")
        val alerts = (0 until (features?.length() ?: 0)).mapNotNull { i ->
            val a = features!!.getJSONObject(i).optJSONObject("properties") ?: return@mapNotNull null
            val expires = a.optString("expires")
            if (!JaWeatherRules.activeActualAlert(a.optString("status"), a.optString("messageType"),
                    expires, System.currentTimeMillis())) return@mapNotNull null
            JaWeatherAlert(a.optString("event"), a.optString("severity"), a.optString("headline"),
                a.optString("areaDesc"), a.optString("description"), a.optString("instruction"), expires)
        }.sortedBy { listOf("Extreme", "Severe", "Moderate", "Minor", "Unknown").indexOf(it.severity).let { n -> if (n < 0) 5 else n } }
        val pressure = field(p, "seaLevelPressure", JaWeatherRules::inchesMercury)
            ?: field(p, "barometricPressure", JaWeatherRules::inchesMercury)
        val (humidity, humidityUnit) = quantity(p, "relativeHumidity")
        val (direction, _) = quantity(p, "windDirection")
        val speed = field(p, "windSpeed", JaWeatherRules::milesPerHour)
        val wind = if (speed == null) "—" else if (speed == 0.0) "Calm" else "${JaWeatherRules.direction(direction)} ${JaWeatherRules.number(speed, 0, " mph")}"
        val forecastItems = raw.optJSONObject("forecast")?.optJSONObject("properties")?.optJSONArray("periods")
        val forecast = (0 until (forecastItems?.length() ?: 0)).mapNotNull { i ->
            val f = forecastItems!!.getJSONObject(i)
            if (runCatching { Instant.parse(f.getString("endTime")).isBefore(Instant.now()) }.getOrDefault(true)) return@mapNotNull null
            val chance = f.optJSONObject("probabilityOfPrecipitation")
            JaForecastPeriod(f.optString("name"), f.optString("startTime"), f.optString("endTime"), f.optBoolean("isDaytime"),
                "${f.optInt("temperature")}°${f.optString("temperatureUnit")}", f.optString("shortForecast"),
                f.optString("icon").takeIf { it.startsWith("https://api.weather.gov/") },
                if (chance == null || chance.isNull("value")) "" else "${chance.optInt("value")}%")
        }
        val location = prefs.getString("location_$zip", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        return JaWeatherData(zip, raw.getString("place"), raw.optString("station"), p.optString("timestamp"),
            format("temperature", 0, "°F", JaWeatherRules::fahrenheit), p.optString("textDescription").ifBlank { "Conditions unavailable" },
            p.optString("icon").takeIf { it.startsWith("https://api.weather.gov/") },
            JaWeatherRules.number(humidity.takeIf { humidityUnit.endsWith(":percent") }, 0, "%"),
            format("dewpoint", 0, "°F", JaWeatherRules::fahrenheit), JaWeatherRules.number(bases.minOrNull(), 0, " ft"),
            format("visibility", 1, " mi") { v, u -> JaWeatherRules.distance(v, u, false) },
            JaWeatherRules.number(pressure, 2, " inHg"), format("windChill", 0, "°F", JaWeatherRules::fahrenheit),
            wind, alerts, raw.optLong("fetchedAt"),
            latitude = raw.optDouble("latitude", location?.optString("lat")?.toDoubleOrNull() ?: Double.NaN),
            longitude = raw.optDouble("longitude", location?.optString("lon")?.toDoubleOrNull() ?: Double.NaN),
            forecast = forecast, forecastAvailable = raw.optBoolean("forecastAvailable"), stale = !current(p), alertsAvailable = raw.optBoolean("alertsAvailable", false))
    }
}
