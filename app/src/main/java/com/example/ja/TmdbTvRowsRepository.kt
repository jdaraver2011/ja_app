package com.example.ja

import android.util.Log
import kotlinx.coroutines.CancellationException
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
 * TV home collections, independent of the Movies tab and Search's genre browser.
 * Duplicates are removed only *within* a row, not between categories.
 * TMDB watch-provider availability is based on the US region, not guaranteed
 * playback availability through any particular JA playback integration.
 * The API doesn't publish the date a show entered a streaming service.
 */
class TmdbTvRowsRepository {
    companion object {
        private const val REGION = "US"
        private const val ROW_LIMIT = 50
        private const val MAX_PAGES = 3
        private const val CACHE_MS = 30 * 60 * 1000L
        private const val LOG_TAG = "JA_TV_ROWS"
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
    private var cachedAtMs: Long = 0L

    private fun calendar(): Calendar = Calendar.getInstance(TimeZone.getDefault())

    private fun formatDate(cal: Calendar): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = cal.timeZone
        }.format(cal.time)

    private fun shifted(field: Int, amount: Int): String =
        formatDate(calendar().apply { add(field, amount) })

    private suspend fun fetch(endpoint: String, query: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val apiKey = BuildConfig.TMDB_API_KEY
            require(apiKey.isNotBlank()) { "TMDB_API_KEY is not configured" }
            val builder = HttpUrl.Builder().scheme("https")
                .host("api.themoviedb.org").addPathSegment("3")
            endpoint.split('/').forEach { builder.addPathSegment(it) }
            builder.addQueryParameter("api_key", apiKey)
                .addQueryParameter("language", "en-US")
            query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
            val request = Request.Builder().url(builder.build()).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("TMDB HTTP ${response.code}: $endpoint")
                JSONObject(response.body?.string() ?: error("Empty TMDB response for $endpoint"))
            }
        }

    private fun titleFrom(obj: JSONObject): TitleItem? {
        if (obj.optBoolean("adult", false)) return null
        val id = obj.optInt("id")
        val name = obj.optString("name").trim()
        if (id <= 0 || name.isBlank() || name == "null") return null
        return TitleItem(
            id = id, title = name, overview = obj.optString("overview"),
            poster = obj.optString("poster_path").takeIf { it.startsWith("/") },
            backdrop = obj.optString("backdrop_path").takeIf { it.startsWith("/") },
            type = "tv", rating = obj.optDouble("vote_average", 0.0)
        )
    }

    private fun readTitles(results: JSONArray?): List<TitleItem> =
        (0 until (results?.length() ?: 0)).mapNotNull { index ->
            results?.optJSONObject(index)?.let(::titleFrom)
        }

    private suspend fun providerIds(): Pair<String?, String?> {
        val arr = fetch("watch/providers/tv", mapOf("watch_region" to REGION))
            .optJSONArray("results") ?: return null to null
        var netflix: String? = null
        var disney: String? = null
        for (i in 0 until arr.length()) {
            val provider = arr.optJSONObject(i) ?: continue
            val id = provider.optInt("provider_id")
            val normalized = provider.optString("provider_name")
                .lowercase(Locale.US).replace(" ", "").replace("+", "plus")
            if (id <= 0) continue
            if (normalized == "netflix") netflix = id.toString()
            if (normalized == "disneyplus") disney = id.toString()
        }
        Log.i(LOG_TAG, "US service providers available: netflix=${netflix != null} disney=${disney != null}")
        return netflix to disney
    }

    private fun specs(netflix: String?, disney: String?): List<RowSpec> {
        val today = formatDate(calendar())
        val twelveMonthsAgo = shifted(Calendar.MONTH, -12)
        val sixMonthsAgo = shifted(Calendar.MONTH, -6)
        val establishedBefore = shifted(Calendar.DAY_OF_YEAR, -60)
        val weekStart = calendar().apply {
            val daysSinceMonday = (get(Calendar.DAY_OF_WEEK) + 5) % 7
            add(Calendar.DAY_OF_YEAR, -daysSinceMonday)
        }
        val weekEnd = (weekStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 6) }
        val streamBase = mapOf(
            "watch_region" to REGION,
            "with_watch_monetization_types" to "flatrate",
            "first_air_date.gte" to twelveMonthsAgo,
            "first_air_date.lte" to today,
            "sort_by" to "popularity.desc",
            "include_adult" to "false"
        )
        val recentBase = mapOf(
            "first_air_date.gte" to sixMonthsAgo,
            "first_air_date.lte" to today,
            "sort_by" to "first_air_date.desc",
            "include_adult" to "false"
        )
        return listOf(
            RowSpec("Popular", "tv/popular"),
            RowSpec("New on Streaming", "discover/tv", streamBase),
            RowSpec("New on Netflix", "discover/tv", streamBase +
                ("with_watch_providers" to (netflix ?: "-1"))),
            RowSpec("New on Disney+", "discover/tv", streamBase +
                ("with_watch_providers" to (disney ?: "-1"))),
            RowSpec("Airing Tonight", "tv/airing_today", mapOf(
                "timezone" to TimeZone.getDefault().id
            )),
            RowSpec("New Series", "discover/tv", recentBase),
            // Established, active shows that have an episode listed this calendar week.
            // Not necessarily a brand-new season premiere.
            RowSpec("Returning This Week", "discover/tv", mapOf(
                "air_date.gte" to formatDate(weekStart),
                "air_date.lte" to formatDate(weekEnd),
                "first_air_date.lte" to establishedBefore,
                "with_status" to "0", // Returning Series
                "sort_by" to "popularity.desc",
                "timezone" to TimeZone.getDefault().id,
                "include_adult" to "false"
            )),
            // 'ads' can mean free with advertisements; 'free' means no fee.
            RowSpec("Free to Watch", "discover/tv", mapOf(
                "watch_region" to REGION,
                "with_watch_monetization_types" to "free|ads",
                "sort_by" to "popularity.desc",
                "include_adult" to "false"
            )),
            RowSpec("Top Rated", "tv/top_rated"),
            RowSpec("Hidden Gems", "discover/tv", mapOf(
                "sort_by" to "vote_average.desc",
                "vote_average.gte" to "7.2",
                "vote_count.gte" to "75",
                "vote_count.lte" to "2000",
                "popularity.lte" to "30",
                "include_adult" to "false"
            )),
            // TMDB has audience votes but *no* critic-review score.
            // Use strong viewer scores and broad voting participation as a proxy.
            RowSpec("Critically Acclaimed", "discover/tv", mapOf(
                "sort_by" to "vote_average.desc",
                "vote_average.gte" to "8.0",
                "vote_count.gte" to "1000",
                "first_air_date.lte" to today,
                "include_adult" to "false"
            ))
        )
    }

    private suspend fun loadRow(spec: RowSpec): GenreRow {
        if (spec.query["with_watch_providers"] == "-1") {
            Log.w(LOG_TAG, "Provider missing: ${spec.label}; row empty")
            return GenreRow(spec.label, emptyList())
        }
        val unique = linkedMapOf<Int, TitleItem>()
        try {
            for (page in 1..MAX_PAGES) {
                val data = fetch(spec.endpoint, spec.query + ("page" to page.toString()))
                val titles = readTitles(data.optJSONArray("results"))
                for (title in titles) {
                    unique.putIfAbsent(title.id, title)
                    if (unique.size >= ROW_LIMIT) break
                }
                if (unique.size >= ROW_LIMIT || titles.isEmpty() ||
                    page >= data.optInt("total_pages", 1)) break
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (ex: Exception) {
            Log.e(LOG_TAG, "Row ${spec.label} loading error: ${ex.message}", ex)
        }
        Log.i(LOG_TAG, "ROW label=${spec.label} titles=${unique.size}")
        return GenreRow(spec.label, unique.values.toList())
    }

    suspend fun tvRows(): List<GenreRow> {
        require(BuildConfig.TMDB_API_KEY.isNotBlank()) { "TMDB_API_KEY is not configured" }
        val now = System.currentTimeMillis()
        cached?.takeIf { now - cachedAtMs in 0 until CACHE_MS }?.let { return it }
        val (netflix, disney) = try { providerIds() }
            catch (cancel: CancellationException) { throw cancel }
            catch (ex: Exception) {
                Log.w(LOG_TAG, "Watch-provider lookup failed", ex)
                null to null
            }
        val limiter = Semaphore(3)
        val rows = coroutineScope {
            specs(netflix, disney).map { spec ->
                async { limiter.withPermit { loadRow(spec) } }
            }.awaitAll()
        }
        if (rows.none { it.titles.isNotEmpty() }) {
            error("TMDB returned no TV collections; check network or API key")
        }
        cached = rows
        cachedAtMs = System.currentTimeMillis()
        return rows
    }
}
