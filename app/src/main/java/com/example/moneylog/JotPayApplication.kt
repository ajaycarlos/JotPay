package com.example.moneylog

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class JotPayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // INSTANT: Forces Dark Mode process-wide before MainActivity even launches!
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }
}