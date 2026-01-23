package com.example.moneylog

import android.content.Context
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.security.MessageDigest

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("jotpay_sync", Context.MODE_PRIVATE)
        val vaultId = prefs.getString("vault_id", null)
        val secretKey = prefs.getString("secret_key", null)
        val forcePush = inputData.getBoolean("FORCE_PUSH", false)

        if (vaultId == null || secretKey == null) {
            return Result.success()
        }

        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "moneylog-db").build()
        val syncManager = SyncManager(applicationContext, db)

        try {
            val ref = FirebaseDatabase.getInstance().getReference("vaults").child(vaultId)

            // STEP 0: PROCESS PENDING DELETES
            val pendingDeletes = syncManager.getPendingDeletes()
            for (tsString in pendingDeletes) {
                val ts = tsString.toLongOrNull() ?: continue
                val stableId = syncManager.generateStableId(ts)
                try {
                    ref.child("transactions").child(stableId).removeValue().await()
                    ref.child("deleted").child(stableId).setValue(System.currentTimeMillis()).await()
                    syncManager.removePendingDelete(ts)
                } catch (e: Exception) { }
            }
            val activePendingDeletes = syncManager.getPendingDeletes().mapNotNull { it.toLongOrNull() }.toSet()

            // NEW: Get Pending Edits
            val pendingEdits = syncManager.getPendingEdits()

            // 1. Fetch Server Data
            val serverSnapshot = ref.child("transactions").get().await()
            val deletedSnapshot = ref.child("deleted").get().await()
            val deletedIds = mutableSetOf<String>()
            for (child in deletedSnapshot.children) {
                child.key?.let { deletedIds.add(it) }
            }

            // 3. Process Local Data (Push Logic)
            val localData = db.transactionDao().getAll()
            var changesCount = 0
            val pushedKeys = mutableSetOf<String>()

            for (t in localData) {
                val stableId = syncManager.generateStableId(t.timestamp)

                if (deletedIds.contains(stableId)) {
                    db.transactionDao().delete(t)
                    changesCount++
                } else {
                    // JSON Generation
                    val jsonObject = JSONObject()
                    jsonObject.put("o", t.originalText)
                    jsonObject.put("a", t.amount)
                    jsonObject.put("d", t.description)
                    jsonObject.put("t", t.timestamp)

                    // Conflict Check
                    var shouldPush = true
                    val isPendingEdit = pendingEdits.contains(t.timestamp.toString())

                    if (serverSnapshot.hasChild(stableId)) {
                        val serverEncrypted = serverSnapshot.child(stableId).value.toString()

                        // FIX: Decrypt and compare VALUES, because Random IV means encrypted strings never match
                        val serverJsonStr = EncryptionHelper.decrypt(serverEncrypted, secretKey)

                        var isContentMatch = false
                        if (serverJsonStr.isNotEmpty()) {
                            try {
                                val serverObj = JSONObject(serverJsonStr)
                                val sText = serverObj.optString("o")
                                val sAmt = serverObj.optDouble("a")
                                val sDesc = serverObj.optString("d")

                                // Precise check: If server data equals local data, we don't need to push
                                if (sText == t.originalText && sAmt == t.amount && sDesc == t.description) {
                                    isContentMatch = true
                                }
                            } catch (e: Exception) {}
                        }

                        if (isContentMatch) {
                            shouldPush = false
                            // Data matches, so any pending edit flag is now redundant/safe to clear
                            if (isPendingEdit) syncManager.removePendingEdit(t.timestamp)
                        } else {
                            // Content Differs.
                            if (!forcePush && !isPendingEdit) {
                                shouldPush = false // Server Wins (Standard Rule)
                            }
                        }
                    }

                    if (shouldPush) {
                        // We re-encrypt here. This generates a NEW Random IV every time.
                        val encryptedData = EncryptionHelper.encrypt(jsonObject.toString(), secretKey)
                        ref.child("transactions").child(stableId).setValue(encryptedData)
                        pushedKeys.add(stableId)
                        if (isPendingEdit) syncManager.removePendingEdit(t.timestamp)
                    }
                }
            }

            // 4. Download/Update Server Items
            for (child in serverSnapshot.children) {
                val serverKey = child.key ?: continue
                if (pushedKeys.contains(serverKey)) continue

                val encryptedJson = child.getValue(String::class.java) ?: continue
                val jsonStr = EncryptionHelper.decrypt(encryptedJson, secretKey)

                if (jsonStr.isNotEmpty()) {
                    try {
                        val jsonObject = JSONObject(jsonStr)
                        val originalText = jsonObject.optString("o")
                        val amount = jsonObject.optDouble("a")
                        val desc = jsonObject.optString("d")
                        val timestamp = jsonObject.optLong("t")

                        if (activePendingDeletes.contains(timestamp)) continue

                        val existing = db.transactionDao().getByTimestamp(timestamp)

                        if (existing == null) {
                            db.transactionDao().insert(Transaction(
                                originalText = originalText,
                                amount = amount,
                                description = desc,
                                timestamp = timestamp
                            ))
                            changesCount++
                        } else {
                            if (existing.originalText != originalText || existing.amount != amount || existing.description != desc) {
                                val updated = existing.copy(
                                    originalText = originalText,
                                    amount = amount,
                                    description = desc
                                )
                                db.transactionDao().update(updated)
                                changesCount++
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            db.close()
            val msg = if (changesCount > 0) "Synced: $changesCount updates" else "Sync Complete"
            return Result.success(workDataOf("MSG" to msg))

        } catch (e: Exception) {
            db.close()
            return Result.failure(workDataOf("MSG" to "Error: ${e.message}"))
        }
    }

    private fun generateStableId(timestamp: Long): String {
        val input = "$timestamp"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}