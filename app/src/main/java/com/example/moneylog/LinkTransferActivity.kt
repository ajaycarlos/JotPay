package com.example.moneylog

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.moneylog.databinding.ActivityLinkTransferBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID

class LinkTransferActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLinkTransferBinding

    private var workId: UUID? = null
    private var currentState: WorkInfo.State? = null

    companion object {
        fun start(
            activity: AppCompatActivity,
            transferMode: String,
            vaultId: String,
            secretKey: String,
            workId: UUID
        ) {
            val intent = Intent(activity, LinkTransferActivity::class.java).apply {
                putExtra("TRANSFER_MODE", transferMode)
                putExtra("VAULT_ID", vaultId)
                putExtra("SECRET_KEY", secretKey)
                putExtra("WORK_ID", workId.toString())
            }
            activity.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLinkTransferBinding.inflate(layoutInflater)
        setContentView(binding.root)

        EdgeToEdgeCompat.apply(window, binding.root)

        // Resolve the work UUID — prefer savedInstanceState (survives rotation),
        // then fall back to the intent extra.
        val savedWorkIdStr = savedInstanceState?.getString("work_id")
        val intentWorkIdStr = intent.getStringExtra("WORK_ID")
        val resolvedWorkIdStr = savedWorkIdStr ?: intentWorkIdStr

        if (resolvedWorkIdStr != null) {
            workId = UUID.fromString(resolvedWorkIdStr)
        } else {
            // Fallback: enqueue a new worker using intent extras (should not normally happen,
            // since the caller is expected to pass WORK_ID; but guard anyway).
            val transferMode = intent.getStringExtra("TRANSFER_MODE") ?: "OVERWRITE"
            val vaultId = intent.getStringExtra("VAULT_ID") ?: ""
            val secretKey = intent.getStringExtra("SECRET_KEY") ?: ""
            workId = LinkTransferWorker.enqueueWork(this, transferMode, vaultId, secretKey)
        }

        observeWork(workId!!)

        binding.btnBack.setOnClickListener { handleCancelOrBack() }
        binding.btnCancel.setOnClickListener { handleCancelOrBack() }
        binding.btnDone.setOnClickListener { finish() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleCancelOrBack()
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        workId?.let { outState.putString("work_id", it.toString()) }
    }

    private fun observeWork(id: UUID) {
        WorkManager.getInstance(this)
            .getWorkInfoByIdLiveData(id)
            .observe(this) { workInfo ->
                if (workInfo == null) return@observe
                currentState = workInfo.state
                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> handleRunning(workInfo)
                    WorkInfo.State.SUCCEEDED -> handleSucceeded(workInfo)
                    WorkInfo.State.FAILED -> handleFailed(workInfo)
                    WorkInfo.State.CANCELLED -> handleCancelled()
                    else -> { /* ENQUEUED / BLOCKED — keep initial UI */ }
                }
            }
    }

    // ── State handlers ────────────────────────────────────────────────────────

    private fun handleRunning(workInfo: WorkInfo) {
        val stage = workInfo.progress.getString(LinkTransferWorker.KEY_STAGE) ?: ""
        val percent = workInfo.progress.getInt(LinkTransferWorker.KEY_PERCENT, -1)
        val processed = workInfo.progress.getInt(LinkTransferWorker.KEY_PROCESSED, 0)
        val total = workInfo.progress.getInt(LinkTransferWorker.KEY_TOTAL, -1)

        // Progress bar
        if (percent == -1) {
            binding.progressBar.isIndeterminate = true
        } else {
            binding.progressBar.isIndeterminate = false
            binding.progressBar.progress = percent
        }

        // Circular spinner always visible while running
        binding.progressCircle.visibility = View.VISIBLE

        binding.tvStage.text = if (stage.endsWith("...")) stage else "$stage..."
        binding.tvProgressDetail.text = if (total > 0) "$processed / $total records" else ""
        binding.tvStatusDetail.visibility = View.GONE

        binding.btnCancel.visibility = View.VISIBLE
        binding.btnCancel.text = "Cancel"
        binding.btnDone.visibility = View.GONE
    }

    private fun handleSucceeded(workInfo: WorkInfo) {
        val msg = workInfo.outputData.getString(LinkTransferWorker.KEY_MSG) ?: "Transfer complete"
        val count = workInfo.outputData.getInt(LinkTransferWorker.KEY_COUNT, 0)
        val downloaded = workInfo.outputData.getInt(LinkTransferWorker.KEY_DOWNLOADED, count)
        val validated = workInfo.outputData.getInt(LinkTransferWorker.KEY_VALIDATED, count)
        val skipped = workInfo.outputData.getInt(LinkTransferWorker.KEY_SKIPPED, 0)

        binding.progressBar.isIndeterminate = false
        binding.progressBar.progress = 100
        binding.progressCircle.visibility = View.GONE

        binding.tvStage.text = "Transfer Complete"
        binding.tvProgressDetail.text = ""

        val detail = buildString {
            append(msg)
            if (downloaded > 0 || validated > 0) {
                append("\nDownloaded: $downloaded · Validated: $validated · Imported: $count")
                if (skipped > 0) {
                    append(" · Skipped: $skipped")
                }
            }
        }
        binding.tvStatusDetail.text = detail
        binding.tvStatusDetail.visibility = View.VISIBLE

        binding.btnCancel.visibility = View.GONE
        binding.btnDone.visibility = View.VISIBLE
    }

    private fun handleFailed(workInfo: WorkInfo) {
        val msg = workInfo.outputData.getString(LinkTransferWorker.KEY_MSG) ?: "An error occurred"
        val downloaded = workInfo.outputData.getInt(LinkTransferWorker.KEY_DOWNLOADED, 0)
        val validated = workInfo.outputData.getInt(LinkTransferWorker.KEY_VALIDATED, 0)
        val skipped = workInfo.outputData.getInt(LinkTransferWorker.KEY_SKIPPED, 0)

        binding.progressBar.isIndeterminate = false
        binding.progressBar.progress = 0
        binding.progressCircle.visibility = View.GONE

        binding.tvStage.text = "Transfer Failed"
        binding.tvProgressDetail.text = ""

        val detail = if (downloaded > 0) {
            "$msg\nDownloaded: $downloaded · Validated: $validated" + (if (skipped > 0) " · Skipped: $skipped" else "")
        } else {
            msg
        }
        binding.tvStatusDetail.text = detail
        binding.tvStatusDetail.visibility = View.VISIBLE

        binding.btnCancel.visibility = View.VISIBLE
        binding.btnCancel.text = "Close"
        binding.btnDone.visibility = View.GONE
    }

    private fun handleCancelled() {
        binding.progressCircle.visibility = View.GONE

        binding.tvStage.text = "Transfer Cancelled"
        binding.tvProgressDetail.text = ""
        binding.tvStatusDetail.text = "No changes were made."
        binding.tvStatusDetail.visibility = View.VISIBLE

        binding.btnCancel.visibility = View.VISIBLE
        binding.btnCancel.text = "Close"
        binding.btnDone.visibility = View.GONE
    }

    // ── Back / Cancel logic ───────────────────────────────────────────────────

    private fun handleCancelOrBack() {
        val state = currentState
        if (state == null || state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED) {
            // Work is still in progress — ask for confirmation
            MaterialAlertDialogBuilder(this)
                .setTitle("Cancel transfer?")
                .setMessage("The transfer is still in progress. Are you sure you want to cancel?")
                .setPositiveButton("Yes, cancel") { _, _ ->
                    WorkManager.getInstance(this).cancelUniqueWork("JotPayLinkTransfer")
                    finish()
                }
                .setNegativeButton("Keep going", null)
                .show()
        } else {
            // Work is finished (SUCCEEDED / FAILED / CANCELLED) — just close
            finish()
        }
    }
}
