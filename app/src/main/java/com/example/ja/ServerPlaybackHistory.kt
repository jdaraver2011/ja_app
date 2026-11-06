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

    /** Working sources are remembered independently for each TV series season. */
    private fun seasonKey(r: PlaybackRequest): String = "${r.media.id}_${r.season}"

    fun recordVideoStarted(r: PlaybackRequest, providerId: String) {
        val title = titleKey(r)
        val count = prefs.getInt("started_count_$providerId", 0).coerceAtMost(99)
        val edit = prefs.edit()
            .putString("last_started_$title", providerId)
            .putInt("started_count_$providerId", count + 1)
            .remove("failed_${title}_$providerId")
        if (r.media.type == "tv" && r.episode > 0) {
            // One successful episode establishes a preferred source only for
            // its own season; a different season can learn another provider.
            edit.putString("last_started_season_${seasonKey(r)}", providerId)
                .remove("failed_season_${seasonKey(r)}_$providerId")
        }
        edit.apply()
    }

    fun recordExplicitFailure(r: PlaybackRequest, providerId: String, reason: String = "") {
        val edit = prefs.edit().putLong(
            "failed_${titleKey(r)}_$providerId", System.currentTimeMillis()
        )
        // Avoid retrying a sandbox-incompatible provider throughout this
        // season, without penalizing working sources in other seasons.
        if (r.media.type == "tv" && r.episode > 0 && reason.contains("SANDBOX", ignoreCase = true)) {
            edit.putLong("failed_season_${seasonKey(r)}_$providerId", System.currentTimeMillis())
        }
        edit.apply()
    }

    /**
     * Migrate existing per-episode successes without leaking the old
     * series-wide v1.1.5z preference into other seasons. The old
     * last_started_series_* value is intentionally not consulted.
     */
    private fun legacySeasonSource(r: PlaybackRequest): String? {
        if (r.media.type != "tv" || r.episode <= 0) return null
        val prefix = "last_started_tv_${r.media.id}_${r.season}_"
        return prefs.all.entries.asSequence()
            .filter { (key, value) -> key.startsWith(prefix) && value is String }
            .map { it.value as String }
            .distinct()
            .maxWithOrNull(compareBy<String> { prefs.getInt("started_count_$it", 0) }
                .thenBy { -defaultPriority(it) })
    }

    /** Only real foreground playback establishes a persistent preference. */
    fun hasForegroundPreference(r: PlaybackRequest): Boolean {
        val saved = if (r.media.type == "tv" && r.episode > 0)
            prefs.getString("last_started_season_${seasonKey(r)}", null) ?: legacySeasonSource(r)
        else prefs.getString("last_started_${titleKey(r)}", null)
        return saved != null
    }

    fun rank(
        request: PlaybackRequest,
        choices: List<PlaybackChoice>,
        responseCodes: Map<String, Int> = emptyMap()
    ): List<PlaybackChoice> {
        val title = titleKey(request)
        // A successful provider is preferred for one TV season at a time.
        // Migrate old episode-specific successes from this season only; never
        // use the older series-wide key from v1.1.5z for a new season.
        // An explicit failure on the requested episode still overrides it.
        val recentSuccessId = if (request.media.type == "tv") {
            if (request.episode > 0) {
                prefs.getString("last_started_season_${seasonKey(request)}", null)
                    ?: legacySeasonSource(request)
            } else prefs.getString("last_started_$title", null)
        } else prefs.getString("last_started_$title", null)
        val now = System.currentTimeMillis()
        return choices.withIndex().sortedWith(
            compareBy<IndexedValue<PlaybackChoice>> { indexed ->
                val candidate = indexed.value
                val code = responseCodes[candidate.id] ?: 0
                val failedAt = prefs.getLong("failed_${title}_${candidate.id}", 0L)
                val seasonFailedAt = if (request.media.type == "tv" && request.episode > 0)
                    prefs.getLong("failed_season_${seasonKey(request)}_${candidate.id}", 0L)
                else 0L
                val cooldown = (failedAt > 0L && now - failedAt in 0 until failureCooldownMs) ||
                    (seasonFailedAt > 0L && now - seasonFailedAt in 0 until failureCooldownMs)
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
                // Do not bias an unplayed TV season toward servers learned in
                // other seasons. Movie-level tie-breaking remains unchanged.
                .thenByDescending { indexed ->
                    if (request.media.type == "tv") 0 else prefs.getInt("started_count_${indexed.value.id}", 0)
                }
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
