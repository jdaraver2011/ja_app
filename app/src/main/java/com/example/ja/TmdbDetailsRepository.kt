package com.example.ja

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Detailed metadata supplied by TMDB. No media-stream attributes or third-party scores are inferred. */
data class TmdbPersonCredit(
    val id: Int,
    val name: String,
    val role: String,
    val profilePath: String?
) {
    val profileUrl: String?
        get() = profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }
}

data class TmdbTitleDetails(
    val title: String,
    val overview: String,
    val tagline: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val releaseYear: String?,
    val certification: String?,
    val runtimeMinutes: Int?,
    val genres: List<String>,
    val rating: Double?,
    val voteCount: Int,
    val creatorLabel: String?,
    val creatorNames: String?,
    val cast: List<TmdbPersonCredit>,
    val recommendations: List<TitleItem>,
    val isTv: Boolean
) {
    val posterUrl: String?
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
    val backdropUrl: String?
        get() = backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
}

/**
 * Separate from the catalog repository so the existing discovery and playback pipeline stays intact.
 * Requests one appended TMDB response per opened title.
 */
class TmdbDetailsRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun details(media: TitleItem): TmdbTitleDetails = withContext(Dispatchers.IO) {
        require(media.type == "movie" || media.type == "tv") { "Unknown media type" }
        val key = BuildConfig.TMDB_API_KEY
        require(key.isNotBlank()) { "TMDB API key is not configured" }
        val tv = media.type == "tv"
        val append = if (tv) "aggregate_credits,content_ratings,recommendations" else "credits,release_dates,recommendations"
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("api.themoviedb.org")
            .addPathSegment("3")
            .addPathSegment(media.type)
            .addPathSegment(media.id.toString())
            .addQueryParameter("language", "en-US")
            .addQueryParameter("append_to_response", append)
            .addQueryParameter("api_key", key)
            .build()
        val req = Request.Builder().url(url).get().build()
        val json = client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("TMDB detail request failed (HTTP ${response.code})")
            JSONObject(response.body?.string() ?: error("TMDB returned no details"))
        }
        parse(json, media, tv)
    }

    private fun parse(obj: JSONObject, fallback: TitleItem, tv: Boolean): TmdbTitleDetails {
        val castRoot = obj.optJSONObject(if (tv) "aggregate_credits" else "credits")
        val cast = castRoot?.optJSONArray("cast").orEmptyObjects().take(18).map { person ->
            val role = if (tv) {
                person.optJSONArray("roles").orEmptyObjects()
                    .mapNotNull { clean(it.optString("character")) }
                    .distinct().take(2).joinToString(" / ")
            } else {
                clean(person.optString("character")).orEmpty()
            }
            TmdbPersonCredit(
                id = person.optInt("id"),
                name = clean(person.optString("name")).orEmpty(),
                role = role,
                profilePath = clean(person.optString("profile_path"))?.takeIf { it.startsWith("/") }
            )
        }.filter { it.name.isNotBlank() }

        val creatorNames = if (tv) {
            obj.optJSONArray("created_by").orEmptyObjects()
                .mapNotNull { clean(it.optString("name")) }
                .distinct().take(3).joinToString(", ").ifBlank { null }
        } else {
            castRoot?.optJSONArray("crew").orEmptyObjects()
                .filter { it.optString("job") == "Director" }
                .mapNotNull { clean(it.optString("name")) }
                .distinct().take(3).joinToString(", ").ifBlank { null }
        }

        val certification = if (tv) {
            obj.optJSONObject("content_ratings")?.optJSONArray("results").orEmptyObjects()
                .firstOrNull { it.optString("iso_3166_1") == "US" }
                ?.optString("rating")?.let(::clean)
        } else {
            obj.optJSONObject("release_dates")?.optJSONArray("results").orEmptyObjects()
                .firstOrNull { it.optString("iso_3166_1") == "US" }
                ?.optJSONArray("release_dates").orEmptyObjects()
                .sortedBy { when (it.optInt("type")) { 3 -> 0; 2 -> 1; 4 -> 2; 5 -> 3; else -> 4 } }
                .firstNotNullOfOrNull { clean(it.optString("certification")) }
        }

        val runtime = if (tv) {
            obj.optJSONArray("episode_run_time")?.let { runtimes ->
                (0 until runtimes.length()).map { runtimes.optInt(it) }.firstOrNull { it > 0 }
            } ?: obj.optJSONObject("last_episode_to_air")?.optInt("runtime", 0)?.takeIf { it > 0 }
        } else obj.optInt("runtime", 0).takeIf { it > 0 }

        val related = obj.optJSONObject("recommendations")?.optJSONArray("results")
            .orEmptyObjects().asSequence()
            .filter { !it.optBoolean("adult", false) }
            .mapNotNull { entry ->
                val id = entry.optInt("id")
                if (id <= 0 || id == fallback.id) return@mapNotNull null
                val name = clean(entry.optString(if (tv) "name" else "title")) ?: return@mapNotNull null
                TitleItem(
                    id, name, entry.optString("overview"),
                    clean(entry.optString("poster_path"))?.takeIf { it.startsWith("/") },
                    clean(entry.optString("backdrop_path"))?.takeIf { it.startsWith("/") },
                    fallback.type, entry.optDouble("vote_average", 0.0)
                )
            }.take(14).toList().orEmpty()

        val releaseDate = if (tv) obj.optString("first_air_date") else obj.optString("release_date")
        return TmdbTitleDetails(
            title = clean(obj.optString(if (tv) "name" else "title")) ?: fallback.title,
            overview = clean(obj.optString("overview")) ?: fallback.overview,
            tagline = clean(obj.optString("tagline")),
            posterPath = clean(obj.optString("poster_path"))?.takeIf { it.startsWith("/") } ?: fallback.poster,
            backdropPath = clean(obj.optString("backdrop_path"))?.takeIf { it.startsWith("/") } ?: fallback.backdrop,
            releaseYear = releaseDate.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) },
            certification = certification,
            runtimeMinutes = runtime,
            genres = obj.optJSONArray("genres").orEmptyObjects().mapNotNull { clean(it.optString("name")) },
            rating = obj.optDouble("vote_average", 0.0).takeIf { it > 0.0 && it.isFinite() },
            voteCount = obj.optInt("vote_count", 0).coerceAtLeast(0),
            creatorLabel = if (creatorNames == null) null else if (tv) "Created by" else "Directed by",
            creatorNames = creatorNames,
            cast = cast,
            recommendations = related,
            isTv = tv
        )
    }
}

private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

private fun JSONArray?.orEmptyObjects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).mapNotNull { optJSONObject(it) }
