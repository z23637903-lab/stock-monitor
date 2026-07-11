package com.stockmon.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 权限结果由 Web 端自检展示 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setGeolocationEnabled(false)
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(WebAppInterface(this), "NativeBridge")
        webView.loadUrl("file:///android_asset/www/index.html")

        // Android 13+ 请求通知权限（锁屏报警需要）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    // 由 WebAppInterface 调用：跳转到系统电池优化设置（原生 App 专用）
    fun openBatterySettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (e2: Exception) {
                Toast.makeText(this, "请手动到系统设置 → 电池 中放行本应用", Toast.LENGTH_LONG).show()
            }
        }
    }
}
