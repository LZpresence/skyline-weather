package com.lzpresence.skyline

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** The full Kikker Forecast web app, full screen, with device location passed through. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var geoCallback: GeolocationPermissions.Callback? = null
    private var geoOrigin: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.setGeolocationEnabled(true)
        // lets the web app tell the widget which units to show
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun setUnits(units: String) {
                val value = if (units == "metric") "metric" else "us"
                val prefs = getSharedPreferences("skyline", MODE_PRIVATE)
                if (prefs.getString("units", "us") != value) {
                    prefs.edit().putString("units", value).apply()
                    WidgetWorker.runNow(this@MainActivity)
                }
            }
        }, "KikkerApp")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == HOST) return false
                // weather.gov and other links open in the regular browser
                try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (e: Exception) { /* no browser */ }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (Loc.hasPermission(this@MainActivity)) {
                    callback.invoke(origin, true, false)
                } else {
                    geoCallback = callback
                    geoOrigin = origin
                    askLocation()
                }
            }
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(URL)
        if (!Loc.hasPermission(this)) askLocation()
        WidgetWorker.schedule(this)
    }

    private fun askLocation() {
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = Loc.hasPermission(this)
        geoCallback?.invoke(geoOrigin, ok, false)
        geoCallback = null
        if (ok) refreshLocation()
    }

    override fun onResume() {
        super.onResume()
        if (Loc.hasPermission(this)) refreshLocation()
    }

    /** Save a fresh fix so the widget can use it while the app is closed. */
    @SuppressLint("MissingPermission")
    private fun refreshLocation() {
        Loc.lastKnown(this)?.let { Loc.save(this, it.latitude, it.longitude); WidgetWorker.runNow(this) }
        val lm = getSystemService(LocationManager::class.java) ?: return
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return
        }
        val onFix = { l: Location? ->
            if (l != null) {
                Loc.save(this, l.latitude, l.longitude)
                WidgetWorker.runNow(this)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, mainExecutor) { l -> onFix(l) }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, object : LocationListener {
                    override fun onLocationChanged(location: Location) { onFix(location) }
                }, mainLooper)
            }
        } catch (e: Exception) { /* keep last known */ }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Still the simplest back handling for a plain Activity")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    companion object {
        private const val HOST = "lzpresence.github.io"
        private const val URL = "https://lzpresence.github.io/skyline-weather/"
    }
}
