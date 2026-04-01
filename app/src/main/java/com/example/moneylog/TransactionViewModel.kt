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

    private val _totalBalance = MutableLiveData<Long>()
    val totalBalance: LiveData<Long> = _totalBalance

    private val _assets = MutableLiveData<List<Transaction>>()
    val assets: LiveData<List<Transaction>> = _assets

    private val _liabilities = MutableLiveData<List<Transaction>>()
    val liabilities: LiveData<List<Transaction>> = _liabilities

    private val _totalAssets = MutableLiveData<Long>()
    val totalAssets: LiveData<Long> = _totalAssets

    private val _totalLiabilities = MutableLiveData<Long>()
    val totalLiabilities: LiveData<Long> = _totalLiabilities

    private val _performanceStats = MutableLiveData<PerformanceStats>()
    val performanceStats: LiveData<PerformanceStats> = _performanceStats

    private val _dateRange = MutableLiveData<Pair<Long, Long>?>(null)
    val dateRange: LiveData<Pair<Long, Long>?> = _dateRange

    fun setDateRange(start: Long, end: Long) {
        _dateRange.value = Pair(start, end)
        _transactions.value?.let { calculatePerformanceStats(it) }
    }

    // FIX: Declare property ABOVE the init block to ensure it is instantiated before use
    private var currentQuery = ""

    init {
        val db = Room.databaseBuilder(application, AppDatabase::class.java, "moneylog-db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3) // Register BOTH migrations
            .build()
        val syncManager = SyncManager(application, db)
        repository = TransactionRepository(db, syncManager)

        refreshData()
    }

    fun refreshData() {
        // FIX: Delegate to search to re-apply active filters when DB updates occur
        search(currentQuery)
    }

    fun addTransaction(originalText: String, amount: Long, desc: String, nature: String) {
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

    // FIX: Pure Inversion Logic using Long
    private fun calculateObligation(amount: Long, nature: String): Long {
        return when (nature) {
            "ASSET", "LIABILITY" -> {
                // Simply invert the sign.
                // If you Lend (-500), Obligation becomes +500 (Added to Asset).
                // If you get Repaid (+300), Obligation becomes -300 (Subtracted from Asset).
                -amount
            }

            else -> 0L
        }
    }

    fun loadAssetsAndLiabilities() {
        viewModelScope.launch(Dispatchers.IO) {
            val assetList = repository.getAssets()
            val liabilityList = repository.getLiabilities()

            withContext(Dispatchers.Main) {
                _assets.value = assetList
                _liabilities.value = liabilityList
                // FIX: Removed unused aggregate DB queries. AssetsLiabilitiesActivity calculates this dynamically.
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
        currentQuery = query
        viewModelScope.launch(Dispatchers.IO) {
            val results = if (query.isNotEmpty()) repository.search(query) else repository.getAllTransactions()
            val searchTotal = results.sumOf { it.amount }

            withContext(Dispatchers.Main) {
                _transactions.value = results
                _totalBalance.value = searchTotal
            }

            // FIX: Ensure dashboard stats are always calculated against the full DB, not the filtered list
            val fullList = if (query.isNotEmpty()) repository.getAllTransactions() else results
            calculatePerformanceStats(fullList)
        }
    }

    fun importTransactionList(list: List<Transaction>) {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Fetch all existing records to memory for lightning-fast O(1) lookups
            val existing = repository.getAllTransactions()
            // FIX: Use ONLY timestamp as the unique signature. Using mutable fields (amount/desc)
            // causes edited transactions to be double-counted as "new" during future CSV restores.
            val existingSigs = existing.map { it.timestamp.toString() }.toHashSet()

            // 2. Filter out duplicates in-memory (No database I/O required)
            val toInsert = list.filter { t ->
                !existingSigs.contains(t.timestamp.toString())
            }

            // 3. Batch insert everything at once
            if (toInsert.isNotEmpty()) {
                repository.insertAll(toInsert)
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

            var todayNet = 0L
            var todayCredits = 0L
            var todayDebits = 0L
            var weekNet = 0L
            var weekCredits = 0L
            var weekDebits = 0L
            var monthNet = 0L
            var monthCredits = 0L
            var monthDebits = 0L

            val sortedList = list.sortedBy { it.timestamp }
            if (sortedList.isEmpty()) {
                withContext(Dispatchers.Main) { _performanceStats.value = PerformanceStats() }
                return@launch
            }

            val dayCalc = java.util.Calendar.getInstance()
            val allDays = sortedList.map {
                dayCalc.timeInMillis = it.timestamp
                dayCalc.set(java.util.Calendar.HOUR_OF_DAY, 0)
                dayCalc.set(java.util.Calendar.MINUTE, 0)
                dayCalc.set(java.util.Calendar.SECOND, 0)
                dayCalc.set(java.util.Calendar.MILLISECOND, 0)
                dayCalc.timeInMillis
            }.distinct()

            val selectedStart = _dateRange.value?.first ?: allDays.first()
            val selectedEnd = _dateRange.value?.second ?: allDays.last()

            val strictEnd = if (selectedEnd == Long.MAX_VALUE) Long.MAX_VALUE else {
                val endCal = java.util.Calendar.getInstance()
                endCal.timeInMillis = selectedEnd
                endCal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                endCal.timeInMillis - 1L
            }

            var periodNet = 0L
            var periodCredits = 0L
            var periodDebits = 0L
            var runningBalance = 0L
            val timelineList = mutableListOf<TimelineItem>()
            val cal = java.util.Calendar.getInstance()

            // 1. RUN THE CALCULATION LOOP
            for (t in sortedList) {
                // Running balance (Cash in Hand) must ALWAYS include all cash flows (Loans/Assets)
                runningBalance += t.amount
                cal.timeInMillis = t.timestamp

                // FIX: To match the Main Balance (Cash-Basis), all cash flows must be included.
                // Removing the 'isNormal' filter ensures the Net reflects actual Cash in Hand
                // by accounting for the cash-outflow of Assets (-₹645) and inflow of Liabilities.

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
    fun restoreTransaction(transaction: Transaction) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insert(transaction)
            refreshData()
        }
    }
}