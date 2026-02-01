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

class TransactionViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: TransactionRepository

    private val _transactions = MutableLiveData<List<Transaction>>()
    val transactions: LiveData<List<Transaction>> = _transactions

    private val _totalBalance = MutableLiveData<Double>()
    val totalBalance: LiveData<Double> = _totalBalance

    // NEW: Assets & Liabilities LiveData
    private val _assets = MutableLiveData<List<Transaction>>()
    val assets: LiveData<List<Transaction>> = _assets

    private val _liabilities = MutableLiveData<List<Transaction>>()
    val liabilities: LiveData<List<Transaction>> = _liabilities

    private val _totalAssets = MutableLiveData<Double>()
    val totalAssets: LiveData<Double> = _totalAssets

    private val _totalLiabilities = MutableLiveData<Double>()
    val totalLiabilities: LiveData<Double> = _totalLiabilities

    init {
        // Initialize DB with Migration Strategy
        val db = Room.databaseBuilder(application, AppDatabase::class.java, "moneylog-db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
        val syncManager = SyncManager(application, db)
        repository = TransactionRepository(db, syncManager)

        // Initial Load
        refreshData()
    }

    fun refreshData() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = repository.getAllTransactions()
            val total = list.sumOf { it.amount }

            withContext(Dispatchers.Main) {
                _transactions.value = list
                _totalBalance.value = total
            }
        }
    }

    // UPDATED: Added 'nature' parameter and Settlement Logic
    fun addTransaction(originalText: String, amount: Double, desc: String, nature: String) {
        viewModelScope.launch(Dispatchers.IO) {

            // SETTLEMENT MATH:
            // If Nature is Asset/Liability, the Obligation is the INVERSE of the cash flow.
            // Example 1 (Lending): Cash -500. Obligation +500 (Owed to you).
            // Example 2 (Repayment): Cash +200. Obligation -200 (Reduces what is owed).
            val obligation = when (nature) {
                "ASSET", "LIABILITY" -> -amount
                else -> 0.0
            }

            val t = Transaction(
                originalText = originalText,
                amount = amount,
                description = desc,
                timestamp = System.currentTimeMillis(),
                nature = nature,
                obligationAmount = obligation
            )
            repository.insert(t)
            refreshData()
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

    fun updateTransaction(transaction: Transaction) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.update(transaction)
            refreshData()
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
            for(t in list) {
                val start = t.timestamp - 60000
                val end = t.timestamp + 60000
                val count = repository.checkDuplicate(t.amount, t.description, start, end)
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
}