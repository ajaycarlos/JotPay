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

data class TimelineItem(
    val timestamp: Long,
    val description: String,
    val amount: Double,
    val runningBalance: Double
)

data class PerformanceStats(
    val periodNet: Double = 0.0,
    val periodCredits: Double = 0.0,
    val periodDebits: Double = 0.0,

    val todayNet: Double = 0.0,
    val todayCredits: Double = 0.0,
    val todayDebits: Double = 0.0,

    val weekNet: Double = 0.0,
    val weekCredits: Double = 0.0,
    val weekDebits: Double = 0.0,

    // RESTORE THESE MISSING VARIABLES
    val monthNet: Double = 0.0,
    val monthCredits: Double = 0.0,
    val monthDebits: Double = 0.0,

    val availableTimestamps: List<Long> = emptyList(),
    val timeline: List<TimelineItem> = emptyList()
)