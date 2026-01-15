package com.example.moneylog

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object EncryptionHelper {

    private const val ALGORITHM = "AES/CBC/PKCS5Padding"

    // Fix the key to exactly 16 characters (128-bit)
    private fun formatKey(key: String): SecretKeySpec {
        val paddedKey = key.padEnd(16, '0').substring(0, 16)
        return SecretKeySpec(paddedKey.toByteArray(Charsets.UTF_8), "AES")
    }

    fun encrypt(text: String, secretKey: String): String {
        try {
            val keySpec = formatKey(secretKey)
            val cipher = Cipher.getInstance(ALGORITHM)
            // Use a static IV for simplicity in this offline-first setup,
            // or generate random IV and prepend it for higher security.
            // Here we use a zero IV to ensure strict string matching if needed.
            val iv = IvParameterSpec(ByteArray(16))
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, iv)
            val encrypted = cipher.doFinal(text.toByteArray())
            return Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }

    fun decrypt(encryptedText: String, secretKey: String): String {
        try {
            val keySpec = formatKey(secretKey)
            val cipher = Cipher.getInstance(ALGORITHM)
            val iv = IvParameterSpec(ByteArray(16))
            cipher.init(Cipher.DECRYPT_MODE, keySpec, iv)
            val decoded = Base64.decode(encryptedText, Base64.NO_WRAP)
            return String(cipher.doFinal(decoded))
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }
}