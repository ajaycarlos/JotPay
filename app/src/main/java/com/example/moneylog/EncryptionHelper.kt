
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

                // Legacy Key Formatting (Vulnerable to truncation) - Kept only for decrypting old data
                private fun getLegacyKey(key: String): SecretKeySpec {
                    val paddedChars = key.padEnd(16, '0')
                    val rawBytes = paddedChars.toByteArray(Charsets.UTF_8)
                    val finalKeyBytes = if (rawBytes.size == 16) rawBytes else rawBytes.copyOf(16)
                    return SecretKeySpec(finalKeyBytes, "AES")
                }

                // Secure Key Derivation (Fixes UTF-8 Slicing & Weak Keys)
                // Uses SHA-256 to hash the password, guaranteeing a safe 32-byte array (AES-256)
                // without ever slicing multi-byte UTF-8 characters.
                private fun getSecureKey(key: String): SecretKeySpec {
                    val md = java.security.MessageDigest.getInstance("SHA-256")
                    val keyBytes = md.digest(key.toByteArray(Charsets.UTF_8))
                    return SecretKeySpec(keyBytes, "AES")
                }

                fun encrypt(text: String, secretKey: String): String {
                    try {
                        val keySpec = getSecureKey(secretKey)
                        val cipher = Cipher.getInstance(ALGORITHM)

                        // SECURITY UPGRADE: Generate a Random IV for every encryption
                        val ivBytes = ByteArray(IV_SIZE)
                        SecureRandom().nextBytes(ivBytes)
                        val iv = IvParameterSpec(ivBytes)

                        cipher.init(Cipher.ENCRYPT_MODE, keySpec, iv)
                        val encryptedBytes = cipher.doFinal(text.toByteArray(Charsets.UTF_8))

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
                        val decoded = Base64.decode(encryptedText, Base64.NO_WRAP)

                        // Try extracting IV and decrypting
                        if (decoded.size > IV_SIZE) {
                            val ivBytes = ByteArray(IV_SIZE)
                            val bodySize = decoded.size - IV_SIZE
                            val bodyBytes = ByteArray(bodySize)

                            System.arraycopy(decoded, 0, ivBytes, 0, IV_SIZE)
                            System.arraycopy(decoded, IV_SIZE, bodyBytes, 0, bodySize)

                            val iv = IvParameterSpec(ivBytes)
                            val cipher = Cipher.getInstance(ALGORITHM)

                            // Attempt 1: New Secure SHA-256 Key (AES-256)
                            try {
                                cipher.init(Cipher.DECRYPT_MODE, getSecureKey(secretKey), iv)
                                val decryptedBytes = cipher.doFinal(bodyBytes)
                                val result = String(decryptedBytes, Charsets.UTF_8)
                                if (looksLikeText(result)) return result
                            } catch (e: Exception) {
                                // Fall through to Attempt 2
                            }

                            // Attempt 2: Legacy Truncated Key with IV (AES-128)
                            try {
                                cipher.init(Cipher.DECRYPT_MODE, getLegacyKey(secretKey), iv)
                                val decryptedBytes = cipher.doFinal(bodyBytes)
                                val result = String(decryptedBytes, Charsets.UTF_8)
                                if (looksLikeText(result)) return result
                            } catch (e: Exception) {
                                // Fall through to Attempt 3
                            }
                        }

                        // Attempt 3: Legacy Truncated Key with Zero IV (Oldest Format)
                        return decryptLegacy(decoded, getLegacyKey(secretKey))

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

                        val decryptedBytes = cipher.doFinal(decoded)
                        val result = String(decryptedBytes, Charsets.UTF_8)

                        return if (looksLikeText(result)) result else ""
                    } catch (e: Exception) {
                        return "" // Data is corrupt or key is wrong
                    }
                }

                // Helper to check if string contains mostly printable characters
                private fun looksLikeText(text: String): Boolean {
                    if (text.isEmpty()) return true
                    // Allow JSON chars: { } " : , [ ] - . numbers letters spaces
                    // Just check for control characters that indicate binary garbage
                    for (char in text) {
                        if (char.isISOControl() && char != '\n' && char != '\r' && char != '\t') {
                            return false
                        }
                        // Also reject the  replacement char which indicates UTF-8 decoding errors
                        if (char == '\uFFFD') {
                            return false
                        }
                    }
                    return true
                }
            }
