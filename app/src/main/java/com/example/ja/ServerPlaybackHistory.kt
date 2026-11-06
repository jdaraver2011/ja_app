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

    fun recordVideoStarted(r: PlaybackRequest, providerId: String) {
        val title = titleKey(r)
        val count = prefs.getInt("started_count_$providerId", 0).coerceAtMost(99)
        prefs.edit()
            .putString("last_started_$title", providerId)
            .putInt("started_count_$providerId", count + 1)
            .remove("failed_${title}_$providerId")
            .apply()
    }

    fun recordExplicitFailure(r: PlaybackRequest, providerId: String) {
        prefs.edit().putLong("failed_${titleKey(r)}_$providerId", System.currentTimeMillis()).apply()
    }

    fun rank(
        request: PlaybackRequest,
        choices: List<PlaybackChoice>,
        responseCodes: Map<String, Int> = emptyMap()
    ): List<PlaybackChoice> {
        val title = titleKey(request)
        val recentSuccessId = prefs.getString("last_started_$title", null)
        val now = System.currentTimeMillis()
        return choices.withIndex().sortedWith(
            compareBy<IndexedValue<PlaybackChoice>> { indexed ->
                val candidate = indexed.value
                val code = responseCodes[candidate.id] ?: 0
                val failedAt = prefs.getLong("failed_${title}_${candidate.id}", 0L)
                val cooldown = failedAt > 0L && now - failedAt in 0 until failureCooldownMs
                when {
                    cooldown -> 7
                    // A concrete 404/5xx is a stronger negative signal than old success.
                    code in 400..599 && code != 403 && code != 429 -> 6
                    candidate.source is PlaybackSource.Native -> 0
                    candidate.id == recentSuccessId -> 1
                    isPreferred(candidate.id) && code in 200..299 -> 2
                    isPreferred(candidate.id) && (code in 300..399 || code == 403 || code == 429) -> 3
                    code in 200..299 -> 4
                    code in 300..399 || code == 403 || code == 429 -> 5
                    else -> 6
                }
            }.thenByDescending { indexed ->
                prefs.getInt("started_count_${indexed.value.id}", 0)
            }.thenBy { indexed -> defaultPriority(indexed.value.id) }
                .thenBy { indexed -> indexed.index }
        ).map { it.value }
    }

    companion object {
        /** Conservative preference order informed by confirmed JA behavior. */
        fun defaultPriority(id: String): Int = when (id) {
            "licensed-direct" -> 0
            "vidsrc-mov" -> 1     // Previously played successfully on the user's device.
            "vidsrc" -> 2         // Existing embedded player and remote behavior.
            "vidcore" -> 3        // Documented native autoplay.
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
            "licensed-direct", "vidsrc-mov", "vidsrc", "vidcore", "videasy",
            "vidfast-vc", "vidfast-pro"
        )
    }
}
