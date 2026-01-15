package com.example.moneylog

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.moneylog.databinding.ActivityLinkDeviceBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.BarcodeEncoder
import org.json.JSONObject
import java.util.UUID

class LinkDeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLinkDeviceBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLinkDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Get or Generate Credentials
        val prefs = getSharedPreferences("jotpay_sync", Context.MODE_PRIVATE)
        var vaultId = prefs.getString("vault_id", null)
        var secretKey = prefs.getString("secret_key", null)

        // If no credentials exist, create them (You become the Host)
        if (vaultId == null) {
            vaultId = UUID.randomUUID().toString()
            secretKey = UUID.randomUUID().toString().substring(0, 16) // 16-char key
            prefs.edit()
                .putString("vault_id", vaultId)
                .putString("secret_key", secretKey)
                .apply()
        }

        // 2. Display QR Code (Vault ID + Key)
        displayQrCode(vaultId!!, secretKey!!)

        // 3. Setup Scan Button (To become a Client)
        binding.btnScan.setOnClickListener {
            val integrator = IntentIntegrator(this)
            integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            integrator.setPrompt("Scan QR code from the other device")
            integrator.setCameraId(0)
            integrator.setBeepEnabled(true)
            integrator.setOrientationLocked(true) // Keeps it portrait
            integrator.initiateScan()
        }
    }

    private fun displayQrCode(vaultId: String, key: String) {
        try {
            // Pack data into JSON
            val json = JSONObject()
            json.put("v", vaultId)
            json.put("k", key)

            val writer = MultiFormatWriter()
            // 600x600 px QR Code
            val matrix = writer.encode(json.toString(), BarcodeFormat.QR_CODE, 600, 600)
            val encoder = BarcodeEncoder()
            val bitmap = encoder.createBitmap(matrix)
            binding.ivQrCode.setImageBitmap(bitmap)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error generating QR", Toast.LENGTH_SHORT).show()
        }
    }

    // 4. Handle Scan Result
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents != null) {
                try {
                    // Parse the scanned JSON
                    val json = JSONObject(result.contents)
                    val scannedVault = json.getString("v")
                    val scannedKey = json.getString("k")

                    // Save to Memory
                    val prefs = getSharedPreferences("jotpay_sync", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("vault_id", scannedVault)
                        .putString("secret_key", scannedKey)
                        .apply()

                    Toast.makeText(this, "Device Linked Successfully", Toast.LENGTH_LONG).show()

                    // Restart app to apply changes? Or just finish
                    finish()

                } catch (e: Exception) {
                    Toast.makeText(this, "Invalid QR Code", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}