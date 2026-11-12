package com.example.ja

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persistent watched flags, independent of favorites and resume positions. */
internal object WatchedStore {
    private var prefs: SharedPreferences? = null
    private val mutable = MutableStateFlow<Set<String>>(emptySet())
    val watched = mutable.asStateFlow()
    fun key(id: Int, type: String, season: Int = 0, episode: Int = 0) = "$type:$id:$season:$episode"
    @Synchronized fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("ja_watched_v1", Context.MODE_PRIVATE)
        mutable.value = prefs!!.getStringSet("watched", emptySet()).orEmpty().toSet()
    }
    /** Call on IO; serialize manual and automatic updates and persist before publishing. */
    @Synchronized fun set(id: Int, type: String, season: Int, episode: Int, watched: Boolean) {
        val store = checkNotNull(prefs) { "Watch tracking is not initialized" }
        val key = key(id, type, season, episode)
        if ((key in mutable.value) == watched) return
        val next = if (watched) mutable.value + key else mutable.value - key
        check(store.edit().putStringSet("watched", next).commit()) { "Could not save watched status" }
        mutable.value = next
    }
}
