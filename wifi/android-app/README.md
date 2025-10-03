Android app skeleton for automating the captive-portal flow from the `wifi` repo

What this is

- A minimal Android Studio-compatible skeleton that uses a WebView to load a captive-portal page and injects the existing `preload.js` (from the repo) to apply the same runtime navigator overrides used by the desktop Puppeteer worker.
- The app includes basic JS-driven form automation (selector + XPath/text fallback) and saves screenshots to the app's files directory.

Important limitations / differences vs. the original Node.js version

- MAC address spoofing: changing the device MAC on Android requires root or vendor-specific APIs. This app DOES NOT attempt to change the hardware MAC. See the `MAC SPOOFING` section below for options and guidance if you need that behavior.
- Puppeteer vs WebView: Puppeteer runs a full Chromium with request interception and evaluateOnNewDocument. Android's WebView has different lifecycle hooks; injection via `evaluateJavascript` is performed after load (onPageFinished), which may be late for some anti-detection checks.

How to open and run

1. Open `android-app/` in Android Studio.
2. Build and run on a device or emulator with network access.
3. In the app UI paste or navigate to the captive portal URL (e.g. `http://cwifi-new.cox.com/...`) and use the provided "Fill & Submit" action.

Files created

- `app/src/main/java/com/example/freewifi/MainActivity.kt` — main WebView activity with injection and automation logic.
- `app/src/main/assets/preload.js` — copied from the repository's `preloads`.
- Gradle files so you can open the project in Android Studio.

MAC SPOOFING

- Android requires root to change the device's MAC address in most cases. If you need to reproduce the Linux `Macchangerizer.sh` behavior:
  - Use a rooted device and a script that calls `ip link set dev wlan0 address <mac>` or `busybox ifconfig wlan0 hw ether <mac>`.
  - Alternatively, use an external Wi-Fi adapter that supports MAC randomization and can be controlled from a host machine.

If you'd like, I can:
- Add a small local HTML test harness (recorded captive portal) and unit tests for the JS automation logic.
- Implement an advanced injection using `shouldInterceptRequest()` to prepend the `preload.js` to HTML responses (more complex but earlier injection point).

Notes

This skeleton is a starting point. Building and debugging require Android Studio and an appropriate Android SDK/NDK environment.

Testing with recorded HTML

For repeatable local testing (recommended):

1. Save a captured HTML file of the captive portal to a simple web server (e.g., `python -m http.server`) on your workstation.
2. Point the Android WebView to the local server address (e.g., `http://<host-ip>:8000/recorded-portal.html`) so you can iterate quickly without relying on the live portal.

Advanced injection note

- The current injection happens in `onPageFinished`. If you need earlier injection (closer to evaluateOnNewDocument), consider implementing `shouldInterceptRequest()` in a custom `WebViewClient` and rewriting HTML responses to prepend the `preload.js` contents. This is more complex but possible.

Run & build (quick)

- Open `android-app/` in Android Studio and run on a device (recommended) or emulator.

- Or build from the terminal (requires Gradle installed or use Android Studio's embedded Gradle):

```bash
# from the android-app/ folder
./gradlew assembleDebug
# or to install to a connected device
./gradlew installDebug
```

Using Android's randomized MAC behavior

- On Android 10+ the system can use randomized MACs for Wi-Fi connections when apps request a network using `WifiNetworkSpecifier` (the method used by the app).
- The app prompts for SSID (and optional passphrase) and calls the OS to connect to that network. The system will apply the device's randomized MAC policy for that network (no root required).
- Note: randomized MAC behavior is controlled by the OS and potentially by the network profile; the app requests the connection but the OS enforces MAC selection.

Testing tips

- For stable iteration, host a recorded captive-portal HTML and point the WebView at it (see "Testing with recorded HTML").
- If screenshots are not captured, ensure the device has network access to the html2canvas CDN or include html2canvas in `app/src/main/assets` and load it locally.
