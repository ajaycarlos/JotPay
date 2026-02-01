package com.example.moneylog

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Transaction::class], version = 2) // Version Bumped
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add new columns with defaults
                database.execSQL("ALTER TABLE transactions ADD COLUMN nature TEXT NOT NULL DEFAULT 'NORMAL'")
                database.execSQL("ALTER TABLE transactions ADD COLUMN obligationAmount REAL NOT NULL DEFAULT 0.0")
            }
        }
    }
}