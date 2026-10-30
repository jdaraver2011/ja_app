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

private data class GenreQuery(val name: String, val genreId: Int)

// Display order is alphabetical. Assignment priority is deliberately different:
// specific genres win over broad labels such as Comedy and Drama.
private val movieGenres = listOf(
    GenreQuery("Action", 28), GenreQuery("Adventure", 12), GenreQuery("Animation", 16),
    GenreQuery("Comedy", 35), GenreQuery("Crime", 80), GenreQuery("Documentary", 99),
    GenreQuery("Drama", 18), GenreQuery("Family", 10751), GenreQuery("Fantasy", 14),
    GenreQuery("History", 36), GenreQuery("Horror", 27), GenreQuery("Music", 10402),
    GenreQuery("Mystery", 9648), GenreQuery("Romance", 10749), GenreQuery("Science Fiction", 878),
    GenreQuery("TV Movie", 10770), GenreQuery("Thriller", 53), GenreQuery("War", 10752),
    GenreQuery("Western", 37)
)
private val tvGenres = listOf(
    GenreQuery("Action & Adventure", 10759), GenreQuery("Animation", 16),
    GenreQuery("Comedy", 35), GenreQuery("Crime", 80), GenreQuery("Documentary", 99),
    GenreQuery("Drama", 18), GenreQuery("Family", 10751), GenreQuery("Kids", 10762),
    GenreQuery("Mystery", 9648), GenreQuery("News", 10763), GenreQuery("Reality", 10764),
    GenreQuery("Sci-Fi & Fantasy", 10765), GenreQuery("Soap", 10766),
    GenreQuery("Talk", 10767), GenreQuery("War & Politics", 10768), GenreQuery("Western", 37)
)
private val moviePriority = listOf(
    99, 16, 27, 10402, 37, 10752, 36, 10770, 878, 14, 80, 9648,
    28, 12, 10751, 10749, 53, 35, 18
)
private val tvPriority = listOf(
    99, 16, 10762, 10763, 10767, 10764, 37, 10768, 10765,
    10759, 80, 9648, 10751, 10766, 35, 18
)

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
     * Discover each official TMDB genre, then assign every unique title to its
     * highest-priority genre from TMDB's genre_ids array. This makes the result
     * independent of row display order and prevents duplicate titles.
     *
     * A title may appear in a row other than the one it was discovered under.
     * Five pages per genre provide up to 100 candidates before deduplication;
     * a row may still contain fewer than 50 matching titles.
     */
    suspend fun genreRows(type: String): List<GenreRow> {
        require(type == "movie" || type == "tv")
        val categories = if (type == "movie") movieGenres else tvGenres
        val priority = if (type == "movie") moviePriority else tvPriority
        val validIds = categories.map { it.genreId }.toSet()
        val buckets = categories.associate { it.genreId to mutableListOf<TitleItem>() }
        val seen = mutableSetOf<Int>()
        for (category in categories) {
            for (page in 1..5) {
                val response = fetch("discover/$type?sort_by=popularity.desc&include_adult=false&with_genres=${category.genreId}&page=$page")
                val raw = response.getJSONArray("results")
                for (i in 0 until raw.length()) {
                    val obj = raw.optJSONObject(i) ?: continue
                    val id = obj.optInt("id")
                    if (id <= 0 || !seen.add(id)) continue
                    val ids = obj.optJSONArray("genre_ids") ?: continue
                    val memberships = (0 until ids.length()).map { ids.optInt(it) }.toSet()
                    val assigned = priority.firstOrNull { it in memberships && it in validIds } ?: continue
                    buckets.getValue(assigned).add(parse(obj, type))
                }
                if (raw.length() == 0 || page >= response.optInt("total_pages", 1)) break
            }
        }
        return categories.map { category ->
            GenreRow(category.name, buckets.getValue(category.genreId).take(50))
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
