package com.radio.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Environment
import com.radio.app.models.AppSettings
import com.radio.app.utils.PreferenceManager
import java.io.File

class RadioApplication : Application() {

    companion object {
        const val CHANNEL_ID = "radio_playback_channel"
        const val NOTIFICATION_ID = 1
        @Volatile private var logDirCache: File? = null
        @Volatile private var crashLogDirCache: File? = null

        fun getLogDir(context: android.content.Context): File {
            logDirCache?.let { return it }
            // 优先使用 /sdcard/RadioApp/logs/（用户易于访问）
            val sdcardDir = File(Environment.getExternalStorageDirectory(), "RadioApp/logs")
            try {
                if (!sdcardDir.exists()) sdcardDir.mkdirs()
                val testFile = File(sdcardDir, ".write_test")
                testFile.writeText("test")
                testFile.delete()
                logDirCache = sdcardDir
                return sdcardDir
            } catch (_: Exception) {
                // 权限不足，回退到应用私有目录
                val fallback = File(context.getExternalFilesDir(null), "RadioApp/logs")
                if (!fallback.exists()) fallback.mkdirs()
                logDirCache = fallback
                return fallback
            }
        }

        fun getCrashLogDir(): File {
            crashLogDirCache?.let { return it }
            // 使用 /sdcard/RadioApp/logs/crash/（用户易于访问崩溃日志）
            val crashDir = File(Environment.getExternalStorageDirectory(), "RadioApp/logs/crash")
            try {
                if (!crashDir.exists()) crashDir.mkdirs()
                val testFile = File(crashDir, ".write_test")
                testFile.writeText("test")
                testFile.delete()
                crashLogDirCache = crashDir
                return crashDir
            } catch (_: Exception) {
                // 权限不足，回退到应用私有目录
                val fallback = File(Environment.getExternalStorageDirectory(), "RadioApp/logs/crash")
                if (!fallback.exists()) fallback.mkdirs()
                crashLogDirCache = fallback
                return fallback
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 初始化崩溃日志收集（必须在最前面）
        com.radio.app.utils.CrashHandler.getInstance().init(this)
        createNotificationChannel()
        // 预热日志目录
        getLogDir(this)
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
