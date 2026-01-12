package com.example.moneylog

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class PrivacyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy)

        // Handle the new Close button
        findViewById<Button>(R.id.btnClose).setOnClickListener {
            finish() // Closes the page and goes back to Dashboard
        }
    }
}