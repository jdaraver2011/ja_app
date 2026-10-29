package com.example.ja

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLEncoder

class TmdbRepository {
    private val client = OkHttpClient()
    private suspend fun fetch(path: String): JSONObject = withContext(Dispatchers.IO) {
        require(BuildConfig.TMDB_API_KEY.isNotBlank()) { "Set TMDB_API_KEY in local.properties" }
        val separator = if ('?' in path) '&' else '?'
        val url = "https://api.themoviedb.org/3/$path${separator}api_key=${BuildConfig.TMDB_API_KEY}"
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("TMDB HTTP ${response.code}")
            JSONObject(response.body?.string() ?: error("Empty TMDB response"))
        }
    }
    private fun parse(obj: JSONObject, type: String): TitleItem = TitleItem(
        obj.optInt("id"), obj.optString(if (type == "tv") "name" else "title"),
        obj.optString("overview"), obj.optString("poster_path").takeIf { it.startsWith("/") },
        obj.optString("backdrop_path").takeIf { it.startsWith("/") }, type, obj.optDouble("vote_average", 0.0)
    )
    private fun list(array: JSONArray, fallback: String): List<TitleItem> = (0 until array.length()).mapNotNull { i ->
        val obj = array.optJSONObject(i) ?: return@mapNotNull null
        val type = obj.optString("media_type", fallback)
        if (type == "movie" || type == "tv") parse(obj, type) else null
    }
    data class GenreRow(val label: String, val genreIds: String, val keywords: String? = null)

    // TMDB does not have exact genres for all requested categories. Some rows use
    // the nearest TMDB genre; those are documented in INSTALL.md.
    val movieGenres = listOf(
        GenreRow("Drama", "18"), GenreRow("Comedy", "35"),
        GenreRow("Science Fiction", "878"), GenreRow("Fantasy", "14"),
        GenreRow("Thriller", "53"), GenreRow("Horror", "27"),
        GenreRow("Romance", "10749"), GenreRow("Crime", "80"),
        GenreRow("Film Noir", "80,9648"), GenreRow("Westerns", "37"),
        GenreRow("Musical", "10402"), GenreRow("Documentary", "99"),
        GenreRow("Biography", "18,36"), GenreRow("Animation", "16")
    )
    val tvGenres = listOf(
        GenreRow("Drama", "18"), GenreRow("Comedy", "35"),
        GenreRow("Science Fiction", "10765"), GenreRow("Fantasy", "10765"),
        GenreRow("Thriller", "9648,80"), GenreRow("Horror", "9648,10765"),
        GenreRow("Romance", "18"), GenreRow("Westerns", "37"),
        GenreRow("Soap Operas & Telenovelas", "10766"),
        GenreRow("Animation & Anime", "16"), GenreRow("Reality TV", "10764"),
        GenreRow("Documentary & Docudrama", "99"),
        GenreRow("Game Shows", "10767"), GenreRow("Talk Shows & Late-Night", "10767"),
        GenreRow("Lifestyle & Cooking", "10764"), GenreRow("Sports", "10767")
    )
    suspend fun discoverGenre(type: String, genre: GenreRow): List<TitleItem> {
        val encoded = URLEncoder.encode(genre.genreIds, "UTF-8")
        val endpoint = "discover/$type?sort_by=popularity.desc&with_genres=$encoded&include_adult=false&page=1"
        return list(fetch(endpoint).getJSONArray("results"), type).take(20)
    }
    suspend fun trending(): List<TitleItem> = list(fetch("trending/all/week").getJSONArray("results"), "movie")
    suspend fun movies(): List<TitleItem> = list(fetch("movie/popular").getJSONArray("results"), "movie")
    suspend fun shows(): List<TitleItem> = list(fetch("tv/popular").getJSONArray("results"), "tv")
    suspend fun search(query: String): List<TitleItem> = list(fetch("search/multi?query=${URLEncoder.encode(query, "UTF-8")}").getJSONArray("results"), "movie")
    suspend fun seasons(id: Int): List<Int> {
        val arr = fetch("tv/$id").getJSONArray("seasons")
        return (0 until arr.length()).map { arr.getJSONObject(it).getInt("season_number") }.filter { it > 0 }
    }
    suspend fun episodes(id: Int, season: Int): List<EpisodeItem> {
        val arr = fetch("tv/$id/season/$season").getJSONArray("episodes")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { EpisodeItem(season, it.getInt("episode_number"), it.optString("name")) }
    }
}
