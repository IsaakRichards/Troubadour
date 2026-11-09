package com.isaakrichards.troubadour.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.isaakrichards.troubadour.pandora.AudioQuality
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the Pandora login on-device. The password is encrypted with an AES key
 * that lives in the Android Keystore and never leaves it.
 */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("troubadour", Context.MODE_PRIVATE)

    fun save(username: String, password: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(K_USER, username)
            .putString(K_IV, b64(cipher.iv))
            .putString(K_PASS, b64(sealed))
            .apply()
    }

    fun load(): Pair<String, String>? {
        val user = prefs.getString(K_USER, null) ?: return null
        val iv = prefs.getString(K_IV, null) ?: return null
        val sealed = prefs.getString(K_PASS, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(iv)))
            user to String(cipher.doFinal(unb64(sealed)), Charsets.UTF_8)
        } catch (e: Exception) {
            null // key lost (e.g. restored device); the person signs in again
        }
    }

    fun clear() {
        prefs.edit().remove(K_USER).remove(K_IV).remove(K_PASS).remove(K_LAST_STATION).apply()
    }

    var quality: AudioQuality
        get() = prefs.getString(K_QUALITY, null)
            ?.let { runCatching { AudioQuality.valueOf(it) }.getOrNull() }
            ?: AudioQuality.HIGH
        set(value) = prefs.edit().putString(K_QUALITY, value.name).apply()

    var lastStationToken: String?
        get() = prefs.getString(K_LAST_STATION, null)
        set(value) = prefs.edit().putString(K_LAST_STATION, value).apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val ALIAS = "troubadour_login"
        const val K_USER = "user"
        const val K_IV = "iv"
        const val K_PASS = "pass"
        const val K_QUALITY = "quality"
        const val K_LAST_STATION = "last_station"
    }
}
