package com.example.ja

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate

/** Bounded cache; date in key prevents yesterday's decisions surviving midnight. */
class JaMovieReleaseRepository {
    private val cache = JaTimedCache<String, Boolean>(1200, 3_600_000L)
    suspend fun released(movieId: Int, today: LocalDate): Boolean {
        val key = "$today:$movieId"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            require(BuildConfig.TMDB_API_KEY.isNotBlank()) { "TMDB API key is not configured" }
            val url = "https://api.themoviedb.org/3/movie/$movieId/release_dates?api_key=${BuildConfig.TMDB_API_KEY}"
            JaTmdbHttp.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                // A failed metadata lookup is retryable, never classified as unavailable.
                if (!response.isSuccessful) error("TMDB release dates HTTP ${response.code}")
                val json = JSONObject(response.body?.string() ?: error("Empty release dates"))
                val countries = json.optJSONArray("results") ?: error("Missing release dates results")
                var qualifies = false
                for (i in 0 until countries.length()) {
                    val country = countries.optJSONObject(i) ?: continue
                    if (country.optString("iso_3166_1") != JaMovieReleaseRules.REGION) continue
                    val dates = country.optJSONArray("release_dates") ?: continue
                    for (j in 0 until dates.length()) {
                        val date = dates.optJSONObject(j) ?: continue
                        if (JaMovieReleaseRules.qualifies(date.optInt("type"), date.optString("release_date"), today)) {
                            qualifies = true
                        }
                    }
                }
                cache.put(key, qualifies)
                qualifies
            }
        }
    }
}

internal fun movieReleaseParams(today: LocalDate = LocalDate.now()): Map<String, String> = mapOf(
    "region" to JaMovieReleaseRules.REGION,
    "with_release_type" to "4|6",
    "release_date.lte" to today.minusDays(1).toString()
)
