package com.example.moneylog

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val originalText: String,
    val amount: Double,       // CASH FLOW (Affects Main Balance)
    val description: String,
    val timestamp: Long,

    // NEW FIELDS
    val nature: String = "NORMAL", // "NORMAL", "ASSET", "LIABILITY"
    val obligationAmount: Double = 0.0 // Tracks what is owed/due. DOES NOT affect Main Balance.
)