package com.example.ja

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Session material never enters an Intent, saved UI state, or diagnostic output. */
internal class JaMusicLabVault(context: Context) {
    private val prefs = context.getSharedPreferences("ja_music_lab_session", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("ja.music.lab.arl", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("ja.music.lab.arl", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): String? = runCatching {
        val saved = prefs.getString("session", null) ?: return@runCatching null
        val pieces = saved.split(":")
        require(pieces.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()
    fun save(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val data = Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("session", "$iv:$data").commit()) { "Could not save session" }
    }
    fun clear() { prefs.edit().clear().commit() }
}

internal class JaMusicLabFailure(val reason: String) : Exception(reason)
internal class JaMusicLabAccount(private val arl: String) {
    class Account(val name: String, val id: String, val csrf: String, val license: String)
    class Track(val id: String, val title: String, val artist: String, val duration: Long, val token: String, val album: String = "", val art: String = "")
    class Audio(val url: String, val format: String, val cipher: String)
    private val cookies = mutableListOf<Cookie>()
    private val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, received: List<Cookie>) {
                if (url.host == "www.deezer.com") received.filter { it.name != "arl" }.forEach { cookie ->
                    cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                    cookies.add(cookie)
                }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                if (url.host != "www.deezer.com") return emptyList()
                return cookies.filter { it.matches(url) } + Cookie.Builder().name("arl").value(arl)
                    .hostOnlyDomain("www.deezer.com").path("/").secure().httpOnly().build()
            }
        }).build()
    private fun json(url: HttpUrl, body: JSONObject, stage: String): JSONObject {
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 JA-MusicLab")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw JaMusicLabFailure("${stage}_HTTP_${response.code}")
                return JSONObject(response.body?.string() ?: throw JaMusicLabFailure("${stage}_EMPTY"))
            }
        } catch (e: JaMusicLabFailure) { throw e }
        catch (_: Exception) { throw JaMusicLabFailure("${stage}_NETWORK_OR_RESPONSE") }
    }
    private fun gateway(method: String, args: JSONObject = JSONObject(), csrf: String = "null"): JSONObject {
        val url = "https://www.deezer.com/ajax/gw-light.php".toHttpUrl().newBuilder()
            .addQueryParameter("api_version", "1.0").addQueryParameter("api_token", csrf)
            .addQueryParameter("input", "3").addQueryParameter("method", method).build()
        val result = json(url, args, if (method == "deezer.getUserData") "SESSION" else "TRACK")
        val error = result.opt("error")
        if ((error is JSONObject && error.length() > 0) || (error is JSONArray && error.length() > 0))
            throw JaMusicLabFailure(if (method == "deezer.getUserData") "SESSION_REJECTED" else "TRACK_REJECTED")
        return result.optJSONObject("results") ?: throw JaMusicLabFailure("GATEWAY_RESPONSE")
    }
    fun validate(): Account {
        if (!Regex("[A-Za-z0-9_-]{64,512}").matches(arl)) throw JaMusicLabFailure("SESSION_FORMAT")
        val data = gateway("deezer.getUserData")
        val user = data.optJSONObject("USER") ?: throw JaMusicLabFailure("SESSION_REJECTED")
        val id = user.optString("USER_ID")
        if (id.isBlank() || id == "0") throw JaMusicLabFailure("SESSION_EXPIRED_OR_INVALID")
        val license = user.optJSONObject("OPTIONS")?.optString("license_token").orEmpty()
        return Account(user.optString("BLOG_NAME", "Deezer account"), id, data.optString("checkForm"), license)
    }
    fun track(id: String, account: Account): Track {
        val data = gateway("song.getData", JSONObject().put("SNG_ID", id), account.csrf)
        val token = data.optString("TRACK_TOKEN")
        if (token.isBlank()) throw JaMusicLabFailure("TRACK_TOKEN_UNAVAILABLE")
        return Track(data.optString("SNG_ID", id), data.optString("SNG_TITLE", "Track $id"),
            data.optString("ART_NAME"), data.optLong("DURATION") * 1000, token, data.optString("ALB_TITLE"),
            data.optString("ALB_PICTURE").takeIf { Regex("[a-fA-F0-9]{32}").matches(it) }
                ?.let { "https://e-cdns-images.dzcdn.net/images/cover/$it/500x500-000000-80-0-0.jpg" }.orEmpty())
    }
    fun audio(track: Track, account: Account): Audio {
        if (account.license.isBlank()) throw JaMusicLabFailure("ACCOUNT_HAS_NO_PLAYBACK_LICENSE")
        // FULL only, using this account's license. No generated/legacy CDN URL or preview fallback.
        val formats = JSONArray().put(JSONObject().put("cipher", "BF_CBC_STRIPE").put("format", "MP3_128"))
        val body = JSONObject().put("license_token", account.license)
            .put("media", JSONArray().put(JSONObject().put("type", "FULL").put("formats", formats)))
            .put("track_tokens", JSONArray().put(track.token))
        val result = json("https://media.deezer.com/v1/get_url".toHttpUrl(), body, "AUDIO")
        val item = result.optJSONArray("data")?.optJSONObject(0) ?: throw JaMusicLabFailure("AUDIO_RESPONSE")
        val errors = item.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val code = errors.optJSONObject(0)?.optInt("code", 0) ?: 0
            throw JaMusicLabFailure("AUDIO_ACCESS_OR_FORMAT_$code")
        }
        val media = item.optJSONArray("media")?.optJSONObject(0) ?: throw JaMusicLabFailure("AUDIO_NOT_PROVIDED")
        val cipher = media.optJSONObject("cipher")?.optString("type", "BF_CBC_STRIPE") ?: "BF_CBC_STRIPE"
        if (cipher !in listOf("NONE", "BF_CBC_STRIPE")) throw JaMusicLabFailure("AUDIO_CIPHER_UNSUPPORTED")
        val url = media.optJSONArray("sources")?.optJSONObject(0)?.optString("url").orEmpty()
        val parsed = runCatching { url.toHttpUrl() }.getOrNull() ?: throw JaMusicLabFailure("AUDIO_URL_INVALID")
        if (!parsed.isHttps || !(parsed.host == "dzcdn.net" || parsed.host.endsWith(".dzcdn.net")))
            throw JaMusicLabFailure("AUDIO_HOST_UNSUPPORTED")
        return Audio(url, media.optString("format", "MP3_128"), cipher)
    }
    fun cancel() { client.dispatcher.cancelAll(); cookies.clear() }
}
