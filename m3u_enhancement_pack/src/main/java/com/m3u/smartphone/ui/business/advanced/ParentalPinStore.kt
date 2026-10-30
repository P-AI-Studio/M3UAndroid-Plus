/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.m3u.smartphone.ui.business.advanced

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.util.Locale
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Stores a salted PBKDF2 hash, never the clear-text PIN. The protected-category
 * list is used by the Advanced global-search view. Player-wide enforcement must
 * additionally be wired into every playback entry point before this is treated
 * as a complete household/parental lock.
 */
class ParentalPinStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("m3u_plus_parental", Context.MODE_PRIVATE)

    fun hasPin(): Boolean = !preferences.getString(KEY_HASH, null).isNullOrBlank()

    fun setPin(pin: String): Boolean {
        if (pin.length !in 4..8 || pin.any { !it.isDigit() }) return false
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val hash = derive(pin, salt)
        preferences.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .remove(KEY_FAILED_ATTEMPTS)
            .remove(KEY_LOCKOUT_UNTIL)
            .apply()
        return true
    }

    @Synchronized
    fun verifyPin(pin: String): Boolean {
        val now = System.currentTimeMillis()
        val lockoutUntil = preferences.getLong(KEY_LOCKOUT_UNTIL, 0L)
        if (now < lockoutUntil) return false
        val saltText = preferences.getString(KEY_SALT, null) ?: return false
        val hashText = preferences.getString(KEY_HASH, null) ?: return false
        val valid = runCatching {
            val salt = Base64.decode(saltText, Base64.NO_WRAP)
            val expected = Base64.decode(hashText, Base64.NO_WRAP)
            MessageDigest.isEqual(expected, derive(pin, salt))
        }.getOrDefault(false)
        if (valid) {
            preferences.edit().remove(KEY_FAILED_ATTEMPTS).remove(KEY_LOCKOUT_UNTIL).apply()
            return true
        }
        val attempts = preferences.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val delayMs = when {
            attempts >= 10 -> 5 * 60_000L
            attempts >= 5 -> 30_000L
            else -> 0L
        }
        preferences.edit()
            .putInt(KEY_FAILED_ATTEMPTS, attempts)
            .putLong(KEY_LOCKOUT_UNTIL, now + delayMs)
            .apply()
        return false
    }

    fun lockedCategories(): Set<String> = preferences
        .getStringSet(KEY_LOCKED_CATEGORIES, emptySet())
        ?.map { it.trim().lowercase(Locale.ROOT) }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        .orEmpty()

    fun setLockedCategories(categories: Set<String>) {
        preferences.edit()
            .putStringSet(
                KEY_LOCKED_CATEGORIES,
                categories.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
            )
            .apply()
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, HASH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private companion object {
        const val KEY_SALT = "pin_salt"
        const val KEY_HASH = "pin_hash"
        const val KEY_LOCKED_CATEGORIES = "locked_categories"
        const val KEY_FAILED_ATTEMPTS = "failed_pin_attempts"
        const val KEY_LOCKOUT_UNTIL = "pin_lockout_until"
        const val SALT_BYTES = 16
        const val ITERATIONS = 150_000
        const val HASH_BITS = 256
    }
}
