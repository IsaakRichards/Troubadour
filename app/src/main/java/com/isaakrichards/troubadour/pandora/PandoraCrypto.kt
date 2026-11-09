package com.isaakrichards.troubadour.pandora

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Pandora's JSON API wraps most request bodies in Blowfish (ECB, zero padding,
 * hex encoded). The partner keys are the publicly known ones used by Pithos,
 * pianobar and friends.
 */
object PandoraCrypto {
    private const val TRANSFORM = "Blowfish/ECB/NoPadding"

    fun encrypt(plain: String, key: String): String {
        val data = plain.toByteArray(Charsets.UTF_8)
        val padded = data.copyOf((data.size + 7) / 8 * 8) // zero-filled tail
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "Blowfish"))
        return toHex(cipher.doFinal(padded))
    }

    fun decrypt(hex: String, key: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "Blowfish"))
        return cipher.doFinal(fromHex(hex))
    }

    /** The partner login returns an encrypted sync time: 4 junk bytes, then ASCII seconds. */
    fun decodeSyncTime(hex: String, key: String): Long {
        val bytes = decrypt(hex, key)
        val digits = String(bytes.copyOfRange(4, bytes.size), Charsets.ISO_8859_1)
            .takeWhile { it.isDigit() }
        return digits.toLongOrNull()
            ?: throw PandoraException(PandoraException.BAD_SYNC_TIME, "Could not read Pandora's clock")
    }

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Odd-length hex string" }
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
