package com.example.ja

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** User-entered searches from the main JA Search tab. */
internal class JaSearchHistory(context: Context) {
    data class Entry(val query: String, val mode: JaSearchMode, val savedAt: Long)

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<Entry> = runCatching {
        val rows = JSONArray(prefs.getString(KEY_ENTRIES, "[]"))
        (0 until rows.length()).mapNotNull { index ->
            runCatching {
                val row = rows.getJSONObject(index)
                val mode = JaSearchMode.valueOf(row.getString("mode"))
                Entry(row.getString("query"), mode, row.optLong("savedAt"))
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    fun record(rawQuery: String, mode: JaSearchMode): List<Entry> {
        val query = rawQuery.trim().replace(Regex("\\s+"), " ").take(200)
        if (query.isBlank()) return all()
        val now = System.currentTimeMillis()
        val entries = (listOf(Entry(query, mode, now)) + all().filterNot {
            it.mode == mode && it.query.equals(query, ignoreCase = true)
        }).take(MAX_ENTRIES)
        val json = JSONArray().apply {
            entries.forEach { put(JSONObject().put("query", it.query).put("mode", it.mode.name).put("savedAt", it.savedAt)) }
        }
        prefs.edit().putString(KEY_ENTRIES, json.toString()).apply()
        return entries
    }

    fun clear(): List<Entry> {
        prefs.edit().remove(KEY_ENTRIES).apply()
        return emptyList()
    }

    companion object {
        const val PREFS = "ja_search_history_v1"
        private const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 20
    }
}
