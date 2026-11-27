package com.example.ja

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Local PIN gate for NTV's Adult Only channel group. */
internal class JaIptvPinStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ja_iptv_security", Context.MODE_PRIVATE)

    fun hasPin(): Boolean = !prefs.getString(SALT_KEY, null).isNullOrBlank() &&
        !prefs.getString(HASH_KEY, null).isNullOrBlank()

    fun isAdultGroupEnabled(): Boolean = prefs.getBoolean(ADULT_GROUP_ENABLED_KEY, false)

    fun enableAdultGroup() {
        check(prefs.edit().putBoolean(ADULT_GROUP_ENABLED_KEY, true).commit())
    }

    fun save(pin: String) {
        require(pin.matches(Regex("\\d{6}")))
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val hash = derive(pin, salt)
        check(prefs.edit()
            .putString(SALT_KEY, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(HASH_KEY, Base64.encodeToString(hash, Base64.NO_WRAP))
            .commit())
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
        const val SALT_BYTES = 16
        const val ITERATIONS = 120_000
        const val HASH_BITS = 256
    }
}
