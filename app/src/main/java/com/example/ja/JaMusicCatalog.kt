package com.example.ja

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Public metadata only. All paging stays on the Deezer API; no preview audio is used. */
object JaMusicCatalog {
    data class Track(val id: String, val title: String, val artist: String, val album: String, val art: String, val seconds: Long,
        val artistId: String = "", val albumId: String = "")
    data class Card(val id: String, val title: String, val subtitle: String, val art: String, val kind: String)
    data class Page(val tracks: List<Track> = emptyList(), val cards: List<Card> = emptyList(), val next: String = "")
    private val knownTracks = object : LinkedHashMap<String, Track>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track>?): Boolean = size > 2000
    }
    @Synchronized fun remember(tracks: List<Track>) { tracks.forEach { knownTracks[it.id] = it } }
    @Synchronized fun known(id: String): Track = knownTracks[id] ?: Track(id, "Track $id", "Deezer", "", "", 0)
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val cache = object : LinkedHashMap<String, Pair<Long, JSONObject>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, JSONObject>>?) = size > 64
    }
    private fun request(path: String): JSONObject {
        val url = (if (path.startsWith("https://")) path else "https://api.deezer.com/$path").toHttpUrl()
        require(url.isHttps && url.host == "api.deezer.com" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty())
        synchronized(cache) { cache[url.toString()]?.let { if (android.os.SystemClock.elapsedRealtime() - it.first < 300000) return it.second } }
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw JaMusicLabFailure("CATALOG_HTTP_${response.code}")
                val result = JSONObject(response.body?.string().orEmpty())
                if (result.has("error")) throw JaMusicLabFailure("CATALOG_UNAVAILABLE")
                synchronized(cache) { cache[url.toString()] = android.os.SystemClock.elapsedRealtime() to result }
                return result
            }
        } catch (e: JaMusicLabFailure) { throw e }
        catch (_: Exception) { throw JaMusicLabFailure("CATALOG_NETWORK_OR_RESPONSE") }
    }
    internal fun track(o: JSONObject, fallbackAlbum: JSONObject? = null): Track? {
        val id = o.optString("id")
        if (!Regex("[1-9][0-9]{0,18}").matches(id)) return null
        val album = o.optJSONObject("album") ?: fallbackAlbum
        val artist = o.optJSONObject("artist")
        return Track(id, o.optString("title"), artist?.optString("name").orEmpty(), album?.optString("title").orEmpty(),
            album?.optString("cover_medium").orEmpty(), o.optLong("duration"), artist?.optString("id").orEmpty(), album?.optString("id").orEmpty())
    }
    private fun card(o: JSONObject, kind: String): Card = Card(o.optString("id"), o.optString(if (kind in listOf("artist", "genre")) "name" else "title"),
        o.optJSONObject("artist")?.optString("name") ?: o.optJSONObject("user")?.optString("name") ?: o.optString("release_date"),
        o.optString(if (kind in listOf("artist", "genre", "playlist")) "picture_medium" else "cover_medium"), kind)
    fun page(path: String, kind: String = "track", fallbackAlbum: JSONObject? = null): Page {
        val result = request(path)
        val items = result.optJSONArray("data") ?: return Page()
        val objects = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
        val tracks = if (kind == "track") objects.mapNotNull { track(it, fallbackAlbum) } else emptyList()
        remember(tracks)
        return Page(tracks, if (kind == "track") emptyList() else objects.map { card(it, kind) }, result.optString("next"))
    }
    fun detail(id: String, kind: String): JSONObject { require(Regex("[0-9]+").matches(id) && kind in listOf("artist", "album", "playlist")); return request("$kind/$id") }
    fun search(query: String, kind: String): Page {
        require(kind in listOf("track", "artist", "album", "playlist"))
        val path = "https://api.deezer.com/search/$kind".toHttpUrl().newBuilder().addQueryParameter("q", query.take(200)).addQueryParameter("limit", "50").build()
        return page(path.toString(), kind)
    }
    fun tracks(query: String = ""): List<Track> = if (query.isBlank()) page("chart/0/tracks?limit=50").tracks else search(query, "track").tracks
}
