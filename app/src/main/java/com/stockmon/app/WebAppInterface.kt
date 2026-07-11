package com.stockmon.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * WebView <-> 原生桥接。Web 端通过 window.NativeBridge 调用：
 *  hasNative()         -> "1" 表示运行在原生 App 中
 *  saveConfig(json)    -> 把监控列表/阈值写入文件，供前台服务读取
 *  startMonitor()      -> 启动前台服务（后台持续拉行情 + 锁屏报警）
 *  stopMonitor()       -> 停止前台服务
 *  openBatterySettings()-> 打开系统电池优化设置页
 */
class WebAppInterface(private val activity: MainActivity) {

    private val appContext: Context = activity.applicationContext

    @JavascriptInterface
    fun hasNative(): String = "1"

    @JavascriptInterface
    fun saveConfig(json: String) {
        try {
            JSONObject(json) // 校验为合法 JSON
            appContext.openFileOutput("monitor_config.json", Context.MODE_PRIVATE).use {
                it.write(json.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JavascriptInterface
    fun startMonitor() {
        try {
            appContext.startForegroundService(Intent(appContext, MonitorService::class.java))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JavascriptInterface
    fun stopMonitor() {
        try {
            appContext.stopService(Intent(appContext, MonitorService::class.java))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JavascriptInterface
    fun openBatterySettings() {
        // 启动 Activity 必须在主线程
        Handler(Looper.getMainLooper()).post { activity.openBatterySettings() }
    }
}
