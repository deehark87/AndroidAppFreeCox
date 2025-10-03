package com.example.freewifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.*
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.view.View
import android.view.animation.AlphaAnimation
import androidx.preference.PreferenceManager

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val TAG = "FreeWifiMain"
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallbackRef: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private val networkTimeoutMs = 30_000L
    private val captivePortalUrl = "http://cwifi-new.cox.com/"
    private lateinit var rvScreenshots: RecyclerView
    private lateinit var adapter: ScreenshotAdapter
    private val logs = mutableListOf<String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
    webView = findViewById(R.id.webView)
    rvScreenshots = findViewById(R.id.rvScreenshots)
    adapter = ScreenshotAdapter(this, mutableListOf(), onShare = { file -> shareFile(file) }, onDelete = { file -> deleteFileAndRefresh(file) })
    rvScreenshots.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
    rvScreenshots.adapter = adapter

    val statusTv = findViewById<android.widget.TextView>(R.id.tvStatus)
    val retryBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRetry)
    val progressBar = findViewById<android.widget.ProgressBar>(R.id.pbStatus)
        retryBtn.setOnClickListener {
            animateStatusChange(statusTv, "Retrying")
            progressBar.visibility = android.view.View.VISIBLE
            connectToWifiWithRandomizedMac("CoxWiFi", null)
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.i(TAG, "onPageFinished: $url")
                injectPreloadAndAutomate()
            }
        }

        // Load a default page — user can navigate to the captive portal in the WebView
        webView.loadUrl("https://example.com")

        // Attempt automatic connect to SSID "CoxWiFi" using the system randomized MAC (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requestLocationPermissionAndConnect()
        }

        // load existing screenshots into RecyclerView
        loadScreenshotsIntoAdapter()

        // read auto-connect preference and optionally auto-connect
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val autoConnect = prefs.getBoolean("pref_auto_connect", true)
        if (autoConnect && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectToWifiWithRandomizedMac("CoxWiFi", null)
        }
    }

    private fun requestLocationPermissionAndConnect() {
        // Some APIs require location permission for Wi-Fi operations; check first then request if needed.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            connectToWifiWithRandomizedMac("CoxWiFi", null)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                connectToWifiWithRandomizedMac("CoxWiFi", null)
            } else {
                // Permission denied — show rationale and allow retry or continue without connecting
                val shouldShow = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)
                val builder = AlertDialog.Builder(this)
                builder.setTitle("Location permission needed")
                builder.setMessage("This app needs location permission to connect to Wi-Fi networks using the system API. Grant it to let the app connect to CoxWiFi with a randomized MAC.")
                if (shouldShow) {
                    builder.setPositiveButton("Retry") { _, _ ->
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
                    }
                } else {
                    builder.setPositiveButton("Open settings") { _, _ ->
                        // Open app settings
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        val uri = android.net.Uri.fromParts("package", packageName, null)
                        intent.data = uri
                        startActivity(intent)
                    }
                }
                builder.setNegativeButton("Continue without Wi-Fi", null)
                builder.show()
            }
        }
    }

    private fun connectToWifiWithRandomizedMac(ssid: String, password: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Randomized MAC via WifiNetworkSpecifier requires Android 10+")
            return
        }

        try {
            val specBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            // If a WPA2 password is provided, use the WPA2 passphrase builder method
            if (!password.isNullOrEmpty()) {
                try {
                    specBuilder.setWpa2Passphrase(password)
                } catch (e: NoSuchMethodError) {
                    // Some older vendor SDKs might not have this; fallback to just SSID
                    Log.w(TAG, "setWpa2Passphrase not available on this device: ${'$'}e")
                }
            }
            val spec = specBuilder.build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(spec)
                .build()

            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager = cm

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.i(TAG, "Network available: ${'$'}network — binding process to it")
                    runOnUiThread {
                        animateStatusChange(statusTv, "Connected")
                        progressBar.visibility = android.view.View.GONE
                    }
                    // Bind the process to the new network so WebView traffic uses it
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        cm.bindProcessToNetwork(network)
                    } else {
                        @Suppress("DEPRECATION")
                        ConnectivityManager.setProcessDefaultNetwork(network)
                    }

                    // Cancel timeout and notify success
                    timeoutRunnable?.let { handler.removeCallbacks(it) }
                    showSuccessDialog("Connected to $ssid")
                    runOnUiThread {
                        animateStatusChange(statusTv, "Connected")
                        progressBar.visibility = android.view.View.GONE
                    }

                    // Load captive portal in WebView on UI thread
                    runOnUiThread {
                        try {
                            webView.loadUrl(captivePortalUrl)
                        } catch (e: Exception) {
                            Log.e(TAG, "failed to load captive portal: ${'$'}e")
                        }
                    }
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    Log.w(TAG, "Requested Wi-Fi network unavailable")
                    showFailureDialog("Requested Wi-Fi network unavailable")
                    runOnUiThread {
                        animateStatusChange(statusTv, "Unavailable")
                        progressBar.visibility = android.view.View.GONE
                    }
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.i(TAG, "Network lost: ${'$'}network")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Wi-Fi network lost", Toast.LENGTH_LONG).show()
                        animateStatusChange(statusTv, "Lost")
                        progressBar.visibility = android.view.View.GONE
                    }
                }
            }

            // Keep reference so we can unregister on timeout
            networkCallbackRef = callback

            cm.requestNetwork(request, callback)
            Log.i(TAG, "Requested network spec for SSID: $ssid — system will manage randomized MAC settings")

            // Schedule timeout
            timeoutRunnable = Runnable {
                try {
                    networkCallbackRef?.let { cm.unregisterNetworkCallback(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "error unregistering callback: ${'$'}e")
                }
                showFailureDialog("Timed out connecting to $ssid")
                runOnUiThread {
                    animateStatusChange(statusTv, "Timeout")
                    progressBar.visibility = android.view.View.GONE
                }
            }
            handler.postDelayed(timeoutRunnable!!, networkTimeoutMs)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Wi-Fi network: ${'$'}e")
        }
    }

    private fun injectPreloadAndAutomate() {
        try {
            val preloadJs = assets.open("preload.js").bufferedReader().use { it.readText() }

            // Inject the preload script into the page context
            val wrapped = "(function() { try { $preloadJs } catch (e) { console.error('preload error', e); } })();"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                webView.evaluateJavascript(wrapped, ValueCallback { result ->
                    Log.i(TAG, "preload injected: $result")
                    appendLog("preload injected: $result")
                    // After injecting, run automation script
                    runAutomationScript()
                })
            } else {
                webView.loadUrl("javascript:$wrapped")
                runAutomationScript()
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed inject: ${'$'}e")
        }
    }

    private fun runAutomationScript() {
        // A JS automation snippet that tries CSS selector then XPath/text fallback.
        val automation = """
            (function(){
              try {
                function snapshot(){
                  try {
                    var canvas = document.createElement('canvas');
                    canvas.width = window.innerWidth;
                    canvas.height = window.innerHeight;
                    var ctx = canvas.getContext('2d');
                    ctx.fillStyle = '#fff';
                    ctx.fillRect(0,0,canvas.width,canvas.height);
                    html2canvas(document.body).then(function(canvasEl){
                      var data = canvasEl.toDataURL('image/jpeg', 0.8);
                      // send via prompt so Android can intercept
                      console.log('screenshot-captured');
                      window.android.onScreenshot(data);
                    });
                  } catch(e){ console.error('snapshot failed', e); }
                }

                // Try known fragile selector first
                var sel = document.querySelector('#signIn > .signInText > .freeAccessPassSignup > .floatleft > .coxRegisterButton');
                if(sel){ sel.click(); console.log('clicked fragile selector'); setTimeout(snapshot, 1000); return; }

                // Fallback: find buttons with innerText matching common labels
                var buttons = Array.from(document.querySelectorAll('button, a'));
                var found = buttons.find(function(b){
                  var t = (b.innerText || '').trim().toLowerCase();
                  return ['register','get pass','continue','sign in','free access'].some(function(x){ return t.indexOf(x) !== -1; });
                });
                if(found){ found.click(); console.log('clicked fallback button'); setTimeout(snapshot, 1000); return; }

                // XPath/text fallback
                var xpath = "//button[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'),'register')]");
                var res = document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);
                if(res && res.singleNodeValue){ res.singleNodeValue.click(); console.log('clicked xpath'); setTimeout(snapshot,1000); return; }

                console.log('no-candidate-buttons', buttons.map(function(b){return b.innerText}).slice(0,20));
                setTimeout(snapshot, 500);
              }catch(e){ console.error('automation error', e); }
            })();
        """.trimIndent()

        // Evaluate automation; also register an interface for receiving screenshots
        runOnUiThread {
            webView.addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun onScreenshot(dataUrl: String) {
                    saveDataUrlScreenshot(dataUrl)
                }
            }, "android")

            // Inject html2canvas from CDN for screenshotting (best-effort)
            val loader = "var s=document.createElement('script');s.src='https://cdnjs.cloudflare.com/ajax/libs/html2canvas/1.4.1/html2canvas.min.js';document.head.appendChild(s);"
            webView.evaluateJavascript(loader, null)
            webView.evaluateJavascript(automation, null)
        }
    }

    private fun saveDataUrlScreenshot(dataUrl: String) {
        try {
            val base64Data = dataUrl.substringAfter(',')
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val out = File(filesDir, "screenshot-${'$'}{System.currentTimeMillis()}.jpeg")
            FileOutputStream(out).use { it.write(bytes) }
            Log.i(TAG, "saved screenshot: ${'$'}out")
            // refresh RecyclerView
            runOnUiThread { loadScreenshotsIntoAdapter() }
        } catch (e: Exception) {
            Log.e(TAG, "save screenshot failed: ${'$'}e")
        }
    }

    private fun loadScreenshotsIntoAdapter() {
        val files = filesDir.listFiles()?.filter { it.name.endsWith(".jpeg") || it.name.endsWith(".jpg") }?.sortedByDescending { it.lastModified() } ?: emptyList()
        adapter.updateFiles(files.toMutableList())
    }

    private fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${'$'}packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "image/jpeg"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Share screenshot"))
        } catch (e: Exception) {
            Log.e(TAG, "share failed: ${'$'}e")
        }
    }

    private fun deleteFileAndRefresh(file: File) {
        try {
            if (file.delete()) {
                runOnUiThread { loadScreenshotsIntoAdapter() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "delete failed: ${'$'}e")
        }
    }

    private fun showSuccessDialog(message: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Wi-Fi connected")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
            appendLog("success: $message")
        }
    }

    private fun showFailureDialog(message: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Connection failed")
                .setMessage(message)
                .setPositiveButton("Retry") { _, _ ->
                    // retry connecting
                    connectToWifiWithRandomizedMac("CoxWiFi", null)
                }
                .setNegativeButton("Cancel", null)
                .show()
            appendLog("failure: $message")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            connectivityManager?.let { cm ->
                networkCallbackRef?.let { cm.unregisterNetworkCallback(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during onDestroy cleanup: ${'$'}e")
        }
        timeoutRunnable?.let { handler.removeCallbacks(it) }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.top_app_bar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            R.id.action_logs -> {
                showLogsBottomSheet()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showLogsBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_logs, null)
        val tvLogs = view.findViewById<android.widget.TextView>(R.id.tvLogs)
        tvLogs.text = logs.joinToString("\n")
        dialog.setContentView(view)
        dialog.show()
    }

    private fun appendLog(line: String) {
        val ts = System.currentTimeMillis()
        logs.add(0, "[${'$'}ts] ${'$'}line")
    }

    private fun animateStatusChange(tv: android.widget.TextView, text: String) {
        runOnUiThread {
            val out = AlphaAnimation(1f, 0f)
            out.duration = 150
            out.fillAfter = true
            val `in` = AlphaAnimation(0f, 1f)
            `in`.duration = 150
            `in`.fillAfter = true
            out.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                    tv.text = text
                    tv.startAnimation(`in`)
                }
                override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
            })
            tv.startAnimation(out)
        }
    }
}
