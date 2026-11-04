package com.example.ja

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
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

class TmdbSearchRepository {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private var genreCache: List<JaSearchGenre>? = null

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
            val department = if (mode == JaSearchMode.ACTORS) "Acting" else "Directing"
            // TMDB categorizes people by their *primary* department. Display matches
            // from the requested department first, but do not incorrectly exclude
            // directors who are primarily known as actors (and vice versa).
            return JaSearchResponse(people = persons.sortedBy { if (it.department == department) 0 else 1 })
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

    suspend fun genreTitles(genre: JaSearchGenre): List<TitleItem> {
        require(genre.type == "movie" || genre.type == "tv")
        val json = fetch("discover/${genre.type}", mapOf(
            "with_genres" to genre.id.toString(),
            "sort_by" to "popularity.desc", "include_adult" to "false", "page" to "1"
        ))
        return json.optJSONArray("results").objects().mapNotNull { asTitle(it, genre.type) }
            .distinctBy { it.id }
    }
}
