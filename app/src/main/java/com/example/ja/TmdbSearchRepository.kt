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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Dedicated TMDB search adapter. The stable catalog and player repositories are unchanged. */
enum class JaSearchMode(val label: String) {
    ALL("All"), TITLES("Titles"), ACTORS("Actors"), DIRECTORS("Directors"), GENRES("Genres")
}

data class JaSearchPerson(
    val credit: TmdbPersonCredit,
    val department: String?,
    val knownFor: String?
)

data class JaSearchGenre(val id: Int, val name: String, val type: String) {
    val displayType: String get() = if (type == "movie") "Movies" else "TV"
}

data class JaSearchResponse(
    val titles: List<TitleItem> = emptyList(),
    val people: List<JaSearchPerson> = emptyList(),
    val genres: List<JaSearchGenre> = emptyList()
)

/** One TMDB page (normally 20 titles) and pagination metadata. */
data class JaTitlePage(
    val titles: List<TitleItem>,
    val page: Int,
    val totalPages: Int
) {
    val hasMore: Boolean get() = page < totalPages && titles.isNotEmpty()
}

class TmdbSearchRepository {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private var genreCache: List<JaSearchGenre>? = null
    // A person can be primarily known for acting and also direct films or TV episodes.
    // Cache verified Director credits so repeated searches don't re-fetch every profile.
    private val verifiedDirectorCache = ConcurrentHashMap<Int, Boolean>()
    private val directorJobs = setOf(
        "director", "co-director", "series director", "episode director", "supervising director"
    )

    private suspend fun fetch(path: String, params: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val apiKey = BuildConfig.TMDB_API_KEY
            require(apiKey.isNotBlank()) { "TMDB API key is not configured" }
            val builder = HttpUrl.Builder().scheme("https").host("api.themoviedb.org")
                .addPathSegment("3")
            path.split('/').forEach { builder.addPathSegment(it) }
            builder.addQueryParameter("api_key", apiKey)
                .addQueryParameter("language", "en-US")
            params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
            http.newCall(Request.Builder().url(builder.build()).get().build()).execute().use { response ->
                if (!response.isSuccessful) error("TMDB search HTTP ${response.code}")
                JSONObject(response.body?.string() ?: error("TMDB returned an empty response"))
            }
        }

    private fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun asTitle(json: JSONObject, type: String): TitleItem? {
        if (type != "movie" && type != "tv") return null
        if (json.optBoolean("adult", false)) return null
        val id = json.optInt("id")
        val title = clean(json.optString(if (type == "tv") "name" else "title"))
        if (id <= 0 || title == null) return null
        return TitleItem(
            id = id, title = title, overview = clean(json.optString("overview")).orEmpty(),
            poster = clean(json.optString("poster_path"))?.takeIf { it.startsWith('/') },
            backdrop = clean(json.optString("backdrop_path"))?.takeIf { it.startsWith('/') },
            type = type,
            rating = json.optDouble("vote_average", 0.0).takeIf { it.isFinite() } ?: 0.0
        )
    }

    private fun asPerson(json: JSONObject): JaSearchPerson? {
        if (json.optBoolean("adult", false)) return null
        val id = json.optInt("id")
        val name = clean(json.optString("name"))
        if (id <= 0 || name == null) return null
        val works = json.optJSONArray("known_for")
        val knownFor = (0 until (works?.length() ?: 0)).mapNotNull { i ->
            works?.optJSONObject(i)?.let { it.optString("title", it.optString("name")) }?.let(::clean)
        }.take(3).joinToString(" • ").ifBlank { null }
        return JaSearchPerson(
            TmdbPersonCredit(
                id = id, name = name, role = "",
                profilePath = clean(json.optString("profile_path"))?.takeIf { it.startsWith('/') }
            ),
            department = clean(json.optString("known_for_department")),
            knownFor = knownFor
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList()
        else (0 until length()).mapNotNull { this.optJSONObject(it) }

    private suspend fun genres(): List<JaSearchGenre> {
        genreCache?.let { return it }
        val all = mutableListOf<JaSearchGenre>()
        for (type in listOf("movie", "tv")) {
            val root = fetch("genre/$type/list")
            for (item in root.optJSONArray("genres").objects()) {
                val id = item.optInt("id")
                val name = clean(item.optString("name"))
                if (id > 0 && name != null) all.add(JaSearchGenre(id, name, type))
            }
        }
        val sorted = all.distinctBy { it.type to it.id }
            .sortedWith(compareBy<JaSearchGenre> { it.name }.thenBy { it.type })
        genreCache = sorted
        return sorted
    }

    /**
     * Search/person cannot filter by a crew job. Verify director credits instead of
     * trusting known_for_department, which is only the person's primary department.
     * This excludes actor-only and assistant-director-only results, but keeps genuine
     * actor/directors whose primary department is Acting.
     */
    private suspend fun hasVerifiedDirectorCredit(personId: Int): Boolean {
        verifiedDirectorCache[personId]?.let { return it }
        return try {
            val credits = fetch("person/$personId/combined_credits")
            val hasDirected = credits.optJSONArray("crew").objects().any { credit ->
                credit.optString("job").trim().lowercase() in directorJobs
            }
            verifiedDirectorCache[personId] = hasDirected
            hasDirected
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Fail closed: don't show an unverified actor as a director.
            // Do not cache temporary API/network failures.
            Log.w("JA_SEARCH", "Unable to verify directing credits for person $personId", error)
            false
        }
    }

    suspend fun search(rawQuery: String, mode: JaSearchMode): JaSearchResponse {
        val query = rawQuery.trim()
        if (mode == JaSearchMode.GENRES) {
            return JaSearchResponse(genres = genres().filter {
                query.isBlank() || it.name.contains(query, ignoreCase = true)
            })
        }
        if (query.isBlank()) return JaSearchResponse()

        if (mode == JaSearchMode.ACTORS || mode == JaSearchMode.DIRECTORS) {
            val persons = fetch("search/person", mapOf("query" to query, "include_adult" to "false"))
                .optJSONArray("results").objects().mapNotNull(::asPerson)
            if (mode == JaSearchMode.DIRECTORS) {
                // TMDB has no Directors-only search parameter. Confirm a real
                // Director job in the person's movie/TV crew credits.
                // Limit simultaneous lookups to avoid overwhelming the TMDB API.
                val directors = coroutineScope {
                    val limit = Semaphore(4)
                    persons.distinctBy { it.credit.id }.map { person ->
                        async {
                            limit.withPermit {
                                if (hasVerifiedDirectorCredit(person.credit.id)) {
                                    person.copy(department = "Directing")
                                } else null
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                Log.i("JA_SEARCH", "Directors filter: ${directors.size} verified / ${persons.size} candidates")
                return JaSearchResponse(people = directors)
            }
            // Preserve the original Actors behavior for this Directors-only fix.
            return JaSearchResponse(people = persons.sortedBy { if (it.department == "Acting") 0 else 1 })
        }

        val entries = fetch("search/multi", mapOf("query" to query, "include_adult" to "false"))
            .optJSONArray("results").objects()
        val titles = entries.mapNotNull { asTitle(it, it.optString("media_type")) }
            .distinctBy { it.type to it.id }
        if (mode == JaSearchMode.TITLES) return JaSearchResponse(titles = titles)
        val people = entries.asSequence().filter { it.optString("media_type") == "person" }
            .mapNotNull(::asPerson).distinctBy { it.credit.id }.toList()
        // Keep title/person hits even if the separate genre metadata request fails.
        val matchedGenres = try { genres().filter { it.name.contains(query, ignoreCase = true) } }
            catch (_: Exception) { emptyList() }
        return JaSearchResponse(titles = titles, people = people, genres = matchedGenres)
    }

    private fun titlePage(json: JSONObject, type: String, requestedPage: Int): JaTitlePage {
        val titles = json.optJSONArray("results").objects().mapNotNull { asTitle(it, type) }
            .distinctBy { it.type to it.id }
        // TMDB limits listing/discover pagination; don't request beyond page 500.
        val total = json.optInt("total_pages", requestedPage).coerceIn(1, 500)
        return JaTitlePage(titles, json.optInt("page", requestedPage).coerceIn(1, 500), total)
    }

    /** TMDB's standard movie/popular list: fetch one 20-result page at a time. */
    suspend fun popularMoviesPage(page: Int): JaTitlePage {
        require(page in 1..500) { "Invalid TMDB movie page" }
        val json = fetch("movie/popular", mapOf("page" to page.toString(), "include_adult" to "false"))
        return titlePage(json, "movie", page)
    }

    /** Browse the chosen TMDB movie or TV genre, preserving pagination metadata. */
    suspend fun genreTitlesPage(genre: JaSearchGenre, page: Int): JaTitlePage {
        require(genre.type == "movie" || genre.type == "tv")
        require(page in 1..500) { "Invalid TMDB genre page" }
        val json = fetch("discover/${genre.type}", mapOf(
            "with_genres" to genre.id.toString(),
            "sort_by" to "popularity.desc", "include_adult" to "false", "page" to page.toString()
        ))
        return titlePage(json, genre.type, page)
    }

    // Retain the old entry point for other consumers of the search repository.
    suspend fun genreTitles(genre: JaSearchGenre): List<TitleItem> = genreTitlesPage(genre, 1).titles
}
