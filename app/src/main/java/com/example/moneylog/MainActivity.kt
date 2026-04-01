
    package com.example.moneylog

    import android.animation.ValueAnimator
            import android.content.Context
            import android.content.Intent
            import android.net.Uri
            import android.os.Bundle
            import android.text.Editable
            import android.text.InputType
            import android.text.TextWatcher
            import android.view.View
            import android.view.inputmethod.InputMethodManager
            import android.widget.ArrayAdapter
            import android.widget.Toast
            import androidx.activity.OnBackPressedCallback
            import androidx.activity.result.contract.ActivityResultContracts
            import androidx.activity.viewModels
            import androidx.appcompat.app.AppCompatActivity
            import androidx.appcompat.app.AppCompatDelegate
            import androidx.core.content.ContextCompat
            import androidx.core.content.FileProvider
            import androidx.core.view.GravityCompat
            import androidx.lifecycle.lifecycleScope
            import androidx.recyclerview.widget.LinearLayoutManager
            import androidx.room.Room
            import androidx.work.WorkInfo
            import androidx.work.WorkManager
            import com.example.moneylog.databinding.ActivityMainBinding
            import com.google.android.material.dialog.MaterialAlertDialogBuilder
            import kotlinx.coroutines.Dispatchers
            import kotlinx.coroutines.launch
            import kotlinx.coroutines.withContext
            import java.io.BufferedReader
            import java.io.File
            import java.io.InputStreamReader
            import java.text.SimpleDateFormat
            import java.util.Calendar
            import java.util.Date
            import java.util.Locale
            import kotlin.math.abs
            import android.os.Handler
            import android.os.Looper
            import android.widget.TextView
    import com.google.android.play.core.appupdate.AppUpdateManager
    import com.google.android.play.core.appupdate.AppUpdateManagerFactory
    import com.google.android.play.core.install.InstallStateUpdatedListener
    import com.google.android.play.core.install.model.AppUpdateType
    import com.google.android.play.core.install.model.InstallStatus
    import com.google.android.play.core.install.model.UpdateAvailability
    import com.google.android.material.snackbar.Snackbar

    class MainActivity : AppCompatActivity() {

        private lateinit var binding: ActivityMainBinding
        private val viewModel: TransactionViewModel by viewModels()
        private lateinit var adapter: TransactionAdapter
        private var editingTransaction: Transaction? = null
        private var pendingEditId: Long = 0L
        private var pendingEditText: String = "" // FIX: Variable to hold text during rotation
        private var balanceAnimator: ValueAnimator? = null
        private var currentDisplayedBalance = 0.0
        private lateinit var appUpdateManager: AppUpdateManager
        private val updateListener = InstallStateUpdatedListener { state ->
            if (state.installStatus() == InstallStatus.DOWNLOADED) {
                // Once downloaded, show a message to the user to restart
                showUpdateFinishedSnackbar()
            }
        }
        private var isOnboardingShowing = false

        private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { parseAndImportCsv(it) }
        }
        private var undoRunnable: Runnable? = null
        private val undoHandler = Handler(Looper.getMainLooper())

        override fun onCreate(savedInstanceState: Bundle?) {
            // Force Dark Mode for consistent UI
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)



            super.onCreate(savedInstanceState)

            // FIX: Pre-warm Anonymous Auth silently on app launch so SyncWorker runs instantly later
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            if (auth.currentUser == null) auth.signInAnonymously()

            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            if (savedInstanceState != null) {
                pendingEditId = savedInstanceState.getLong("editing_id", 0L)
                pendingEditText = savedInstanceState.getString("editing_text", "") // FIX: Extract saved text
            }

            setupRecyclerView()
            setupListeners()
            setupInputLogic()
            appUpdateManager = AppUpdateManagerFactory.create(this)
            appUpdateManager.registerListener(updateListener)


            val prefs = getSharedPreferences("moneylog_prefs", Context.MODE_PRIVATE)
            val isSetupDone = prefs.getBoolean("policy_accepted", false) && CurrencyHelper.isCurrencySet(this)

            observeViewModel()

            if (isSetupDone) {
                checkMonthlyCheckpoint()
                checkBackupReminder()
                checkFeatureDiscovery()
                checkForUpdates()
            }

            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.etSearch.visibility == View.VISIBLE) {
                        closeSearchBar()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            })
        }

        private fun closeSearchBar() {
            binding.etSearch.text.clear() // Triggers TextWatcher which updates the ViewModel safely
            binding.etSearch.visibility = View.GONE
            binding.btnCloseSearch.visibility = View.GONE
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
            // FIX: Removed redundant viewModel.refreshData() to prevent DB race condition
        }

        override fun onResume() {
            super.onResume()
            val prefs = getSharedPreferences("moneylog_prefs", Context.MODE_PRIVATE)
            val policyAccepted = prefs.getBoolean("policy_accepted", false)
            val currencySet = CurrencyHelper.isCurrencySet(this)

            if (!policyAccepted) {
                if (!isOnboardingShowing) checkFirstLaunchFlow()
            } else if (!currencySet) {
                if (!isOnboardingShowing) checkCurrencySetup()
            } else {
                // Only refresh and check for updates IF setup is complete
                viewModel.refreshData()
                runSync()
                checkForUpdates()
            }

            // Check for downloaded background updates
            appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    showUpdateFinishedSnackbar()
                }
            }
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)
            editingTransaction?.let {
                outState.putLong("editing_id", it.timestamp)
                // FIX: Save the actual typed text so it survives the Activity destruction
                outState.putString("editing_text", binding.etInput.text.toString())
            }
        }

        private fun observeViewModel() {
            viewModel.transactions.observe(this) { list ->
                adapter.updateData(list)
                updateAutocomplete(list)

                if (pendingEditId != 0L) {
                    val target = list.find { it.timestamp == pendingEditId }
                    if (target != null) {
                        startEditing(target)
                        // FIX: Restore user's in-progress text instead of overwriting with original text
                        if (pendingEditText.isNotEmpty()) {
                            binding.etInput.setText(pendingEditText)
                            binding.etInput.setSelection(pendingEditText.length)
                        }
                        pendingEditId = 0L
                        pendingEditText = ""
                    }
                }

                if (list.isEmpty()) {
                    binding.tvEmptyState.text = "Try +7000"
                    binding.tvEmptyState.visibility = View.VISIBLE
                    binding.rvTransactions.visibility = View.GONE
                } else {
                    binding.tvEmptyState.visibility = View.GONE
                    binding.rvTransactions.visibility = View.VISIBLE
                }
            }

            viewModel.totalBalance.observe(this) { totalCents ->
                val finalTotal = (totalCents ?: 0L) / 100.0
                val symbol = CurrencyHelper.getSymbol(this)

                // FIX: Determine if search is active to adjust UI
                val isSearchActive = binding.etSearch.visibility == View.VISIBLE && binding.etSearch.text.isNotEmpty()

                // FIX: If searching, show absolute value (Total Spend). If not, show actual balance.
                val targetValue = if (isSearchActive) abs(finalTotal) else finalTotal

                // FIX: Update label dynamically
                binding.tvBalanceLabel.text = if (isSearchActive) "Total Found" else "Current Balance"

                balanceAnimator?.cancel()
                balanceAnimator = null

                val formatter = java.text.NumberFormat.getInstance(Locale.getDefault()).apply {
                    minimumFractionDigits = 0
                    maximumFractionDigits = 2
                }

                if (currentDisplayedBalance != targetValue) {
                    val animator = ValueAnimator.ofObject(DoubleEvaluator(), currentDisplayedBalance, targetValue)
                    animator.duration = 500
                    animator.addUpdateListener { animation ->
                        val animatedValue = animation.animatedValue as Double
                        binding.tvTotalBalance.text = "$symbol ${formatter.format(animatedValue)}"
                    }
                    animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            binding.tvTotalBalance.text = "$symbol ${formatter.format(targetValue)}"
                        }
                    })
                    animator.start()
                    balanceAnimator = animator
                    currentDisplayedBalance = targetValue
                } else {
                    binding.tvTotalBalance.text = "$symbol ${formatter.format(targetValue)}"
                }
                binding.tvTotalBalance.setTextColor(android.graphics.Color.WHITE)
            }
        }

        private fun setupRecyclerView() {
            adapter = TransactionAdapter(emptyList()) { transaction ->
                showActionDialog(transaction)
            }
            binding.rvTransactions.layoutManager = LinearLayoutManager(this)
            binding.rvTransactions.adapter = adapter
        }

        private fun setupListeners() {
            binding.swipeRefresh.setProgressBackgroundColorSchemeColor(android.graphics.Color.TRANSPARENT)
            binding.swipeRefresh.setColorSchemeColors(android.graphics.Color.parseColor("#81C784"))
            binding.swipeRefresh.setOnRefreshListener {
                if (!isNetworkAvailable()) {
                    // No Internet? Just stop the spinner silently.
                    binding.swipeRefresh.isRefreshing = false
                } else {
                    // Internet OK? Run the sync
                    runSync()

                    // Safety Timeout: Force stop spinner after 5 seconds
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        binding.swipeRefresh.isRefreshing = false
                    }, 5000)
                }
            }

            // 1. STANDARD CLICK -> Normal Transaction
            binding.btnSend.setOnClickListener {
                // FIX: Preserve existing nature if editing, otherwise default to NORMAL
                val targetNature = editingTransaction?.nature ?: "NORMAL"
                handleInput(nature = targetNature)
            }

            // 2. LONG CLICK -> Custom Tiny Popup (Bubble)
            binding.btnSend.setOnLongClickListener { anchor ->
                val container = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    background = ContextCompat.getDrawable(context, R.drawable.bg_message_card)
                    setPadding(0, 12, 0, 12)
                    elevation = 24f
                }

                val popup = android.widget.PopupWindow(
                    container,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    true
                )
                popup.elevation = 24f

                fun createRow(text: String, colorRes: Int, nature: String) {
                    val tv = android.widget.TextView(this).apply {
                        this.text = text
                        textSize = 14f
                        setTextColor(ContextCompat.getColor(context, colorRes))
                        setPadding(48, 20, 48, 20)
                        setOnClickListener {
                            handleInput(nature)
                            popup.dismiss()
                        }
                    }
                    container.addView(tv)
                }

                createRow("Asset", R.color.income_green, "ASSET")
                createRow("Liability", R.color.expense_red, "LIABILITY")

                container.measure(
                    android.view.View.MeasureSpec.UNSPECIFIED,
                    android.view.View.MeasureSpec.UNSPECIFIED
                )
                val popupHeight = container.measuredHeight
                val popupWidth = container.measuredWidth
                val xOff = -(popupWidth - anchor.width) / 2
                val yOff = -(anchor.height + popupHeight + 16)

                popup.showAsDropDown(anchor, xOff, yOff)

                true
            }

            binding.btnMenu.setOnClickListener {
                binding.drawerLayout.openDrawer(GravityCompat.START)
            }

            binding.cardBalance.setOnClickListener {
                showSummarySheet()
            }

            binding.btnCancelEdit.setOnClickListener {
                resetInput()
            }

            binding.btnAssetsLiabilities.setOnClickListener {
                startActivity(Intent(this, AssetsLiabilitiesActivity::class.java))
            }

            binding.navView.setNavigationItemSelectedListener { menuItem ->
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                when (menuItem.itemId) {
                    R.id.nav_faq -> {
                        startActivity(Intent(this, FaqActivity::class.java))
                        binding.drawerLayout.closeDrawer(GravityCompat.START)
                        true
                    }
                    R.id.nav_export -> showExportDialog()
                    R.id.nav_import -> importLauncher.launch("text/*")
                    R.id.nav_currency -> showCurrencySelector(isFirstLaunch = false)
                    R.id.nav_privacy -> startActivity(Intent(this, PrivacyActivity::class.java))
                    R.id.nav_about -> startActivity(Intent(this, AboutActivity::class.java))
                    R.id.nav_link_device -> startActivity(Intent(this, LinkDeviceActivity::class.java))
                    else -> return@setNavigationItemSelectedListener false
                }
                true
            }
        }

        private var lastClickTime = 0L

        private fun handleInput(nature: String) {
            // FIX: True time-based debounce to prevent rapid double-tap duplicate injections
            if (System.currentTimeMillis() - lastClickTime < 500) return
            lastClickTime = System.currentTimeMillis()

            val rawText = binding.etInput.text.toString().trim()
            val parsed = parseTransactionInput(rawText)

            if (parsed != null) {
                val (amount, desc) = parsed

                // PRESERVE THE SKELETON:
                // Save exactly what the user typed (e.g. "50 + 100 + 50") so the Edit screen can restore the math perfectly.
                val finalRawText = rawText

                binding.btnSend.isEnabled = false

                if (editingTransaction == null) {
                    // ADD NEW
                    viewModel.addTransaction(finalRawText, amount, desc, nature)
                    binding.rvTransactions.scrollToPosition(0)
                } else {
                    // UPDATE EXISTING
                    val current = editingTransaction!!
                    val updated = current.copy(
                        originalText = finalRawText,
                        amount = amount,
                        description = desc,
                        nature = nature
                    )
                    viewModel.updateTransaction(updated)

                    showCustomUndo("Log updated") {
                        viewModel.updateTransaction(current) // Restores the old version
                        runSync(force = false)
                    }
                }

                resetInput()
                // Removed force=true. Standard sync is sufficient as Repo queues the edit.
                runSync(force = false)

            } else {
                showError("Please enter an amount (e.g., '50 Snacks')")
            }
        }

        // FIX 2: Completely rewritten to handle "Amount First" logic with spaces (e.g. "50 + 50 Lunch")
        private fun parseTransactionInput(text: String): Pair<Long, String>? {
            if (text.isBlank()) return null

            // Strategy 1: "Amount First" (Math Expression at start)
            val match = Regex("^([0-9+\\-*/.\\s]+)(.*)").find(text)

            if (match != null) {
                val mathPart = match.groupValues[1].trim()
                val potentialDesc = match.groupValues[2].trim()

                if (mathPart.any { it.isDigit() }) {
                    val result = evaluateMath(mathPart) ?: mathPart.toDoubleOrNull()
                    if (result != null) {
                        val desc = if (potentialDesc.isNotEmpty()) potentialDesc.replaceFirstChar { it.uppercase() } else "General"
                        return Pair(Math.round(result * 100.0), desc)
                    }
                }
            }

            // Strategy 2: "Amount Last" (e.g. "Lunch 50") - Fallback
            val lastSpace = text.lastIndexOf(' ')
            if (lastSpace != -1) {
                val lastPart = text.substring(lastSpace + 1)
                val lastEval = evaluateMath(lastPart) ?: lastPart.toDoubleOrNull()

                if (lastEval != null) {
                    val descPart = text.substring(0, lastSpace).trim()
                    val desc = if (descPart.isNotEmpty()) descPart.replaceFirstChar { it.uppercase() } else "General"
                    return Pair(Math.round(lastEval * 100.0), desc)
                }
            }
            return null
        }

        private fun runSync(force: Boolean = false) {
            val workId = viewModel.scheduleSync(force)
            WorkManager.getInstance(this).getWorkInfoByIdLiveData(workId).observe(this) { workInfo ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            val msg = workInfo.outputData.getString("MSG") ?: "Sync Complete"
                            if (msg.contains("Synced") || msg.contains("Error")) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                            if (msg.contains("Synced")) viewModel.refreshData()
                            binding.swipeRefresh.isRefreshing = false
                        }
                        WorkInfo.State.FAILED -> {
                            val msg = workInfo.outputData.getString("MSG") ?: "Sync Failed"
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                            binding.swipeRefresh.isRefreshing = false
                        }
                        WorkInfo.State.RUNNING -> binding.swipeRefresh.isRefreshing = true
                        else -> { }
                    }
                }
            }
        }

        private fun deleteTransaction(transaction: Transaction) {
            // Delete instantly
            viewModel.deleteTransaction(transaction)
            runSync(force = false)

            // Show Undo popup
            showCustomUndo("Log deleted") {
                viewModel.restoreTransaction(transaction)
                runSync(force = false)
            }
        }

        private fun parseAndImportCsv(uri: Uri) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val importList = ArrayList<Transaction>()
                    reader.readLine() // Header

                    var line = reader.readLine()

                    // FIX 3: Support multiple date formats & prevent "Current Time" corruption
                    val dateFormats = listOf(
                        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()),
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()),
                        SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()),
                        SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault()),
                        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                    )

                    val usedTimestamps = HashSet<Long>()
                    var skippedCount = 0

                    while (line != null) {
                        // FIX: Safe linear-time manual CSV parser to prevent Regex Denial of Service (ReDoS)
                        val tokens = ArrayList<String>()
                        val currentToken = java.lang.StringBuilder()
                        var inQuotes = false
                        var i = 0
                        while (i < line.length) {
                            val c = line[i]
                            if (c == '"') {
                                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                                    currentToken.append('"') // Handle escaped quote ""
                                    i++
                                } else {
                                    inQuotes = !inQuotes
                                }
                            } else if (c == ',' && !inQuotes) {
                                tokens.add(currentToken.toString().trim())
                                currentToken.clear()
                            } else {
                                currentToken.append(c)
                            }
                            i++
                        }
                        tokens.add(currentToken.toString().trim())

                        if (tokens.size >= 4) {
                            val dateStr = "${tokens[0]} ${tokens[1]}"
                            val amount = tokens[2].toDoubleOrNull() ?: 0.0
                            val desc = tokens[3]
                            var timestamp: Long = 0L

                            // 1. Try explicit timestamp column first
                            if (tokens.size >= 7) {
                                timestamp = tokens[6].toLongOrNull() ?: 0L
                            } else if (tokens.size >= 5) {
                                // Fallback for older CSV versions
                                timestamp = tokens[4].toLongOrNull() ?: 0L
                            }

                            // 2. If no timestamp, try parsing the date string
                            if (timestamp == 0L) {
                                for (fmt in dateFormats) {
                                    try {
                                        // Strict parsing to ensure accuracy
                                        // fmt.isLenient = false // Optional: uncomment if strictness is required
                                        val date = fmt.parse(dateStr)
                                        if (date != null) {
                                            timestamp = date.time
                                            break
                                        }
                                    } catch (e: Exception) { }
                                }
                            }

                            // FIX 3 (Critical): If date parsing failed, SKIP.
                            // Do NOT default to System.currentTimeMillis(), as that corrupts history.
                            if (timestamp == 0L) {
                                skippedCount++
                            } else {
                                // Prevent collision
                                while (usedTimestamps.contains(timestamp)) timestamp += 1
                                usedTimestamps.add(timestamp)

                                val nature = if (tokens.size >= 5) tokens[4] else "NORMAL"
                                val obligation = if (tokens.size >= 6) tokens[5].toDoubleOrNull() ?: 0.0 else 0.0
                                val fmtAmount = if (amount % 1.0 == 0.0) amount.toLong().toString() else amount.toString()

                                importList.add(Transaction(
                                    originalText = "$fmtAmount $desc",
                                    amount = Math.round(amount * 100.0),
                                    description = desc,
                                    timestamp = timestamp,
                                    nature = nature,
                                    obligationAmount = Math.round(obligation * 100.0)
                                ))
                            }
                        }
                        line = reader.readLine()
                    }

                    withContext(Dispatchers.Main) {
                        val existingTransactions = viewModel.transactions.value ?: emptyList()
                        val skippedMsg = if (skippedCount > 0) " Skipped $skippedCount invalid dates." else ""

                        if (existingTransactions.isEmpty()) {
                            viewModel.importTransactionList(importList)
                            showError("Imported ${importList.size} items.$skippedMsg")
                        } else {
                            MaterialAlertDialogBuilder(this@MainActivity)
                                .setTitle("Import Data")
                                .setMessage("You already have logs in JotPay.\n" +
                                        "Do you want to add the new logs or replace the existing ones?\n")
                                .setPositiveButton("Merge") { _, _ ->
                                    viewModel.importTransactionList(importList)
                                    showError("Added ${importList.size} items.$skippedMsg")
                                }
                                .setNeutralButton("Overwrite") { _, _ ->
                                    viewModel.clearAllTransactions {
                                        viewModel.importTransactionList(importList)
                                        showError("Cleared existing and imported ${importList.size} items.$skippedMsg")
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { showError("Import Failed: ${e.message}") }
                }
            }
        }
        private fun setupInputLogic() {
            setupSearch()
            setupInputPreview()
            setupSignToggles()
            setupKeyboardSwitching()
        }

        private fun setupSearch() {
            binding.btnSearch.setOnClickListener {
                if (binding.etSearch.visibility != View.VISIBLE) {
                    binding.etSearch.visibility = View.VISIBLE
                    binding.btnCloseSearch.visibility = View.VISIBLE
                    binding.etSearch.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            binding.btnCloseSearch.setOnClickListener { closeSearchBar() }
            binding.etSearch.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val query = s.toString().trim()
                    // FIX: Always route through search to maintain active query state sync
                    viewModel.search(query)
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }

        private fun setupSignToggles() {
            binding.btnPlus.setOnClickListener { insertSign("+") }
            binding.btnMinus.setOnClickListener { insertSign("-") }
            binding.btnSpace.setOnClickListener { insertSign(" ") }
            binding.etInput.setOnFocusChangeListener { _, _ -> updateSignToggleVisibility() }
            updateSignToggleVisibility()
        }

        private fun insertSign(sign: String) {
            binding.etInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.etInput, InputMethodManager.SHOW_IMPLICIT)
            val start = binding.etInput.selectionStart.coerceAtLeast(0)
            binding.etInput.text.insert(start, sign)
        }

        private fun updateSignToggleVisibility() {
            val text = binding.etInput.text.toString()
            val isNumericMode = (text.startsWith("+") || text.startsWith("-") || text.firstOrNull()?.isDigit() == true) && !text.contains(" ")
            val shouldShowBar = text.isEmpty() || isNumericMode

            if (shouldShowBar) {
                if (binding.layoutSignToggles.visibility != View.VISIBLE) {
                    binding.layoutSignToggles.alpha = 0f
                    binding.layoutSignToggles.visibility = View.VISIBLE
                    binding.layoutSignToggles.animate().alpha(1f).setDuration(150).withEndAction(null).start()
                } else {
                    binding.layoutSignToggles.alpha = 1f
                }

                if (text.isEmpty()) {
                    binding.btnPlus.visibility = View.VISIBLE
                    binding.btnMinus.visibility = View.VISIBLE
                    binding.btnSpace.visibility = View.GONE
                } else {
                    binding.btnPlus.visibility = View.VISIBLE
                    binding.btnMinus.visibility = View.VISIBLE
                    binding.btnSpace.visibility = View.VISIBLE
                }
            } else {
                if (binding.layoutSignToggles.visibility == View.VISIBLE && binding.layoutSignToggles.alpha == 1f) {
                    binding.layoutSignToggles.animate().alpha(0f).setDuration(150).withEndAction {
                        binding.layoutSignToggles.visibility = View.GONE
                    }.start()
                }
            }
        }

        private fun setupKeyboardSwitching() {
            binding.etInput.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val text = s.toString()
                    val isTransactionStart = text.startsWith("+") || text.startsWith("-")
                    val hasSpace = text.contains(" ")
                    val typeText = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
// Use TYPE_CLASS_PHONE to allow digits, dots, and math operators while forcing numeric pad
                    val typeNumeric = InputType.TYPE_CLASS_PHONE
                    val targetType = if (isTransactionStart && !hasSpace) typeNumeric else typeText

                    if (binding.etInput.inputType != targetType) {
                        val selStart = binding.etInput.selectionStart
                        val selEnd = binding.etInput.selectionEnd
                        binding.etInput.inputType = targetType
                        if (selStart >= 0 && selEnd >= 0) binding.etInput.setSelection(selStart, selEnd)
                    }

                    if (text.isEmpty()) binding.etInput.hint = "Try '+500 Dividends'"
                    else if (isTransactionStart && !hasSpace) binding.etInput.hint = "Amount (supports + - * /)"
                    else binding.etInput.hint = "Description"
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }

        private fun updateAutocomplete(list: List<Transaction>) {
            // FIX: Offload heavy grouping, formatting, and sorting to background thread
            lifecycleScope.launch(Dispatchers.Default) {
                val frequencyMap = HashMap<String, HashMap<String, Int>>()
                val formatter = java.text.NumberFormat.getInstance(Locale.getDefault()).apply { minimumFractionDigits = 0; maximumFractionDigits = 2 }
                for (t in list) {
                    val amountStr = formatter.format(t.amount / 100.0)
                    val map = frequencyMap.getOrDefault(amountStr, HashMap())
                    val count = map.getOrDefault(t.description, 0)
                    map[t.description] = count + 1
                    frequencyMap[amountStr] = map
                }
                val suggestions = ArrayList<String>()
                for ((amount, descMap) in frequencyMap) {
                    val topDesc = descMap.maxByOrNull { it.value }
                    if (topDesc != null && topDesc.value >= 2) suggestions.add("$amount ${topDesc.key}")
                }

                withContext(Dispatchers.Main) {
                    val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, suggestions)
                    binding.etInput.setAdapter(adapter)
                }
            }
        }

        private fun evaluateMath(expression: String): Double? {
            try {
                // FIX 2: Added \\s to Regex to allow whitespace in math expressions
                if (!expression.matches(Regex("[-+*/.0-9\\s]+"))) return null
                val tokens = ArrayList<String>()
                var buffer = StringBuilder()
                for (char in expression) {
                    // FIX 2: Skip whitespace in tokenization logic
                    if (char.isWhitespace()) continue

                    if (char in listOf('+', '-', '*', '/')) {
                        if (buffer.isNotEmpty()) tokens.add(buffer.toString())
                        tokens.add(char.toString())
                        buffer.clear()
                    } else buffer.append(char)
                }
                if (buffer.isNotEmpty()) tokens.add(buffer.toString())

                var i = 0
                while (i < tokens.size) {
                    val token = tokens[i]
                    if (token == "+" || token == "-") {
                        val isUnary = (i == 0) || (tokens[i - 1] in listOf("+", "-", "*", "/"))
                        if (isUnary && i + 1 < tokens.size) {
                            tokens[i] = token + tokens[i + 1]
                            tokens.removeAt(i + 1)
                        }
                    }
                    i++
                }
                i = 0
                while (i < tokens.size) {
                    if (tokens[i] == "*" || tokens[i] == "/") {
                        if (i == 0 || i + 1 >= tokens.size) return null
                        val op = tokens[i]
                        val prev = tokens[i - 1].toDouble()
                        val next = tokens[i + 1].toDouble()

                        if (op == "/" && next == 0.0) return null

                        val res = if (op == "*") prev * next else prev / next
                        tokens[i - 1] = res.toString()
                        tokens.removeAt(i); tokens.removeAt(i)
                        i--
                    }
                    i++
                }
                if (tokens.isEmpty()) return null
                var result = tokens[0].toDouble()
                i = 1
                while (i < tokens.size) {
                    val op = tokens[i]
                    if (i + 1 >= tokens.size) return null
                    val next = tokens[i + 1].toDouble()
                    if (op == "+") result += next
                    if (op == "-") result -= next
                    i += 2
                }
                return result
            } catch (e: Exception) { return null }
        }

        private fun setupInputPreview() {
            binding.etInput.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    updateSignToggleVisibility()
                    val text = s.toString().trim()
                    if (text.isEmpty()) {
                        binding.tvInputPreview.visibility = View.GONE
                        return
                    }

                    // FIX 2: Use the new parseTransactionInput logic for preview consistency
                    val parsed = parseTransactionInput(text)

                    if (parsed != null) {
                        val (amountCents, desc) = parsed
                        val amount = amountCents / 100.0
                        val type = if (amount >= 0) "Credit" else "Debit"
                        val absAmount = abs(amount)
                        val cleanDesc = if(desc.isBlank()) "..." else desc
                        val symbol = CurrencyHelper.getSymbol(this@MainActivity)
                        val formatter = java.text.NumberFormat.getInstance(Locale.getDefault()).apply { minimumFractionDigits = 0; maximumFractionDigits = 2 }
                        binding.tvInputPreview.text = "$type $symbol${formatter.format(absAmount)} · $cleanDesc"
                        binding.tvInputPreview.visibility = View.VISIBLE
                    } else binding.tvInputPreview.visibility = View.GONE
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
            binding.etInput.setOnClickListener { updateSignToggleVisibility() }
        }

        // Bypass the confirmation dialog entirely for a faster UX
        private fun showActionDialog(transaction: Transaction) {
            val options = arrayOf("Edit", "Delete")
            MaterialAlertDialogBuilder(this)
                .setTitle("Choose Action")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> startEditing(transaction)
                        1 -> deleteTransaction(transaction)
                    }
                }
                .show()
        }

        private fun startEditing(transaction: Transaction) {
            editingTransaction = transaction
            binding.etInput.setText(transaction.originalText)
            binding.etInput.setSelection(transaction.originalText.length)
            binding.btnSend.background.mutate().setTint(android.graphics.Color.parseColor("#FF9800"))
            binding.btnCancelEdit.visibility = View.VISIBLE
            binding.etInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.etInput, InputMethodManager.SHOW_IMPLICIT)
        }

        private fun resetInput() {
            binding.etInput.text.clear()
            binding.btnSend.isEnabled = true
            binding.btnSend.background.mutate().setTintList(null)
            editingTransaction = null
            binding.btnCancelEdit.visibility = View.GONE
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.etInput.windowToken, 0)
        }

        private fun showError(msg: String) {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        private fun showSummarySheet() {
            val sheet = PerformanceSummarySheet()
            sheet.show(supportFragmentManager, "PerformanceSheet")
        }

        private fun showExportDialog() {
            MaterialAlertDialogBuilder(this).setTitle("Export Data").setItems(arrayOf("CSV (Excel)", "Text File")) { _, which -> exportData(which == 0) }.show()
        }

        private fun exportData(isCsv: Boolean) {
            val transactions = viewModel.transactions.value ?: emptyList()
            if (transactions.isEmpty()) { showError("No data to export"); return }
            lifecycleScope.launch(Dispatchers.IO) {
                val sb = StringBuilder()
                val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                val symbol = CurrencyHelper.getSymbol(applicationContext)
                fun fmt(d: Long): String {
                    val v = d / 100.0
                    return if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
                }

                if (isCsv) {
                    sb.append("Date,Time,Amount,Description,Nature,Obligation,Timestamp\n")
                    for (t in transactions) {
                        val date = Date(t.timestamp)
                        val safeDesc = t.description.replace("\"", "\"\"")
                        sb.append("${dateFormat.format(date)},${timeFormat.format(date)},${fmt(t.amount)},\"$safeDesc\",${t.nature},${fmt(t.obligationAmount)},${t.timestamp}\n")
                    }
                } else {
                    sb.append("JotPay REPORT\nCurrency: $symbol\n=================\n")
                    for (t in transactions) {
                        val date = Date(t.timestamp)
                        val natureTag = if (t.nature != "NORMAL") " [${t.nature}]" else ""
                        sb.append("[${dateFormat.format(date)}] ${fmt(t.amount)} ${t.description}$natureTag\n")
                    }
                }
                try {
                    val filename = if (isCsv) "JotPay_Backup.csv" else "JotPay_Backup.txt"
                    val file = File(cacheDir, filename)
                    file.writeText(sb.toString())
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.provider", file)
                    val intent = Intent(Intent.ACTION_SEND)
                    intent.type = if (isCsv) "text/csv" else "text/plain"
                    intent.putExtra(Intent.EXTRA_STREAM, uri)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    withContext(Dispatchers.Main) { startActivity(Intent.createChooser(intent, "Share Export")) }
                    getSharedPreferences("moneylog_prefs", Context.MODE_PRIVATE).edit().putLong("last_backup_timestamp", System.currentTimeMillis()).apply()
                } catch (e: Exception) { withContext(Dispatchers.Main) { showError("Export Failed: ${e.message}") } }
            }
        }

        private fun checkFirstLaunchFlow() {
            val prefs = getSharedPreferences("moneylog_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("policy_accepted", false)) showPrivacyWelcomeDialog() else checkCurrencySetup()
        }

        private fun showPrivacyWelcomeDialog() {
            isOnboardingShowing = true
            MaterialAlertDialogBuilder(this).setTitle("Welcome to JotPay").setMessage("Before you start tracking your finances, please accept our terms.\n\n• Your data is encrypted and stored locally.\n• Cloud Sync is optional and end-to-end encrypted.\n• We do not track you or sell your data.").setCancelable(false)
                .setPositiveButton("Accept & Continue") { _, _ ->
                    isOnboardingShowing = false
                    getSharedPreferences("moneylog_prefs", Context.MODE_PRIVATE).edit().putBoolean("policy_accepted", true).apply()
                    checkCurrencySetup()
                }
                .setNegativeButton("Read Full Policy") { _, _ -> startActivity(Intent(this, PrivacyActivity::class.java)) }.show()
        }

        private fun checkCurrencySetup() {
            if (!CurrencyHelper.isCurrencySet(this)) showCurrencySelector(isFirstLaunch = true) else { viewModel.refreshData(); checkMonthlyCheckpoint() }
        }

        private fun checkMonthlyCheckpoint() {
            val prefs = getSharedPreferences("moneylog_prefs", Context.MODE_PRIVATE)
            val lastSeen = prefs.getString("last_month_checkpoint", "")
            val cal = Calendar.getInstance()
            val currentMonthKey = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(cal.time)

            if (lastSeen != currentMonthKey) {
                cal.add(Calendar.MONTH, -1); cal.set(Calendar.DAY_OF_MONTH, 1); cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                val start = cal.timeInMillis
                cal.add(Calendar.MONTH, 1); cal.set(Calendar.DAY_OF_MONTH, 1)
                val end = cal.timeInMillis

                // FIX: Remove redundant DB creation. Defer execution to prevent cold-start blockage.
                lifecycleScope.launch(Dispatchers.IO) {
                    // Using the existing ViewModel/Repository prevents the overhead of building a second DB instance
                    val list = viewModel.transactions.value ?: return@launch
                    val prevMonthList = list.filter { it.timestamp in start until end }

                    if (prevMonthList.isNotEmpty()) {
                        val sumCents = prevMonthList.sumOf { it.amount }
                        val prevMonthName = SimpleDateFormat("MMMM", Locale.getDefault()).format(Date(start))
                        withContext(Dispatchers.Main) {
                            val symbol = CurrencyHelper.getSymbol(this@MainActivity)
                            val formatter = java.text.NumberFormat.getInstance(Locale.getDefault()).apply {
                                minimumFractionDigits = 0
                                maximumFractionDigits = 2
                            }
                            val displaySum = sumCents / 100.0
                            binding.tvMonthlySummary.text = "Last month ($prevMonthName): $symbol ${formatter.format(displaySum)}"
                            binding.tvMonthlySummary.visibility = View.VISIBLE
                            prefs.edit().putString("last_month_checkpoint", currentMonthKey).apply()
                        }
                    }
                }
            }
        }

        private fun showCurrencySelector(isFirstLaunch: Boolean) {
            isOnboardingShowing = true
            val currencies = CurrencyHelper.CURRENCIES
            MaterialAlertDialogBuilder(this).setTitle("Select Currency").setCancelable(!isFirstLaunch)
                .setItems(currencies) { _, which ->
                    isOnboardingShowing = false
                    CurrencyHelper.setCurrency(this, currencies[which])
                    viewModel.refreshData()
                    if(isFirstLaunch) checkMonthlyCheckpoint()
                }.show()
        }

        class DoubleEvaluator : android.animation.TypeEvaluator<Double> {
            override fun evaluate(fraction: Float, startValue: Double, endValue: Double): Double {
                return startValue + (endValue - startValue) * fraction.toDouble()
            }
        }

        private fun checkBackupReminder() {
            val prefs = getSharedPreferences("moneylog_prefs", Context.MODE_PRIVATE)

            // 1. Check if user permanently disabled it
            if (prefs.getBoolean("disable_backup_reminder", false)) return

            // 2. Initialize timestamp to 'now' if it's the first time so we don't nag on day 1
            val lastReminded = prefs.getLong("last_backup_timestamp", -1L)
            if (lastReminded == -1L) {
                prefs.edit().putLong("last_backup_timestamp", System.currentTimeMillis()).apply()
                return
            }

            val now = System.currentTimeMillis()
            val sevenDays = 604800000L

            if (now - lastReminded > sevenDays) {
                // 3. Only remind if they actually have transactions to back up
                val transactions = viewModel.transactions.value ?: emptyList()
                if (transactions.isEmpty()) return

                MaterialAlertDialogBuilder(this)
                    .setTitle("Backup Reminder")
                    .setMessage("It's been a while since your last backup. To prevent data loss, we recommend exporting your data.")
                    .setPositiveButton("Export Now") { _, _ -> showExportDialog() }
                    .setNeutralButton("Never Remind Me") { _, _ ->
                        prefs.edit().putBoolean("disable_backup_reminder", true).apply()
                    }
                    .setNegativeButton("Remind Later") { _, _ ->
                        prefs.edit().putLong("last_backup_timestamp", now).apply()
                    }
                    .show()
            }
        }

        // --- NEW: ONE-TIME FEATURE DISCOVERY HINT ---
        private fun checkFeatureDiscovery() {
            val prefs = getSharedPreferences("moneylog_prefs", Context.MODE_PRIVATE)
            val hasSeenHint = prefs.getBoolean("seen_long_press_hint", false)

            if (!hasSeenHint) {
                // Delay slightly to ensure UI is ready and user is looking
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        showDiscoveryBubble()
                        prefs.edit().putBoolean("seen_long_press_hint", true).apply()
                    } catch (e: Exception) { e.printStackTrace() }
                }, 1000)
            }
        }

        private fun showDiscoveryBubble() {
            // Reuse your Bubble styling
            val context = this
            val container = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_message_card)
                setPadding(32, 24, 32, 24)
                elevation = 24f
            }

            val text = TextView(context).apply {
                text = "Tip: Long-press Send for Assets & Liabilities"
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }
            container.addView(text)

            val popup = android.widget.PopupWindow(
                container,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true // Focusable so clicking outside dismisses it
            )
            popup.elevation = 24f

            // Show it anchored to the Send button
            // Calculate offset to show ABOVE the button
            container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val popupHeight = container.measuredHeight
            val popupWidth = container.measuredWidth
            val anchor = binding.btnSend
            val xOff = -(popupWidth - anchor.width) / 2
            val yOff = -(anchor.height + popupHeight + 16)

            popup.showAsDropDown(anchor, xOff, yOff)

            // Auto-dismiss after 5 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                if (popup.isShowing) popup.dismiss()
            }, 5000)
        }

        private fun isNetworkAvailable(): Boolean {
            val connectivityManager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        private fun checkForUpdates() {
            val appUpdateInfoTask = appUpdateManager.appUpdateInfo
            appUpdateInfoTask.addOnSuccessListener { info ->
                if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                ) {
                    // This shows the official Play Store "Update Available" dialog
                    // but with a "Download in background" option
                    appUpdateManager.startUpdateFlowForResult(info, AppUpdateType.FLEXIBLE, this, 9001)
                }
            }
        }

        private fun showUpdateFinishedSnackbar() {
            Snackbar.make(
                binding.root,
                "Update downloaded. Restart to install.",
                Snackbar.LENGTH_INDEFINITE
            ).apply {
                setAction("RESTART") { appUpdateManager.completeUpdate() }
                setActionTextColor(ContextCompat.getColor(context, R.color.income_green))
                show()
            }
        }

        override fun onDestroy() {
            // Always unregister listeners to prevent memory leaks
            appUpdateManager.unregisterListener(updateListener)

            // FIX: Cancel pending UI callbacks to prevent Activity memory leaks and crashes
            undoRunnable?.let { undoHandler.removeCallbacks(it) }
            undoHandler.removeCallbacksAndMessages(null)

            super.onDestroy()
        }
        private fun showCustomUndo(message: String, onUndo: () -> Unit) {
            binding.tvUndoMessage.text = message
            binding.layoutUndoAction.visibility = View.VISIBLE

            // Premium float-up and fade-in animation
            binding.layoutUndoAction.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .start()

            // Handle Undo Click
            binding.btnUndoAction.setOnClickListener {
                onUndo()
                hideCustomUndo()
                undoRunnable?.let { undoHandler.removeCallbacks(it) }
            }

            // Reset the 4-second timer
            undoRunnable?.let { undoHandler.removeCallbacks(it) }
            undoRunnable = Runnable { hideCustomUndo() }
            undoHandler.postDelayed(undoRunnable!!, 4000)
        }

        private fun hideCustomUndo() {
            // Float down and fade out
            binding.layoutUndoAction.animate()
                .alpha(0f)
                .translationY(20f) // Sinks slightly as it fades
                .setDuration(250)
                .withEndAction {
                    binding.layoutUndoAction.visibility = View.GONE
                }.start()
        }

    }
