package com.example.wifi2

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import androidx.work.ListenableWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture
import androidx.core.app.NotificationCompat
import androidx.concurrent.futures.CallbackToFutureAdapter
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID

class PortalLoginWorker(private val appContext: Context, workerParams: WorkerParameters) :
    ListenableWorker(appContext, workerParams) {

    companion object {
        const val NOTIFICATION_ID = 101
        const val CHANNEL_ID = "WIFI_CONNECT_CHANNEL"
    }

    override fun startWork(): ListenableFuture<Result> {
        // Create ForegroundInfo and set the worker to run in the foreground.
        val foregroundInfo = ForegroundInfo(NOTIFICATION_ID, createNotification("Connecting to Wi-Fi..."))
        setForegroundAsync(foregroundInfo)

        return CallbackToFutureAdapter.getFuture { completer ->
            val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            if (!wifiManager.isWifiEnabled) {
                Log.w("PortalLoginWorker", "Wi-Fi is disabled.")
                completer.set(Result.failure())
                return@getFuture "PortalLoginWorker: Wi-Fi disabled"
            }

            Log.d("PortalLoginWorker", "Disconnecting Wi-Fi to get a new MAC address...")
            wifiManager.disconnect()

            Thread.sleep(5000)

            val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.d("PortalLoginWorker", "Wi-Fi network is available.")
                    connectivityManager.unregisterNetworkCallback(this)
                    ConnectivityManager.setProcessDefaultNetwork(network)

                    val newMac = getMacAddress()
                    if (newMac.isNullOrEmpty() || newMac == "02:00:00:00:00:00") {
                        Log.e("PortalLoginWorker", "Could not get a valid MAC address.")
                        completer.set(Result.failure())
                        notificationManager.notify(NOTIFICATION_ID, createNotification("Connection Failed: No MAC"))
                        return
                    }
                    Log.d("PortalLoginWorker", "New MAC Address: $newMac")

                    val portalUrl = "https://uwp-wifi-access-portal.cox.com/splash?mac-address=$newMac&ap-mac=3C:82:C0:F6:DA:24&ssid=CoxWiFi&vlan=103&nas-id=NRFKWAGB01.at.at.cox.net&block=false&unique=${UUID.randomUUID()}"

                    val intent = Intent(applicationContext, PortalWebViewActivity::class.java).apply {
                        putExtra("PORTAL_URL", portalUrl)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    applicationContext.startActivity(intent)
                    notificationManager.notify(NOTIFICATION_ID, createNotification("Success! Authenticating..."))
                    completer.set(Result.success())
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    Log.e("PortalLoginWorker", "Wi-Fi network became unavailable.")
                    notificationManager.notify(NOTIFICATION_ID, createNotification("Connection Failed: No Network"))
                    completer.set(Result.failure())
                }
            }

            connectivityManager.requestNetwork(networkRequest, networkCallback, 15_000)
            "PortalLoginWorker: Waiting for network callback"
        }
    }

    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Wi-Fi Connector")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Make sure you have this drawable
            .setOngoing(true) // Makes the notification non-dismissable
            .build()
    }

    private fun getMacAddress(): String? {
        try {
            return Collections.list(NetworkInterface.getNetworkInterfaces())
                .firstOrNull { it.name.equals("wlan0", ignoreCase = true) }
                ?.hardwareAddress?.joinToString(":") { String.format("%02X", it) }
        } catch (ex: Exception) {
            Log.e("PortalLoginWorker", "Exception getting MAC address", ex)
        }
        return null
    }
}