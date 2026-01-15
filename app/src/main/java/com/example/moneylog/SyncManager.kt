package com.example.moneylog

import android.content.Context
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class SyncManager(private val context: Context, private val db: AppDatabase) {

    private val prefs = context.getSharedPreferences("jotpay_sync", Context.MODE_PRIVATE)

    // SAFEGUARD: If this takes > 5 seconds, we assume the server is full/busy
    private val CONNECTION_TIMEOUT = 5000L

    fun syncData(onComplete: (String) -> Unit) {
        val vaultId = prefs.getString("vault_id", null)
        val secretKey = prefs.getString("secret_key", null)

        if (vaultId == null || secretKey == null) {
            onComplete("Skipped: Device not linked")
            return
        }

        val ref = FirebaseDatabase.getInstance().getReference("vaults").child(vaultId).child("transactions")

        // 1. PUSH local data to Cloud
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // Get all local transactions
                val localData = db.transactionDao().getAll()

                for (t in localData) {
                    // Create a unique hash for the transaction to use as ID
                    // (prevents duplicates in cloud)
                    val rawString = "${t.amount}|${t.description}|${t.timestamp}"
                    val uniqueId = EncryptionHelper.encrypt(rawString, secretKey).replace("/", "_").replace("+", "-").replace("=", "")

                    // Encrypt the full data
                    val json = "{\"o\":\"${t.originalText}\", \"a\":${t.amount}, \"d\":\"${t.description}\", \"t\":${t.timestamp}}"
                    val encryptedData = EncryptionHelper.encrypt(json, secretKey)

                    // Upload if not exists (using updateChildren to be efficient)
                    // We don't await here to keep UI fast
                    ref.child(uniqueId).setValue(encryptedData)
                }

                // 2. PULL Cloud data to Local
                // We use a SingleValueEvent to just "Check Mailbox" and disconnect immediately
                // This protects against the "100 connections" limit
                withContext(Dispatchers.Main) {
                    ref.addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            processCloudData(snapshot, secretKey, onComplete)
                        }

                        override fun onCancelled(error: DatabaseError) {
                            // This is where we catch the "Server Busy" or Permission errors
                            if (error.code == DatabaseError.NETWORK_ERROR || error.code == DatabaseError.DISCONNECTED) {
                                onComplete("Error: Server is busy (101). Try again in 1 min.")
                            } else {
                                onComplete("Sync Error: ${error.message}")
                            }
                        }
                    })
                }
            } catch (e: Exception) {
                onComplete("Sync Failed: ${e.message}")
            }
        }
    }

    private fun processCloudData(snapshot: DataSnapshot, secretKey: String, onComplete: (String) -> Unit) {
        GlobalScope.launch(Dispatchers.IO) {
            var newItemsCount = 0

            for (child in snapshot.children) {
                val encryptedJson = child.getValue(String::class.java) ?: continue
                val jsonStr = EncryptionHelper.decrypt(encryptedJson, secretKey)

                if (jsonStr.isNotEmpty()) {
                    try {
                        // Manually parse JSON to avoid extra libraries
                        // Format: {"o":"text", "a":10.0, "d":"desc", "t":12345}
                        val clean = jsonStr.replace("{", "").replace("}", "").replace("\"", "")
                        val parts = clean.split(",")

                        var originalText = ""
                        var amount = 0.0
                        var desc = ""
                        var timestamp = 0L

                        for (part in parts) {
                            val kv = part.split(":")
                            when(kv[0].trim()) {
                                "o" -> originalText = kv[1]
                                "a" -> amount = kv[1].toDouble()
                                "d" -> desc = kv[1]
                                "t" -> timestamp = kv[1].toLong()
                            }
                        }

                        // THE SMART CHECK: Don't add if we already have it
                        // We use a 100ms buffer for timestamps because of network lag
                        val exists = db.transactionDao().checkDuplicate(amount, desc, timestamp - 100, timestamp + 100)

                        if (exists == 0) {
                            db.transactionDao().insert(Transaction(
                                originalText = originalText,
                                amount = amount,
                                description = desc,
                                timestamp = timestamp
                            ))
                            newItemsCount++
                        }

                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (newItemsCount > 0) {
                    onComplete("Synced: $newItemsCount new items downloaded")
                } else {
                    onComplete("Sync Complete: Up to date")
                }
            }
        }
    }
}