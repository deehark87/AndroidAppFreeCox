package com.example.wifi2

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class WifiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Wi-Fi Connection Service"
            val descriptionText = "Notifications for the hourly Wi-Fi reconnect service"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(PortalLoginWorker.CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}