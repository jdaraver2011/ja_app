package com.example.ja

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-owned writes survive player composition disposal; no Activity is retained. */
object PlaybackProgress {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    fun save(dao: WatchDao, request: PlaybackRequest?, positionMs: Long) {
        val r = request ?: return
        if (positionMs < 0) return
        scope.launch {
            mutex.withLock {
                try {
                    dao.updateProgress(r.media.id, r.media.type, r.season, r.episode,
                        positionMs, System.currentTimeMillis())
                } catch (error: Exception) { Log.w("JA_PROGRESS", "SAVE_FAILED", error) }
            }
        }
    }
}
