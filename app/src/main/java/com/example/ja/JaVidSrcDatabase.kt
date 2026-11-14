package com.example.ja

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File

/** Separate from ja.db: watch history and favorites require no migration. All calls run on IO. */
internal class JaVidSrcDatabase(context: Context) : SQLiteOpenHelper(context, "ja_vidsrc_catalog.db", null, 1) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta(name TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE ids(generation INTEGER NOT NULL, kind INTEGER NOT NULL, media_id INTEGER NOT NULL, season INTEGER NOT NULL, episode INTEGER NOT NULL, PRIMARY KEY(generation,kind,media_id,season,episode)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE quality(media_id INTEGER PRIMARY KEY, label TEXT NOT NULL, checked INTEGER NOT NULL, retry_after INTEGER NOT NULL, title_json TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    private fun meta(db: SQLiteDatabase, key: String): Long = db.rawQuery("SELECT value FROM meta WHERE name=?", arrayOf(key)).use { if(it.moveToFirst())it.getLong(0) else 0L }
    private fun put(db: SQLiteDatabase, key: String, value: Long) {
        val values=ContentValues().apply { put("name",key);put("value",value) }
        check(db.insertWithOnConflict("meta",null,values,SQLiteDatabase.CONFLICT_REPLACE) >= 0) { "Could not save source snapshot metadata" }
    }
    fun snapshot(): JaVidSrcCatalog.Snapshot {
        val db=readableDatabase; val generation=meta(db,"generation")
        if(generation==0L)return JaVidSrcCatalog.Snapshot()
        val movies=mutableSetOf<Int>(); val shows=mutableSetOf<Int>()
        db.rawQuery("SELECT kind,media_id FROM ids WHERE generation=? AND kind IN (1,2)",arrayOf(generation.toString())).use { c ->
            while(c.moveToNext())if(c.getInt(0)==1)movies.add(c.getInt(1))else shows.add(c.getInt(1))
        }
        return JaVidSrcCatalog.Snapshot(true,generation,meta(db,"synced"),movies,shows,meta(db,"episodes"))
    }
    fun install(files: Map<Int,File>, now: Long): JaVidSrcCatalog.Snapshot {
        val db=writableDatabase
        db.beginTransaction()
        try {
            val generation=maxOf(now,meta(db,"generation")+1L)
            val statement=db.compileStatement("INSERT OR IGNORE INTO ids(generation,kind,media_id,season,episode) VALUES(?,?,?,?,?)")
            statement.use { insert ->
                for((kind,file) in files) {
                    file.inputStream().use { input -> JaVidSrcRules.read(input,kind) { key ->
                        insert.bindLong(1,generation);insert.bindLong(2,kind.toLong());insert.bindLong(3,key.id.toLong())
                        insert.bindLong(4,key.season.toLong());insert.bindLong(5,key.episode.toLong());insert.executeInsert()
                    } }
                    val count=db.rawQuery("SELECT COUNT(*) FROM ids WHERE generation=? AND kind=?",arrayOf(generation.toString(),kind.toString())).use { it.moveToFirst();it.getLong(0) }
                    if(!JaVidSrcRules.safeCount(meta(db,"count_$kind"),count))error("Incomplete ID snapshot; kept previous catalogue")
                    put(db,"count_$kind",count)
                    if(kind==3)put(db,"episodes",count)
                }
            }
            require(files.keys==setOf(1,2,3)) { "All three ID feeds are required" }
            put(db,"generation",generation);put(db,"synced",now)
            db.delete("ids","generation<>?",arrayOf(generation.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return snapshot()
    }
    data class Quality(val id: Int, val label: String, val checked: Long, val retryAfter: Long, val title: TitleItem)
    fun qualities(): List<Quality> {
        val out=mutableListOf<Quality>()
        readableDatabase.rawQuery("SELECT media_id,label,checked,retry_after,title_json FROM quality",null).use { c ->
            while(c.moveToNext())try {
                val j=JSONObject(c.getString(4)); val id=c.getInt(0)
                out.add(Quality(id,c.getString(1),c.getLong(2),c.getLong(3),TitleItem(id,j.getString("title"),j.optString("overview"),
                    j.optString("poster").takeIf { it.startsWith('/') },j.optString("backdrop").takeIf { it.startsWith('/') },"movie",j.optDouble("rating",0.0))))
            }catch(_:Exception) { /* Corrupt quality metadata never changes catalogue membership. */ }
        }
        return out
    }
    fun save(record: Quality) {
        val t=record.title
        val j=JSONObject().put("title",t.title).put("overview",t.overview).put("poster",t.poster).put("backdrop",t.backdrop).put("rating",t.rating)
        val values=ContentValues().apply {
            put("media_id",record.id);put("label",record.label);put("checked",record.checked);put("retry_after",record.retryAfter);put("title_json",j.toString())
        }
        check(writableDatabase.insertWithOnConflict("quality",null,values,SQLiteDatabase.CONFLICT_REPLACE) >= 0) { "Could not save quality result" }
    }
    fun episodeListed(show: Int, season: Int, episode: Int): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM ids WHERE generation=(SELECT value FROM meta WHERE name='generation') AND kind=3 AND media_id=? AND season=? AND episode=? LIMIT 1",
        arrayOf(show.toString(),season.toString(),episode.toString())).use { it.moveToFirst() }
}
