package com.example.ja

import android.net.Uri

sealed interface PlaybackSource {
    data class Native(val url: String) : PlaybackSource
    data class Embed(val url: String, val allowedHost: String) : PlaybackSource
}

data class PlaybackRequest(val media: TitleItem, val season: Int = 0, val episode: Int = 0)
interface PlaybackProvider { fun resolve(request: PlaybackRequest): PlaybackSource? }

/** Direct URLs must be provided by a service licensed for the requested media. */
class LicensedDirectProvider : PlaybackProvider {
    override fun resolve(request: PlaybackRequest): PlaybackSource? {
        val template = BuildConfig.DIRECT_MEDIA_TEMPLATE.trim()
        if (template.isEmpty()) return null
        val url = expandTemplate(template, request) ?: return null
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null
        val configuredHost = BuildConfig.DIRECT_MEDIA_HOST.trim()
        if (configuredHost.isEmpty() || !uri.host.equals(configuredHost, ignoreCase = true)) return null
        return PlaybackSource.Native(url)
    }
}

/** Web embed fallback. Never treat an HTML embed URL as an ExoPlayer media URL. */
class AuthorizedEmbedProvider : PlaybackProvider {
    override fun resolve(request: PlaybackRequest): PlaybackSource? {
        val template = BuildConfig.AUTHORIZED_EMBED_TEMPLATE.ifBlank {
            if (request.media.type == "movie")
                "https://vidsrc.sh/embed/movie/{id}"
            else
                "https://vidsrc.sh/embed/tv/{id}/{season}/{episode}"
        }
        val url = expandTemplate(template, request) ?: return null
        val uri = Uri.parse(url)
        val host = BuildConfig.AUTHORIZED_EMBED_HOST.ifBlank { "vidsrc.sh" }
        if (uri.scheme != "https" || !uri.host.equals(host, ignoreCase = true)) return null
        return PlaybackSource.Embed(url, host)
    }
}

private fun expandTemplate(template: String, request: PlaybackRequest): String? {
    if (request.media.type !in setOf("movie", "tv") || request.media.id <= 0) return null
    if (request.media.type == "tv" && (request.season < 0 || request.episode <= 0)) return null
    // IMDb placeholders are intentionally not supported: JA now uses TMDB IDs directly.
    if ("{imdb_id}" in template) return null
    val url = template.replace("{id}", request.media.id.toString())
        .replace("{type}", request.media.type)
        .replace("{season}", request.season.toString())
        .replace("{episode}", request.episode.toString())
    if (Regex("\\{[^}]+\\}").containsMatchIn(url)) return null
    return url
}

class PlaybackRegistry(
    private val direct: PlaybackProvider = LicensedDirectProvider(),
    private val embed: PlaybackProvider = AuthorizedEmbedProvider()
) {
    fun resolve(request: PlaybackRequest): PlaybackSource? = direct.resolve(request) ?: embed.resolve(request)
}
