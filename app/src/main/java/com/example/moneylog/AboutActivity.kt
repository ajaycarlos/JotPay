package com.example.moneylog

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        val versionName = BuildConfig.VERSION_NAME
        tvVersion.text = "Version $versionName"

        // 1. Website Click
        findViewById<LinearLayout>(R.id.rowWebsite).setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://thecarlos.in"))
            startActivity(intent)
        }

        // 2. Support Email Click
        findViewById<LinearLayout>(R.id.rowSupport).setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:thecarlosdev@gmail.com") // Replace with your actual support email
                putExtra(Intent.EXTRA_SUBJECT, "JotPay Support Request (v$versionName)")
            }
            startActivity(Intent.createChooser(intent, "Send Email"))
        }

        // 3. Privacy Policy Click
        findViewById<LinearLayout>(R.id.rowPrivacy).setOnClickListener {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }

        // 4. Close Button
        findViewById<Button>(R.id.btnClose).setOnClickListener {
            finish()
        }
    }
}