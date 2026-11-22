package com.example.ja

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class JaAccountSession(val email: String, val accessToken: String, val refreshToken: String, val userId: String)

/** Supabase Auth and one RLS-protected JSON row per JA account. The publishable key is not a secret. */
internal class JaAccountSync(private val context: Context, private val dao: WatchDao) {
    companion object { const val AUTH_REDIRECT = "ja://auth/callback" }
    private val prefs = context.getSharedPreferences("ja_account_session", Context.MODE_PRIVATE)
    private val http = OkHttpClient()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val baseUrl get() = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val apiKey get() = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    private val authPrefs = context.getSharedPreferences("ja_account_sync_meta", Context.MODE_PRIVATE)

    fun session(): JaAccountSession? = runCatching {
        val encrypted = prefs.getString("session", null) ?: return null
        val pieces = Base64.decode(encrypted, Base64.NO_WRAP)
        val iv = pieces.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val data = JSONObject(String(cipher.doFinal(pieces.copyOfRange(12, pieces.size)), Charsets.UTF_8))
        JaAccountSession(data.getString("email"), data.getString("access_token"), data.getString("refresh_token"), data.getString("user_id"))
    }.getOrNull()

    private fun saveSession(value: JaAccountSession) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(JSONObject().put("email", value.email).put("access_token", value.accessToken)
            .put("refresh_token", value.refreshToken).put("user_id", value.userId).toString().toByteArray(Charsets.UTF_8))
        prefs.edit().putString("session", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("ja_supabase_session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("ja_supabase_session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    suspend fun signUp(email: String, password: String): String = withContext(Dispatchers.IO) {
        val redirect = URLEncoder.encode(AUTH_REDIRECT, "UTF-8")
        val response = request("POST", "$baseUrl/auth/v1/signup?redirect_to=$redirect", body = JSONObject().put("email", email).put("password", password))
        if (response.optString("access_token").isBlank()) {
            "Account created. Verify your email, then wait for a JA administrator to approve the account before signing in."
        } else {
            val created = authSession(response, email)
            if (!isApproved(created)) "Account created. A JA administrator must approve it before you can continue."
            else { saveSession(created); "Account created and approved." }
        }
    }

    suspend fun sendPasswordReset(email: String): String = withContext(Dispatchers.IO) {
        check(email.contains("@")) { "Enter the account email first." }
        check(baseUrl.startsWith("https://") && apiKey.isNotBlank()) { "Supabase settings are not configured in this test build." }
        val redirect = URLEncoder.encode(AUTH_REDIRECT, "UTF-8")
        request("POST", "$baseUrl/auth/v1/recover?redirect_to=$redirect", body = JSONObject().put("email", email))
        prefs.edit().putString("recovery_email", email).apply()
        "If that email has a JA account, Supabase will send a password reset link. Open it on this device with JA installed."
    }

    suspend fun completePasswordReset(accessToken: String, refreshToken: String, newPassword: String): String = withContext(Dispatchers.IO) {
        check(newPassword.length >= 6) { "Use a password with at least 6 characters." }
        val claims = jwtClaims(accessToken)
        val updated = request("PUT", "$baseUrl/auth/v1/user", token = accessToken, body = JSONObject().put("password", newPassword))
        val userId = updated.optString("id").ifBlank { claims.optString("sub") }
        val email = updated.optString("email").ifBlank { claims.optString("email").ifBlank { prefs.getString("recovery_email", "").orEmpty() } }
        check(userId.isNotBlank() && email.isNotBlank()) { "Password changed, but JA could not read the account details. Sign in again." }
        val next = JaAccountSession(email, accessToken, refreshToken, userId)
        prefs.edit().remove("recovery_email").apply()
        if (!isApproved(next)) {
            prefs.edit().remove("session").apply()
            return@withContext "Password updated. Your JA account still needs administrator approval before you can enter."
        }
        saveSession(next)
        "Password updated. You can continue into JA."
    }

    suspend fun acceptEmailConfirmation(accessToken: String, refreshToken: String): String = withContext(Dispatchers.IO) {
        val claims = jwtClaims(accessToken)
        val email = claims.optString("email").ifBlank { prefs.getString("recovery_email", "").orEmpty() }
        val userId = claims.optString("sub")
        check(email.isNotBlank() && userId.isNotBlank()) { "The confirmation link is incomplete. Return to JA and sign in." }
        val next = JaAccountSession(email, accessToken, refreshToken, userId)
        if (!isApproved(next)) return@withContext "Email verified. A JA administrator must approve your account before you can continue."
        saveSession(next)
        prefs.edit().remove("recovery_email").apply()
        "Email verified. You can continue into JA."
    }

    private fun jwtClaims(accessToken: String): JSONObject = runCatching {
        val part = accessToken.split('.')[1]
        JSONObject(String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8))
    }.getOrElse { error("Supabase returned an invalid recovery token.") }

    suspend fun signIn(email: String, password: String) = withContext(Dispatchers.IO) {
        val response = request("POST", "$baseUrl/auth/v1/token?grant_type=password", body = JSONObject().put("email", email).put("password", password))
        val result = authSession(response, email)
        if (!isApproved(result)) error("Your JA account is waiting for administrator approval.")
        saveSession(result)
        result
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        session()?.let { runCatching { request("POST", "$baseUrl/auth/v1/logout", token = it.accessToken) } }
        prefs.edit().remove("session").apply()
        authPrefs.edit().clear().apply()
    }

    private fun authSession(response: JSONObject, email: String): JaAccountSession {
        val user = response.optJSONObject("user") ?: error("Supabase returned an incomplete account response.")
        return JaAccountSession(email, response.getString("access_token"), response.getString("refresh_token"), user.getString("id"))
    }

    private suspend fun validSession(): JaAccountSession = withContext(Dispatchers.IO) {
        val current = session() ?: error("Sign in to your JA account first.")
        // Refresh on demand to keep the access token valid without storing the password.
        val valid = try { request("GET", "$baseUrl/auth/v1/user", token = current.accessToken); current }
        catch (_: Exception) {
            val refreshed = request("POST", "$baseUrl/auth/v1/token?grant_type=refresh_token", body = JSONObject().put("refresh_token", current.refreshToken))
            val user = refreshed.optJSONObject("user")
            val next = JaAccountSession(current.email, refreshed.getString("access_token"), refreshed.getString("refresh_token"), user?.optString("id")?.ifBlank { null } ?: current.userId)
            next
        }
        if (!isApproved(valid)) {
            prefs.edit().remove("session").apply()
            error("Your JA account is awaiting administrator approval.")
        }
        saveSession(valid)
        valid
    }

    private suspend fun isApproved(value: JaAccountSession): Boolean {
        val rows = request("GET", "$baseUrl/rest/v1/ja_user_access?select=approved&user_id=eq.${value.userId}",
            token = value.accessToken, acceptArray = true).optJSONArray("data")
        return rows?.optJSONObject(0)?.optBoolean("approved", false) == true
    }

    suspend fun sync(): String = withContext(Dispatchers.IO) {
        check(baseUrl.startsWith("https://") && apiKey.isNotBlank()) { "Supabase settings are not configured in this test build." }
        val account = validSession()
        val local = snapshot()
        val localHash = stateHash(local)
        val fetched = request("GET", "$baseUrl/rest/v1/ja_user_state?select=state&user_id=eq.${account.userId}", token = account.accessToken, acceptArray = true)
        val remote = fetched.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("state")
        val oldLocalHash = authPrefs.getString("last_local_hash", null)
        val oldRemoteHash = authPrefs.getString("last_remote_hash", null)
        if (remote == null) {
            upload(account, local)
            authPrefs.edit().putString("last_local_hash", localHash).putString("last_remote_hash", localHash).apply()
            return@withContext "This device's JA data was uploaded to your account."
        }
        val remoteHash = stateHash(remote)
        if (oldLocalHash == null && oldRemoteHash == null) {
            if (stateHash(local) != stateHash(emptySnapshot())) {
                error("Cloud data already exists for this account. Choose Download cloud data or Upload this device before the first sync.")
            }
            applySnapshot(remote)
            val applied = stateHash(snapshot())
            authPrefs.edit().putString("last_local_hash", applied).putString("last_remote_hash", remoteHash).apply()
            return@withContext "Downloaded your saved data from your account."
        }
        val localChanged = oldLocalHash != null && oldLocalHash != localHash
        val remoteChanged = oldRemoteHash != null && oldRemoteHash != remoteHash
        if (remoteChanged && !localChanged) {
            applySnapshot(remote)
            val applied = stateHash(snapshot())
            authPrefs.edit().putString("last_local_hash", applied).putString("last_remote_hash", remoteHash).apply()
            "Downloaded your latest saved data from your account."
        } else if (localChanged && remoteChanged && localHash != remoteHash) {
            error("Both this device and the cloud changed since the previous sync. Choose Upload this device or Download cloud data.")
        } else {
            if (localChanged || oldLocalHash == null) upload(account, local)
            authPrefs.edit().putString("last_local_hash", localHash).putString("last_remote_hash", localHash).apply()
            "JA data is synced with your account."
        }
    }

    suspend fun chooseSync(direction: String): String = withContext(Dispatchers.IO) {
        val account = validSession()
        if (direction == "upload") {
            val local = snapshot(); upload(account, local); val hash = stateHash(local)
            authPrefs.edit().putString("last_local_hash", hash).putString("last_remote_hash", hash).apply()
            "This device's data replaced the cloud copy."
        } else {
            val fetched = request("GET", "$baseUrl/rest/v1/ja_user_state?select=state&user_id=eq.${account.userId}", token = account.accessToken, acceptArray = true)
            val remote = fetched.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("state") ?: error("There is no cloud copy to download yet.")
            applySnapshot(remote); val hash = stateHash(snapshot())
            authPrefs.edit().putString("last_local_hash", hash).putString("last_remote_hash", stateHash(remote)).apply()
            "Cloud data was downloaded to this device."
        }
    }

    private suspend fun upload(account: JaAccountSession, state: JSONObject) {
        request("POST", "$baseUrl/rest/v1/ja_user_state?on_conflict=user_id", token = account.accessToken,
            body = JSONObject().put("user_id", account.userId).put("state", state), prefer = "resolution=merge-duplicates,return=minimal")
    }

    private suspend fun request(method: String, url: String, token: String? = null, body: JSONObject? = null,
                                prefer: String? = null, acceptArray: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).header("apikey", apiKey).header("Accept", if (acceptArray) "application/json" else "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (prefer != null) builder.header("Prefer", prefer)
        if (body != null) builder.method(method, body.toString().toRequestBody(jsonType)) else builder.method(method, null)
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(JSONObject(text.ifBlank { "{}" }).optString("msg").ifBlank {
                JSONObject(text.ifBlank { "{}" }).optString("message").ifBlank { "Supabase request failed (${response.code})." }
            })
            if (text.isBlank()) JSONObject() else if (acceptArray) JSONObject().put("data", JSONArray(text)) else JSONObject(text)
        }
    }

    private suspend fun snapshot(): JSONObject {
        val media = JSONObject().put("watch_items", JSONArray().apply { dao.all().forEach { row -> put(JSONObject()
            .put("mediaId", row.mediaId).put("mediaType", row.mediaType).put("season", row.season).put("episode", row.episode)
            .put("title", row.title).put("poster", row.poster).put("favorite", row.favorite).put("positionMs", row.positionMs).put("updatedAt", row.updatedAt)) } })
        val watched = context.getSharedPreferences("ja_watched_v1", Context.MODE_PRIVATE).getStringSet("watched", emptySet()).orEmpty()
        media.put("watched", JSONArray(watched.sorted()))
        media.put("favorite_order", JSONObject().apply { val p = context.getSharedPreferences("ja_favorite_display_order_v1", Context.MODE_PRIVATE)
            put("movie", p.getString("movie-ids", "").orEmpty()); put("tv", p.getString("tv-ids", "").orEmpty()) })
        val musicPrefs = context.getSharedPreferences("ja_music_library", Context.MODE_PRIVATE)
        val music = JSONObject().put("values", prefsJson(musicPrefs.all))
        val iptvPrefs = context.getSharedPreferences("ja_live_favorites", Context.MODE_PRIVATE)
        val iptv = JSONObject().put("values", prefsJson(iptvPrefs.all.filterKeys { it == "ids" || it == "order" || it.startsWith("group-order:") }))
        return JSONObject().put("schema", 1).put("media", media).put("music", music).put("iptv", iptv)
    }

    private fun prefsJson(values: Map<String, *>): JSONObject = JSONObject().apply {
        values.forEach { (name, value) -> when (value) {
            is Set<*> -> put(name, JSONArray(value.filterIsInstance<String>().sorted()))
            is String -> put(name, value)
            is Boolean -> put(name, value)
            is Number -> put(name, value)
        } }
    }

    private suspend fun applySnapshot(state: JSONObject) {
        val media = state.optJSONObject("media") ?: JSONObject()
        dao.clearAll()
        val rows = media.optJSONArray("watch_items") ?: JSONArray()
        for (i in 0 until rows.length()) runCatching {
            val row = rows.getJSONObject(i)
            dao.save(WatchItem(row.getInt("mediaId"), row.getString("mediaType"), row.optInt("season"), row.optInt("episode"),
                row.optString("title"), row.optString("poster").takeIf { it != "null" }, row.optBoolean("favorite"), row.optLong("positionMs"), row.optLong("updatedAt")))
        }
        val watchedRows = media.optJSONArray("watched") ?: JSONArray()
        val watched = (0 until watchedRows.length()).map { watchedRows.getString(it) }.toSet()
        context.getSharedPreferences("ja_watched_v1", Context.MODE_PRIVATE).edit().putStringSet("watched", watched).commit()
        WatchedStore.replaceAll(context, watched)
        val orders = media.optJSONObject("favorite_order") ?: JSONObject()
        context.getSharedPreferences("ja_favorite_display_order_v1", Context.MODE_PRIVATE).edit()
            .putString("movie-ids", orders.optString("movie", "")).putString("tv-ids", orders.optString("tv", "")).commit()
        applyPrefs("ja_music_library", state.optJSONObject("music")?.optJSONObject("values"), stringSets = false)
        applyPrefs("ja_live_favorites", state.optJSONObject("iptv")?.optJSONObject("values"), stringSets = true)
    }

    private fun applyPrefs(name: String, values: JSONObject?, stringSets: Boolean) {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val syncedKey = { key: String -> !stringSets || key == "ids" || key == "order" || key.startsWith("group-order:") }
        val edit = prefs.edit()
        prefs.all.keys.filter(syncedKey).forEach(edit::remove)
        if (values != null) for (i in 0 until values.length()) {
            val key = values.names()?.optString(i) ?: continue
            when (val v = values.get(key)) {
                is JSONArray -> if (stringSets && key == "ids") edit.putStringSet(key, (0 until v.length()).map { v.getString(it) }.toSet()) else edit.putString(key, v.toString())
                is String -> edit.putString(key, v)
                is Boolean -> edit.putBoolean(key, v)
                is Int -> edit.putInt(key, v)
                is Long -> edit.putLong(key, v)
                is Double -> edit.putFloat(key, v.toFloat())
            }
        }
        edit.commit()
    }

    private fun stateHash(value: JSONObject): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(value).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { key ->
            JSONObject.quote(key) + ":" + canonical(value.get(key))
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
        else -> value?.toString() ?: "null"
    }

    private fun emptySnapshot() = JSONObject().put("schema", 1).put("media", JSONObject().put("watch_items", JSONArray()).put("watched", JSONArray()).put("favorite_order", JSONObject().put("movie", "").put("tv", "")))
        .put("music", JSONObject().put("values", JSONObject())).put("iptv", JSONObject().put("values", JSONObject()))
}
