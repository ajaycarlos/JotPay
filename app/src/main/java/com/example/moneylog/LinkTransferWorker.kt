package com.example.moneylog

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.UUID

class LinkTransferWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "JotPayLinkTransfer"

        // Input keys
        const val KEY_TRANSFER_MODE = "TRANSFER_MODE"
        const val KEY_VAULT_ID     = "VAULT_ID"
        const val KEY_SECRET_KEY   = "SECRET_KEY"

        // Output keys
        const val KEY_MSG        = "MSG"
        const val KEY_COUNT      = "COUNT"
        const val KEY_DOWNLOADED = "DOWNLOADED"
        const val KEY_VALIDATED  = "VALIDATED"
        const val KEY_SKIPPED    = "SKIPPED"

        // Progress keys
        const val KEY_STAGE     = "STAGE"
        const val KEY_PERCENT   = "PERCENT"
        const val KEY_PROCESSED = "PROCESSED"
        const val KEY_TOTAL     = "TOTAL"

        // Transfer modes
        const val MODE_MERGE     = "MERGE"
        const val MODE_OVERWRITE = "OVERWRITE"

        /**
         * Enqueues a unique LinkTransferWorker with the given parameters.
         * Returns the UUID of the enqueued work so the UI can observe it.
         * Uses KEEP policy — if a transfer is already running, it won't be replaced.
         * Call cancelUniqueWork(WORK_NAME) first if you want to restart.
         */
        fun enqueueWork(
            context: Context,
            transferMode: String,
            vaultId: String,
            secretKey: String
        ): UUID {
            val inputData = workDataOf(
                KEY_TRANSFER_MODE to transferMode,
                KEY_VAULT_ID      to vaultId,
                KEY_SECRET_KEY    to secretKey
            )
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<LinkTransferWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            return request.id
        }
    }

    private data class ValidatedRecord(
        val stableId: String,
        val originalText: String,
        val amount: Long,
        val description: String,
        val timestamp: Long,
        val nature: String,
        val obligationAmount: Long
    )

    override suspend fun doWork(): Result {
        val transferMode = inputData.getString(KEY_TRANSFER_MODE) ?: return Result.failure(
            workDataOf(KEY_MSG to "Missing TRANSFER_MODE input")
        )
        val newVaultId = inputData.getString(KEY_VAULT_ID) ?: return Result.failure(
            workDataOf(KEY_MSG to "Missing VAULT_ID input")
        )
        val newSecretKey = inputData.getString(KEY_SECRET_KEY) ?: return Result.failure(
            workDataOf(KEY_MSG to "Missing SECRET_KEY input")
        )

        return when (transferMode) {
            MODE_MERGE     -> doMerge(newVaultId, newSecretKey)
            MODE_OVERWRITE -> doOverwrite(newVaultId, newSecretKey)
            else           -> Result.failure(workDataOf(KEY_MSG to "Unknown TRANSFER_MODE: $transferMode"))
        }
    }

    // -------------------------------------------------------------------------
    // MERGE
    // -------------------------------------------------------------------------
    private suspend fun doMerge(newVaultId: String, newSecretKey: String): Result {
        // 1. Connecting to Firebase
        reportProgress("Connecting to Firebase", percent = -1)

        // 2. Ensure Firebase Auth
        try {
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                auth.signInAnonymously().await()
            }
        } catch (e: Exception) {
            return Result.failure(workDataOf(KEY_MSG to "Auth failed: ${e.message}"))
        }

        if (isStopped) return cancelledFailure()

        // 3. Download records
        reportProgress("Downloading transaction records", percent = -1)

        val ref = FirebaseDatabase.getInstance().getReference("vaults").child(newVaultId)

        var serverSnapshot = try {
            ref.child("transactions").get().await()
        } catch (e: Exception) {
            return Result.failure(workDataOf(KEY_MSG to "Failed to fetch transactions: ${e.message}"))
        }

        var metaSnapshot = try {
            ref.child("meta").get().await()
        } catch (e: Exception) {
            null
        }

        // Retry / polling loop if source device is still uploading
        var retryCount = 0
        val maxRetries = 5
        while (retryCount < maxRetries && !isStopped) {
            val expectedCount = metaSnapshot?.child("sourceRecordCount")?.getValue(Long::class.java)?.toInt()
                ?: metaSnapshot?.child("recordCount")?.getValue(Long::class.java)?.toInt() ?: 0
            val isSyncing = metaSnapshot?.child("status")?.getValue(String::class.java) == "syncing"

            val needsWait = (serverSnapshot.childrenCount == 0L && (expectedCount > 0 || metaSnapshot == null)) ||
                    isSyncing ||
                    (expectedCount > 0 && serverSnapshot.childrenCount < expectedCount)

            if (needsWait) {
                retryCount++
                reportProgress(
                    if (expectedCount > 0) "Waiting for source device upload ($retryCount/$maxRetries)"
                    else "Connecting to vault ($retryCount/$maxRetries)",
                    percent = -1
                )
                kotlinx.coroutines.delay(1500)
                serverSnapshot = try { ref.child("transactions").get().await() } catch (e: Exception) { serverSnapshot }
                metaSnapshot = try { ref.child("meta").get().await() } catch (e: Exception) { metaSnapshot }
            } else {
                break
            }
        }

        if (isStopped) return cancelledFailure()

        val totalDownloaded = serverSnapshot.childrenCount.toInt()
        val expectedCount = metaSnapshot?.child("sourceRecordCount")?.getValue(Long::class.java)?.toInt()
            ?: metaSnapshot?.child("recordCount")?.getValue(Long::class.java)?.toInt() ?: 0

        if (totalDownloaded == 0 && expectedCount > 0) {
            return Result.failure(
                workDataOf(
                    KEY_MSG        to "Remote vault upload incomplete ($expectedCount records expected, 0 received). Ensure source device is open and showing 'Vault ready' before scanning.",
                    KEY_COUNT      to 0,
                    KEY_DOWNLOADED to 0,
                    KEY_VALIDATED  to 0,
                    KEY_SKIPPED    to 0
                )
            )
        }

        if (totalDownloaded == 0) {
            // No remote records to merge; switch credentials and queue local records to push to new vault
            val db = Room.databaseBuilder(
                applicationContext,
                AppDatabase::class.java,
                "moneylog-db"
            ).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()

            try {
                val dao = db.transactionDao()
                val syncManager = SyncManager(applicationContext, db)
                val localRecords = dao.getAll()

                val prefs = applicationContext.getSharedPreferences("jotpay_sync", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("vault_id", newVaultId)
                    .putString("secret_key", newSecretKey)
                    .commit()

                syncManager.purgeSyncQueue()
                for (t in localRecords) {
                    syncManager.queueEdit(t.timestamp)
                }

                reportProgress("Finalizing synchronization", percent = 100)
                syncManager.scheduleSync(forcePush = true)

                return Result.success(
                    workDataOf(
                        KEY_MSG        to "Merge complete: 0 remote records found. Local records (${localRecords.size}) queued to sync to new vault.",
                        KEY_COUNT      to 0,
                        KEY_DOWNLOADED to 0,
                        KEY_VALIDATED  to 0,
                        KEY_SKIPPED    to 0
                    )
                )
            } finally {
                db.close()
            }
        }

        val deletedSnapshot = try {
            ref.child("deleted").get().await()
        } catch (e: Exception) {
            return Result.failure(workDataOf(KEY_MSG to "Failed to fetch deleted list: ${e.message}"))
        }

        if (isStopped) return cancelledFailure(downloaded = totalDownloaded)

        // Build serverDeletedIds
        val serverDeletedIds = mutableSetOf<String>()
        for (child in deletedSnapshot.children) {
            child.key?.let { serverDeletedIds.add(it) }
        }

        // Report decrypt stage
        reportProgress("Validating and decrypting records", percent = 0, total = totalDownloaded, processed = 0)

        // Decrypt all server records
        val validatedRecords = mutableListOf<ValidatedRecord>()
        var malformedCount = 0
        val progressStep = maxOf(1, totalDownloaded / 50)

        serverSnapshot.children.forEachIndexed { index, child ->
            if (isStopped) return@forEachIndexed

            val stableId = child.key ?: run { malformedCount++; return@forEachIndexed }
            val encryptedJson = child.getValue(String::class.java) ?: run { malformedCount++; return@forEachIndexed }

            val jsonStr = EncryptionHelper.decrypt(encryptedJson, newSecretKey)
            if (jsonStr.isEmpty()) { malformedCount++; return@forEachIndexed }

            try {
                val obj = JSONObject(jsonStr)
                val ts = obj.optLong("t")
                if (ts <= 0L) {
                    malformedCount++
                    return@forEachIndexed
                }
                validatedRecords.add(
                    ValidatedRecord(
                        stableId        = stableId,
                        originalText    = obj.optString("o"),
                        amount          = obj.optLong("a"),
                        description     = obj.optString("d"),
                        timestamp       = ts,
                        nature          = obj.optString("n", "NORMAL"),
                        obligationAmount = obj.optLong("oa", 0L)
                    )
                )
            } catch (e: Exception) {
                malformedCount++
            }

            val processed = index + 1
            if (totalDownloaded < 20 || processed % progressStep == 0) {
                val pct = if (totalDownloaded > 0) (processed * 100) / totalDownloaded else 0
                reportProgress(
                    "Validating and decrypting records",
                    percent = pct,
                    total = totalDownloaded,
                    processed = processed
                )
            }
        }

        if (isStopped) return cancelledFailure(downloaded = totalDownloaded, validated = validatedRecords.size)

        if (validatedRecords.isEmpty()) {
            return Result.failure(
                workDataOf(
                    KEY_MSG        to "Failed to decrypt records. Encryption key may be invalid or mismatched.",
                    KEY_COUNT      to 0,
                    KEY_DOWNLOADED to totalDownloaded,
                    KEY_VALIDATED  to 0,
                    KEY_SKIPPED    to malformedCount
                )
            )
        }

        // Reconciling merge conflicts
        reportProgress("Reconciling merge conflicts", percent = 0, total = validatedRecords.size, processed = 0)

        // Open DB
        val db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "moneylog-db"
        ).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()

        var importedCount = 0
        var skippedCount = malformedCount

        try {
            val dao = db.transactionDao()
            val syncManager = SyncManager(applicationContext, db)

            // Step 0: Process pending deletes to clean up server and preserve tombstones
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

            // Step 1: Remove local records that were deleted on the server (honoring tombstones)
            val localData = dao.getAll()
            for (t in localData) {
                val stableId = syncManager.generateStableId(t.timestamp)
                if (serverDeletedIds.contains(stableId)) {
                    dao.delete(t)
                }
            }

            // Step 2: Reconcile validated server records into local DB
            val mergeTotal = validatedRecords.size
            val mergeStep = maxOf(1, mergeTotal / 50)

            validatedRecords.forEachIndexed { index, record ->
                if (isStopped) return@forEachIndexed

                // Skip if in server deleted set
                if (serverDeletedIds.contains(record.stableId)) {
                    skippedCount++
                    return@forEachIndexed
                }

                // Skip if locally pending delete
                if (syncManager.getPendingDeletes().contains(record.timestamp.toString())) {
                    skippedCount++
                    return@forEachIndexed
                }

                // Skip if local edit wins
                if (syncManager.hasPendingEdit(record.timestamp)) {
                    skippedCount++
                    return@forEachIndexed
                }

                val existing = dao.getByTimestamp(record.timestamp)
                if (existing == null) {
                    dao.insert(
                        Transaction(
                            id              = 0,
                            originalText    = record.originalText,
                            amount          = record.amount,
                            description     = record.description,
                            timestamp       = record.timestamp,
                            nature          = record.nature,
                            obligationAmount = record.obligationAmount
                        )
                    )
                    importedCount++
                } else {
                    val differs = existing.originalText    != record.originalText ||
                                  existing.amount          != record.amount       ||
                                  existing.description     != record.description  ||
                                  existing.nature          != record.nature       ||
                                  existing.obligationAmount != record.obligationAmount
                    if (differs) {
                        dao.update(
                            existing.copy(
                                originalText    = record.originalText,
                                amount          = record.amount,
                                description     = record.description,
                                nature          = record.nature,
                                obligationAmount = record.obligationAmount
                            )
                        )
                        importedCount++
                    } else {
                        // identical → skipped as duplicate
                        skippedCount++
                    }
                }

                val processed = index + 1
                if (mergeTotal < 20 || processed % mergeStep == 0) {
                    val pct = if (mergeTotal > 0) (processed * 100) / mergeTotal else 100
                    reportProgress(
                        "Reconciling merge conflicts",
                        percent = pct,
                        total = mergeTotal,
                        processed = processed
                    )
                }
            }

            if (isStopped) {
                return Result.failure(workDataOf(
                    KEY_MSG        to "Cancelled during merge (partial import: $importedCount records)",
                    KEY_COUNT      to importedCount,
                    KEY_DOWNLOADED to totalDownloaded,
                    KEY_VALIDATED  to validatedRecords.size,
                    KEY_SKIPPED    to skippedCount
                ))
            }

            // Update vault credentials now that merge has succeeded
            val prefs = applicationContext.getSharedPreferences("jotpay_sync", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("vault_id", newVaultId)
                .putString("secret_key", newSecretKey)
                .commit()

            // Purge and queue all local records to be pushed to new vault
            syncManager.purgeSyncQueue()
            val localRecords = dao.getAll()
            for (t in localRecords) {
                syncManager.queueEdit(t.timestamp)
            }

            // Finalizing
            reportProgress("Finalizing synchronization", percent = 100)

            // Schedule a force-push sync to upload local records to new vault.
            syncManager.scheduleSync(forcePush = true)

            return Result.success(
                workDataOf(
                    KEY_MSG        to "Merge complete: $importedCount records imported" +
                                 (if (skippedCount > 0) " ($skippedCount skipped)" else ""),
                    KEY_COUNT      to importedCount,
                    KEY_DOWNLOADED to totalDownloaded,
                    KEY_VALIDATED  to validatedRecords.size,
                    KEY_SKIPPED    to skippedCount
                )
            )
        } finally {
            db.close()
        }
    }

    // -------------------------------------------------------------------------
    // OVERWRITE
    // -------------------------------------------------------------------------
    private suspend fun doOverwrite(newVaultId: String, newSecretKey: String): Result {
        // 1. Connecting to Firebase
        reportProgress("Connecting to Firebase", percent = -1)

        // 2. Ensure Firebase Auth
        try {
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                auth.signInAnonymously().await()
            }
        } catch (e: Exception) {
            return Result.failure(workDataOf(KEY_MSG to "Auth failed: ${e.message}"))
        }

        if (isStopped) return cancelledFailure()

        // 3. Download records
        reportProgress("Downloading transaction records", percent = -1)

        val ref = FirebaseDatabase.getInstance().getReference("vaults").child(newVaultId)

        var serverSnapshot = try {
            ref.child("transactions").get().await()
        } catch (e: Exception) {
            return Result.failure(workDataOf(KEY_MSG to "Failed to fetch transactions: ${e.message}"))
        }

        var metaSnapshot = try {
            ref.child("meta").get().await()
        } catch (e: Exception) {
            null
        }

        // Retry / polling loop if source device is still uploading
        var retryCount = 0
        val maxRetries = 5
        while (retryCount < maxRetries && !isStopped) {
            val expectedCount = metaSnapshot?.child("sourceRecordCount")?.getValue(Long::class.java)?.toInt()
                ?: metaSnapshot?.child("recordCount")?.getValue(Long::class.java)?.toInt() ?: 0
            val isSyncing = metaSnapshot?.child("status")?.getValue(String::class.java) == "syncing"

            val needsWait = (serverSnapshot.childrenCount == 0L && (expectedCount > 0 || metaSnapshot == null)) ||
                    isSyncing ||
                    (expectedCount > 0 && serverSnapshot.childrenCount < expectedCount)

            if (needsWait) {
                retryCount++
                reportProgress(
                    if (expectedCount > 0) "Waiting for source device upload ($retryCount/$maxRetries)"
                    else "Connecting to vault ($retryCount/$maxRetries)",
                    percent = -1
                )
                kotlinx.coroutines.delay(1500)
                serverSnapshot = try { ref.child("transactions").get().await() } catch (e: Exception) { serverSnapshot }
                metaSnapshot = try { ref.child("meta").get().await() } catch (e: Exception) { metaSnapshot }
            } else {
                break
            }
        }

        if (isStopped) return cancelledFailure()

        val totalDownloaded = serverSnapshot.childrenCount.toInt()
        val expectedCount = metaSnapshot?.child("sourceRecordCount")?.getValue(Long::class.java)?.toInt()
            ?: metaSnapshot?.child("recordCount")?.getValue(Long::class.java)?.toInt() ?: 0
        val metaStatus = metaSnapshot?.child("status")?.getValue(String::class.java)

        // Safety verification if remote has 0 records
        if (totalDownloaded == 0) {
            val isConfirmedEmpty = (metaSnapshot != null && metaStatus == "ready" && expectedCount == 0)

            if (!isConfirmedEmpty) {
                val errorMsg = if (expectedCount > 0) {
                    "Remote vault upload incomplete ($expectedCount records expected, 0 received). Ensure source device is open and showing 'Vault ready' before scanning."
                } else {
                    "Remote vault has 0 records. Ensure source device is open and showing 'Vault ready' before scanning."
                }
                return Result.failure(
                    workDataOf(
                        KEY_MSG        to errorMsg,
                        KEY_COUNT      to 0,
                        KEY_DOWNLOADED to 0,
                        KEY_VALIDATED  to 0,
                        KEY_SKIPPED    to 0
                    )
                )
            }
        }

        val deletedSnapshot = try {
            ref.child("deleted").get().await()
        } catch (e: Exception) {
            return Result.failure(workDataOf(KEY_MSG to "Failed to fetch deleted list: ${e.message}"))
        }

        // Build serverDeletedIds
        val serverDeletedIds = mutableSetOf<String>()
        for (child in deletedSnapshot.children) {
            child.key?.let { serverDeletedIds.add(it) }
        }

        if (isStopped) return cancelledFailure(downloaded = totalDownloaded)

        // Decrypt ALL records into memory
        reportProgress("Validating and decrypting records", percent = 0, total = totalDownloaded, processed = 0)

        val validatedRecords = mutableListOf<ValidatedRecord>()
        var malformedCount = 0
        val progressStep = maxOf(1, totalDownloaded / 50)

        serverSnapshot.children.forEachIndexed { index, child ->
            if (isStopped) return@forEachIndexed

            val stableId = child.key ?: run { malformedCount++; return@forEachIndexed }
            val encryptedJson = child.getValue(String::class.java) ?: run { malformedCount++; return@forEachIndexed }

            val jsonStr = EncryptionHelper.decrypt(encryptedJson, newSecretKey)
            if (jsonStr.isEmpty()) { malformedCount++; return@forEachIndexed }

            try {
                val obj = JSONObject(jsonStr)
                val ts = obj.optLong("t")
                if (ts <= 0L) {
                    malformedCount++
                    return@forEachIndexed
                }
                validatedRecords.add(
                    ValidatedRecord(
                        stableId        = stableId,
                        originalText    = obj.optString("o"),
                        amount          = obj.optLong("a"),
                        description     = obj.optString("d"),
                        timestamp       = ts,
                        nature          = obj.optString("n", "NORMAL"),
                        obligationAmount = obj.optLong("oa", 0L)
                    )
                )
            } catch (e: Exception) {
                malformedCount++
            }

            val processed = index + 1
            if (totalDownloaded < 20 || processed % progressStep == 0) {
                val pct = if (totalDownloaded > 0) (processed * 100) / totalDownloaded else 0
                reportProgress(
                    "Validating and decrypting records",
                    percent = pct,
                    total = totalDownloaded,
                    processed = processed
                )
            }
        }

        if (isStopped) return cancelledFailure(downloaded = totalDownloaded, validated = validatedRecords.size)

        // If records were downloaded but none could be decrypted, abort BEFORE touching DB
        if (totalDownloaded > 0 && validatedRecords.isEmpty()) {
            return Result.failure(
                workDataOf(
                    KEY_MSG        to "Failed to decrypt records. Encryption key may be invalid or mismatched.",
                    KEY_COUNT      to 0,
                    KEY_DOWNLOADED to totalDownloaded,
                    KEY_VALIDATED  to 0,
                    KEY_SKIPPED    to malformedCount
                )
            )
        }

        // Build the list of Transaction objects to insert (exclude server-deleted IDs and deduplicate by timestamp)
        val toInsert = validatedRecords
            .filter { !serverDeletedIds.contains(it.stableId) }
            .distinctBy { it.timestamp }
            .map { record ->
                Transaction(
                    id              = 0,
                    originalText    = record.originalText,
                    amount          = record.amount,
                    description     = record.description,
                    timestamp       = record.timestamp,
                    nature          = record.nature,
                    obligationAmount = record.obligationAmount
                )
            }
        val importedCount = toInsert.size
        val skippedTombstones = validatedRecords.size - toInsert.size
        val totalSkipped = malformedCount + skippedTombstones

        reportProgress("Importing records", percent = 50, total = importedCount, processed = 0)

        // Open DB
        val db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "moneylog-db"
        ).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()

        var dbCommitCompleted = false

        try {
            val dao = db.transactionDao()
            val syncManager = SyncManager(applicationContext, db)

            // ATOMIC Room transaction: deleteAll + insertAll in one block
            db.withTransaction {
                dao.deleteAll()
                dao.insertAll(toInsert)
            }
            dbCommitCompleted = true

            // After DB commit: purge old sync queue, then write new credentials atomically.
            syncManager.purgeSyncQueue()

            val prefs = applicationContext.getSharedPreferences("jotpay_sync", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("vault_id", newVaultId)
                .putString("secret_key", newSecretKey)
                .commit()

            // Finalizing
            reportProgress("Finalizing synchronization", percent = 100)

            // Schedule a routine sync
            syncManager.scheduleSync(forcePush = false)

            val msg = if (totalDownloaded == 0) {
                "Overwrite complete: 0 records in remote vault"
            } else {
                "Overwrite complete: $importedCount records imported" +
                        (if (totalSkipped > 0) " ($totalSkipped skipped)" else "")
            }

            return Result.success(
                workDataOf(
                    KEY_MSG        to msg,
                    KEY_COUNT      to importedCount,
                    KEY_DOWNLOADED to totalDownloaded,
                    KEY_VALIDATED  to validatedRecords.size,
                    KEY_SKIPPED    to totalSkipped
                )
            )
        } catch (e: Exception) {
            return if (dbCommitCompleted) {
                Result.success(
                    workDataOf(
                        KEY_MSG        to "Overwrite committed but post-processing failed: ${e.message}",
                        KEY_COUNT      to importedCount,
                        KEY_DOWNLOADED to totalDownloaded,
                        KEY_VALIDATED  to validatedRecords.size,
                        KEY_SKIPPED    to totalSkipped
                    )
                )
            } else {
                Result.failure(
                    workDataOf(
                        KEY_MSG        to "Overwrite failed: ${e.message}",
                        KEY_COUNT      to 0,
                        KEY_DOWNLOADED to totalDownloaded,
                        KEY_VALIDATED  to validatedRecords.size,
                        KEY_SKIPPED    to totalSkipped
                    )
                )
            }
        } finally {
            db.close()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private suspend fun reportProgress(
        stage: String,
        percent: Int,
        total: Int = -1,
        processed: Int = 0
    ) {
        setProgress(
            workDataOf(
                KEY_STAGE     to stage,
                KEY_PERCENT   to percent,
                KEY_TOTAL     to total,
                KEY_PROCESSED to processed
            )
        )
    }

    private fun cancelledFailure(downloaded: Int = 0, validated: Int = 0): Result =
        Result.failure(
            workDataOf(
                KEY_MSG        to "Transfer cancelled",
                KEY_COUNT      to 0,
                KEY_DOWNLOADED to downloaded,
                KEY_VALIDATED  to validated,
                KEY_SKIPPED    to 0
            )
        )
}
