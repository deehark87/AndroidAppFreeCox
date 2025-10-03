package com.example.wifi2

import android.Manifest
import android.content.Intent
import android.net.wifi.WifiNetworkSuggestion
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
                if (!isGranted) {
                    Toast.makeText(this, "Notifications are recommended for this app to run correctly.", Toast.LENGTH_LONG).show()
                }
            }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val setupButton: Button = findViewById(R.id.setupButton)
        val connectButton: Button = findViewById(R.id.connectButton)

        // This button adds the network with non-persistent MAC randomization
        setupButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setupWifiSuggestion()
            } else {
                Toast.makeText(this, "This feature requires Android 10+", Toast.LENGTH_LONG).show()
            }
        }

        // This button schedules the hourly job
        connectButton.setOnClickListener {
            scheduleHourlyConnection()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            Toast.makeText(this, "Hourly connection job scheduled!", Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setupWifiSuggestion() {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid("CoxWiFi")
            .setMacRandomizationSetting(WifiNetworkSuggestion.MAC_RANDOMIZATION_SETTING_NON_PERSISTENT)
            .build()

        val status = wifiManager.addNetworkSuggestions(listOf(suggestion))

        if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            Toast.makeText(this, "Wi-Fi suggestion for 'CoxWiFi' added!", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.Panel.ACTION_WIFI))
        } else {
            Toast.makeText(this, "Failed to add Wi-Fi suggestion.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scheduleHourlyConnection() {
        val connectRequest = PeriodicWorkRequestBuilder<PortalLoginWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("hourly-wifi-connect", ExistingPeriodicWorkPolicy.REPLACE, connectRequest)
    }
}