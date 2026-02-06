package com.example.moneylog

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.moneylog.databinding.ActivityAssetsLiabilitiesBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.abs

class AssetsLiabilitiesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAssetsLiabilitiesBinding
    private val viewModel: TransactionViewModel by viewModels()
    private lateinit var adapter: TransactionAdapter
    private var isAssetsTab = true
    private var currentSearchQuery = "" // Track search text

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAssetsLiabilitiesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupObservers()

        viewModel.loadAssetsAndLiabilities()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        binding.rvList.layoutManager = LinearLayoutManager(this)

        adapter = TransactionAdapter(emptyList()) { transaction ->
            showActionDialog(transaction)
        }
        adapter.showSigns = false
        adapter.showNatureIndicator = false
        binding.rvList.adapter = adapter

        // Tab Switching
        binding.btnTabAssets.setOnClickListener { switchTab(true) }
        binding.btnTabLiabilities.setOnClickListener { switchTab(false) }

        // NEW: Search Listener
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                currentSearchQuery = s.toString().trim()
                updateDisplay() // Re-run filter & total calc
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun switchTab(showAssets: Boolean) {
        if (isAssetsTab == showAssets) return // Avoid redundant refresh

        isAssetsTab = showAssets

        // Clear search when switching tabs to avoid confusion
        currentSearchQuery = ""
        binding.etSearch.setText("")
        binding.etSearch.clearFocus()

        if (showAssets) {
            styleButton(binding.btnTabAssets, true, R.color.income_muted)
            styleButton(binding.btnTabLiabilities, false, R.color.expense_muted)
        } else {
            styleButton(binding.btnTabAssets, false, R.color.income_muted)
            styleButton(binding.btnTabLiabilities, true, R.color.expense_muted)
        }

        updateDisplay()
    }

    private fun styleButton(btn: MaterialButton, isActive: Boolean, activeColorRes: Int) {
        if (isActive) {
            btn.backgroundTintList = ContextCompat.getColorStateList(this, activeColorRes)
            btn.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            btn.strokeWidth = 0
        } else {
            btn.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            btn.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btn.strokeColor = ContextCompat.getColorStateList(this, R.color.text_tertiary)
            btn.strokeWidth = 2
        }
    }

    private fun setupObservers() {
        viewModel.assets.observe(this) { updateDisplay() }
        viewModel.liabilities.observe(this) { updateDisplay() }
        // We don't need totalAssets/Liabilities observers anymore because we calc it dynamically

        viewModel.transactions.observe(this) {
            viewModel.loadAssetsAndLiabilities()
        }
    }

    private fun updateDisplay() {
        val symbol = CurrencyHelper.getSymbol(this)

        // 1. Get the Full List based on Tab
        val fullList = if (isAssetsTab) {
            viewModel.assets.value ?: emptyList()
        } else {
            viewModel.liabilities.value ?: emptyList()
        }

        // 2. Filter List based on Search Query
        val filteredList = if (currentSearchQuery.isEmpty()) {
            fullList
        } else {
            fullList.filter {
                it.description.contains(currentSearchQuery, ignoreCase = true)
            }
        }

        // 3. Calculate Dynamic Total based on FILTERED list
        val dynamicTotal = filteredList.sumOf { it.obligationAmount }

        // 4. Update UI
        if (isAssetsTab) {
            binding.tvTotalLabel.text = if(currentSearchQuery.isEmpty()) "TOTAL TO RECEIVE" else "TOTAL FROM '${currentSearchQuery.uppercase()}'"
            binding.tvTotalValue.text = "$symbol ${fmt(dynamicTotal)}"
            binding.tvTotalValue.setTextColor(ContextCompat.getColor(this, R.color.income_green))
        } else {
            binding.tvTotalLabel.text = if(currentSearchQuery.isEmpty()) "TOTAL TO PAY" else "TOTAL TO '${currentSearchQuery.uppercase()}'"
            binding.tvTotalValue.text = "$symbol ${fmt(abs(dynamicTotal))}"
            binding.tvTotalValue.setTextColor(ContextCompat.getColor(this, R.color.expense_red))
        }

        // 5. Update Adapter
        val displayList = filteredList.map { it.copy(amount = it.obligationAmount) }
        adapter.updateData(displayList)

        binding.tvEmptyHint.text = if(currentSearchQuery.isNotEmpty()) "No matches found" else "No active records"
        binding.tvEmptyHint.visibility = if (filteredList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showActionDialog(transaction: Transaction) {
        val options = arrayOf("Settle (Mark Paid)", "Delete (Correction)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Choose Action")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSettleDialog(transaction)
                    1 -> confirmDelete(transaction)
                }
            }
            .show()
    }

    private fun showSettleDialog(transaction: Transaction) {
        val settleAmount = transaction.obligationAmount
        val symbol = CurrencyHelper.getSymbol(this)
        val displayAmount = "$symbol ${fmt(abs(settleAmount))}"

        MaterialAlertDialogBuilder(this)
            .setTitle("Settle Transaction?")
            .setMessage("Mark this record as settled?\n\nThis will remove it from the active list and record a $displayAmount payment in your main history.")
            .setPositiveButton("Settle") { _, _ ->
                val desc = "Settlement: ${transaction.description}"
                viewModel.addTransaction(desc, settleAmount, desc, "NORMAL")

                val updated = transaction.copy(nature = "NORMAL")
                viewModel.updateTransaction(updated)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(transaction: Transaction) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove Record")
            .setMessage("How do you want to remove '${transaction.description}'?")
            .setPositiveButton("Unmark Only") { _, _ ->
                val updated = transaction.copy(nature = "NORMAL")
                viewModel.updateTransaction(updated)
            }
            .setNeutralButton("Delete Forever") { _, _ ->
                viewModel.deleteTransaction(transaction)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fmt(d: Double): String {
        return if (d % 1.0 == 0.0) d.toLong().toString() else String.format("%.2f", d)
    }
}