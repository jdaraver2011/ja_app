package com.example.ja

import android.content.Context

/**
 * Stable insertion order for Favorites, separate from WatchItem.updatedAt.
 * WatchItem is also updated for playback progress, so its timestamp cannot
 * represent the date a title became a favorite.
 *
 * Existing favorites are seeded oldest-first once; after that, their
 * positions survive app restarts, playback saves and ordinary refreshes.
 * Removing a favorite drops its ID, so re-favoriting appends it at the end.
 * This intentionally requires no migration of the existing Room database.
 */
internal class FavoritesDisplayOrder(context: Context) {
    private val prefs = context.getSharedPreferences("ja_favorite_display_order_v1", Context.MODE_PRIVATE)

    fun reconcile(type: String, watchItems: List<WatchItem>): List<Int> {
        require(type == "movie" || type == "tv")
        val activeIds = watchItems.asSequence()
            .filter { it.favorite && it.mediaType == type }
            // DAO provides newest-first history. Seed first-time Favorites
            // in original chronological order, then persist the order.
            .sortedWith(compareBy<WatchItem> { it.updatedAt }.thenBy { it.mediaId })
            .map { it.mediaId }
            .distinct()
            .toList()

        val previous = prefs.getString("$type-ids", "").orEmpty()
            .split(',')
            .mapNotNull { it.toIntOrNull() }
            .distinct()
        val activeSet = activeIds.toHashSet()
        val previousSet = previous.toHashSet()
        val ordered = previous.filter { it in activeSet } +
            activeIds.filterNot { it in previousSet }
        if (ordered != previous) {
            prefs.edit().putString("$type-ids", ordered.joinToString(",")).apply()
        }
        return ordered
    }
}
