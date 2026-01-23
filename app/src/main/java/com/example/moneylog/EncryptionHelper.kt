package com.example.moneylog

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object EncryptionHelper {

    // AES-CBC is secure, provided we use a random IV (implemented below)
    private const val ALGORITHM = "AES/CBC/PKCS5Padding"
    private const val IV_SIZE = 16

    // Ensure key is exactly 128-bit
    private fun formatKey(key: String): SecretKeySpec {
        val paddedKey = key.padEnd(16, '0').substring(0, 16)
        return SecretKeySpec(paddedKey.toByteArray(Charsets.UTF_8), "AES")
    }

    fun encrypt(text: String, secretKey: String): String {
        try {
            val keySpec = formatKey(secretKey)
            val cipher = Cipher.getInstance(ALGORITHM)

            // SECURITY UPGRADE: Generate a Random IV for every encryption
            val ivBytes = ByteArray(IV_SIZE)
            SecureRandom().nextBytes(ivBytes)
            val iv = IvParameterSpec(ivBytes)

            cipher.init(Cipher.ENCRYPT_MODE, keySpec, iv)
            val encryptedBytes = cipher.doFinal(text.toByteArray())

            // Format: [IV (16 bytes)] + [Ciphertext]
            val combined = ByteArray(ivBytes.size + encryptedBytes.size)
            System.arraycopy(ivBytes, 0, combined, 0, ivBytes.size)
            System.arraycopy(encryptedBytes, 0, combined, ivBytes.size, encryptedBytes.size)

            return Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }

    fun decrypt(encryptedText: String, secretKey: String): String {
        try {
            val keySpec = formatKey(secretKey)
            val decoded = Base64.decode(encryptedText, Base64.NO_WRAP)

            // COMPATIBILITY LOGIC:
            // 1. Try to decrypt assuming the new format (Random IV at the front)
            if (decoded.size > IV_SIZE) {
                try {
                    val ivBytes = ByteArray(IV_SIZE)
                    val bodySize = decoded.size - IV_SIZE
                    val bodyBytes = ByteArray(bodySize)

                    System.arraycopy(decoded, 0, ivBytes, 0, IV_SIZE)
                    System.arraycopy(decoded, IV_SIZE, bodyBytes, 0, bodySize)

                    val cipher = Cipher.getInstance(ALGORITHM)
                    val iv = IvParameterSpec(ivBytes)
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, iv)
                    return String(cipher.doFinal(bodyBytes))
                } catch (e: Exception) {
                    // If padding fails, it might be old data (Zero IV). Fallback.
                    return decryptLegacy(decoded, keySpec)
                }
            } else {
                // Too short to have an IV, likely legacy data
                return decryptLegacy(decoded, keySpec)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }

    private fun decryptLegacy(decoded: ByteArray, keySpec: SecretKeySpec): String {
        try {
            val cipher = Cipher.getInstance(ALGORITHM)
            // Old format used a static Zero IV
            val iv = IvParameterSpec(ByteArray(16))
            cipher.init(Cipher.DECRYPT_MODE, keySpec, iv)
            return String(cipher.doFinal(decoded))
        } catch (e: Exception) {
            return "" // Data is corrupt or key is wrong
        }
    }
}