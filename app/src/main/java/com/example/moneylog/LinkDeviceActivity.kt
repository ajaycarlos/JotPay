package com.example.moneylog

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import androidx.work.WorkManager
import com.example.moneylog.databinding.ActivityLinkDeviceBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

class LinkDeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLinkDeviceBinding
    private lateinit var syncManager: SyncManager
    private var deviceListener: ValueEventListener? = null
    private var currentVaultId: String? = null
    private var currentDeviceCount: Long = 1
    private var localRecordCount: Int = 0
    private var isVaultSynced: Boolean = false

    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLinkDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        EdgeToEdgeCompat.apply(window, binding.root)

        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "moneylog-db").build()
        syncManager = SyncManager(this, db) //SyncManager

        binding.btnBack.setOnClickListener {
            finish() //close and go back
        }

        val prefs = getSharedPreferences("jotpay_sync", Context.MODE_PRIVATE)
        currentVaultId = prefs.getString("vault_id", null)
        var secretKey = prefs.getString("secret_key", null)

        // Ensure we always have a vault (auto-generate if missing)
        if (currentVaultId == null) {
            currentVaultId = UUID.randomUUID().toString()
            secretKey = UUID.randomUUID().toString().substring(0, 16)
            prefs.edit()
                .putString("vault_id", currentVaultId)
                .putString("secret_key", secretKey)
                .apply()
        }

        // Show QR
        displayQrCode(currentVaultId!!, secretKey!!)

        // Register this device on the server so others can count it
        registerDeviceOnServer(currentVaultId!!)

        // Listen for device count
        listenForDeviceCount(currentVaultId!!)

        // CRITICAL FIX: Ensure all local records on this device are uploaded to the vault
        // so the other device has data to download immediately upon scanning the QR code
        syncLocalRecordsToVault(currentVaultId!!, secretKey!!)

        // Handle Scan Button
        binding.btnScan.setOnClickListener {
            val integrator = IntentIntegrator(this)
            integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            integrator.setPrompt("Scan QR code from the other device")
            integrator.setCameraId(0)
            integrator.setBeepEnabled(true)
            // FIX: Set to false so the scanner dynamically respects the phone's auto-rotate and current orientation
            integrator.setOrientationLocked(false)
            integrator.initiateScan()
        }

        // Handle Unlink Button
        binding.btnUnlink.setOnClickListener {
            // SECURITY UPDATE: Confirmation Dialog
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Unlink Device?")
                .setMessage("This will disconnect this phone from the sync vault.\n\nWARNING: If this is your only device, you will lose access to your encrypted cloud data permanently.")
                .setPositiveButton("Unlink") { _, _ ->
                    unlinkThisDevice()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // FIX: Silent Auth Wrapper to ensure operations only execute after authentication
    private fun ensureAuth(onSuccess: () -> Unit) {
        val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            onSuccess()
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { onSuccess() }
                .addOnFailureListener {
                    Toast.makeText(this, "Network Error: Cannot connect to Sync Server", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun registerDeviceOnServer(vaultId: String) {
        if (!isOnline()) return
        val installId = syncManager.getInstallationId()
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

        ensureAuth {
            // Write: vaults/{id}/devices/{installId} = "Pixel 7"
            FirebaseDatabase.getInstance().getReference("vaults")
                .child(vaultId)
                .child("devices")
                .child(installId)
                .setValue(deviceName)
        }
    }

    private fun updateSyncStatusUI() {
        if (isDestroyed || isFinishing) return
        val deviceLabel = if (currentDeviceCount > 1) "$currentDeviceCount Devices Linked" else "1 Device (This Phone)"
        val syncLabel = if (isVaultSynced) {
            if (localRecordCount > 0) "Vault ready ($localRecordCount records)" else "Vault ready"
        } else {
            "Syncing records to vault..."
        }
        binding.tvLinkedCount.text = "$deviceLabel • $syncLabel"
    }

    private fun listenForDeviceCount(vaultId: String) {
        ensureAuth {
            val ref = FirebaseDatabase.getInstance().getReference("vaults")
                .child(vaultId)
                .child("devices")

            deviceListener = ref.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    currentDeviceCount = snapshot.childrenCount

                    // Update UI based on count
                    if (currentDeviceCount > 1) {
                        binding.btnUnlink.visibility = View.VISIBLE
                        binding.btnUnlink.text = "Unlink This Device"
                    } else {
                        binding.btnUnlink.visibility = View.VISIBLE
                        binding.btnUnlink.text = "Reset Sync ID"
                    }
                    updateSyncStatusUI()
                }
                override fun onCancelled(error: DatabaseError) {}
            })
        }
    }

    private fun syncLocalRecordsToVault(vaultId: String, key: String) {
        if (!isOnline()) {
            binding.tvLinkedCount.text = "Offline • Connect to internet to sync vault"
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val localRecords = db.transactionDao().getAll()
            localRecordCount = localRecords.size

            withContext(Dispatchers.Main) {
                isVaultSynced = false
                updateSyncStatusUI()
            }

            if (localRecords.isEmpty()) {
                ensureAuth {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val ref = FirebaseDatabase.getInstance().getReference("vaults").child(vaultId)
                            ref.child("meta").child("sourceRecordCount").setValue(0).await()
                            ref.child("meta").child("recordCount").setValue(0).await()
                            ref.child("meta").child("status").setValue("ready").await()
                            ref.child("meta").child("updatedAt").setValue(System.currentTimeMillis()).await()
                            withContext(Dispatchers.Main) {
                                isVaultSynced = true
                                updateSyncStatusUI()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                isVaultSynced = true
                                updateSyncStatusUI()
                            }
                        }
                    }
                }
                return@launch
            }

            ensureAuth {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val ref = FirebaseDatabase.getInstance().getReference("vaults").child(vaultId)

                        // 1. Mark as syncing with expected record count so receiving devices know upload is in-flight
                        ref.child("meta").child("sourceRecordCount").setValue(localRecords.size).await()
                        ref.child("meta").child("status").setValue("syncing").await()

                        // 2. Fetch remote transactions snapshot to see what's already uploaded
                        val serverSnapshot = try {
                            ref.child("transactions").get().await()
                        } catch (e: Exception) {
                            null
                        }

                        val batchUpdates = mutableMapOf<String, Any>()
                        for (t in localRecords) {
                            val stableId = syncManager.generateStableId(t.timestamp)
                            var needsPush = true

                            if (serverSnapshot != null && serverSnapshot.hasChild(stableId)) {
                                val serverEncrypted = serverSnapshot.child(stableId).value?.toString() ?: ""
                                val serverJsonStr = EncryptionHelper.decrypt(serverEncrypted, key)
                                if (serverJsonStr.isNotEmpty()) {
                                    try {
                                        val serverObj = JSONObject(serverJsonStr)
                                        needsPush = (serverObj.optString("o") != t.originalText) ||
                                                (serverObj.optLong("a") != t.amount) ||
                                                (serverObj.optString("d") != t.description) ||
                                                (serverObj.optString("n", "NORMAL") != t.nature) ||
                                                (serverObj.optLong("oa", 0L) != t.obligationAmount)
                                    } catch (e: Exception) {
                                        needsPush = true
                                    }
                                }
                            }

                            if (needsPush) {
                                val jsonObject = JSONObject()
                                jsonObject.put("o", t.originalText)
                                jsonObject.put("a", t.amount)
                                jsonObject.put("d", t.description)
                                jsonObject.put("t", t.timestamp)
                                jsonObject.put("n", t.nature)
                                jsonObject.put("oa", t.obligationAmount)
                                val encryptedData = EncryptionHelper.encrypt(jsonObject.toString(), key)
                                batchUpdates[stableId] = encryptedData
                            }
                        }

                        // Atomic batch upload of all pending records
                        if (batchUpdates.isNotEmpty()) {
                            ref.child("transactions").updateChildren(batchUpdates).await()
                        }

                        // Write metadata for verification by the receiving device
                        ref.child("meta").child("recordCount").setValue(localRecords.size).await()
                        ref.child("meta").child("status").setValue("ready").await()
                        ref.child("meta").child("updatedAt").setValue(System.currentTimeMillis()).await()

                        withContext(Dispatchers.Main) {
                            isVaultSynced = true
                            updateSyncStatusUI()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                            if (isDestroyed || isFinishing) return@withContext
                            binding.tvLinkedCount.text = "Vault sync error. Check connection."
                        }
                    }
                }
            }
        }
    }

    private fun unlinkThisDevice() {
        if (!isOnline()) {
            Toast.makeText(this, "Connect to internet to unlink.", Toast.LENGTH_SHORT).show()
            return
        }

        val installId = syncManager.getInstallationId()
        val oldVaultId = currentVaultId ?: return

        ensureAuth {
            // 1. Remove myself from Server List
            FirebaseDatabase.getInstance().getReference("vaults")
                .child(oldVaultId)
                .child("devices")
                .child(installId)
                .removeValue()
                .addOnCompleteListener {
                    // FIX 10: Lifecycle Guard
                    // If activity is destroyed (e.g., user rotated screen or left), don't touch UI
                    if (isDestroyed || isFinishing) return@addOnCompleteListener

                    // 2. Clear Local Data & Generate New ID
                    syncManager.unlinkDevice()
                    Toast.makeText(this@LinkDeviceActivity, "Unlinked. You are now on a fresh account.", Toast.LENGTH_LONG).show()

                    // 3. Restart Activity Cleanly
                    // FIX: Removed the CLEAR_TASK flags.
                    // This safely refreshes the Link page to show the new QR code
                    // while perfectly preserving MainActivity underneath for the back button.
                    val intent = Intent(this@LinkDeviceActivity, LinkDeviceActivity::class.java)
                    startActivity(intent)
                    finish()
                }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop listening to avoid leaks
        currentVaultId?.let { vid ->
            FirebaseDatabase.getInstance().getReference("vaults")
                .child(vid).child("devices").removeEventListener(deviceListener ?: return)
        }
    }

    private fun displayQrCode(vaultId: String, key: String) {
        try {
            val json = JSONObject()
            json.put("v", vaultId)
            json.put("k", key)
            val writer = MultiFormatWriter()
            val matrix = writer.encode(json.toString(), BarcodeFormat.QR_CODE, 600, 600)
            val encoder = BarcodeEncoder()
            val bitmap = encoder.createBitmap(matrix)
            binding.ivQrCode.setImageBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error generating QR", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents != null) {
                try {
                    val json = JSONObject(result.contents)
                    val scannedVault = json.getString("v")
                    val scannedKey = json.getString("k")

                    // 1. Validate the key by trying to encrypt a dummy word
                    try {
                        val test = EncryptionHelper.encrypt("test", scannedKey)
                        if (test.isEmpty()) throw Exception("Encryption failed")
                    } catch (e: Exception) {
                        Toast.makeText(this, "Error: Invalid or corrupted Key in QR Code", Toast.LENGTH_LONG).show()
                        return
                    }

                    lifecycleScope.launch(Dispatchers.IO) {
                        val existingTransactions = db.transactionDao().getAll()

                        withContext(Dispatchers.Main) {
                            if (existingTransactions.isEmpty()) {
                                // Log is empty, link immediately (No merge needed)
                                startTransfer(scannedVault, scannedKey, isMerging = false)
                            } else {
                                // Log is not empty, show Merge/Overwrite dialog
                                MaterialAlertDialogBuilder(this@LinkDeviceActivity)
                                    .setTitle("Link Device")
                                    .setMessage("You already have logs on this device.\nDo you want to merge them with the new linked device or replace them entirely?")
                                    .setPositiveButton("Merge") { _, _ ->
                                        startTransfer(scannedVault, scannedKey, isMerging = true)
                                    }
                                    .setNeutralButton("Overwrite") { _, _ ->
                                        // Worker handles safe deletion — do NOT deleteAll() here
                                        startTransfer(scannedVault, scannedKey, isMerging = false)
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        }
                    }

                } catch (e: Exception) {
                    Toast.makeText(this, "Invalid QR Code", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun startTransfer(newVaultId: String, newSecretKey: String, isMerging: Boolean) {
        if (!isOnline()) {
            Toast.makeText(this, "Connect to internet to link devices.", Toast.LENGTH_SHORT).show()
            return
        }

        // Cancel any ongoing normal sync to prevent race conditions
        WorkManager.getInstance(this).cancelUniqueWork("JotPaySyncQueue")

        launchTransferWorker(newVaultId, newSecretKey, isMerging)
    }

    private fun launchTransferWorker(newVaultId: String, newSecretKey: String, isMerging: Boolean) {
        val mode = if (isMerging) LinkTransferWorker.MODE_MERGE else LinkTransferWorker.MODE_OVERWRITE
        val workId = LinkTransferWorker.enqueueWork(this, mode, newVaultId, newSecretKey)
        registerDeviceOnServer(newVaultId) // Register device in new vault
        LinkTransferActivity.start(this, mode, newVaultId, newSecretKey, workId)
        finish() // Closes LinkDeviceActivity so MainActivity is underneath
    }
}
