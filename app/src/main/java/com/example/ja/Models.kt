package com.example.ja

import androidx.room.*

data class TitleItem(val id: Int, val title: String, val overview: String, val poster: String?, val backdrop: String?, val type: String, val rating: Double = 0.0) {
    val posterUrl get() = poster?.let { "https://image.tmdb.org/t/p/w500$it" }
    val backdropUrl get() = backdrop?.let { "https://image.tmdb.org/t/p/w780$it" }
}

data class EpisodeItem(val season: Int, val episode: Int, val name: String)

@Entity(tableName = "watch_items", primaryKeys = ["mediaId", "mediaType", "season", "episode"])
data class WatchItem(val mediaId: Int, val mediaType: String, val season: Int = 0, val episode: Int = 0, val title: String, val poster: String?, val favorite: Boolean = false, val positionMs: Long = 0, val updatedAt: Long = System.currentTimeMillis())

@Dao interface WatchDao {
    @Query("SELECT * FROM watch_items ORDER BY updatedAt DESC") suspend fun all(): List<WatchItem>
    @Query("UPDATE watch_items SET positionMs=:position, updatedAt=:updated WHERE mediaId=:id AND mediaType=:type AND season=:season AND episode=:episode")
    suspend fun updateProgress(id: Int, type: String, season: Int, episode: Int, position: Long, updated: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(item: WatchItem)
    @Query("SELECT * FROM watch_items WHERE mediaId=:id AND mediaType=:type AND season=:season AND episode=:episode LIMIT 1") suspend fun find(id: Int, type: String, season: Int, episode: Int): WatchItem?
}

@Database(entities = [WatchItem::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): WatchDao
}
