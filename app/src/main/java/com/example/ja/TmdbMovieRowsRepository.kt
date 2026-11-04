package com.example.ja

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Movies-tab collections. Titles are unique within one row only;
 * intentionally NEVER deduplicate across rows. This class does not change
 * the original TMDB genre catalog (still available in Search).
 *
 * Streaming availability is US-region TMDB watch-provider metadata.
 * TMDB does not provide an API date for when a film was added to a service.
 * "New on ..." therefore means recently released movies currently listed on
 * that service, NOT verified recent streaming-library additions.
 */
class TmdbMovieRowsRepository {
    companion object {
        private const val REGION = "US"
        private const val MAX_TITLES = 50
        private const val MAX_PAGES = 3
        private const val CACHE_MS = 30 * 60 * 1000L
    }

    private data class RowSpec(
        val label: String,
        val endpoint: String,
        val query: Map<String, String> = emptyMap()
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var cached: List<GenreRow>? = null
    private var cachedAtMs = 0L

    private fun dateString(cal: Calendar): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(cal.time)

    private fun utcToday(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

    private fun dateShift(field: Int, amount: Int): String =
        dateString(utcToday().apply { add(field, amount) })

    private suspend fun fetch(endpoint: String, query: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val apiKey = BuildConfig.TMDB_API_KEY
            require(apiKey.isNotBlank()) { "TMDB_API_KEY is not configured" }
            val url = HttpUrl.Builder().scheme("https").host("api.themoviedb.org")
                .addPathSegment("3")
            endpoint.split('/').forEach { url.addPathSegment(it) }
            url.addQueryParameter("api_key", apiKey)
                .addQueryParameter("language", "en-US")
            query.forEach { (k, v) -> url.addQueryParameter(k, v) }
            http.newCall(Request.Builder().url(url.build()).get().build()).execute().use { response ->
                if (!response.isSuccessful) error("TMDB HTTP ${response.code}: $endpoint")
                JSONObject(response.body?.string() ?: error("TMDB returned an empty response"))
            }
        }

    private fun titleFrom(json: JSONObject): TitleItem? {
        if (json.optBoolean("adult", false)) return null
        val id = json.optInt("id")
        val title = json.optString("title").trim()
        if (id <= 0 || title.isBlank() || title == "null") return null
        val poster = json.optString("poster_path").takeIf { it.startsWith("/") }
        val backdrop = json.optString("backdrop_path").takeIf { it.startsWith("/") }
        return TitleItem(
            id = id, title = title, overview = json.optString("overview"),
            poster = poster, backdrop = backdrop, type = "movie",
            rating = json.optDouble("vote_average", 0.0)
        )
    }

    private fun readItems(array: JSONArray?): List<TitleItem> =
        (0 until (array?.length() ?: 0)).mapNotNull { index ->
            array?.optJSONObject(index)?.let(::titleFrom)
        }

    /** Resolve current primary US provider IDs from TMDB rather than depending on static IDs. */
    private suspend fun providerIds(): Pair<String?, String?> {
        val results = fetch("watch/providers/movie", mapOf("watch_region" to REGION))
            .optJSONArray("results") ?: return null to null
        var netflix: String? = null
        var disneyPlus: String? = null
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val providerId = item.optInt("provider_id")
            val normalized = item.optString("provider_name").lowercase(Locale.US)
                .replace(" ", "").replace("+", "plus")
            if (providerId <= 0) continue
            if (normalized == "netflix") netflix = providerId.toString()
            if (normalized == "disneyplus") disneyPlus = providerId.toString()
        }
        Log.i("JA_MOVIE_ROWS", "US watch-provider ids found netflix=${netflix != null} disney=${disneyPlus != null}")
        return netflix to disneyPlus
    }

    private fun specs(netflix: String?, disneyPlus: String?): List<RowSpec> {
        val current = dateString(utcToday())
        val recent = dateShift(Calendar.MONTH, -12)
        val defaultRegion = mapOf("region" to REGION)
        val streamingBase = mapOf(
            "watch_region" to REGION,
            "with_watch_monetization_types" to "flatrate",
            "primary_release_date.gte" to recent,
            "primary_release_date.lte" to current,
            "sort_by" to "primary_release_date.desc",
            "include_adult" to "false"
        )
        return listOf(
            RowSpec("Popular", "movie/popular", defaultRegion),
            RowSpec("In Theatres", "movie/now_playing", defaultRegion),
            RowSpec("Coming Soon", "movie/upcoming", defaultRegion),
            RowSpec("For Rent", "discover/movie", mapOf(
                "watch_region" to REGION, "with_watch_monetization_types" to "rent",
                "sort_by" to "popularity.desc", "include_adult" to "false"
            )),
            RowSpec("New on Streaming", "discover/movie", streamingBase),
            RowSpec("New on Netflix", "discover/movie", streamingBase +
                mapOf("with_watch_providers" to (netflix ?: "-1"))),
            RowSpec("New on Disney+", "discover/movie", streamingBase +
                mapOf("with_watch_providers" to (disneyPlus ?: "-1"))),
            RowSpec("Top Rated", "movie/top_rated", defaultRegion),
            RowSpec("Hidden Gems", "discover/movie", mapOf(
                "sort_by" to "vote_average.desc", "vote_average.gte" to "7.0",
                "vote_count.gte" to "100", "vote_count.lte" to "3000",
                "popularity.lte" to "30", "include_adult" to "false"
            ))
        )
    }

    private suspend fun loadRow(spec: RowSpec): GenreRow {
        // Don't silently substitute unrelated provider content if TMDB omits a service.
        if (spec.query["with_watch_providers"] == "-1") {
            Log.w("JA_MOVIE_ROWS", "Provider unavailable for ${spec.label}; row will be empty")
            return GenreRow(spec.label, emptyList())
        }
        val unique = linkedMapOf<Int, TitleItem>()
        try {
            for (page in 1..MAX_PAGES) {
                val response = fetch(spec.endpoint, spec.query + mapOf("page" to page.toString()))
                val items = readItems(response.optJSONArray("results"))
                for (item in items) {
                    unique.putIfAbsent(item.id, item)
                    if (unique.size == MAX_TITLES) break
                }
                if (unique.size >= MAX_TITLES || items.isEmpty() || page >= response.optInt("total_pages", 1)) break
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (ex: Exception) {
            Log.e("JA_MOVIE_ROWS", "Row ${spec.label} loading error: ${ex.message}", ex)
        }
        Log.i("JA_MOVIE_ROWS", "ROW label=${spec.label} titles=${unique.size}")
        return GenreRow(spec.label, unique.values.toList())
    }

    suspend fun movieRows(): List<GenreRow> {
        require(BuildConfig.TMDB_API_KEY.isNotBlank()) { "TMDB_API_KEY is not configured" }
        val now = System.currentTimeMillis()
        cached?.takeIf { now - cachedAtMs in 0 until CACHE_MS }?.let { return it }
        val (netflix, disneyPlus) = try { providerIds() }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (ex: Exception) {
                Log.w("JA_MOVIE_ROWS", "Provider lookup failed; service-specific rows unavailable", ex)
                null to null
            }
        // Bound in-flight requests; avoid hammering the TMDB API with 30 concurrent calls.
        val limiter = Semaphore(3)
        val loaded = coroutineScope {
            specs(netflix, disneyPlus).map { spec ->
                async { limiter.withPermit { loadRow(spec) } }
            }.awaitAll()
        }
        // Do not cache an outage as an apparently legitimate empty catalog.
        if (loaded.none { it.titles.isNotEmpty() }) {
            error("TMDB returned no movie collections; check the connection or API key")
        }
        cached = loaded
        cachedAtMs = System.currentTimeMillis()
        return loaded
    }
}
