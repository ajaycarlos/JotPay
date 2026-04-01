package com.example.moneylog

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val originalText: String,
    val amount: Long,       // CASH FLOW in cents/smallest unit (Affects Main Balance)
    val description: String,
    val timestamp: Long,

    // NEW FIELDS
    val nature: String = "NORMAL", // "NORMAL", "ASSET", "LIABILITY"
    val obligationAmount: Long = 0L // Tracks what is owed/due in cents/smallest unit.
)

data class TimelineItem(
    val timestamp: Long,
    val description: String,
    val amount: Long,
    val runningBalance: Long
)

data class PerformanceStats(
    val periodNet: Long = 0L,
    val periodCredits: Long = 0L,
    val periodDebits: Long = 0L,

    val todayNet: Long = 0L,
    val todayCredits: Long = 0L,
    val todayDebits: Long = 0L,

    val weekNet: Long = 0L,
    val weekCredits: Long = 0L,
    val weekDebits: Long = 0L,

    val monthNet: Long = 0L,
    val monthCredits: Long = 0L,
    val monthDebits: Long = 0L,

    val availableTimestamps: List<Long> = emptyList(),
    val timeline: List<TimelineItem> = emptyList()
)