package com.example.moneylog

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class PrivacyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Convert 24dp to actual pixels for your specific tablet screen
            val density = resources.displayMetrics.density
            val sidePaddingPx = (24 * density).toInt()

            // Apply: Original side padding, System top padding, and Original bottom padding
            v.setPadding(sidePaddingPx, systemBars.top, sidePaddingPx, sidePaddingPx)

            insets
        }

        // Handle the new Close button
        findViewById<Button>(R.id.btnClose).setOnClickListener {
            finish() // Closes the page and goes back to Dashboard
        }
    }
}