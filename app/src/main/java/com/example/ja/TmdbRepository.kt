package com.example.ja

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLEncoder


/** Ordered genre row; each TMDB ID appears in only the first matching row. */
data class GenreRow(val name: String, val titles: List<TitleItem>)

private data class GenreQuery(val name: String, val genreId: Int, val extra: String = "")

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
    /** TMDB's official external identifiers; an IMDb ID is not a TMDB ID. */
    suspend fun imdbId(media: TitleItem): String? {
        val result = when (media.type) {
            "movie" -> fetch("movie/${media.id}/external_ids")
            "tv" -> fetch("tv/${media.id}/external_ids")
            else -> return null
        }
        return result.optString("imdb_id").takeIf { it.matches(Regex("tt[0-9]+")) }
    }

    /**
     * TMDB discover results are paginated (20/page). Fetch up to 10 pages per
     * category so later categories can still reach 50 after global deduplication.
     * Earlier genre rows always take priority. Categories without a native TMDB
     * genre use keyword filters, not an unrelated genre ID.
     */
    suspend fun genreRows(type: String): List<GenreRow> {
        require(type == "movie" || type == "tv")
        val categories = if (type == "movie") listOf(
            GenreQuery("Drama", 18), GenreQuery("Comedy", 35),
            GenreQuery("Science Fiction & Fantasy", 878),
            GenreQuery("Thriller", 53), GenreQuery("Horror", 27),
            GenreQuery("Romance", 10749), GenreQuery("Crime", 80),
            GenreQuery("Westerns", 37), GenreQuery("Musical", 10402),
            GenreQuery("Documentary", 99), GenreQuery("Animation", 16),
            GenreQuery("Anime", 16, "&with_original_language=ja")
        ) else listOf(
            GenreQuery("Drama", 18), GenreQuery("Comedy", 35),
            GenreQuery("Science Fiction & Fantasy", 10765),
            // TMDB does not define TV Thriller or Romance genres. Use
            // relevant TMDB keyword filtering combined with drama/mystery.
            GenreQuery("Thriller", 9648, "&with_keywords=9663"),
            GenreQuery("Romance", 18, "&with_keywords=9840"),
            GenreQuery("Westerns", 37),
            GenreQuery("Animation", 16),
            GenreQuery("Anime", 16, "&with_original_language=ja"),
            GenreQuery("Reality TV", 10764), GenreQuery("Documentary", 99)
        )
        val seen = mutableSetOf<Int>()
        return categories.map { category ->
            val picked = mutableListOf<TitleItem>()
            for (page in 1..10) {
                if (picked.size >= 50) break
                val response = fetch("discover/$type?sort_by=popularity.desc&include_adult=false&with_genres=${category.genreId}${category.extra}&page=$page")
                val raw = response.getJSONArray("results")
                val candidates = (0 until raw.length()).mapNotNull { index ->
                    val obj = raw.optJSONObject(index) ?: return@mapNotNull null
                    val isJapanese = obj.optString("original_language") == "ja"
                    if (category.name == "Animation" && isJapanese) return@mapNotNull null
                    if (category.name == "Anime" && !isJapanese) return@mapNotNull null
                    parse(obj, type)
                }
                if (raw.length() == 0) break
                for (item in candidates) {
                    if (picked.size >= 50) break
                    if (seen.add(item.id)) picked.add(item)
                }
                if (page >= response.optInt("total_pages", 1)) break
            }
            GenreRow(category.name, picked)
        }
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
