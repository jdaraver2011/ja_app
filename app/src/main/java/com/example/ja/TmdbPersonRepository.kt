package com.example.ja

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Person details and credits from TMDB's person endpoint, with no inferred information. */
data class TmdbPersonFilmCredit(
    val title: TitleItem,
    val role: String?,
    val year: String?,
    val popularity: Double
)

data class TmdbPersonDetails(
    val id: Int,
    val name: String,
    val profilePath: String?,
    val knownFor: String?,
    val biography: String?,
    val birthday: String?,
    val deathday: String?,
    val placeOfBirth: String?,
    val alsoKnownAs: List<String>,
    val knownForTitles: List<TmdbPersonFilmCredit>,
    val filmography: List<TmdbPersonFilmCredit>
) {
    val profileUrl: String?
        get() = profilePath?.let { "https://image.tmdb.org/t/p/h632$it" }
}

/** Independent of the stable title catalog, details and playback repositories. */
class TmdbPersonRepository {
    private val client = JaTmdbHttp.client
    private val cache = JaTimedCache<Int, TmdbPersonDetails>(16, 300_000L)

    suspend fun person(credit: TmdbPersonCredit): TmdbPersonDetails = withContext(Dispatchers.IO) {
        require(credit.id > 0) { "TMDB person ID is unavailable" }
        cache.get(credit.id)?.let { return@withContext it }
        val apiKey = BuildConfig.TMDB_API_KEY
        require(apiKey.isNotBlank()) { "TMDB API key is not configured" }
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("api.themoviedb.org")
            .addPathSegment("3")
            .addPathSegment("person")
            .addPathSegment(credit.id.toString())
            .addQueryParameter("language", "en-US")
            .addQueryParameter("append_to_response", "combined_credits")
            .addQueryParameter("api_key", apiKey)
            .build()
        val responseJson = client.newCall(Request.Builder().url(url).get().build())
            .execute().use { response ->
                if (!response.isSuccessful) error("TMDB person request failed (HTTP ${response.code})")
                JSONObject(response.body?.string() ?: error("TMDB returned no person details"))
            }
        parsePerson(responseJson, credit).also { cache.put(credit.id, it) }
    }

    private fun parsePerson(obj: JSONObject, fallback: TmdbPersonCredit): TmdbPersonDetails {
        val department = personText(obj.optString("known_for_department"))
        val creditsObject = obj.optJSONObject("combined_credits")
        val cast = parseCredits(creditsObject?.optJSONArray("cast"), fromCrew = false)
        val crew = parseCredits(creditsObject?.optJSONArray("crew"), fromCrew = true)
        // For actors prioritize their acting appearances; for directors/writers prioritize crew credits.
        val ordered = if (department == "Acting") cast + crew else crew + cast
        val unique = ordered.distinctBy { "${it.title.type}:${it.title.id}" }
        val knownFor = unique.asSequence()
            .filter { it.title.poster != null }
            .sortedByDescending { it.popularity }
            .take(20).toList()
        val filmography = unique.sortedWith(
            compareByDescending<TmdbPersonFilmCredit> { it.year ?: "" }
                .thenByDescending { it.popularity }
        )
        return TmdbPersonDetails(
            id = obj.optInt("id", fallback.id),
            name = personText(obj.optString("name")) ?: fallback.name,
            profilePath = personText(obj.optString("profile_path"))
                ?.takeIf { it.startsWith("/") } ?: fallback.profilePath,
            knownFor = department,
            biography = personText(obj.optString("biography")),
            birthday = personText(obj.optString("birthday")),
            deathday = personText(obj.optString("deathday")),
            placeOfBirth = personText(obj.optString("place_of_birth")),
            alsoKnownAs = obj.optJSONArray("also_known_as").personStrings().distinct().take(8),
            knownForTitles = knownFor,
            filmography = filmography
        )
    }

    private fun parseCredits(array: JSONArray?, fromCrew: Boolean): List<TmdbPersonFilmCredit> =
        array.personObjects().mapNotNull { entry ->
            val mediaType = entry.optString("media_type")
            if (mediaType != "tv" && mediaType != "movie") return@mapNotNull null
            if (entry.optBoolean("adult", false)) return@mapNotNull null
            val id = entry.optInt("id")
            if (id <= 0) return@mapNotNull null
            val title = personText(entry.optString(if (mediaType == "tv") "name" else "title"))
                ?: return@mapNotNull null
            val releaseDate = entry.optString(if (mediaType == "tv") "first_air_date" else "release_date")
            val year = releaseDate.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }
            val popularity = entry.optDouble("popularity", 0.0).takeIf { it.isFinite() } ?: 0.0
            val rating = entry.optDouble("vote_average", 0.0).takeIf { it.isFinite() } ?: 0.0
            TmdbPersonFilmCredit(
                title = TitleItem(
                    id, title, personText(entry.optString("overview")).orEmpty(),
                    personText(entry.optString("poster_path"))?.takeIf { it.startsWith("/") },
                    personText(entry.optString("backdrop_path"))?.takeIf { it.startsWith("/") },
                    mediaType, rating
                ),
                role = personText(entry.optString(if (fromCrew) "job" else "character")),
                year = year,
                popularity = popularity
            )
        }
}

private fun personText(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
private fun JSONArray?.personObjects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONArray?.personStrings(): List<String> = if (this == null) emptyList() else
    (0 until length()).mapNotNull { personText(optString(it)) }
