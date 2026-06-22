package com.radio.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.radio.app.models.AppSettings
import com.radio.app.utils.PreferenceManager

class RadioApplication : Application() {

    companion object {
        const val CHANNEL_ID = "radio_playback_channel"
    }

    override fun onCreate() {
        super.onCreate()
        // 初始化崩溃日志收集（必须在最前面）
        com.radio.app.utils.CrashHandler.getInstance().init(this)
        createNotificationChannel()
    }

    private fun applyTheme() {
        try {
            val prefMgr = PreferenceManager(this)
            val settings = prefMgr.loadSettings()
            val theme = settings.uiTheme
            when {
                AppSettings.THEME_FRESH == theme -> setTheme(R.style.Theme_RadioApp_Fresh)
                AppSettings.THEME_CLASSIC == theme -> setTheme(R.style.Theme_RadioApp_Classic)
                AppSettings.THEME_MINIMAL == theme -> setTheme(R.style.Theme_RadioApp_Minimal)
                else -> setTheme(R.style.Theme_RadioApp)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Radio Playback",
                NotificationManager.IMPORTANCE_MIN
            )
            channel.description = "Radio playback controls"
            channel.setShowBadge(false)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
