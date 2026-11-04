package com.example.ja

import android.net.Uri

sealed interface PlaybackSource {
    data class Native(val url: String) : PlaybackSource
    data class Embed(val url: String, val allowedHost: String) : PlaybackSource
}

data class PlaybackRequest(val media: TitleItem, val season: Int = 0, val episode: Int = 0)

/** One candidate in the ordered source chain. The source is a URL, not proof of availability. */
data class PlaybackChoice(val id: String, val label: String, val source: PlaybackSource)

/**
 * A media provider resolves identifiers into authorized playback URLs.
 * Add future providers by implementing this interface and registering them in PlaybackRegistry.
 * Do not fetch a cross-origin embed page to guess whether the video actually works.
 */
interface PlaybackProvider {
    val id: String
    val label: String
    fun resolve(request: PlaybackRequest): PlaybackSource?
}

/** Direct URLs must be supplied by a service licensed to serve the requested media. */
class LicensedDirectProvider : PlaybackProvider {
    override val id = "licensed-direct"
    override val label = "Direct"
    override fun resolve(request: PlaybackRequest): PlaybackSource? {
        val template = BuildConfig.DIRECT_MEDIA_TEMPLATE.trim()
        if (template.isEmpty()) return null
        val url = expandTemplate(template, request) ?: return null
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null
        val host = BuildConfig.DIRECT_MEDIA_HOST.trim()
        if (host.isEmpty() || !uri.host.equals(host, ignoreCase = true)) return null
        return PlaybackSource.Native(url)
    }
}

/** Primary website source: preserve JA's known-working VidSrc URLs and optional configuration. */
class AuthorizedEmbedProvider : PlaybackProvider {
    override val id = "vidsrc"
    override val label = "VidSrc"
    override fun resolve(request: PlaybackRequest): PlaybackSource? {
        val template = BuildConfig.AUTHORIZED_EMBED_TEMPLATE.ifBlank {
            if (request.media.type == "movie") "https://vidsrc.sh/embed/movie/{id}"
            else "https://vidsrc.sh/embed/tv/{id}/{season}/{episode}"
        }
        val url = expandTemplate(template, request) ?: return null
        val uri = Uri.parse(url)
        val host = BuildConfig.AUTHORIZED_EMBED_HOST.ifBlank { "vidsrc.sh" }
        if (uri.scheme != "https" || !uri.host.equals(host, ignoreCase = true)) return null
        return PlaybackSource.Embed(url, host)
    }
}

/**
 * Optional secondary embed source. The URL formats and autoPlay/theme parameters
 * are documented at https://vidfast.vc/ (movie and TV embed sections).
 * Only use a provider for titles JA is authorized to play through it.
 */
class VidFastEmbedProvider : PlaybackProvider {
    override val id = "vidfast"
    override val label = "VidFast"
    override fun resolve(request: PlaybackRequest): PlaybackSource? {
        if (!isValidRequest(request)) return null
        val path = if (request.media.type == "movie") {
            "/movie/${request.media.id}"
        } else {
            "/tv/${request.media.id}/${request.season}/${request.episode}"
        }
        val url = Uri.Builder()
            .scheme("https")
            .authority("vidfast.vc")
            .encodedPath(path)
            .appendQueryParameter("autoPlay", "true")
            .appendQueryParameter("theme", "E5A43B")
            .build().toString()
        return PlaybackSource.Embed(url, "vidfast.vc")
    }
}

private fun isValidRequest(request: PlaybackRequest): Boolean =
    request.media.id > 0 && request.media.type in setOf("movie", "tv") &&
        (request.media.type != "tv" || (request.season >= 0 && request.episode > 0))

private fun expandTemplate(template: String, request: PlaybackRequest): String? {
    if (!isValidRequest(request)) return null
    // JA uses numeric TMDB IDs directly; no IMDb conversion or scraping.
    if ("{imdb_id}" in template) return null
    val url = template.replace("{id}", request.media.id.toString())
        .replace("{type}", request.media.type)
        .replace("{season}", request.season.toString())
        .replace("{episode}", request.episode.toString())
    if (Regex("\\{[^}]+\\}").containsMatchIn(url)) return null
    return url
}

/**
 * Ordered, extensible chain. Resolving a source only creates a candidate URL;
 * actual playback success/failure is reported by the video player.
 * To add another authorized source, insert one more PlaybackProvider here.
 */
class PlaybackRegistry(
    private val providers: List<PlaybackProvider> = listOf(
        LicensedDirectProvider(), AuthorizedEmbedProvider(), VidFastEmbedProvider()
    )
) {
    fun choices(request: PlaybackRequest): List<PlaybackChoice> =
        providers.mapNotNull { provider ->
            provider.resolve(request)?.let { PlaybackChoice(provider.id, provider.label, it) }
        }.distinctBy {
            when (val source = it.source) {
                is PlaybackSource.Native -> source.url
                is PlaybackSource.Embed -> source.url
            }
        }

    /** Backwards-compatible first-source lookup for old callers. */
    fun resolve(request: PlaybackRequest): PlaybackSource? = choices(request).firstOrNull()?.source
}
