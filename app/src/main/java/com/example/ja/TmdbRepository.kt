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
     * Balanced genre allocation (Option B).
     *
     * First try to place each title in its highest-priority TMDB genre. When
     * that leaves rows short, redistribute eligible titles from fuller rows
     * to sparse rows, preferring the smallest priority penalty. Titles are
     * globally unique within this media type and no row exceeds 50.
     *
     * Discover pages are fetched adaptively: initial sampling is three pages
     * per genre, then up to nine pages for genres still below the target.
     * A genre may legitimately remain short if eligible titles are exhausted.
     */
    suspend fun genreRows(type: String): List<GenreRow> {
        require(type == "movie" || type == "tv")
        val categories = if (type == "movie") movieGenres else tvGenres
        val priority = if (type == "movie") moviePriority else tvPriority
        val genreIds = categories.map { it.genreId }
        val validIds = genreIds.toSet()
        val priorityRank = priority.withIndex().associate { it.value to it.index }
        val limit = 50
        val candidates = linkedMapOf<Int, TitleItem>()
        val memberships = mutableMapOf<Int, Set<Int>>()
        val nextPage = genreIds.associateWith { 1 }.toMutableMap()
        val lastPage = genreIds.associateWith { 9 }.toMutableMap()

        suspend fun collect(genreId: Int, pages: Int) {
            repeat(pages) {
                val page = nextPage.getValue(genreId)
                if (page > minOf(9, lastPage.getValue(genreId))) return
                val response = fetch("discover/$type?sort_by=popularity.desc&include_adult=false&with_genres=$genreId&page=$page")
                nextPage[genreId] = page + 1
                lastPage[genreId] = minOf(9, response.optInt("total_pages", 1))
                val raw = response.optJSONArray("results") ?: return
                for (i in 0 until raw.length()) {
                    val obj = raw.optJSONObject(i) ?: continue
                    val id = obj.optInt("id")
                    if (id <= 0) continue
                    val ids = obj.optJSONArray("genre_ids") ?: continue
                    val genres = (0 until ids.length()).map { ids.optInt(it) }.filter { it in validIds }.toSet()
                    if (genres.isEmpty()) continue
                    candidates.putIfAbsent(id, parse(obj, type))
                    memberships[id] = (memberships[id].orEmpty() + genres)
                }
                if (raw.length() == 0) return
            }
        }

        // Work from a modest sample before spending extra API requests.
        for (genreId in genreIds) collect(genreId, 3)

        fun allocate(): Map<Int, List<TitleItem>> {
            val assigned = genreIds.associateWith { mutableListOf<Int>() }
            val owner = mutableMapOf<Int, Int>()
            val discoveryOrder = candidates.keys.withIndex().associate { it.value to it.index }
            val ranked = candidates.keys.sortedWith(compareBy<Int> { id ->
                memberships[id].orEmpty().minOfOrNull { priorityRank[it] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE
            }.thenBy { id -> discoveryOrder[id] ?: Int.MAX_VALUE })

            // Pass 1: highest-priority eligible category with available space.
            for (id in ranked) {
                val choices = memberships[id].orEmpty().sortedBy { priorityRank[it] ?: Int.MAX_VALUE }
                val target = choices.firstOrNull { assigned.getValue(it).size < limit } ?: continue
                assigned.getValue(target).add(id)
                owner[id] = target
            }

            // Pass 2: improve sparse rows by moving an eligible title out of
            // a fuller row. Never duplicate or exceed the capacity.
            for (target in genreIds.sortedBy { assigned.getValue(it).size }) {
                while (assigned.getValue(target).size < limit) {
                    val donor = owner.entries.asSequence()
                        .filter { (id, from) -> from != target && target in memberships[id].orEmpty() && assigned.getValue(from).size > assigned.getValue(target).size + 1 }
                        .minWithOrNull(compareBy<Map.Entry<Int, Int>> { (id, from) ->
                            (priorityRank[target] ?: 999) - (priorityRank[from] ?: 999)
                        }.thenByDescending { assigned.getValue(it.value).size }) ?: break
                    assigned.getValue(donor.value).remove(donor.key)
                    assigned.getValue(target).add(donor.key)
                    owner[donor.key] = target
                }
            }
            return assigned.mapValues { (_, ids) -> ids.mapNotNull { candidates[it] } }
        }

        var result = allocate()
        // Request more pages only for genres that need candidates. Rebalance
        // after each round, stopping at nine pages per genre.
        for (round in 1..6) {
            val shortGenres = genreIds.filter { result.getValue(it).size < limit && nextPage.getValue(it) <= lastPage.getValue(it) }
            if (shortGenres.isEmpty()) break
            val previousSize = candidates.size
            for (genreId in shortGenres) collect(genreId, 1)
            if (candidates.size == previousSize) break
            result = allocate()
        }
        return categories.map { GenreRow(it.name, result.getValue(it.genreId)) }
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
