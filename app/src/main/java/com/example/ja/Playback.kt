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
    /** Some providers need an IMDb title/series ID instead of JA's TMDB ID. */
    val imdbId: String? = null
)

data class PlaybackChoice(val id: String, val label: String, val source: PlaybackSource)

interface PlaybackProvider {
    val id: String
    val label: String
    fun resolve(request: PlaybackRequest): PlaybackSource?
}

class LicensedDirectProvider : PlaybackProvider {
    override val id = "licensed-direct"
    override val label = "Direct"
    override fun resolve(request: PlaybackRequest): PlaybackSource? {
        val template = BuildConfig.DIRECT_MEDIA_TEMPLATE.trim()
        val host = BuildConfig.DIRECT_MEDIA_HOST.trim()
        if (template.isBlank() || host.isBlank() || !request.valid()) return null
        val url = template.replace("{id}", request.media.id.toString())
            .replace("{type}", request.media.type)
            .replace("{season}", request.season.toString())
            .replace("{episode}", request.episode.toString())
            .replace("{imdb_id}", request.imdbId.orEmpty())
        if (Regex("\\{[^}]+\\}").containsMatchIn(url)) return null
        if ("{imdb_id}" in template && !request.validImdb()) return null
        return checkedSource(url, host, native = true)
    }
}

class AuthorizedEmbedProvider : PlaybackProvider {
    override val id = "vidsrc"
    override val label = "VidSrc (.sh)"
    override fun resolve(request: PlaybackRequest): PlaybackSource? {
        if (!request.valid()) return null
        val template = BuildConfig.AUTHORIZED_EMBED_TEMPLATE.ifBlank {
            if (request.media.type == "movie") "https://vidsrc.sh/embed/movie/{id}"
            else "https://vidsrc.sh/embed/tv/{id}/{season}/{episode}"
        }
        val host = BuildConfig.AUTHORIZED_EMBED_HOST.ifBlank { "vidsrc.sh" }
        val url = template.replace("{id}", request.media.id.toString())
            .replace("{type}", request.media.type)
            .replace("{season}", request.season.toString())
            .replace("{episode}", request.episode.toString())
            .replace("{imdb_id}", request.imdbId.orEmpty())
        if ("{imdb_id}" in template && !request.validImdb()) return null
        if (Regex("\\{[^}]+\\}").containsMatchIn(url)) return null
        return checkedSource(url, host)
    }
}

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
            .appendQueryParameter("theme", "E5A43B").build().toString()
        return checkedSource(url, "vidfast.vc")
    }
}

/**
 * URL adapters taken from the attached IMDB VidSrc Player userscript.
 * These are candidate embeddable URLs, NOT guaranteed playable streams.
 * A non-matching IMDb ID is never guessed from a TMDB numeric ID.
 */
private class ImdbSiteProvider(
    override val id: String,
    override val label: String,
    private val host: String,
    private val movieUrl: (String) -> String,
    private val episodeUrl: (String, Int, Int) -> String
) : PlaybackProvider {
    override fun resolve(request: PlaybackRequest): PlaybackSource? {
        if (!request.valid() || !request.validImdb()) return null
        val imdb = request.imdbId!!
        val url = if (request.media.type == "movie") movieUrl(imdb)
        else episodeUrl(imdb, request.season, request.episode)
        return checkedSource(url, host)
    }
}

private fun PlaybackRequest.valid(): Boolean =
    media.id > 0 && media.type in setOf("movie", "tv") &&
        (media.type == "movie" || (season >= 0 && episode > 0))

private fun PlaybackRequest.validImdb(): Boolean = imdbId?.matches(Regex("tt[0-9]+")) == true

private fun checkedSource(url: String, host: String, native: Boolean = false): PlaybackSource? {
    val parsed = try { Uri.parse(url) } catch (_: Exception) { return null }
    if (parsed.scheme != "https" || !parsed.host.equals(host, ignoreCase = true)) return null
    return if (native) PlaybackSource.Native(url) else PlaybackSource.Embed(url, host)
}

/** Register another authorized provider here; no player-screen edits needed. */
class PlaybackRegistry(
    private val providers: List<PlaybackProvider> = listOf(
        LicensedDirectProvider(),
        AuthorizedEmbedProvider(),
        VidFastEmbedProvider(),
        ImdbSiteProvider("vidsrc-embed-ru", "VidSrc (.ru)", "vidsrc-embed.ru",
            { "https://vidsrc-embed.ru/embed/$it/" },
            { id, s, e -> "https://vidsrc-embed.ru/embed/$id/$s-$e/" }),
        ImdbSiteProvider("vidsrc-embed-su", "VidSrc (.su)", "vidsrc-embed.su",
            { "https://vidsrc-embed.su/embed/$it/" },
            { id, s, e -> "https://vidsrc-embed.su/embed/$id/$s-$e/" }),
        ImdbSiteProvider("111movies", "111Movies", "111movies.com",
            { "https://111movies.com/movie/$it" },
            { id, s, e -> "https://111movies.com/tv/$id/$s/$e" }),
        ImdbSiteProvider("2embed", "2Embed", "www.2embed.cc",
            { "https://www.2embed.cc/embed/$it" },
            { id, s, e -> "https://www.2embed.cc/embedtv/$id?s=$s&e=$e" }),
        ImdbSiteProvider("vidfast-pro", "VidFast (.pro)", "vidfast.pro",
            { "https://vidfast.pro/movie/$it" },
            { id, s, e -> "https://vidfast.pro/tv/$id/$s/$e" }),
        ImdbSiteProvider("vidsrc-mov", "VidSrc (.mov)", "vidsrc.mov",
            { "https://vidsrc.mov/embed/movie/$it" },
            { id, s, e -> "https://vidsrc.mov/embed/tv/$id/$s/$e" }),
        ImdbSiteProvider("autoembed", "AutoEmbed", "autoembed.co",
            { "https://autoembed.co/movie/imdb/$it" },
            { id, s, e -> "https://autoembed.co/tv/imdb/$id-$s-$e" }),
        ImdbSiteProvider("multiembed", "MultiEmbed", "multiembed.mov",
            { "https://multiembed.mov/?video_id=$it&tmdb=0" },
            { id, s, e -> "https://multiembed.mov/?video_id=$id&tmdb=0&s=$s&e=$e" }),
        ImdbSiteProvider("hnembed", "HnEmbed", "hnembed.cc",
            { "https://hnembed.cc/embed/movie/$it" },
            { id, s, e -> "https://hnembed.cc/embed/tv/$id/$s/$e" }),
        ImdbSiteProvider("vaplayer", "VaPlayer", "vaplayer.ru",
            { "https://vaplayer.ru/embed/movie/$it" },
            { id, s, e -> "https://vaplayer.ru/embed/tv/$id/$s/$e" }),
        ImdbSiteProvider("hndrama", "HnDrama", "hndrama.cc",
            { "https://hndrama.cc/embed/movie/$it" },
            { id, s, e -> "https://hndrama.cc/embed/tv/$id/$s/$e" })
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
