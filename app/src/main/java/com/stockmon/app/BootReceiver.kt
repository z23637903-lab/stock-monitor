package com.stockmon.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 应用更新后自启前台服务（仅当用户已配置过监控）。
 * 配合系统「自启动管理」允许本应用，重启手机后自动开始盯盘。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (context.getFileStreamPath("monitor_config.json")?.exists() == true) {
                try {
                    context.startForegroundService(Intent(context, MonitorService::class.java))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
