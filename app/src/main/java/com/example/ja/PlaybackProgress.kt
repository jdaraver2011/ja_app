package com.example.ja

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-owned writes survive player composition disposal; no Activity is retained. */
object PlaybackProgress {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    suspend fun setWatched(dao: WatchDao, id: Int, type: String, season: Int, episode: Int, watched: Boolean) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // An unwatched episode starts fresh instead of immediately resuming
                // at the 90-percent completion position and marking itself again.
                if (!watched) dao.updateProgress(id, type, season, episode, 0L, System.currentTimeMillis())
                WatchedStore.set(id, type, season, episode, watched)
            }
        }
    }
    fun save(dao: WatchDao, request: PlaybackRequest?, positionMs: Long, durationMs: Long = 0L) {
        val r = request ?: return
        if (positionMs < 0) return
        scope.launch {
            mutex.withLock {
                try {
                    dao.updateProgress(r.media.id, r.media.type, r.season, r.episode,
                        positionMs, System.currentTimeMillis())
                    if (JaWatchRules.isWatched(positionMs, durationMs)) {
                        WatchedStore.set(r.media.id, r.media.type, r.season, r.episode, true)
                    }
                } catch (error: Exception) { Log.w("JA_PROGRESS", "SAVE_FAILED", error) }
            }
        }
    }
}
