package com.example.ja

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Private JA library. No writes to the Deezer account. */
internal class JaMusicLibrary(context: Context) {
    data class Playlist(val id: String, val name: String, val tracks: List<JaMusicCatalog.Track>)
    data class SearchEntry(val query: String, val kind: String, val id: String, val title: String,
        val subtitle: String, val art: String)
    private val prefs = context.applicationContext.getSharedPreferences("ja_music_library", Context.MODE_PRIVATE)
    val changes = MutableStateFlow(0)
    private fun notifyChange() { changes.value++ }
    private fun encode(t: JaMusicCatalog.Track) = JSONObject().put("id", t.id).put("title", t.title).put("artist", t.artist)
        .put("album", t.album).put("art", t.art).put("seconds", t.seconds).put("artistId", t.artistId).put("albumId", t.albumId)
    private fun decode(o: JSONObject) = JaMusicCatalog.Track(o.getString("id"), o.optString("title"), o.optString("artist"), o.optString("album"),
        o.optString("art"), o.optLong("seconds"), o.optString("artistId"), o.optString("albumId"))
    private fun tracks(a: JSONArray): List<JaMusicCatalog.Track> = (0 until a.length()).mapNotNull { runCatching { decode(a.getJSONObject(it)) }.getOrNull() }
    private fun json(key: String) = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrElse { JSONArray() }
    fun playlists(): List<Playlist> = json("playlists").let { a -> (0 until a.length()).mapNotNull { i -> runCatching {
        val o = a.getJSONObject(i); Playlist(o.getString("id"), o.getString("name"), tracks(o.getJSONArray("tracks"))) }.getOrNull() } }
    private fun save(rows: List<Playlist>) {
        prefs.edit().putString("playlists", JSONArray().apply { rows.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name)
            .put("tracks", JSONArray().apply { p.tracks.forEach { put(encode(it)) } })) } }.toString()).apply(); notifyChange()
    }
    fun create(name: String): String { val id = UUID.randomUUID().toString(); save(playlists() + Playlist(id, name.trim().take(80).ifBlank { "My playlist" }, emptyList())); return id }
    fun rename(id: String, name: String) { if (name.isNotBlank()) save(playlists().map { if (it.id == id) it.copy(name = name.trim().take(80)) else it }) }
    fun delete(id: String) = save(playlists().filterNot { it.id == id })
    fun add(id: String, track: JaMusicCatalog.Track) = save(playlists().map { if (it.id == id && it.tracks.none { t -> t.id == track.id }) it.copy(tracks = it.tracks + track) else it })
    fun remove(id: String, track: String) = save(playlists().map { if (it.id == id) it.copy(tracks = it.tracks.filterNot { t -> t.id == track }) else it })
    fun move(id: String, track: String, delta: Int) = save(playlists().map { p -> if (p.id != id) p else {
        val rows = p.tracks.toMutableList(); val from = rows.indexOfFirst { it.id == track }; val to = (from + delta).coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        if (from >= 0) rows.add(to, rows.removeAt(from)); p.copy(tracks = rows) } })
    fun liked(): List<JaMusicCatalog.Track> = tracks(json("liked"))
    fun toggleLike(t: JaMusicCatalog.Track) {
        val rows = liked(); val next = if (rows.any { it.id == t.id }) rows.filterNot { it.id == t.id } else rows + t
        prefs.edit().putString("liked", JSONArray().apply { next.forEach { put(encode(it)) } }.toString()).apply(); notifyChange()
    }
    fun saved(): List<JaMusicCatalog.Card> = json("saved").let { a -> (0 until a.length()).mapNotNull { i -> runCatching { val o = a.getJSONObject(i)
        JaMusicCatalog.Card(o.getString("id"), o.getString("title"), o.optString("subtitle"), o.optString("art"), o.getString("kind")) }.getOrNull() } }
    fun recentSearches(): List<SearchEntry> = json("recent_searches").let { a -> (0 until a.length()).mapNotNull { i -> runCatching {
        val o = a.getJSONObject(i); SearchEntry(o.getString("query"), o.getString("kind"), o.optString("id"),
            o.optString("title"), o.optString("subtitle"), o.optString("art")) }.getOrNull() } }
    fun rememberSearch(query: String, kind: String, card: JaMusicCatalog.Card? = null, track: JaMusicCatalog.Track? = null) {
        val value = query.trim().take(200)
        if (value.isBlank() || kind !in listOf("track", "artist", "album", "playlist")) return
        val entry = SearchEntry(value, kind, card?.id ?: track?.artistId.orEmpty(), value,
            card?.subtitle?.takeIf { it.isNotBlank() } ?: kind.replaceFirstChar { it.uppercase() }, card?.art ?: track?.art.orEmpty())
        val rows = (listOf(entry) + recentSearches().filterNot { it.query.equals(value, true) && it.kind == kind }).take(10)
        prefs.edit().putString("recent_searches", JSONArray().apply { rows.forEach { row -> put(JSONObject()
            .put("query", row.query).put("kind", row.kind).put("id", row.id).put("title", row.title)
            .put("subtitle", row.subtitle).put("art", row.art)) } }.toString()).apply()
        notifyChange()
    }
    fun removeRecentSearch(query: String, kind: String) {
        val rows = recentSearches().filterNot { it.query == query && it.kind == kind }
        prefs.edit().putString("recent_searches", JSONArray().apply { rows.forEach { row -> put(JSONObject()
            .put("query", row.query).put("kind", row.kind).put("id", row.id).put("title", row.title)
            .put("subtitle", row.subtitle).put("art", row.art)) } }.toString()).apply(); notifyChange()
    }
    fun clearRecentSearches() { prefs.edit().remove("recent_searches").apply(); notifyChange() }
    fun toggleSave(card: JaMusicCatalog.Card) {
        val rows = saved(); val next = if (rows.any { it.id == card.id && it.kind == card.kind }) rows.filterNot { it.id == card.id && it.kind == card.kind } else rows + card
        prefs.edit().putString("saved", JSONArray().apply { next.forEach { put(JSONObject().put("id", it.id).put("title", it.title)
            .put("subtitle", it.subtitle).put("art", it.art).put("kind", it.kind)) } }.toString()).apply(); notifyChange()
    }
}
