package com.example.moneylog

import java.util.UUID

class TransactionRepository(private val db: AppDatabase, private val syncManager: SyncManager) {

    // Passthroug to DAO
    suspend fun getAllTransactions() = db.transactionDao().getAll()

    suspend fun search(query: String) = db.transactionDao().search(query)

    suspend fun insert(transaction: Transaction) {
        db.transactionDao().insert(transaction)
    }

    suspend fun update(transaction: Transaction) {
        db.transactionDao().update(transaction)
    }

    suspend fun delete(transaction: Transaction) {
        // 1. Queue the delete locally (so we remember it even if offline)
        syncManager.queueDelete(transaction.timestamp)

        // 2. Delete from Local DB immediately (Update UI)
        db.transactionDao().delete(transaction)

        // 3. Schedule a sync to push this delete to server
        syncManager.scheduleSync()
    }

    suspend fun checkDuplicate(amount: Double, desc: String, start: Long, end: Long): Int {
        return db.transactionDao().checkDuplicate(amount, desc, start, end)
    }

    // Trigger the Syncworker
    fun scheduleSync(forcePush: Boolean = false): UUID {
        return syncManager.scheduleSync(forcePush)
    }
}