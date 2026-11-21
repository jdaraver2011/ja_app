package com.example.ja

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Public metadata only; preview URLs are deliberately never read. */
object JaMusicCatalog {
    data class Track(val id: String, val title: String, val artist: String, val album: String, val art: String, val seconds: Long)
    private val knownTracks = object : LinkedHashMap<String, Track>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track>?): Boolean = size > 500
    }
    @Synchronized fun remember(tracks: List<Track>) { tracks.forEach { knownTracks[it.id] = it } }
    @Synchronized fun known(id: String): Track = knownTracks[id] ?: Track(id, "Track $id", "Deezer", "", "", 0)
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    fun tracks(query: String = ""): List<Track> {
        val url = (if (query.isBlank()) "https://api.deezer.com/chart/0/tracks" else "https://api.deezer.com/search/track")
            .toHttpUrl().newBuilder().addQueryParameter("limit", "50")
            .apply { if (query.isNotBlank()) addQueryParameter("q", query.take(200)) }.build()
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw JaMusicLabFailure("CATALOG_HTTP_${response.code}")
                val result = JSONObject(response.body?.string().orEmpty())
                if (result.has("error")) throw JaMusicLabFailure("CATALOG_UNAVAILABLE")
                val items = result.optJSONArray("data") ?: throw JaMusicLabFailure("CATALOG_RESPONSE")
                val tracks = (0 until items.length()).mapNotNull { i ->
                    val track = items.optJSONObject(i) ?: return@mapNotNull null
                    val id = track.optString("id")
                    if (JaMusicLabService.parseTrack(id) != id) return@mapNotNull null
                    val album = track.optJSONObject("album")
                    Track(id, track.optString("title"), track.optJSONObject("artist")?.optString("name").orEmpty(),
                        album?.optString("title").orEmpty(), album?.optString("cover_medium").orEmpty(), track.optLong("duration"))
                }
                remember(tracks)
                return tracks
            }
        } catch (e: JaMusicLabFailure) { throw e }
        catch (_: Exception) { throw JaMusicLabFailure("CATALOG_NETWORK_OR_RESPONSE") }
    }
}
