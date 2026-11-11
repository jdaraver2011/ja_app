package com.example.ja

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val genreCache = mutableMapOf<String, List<GenreRow>>()
    fun cachedGenreRows(type: String): List<GenreRow>? = genreCache[type]
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
     * Option C: strict primary-genre assignment with adaptive discovery.
     * Every title belongs exclusively to its highest-priority official TMDB
     * genre, regardless of which discover query returned it. Never rebalance
     * titles into lower-priority genres to fill a row.
     *
     * Request three pages per genre initially, then at most six additional
     * pages per underfilled genre. Rows may remain short if priority rules
     * assign their candidates elsewhere.
     */
    suspend fun genreRows(type: String, onPartial: (List<GenreRow>) -> Unit = {}): List<GenreRow> {
        require(type == "movie" || type == "tv")
        val categories = if (type == "movie") movieGenres else tvGenres
        val priority = if (type == "movie") moviePriority else tvPriority
        val validIds = categories.map { it.genreId }.toSet()
        val candidates = linkedMapOf<Int, TitleItem>()
        val memberships = mutableMapOf<Int, Set<Int>>()
        val nextPage = categories.associate { it.genreId to 1 }.toMutableMap()
        val lastPage = categories.associate { it.genreId to 9 }.toMutableMap()
        val maxPages = 9
        val targetCount = 50

        suspend fun collect(genreId: Int, pageCount: Int) {
            repeat(pageCount) {
                val page = nextPage.getValue(genreId)
                if (page > minOf(maxPages, lastPage.getValue(genreId))) return
                val response = fetch("discover/$type?sort_by=popularity.desc&include_adult=false&with_genres=$genreId&page=$page")
                nextPage[genreId] = page + 1
                lastPage[genreId] = minOf(maxPages, response.optInt("total_pages", 1))
                val results = response.optJSONArray("results") ?: return
                for (i in 0 until results.length()) {
                    val obj = results.optJSONObject(i) ?: continue
                    val id = obj.optInt("id")
                    if (id <= 0) continue
                    val rawIds = obj.optJSONArray("genre_ids") ?: continue
                    val ids = (0 until rawIds.length())
                        .map { rawIds.optInt(it) }
                        .filter { it in validIds }
                        .toSet()
                    if (ids.isEmpty()) continue
                    candidates.putIfAbsent(id, parse(obj, type))
                    memberships[id] = memberships[id].orEmpty() + ids
                }
                if (results.length() == 0) return
            }
        }

        fun allocate(): Map<Int, List<TitleItem>> {
            val buckets = categories.associate { it.genreId to mutableListOf<TitleItem>() }
            for ((id, item) in candidates) {
                val genres = memberships[id].orEmpty()
                val primary = priority.firstOrNull { it in genres } ?: continue
                val row = buckets.getValue(primary)
                if (row.size < targetCount) row.add(item)
            }
            return buckets
        }

        val limit = Semaphore(3)
        var successes = 0
        var firstError: Exception? = null
        suspend fun collectSafely(category: GenreQuery, pages: Int) {
            try {
                limit.withPermit { collect(category.genreId, pages) }
                successes++
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (firstError == null) firstError = error
                lastPage[category.genreId] = 0
                android.util.Log.w("JA_CATALOG", "GENRE_LOAD_FAILED genre=${category.genreId}", error)
            }
        }
        // Coroutines inherit the caller dispatcher. Response parsing and merges
        // run serially on Main; only blocking HTTP executes on Dispatchers.IO.
        // Keep cached complete rows visible throughout background refresh.
        coroutineScope {
            categories.map { category -> async {
                collectSafely(category, 1)
                if (genreCache[type] == null) {
                    val allocated = allocate()
                    onPartial(categories.map { GenreRow(it.name, allocated.getValue(it.genreId)) })
                }
            } }.awaitAll()
        }
        if (successes == 0) throw (firstError ?: IllegalStateException("No catalogue rows loaded"))
        var assigned = allocate()
        for (round in 1 until maxPages) {
            val shortGenres = categories.filter { category ->
                assigned.getValue(category.genreId).size < targetCount &&
                    nextPage.getValue(category.genreId) <= lastPage.getValue(category.genreId)
            }
            if (shortGenres.isEmpty()) break
            coroutineScope { shortGenres.map { category -> async { collectSafely(category, 1) } }.awaitAll() }
            assigned = allocate()
            if (genreCache[type] == null) onPartial(categories.map {
                GenreRow(it.name, assigned.getValue(it.genreId))
            })
        }
        val rows = categories.map { category ->
            val fresh = assigned.getValue(category.genreId)
            GenreRow(category.name, fresh.ifEmpty {
                genreCache[type]?.firstOrNull { it.name == category.name }?.titles.orEmpty()
            })
        }
        genreCache[type] = rows
        onPartial(rows)
        return rows
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
