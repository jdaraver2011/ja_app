package com.example.ja

import android.content.Context

/**
 * Remembers actual GeckoView VIDEO_STARTED callbacks, NOT HTTP 200 responses.
 * A started video is useful playback evidence but may still be a preroll ad;
 * retain automatic explicit-error fallback rather than claiming content verified.
 * Favorites, watch history and remote controls are not modified.
 */
class ServerPlaybackHistory(context: Context) {
    private val prefs = context.getSharedPreferences("ja_playback_server_history_v1", Context.MODE_PRIVATE)
    private val failureCooldownMs = 30 * 60 * 1000L

    private fun titleKey(r: PlaybackRequest): String =
        "${r.media.type}_${r.media.id}_${r.season}_${r.episode}"

    /** One series has one preferred source, regardless of season/episode. */
    private fun seriesKey(r: PlaybackRequest): String = "${r.media.type}_${r.media.id}"

    fun recordVideoStarted(r: PlaybackRequest, providerId: String) {
        val title = titleKey(r)
        val count = prefs.getInt("started_count_$providerId", 0).coerceAtMost(99)
        val edit = prefs.edit()
            .putString("last_started_$title", providerId)
            .putInt("started_count_$providerId", count + 1)
            .remove("failed_${title}_$providerId")
        if (r.media.type == "tv") {
            // A working episode provides evidence for the entire series.
            // Episode-specific failures still take precedence when ranking.
            edit.putString("last_started_series_${seriesKey(r)}", providerId)
                .remove("failed_series_${seriesKey(r)}_$providerId")
        }
        edit.apply()
    }

    fun recordExplicitFailure(r: PlaybackRequest, providerId: String, reason: String = "") {
        val edit = prefs.edit().putLong(
            "failed_${titleKey(r)}_$providerId", System.currentTimeMillis()
        )
        // A definitive iframe restriction is provider-wide for this TV series,
        // unlike a missing individual episode. Don't try it on every episode.
        if (r.media.type == "tv" && reason.contains("SANDBOX", ignoreCase = true)) {
            edit.putLong("failed_series_${seriesKey(r)}_$providerId", System.currentTimeMillis())
        }
        edit.apply()
    }

    /** Promote old per-episode success records after upgrading from v1.1.5y. */
    private fun legacySeriesSource(r: PlaybackRequest): String? {
        if (r.media.type != "tv") return null
        val prefix = "last_started_tv_${r.media.id}_"
        return prefs.all.entries.asSequence()
            .filter { (key, value) -> key.startsWith(prefix) && value is String }
            .map { it.value as String }
            .distinct()
            .maxWithOrNull(compareBy<String> { prefs.getInt("started_count_$it", 0) }
                .thenBy { -defaultPriority(it) })
    }

    fun rank(
        request: PlaybackRequest,
        choices: List<PlaybackChoice>,
        responseCodes: Map<String, Int> = emptyMap()
    ): List<PlaybackChoice> {
        val title = titleKey(request)
        // The series selection is authoritative across *all* episodes.
        // An explicit failure on the current episode still overrides it.
        // Old per-episode data is consulted only before a series preference
        // has been learned in this release.
        val recentSuccessId = if (request.media.type == "tv") {
            prefs.getString("last_started_series_${seriesKey(request)}", null)
                ?: prefs.getString("last_started_$title", null)
                ?: legacySeriesSource(request)
        } else prefs.getString("last_started_$title", null)
        val now = System.currentTimeMillis()
        return choices.withIndex().sortedWith(
            compareBy<IndexedValue<PlaybackChoice>> { indexed ->
                val candidate = indexed.value
                val code = responseCodes[candidate.id] ?: 0
                val failedAt = prefs.getLong("failed_${title}_${candidate.id}", 0L)
                val seriesFailedAt = if (request.media.type == "tv")
                    prefs.getLong("failed_series_${seriesKey(request)}_${candidate.id}", 0L)
                else 0L
                val cooldown = (failedAt > 0L && now - failedAt in 0 until failureCooldownMs) ||
                    (seriesFailedAt > 0L && now - seriesFailedAt in 0 until failureCooldownMs)
                when {
                    cooldown -> 7
                    candidate.source is PlaybackSource.Native -> 0
                    // VidCore repeatedly displayed a restricted-frame error on
                    // the tested GeckoView devices, despite HTTP 200. Retain it
                    // as a last-resort candidate rather than guessing it works.
                    candidate.id == "vidcore" -> 8
                    // A real video-start event is stronger evidence than a
                    // preflight HTTP code (some embeds respond 4xx to probes).
                    // Explicit failure on THIS episode still overrides it.
                    candidate.id == recentSuccessId -> 1
                    code in 400..599 && code != 403 && code != 429 -> 6
                    isPreferred(candidate.id) && code in 200..299 -> 2
                    isPreferred(candidate.id) && (code in 300..399 || code == 403 || code == 429) -> 3
                    code in 200..299 -> 4
                    code in 300..399 || code == 403 || code == 429 -> 5
                    else -> 6
                }
            }.thenBy { indexed -> defaultPriority(indexed.value.id) }
                // General success statistics only break an otherwise equal
                // ranking; a TV-series preference is stored separately.
                .thenByDescending { indexed -> prefs.getInt("started_count_${indexed.value.id}", 0) }
                .thenBy { indexed -> indexed.index }
        ).map { it.value }
    }

    companion object {
        /** Conservative preference order informed by confirmed JA behavior. */
        fun defaultPriority(id: String): Int = when (id) {
            "licensed-direct" -> 0
            "vidsrc-mov" -> 1     // Previously played successfully on the user's device.
            "vidsrc" -> 2         // Existing embedded player and remote behavior.
            "vidcore" -> 98       // Observed sandbox incompatibility; last-resort only.
            "videasy" -> 4        // Documented native autoplay.
            "vidfast-vc" -> 5     // Documented native autoplay; touch behavior still unverified.
            "vidfast-pro" -> 6
            "vidsrc-embed-ru" -> 7
            "vidsrc-embed-su" -> 8
            "2embed" -> 9
            "autoembed" -> 10
            "multiembed" -> 11
            "hnembed" -> 12
            "vaplayer" -> 13
            "hndrama" -> 14
            "111movies" -> 99    // Responded HTTP 200 but displayed only a white player.
            else -> 50
        }

        private fun isPreferred(id: String): Boolean = id in setOf(
            "licensed-direct", "vidsrc-mov", "vidsrc", "videasy",
            "vidfast-vc", "vidfast-pro"
        )
    }
}
