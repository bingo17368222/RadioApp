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

        // v2.4.51: Kill :subtitle process if APK version changed.
        // The :subtitle process (SubtitleGeneratorService) survives APK updates.
        // Once .so is loaded, it can't be unloaded. We must kill the process
        // so it reloads the new .so on next start.
        // This runs in the MAIN process, so it can safely kill :subtitle.
        try {
            val flagFile = java.io.File(filesDir, "native_lib_version.txt")
            val storedVersion = if (flagFile.exists()) flagFile.readText().trim().toIntOrNull() ?: 0 else 0
            val currentVersion = packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
            if (storedVersion != currentVersion) {
                flagFile.writeText(currentVersion.toString())
                android.util.Log.w("RadioApplication", "v2.4.51: APK version changed ($storedVersion → $currentVersion), killing :subtitle process")
                // Kill the :subtitle process by name
                val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                for (pi in am.runningAppProcesses) {
                    if (pi.processName == "$packageName:subtitle") {
                        android.util.Log.w("RadioApplication", "v2.4.51: Found :subtitle process (pid=${pi.pid}), killing...")
                        android.os.Process.killProcess(pi.pid)
                        break
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("RadioApplication", "v2.4.51: Failed to check/kill :subtitle process: ${e.message}")
        }
    }

    /**
     * v2.1.0: Migrate legacy PCM cache from getExternalFilesDir/pcm_cache/ to /sdcard/RadioApp/pcm_cache/
     * v2.1.3: Don't delete _5min_16k.pcm - it's valid 16kHz data from SubtitleGeneratorService.
     *         Rename it to _5min.pcm if the new file doesn't exist or is smaller.
     */
    private fun migrateLegacyPcmCache() {
        try {
            val newDir = getPcmCacheDir(this)
            val oldDir = getExternalFilesDir(null)?.let { File(it, "pcm_cache") }
            if (oldDir == null || !oldDir.exists()) return
            val oldFiles = oldDir.listFiles() ?: return
            var migrated = 0
            var renamed = 0
            for (file in oldFiles) {
                if (file.name.endsWith("_5min_16k.pcm")) {
                    // [v2.1.3] This is valid 16kHz PCM from v2.0.98 SubtitleGeneratorService.
                    // Rename to _5min.pcm (unified name) if target doesn't exist or is smaller.
                    val baseName = file.name.replace("_5min_16k.pcm", "_5min.pcm")
                    val target = File(newDir, baseName)
                    if (!target.exists() || target.length() < file.length()) {
                        target.delete()
                        file.renameTo(target)
                        renamed++
                    } else {
                        file.delete()  // New file is better, delete old
                    }
                } else if (file.name.endsWith("_5min_16k.info")) {
                    // Rename info file to match
                    val baseName = file.name.replace("_5min_16k.info", "_5min.info")
                    val target = File(newDir, baseName)
                    if (!target.exists()) {
                        file.renameTo(target)
                    } else {
                        file.delete()
                    }
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

            // [v2.1.6] Clean up old-format PCM files (e.g., sijiache-20240712-0700_5min.pcm)
            // These were generated by fetchMoreDaysForPreCache which used URL-based episode IDs.
            // New format uses stationId-based IDs (e.g., henan-private-car-2024-07-12-2_5min.pcm)
            // Old format has date without dashes (20240712), new format has dashes (2024-07-12)
            val dateDashPattern = Regex("\\d{4}-\\d{2}-\\d{2}")
            var deletedOldFormat = 0
            newDir.listFiles()?.forEach { f ->
                if ((f.name.endsWith("_5min.pcm") || f.name.endsWith("_5min.info")) &&
                    !dateDashPattern.containsMatchIn(f.name)) {
                    // Old format: no YYYY-MM-DD pattern in filename
                    f.delete()
                    deletedOldFormat++
                }
            }
            if (deletedOldFormat > 0) {
                android.util.Log.d("RadioApp", "migrateLegacyPcmCache: deleted $deletedOldFormat old-format PCM files")
            }

            if (migrated > 0 || renamed > 0 || deletedOldFormat > 0) {
                android.util.Log.d("RadioApp", "migrateLegacyPcmCache: migrated=$migrated, renamed_16k=$renamed, deletedOldFormat=$deletedOldFormat")
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
