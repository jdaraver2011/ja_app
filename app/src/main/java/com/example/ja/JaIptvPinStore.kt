package com.example.ja

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.json.JSONObject

/** Local PIN gate for NTV's Adult Only channel group. */
internal class JaIptvPinStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ja_iptv_security", Context.MODE_PRIVATE)

    fun hasPin(): Boolean = !prefs.getString(SALT_KEY, null).isNullOrBlank() &&
        !prefs.getString(HASH_KEY, null).isNullOrBlank()

    fun isAdultGroupEnabled(): Boolean = prefs.getBoolean(ADULT_GROUP_ENABLED_KEY, false)

    fun enableAdultGroup() {
        check(prefs.edit().putBoolean(ADULT_GROUP_ENABLED_KEY, true).putLong(UPDATED_AT_KEY, System.currentTimeMillis()).commit())
    }

    fun save(pin: String) {
        require(pin.matches(Regex("\\d{6}")))
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val hash = derive(pin, salt)
        check(prefs.edit()
            .putString(SALT_KEY, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(HASH_KEY, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putLong(UPDATED_AT_KEY, System.currentTimeMillis())
            .commit())
    }

    /** Export only the salted verifier, never the six-digit PIN itself. */
    fun syncState(): JSONObject {
        var updatedAt = prefs.getLong(UPDATED_AT_KEY, 0L)
        val hasPin = hasPin()
        if (updatedAt <= 0L && (hasPin || isAdultGroupEnabled())) {
            updatedAt = System.currentTimeMillis()
            prefs.edit().putLong(UPDATED_AT_KEY, updatedAt).commit()
        }
        return JSONObject()
            .put("pin_salt", prefs.getString(SALT_KEY, null) ?: JSONObject.NULL)
            .put("pin_hash", prefs.getString(HASH_KEY, null) ?: JSONObject.NULL)
            .put("adult_group_enabled", isAdultGroupEnabled())
            .put("updated_at", updatedAt)
    }

    /** Apply the newest account-scoped verifier after validating its encoded sizes. */
    fun applySyncState(value: JSONObject) {
        val saltText = value.optString("pin_salt").takeUnless { it.isBlank() || it == "null" }
        val hashText = value.optString("pin_hash").takeUnless { it.isBlank() || it == "null" }
        require((saltText == null) == (hashText == null)) { "Incomplete synced Adult PIN verifier" }
        val salt = saltText?.let { Base64.decode(it, Base64.NO_WRAP) }
        val hash = hashText?.let { Base64.decode(it, Base64.NO_WRAP) }
        require(salt == null || salt.size == SALT_BYTES) { "Invalid synced Adult PIN salt" }
        require(hash == null || hash.size == HASH_BITS / 8) { "Invalid synced Adult PIN hash" }
        val updatedAt = value.optLong("updated_at", 0L).coerceAtLeast(0L)
        val edit = prefs.edit()
            .putBoolean(ADULT_GROUP_ENABLED_KEY, value.optBoolean("adult_group_enabled", false))
            .putLong(UPDATED_AT_KEY, updatedAt)
        if (saltText == null) edit.remove(SALT_KEY) else edit.putString(SALT_KEY, saltText)
        if (hashText == null) edit.remove(HASH_KEY) else edit.putString(HASH_KEY, hashText)
        check(edit.commit())
    }

    fun verify(pin: String): Boolean {
        if (!pin.matches(Regex("\\d{6}"))) return false
        val salt = runCatching { Base64.decode(prefs.getString(SALT_KEY, null), Base64.NO_WRAP) }.getOrNull() ?: return false
        val expected = runCatching { Base64.decode(prefs.getString(HASH_KEY, null), Base64.NO_WRAP) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, derive(pin, salt))
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, HASH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private companion object {
        const val SALT_KEY = "ntv-pin-salt"
        const val HASH_KEY = "ntv-pin-hash"
        const val ADULT_GROUP_ENABLED_KEY = "adult-group-enabled"
        const val UPDATED_AT_KEY = "adult-gate-updated-at"
        const val SALT_BYTES = 16
        const val ITERATIONS = 120_000
        const val HASH_BITS = 256
    }
}
