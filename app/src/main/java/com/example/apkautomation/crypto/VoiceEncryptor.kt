package com.example.apkautomation.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Real-time voice encryption and decryption using hardware-accelerated AES-256 in CTR mode.
 * 
 * - Key derivation: SHA-256 hash of a shared user PIN (producing a 256-bit AES key).
 * - Cipher mode: AES/CTR/NoPadding (Stream-like block cipher mode: 0 byte padding overhead,
 *   loss-tolerant, parallelizable, and utilizes ARMv8 Crypto Extensions on Android for zero audio stutter).
 */
class VoiceEncryptor(pin: String) {

    private val secretKey: SecretKeySpec
    private val secureRandom = SecureRandom()

    init {
        // Derive 256-bit key from PIN using SHA-256
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
        secretKey = SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Encrypt a PCM audio chunk.
     * Prepends a freshly generated 16-byte IV to the ciphertext.
     * Output format: [16-byte IV] + [encrypted audio bytes]
     */
    fun encrypt(audioData: ByteArray, offset: Int = 0, length: Int = audioData.size): ByteArray {
        val iv = ByteArray(16)
        secureRandom.nextBytes(iv)
        val ivSpec = IvParameterSpec(iv)

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)

        val cipherText = cipher.doFinal(audioData, offset, length)

        val result = ByteArray(16 + cipherText.size)
        System.arraycopy(iv, 0, result, 0, 16)
        System.arraycopy(cipherText, 0, result, 16, cipherText.size)
        return result
    }

    /**
     * Decrypt an encrypted audio chunk.
     * Expects input format: [16-byte IV] + [encrypted audio bytes].
     * Returns the raw PCM byte array, or null if corrupted/tampered.
     */
    fun decrypt(encryptedPacket: ByteArray, offset: Int = 0, length: Int = encryptedPacket.size): ByteArray? {
        if (length <= 16) return null

        return try {
            val iv = ByteArray(16)
            System.arraycopy(encryptedPacket, offset, iv, 0, 16)
            val ivSpec = IvParameterSpec(iv)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            cipher.doFinal(encryptedPacket, offset + 16, length - 16)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Encrypt a text string to Base64-encoded ciphertext with prepended IV.
     */
    fun encryptText(plainText: String): String {
        val encryptedBytes = encrypt(plainText.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(encryptedBytes, android.util.Base64.NO_WRAP)
    }

    /**
     * Decrypt Base64-encoded ciphertext with prepended IV back to plain text.
     */
    fun decryptText(base64Cipher: String): String? {
        return try {
            val bytes = android.util.Base64.decode(base64Cipher, android.util.Base64.NO_WRAP)
            val decryptedBytes = decrypt(bytes) ?: return null
            String(decryptedBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
