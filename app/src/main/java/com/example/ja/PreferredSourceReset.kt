package com.example.ja

import android.content.Context
import android.util.Log

/**
 * A conservative reset layer over JA's existing per-title server history.
 *
 * The older ServerPlaybackHistory storage is deliberately not modified: its
 * private key schema is not part of this one-file playback baseline. Once the
 * user resets, pre-reset rankings are ignored for every title, and only
 * playback successes recorded AFTER the reset are preferred per title/episode.
 */
internal class PreferredSourceReset(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "ja_source_preferences_reset", Context.MODE_PRIVATE
    )

    val enabled: Boolean
        get() = prefs.getBoolean("override_old_history", false)

    /** A persistent, global reset; clears only this class's post-reset choices. */
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
            else -> 3
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
}
