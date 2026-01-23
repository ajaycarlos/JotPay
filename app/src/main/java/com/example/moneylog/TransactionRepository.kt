package com.example.moneylog

import java.util.UUID

class TransactionRepository(private val db: AppDatabase, private val syncManager: SyncManager) {

    suspend fun getAllTransactions() = db.transactionDao().getAll()

    suspend fun search(query: String) = db.transactionDao().search(query)

    suspend fun insert(transaction: Transaction) {
        // FIX: Whitelist this item so SyncWorker knows it's new/modified
        syncManager.queueEdit(transaction.timestamp)
        db.transactionDao().insert(transaction)
    }

    suspend fun update(transaction: Transaction) {
        // FIX: Whitelist this item so SyncWorker protects it from overwrite
        syncManager.queueEdit(transaction.timestamp)
        db.transactionDao().update(transaction)
    }

    suspend fun delete(transaction: Transaction) {
        syncManager.queueDelete(transaction.timestamp)
        db.transactionDao().delete(transaction)
        syncManager.scheduleSync()
    }

    suspend fun checkDuplicate(amount: Double, desc: String, start: Long, end: Long): Int {
        return db.transactionDao().checkDuplicate(amount, desc, start, end)
    }

    fun scheduleSync(forcePush: Boolean = false): UUID {
        return syncManager.scheduleSync(forcePush)
    }
}