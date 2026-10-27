package com.example.ja

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLEncoder

class TmdbRepository {
    private val client = OkHttpClient()
    private suspend fun fetch(path: String): JSONObject = withContext(Dispatchers.IO) {
        require(BuildConfig.TMDB_API_KEY.isNotBlank()) { "Set TMDB_API_KEY in local.properties" }
        val separator = if ('?' in path) '&' else '?'
        val url = "https://api.themoviedb.org/3/$path${separator}api_key=${BuildConfig.TMDB_API_KEY}"
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("TMDB HTTP ${response.code}")
            JSONObject(response.body?.string() ?: error("Empty TMDB response"))
        }
    }
    private fun parse(obj: JSONObject, type: String): TitleItem = TitleItem(
        obj.optInt("id"), obj.optString(if (type == "tv") "name" else "title"),
        obj.optString("overview"), obj.optString("poster_path").takeIf { it.startsWith("/") },
        obj.optString("backdrop_path").takeIf { it.startsWith("/") }, type, obj.optDouble("vote_average", 0.0)
    )
    private fun list(array: JSONArray, fallback: String): List<TitleItem> = (0 until array.length()).mapNotNull { i ->
        val obj = array.optJSONObject(i) ?: return@mapNotNull null
        val type = obj.optString("media_type", fallback)
        if (type == "movie" || type == "tv") parse(obj, type) else null
    }
    suspend fun trending(): List<TitleItem> = list(fetch("trending/all/week").getJSONArray("results"), "movie")
    suspend fun movies(): List<TitleItem> = list(fetch("movie/popular").getJSONArray("results"), "movie")
    suspend fun shows(): List<TitleItem> = list(fetch("tv/popular").getJSONArray("results"), "tv")
    suspend fun search(query: String): List<TitleItem> = list(fetch("search/multi?query=${URLEncoder.encode(query, "UTF-8")}").getJSONArray("results"), "movie")
    suspend fun seasons(id: Int): List<Int> {
        val arr = fetch("tv/$id").getJSONArray("seasons")
        return (0 until arr.length()).map { arr.getJSONObject(it).getInt("season_number") }.filter { it > 0 }
    }
    suspend fun episodes(id: Int, season: Int): List<EpisodeItem> {
        val arr = fetch("tv/$id/season/$season").getJSONArray("episodes")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { EpisodeItem(season, it.getInt("episode_number"), it.optString("name")) }
    }
}
