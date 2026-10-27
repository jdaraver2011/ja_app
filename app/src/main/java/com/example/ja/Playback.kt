package com.example.ja

import android.net.Uri

sealed interface PlaybackSource {
    data class Native(val url: String) : PlaybackSource
    data class Embed(val url: String, val allowedHost: String) : PlaybackSource
}

data class PlaybackRequest(val media: TitleItem, val season: Int = 0, val episode: Int = 0)
interface PlaybackProvider { fun resolve(request: PlaybackRequest): PlaybackSource? }

/** Supply only media URLs for which you have playback rights. */
class LicensedDirectProvider(private val resolver: (PlaybackRequest) -> String? = { null }) : PlaybackProvider {
    override fun resolve(request: PlaybackRequest): PlaybackSource? = resolver(request)?.takeIf { Uri.parse(it).scheme == "https" }?.let(PlaybackSource::Native)
}

/** Generic, explicitly configured embed adapter. No scraping, DRM bypass or stream extraction. */
class AuthorizedEmbedProvider : PlaybackProvider {
    override fun resolve(request: PlaybackRequest): PlaybackSource? {
        val template = BuildConfig.AUTHORIZED_EMBED_TEMPLATE
        val host = BuildConfig.AUTHORIZED_EMBED_HOST
        if (template.isBlank() || host.isBlank()) return null
        val url = template.replace("{id}", request.media.id.toString())
            .replace("{type}", request.media.type).replace("{season}", request.season.toString())
            .replace("{episode}", request.episode.toString())
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || !uri.host.equals(host, ignoreCase = true)) return null
        return PlaybackSource.Embed(url, host)
    }
}

class PlaybackRegistry(private val providers: List<PlaybackProvider> = listOf(LicensedDirectProvider(), AuthorizedEmbedProvider())) {
    fun resolve(request: PlaybackRequest): PlaybackSource? = providers.firstNotNullOfOrNull { it.resolve(request) }
}
