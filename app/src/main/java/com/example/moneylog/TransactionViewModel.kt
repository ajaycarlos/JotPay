package com.example.moneylog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs

class TransactionViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: TransactionRepository

    private val _transactions = MutableLiveData<List<Transaction>>()
    val transactions: LiveData<List<Transaction>> = _transactions

    private val _totalBalance = MutableLiveData<Double>()
    val totalBalance: LiveData<Double> = _totalBalance

    private val _assets = MutableLiveData<List<Transaction>>()
    val assets: LiveData<List<Transaction>> = _assets

    private val _liabilities = MutableLiveData<List<Transaction>>()
    val liabilities: LiveData<List<Transaction>> = _liabilities

    private val _totalAssets = MutableLiveData<Double>()
    val totalAssets: LiveData<Double> = _totalAssets

    private val _totalLiabilities = MutableLiveData<Double>()
    val totalLiabilities: LiveData<Double> = _totalLiabilities

    private val _performanceStats = MutableLiveData<PerformanceStats>()
    val performanceStats: LiveData<PerformanceStats> = _performanceStats

    private val _dateRange = MutableLiveData<Pair<Long, Long>?>(null)
    val dateRange: LiveData<Pair<Long, Long>?> = _dateRange

    fun setDateRange(start: Long, end: Long) {
        _dateRange.value = Pair(start, end)
        _transactions.value?.let { calculatePerformanceStats(it) }
    }

    init {
        val db = Room.databaseBuilder(application, AppDatabase::class.java, "moneylog-db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
        val syncManager = SyncManager(application, db)
        repository = TransactionRepository(db, syncManager)

        refreshData()
    }

    fun refreshData() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = repository.getAllTransactions()
            val total = list.filter { it.nature == "NORMAL" }.sumOf { it.amount }

            withContext(Dispatchers.Main) {
                _transactions.value = list
                _totalBalance.value = total
            }

            // RUN OUR NEW CALCULATION ENGINE IN THE BACKGROUND
            calculatePerformanceStats(list)
        }
    }

    fun addTransaction(originalText: String, amount: Double, desc: String, nature: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val obligation = calculateObligation(amount, nature)

            // FIX: Enforce uniqueness at creation to prevent Sync ID collisions
            var ts = System.currentTimeMillis()
            while (repository.getByTimestamp(ts) != null) {
                ts += 1
            }

            val t = Transaction(
                originalText = originalText,
                amount = amount,
                description = desc,
                timestamp = ts,
                nature = nature,
                obligationAmount = obligation
            )
            repository.insert(t)
            refreshData()
        }
    }

    // FIX: Updated to verify math consistency
    fun updateTransaction(transaction: Transaction) {
        viewModelScope.launch(Dispatchers.IO) {
            // Recalculate obligation in case the Amount OR Nature changed during the edit
            val newObligation = calculateObligation(transaction.amount, transaction.nature)

            val finalTransaction = transaction.copy(obligationAmount = newObligation)

            repository.update(finalTransaction)
            refreshData()
        }
    }

    // Helper logic to keep Add/Edit consistent

    // FIX: Pure Inversion Logic
    private fun calculateObligation(amount: Double, nature: String): Double {
        return when (nature) {
            "ASSET", "LIABILITY" -> {
                // Simply invert the sign.
                // If you Lend (-500), Obligation becomes +500 (Added to Asset).
                // If you get Repaid (+300), Obligation becomes -300 (Subtracted from Asset).
                -amount
            }

            else -> 0.0
        }
    }

    fun loadAssetsAndLiabilities() {
        viewModelScope.launch(Dispatchers.IO) {
            val assetList = repository.getAssets()
            val liabilityList = repository.getLiabilities()
            val assetTotal = repository.getTotalAssets()
            val liabilityTotal = repository.getTotalLiabilities()

            withContext(Dispatchers.Main) {
                _assets.value = assetList
                _liabilities.value = liabilityList
                _totalAssets.value = assetTotal ?: 0.0
                _totalLiabilities.value = liabilityTotal ?: 0.0
            }
        }
    }

    fun deleteTransaction(transaction: Transaction) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(transaction)
            refreshData()
        }
    }

    fun search(query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val results = repository.search(query)
            val searchTotal = results.sumOf { it.amount }

            withContext(Dispatchers.Main) {
                _transactions.value = results
                _totalBalance.value = searchTotal
            }
        }
    }

    fun importTransactionList(list: List<Transaction>) {
        viewModelScope.launch(Dispatchers.IO) {
            for (t in list) {
                // FIX: Removed 60-second window. Now checks Exact Match (Timestamp + Amount + Desc)
                // This prevents legitimate simultaneous purchases from being discarded.
                val count = repository.checkDuplicate(t.amount, t.description, t.timestamp)
                if (count == 0) {
                    repository.insert(t)
                }
            }
            refreshData()
        }
    }

    fun scheduleSync(forcePush: Boolean = false): UUID {
        return repository.scheduleSync(forcePush)
    }

    fun clearAllTransactions(onFinished: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteAll()
            withContext(Dispatchers.Main) {
                refreshData()
                onFinished()
            }
        }
    }

    private fun calculatePerformanceStats(list: List<Transaction>) {
        viewModelScope.launch(Dispatchers.Default) {
            val now = java.util.Calendar.getInstance()
            val currentYear = now.get(java.util.Calendar.YEAR)
            val currentDay = now.get(java.util.Calendar.DAY_OF_YEAR)
            val currentWeek = now.get(java.util.Calendar.WEEK_OF_YEAR)
            val currentMonth = now.get(java.util.Calendar.MONTH)

            var todayNet = 0.0
            var todayCredits = 0.0
            var todayDebits = 0.0
            var weekNet = 0.0
            var weekCredits = 0.0
            var weekDebits = 0.0
            var monthNet = 0.0
            var monthCredits = 0.0
            var monthDebits = 0.0

            val sortedList = list.filter { it.nature == "NORMAL" }.sortedBy { it.timestamp }
            if (sortedList.isEmpty()) {
                withContext(Dispatchers.Main) { _performanceStats.value = PerformanceStats() }
                return@launch
            }

            val allDays = sortedList.map {
                val c = java.util.Calendar.getInstance()
                c.timeInMillis = it.timestamp
                c.set(java.util.Calendar.HOUR_OF_DAY, 0)
                c.set(java.util.Calendar.MINUTE, 0)
                c.set(java.util.Calendar.SECOND, 0)
                c.set(java.util.Calendar.MILLISECOND, 0)
                c.timeInMillis
            }.distinct()

            val selectedStart = _dateRange.value?.first ?: allDays.first()
            val selectedEnd = _dateRange.value?.second ?: allDays.last()

            val strictEnd = if (selectedEnd == Long.MAX_VALUE) Long.MAX_VALUE else {
                val endCal = java.util.Calendar.getInstance()
                endCal.timeInMillis = selectedEnd
                endCal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                endCal.timeInMillis - 1L
            }

            var periodNet = 0.0
            var periodCredits = 0.0
            var periodDebits = 0.0
            var runningBalance = 0.0
            val timelineList = mutableListOf<TimelineItem>()
            val cal = java.util.Calendar.getInstance()

            // 1. RUN THE CALCULATION LOOP
            for (t in sortedList) {
                runningBalance += t.amount
                cal.timeInMillis = t.timestamp

                if (cal.get(java.util.Calendar.YEAR) == currentYear) {
                    if (cal.get(java.util.Calendar.DAY_OF_YEAR) == currentDay) {
                        todayNet += t.amount
                        if (t.amount > 0) todayCredits += t.amount else todayDebits += t.amount
                    }
                    if (cal.get(java.util.Calendar.WEEK_OF_YEAR) == currentWeek) {
                        weekNet += t.amount
                        if (t.amount > 0) weekCredits += t.amount else weekDebits += t.amount
                    }
                    if (cal.get(java.util.Calendar.MONTH) == currentMonth) {
                        monthNet += t.amount
                        if (t.amount > 0) monthCredits += t.amount else monthDebits += t.amount
                    }
                }

                if (t.timestamp in selectedStart..strictEnd) {
                    periodNet += t.amount
                    if (t.amount > 0) periodCredits += t.amount else periodDebits += t.amount
                    timelineList.add(TimelineItem(t.timestamp, t.description, t.amount, runningBalance))
                }
            }

            // 2. AFTER THE LOOP IS FINISHED, CREATE THE STATS OBJECT
            timelineList.reverse()

            val finalStats = PerformanceStats(
                periodNet = periodNet,
                periodCredits = periodCredits,
                periodDebits = periodDebits,
                todayNet = todayNet,
                todayCredits = todayCredits,
                todayDebits = todayDebits,
                weekNet = weekNet,
                weekCredits = weekCredits,
                weekDebits = weekDebits,
                monthNet = monthNet,
                monthCredits = monthCredits,
                monthDebits = monthDebits,
                availableTimestamps = allDays,
                timeline = timelineList
            )

            // 3. EMIT THE FINISHED DATA ONCE
            withContext(Dispatchers.Main) {
                _performanceStats.value = finalStats
            }
        }
    }
}