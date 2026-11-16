package com.example.ja

import android.net.Uri

sealed interface PlaybackSource {
    data class Native(val url: String) : PlaybackSource
    data class Embed(val url: String, val allowedHost: String) : PlaybackSource
}

data class PlaybackRequest(
    val media: TitleItem,
    val season: Int = 0,
    val episode: Int = 0,
    // Retained for compatibility with callers that still pass IMDb metadata.
    // Registered sources use the numeric TMDB ID instead.
    val imdbId: String? = null
)

data class PlaybackChoice(val id: String, val label: String, val source: PlaybackSource)

interface PlaybackProvider {
    val id: String
    val label: String
    fun resolve(request: PlaybackRequest): PlaybackSource?
}

/** Preferred source 1. Uses TMDB IDs directly. */
class AuthorizedEmbedProvider : PlaybackProvider {
    override val id = "vidsrc"
    override val label = "VidSrc (.sh)"
    override fun resolve(request: PlaybackRequest): PlaybackSource? {
        if (!request.valid()) return null
        val path = if (request.media.type == "movie") {
            "/embed/movie/${request.media.id}"
        } else {
            "/embed/tv/${request.media.id}/${request.season}/${request.episode}"
        }
        val url = Uri.Builder().scheme("https").authority("vidsrc.sh")
            .encodedPath(path).build().toString()
        return checkedSource(url, "vidsrc.sh")
    }
}

/** Preferred source 2. Uses TMDB IDs directly. */
class VidFastEmbedProvider : PlaybackProvider {
    override val id = "vidfast-vc"
    override val label = "VidFast (.vc)"
    override fun resolve(request: PlaybackRequest): PlaybackSource? {
        if (!request.valid()) return null
        val path = if (request.media.type == "movie") "/movie/${request.media.id}"
        else "/tv/${request.media.id}/${request.season}/${request.episode}"
        val url = Uri.Builder().scheme("https").authority("vidfast.vc")
            .encodedPath(path)
            .appendQueryParameter("autoPlay", "true")
            .appendQueryParameter("theme", "E5A43B")
            .appendQueryParameter("hideServer", "false")
            .apply { if (request.media.type == "tv") {
                appendQueryParameter("autoNext", "false")
                appendQueryParameter("nextButton", "false")
            } }
            .build().toString()
        return checkedSource(url, "vidfast.vc")
    }
}

/** Preferred source 3. TMDB embeds with URL autoplay. */
class ScottsMediaEmbedProvider : PlaybackProvider {
    override val id = "scottsmedia"
    override val label = "ScottsMedia"
    override fun resolve(request: PlaybackRequest): PlaybackSource? {
        if (!request.valid()) return null
        // TV format follows the VidSrc shape at the user's instruction.
        val path = if (request.media.type == "movie") {
            "/embed/movie/${request.media.id}"
        } else {
            "/embed/tv/${request.media.id}/${request.season}/${request.episode}"
        }
        val url = Uri.Builder().scheme("https").authority("player.scottsmedia.online")
            .encodedPath(path)
            .appendQueryParameter("autoplay", "1")
            .build().toString()
        return checkedSource(url, "player.scottsmedia.online")
    }
}

/** Preferred source 4. CineJoy takes a numeric TMDB movie or television ID. */
class CineJoyEmbedProvider : PlaybackProvider {
    override val id = "cinejoy"
    override val label = "CineJoy"
    override fun resolve(request: PlaybackRequest): PlaybackSource? {
        if (!request.valid()) return null
        val path = if (request.media.type == "movie") {
            "/watch/movie/${request.media.id}"
        } else {
            // CineJoy episode URLs require a real season and episode number.
            if (request.season < 1) return null
            "/watch/tv/${request.media.id}/${request.season}/${request.episode}"
        }
        val url = Uri.Builder().scheme("https").authority("cinejoy.pk")
            .encodedPath(path).build().toString()
        return checkedSource(url, "cinejoy.pk")
    }
}

private fun PlaybackRequest.valid(): Boolean =
    media.id > 0 && media.type in setOf("movie", "tv") &&
        (media.type == "movie" || (season >= 0 && episode > 0))

private fun checkedSource(url: String, host: String): PlaybackSource? {
    val parsed = try { Uri.parse(url) } catch (_: Exception) { return null }
    if (parsed.scheme != "https" || !parsed.host.equals(host, ignoreCase = true)) return null
    return PlaybackSource.Embed(url, host)
}

/**
 * Four registered providers in default preference order. TV seasons may
 * promote their locally learned working provider after this list is built.
 */
class PlaybackRegistry(
    private val providers: List<PlaybackProvider> = listOf(
        AuthorizedEmbedProvider(),
        VidFastEmbedProvider(),
        ScottsMediaEmbedProvider(),
        CineJoyEmbedProvider()
    )
) {
    fun choices(request: PlaybackRequest): List<PlaybackChoice> =
        providers.mapNotNull { adapter ->
            adapter.resolve(request)?.let { PlaybackChoice(adapter.id, adapter.label, it) }
        }.distinctBy {
            when (val source = it.source) {
                is PlaybackSource.Native -> source.url
                is PlaybackSource.Embed -> source.url
            }
        }

    fun resolve(request: PlaybackRequest): PlaybackSource? = choices(request).firstOrNull()?.source
}
