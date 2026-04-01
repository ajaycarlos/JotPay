package com.example.moneylog

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Transaction::class], version = 3) // Version Bumped
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE transactions ADD COLUMN nature TEXT NOT NULL DEFAULT 'NORMAL'")
                database.execSQL("ALTER TABLE transactions ADD COLUMN obligationAmount REAL NOT NULL DEFAULT 0.0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. Create a new table with the updated INTEGER (Long) types
                database.execSQL("""
            CREATE TABLE transactions_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                originalText TEXT NOT NULL,
                amount INTEGER NOT NULL,
                description TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                nature TEXT NOT NULL DEFAULT 'NORMAL',
                obligationAmount INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

                // 2. Copy data, converting previous Double (REAL) values to Long (INTEGER) cents
                // We use ROUND() to ensure floating point artifacts (e.g. 50.0000001) don't corrupt the math
                database.execSQL("""
            INSERT INTO transactions_new (id, originalText, amount, description, timestamp, nature, obligationAmount)
            SELECT id, originalText, CAST(ROUND(amount * 100) AS INTEGER), description, timestamp, nature, CAST(ROUND(obligationAmount * 100) AS INTEGER)
            FROM transactions
        """.trimIndent())

                // 3. Drop the old table and rename the new one
                database.execSQL("DROP TABLE transactions")
                database.execSQL("ALTER TABLE transactions_new RENAME TO transactions")
            }
        }
    }
}