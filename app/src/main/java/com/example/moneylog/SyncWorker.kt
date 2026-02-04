package com.example.moneylog

import android.content.Context
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import kotlin.math.abs

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("jotpay_sync", Context.MODE_PRIVATE)
        val vaultId = prefs.getString("vault_id", null)
        val secretKey = prefs.getString("secret_key", null)
        val forcePush = inputData.getBoolean("FORCE_PUSH", false)

        if (vaultId == null || secretKey == null) {
            return Result.success()
        }

        // 1. Initialize DB & Manager
        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "moneylog-db").build()
        val syncManager = SyncManager(applicationContext, db)

        try {
            val ref = FirebaseDatabase.getInstance().getReference("vaults").child(vaultId)

            // ---------------------------------------------------------
            // STEP 0: PROCESS PENDING DELETES (Clean up server)
            // ---------------------------------------------------------
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

            // ---------------------------------------------------------
            // STEP 1: FETCH SERVER DATA (Network First)
            // ---------------------------------------------------------
            val serverSnapshot = ref.child("transactions").get().await()
            val deletedSnapshot = ref.child("deleted").get().await()

            // FIX: Capture pending edits AFTER network call to avoid race conditions
            val pendingEdits = syncManager.getPendingEdits()
            val activePendingDeletes = syncManager.getPendingDeletes().mapNotNull { it.toLongOrNull() }.toSet()

            val serverDeletedIds = mutableSetOf<String>()
            for (child in deletedSnapshot.children) {
                child.key?.let { serverDeletedIds.add(it) }
            }

            // ---------------------------------------------------------
            // STEP 2: PUSH LOCAL CHANGES TO SERVER
            // ---------------------------------------------------------
            val localData = db.transactionDao().getAll()
            var changesCount = 0
            val pushedKeys = mutableSetOf<String>()

            for (t in localData) {
                val stableId = syncManager.generateStableId(t.timestamp)

                if (serverDeletedIds.contains(stableId)) {
                    // If server says deleted, we delete locally
                    db.transactionDao().delete(t)
                    changesCount++
                } else {
                    // Create the JSON payload including NEW FIELDS (Nature/Obligation)
                    val jsonObject = JSONObject()
                    jsonObject.put("o", t.originalText)
                    jsonObject.put("a", t.amount)
                    jsonObject.put("d", t.description)
                    jsonObject.put("t", t.timestamp)
                    jsonObject.put("n", t.nature)           // <--- Vital for Assets
                    jsonObject.put("oa", t.obligationAmount)// <--- Vital for Assets

                    var shouldPush = true
                    val isPendingEdit = pendingEdits.contains(t.timestamp.toString())

                    // Compare with Server Data to decide if we need to Push
                    if (serverSnapshot.hasChild(stableId)) {
                        val serverEncrypted = serverSnapshot.child(stableId).value.toString()
                        val serverJsonStr = EncryptionHelper.decrypt(serverEncrypted, secretKey)

                        if (serverJsonStr.isNotEmpty()) {
                            try {
                                val serverObj = JSONObject(serverJsonStr)
                                val sText = serverObj.optString("o")
                                val sAmt = serverObj.optDouble("a")
                                val sDesc = serverObj.optString("d")
                                val sNature = serverObj.optString("n", "NORMAL") // Default to Normal if missing
                                val sObligation = serverObj.optDouble("oa", 0.0)

                                // Floating point comparison tolerance
                                val isAmtMatch = abs(sAmt - t.amount) < 0.001
                                val isObliMatch = abs(sObligation - t.obligationAmount) < 0.001

                                // Strict Comparison: If Nature or Obligation differs, we MUST push
                                val isExactMatch = (sText == t.originalText) &&
                                        isAmtMatch &&
                                        (sDesc == t.description) &&
                                        (sNature == t.nature) &&
                                        isObliMatch

                                if (isExactMatch) {
                                    shouldPush = false // Data is identical, no push needed
                                    if (isPendingEdit) syncManager.removePendingEdit(t.timestamp)
                                } else {
                                    // Conflict: If local isn't pending edit, assume Server Wins (unless Force Push)
                                    if (!forcePush && !isPendingEdit) {
                                        shouldPush = false
                                    }
                                }
                            } catch (e: Exception) {
                                shouldPush = true // JSON error? Push our valid version.
                            }
                        }
                    }

                    if (shouldPush) {
                        val encryptedData = EncryptionHelper.encrypt(jsonObject.toString(), secretKey)
                        ref.child("transactions").child(stableId).setValue(encryptedData)
                        pushedKeys.add(stableId)
                        if (isPendingEdit) syncManager.removePendingEdit(t.timestamp)
                    }
                }
            }

            // ---------------------------------------------------------
            // STEP 3: PULL SERVER UPDATES TO LOCAL
            // ---------------------------------------------------------
            for (child in serverSnapshot.children) {
                val serverKey = child.key ?: continue

                // Skip if we just pushed it (we know it's current)
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

                        // READ NEW FIELDS (Default to NORMAL/0.0 if from old app version)
                        val nature = jsonObject.optString("n", "NORMAL")
                        val obligationAmount = jsonObject.optDouble("oa", 0.0)

                        if (activePendingDeletes.contains(timestamp)) continue
                        if (pendingEdits.contains(timestamp.toString())) continue

                        val existing = db.transactionDao().getByTimestamp(timestamp)

                        if (existing == null) {
                            // Insert New
                            db.transactionDao().insert(Transaction(
                                originalText = originalText,
                                amount = amount,
                                description = desc,
                                timestamp = timestamp,
                                nature = nature,
                                obligationAmount = obligationAmount
                            ))
                            changesCount++
                        } else {
                            // Update Existing
                            val isAmtDiff = abs(existing.amount - amount) > 0.001
                            val isObliDiff = abs(existing.obligationAmount - obligationAmount) > 0.001

                            // If ANYTHING changed (including nature), update local DB
                            if (existing.originalText != originalText ||
                                isAmtDiff ||
                                existing.description != desc ||
                                existing.nature != nature ||
                                isObliDiff) {

                                val updated = existing.copy(
                                    originalText = originalText,
                                    amount = amount,
                                    description = desc,
                                    nature = nature,
                                    obligationAmount = obligationAmount
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
}