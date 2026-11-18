package com.example.ja

import android.content.Context
import android.util.Log

/**
 * Resettable source choices plus TV-season preferences learned from confirmed
 * foreground playback. Season keys are independent of the legacy episode
 * override and share its global reset, without touching watch progress.
 */
internal class PreferredSourceReset(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "ja_source_preferences_reset", Context.MODE_PRIVATE
    )

    val enabled: Boolean
        get() = prefs.getBoolean("override_old_history", false)

    /** Clear post-reset episode choices and all newly learned TV-season choices together. */
    fun resetAll(): Boolean {
        val ok = prefs.edit().clear().putBoolean("override_old_history", true).commit()
        Log.i("JA_SERVERS", "PREFERRED_SOURCES_RESET saved=$ok first=vidsrc second=vidfast-vc")
        return ok
    }

    private fun titleKey(request: PlaybackRequest): String =
        "learned:${request.media.type}:${request.media.id}:${request.season}:${request.episode}"

    fun rank(request: PlaybackRequest, choices: List<PlaybackChoice>): List<PlaybackChoice> {
        if (!enabled) return choices
        val learned = prefs.getString(titleKey(request), null)
        fun priority(id: String): Int = when {
            learned != null && id == learned -> 0
            id == "vidsrc" -> 1      // vidsrc.sh
            id == "vidfast-vc" -> 2   // vidfast.vc
            id == "scottsmedia" -> 3  // player.scottsmedia.online
            id == "cinejoy" -> 6
            id == "movy" -> 4
            id == "7movies" -> 5
            else -> 7
        }
        // Preserve existing provider order among other servers.
        val result = choices.withIndex()
            .sortedWith(compareBy<IndexedValue<PlaybackChoice>>(
                { priority(it.value.id) }, { it.index }
            ))
            .map { it.value }
        Log.i("JA_SERVERS", "PREFERRED_RESET_RANK first=${result.firstOrNull()?.id} " +
            "second=${result.getOrNull(1)?.id} learned=${learned ?: "none"}")
        return result
    }

    fun recordVideoStarted(request: PlaybackRequest, sourceId: String) {
        if (!enabled) return
        // A confirmed foreground media start is the only signal that relearns
        // the preference. HTTP availability checks are never enough.
        prefs.edit().putString(titleKey(request), sourceId).apply()
    }
    /** TV seasons learn independently of the older per-episode reset override. */
    private fun tvSeasonKey(request: PlaybackRequest): String? =
        JaTvSeasonSourceRules.key(request.media.type, request.media.id, request.season, request.episode)

    fun rankTvSeason(request: PlaybackRequest, choices: List<PlaybackChoice>): List<PlaybackChoice> {
        val key = tvSeasonKey(request) ?: return choices
        val learned = prefs.getString(key, null)
        val ordered = JaTvSeasonSourceRules.order(choices.map { it.id }, learned)
        val byId = choices.associateBy { it.id }
        val result = ordered.mapNotNull { byId[it] }
        Log.i("JA_SERVERS", "TV_SEASON_SOURCE_ORDER tmdb=${request.media.id} " +
            "season=${request.season} episode=${request.episode} preferred=${learned ?: "none"} " +
            "order=${result.joinToString(",") { it.id }}")
        return result
    }

    fun recordTvSeasonStarted(request: PlaybackRequest, sourceId: String) {
        val key = tvSeasonKey(request) ?: return
        if (sourceId.isBlank()) return
        prefs.edit().putString(key, sourceId).apply()
        Log.i("JA_SERVERS", "TV_SEASON_SOURCE_LEARNED tmdb=${request.media.id} " +
            "season=${request.season} episode=${request.episode} provider=$sourceId")
    }

    fun recordTvSeasonFailure(request: PlaybackRequest, sourceId: String) {
        val key = tvSeasonKey(request) ?: return
        // A failure from another provider must not erase the working choice.
        if (prefs.getString(key, null) != sourceId) return
        prefs.edit().remove(key).apply()
        Log.i("JA_SERVERS", "TV_SEASON_SOURCE_CLEARED tmdb=${request.media.id} " +
            "season=${request.season} episode=${request.episode} provider=$sourceId")
    }

}
