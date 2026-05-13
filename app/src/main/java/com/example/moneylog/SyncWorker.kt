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
            // FIX: Ensure Firebase Anonymous Auth is complete BEFORE accessing DB (Prevents Race Condition)
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                auth.signInAnonymously().await()
            }

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
            // SMART BATCH LOOP: Keep pulling if Firebase paginates data
            // ---------------------------------------------------------
            var totalChangesCount = 0
            var loopCount = 0
            var keepSyncing = true

            // Max 5 loops per sync to prevent infinite battery drain if network hangs
            while (keepSyncing && loopCount < 5) {
                loopCount++
                var currentBatchChanges = 0

                // STEP 1: FETCH SERVER DATA
                val serverSnapshot = ref.child("transactions").get().await()
                val deletedSnapshot = ref.child("deleted").get().await()

                val pendingEditsMap = syncManager.getPendingEditsSnapshot()

                val serverDeletedIds = mutableSetOf<String>()
                for (child in deletedSnapshot.children) {
                    child.key?.let { serverDeletedIds.add(it) }
                }

                // STEP 2: PUSH LOCAL CHANGES TO SERVER
                val localData = db.transactionDao().getAll()
                val pushedKeys = mutableSetOf<String>()

                for (t in localData) {
                    val stableId = syncManager.generateStableId(t.timestamp)

                    if (serverDeletedIds.contains(stableId)) {
                        db.transactionDao().delete(t)
                        currentBatchChanges++
                    } else {
                        val jsonObject = JSONObject()
                        jsonObject.put("o", t.originalText)
                        jsonObject.put("a", t.amount)
                        jsonObject.put("d", t.description)
                        jsonObject.put("t", t.timestamp)
                        jsonObject.put("n", t.nature)
                        jsonObject.put("oa", t.obligationAmount)

                        var shouldPush = true
                        val isPendingEdit = pendingEditsMap.containsKey(t.timestamp)
                        val pendingToken = pendingEditsMap[t.timestamp]

                        if (serverSnapshot.hasChild(stableId)) {
                            val serverEncrypted = serverSnapshot.child(stableId).value.toString()
                            val serverJsonStr = EncryptionHelper.decrypt(serverEncrypted, secretKey)

                            if (serverJsonStr.isNotEmpty()) {
                                try {
                                    val serverObj = JSONObject(serverJsonStr)
                                    val sText = serverObj.optString("o")
                                    val sAmt = serverObj.optLong("a")
                                    val sDesc = serverObj.optString("d")
                                    val sNature = serverObj.optString("n", "NORMAL")
                                    val sObligation = serverObj.optLong("oa", 0L)

                                    val isExactMatch = (sText == t.originalText) &&
                                            (sAmt == t.amount) &&
                                            (sDesc == t.description) &&
                                            (sNature == t.nature) &&
                                            (sObligation == t.obligationAmount)

                                    if (isExactMatch) {
                                        shouldPush = false
                                        if (isPendingEdit && pendingToken != null) {
                                            syncManager.removePendingEdit(t.timestamp, pendingToken)
                                        }
                                    } else {
                                        if (!forcePush && !isPendingEdit) shouldPush = false
                                    }
                                } catch (e: Exception) {
                                    shouldPush = true
                                }
                            }
                        }

                        if (shouldPush) {
                            val encryptedData = EncryptionHelper.encrypt(jsonObject.toString(), secretKey)
                            ref.child("transactions").child(stableId).setValue(encryptedData).await()
                            pushedKeys.add(stableId)

                            if (isPendingEdit && pendingToken != null) {
                                syncManager.removePendingEdit(t.timestamp, pendingToken)
                            }
                        }
                    }
                }

                // STEP 3: PULL SERVER UPDATES TO LOCAL
                for (child in serverSnapshot.children) {
                    val serverKey = child.key ?: continue
                    if (pushedKeys.contains(serverKey)) continue

                    val encryptedJson = child.getValue(String::class.java) ?: continue
                    val jsonStr = EncryptionHelper.decrypt(encryptedJson, secretKey)

                    if (jsonStr.isNotEmpty()) {
                        try {
                            val jsonObject = JSONObject(jsonStr)
                            val originalText = jsonObject.optString("o")
                            val amount = jsonObject.optLong("a")
                            val desc = jsonObject.optString("d")
                            val timestamp = jsonObject.optLong("t")
                            val nature = jsonObject.optString("n", "NORMAL")
                            val obligationAmount = jsonObject.optLong("oa", 0L)

                            val freshPendingDeletes = syncManager.getPendingDeletes()
                            if (freshPendingDeletes.contains(timestamp.toString())) continue
                            if (syncManager.hasPendingEdit(timestamp)) continue

                            val existing = db.transactionDao().getByTimestamp(timestamp)

                            if (existing == null) {
                                db.transactionDao().insert(Transaction(
                                    originalText = originalText,
                                    amount = amount,
                                    description = desc,
                                    timestamp = timestamp,
                                    nature = nature,
                                    obligationAmount = obligationAmount
                                ))
                                currentBatchChanges++
                            } else {
                                if (existing.originalText != originalText ||
                                    existing.amount != amount ||
                                    existing.description != desc ||
                                    existing.nature != nature ||
                                    existing.obligationAmount != obligationAmount) {

                                    val updated = existing.copy(
                                        originalText = originalText,
                                        amount = amount,
                                        description = desc,
                                        nature = nature,
                                        obligationAmount = obligationAmount
                                    )
                                    db.transactionDao().update(updated)
                                    currentBatchChanges++
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                // Evaluate Loop Condition
                totalChangesCount += currentBatchChanges
                if (currentBatchChanges == 0) {
                    keepSyncing = false // Steady state reached, all logs are synced.
                } else {
                    // Small delay to allow Firebase to pull the next chunk into its local cache
                    kotlinx.coroutines.delay(500)
                }
            }

            db.close()
            val msg = if (totalChangesCount > 0) "Synced: $totalChangesCount updates" else "Sync Complete"
            return Result.success(workDataOf("MSG" to msg))

        } catch (e: Exception) {
            db.close()
            return Result.failure(workDataOf("MSG" to "Error: ${e.message}"))
        }
    }
}