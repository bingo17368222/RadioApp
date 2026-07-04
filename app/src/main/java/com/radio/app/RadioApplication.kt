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
        @Volatile private var cacheRootDirCache: File? = null

        /**
         * v2.1.0: External cache root: /sdcard/RadioApp/
         * 所有缓存文件(pcm_cache, episodes, audio_cache)统一放在此目录下，
         * 与 logs/ 同级，方便用户管理和备份。
         * Fallback: getExternalFilesDir(null)/RadioApp/
         */
        fun getCacheRootDir(context: android.content.Context): File {
            cacheRootDirCache?.let { return it }
            val sdcardDir = File(Environment.getExternalStorageDirectory(), "RadioApp")
            try {
                if (!sdcardDir.exists()) sdcardDir.mkdirs()
                val testFile = File(sdcardDir, ".write_test")
                testFile.writeText("test")
                testFile.delete()
                cacheRootDirCache = sdcardDir
                return sdcardDir
            } catch (_: Exception) {
                val fallback = File(context.getExternalFilesDir(null), "RadioApp")
                if (!fallback.exists()) fallback.mkdirs()
                cacheRootDirCache = fallback
                return fallback
            }
        }

        /**
         * v2.1.0: PCM cache dir: /sdcard/RadioApp/pcm_cache/
         */
        fun getPcmCacheDir(context: android.content.Context): File {
            val dir = File(getCacheRootDir(context), "pcm_cache")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        /**
         * v2.1.0: Episodes cache dir: /sdcard/RadioApp/episodes/
         */
        fun getEpisodesCacheDir(context: android.content.Context): File {
            val dir = File(getCacheRootDir(context), "episodes")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        fun getLogDir(context: android.content.Context): File {
            logDirCache?.let { return it }
            // Prefer /sdcard/RadioApp/logs/ (user-accessible)
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
            // Use /sdcard/RadioApp/logs/crash/ (user-accessible)
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
        // Init crash handler first
        com.radio.app.utils.CrashHandler.getInstance().init(this)
        createNotificationChannel()
        // Warm up log dir
        getLogDir(this)
        // [v2.1.0] Warm up cache dir + migrate legacy PCM cache
        migrateLegacyPcmCache()
    }

    /**
     * v2.1.0: Migrate legacy PCM cache from getExternalFilesDir/pcm_cache/ to /sdcard/RadioApp/pcm_cache/
     * Also deletes corrupt _5min_16k.pcm files (v2.0.98 bug)
     */
    private fun migrateLegacyPcmCache() {
        try {
            val newDir = getPcmCacheDir(this)
            val oldDir = getExternalFilesDir(null)?.let { File(it, "pcm_cache") }
            if (oldDir == null || !oldDir.exists()) return
            val oldFiles = oldDir.listFiles() ?: return
            var migrated = 0
            var deletedLegacy = 0
            for (file in oldFiles) {
                if (file.name.endsWith("_5min_16k.pcm") || file.name.endsWith("_5min_16k.info")) {
                    // Delete corrupt legacy files from v2.0.98 bug
                    file.delete()
                    deletedLegacy++
                } else if (file.name.endsWith("_5min.pcm") || file.name.endsWith("_5min.info") || file.name.endsWith("_5min.wav")) {
                    // Move valid unified files to new location
                    val target = File(newDir, file.name)
                    if (!target.exists()) {
                        file.renameTo(target)
                        migrated++
                    } else {
                        file.delete()  // Duplicate, keep new one
                    }
                } else if (file.name.endsWith(".pcm") || file.name.endsWith(".info") || file.name.endsWith(".wav")) {
                    // Move other PCM-related files
                    val target = File(newDir, file.name)
                    if (!target.exists()) {
                        file.renameTo(target)
                        migrated++
                    }
                }
            }
            // Clean up empty old dir
            if (oldDir.listFiles()?.isEmpty() == true) oldDir.delete()
            if (migrated > 0 || deletedLegacy > 0) {
                android.util.Log.d("RadioApp", "migrateLegacyPcmCache: migrated=$migrated, deletedLegacy=$deletedLegacy")
            }
        } catch (_: Exception) {}
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
