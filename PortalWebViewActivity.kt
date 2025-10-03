package com.example.wifi2

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class PortalWebViewActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_portal_web_view)

        val webView: WebView = findViewById(R.id.portalWebView)
        webView.settings.javaScriptEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d("PortalWebView", "Page finished loading: $url")
                injectFormFillScript(view)
            }
        }

        val url = intent.getStringExtra("PORTAL_URL")
        if (url != null) {
            Log.d("PortalWebView", "Loading URL: $url")
            webView.loadUrl(url)
        } else {
            Log.e("PortalWebView", "No URL provided in intent.")
            finish()
        }
    }

    private fun injectFormFillScript(webView: WebView?) {
        // This JS is a direct translation of your Puppeteer logic from free-wifi.js
        val jsScript = """
            (function() {
                function waitForElement(selector, callback) {
                    const el = document.querySelector(selector);
                    if (el) { callback(el); return; }
                    new MutationObserver((mutations, observer) => {
                        const el = document.querySelector(selector);
                        if (el) { observer.disconnect(); callback(el); }
                    }).observe(document.body, { childList: true, subtree: true });
                }

                waitForElement("button[data-cy='register-button']", function(registerButton) {
                    registerButton.click();
                    waitForElement('#firstName', function() {
                        document.querySelector('#firstName').value = 'John';
                        document.querySelector('#lastName').value = 'Smith';
                        document.querySelector('#isp').value = 'Verizon';
                        document.querySelector('#email').value = 'john.smith' + Math.floor(Math.random() * 1000) + '@gmail.com';
                        document.querySelector('#terms-agree').click();
                        document.querySelector("button[type='submit']").click();
                    });
                });
                return 'Automation script injected.';
            })();
        """.trimIndent()

        webView?.evaluateJavascript(jsScript) { result ->
            Log.d("PortalWebView", "JavaScript execution result: $result")
            // You might want to close the activity after a delay
            // handler.postDelayed({ finish() }, 5000)
        }
    }
}