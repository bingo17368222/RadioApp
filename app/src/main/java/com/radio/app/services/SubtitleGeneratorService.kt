package com.radio.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.radio.app.R
import com.radio.app.activities.PlayerActivity
import com.radio.app.database.RadioDatabaseHelper
import com.radio.app.models.AppSettings
import com.radio.app.models.Transcript
import com.radio.app.models.VoiceSegment
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SubtitleGeneratorService : Service() {

    companion object {
        private const val TAG = "SubtitleGenService"
        private const val SAMPLE_RATE = 16000
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "subtitle_progress_channel"
        private const val TASK_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutes hard timeout per task
        private const val MAX_AUDIO_DURATION_SEC = 1800L // 30分钟最大处理时长，超长音频只处理前30分钟
        // [v2.4.13] File-based flag for cross-process subtitle idle detection.
        // Created when any subtitle task is active, deleted when all tasks complete.
        // RadioPlaybackService (main process) checks this file to know if subtitle service is idle.
        private val SUBTITLE_BUSY_FLAG = java.io.File(
            android.os.Environment.getExternalStorageDirectory(),
            "RadioApp/subtitle_service_busy.flag"
        )
    }

    /** Issue 9: app version tag included in every log line */
    private val appVersion: String by lazy {
        try {
            @Suppress("DEPRECATION")
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            "v${pInfo.versionName}"
        } catch (e: Exception) {
            "v?"
        }
    }

    // [v2.0.66] Issue 6 Fix: Track current model name for display in UI
    @Volatile
    private var currentModelName: String = ""

    // [v2.4.31] Track total processing time and audio duration for speed ratio display.
    // Set by processWhisperInChunks right before onComplete; read by the wrapped
    // SubtitleCallback.onComplete to include in the completion broadcast + DB record.
    @Volatile
    private var currentProcessingTimeMs: Long = 0
    @Volatile
    private var currentAudioDurationMs: Long = 0

    // [v2.4.20] Resume mode flag: when true, append new transcripts instead of deleting old ones
    @Volatile
    private var isResumeMode: Boolean = false

    // [v2.4.10] Flag to force Whisper base model for pre-cache subtitle generation
    @Volatile
    private var forceWhisperBaseModel: Boolean = false  // [v2.4.17] Now forces Whisper tiny for pre-cache

    interface SubtitleCallback {
        fun onSubtitleGenerated(transcript: Transcript)
        fun onProgressUpdate(progress: Int, total: Int)
        fun onError(error: String)
        fun onComplete(transcripts: List<Transcript>) {}
    }

    interface SegmentCallback {
        fun onProgressUpdate(progress: Int, total: Int)
        fun onSegmentGenerated(segment: VoiceSegment)
        fun onComplete(segments: List<VoiceSegment>)
        fun onError(error: String)
    }

    inner class LocalBinder : Binder() {
        fun getService(): SubtitleGeneratorService = this@SubtitleGeneratorService
    }

    /**
     * Per-task state: independent progress tracking, cancellation flag, and log file
     */
    private inner class TaskContext(
        val taskId: String,
        val episodeId: String,
        val taskType: String,
        val logFile: File,
        @Volatile var audioUrl: String = ""  // [v2.3.9] Saved for re-generation on ASR change
    ) {
        @Volatile var lastReportedProgress = 0
        @Volatile var startTime = System.currentTimeMillis()
        @Volatile var lastOutputTime = System.currentTimeMillis()
        @Volatile var lastErrorDetail: String? = null
        @Volatile var displayTitle: String = ""  // [v2.4.18] Episode title+date for notification
        val cancelled = AtomicBoolean(false)

        fun log(msg: String) {
            try {
                val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                val line = "[$ts] [$taskType/$episodeId] $msg\n"
                FileWriter(logFile, true).use { it.append(line) }
                Log.d(TAG, "[$taskId] $msg")
            } catch (_: Exception) {}
        }
    }

    private val binder = LocalBinder()
    private var executor: ExecutorService? = null
    private var dbHelper: RadioDatabaseHelper? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Track active tasks to prevent duplicates and support cancellation
    private val activeTasks = ConcurrentHashMap<String, TaskContext>()
    private val globalCancelled = AtomicBoolean(false)

    // [v2.3.2] Global Whisper lock to prevent concurrent Whisper instances.
    // Whisper model (base ~140MB, tiny ~40MB) consumes massive native+Java memory.
    // Running two instances simultaneously (subtitle + segment tasks) causes OOM
    // where heap drops to 0-1MB and whisper_full() silently returns 0 segments.
    private val whisperLock = Object()
    @Volatile private var whisperInUse = false
    // v2.4.38: Prevent concurrent whisper processing.
    // Log showed two processWhisperInChunks running simultaneously,
    // causing extreme slowdown (chunk took 124s instead of 14s).
    @Volatile private var whisperProcessingActive = false

    override fun onCreate() {
        super.onCreate()

        // v2.4.51: The :subtitle process kill logic is now in RadioApplication.onCreate
        // (main process), which can safely kill :subtitle. The old code here tried to
        // kill self, but caused infinite restart loops and didn't work reliably.

        // [v2.0.43] Issue 7: Use unified log directory for all crash logs
        val crashLogDir = java.io.File(com.radio.app.RadioApplication.getLogDir(this), "crash")
        val crashHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
            try {
                if (!crashLogDir.exists()) crashLogDir.mkdirs()
                val ts = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).format(java.util.Date())
                val crashFile = java.io.File(crashLogDir, "subtitle_crash_${ts}.txt")
                java.io.FileWriter(crashFile).use { writer ->
                    writer.appendLine("===== SubtitleGeneratorService 崩溃日志 =====")
                    writer.appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}")
                    writer.appendLine("线程: ${thread.name}")
                    writer.appendLine("设备: ${android.os.Build.BRAND} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})")
                    writer.appendLine("")
                    java.io.PrintWriter(writer).use { pw -> throwable.printStackTrace(pw) }
                }
                Log.e(TAG, "Uncaught exception in thread ${thread.name}, crash log: ${crashFile.absolutePath}", throwable)
            } catch (_: Exception) {}
        }
        executor = Executors.newFixedThreadPool(2, java.util.concurrent.ThreadFactory { r ->
            val t = Thread(r, "SubtitleGen-${System.currentTimeMillis()}")
            t.uncaughtExceptionHandler = crashHandler
            t
        })
        dbHelper = RadioDatabaseHelper.getInstance(this)

        // 确保日志目录存在（外部存储，用户可访问）
        try {
            val logDir = getExternalFilesDir("logs")
            if (logDir != null) {
                val subtitleDir = File(logDir, "subtitle")
                if (!subtitleDir.exists()) {
                    subtitleDir.mkdirs()
                }
            }
        } catch (_: Exception) {}

        // [v2.4.17] Clean up stale busy flag file on service start.
        // If the service was killed (crash/OOM) without calling cleanupTask(), the flag file remains
        // and blocks subtitle patrol indefinitely. Since we're just starting, there are no active tasks,
        // so the flag MUST be stale — delete it.
        try {
            if (SUBTITLE_BUSY_FLAG.exists()) {
                val flagAge = System.currentTimeMillis() - SUBTITLE_BUSY_FLAG.lastModified()
                logToFile("onCreate: [v2.4.17] found stale subtitle busy flag (age=${flagAge}ms), deleting")
                SUBTITLE_BUSY_FLAG.delete()
            }
        } catch (_: Exception) {}

        // [v2.0.43] Issue 3&7: Check for Whisper crash log from native signal handler on service start
        // If the service was restarted by START_STICKY after a native crash, the crash log will be present
        try {
            val nativeCrashFile = java.io.File(filesDir, "logs/whisper/whisper_crash.log")
            if (nativeCrashFile.exists()) {
                val crashContent = nativeCrashFile.readText()
                if (crashContent.contains("CRASH signal=") || crashContent.contains("PRE-CRASH MARKER")) {
                    logToFile("[v2.0.43] onCreate: DETECTED WHISPER CRASH from previous run: $crashContent")
                    // Copy to external logs directory for user export
                    val extCrashDir = java.io.File(com.radio.app.RadioApplication.getLogDir(this), "crash")
                    if (!extCrashDir.exists()) extCrashDir.mkdirs()
                    val extCrashFile = java.io.File(extCrashDir, "whisper_crash.log")
                    // [v2.0.50] Clear the external crash log so user doesn't see old entries
                    extCrashFile.writeText("")
                    // Clear the native crash log so it doesn't trigger again
                    nativeCrashFile.writeText("")
                    // [v2.0.51] Issue 2 Fix: Don't disable Whisper (user explicitly requested not to)
                    // Just log the crash and let the user continue using Whisper
                    val settings = AppSettings.getInstance(this)
                    settings.whisperCrashCount = settings.whisperCrashCount + 1
                    settings.save(this)
                    logToFile("[v2.3.1] onCreate: Whisper crash detected (crashCount=${settings.whisperCrashCount})")
                    // [v2.3.1] Per user requirement: NO auto-switching to other models on failure.
                    // Previously we auto-switched to Vosk after 2 Whisper crashes, but the user
                    // explicitly requested that subtitle generation failure should NOT trigger
                    // model switching. User must manually change ASR engine in Settings.
                    logToFile("[v2.3.1] STRICT mode: NOT auto-switching to Vosk after Whisper crash (user preference)")
                }
            }
        } catch (_: Exception) {}

        // 关键修复：服务启动时清理残留的处理状态，防止force-kill后再次进入app自动启动
        try {
            getSharedPreferences("player_processing_state", MODE_PRIVATE).edit().clear().apply()
            logToFile("onCreate: cleaned stale processing state (prevents auto-restart after force kill)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean processing state", e)
        }

        // [v2.0.70] Issue 2 Fix: Detect if service was killed and restarted for the same episode.
        // If the last task was less than 60 seconds ago, the service was likely killed by OOM.
        // Don't auto-restart - report error to user instead.
        // [v2.0.76] Issue 2 Fix: Mark Whisper as OOM-killed so next attempt uses Vosk directly.
        try {
            val restartPrefs = getSharedPreferences("subtitle_restart_guard", MODE_PRIVATE)
            val lastEpisodeId = restartPrefs.getString("lastEpisodeId", null)
            val lastStartTime = restartPrefs.getLong("lastStartTime", 0L)
            val lastEngine = restartPrefs.getString("lastEngine", null)
            val now = System.currentTimeMillis()
            if (lastEpisodeId != null && now - lastStartTime < 60000) {
                logToFile("onCreate: [v2.0.77] Detected rapid restart for episode=$lastEpisodeId (${now - lastStartTime}ms ago), likely OOM kill. Engine=$lastEngine")
                // [v2.0.77] Issue 2 Fix: Mark OOM with timestamp, not a permanent boolean.
                // v2.0.76 used a permanent boolean which blocked all future Whisper attempts
                // even after the user manually selected Whisper again. Use a timestamp so
                // Whisper can be retried after a cooldown period (10 min) or when the user
                // explicitly selects it again (handled in generateSubtitlesForEpisode).
                if (lastEngine == "whisper") {
                    val oomMarker = getSharedPreferences("subtitle_oom_guard", MODE_PRIVATE)
                    oomMarker.edit()
                        .putLong("whisper_oom_time", now)
                        .putBoolean("whisper_oom_killed", true)
                        .apply()
                    logToFile("onCreate: [v2.0.77] Recorded Whisper OOM at $now (cooldown 10min)")
                }
                // [v2.0.93] Fix: Use engine-specific error message instead of hardcoded Whisper.
                // Previously, Vosk OOM crashes also showed "Whisper引擎已进入10分钟冷却期" which was misleading.
                val engineName = when (lastEngine) {
                    "whisper" -> "Whisper引擎"
                    "vosk" -> "Vosk引擎"
                    else -> "字幕引擎"
                }
                val cooldownMsg = if (lastEngine == "whisper") {
                    "Whisper引擎已进入10分钟冷却期，期间无法使用Whisper重试。"
                } else {
                    ""
                }
                sendSubtitleBroadcast("com.radio.app.SUBTITLE_ERROR", mapOf(
                    "episodeId" to (lastEpisodeId ?: ""),
                    "message" to "字幕生成服务因内存不足被系统终止。${engineName}处理过程中崩溃。${cooldownMsg} 字幕引擎不会自动切换，如需更换请在设置中手动选择ASR引擎后重试。"
                ))
                restartPrefs.edit().clear().apply()
            }
        } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "音频处理", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        // [v2.0.99] Register broadcast receiver for ASR provider changes.
        // When user changes ASR engine in UI process, this receiver reloads ASR settings
        // immediately, so the next subtitle generation uses the new engine.
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                val provider = intent.getStringExtra("asr_provider") ?: return
                val voskDir = intent.getStringExtra("vosk_model_dir") ?: ""
                val whisperDir = intent.getStringExtra("whisper_model_dir") ?: ""  // [v2.2.9]
                // [v2.2.9] Reset forceVosk when Whisper is explicitly selected (user wants to retry)
                val resetForceVosk = provider == "whisper-local"
                logToFile("[v2.2.9] Received ASR_PROVIDER_CHANGED broadcast: provider=$provider, voskDir=$voskDir, whisperDir=$whisperDir, resetForceVosk=$resetForceVosk")
                val settings = AppSettings.getInstance(context)
                settings.asrProvider = provider
                settings.voskModelDir = voskDir
                settings.whisperModelDir = whisperDir  // [v2.2.9]
                if (resetForceVosk) {
                    settings.forceVoskUntil = 0
                    settings.whisperCrashCount = 0
                }
                // [v2.1.6] Also write to this process's SharedPreferences.
                // SubtitleGeneratorService runs in :subtitle process. SharedPreferences
                // with MODE_PRIVATE does NOT sync across processes. Without this write,
                // reloadAsrSettings() would read stale data from this process's prefs.
                try {
                    context.getSharedPreferences("radio_app_settings", android.content.Context.MODE_MULTI_PROCESS).edit()
                        .putString("asr_provider", provider)
                        .putString("vosk_model_dir", voskDir)
                        .putString("whisper_model_dir", whisperDir)  // [v2.2.9]
                        .putLong("force_vosk_until", if (resetForceVosk) 0 else settings.forceVoskUntil)
                        .putInt("whisper_crash_count", if (resetForceVosk) 0 else settings.whisperCrashCount)
                        .commit()  // [v2.4.0] Use commit() for synchronous write across processes
                } catch (_: Exception) {}
                logToFile("[v2.2.9] ASR settings updated via broadcast + persisted to prefs: provider=${settings.safeAsrProvider()}, whisperDir=${settings.whisperModelDir}")

                // [v2.1.2] Cancel current running task so the new ASR engine takes effect immediately.
                // Previously, the running task would continue with the old engine until it finished.
                // [v2.3.9] Also re-generate cancelled tasks with the new engine immediately.
                try {
                    val cancelledTasks = activeTasks.values.toList()
                    var cancelled = 0
                    cancelledTasks.forEach { it.cancelled.set(true); cancelled++ }
                    if (cancelled > 0) {
                        logToFile("[v2.1.6] Cancelled $cancelled active subtitle task(s) due to ASR provider change")
                    }
                    // [v2.3.9] Remove from activeTasks and re-generate with new engine
                    activeTasks.clear()
                    // Wait a moment for old task to actually stop
                    Thread.sleep(500)
                    // Re-generate each cancelled task with the new ASR engine
                    cancelledTasks.forEach { oldCtx ->
                        if (oldCtx.audioUrl.isNotEmpty()) {
                            logToFile("[v2.3.9] Re-generating subtitle for ${oldCtx.episodeId} with new ASR engine")
                            // Use a minimal callback — the real callback was already consumed
                            val dummyCallback = object : SubtitleCallback {
                                override fun onSubtitleGenerated(transcript: Transcript) {}
                                override fun onProgressUpdate(progress: Int, total: Int) {}
                                override fun onComplete(transcripts: List<Transcript>) {
                                    logToFile("[v2.3.9] Re-generation complete for ${oldCtx.episodeId}: ${transcripts.size} transcripts")
                                }
                                override fun onError(error: String) {
                                    logToFile("[v2.3.9] Re-generation failed for ${oldCtx.episodeId}: $error")
                                }
                            }
                            try {
                                generateSubtitlesForEpisode(oldCtx.episodeId, oldCtx.audioUrl, dummyCallback)
                            } catch (e: Exception) {
                                logToFile("[v2.3.9] Re-generation exception for ${oldCtx.episodeId}: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    logToFile("[v2.1.2] Failed to cancel/restart task: ${e.message}")
                }
            }
        }, android.content.IntentFilter("com.radio.app.ASR_PROVIDER_CHANGED"))
    }

    /**
     * Write global log entry to persistent log file (external storage, user accessible)
     */
    private fun logToFile(msg: String) {
        try {
            val logDir = java.io.File(com.radio.app.RadioApplication.getLogDir(this), "subtitle")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = java.io.File(logDir, "service.log")
            // Add version header on first write
            if (!logFile.exists()) {
                logFile.appendText("=== RadioApp $appVersion ===\n")
            }
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            FileWriter(logFile, true).use { it.append("[$ts][$appVersion] $msg\n") }
            // Limit file size to 500KB
            if (logFile.length() > 500_000) {
                val lines = logFile.readLines()
                val keep = lines.takeLast(500)
                logFile.writeText(keep.joinToString("\n") + "\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "logToFile failed", e)
        }
    }

    /**
     * Issue 9: Dedicated Vosk log file for diagnosing Vosk output sparseness.
     * Written to <external>/logs/vosk/vosk.log
     */
    private fun writeVoskLog(message: String) {
        try {
            val logDir = java.io.File(getExternalFilesDir(null), "logs/vosk")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = java.io.File(logDir, "vosk.log")
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
            logFile.appendText("[$timestamp][$appVersion] $message\n")
        } catch (_: Exception) {}
    }

    /**
     * Cancel all running tasks and prevent new ones
     */
    private fun cancelAllTasks() {
        globalCancelled.set(true)
        for (ctx in activeTasks.values) {
            ctx.cancelled.set(true)
        }
        activeTasks.clear()
        logToFile("cancelAllTasks: all tasks cancelled, globalCancelled=true")
        cancelProgressNotification()
    }

    /**
     * [跨进程] 当 SubtitleGeneratorService 运行在 ":subtitle" 进程时，SubtitleCallback 回调对象
     * 无法跨进程传递（LocalBinder.getService() 跨进程返回的是 BinderProxy）。因此在每个回调触发
     * 的同时发送广播，主进程的 PlayerActivity 通过 BroadcastReceiver 接收，从而在字幕进程崩溃
     * （OOM/SIGSEGV）时主进程（播放）仍能正常工作并收到已生成的结果。
     *
     * 广播限定在本应用包内投递（intent.setPackage），避免字幕内容泄露给其他应用。
     */
    private fun sendSubtitleBroadcast(action: String, extras: Map<String, Any?>) {
        try {
            val intent = Intent(action)
            intent.setPackage(packageName)
            for ((key, value) in extras) {
                when (value) {
                    is String -> intent.putExtra(key, value)
                    is Int -> intent.putExtra(key, value)
                    is Long -> intent.putExtra(key, value)
                    is Boolean -> intent.putExtra(key, value)
                    is android.os.Parcelable -> intent.putExtra(key, value)
                }
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "sendSubtitleBroadcast failed for $action: ${e.message}")
        }
    }

    // v2.2.4 New overload that accepts full Episode object.
    // Saves episode metadata to DB before processing, so it's available
    // even if ASR fails or process crashes.
    fun generateSubtitlesForEpisode(episode: com.radio.app.models.Episode, callback: SubtitleCallback) {
        // Save episode info to DB first
        try {
            dbHelper?.saveEpisodeInfo(episode)
            logToFile("generateSubtitlesForEpisode: [v2.2.4] saved episode_info to DB, id=${episode.id}, title=${episode.title}")
        } catch (e: Exception) {
            logToFile("generateSubtitlesForEpisode: [v2.2.4] failed to save episode_info: ${e.message}")
        }
        generateSubtitlesForEpisode(episode.id, episode.audioUrl, callback)
    }

    /**
     * Generate subtitles with independent per-task progress
     */
    fun generateSubtitlesForEpisode(episodeId: String, audioUrl: String, callback: SubtitleCallback) {
        val taskId = "sub_${episodeId}_${System.currentTimeMillis()}"
        val logFile = prepareTaskLogFile("subtitle", episodeId)
        val ctx = TaskContext(taskId, episodeId, "SUBTITLE", logFile)
        ctx.audioUrl = audioUrl  // [v2.3.9] Save for re-generation on ASR change
        // [Fix] Auto-started (pre-cache/background) tasks arrive here via Intent carrying only
        // episodeId + audioUrl, so episode_info may be missing or have an empty date/title.
        // Make sure the row exists with a date and title before building the display title,
        // otherwise the episode shows "no date/title" while 后台音频处理中 is running.
        ensureEpisodeInfo(episodeId, audioUrl)
        // [v2.4.18] Build display title (date + title) for notification
        ctx.displayTitle = buildDisplayTitle(episodeId, audioUrl)
        if (activeTasks.putIfAbsent(episodeId, ctx) != null) {
            Log.w(TAG, "Subtitle task already running for $episodeId, skipping duplicate")
            return
        }
        // [v2.4.13] Set busy flag for cross-process idle detection
        try { SUBTITLE_BUSY_FLAG.parentFile?.mkdirs(); SUBTITLE_BUSY_FLAG.createNewFile() } catch (_: Exception) {}
        ctx.log("Starting subtitle generation, audioUrl=$audioUrl")

        // 包装回调，同时更新通知；并同时发送跨进程广播，保证 :subtitle 进程崩溃后主进程仍可收到结果
        val wrappedCallback = object : SubtitleCallback {
            override fun onSubtitleGenerated(transcript: Transcript) {
                // Ensure episodeId is set for DB persistence
                if (transcript.episodeId.isNullOrBlank()) transcript.episodeId = episodeId
                callback.onSubtitleGenerated(transcript)
                // [跨进程] 发送字幕生成广播（回调跨进程无效，广播作为主通道）
                sendSubtitleBroadcast(
                    "com.radio.app.SUBTITLE_GENERATED",
                    mapOf(
                        "episodeId" to episodeId,
                        "text" to (transcript.text ?: ""),
                        "startMs" to transcript.segmentStart,
                        "endMs" to transcript.segmentEnd,
                        "modelName" to currentModelName  // [v2.0.66] Issue 6: model name for display
                    )
                )
                // Incrementally save to database (服务直接写库，崩溃前已落盘的结果不会丢失)
                try {
                    val dbHelper = com.radio.app.database.RadioDatabaseHelper.getInstance(this@SubtitleGeneratorService)
                    dbHelper.saveTranscript(transcript)
                    logToFile("onSubtitleGenerated: saved transcript to DB, episodeId=$episodeId, text='${transcript.text?.take(30) ?: ""}...'")
                } catch (e: Exception) {
                    logToFile("onSubtitleGenerated: failed to save to DB: ${e.message}")
                }
            }
            override fun onProgressUpdate(progress: Int, total: Int) {
                if (ctx.cancelled.get() || globalCancelled.get()) return
                callback.onProgressUpdate(progress, total)
                // [跨进程] 发送进度广播
                sendSubtitleBroadcast(
                    "com.radio.app.SUBTITLE_PROGRESS",
                    mapOf(
                        "episodeId" to episodeId,
                        "progress" to progress,
                        "total" to total
                    )
                )
                updateProgressNotification(progress, "字幕生成")
                ctx.lastReportedProgress = progress
            }
            override fun onError(error: String) {
                callback.onError(error)
                // [跨进程] 发送错误广播
                sendSubtitleBroadcast(
                    "com.radio.app.SUBTITLE_ERROR",
                    mapOf(
                        "episodeId" to episodeId,
                        "message" to error
                    )
                )
                // Ensure task is cleaned up on error: cancel ongoing notification immediately
                cancelProgressNotification()
                activeTasks.remove(episodeId)
                cleanupTask()
            }
            override fun onComplete(transcripts: List<Transcript>) {
                // [v2.2.6] Only replace old subtitles when new ones are successfully generated.
                // If transcripts is empty, preserve existing subtitles.
                if (transcripts.isNotEmpty()) {
                    try {
                        val dbHelper = com.radio.app.database.RadioDatabaseHelper.getInstance(this@SubtitleGeneratorService)
                        // [v2.4.20] Resume mode: append new transcripts instead of deleting old ones
                        if (isResumeMode) {
                            logToFile("onComplete: [v2.4.20] RESUME MODE: appending ${transcripts.size} new transcripts to existing ones for $episodeId")
                            for (t in transcripts) {
                                if (t.episodeId.isNullOrBlank()) t.episodeId = episodeId
                                dbHelper.saveTranscript(t)
                            }
                        } else {
                            dbHelper.deleteTranscriptsByEpisode(episodeId)
                            // Re-save all new transcripts (for Vosk they were saved incrementally,
                            // for Whisper they need to be saved here)
                            for (t in transcripts) {
                                if (t.episodeId.isNullOrBlank()) t.episodeId = episodeId
                                dbHelper.saveTranscript(t)
                            }
                        }
                        // [v2.4.12] Save the engine name used to generate subtitles
                        val engineName = if (currentModelName.isNotBlank()) currentModelName else "Unknown"
                        dbHelper.saveTranscriptEngine(episodeId, engineName)
                        logToFile("onComplete: [v2.4.12] saved engine name '$engineName' for $episodeId")
                        // [v2.4.31] Persist total processing time & audio duration so the speed
                        // ratio can be shown even when PlayerActivity restores from DB later.
                        dbHelper.saveTranscriptTiming(episodeId, currentProcessingTimeMs, currentAudioDurationMs)
                        logToFile("onComplete: [v2.4.31] saved timing for $episodeId: processingTime=${currentProcessingTimeMs}ms, audioDuration=${currentAudioDurationMs}ms")
                        logToFile("onComplete: [v2.2.6] ${if (isResumeMode) "appended" else "replaced with"} ${transcripts.size} new transcripts for $episodeId")
                        // [v2.4.18] Mark subtitles as complete so patrol skips this episode
                        dbHelper.markSubtitlesComplete(episodeId)
                        logToFile("onComplete: [v2.4.18] marked subtitles as COMPLETE for $episodeId")
                    } catch (e: Exception) {
                        logToFile("onComplete: [v2.2.6] failed to replace subtitles: ${e.message}")
                    }
                } else {
                    logToFile("onComplete: [v2.2.6] 0 transcripts generated, preserving existing subtitles for $episodeId")
                }
                callback.onComplete(transcripts)
                // [跨进程] 发送完成广播
                sendSubtitleBroadcast(
                    "com.radio.app.SUBTITLE_COMPLETE",
                    mapOf(
                        "episodeId" to episodeId,
                        "engineName" to (if (currentModelName.isNotBlank()) currentModelName else ""),
                        // [v2.4.31] Total processing time & audio duration for speed ratio display
                        "processingTimeMs" to currentProcessingTimeMs,
                        "audioDurationMs" to currentAudioDurationMs
                    )
                )
                if (!ctx.cancelled.get() && !globalCancelled.get()) {
                    updateProgressNotification(100, "字幕生成完成")
                }
            }
        }

        executor?.execute {
            var taskFailed = false
            try {
                if (ctx.cancelled.get() || globalCancelled.get()) {
                    ctx.log("Task cancelled before start")
                    return@execute
                }
                // [v2.4.31] Reset timing tracking for this subtitle task so a previous run's
                // values (or a Vosk run that doesn't set them) don't leak into the UI/DB.
                currentProcessingTimeMs = 0
                currentAudioDurationMs = 0

                // 根据ASR引擎设置选择模型
                // [v2.0.86] Issue 4 Fix: STRICTLY respect user's ASR selection - NO auto-switching.
                // If user selected Whisper and Whisper fails, report error and let user manually switch.
                // If user selected Vosk and Vosk fails, report error and let user manually switch.
                val settings = AppSettings.getInstance(this@SubtitleGeneratorService)
                // [v2.0.97] Reload ASR settings from SharedPreferences to get latest user selection.
                // This service runs in :subtitle process, whose AppSettings singleton is loaded once
                // at process start and never refreshed. Without this, ASR engine changes in UI
                // process don't take effect until the :subtitle process is restarted.
                settings.reloadAsrSettings(this@SubtitleGeneratorService)
                // [v2.4.10] If forceWhisperBaseModel is set (pre-cache subtitle generation),
                // override ASR provider to Whisper
                val asrProvider = if (forceWhisperBaseModel) {
                    ctx.log("ASR provider overridden to Whisper (forceWhisperBaseModel=true, pre-cache subtitle)")
                    logToFile("generateSubtitlesForEpisode: [v2.4.17] forceWhisperBaseModel=true, using Whisper tiny for pre-cache, episodeId=$episodeId")
                    AppSettings.ASR_WHISPER
                } else {
                    settings.safeAsrProvider()
                }
                val savedVoskDir = settings.voskModelDir
                ctx.log("ASR provider: $asrProvider (strict mode - NO auto-switch), savedVoskDir=$savedVoskDir")
                logToFile("generateSubtitlesForEpisode: [v2.0.86] ASR engine = $asrProvider (STRICT - no auto-fallback), savedVoskDir=$savedVoskDir, episodeId=$episodeId")
                logToFile("generateSubtitlesForEpisode: ASR engine selected = $asrProvider, savedVoskDir=$savedVoskDir, episodeId=$episodeId")

                when {
                    asrProvider == AppSettings.ASR_WHISPER || asrProvider == "whisper-local" -> {
                        // [v2.3.1] STRICT mode per user requirement: NO auto-switching to Vosk
                        // even after Whisper crashes. Always use Whisper when user selected Whisper.
                        // Reset crash count since we're attempting Whisper again.
                        if (settings.whisperCrashCount > 0) {
                            settings.whisperCrashCount = 0
                            settings.forceVoskUntil = 0
                            settings.save(this)
                            logToFile("generateSubtitlesForEpisode: [v2.3.1] resetting crash count, attempting Whisper as selected by user")
                        }
                        logToFile("generateSubtitlesForEpisode: entering Whisper branch (strict - no auto-fallback)")
                        val whisperModel = findWhisperModel()
                        if (whisperModel != null) {
                            // [v2.0.69] Issue 6: Broadcast model name from the very start
                            // [v2.4.2] Show friendly name "Whisper Tiny" instead of "ggml-tiny.bin"
                            val modelLabel = getFriendlyModelName(whisperModel)
                            currentModelName = modelLabel
                            sendSubtitleBroadcast("com.radio.app.SUBTITLE_MODEL_INFO", mapOf(
                                "episodeId" to episodeId,
                                "modelName" to modelLabel,
                                "engineType" to "whisper"
                            ))
                            logToFile("generateSubtitlesForEpisode: [v2.0.91] broadcasting model name from start: $modelLabel (Whisper)")
                            ctx.log("Using Whisper model: $whisperModel")
                            val success = generateWithWhisper(episodeId, audioUrl, wrappedCallback, ctx)
                            if (!success && !ctx.cancelled.get()) {
                                // [v2.3.0-fix] STRICT mode: NO auto-fallback to other engines.
                                // Per user requirement: "字幕生成失败时不允许跳转其他模型"
                                val failReason = ctx.lastErrorDetail ?: "Whisper引擎处理失败（无详细错误）"
                                ctx.log("Whisper subtitle generation FAILED. Reason: $failReason")
                                logToFile("generateSubtitlesForEpisode: [v2.3.0] Whisper FAILED, reason=$failReason. STRICT mode, NO fallback.")
                                wrappedCallback.onError("Whisper字幕生成失败：$failReason。请检查模型文件完整性，或在设置中手动切换ASR引擎后重试。")
                                activeTasks.remove(episodeId)
                                cleanupTask()
                            }
                        } else {
                            // [v2.3.0-fix] Whisper model not found - STRICT mode, NO fallback
                            val modelsDir = getExternalFilesDir("models")
                            val failReason = "Whisper模型文件未找到（已搜索路径：${modelsDir?.absolutePath}、${filesDir}/engines）"
                            ctx.lastErrorDetail = failReason
                            ctx.log("ERROR: $failReason")
                            logToFile("generateSubtitlesForEpisode: [v2.3.0] $failReason. STRICT mode, NO fallback.")
                            wrappedCallback.onError("$failReason。请在设置→离线引擎管理中下载Whisper模型，或手动切换到其他ASR引擎。")
                            activeTasks.remove(episodeId)
                            cleanupTask()
                        }
                    }
                    asrProvider == AppSettings.ASR_VOSK || asrProvider == "vosk-local" -> {
                        // [v2.0.91] Explicit Vosk branch - only entered when user selects Vosk
                        logToFile("generateSubtitlesForEpisode: entering Vosk branch (explicit)")
                        val voskModel = findVoskModel()
                        if (voskModel != null) {
                            // [v2.0.69] Issue 6: Broadcast model name from the very start
                            // [v2.4.2] Show friendly name "Vosk 小模型" instead of directory name
                            val modelLabel = getFriendlyModelName(voskModel)
                            currentModelName = modelLabel
                            sendSubtitleBroadcast("com.radio.app.SUBTITLE_MODEL_INFO", mapOf(
                                "episodeId" to episodeId,
                                "modelName" to modelLabel,
                                "engineType" to "vosk"
                            ))
                            logToFile("generateSubtitlesForEpisode: [v2.0.91] broadcasting model name from start: $modelLabel (Vosk)")
                            ctx.log("Using Vosk model: $voskModel")
                            val pcm16kCache = find16kHzPcmCache(episodeId)
                            if (pcm16kCache != null) {
                                ctx.log("Found 16kHz PCM cache for $episodeId: ${pcm16kCache.absolutePath}")
                            }
                            val success = generateWithVosk(episodeId, audioUrl, wrappedCallback, ctx)
                            if (!success && !ctx.cancelled.get()) {
                                ctx.log("Vosk subtitle generation FAILED (error already reported by generateWithVosk)")
                                logToFile("generateSubtitlesForEpisode: [v2.0.91] Vosk FAILED. NO auto-switch to Whisper per user requirement.")
                            }
                        } else {
                            // [v2.0.91] Vosk model not found - STRICT mode, NO fallback.
                            ctx.log("ERROR: Vosk model not found (no auto-switch to Whisper)")
                            logToFile("generateSubtitlesForEpisode: [v2.0.91] Vosk model not found. NO fallback to Whisper.")
                            wrappedCallback.onError("未找到Vosk模型，请在设置→离线引擎管理→下载Vosk模型（小模型约55MB）。")
                            activeTasks.remove(episodeId)
                            cleanupTask()
                        }
                    }
                    else -> {
                        // [v2.0.91] Non-local ASR provider selected (baidu/funasr/unknown) - subtitle generation
                        // only supports offline local engines. Report error and let user choose.
                        val providerName = when (asrProvider) {
                            AppSettings.ASR_BAIDU -> "百度在线ASR"
                            AppSettings.ASR_FUNASR -> "FunASR"
                            else -> "当前ASR引擎（$asrProvider）"
                        }
                        logToFile("generateSubtitlesForEpisode: [v2.0.91] Unsupported ASR provider '$asrProvider' for subtitle generation. Only Vosk/Whisper local engines are supported.")
                        wrappedCallback.onError("${providerName}不支持离线字幕生成。请在设置→ASR引擎中选择「Vosk离线」或「Whisper离线」后重试。")
                        activeTasks.remove(episodeId)
                        cleanupTask()
                    }
                }
            } catch (e: Exception) {
                taskFailed = true
                ctx.log("EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "Subtitle generation failed for episodeId=$episodeId", e)
                if (!ctx.cancelled.get()) wrappedCallback.onError("音频处理失败：${e.message}。请检查网络连接后重试")
                // Ensure task is cleaned up on error
                activeTasks.remove(episodeId)
                cleanupTask()
            } finally {
                activeTasks.remove(episodeId)
                ctx.log("Task ended${if (taskFailed) " (failed)" else ""}, active tasks remaining: ${activeTasks.size}")
                cleanupTask()
            }
        }
    }

    /**
     * Generate voice segments with independent per-task progress
     */
    fun generateSegmentsForEpisode(episodeId: String, audioUrl: String, callback: SegmentCallback) {
        val taskId = "seg_${episodeId}_${System.currentTimeMillis()}"
        val logFile = prepareTaskLogFile("segment", episodeId)
        val ctx = TaskContext(taskId, episodeId, "SEGMENT", logFile)
        // Segments use a different key "seg_$episodeId" to avoid conflict with subtitle task
        val segKey = "seg_$episodeId"
        if (activeTasks.putIfAbsent(segKey, ctx) != null) {
            Log.w(TAG, "Segment task already running for $episodeId, skipping duplicate")
            return
        }
        ctx.log("Starting segment generation, audioUrl=$audioUrl")

        // 包装回调，同时更新通知
        val wrappedCallback = object : SegmentCallback {
            override fun onSegmentGenerated(segment: VoiceSegment) {
                callback.onSegmentGenerated(segment)
            }
            override fun onProgressUpdate(progress: Int, total: Int) {
                callback.onProgressUpdate(progress, total)
                updateProgressNotification(progress, "AI分段")
                ctx.lastReportedProgress = progress
            }
            override fun onComplete(segments: List<VoiceSegment>) {
                callback.onComplete(segments)
                updateProgressNotification(100, "AI分段完成")
                // AI分段完成后关闭通知
                cleanupTask()
            }
            override fun onError(error: String) {
                callback.onError(error)
                // Ensure task is cleaned up on error: cancel ongoing notification immediately
                cancelProgressNotification()
                activeTasks.remove(segKey)
                cleanupTask()
            }
        }

        executor?.execute {
            var taskFailed = false
            try {
                if (ctx.cancelled.get() || globalCancelled.get()) {
                    ctx.log("Task cancelled before start")
                    return@execute
                }
                val allSegments = mutableListOf<VoiceSegment>()
                val subtitleCallback = object : SubtitleCallback {
                    override fun onSubtitleGenerated(transcript: Transcript) {
                        val segment = VoiceSegment(
                            start = transcript.segmentStart,
                            end = transcript.segmentEnd,
                            hasVoice = transcript.text?.isNotBlank() == true,
                            label = transcript.text
                        )
                        allSegments.add(segment)
                        wrappedCallback.onSegmentGenerated(segment)
                    }
                    override fun onProgressUpdate(progress: Int, total: Int) {
                        wrappedCallback.onProgressUpdate(progress, total)
                    }
                    override fun onError(error: String) {
                        ctx.log("Segment generation error: $error")
                        wrappedCallback.onError(error)
                    }
                    override fun onComplete(transcripts: List<Transcript>) {
                        ctx.log("Segment generation complete: ${allSegments.size} segments")
                        wrappedCallback.onComplete(allSegments)
                    }
                }

                // 根据ASR引擎设置选择模型
                val settings = AppSettings.getInstance(this@SubtitleGeneratorService)
                settings.reloadAsrSettings(this@SubtitleGeneratorService)  // [v2.0.97] Reload to get latest user selection
                val asrProvider = settings.safeAsrProvider()
                val savedVoskDir = settings.voskModelDir
                ctx.log("Segment ASR provider: $asrProvider")
                logToFile("generateSubtitlesForEpisode: ASR engine selected = $asrProvider, episodeId=$episodeId (segments)")

                when {
                    asrProvider == AppSettings.ASR_WHISPER || asrProvider == "whisper-local" -> {
                        logToFile("generateSubtitlesForEpisode: [v2.0.91] entering Whisper branch (segments)")
                        val whisperModel = findWhisperModel()
                        if (whisperModel != null) {
                            ctx.log("Using Whisper model for segments: $whisperModel")
                            val success = generateWithWhisper(episodeId, audioUrl, subtitleCallback, ctx)
                            if (!success && !ctx.cancelled.get()) {
                                ctx.log("Whisper segment generation FAILED")
                                logToFile("generateSubtitlesForEpisode: [v2.0.91] Whisper segments FAILED. NO auto-fallback.")
                            }
                        } else {
                            ctx.log("ERROR: Whisper model selected but not found for segments")
                            logToFile("generateSubtitlesForEpisode: [v2.0.91] Whisper model not found for segments. NO fallback.")
                            wrappedCallback.onError("Whisper引擎未安装：缺少ggml模型文件。请在设置→离线引擎管理→下载Whisper引擎（tiny版约75MB）。")
                            activeTasks.remove(segKey)
                            cleanupTask()
                        }
                    }
                    asrProvider == AppSettings.ASR_VOSK || asrProvider == "vosk-local" -> {
                        logToFile("generateSubtitlesForEpisode: [v2.0.91] entering Vosk branch (segments, explicit)")
                        val voskModel = findVoskModel()
                        if (voskModel != null) {
                            ctx.log("Using Vosk model for segments: $voskModel")
                            val pcm16kCache = find16kHzPcmCache(episodeId)
                            if (pcm16kCache != null) {
                                ctx.log("Found 16kHz PCM cache for segments $episodeId: ${pcm16kCache.absolutePath}")
                            }
                            val success = generateWithVosk(episodeId, audioUrl, subtitleCallback, ctx)
                            if (!success && !ctx.cancelled.get()) {
                                ctx.log("Vosk segment generation FAILED (error already reported by generateWithVosk)")
                                logToFile("generateSubtitlesForEpisode: [v2.0.91] Vosk segments FAILED. NO auto-fallback.")
                            }
                        } else {
                            ctx.log("ERROR: No Vosk model for segments")
                            logToFile("generateSubtitlesForEpisode: [v2.0.91] Vosk model not found for segments. NO fallback.")
                            wrappedCallback.onError("未找到Vosk模型，请在设置→离线引擎管理→下载Vosk模型（小模型约55MB）。")
                            activeTasks.remove(segKey)
                            cleanupTask()
                        }
                    }
                    else -> {
                        val providerName = when (asrProvider) {
                            AppSettings.ASR_BAIDU -> "百度在线ASR"
                            AppSettings.ASR_FUNASR -> "FunASR"
                            else -> "当前ASR引擎（$asrProvider）"
                        }
                        logToFile("generateSubtitlesForEpisode: [v2.0.91] Unsupported ASR provider '$asrProvider' for segments.")
                        wrappedCallback.onError("${providerName}不支持离线语音分段。请在设置→ASR引擎中选择「Vosk离线」或「Whisper离线」后重试。")
                        activeTasks.remove(segKey)
                        cleanupTask()
                    }
                }
            } catch (e: Exception) {
                taskFailed = true
                ctx.log("EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "Segment generation failed for episodeId=$episodeId", e)
                if (!ctx.cancelled.get()) wrappedCallback.onError("音频处理失败：${e.message}。请检查网络连接后重试")
                // Ensure task is cleaned up on error
                activeTasks.remove(segKey)
                cleanupTask()
            } finally {
                activeTasks.remove(segKey)
                ctx.log("Segment task ended${if (taskFailed) " (failed)" else ""}, active tasks remaining: ${activeTasks.size}")
                cleanupTask()
            }
        }
    }

    private fun prepareTaskLogFile(taskType: String, episodeId: String): File {
        val logDir = getExternalFilesDir("logs")
        if (logDir != null) {
            val subtitleDir = File(logDir, "subtitle")
            if (!subtitleDir.exists()) subtitleDir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(subtitleDir, "${taskType}_${Math.abs(episodeId.hashCode())}_$ts.log")
            if (!file.exists()) file.createNewFile()
            return file
        }
        // Fallback to cache dir
        val fallbackDir = File(cacheDir, "logs/subtitle")
        if (!fallbackDir.exists()) fallbackDir.mkdirs()
        return File(fallbackDir, "${taskType}_${Math.abs(episodeId.hashCode())}.log")
    }

    /**
     * Per-task monotonic progress reporting (no shared state!)
     */
    private fun reportProgress(callback: SubtitleCallback, progress: Int, total: Int, ctx: TaskContext) {
        if (progress >= ctx.lastReportedProgress) {
            ctx.lastReportedProgress = progress
            callback.onProgressUpdate(progress, total)
        } else {
            ctx.log("Progress regression prevented: $progress < ${ctx.lastReportedProgress}")
        }
    }

    // Vosk 类引用（通过反射加载，因为 .so 和 .jar 不再打包进 APK）
    private var voskModelClass: Class<*>? = null
    private var voskRecognizerClass: Class<*>? = null

    private fun ensureVoskClasses(): Boolean {
        if (voskModelClass != null && voskRecognizerClass != null) return true
        // Primary path: the Vosk Java classes (org.vosk.Model / org.vosk.Recognizer) are
        // bundled in the APK via the com.alphacephei:vosk-android dependency (only the
        // native .so is excluded via packagingOptions). So Class.forName works directly.
        try {
            voskModelClass = Class.forName("org.vosk.Model")
            voskRecognizerClass = Class.forName("org.vosk.Recognizer")
            logToFile("ensureVoskClasses: loaded Vosk classes from classpath")
            return true
        } catch (e: Exception) {
            logToFile("ensureVoskClasses: Vosk classes not in classpath, trying downloaded JAR: ${e.message}")
        }
        // Fallback: try loading from a downloaded Vosk JAR/AAR if one exists.
        try {
            val jarFile = findVoskJar()
            if (jarFile == null) {
                logToFile("ensureVoskClasses: no Vosk JAR found, classes unavailable")
                return false
            }
            logToFile("ensureVoskClasses: loading Vosk classes from JAR: ${jarFile.absolutePath}")
            val dexClassLoader = dalvik.system.DexClassLoader(
                jarFile.absolutePath,
                codeCacheDir.absolutePath,
                null,
                javaClass.classLoader
            )
            voskModelClass = dexClassLoader.loadClass("org.vosk.Model")
            voskRecognizerClass = dexClassLoader.loadClass("org.vosk.Recognizer")
            logToFile("ensureVoskClasses: loaded Vosk classes from JAR successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Vosk classes from JAR: ${e.message}")
            logToFile("ensureVoskClasses: failed to load Vosk classes from JAR: ${e.message}")
            return false
        }
    }

    /**
     * 搜索用户下载的 Vosk JAR/AAR 文件（包含 Java 类）。
     * 搜索路径与 loadVoskNativeLibrary 相同：getExternalFilesDir("models") 和 filesDir/engines。
     * @return 找到的 JAR/AAR 文件，或 null
     */
    private fun findVoskJar(): File? {
        val modelsDir = getExternalFilesDir("models")
        if (modelsDir != null && modelsDir.exists()) {
            val found = modelsDir.walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".jar") || it.name.endsWith(".aar")) && it.name.contains("vosk", ignoreCase = true) }
                .firstOrNull()
            if (found != null) return found
        }
        val engineDir = File(filesDir, "engines")
        if (engineDir.exists()) {
            val found = engineDir.walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".jar") || it.name.endsWith(".aar")) && it.name.contains("vosk", ignoreCase = true) }
                .firstOrNull()
            if (found != null) return found
        }
        return null
    }

    private fun generateWithVosk(
        episodeId: String, audioUrl: String, callback: SubtitleCallback, ctx: TaskContext
    ): Boolean {
        logToFile("generateWithVosk: START [v2.0.76], episodeId=$episodeId, audioUrl=$audioUrl")
        // [v2.0.76] Mark engine type for OOM detection, and clear Whisper OOM marker since Vosk works
        try {
            getSharedPreferences("subtitle_restart_guard", MODE_PRIVATE).edit()
                .putString("lastEngine", "vosk")
                .apply()
            // [v2.0.77] Issue 2 Fix: DO NOT clear Whisper OOM marker on Vosk start.
            // The OOM marker now uses a timestamp-based cooldown (10 min) and auto-expires.
            // Clearing it here could cause Whisper to be retried immediately after Vosk started,
            // leading to a loop if memory is still tight.
        } catch (_: Exception) {}
        val modelPath = findVoskModel()
        logToFile("generateWithVosk: voskModel=$modelPath")
        if (modelPath == null) {
            ctx.log("ERROR: No Vosk model found")
            callback.onError("Vosk模型未下载：缺少语音识别模型。请在设置→离线引擎管理→下载Vosk模型（约1.4GB）")
            return false
        }
        try {
            // 动态加载 libvosk.so（不再打包进 APK）
            val nativeLoaded = loadVoskNativeLibrary()
            logToFile("generateWithVosk: nativeLibraryLoaded=$nativeLoaded")
            if (!nativeLoaded) {
                ctx.log("ERROR: Failed to load Vosk native library (libvosk.so)")
                callback.onError("Vosk引擎未安装：缺少libvosk.so原生库。请在设置→离线引擎管理→下载Vosk引擎（约50MB）")
                return false
            }
            // 通过反射加载 Vosk 类（不再打包进 APK）
            val classesLoaded = ensureVoskClasses()
            logToFile("generateWithVosk: classesLoaded=$classesLoaded, voskModelClass=${voskModelClass != null}, voskRecognizerClass=${voskRecognizerClass != null}")
            if (!classesLoaded) {
                ctx.log("ERROR: Vosk classes not available (vosk JAR not in classpath)")
                callback.onError("Vosk引擎未安装：缺少Java库文件。请在设置→离线引擎管理→下载Vosk引擎")
                return false
            }

            // [v2.1.0] Use centralized cache dir
            val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this)
            val pcm16kFile = File(pcmCacheDir, "${episodeId}_5min.pcm")
            val minValidPcmBytes = 1024 * 1024  // [v2.0.67] At least ~30s of audio (was 1024 bytes, too small)
            val pcmValid = pcm16kFile.exists() && pcm16kFile.length() >= minValidPcmBytes
            logToFile("generateWithVosk: PCM file=${pcm16kFile.absolutePath}, size=${pcm16kFile.length()}, valid=${pcmValid}, minRequired=${minValidPcmBytes}")
            if (!pcmValid && pcm16kFile.exists()) {
                logToFile("generateWithVosk: PCM cache too small (${pcm16kFile.length()} bytes), deleting to regenerate")
                try { pcm16kFile.delete() } catch (_: Exception) {}
            }
            if (pcmValid) {
                val sizeMB = pcm16kFile.length() / 1024 / 1024
                ctx.log("[v2.0.67] Using chunked Vosk processing (PCM=${sizeMB}MB)")
                return processVoskInChunks(pcm16kFile, modelPath, callback, ctx)
            }

            // [v2.0.78] Issue 2-4 Fix: UNIFY both paths through processVoskInChunks.
            // v2.0.77 had a bug: when PCM cache was missing, it created the Model/Recognizer
            // BEFORE downloading audio (risking OOM for large models on low-memory devices),
            // then processed audio inline with wrong chunking logic producing garbage single-char output.
            // Fix: download audio first, save to PCM cache, then call processVoskInChunks (which has
            // proper memory checks, chunking, and partial-result handling).

            // [v2.0.78] Check model size/memory BEFORE downloading audio for large models.
            // This prevents the OOM-kill path for vosk-model-cn-0.22 (1.3GB).
            val modelFile = File(modelPath)
            val modelSizeMB = calculateDirSize(modelFile) / 1024 / 1024
            val nameSuggestsLarge = modelFile.name.contains("cn-0.22") && !modelFile.name.contains("small")
            val isLargeModel = nameSuggestsLarge || modelSizeMB > 200
            val actManager = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            val totalMemMB = memInfo.totalMem / 1024 / 1024
            val availMemMB = memInfo.availMem / 1024 / 1024
            val maxHeapMB = Runtime.getRuntime().maxMemory() / 1024 / 1024
            logToFile("generateWithVosk: [v2.0.80] model=${modelSizeMB}MB, isLarge=$isLargeModel, totalMem=${totalMemMB}MB, avail=${availMemMB}MB, maxHeap=${maxHeapMB}MB, lowRam=${memInfo.lowMemory}")
            if (isLargeModel && totalMemMB < 2048) {
                val detail = "Vosk大模型(${modelSizeMB}MB)需要2GB以上总内存（当前${totalMemMB}MB）"
                ctx.lastErrorDetail = detail
                logToFile("generateWithVosk: [v2.0.80] BLOCKING large model (low total RAM): $detail")
                callback.onError("$detail 请使用小模型(vosk-model-small-cn-0.22)。")
                return false
            }
            // [v2.0.80] Issue 4 Fix: Raised threshold from modelSizeMB+300 to modelSizeMB+1500.
            // v2.0.79: avail=2367MB passed check (2044+300=2344 < 2367) but Model.<init> crashed.
            // Vosk large model (vosk-model-cn-0.22, ~1.3GB on disk) needs ~3.5GB native memory
            // for mmap graph + HMM + decoder runtime. modelSizeMB estimate (2044MB) + 1500MB buffer
            // = 3544MB required avail. This will correctly block on devices with < 3.5GB free.
            if (isLargeModel && availMemMB < modelSizeMB + 1500) {
                val detail = "Vosk大模型加载需要${modelSizeMB + 1500}MB可用内存（当前${availMemMB}MB）。大模型(vosk-model-cn-0.22)需要约3.5GB空闲内存才能稳定加载。"
                ctx.lastErrorDetail = detail
                logToFile("generateWithVosk: [v2.0.80] BLOCKING large model (insufficient avail mem): $detail")
                callback.onError("$detail 请关闭其他应用释放内存后重试，或使用小模型(vosk-model-small-cn-0.22)。")
                return false
            }

            // Get audio data - download/process first, then write to cache, then use chunked path
            logToFile("generateWithVosk: [v2.0.78] calling getAudioDataForProcessing episodeId=$episodeId")
            val audioData = getAudioDataForProcessing(episodeId, audioUrl, ctx)
            logToFile("generateWithVosk: [v2.0.78] getAudioDataForProcessing returned audioData=${audioData?.size ?: -1} bytes")
            if (audioData == null || audioData.isEmpty()) {
                callback.onError("音频处理失败：无法获取音频数据。请检查网络连接后重试")
                return false
            }

            // Save audio data to PCM cache for future reuse AND for unified chunked processing
            try {
                pcmCacheDir.mkdirs()
                // Write the downloaded PCM data to cache file (should already be 16kHz mono)
                java.io.FileOutputStream(pcm16kFile).use { fos ->
                    fos.write(audioData)
                    fos.flush()
                }
                logToFile("generateWithVosk: [v2.0.78] Saved PCM cache: ${pcm16kFile.absolutePath}, size=${pcm16kFile.length()} bytes (${audioData.size/32000}s @ 16kHz mono)")
                ctx.log("Saved ${audioData.size/1024}KB PCM to cache, starting chunked Vosk processing...")
            } catch (e: Exception) {
                logToFile("generateWithVosk: [v2.0.78] Failed to write PCM cache: ${e.message}")
                // Continue anyway - we still have audioData in memory, but chunked path needs file
                callback.onError("PCM缓存写入失败: ${e.message}")
                return false
            }

            // Now use the unified chunked processing path (proper memory checks, chunking, partial results)
            return processVoskInChunks(pcm16kFile, modelPath, callback, ctx)
        } catch (e: Exception) {
            logToFile("generateWithVosk: EXCEPTION: ${e.javaClass.name}: ${e.message}")
            e.stackTrace.take(5).forEach { logToFile("generateWithVosk: at $it") }
            callback.onError("Vosk生成失败: ${e.message}")
            return false
        } catch (e: UnsatisfiedLinkError) {
            logToFile("generateWithVosk: UnsatisfiedLinkError: ${e.message}")
            callback.onError("Vosk原生库加载失败: ${e.message}")
            return false
        }
    }

    /**
     * 分块处理大PCM文件（16kHz mono），每5秒一个chunk，避免OOM
     */
    // [v2.0.60] Helper: recursively compute total size (bytes) of a directory tree.
    // Used to estimate Vosk model memory footprint before loading it into native heap.
    private fun calculateDirSize(dir: java.io.File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }

    private fun processVoskInChunks(
        pcmFile: File, modelPath: String, callback: SubtitleCallback, ctx: TaskContext
    ): Boolean {
        logToFile("processVoskInChunks: START, pcmFile=${pcmFile.absolutePath}, size=${pcmFile.length()}")
        val totalSize = pcmFile.length()
        // [v2.0.54] Issue 4: Process 15-20 min range to avoid background music in first 5 min
        // [v2.0.65] Issue 7 Fix: PCM now starts from 15 min (decodeToPcm seeks to 15 min).
        // No need to skip 15 min of PCM data - it's already 15-20 min range.
        // Always add 15-min offset to timestamps.
        val skipped15Min = true  // PCM always starts from 15 min now
        val startOffsetBytes = 0L  // No skip needed in PCM
        val maxBytes = 5L * 60 * 16000 * 2  // 5 minutes
        val processLimit = minOf(totalSize, maxBytes)
        logToFile("processVoskInChunks: [v2.0.55] totalSize=${totalSize / 1024}KB, skipped15Min=$skipped15Min, offset=${if (skipped15Min) startOffsetBytes / 1024 else 0}KB, limit=${processLimit / 1024}KB (5min)")
        ctx.log("processVoskInChunks: PCM file size=${totalSize / 1024 / 1024}MB, processing ${if (skipped15Min) "15-20min" else "0-5min"} range in chunks")
        callback.onProgressUpdate(0, 100)

        val fileInputStream = java.io.FileInputStream(pcmFile)
        val inputStream = java.io.DataInputStream(fileInputStream)
        // [v2.3.0] Optimize Vosk for accuracy (allowing slower speed): increase chunk to 5s for more context,
        // reduce partial query frequency to lower CPU, use very conservative endpoint rules.
        // Chunk size history:
        //   - 2.0s (64000) in v2.0.89-v2.2.9: decent speed but partials unstable, high CPU from frequent JSON parsing
        //   - 5.0s (160000) in v2.3.0: more audio context per call = better decoding accuracy,
        //     fewer JNI/JSON calls = lower CPU overhead per second, acceptable for offline processing.
        val chunkSize = 160000   // 5.0 seconds = 80000 samples = 160000 bytes
        val buffer = ByteArray(chunkSize)
        var offset = 0L  // [v2.0.54] offset relative to the 15-min mark
        var lastProgress = 0
        var chunkCount = 0
        var acceptTrueCount = 0
        var acceptFalseCount = 0
        var lastPartialText = ""
        // [v2.3.0] For accuracy: only query partial every N chunks (reduces CPU, avoids unstable intermediate results)
        val partialQueryInterval = 2  // query getPartialResult() every 2 chunks = every 10s
        // [v2.3.0] More conservative partial emission for stability
        var lastPartialEmitTime = -3000L  // Initialize to allow first partial after warmup
        var lastFinalEmitTime = 0L
        var lastForceEmitTime = 0L
        // Issue 9: Dedicated Vosk log - log start parameters
        writeVoskLog("processVoskInChunks START [v2.3.0-accuracy]: modelPath=$modelPath, chunkSize=$chunkSize (${chunkSize/32}ms), partialQueryInterval=$partialQueryInterval, totalSize=$totalSize, processLimit=$processLimit")

        var recognizer: Any? = null
        var model: Any? = null
        try {
            // Initialize Vosk recognizer
            val nativeLoaded = loadVoskNativeLibrary()
            logToFile("processVoskInChunks: nativeLibraryLoaded=$nativeLoaded")
            if (!nativeLoaded) {
                val detail = "Vosk原生库(libvosk.so)未加载"
                ctx.lastErrorDetail = detail
                ctx.log("ERROR: $detail")
                logToFile("processVoskInChunks: [v2.0.76] FAILED: $detail")
                callback.onError("$detail 请在设置→离线引擎管理→下载Vosk引擎（约50MB）。")
                return false
            }
            val classesLoaded = ensureVoskClasses()
            logToFile("processVoskInChunks: classesLoaded, voskModelClass=${voskModelClass != null}, voskRecognizerClass=${voskRecognizerClass != null}")
            if (!classesLoaded) {
                val detail = "Vosk Java类未加载(vosk.jar可能缺失)"
                ctx.lastErrorDetail = detail
                ctx.log("ERROR: $detail")
                logToFile("processVoskInChunks: [v2.0.76] FAILED: $detail")
                callback.onError("$detail 请在设置→离线引擎管理→下载Vosk引擎。")
                return false
            }

            logToFile("processVoskInChunks: creating Vosk Model with path=$modelPath")
            logToFile("processVoskInChunks: model dir exists=${File(modelPath).exists()}, contents=${File(modelPath).list()?.toList()}")
            // [v2.0.60] Issue 3+4 Fix: Check memory before loading large Vosk model.
            // org.vosk.Model loads the entire model into native memory; a 1.3GB model on a
            // memory-constrained :subtitle process causes a silent OOM that kills the process
            // before the Kotlin try-catch can report it. Pre-check and bail out gracefully.
            val modelDir = java.io.File(modelPath)
            val modelSizeMB = calculateDirSize(modelDir) / 1024 / 1024
            val maxHeapMB = Runtime.getRuntime().maxMemory() / 1024 / 1024
            val freeMemMB = Runtime.getRuntime().freeMemory() / 1024 / 1024
            // [v2.0.75] Issue 4 Fix: Use BOTH name pattern AND model size to detect large models.
            // v2.0.74 only checked name "cn-0.22" without "small", but model size is more reliable:
            // - vosk-model-small-cn-0.22: ~40MB
            // - vosk-model-cn-0.22 (large): ~1.3GB
            // Any model >200MB is considered a large model requiring more memory.
            val nameSuggestsLarge = modelDir.name.contains("cn-0.22") && !modelDir.name.contains("small")
            val isLargeModel = nameSuggestsLarge || modelSizeMB > 200
            val actManager = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            val totalMemMB = memInfo.totalMem / 1024 / 1024
            val availMemMB = memInfo.availMem / 1024 / 1024
            logToFile("processVoskInChunks: [v2.0.80] model=${modelSizeMB}MB, nameSuggestsLarge=$nameSuggestsLarge, isLarge=$isLargeModel, maxHeap=${maxHeapMB}MB, freeMem=${freeMemMB}MB, totalDevice=${totalMemMB}MB, avail=${availMemMB}MB, lowRam=${memInfo.lowMemory}")
            // [v2.0.75] Issue 4 Fix: Lower total RAM threshold from 3GB to 2GB.
            // Many modern phones with 2-3GB RAM can run the large model if enough memory is available.
            // The availMem check below is the real gatekeeper; totalMem is just a quick reject.
            if (isLargeModel && totalMemMB < 2048) {
                val detail = "Vosk大模型(${modelSizeMB}MB)需要2GB以上总内存（当前${totalMemMB}MB）"
                ctx.lastErrorDetail = detail
                logToFile("processVoskInChunks: [v2.0.80] BLOCKING large model load (low total RAM): $detail")
                ctx.log("ERROR: $detail")
                callback.onError("$detail 请使用小模型(vosk-model-small-cn-0.22)。")
                sendSubtitleBroadcast("com.radio.app.SUBTITLE_ERROR", mapOf(
                    "episodeId" to ctx.episodeId, "message" to detail))
                return false
            }
            // [v2.0.80] Issue 4 Fix: Raised from modelSizeMB+300 to modelSizeMB+1500 (same as generateWithVosk)
            if (isLargeModel && availMemMB < modelSizeMB + 1500) {
                val detail = "Vosk大模型加载需要${modelSizeMB + 1500}MB可用内存（当前${availMemMB}MB）。大模型需要约3.5GB空闲内存才能稳定加载。"
                ctx.lastErrorDetail = detail
                logToFile("processVoskInChunks: [v2.0.80] BLOCKING large model load (insufficient avail mem): $detail")
                ctx.log("ERROR: $detail")
                callback.onError("$detail 请关闭其他应用释放内存后重试，或使用小模型(vosk-model-small-cn-0.22)。")
                sendSubtitleBroadcast("com.radio.app.SUBTITLE_ERROR", mapOf(
                    "episodeId" to ctx.episodeId, "message" to detail))
                return false
            }
            logToFile("processVoskInChunks: [v2.0.75] Running GC before model loading, freeMem=${Runtime.getRuntime().freeMemory() / 1024 / 1024}MB")
            System.gc()
            Thread.sleep(200)

            try {
                // [v2.0.65] Issue 4 Fix: Check model directory structure before loading
                val amDir = File(modelDir, "am")
                val graphDir = File(modelDir, "graph")
                val confFile = File(modelDir, "conf")
                if (!amDir.exists() || !graphDir.exists()) {
                    val detail = "Vosk模型目录不完整(${modelDir.name}缺少am/或graph/子目录)"
                    ctx.lastErrorDetail = detail
                    logToFile("processVoskInChunks: [v2.0.76] $detail")
                    callback.onError("$detail 请重新下载模型。")
                    return false
                }
                // [v2.0.70] Issue 4 Fix: Deep check critical model files
                val graphFiles = graphDir.listFiles()?.toList() ?: emptyList()
                val amFiles = amDir.listFiles()?.toList() ?: emptyList()
                val graphSize = graphFiles.sumOf { it.length() }
                val amSize = amFiles.sumOf { it.length() }
                logToFile("processVoskInChunks: [v2.0.70] Model dir check: am=${amDir.exists()} (${amFiles.size} files, ${amSize/1024/1024}MB), graph=${graphDir.exists()} (${graphFiles.size} files, ${graphSize/1024/1024}MB), conf=${confFile.exists()}")
                if (graphSize < 1024 || amSize < 1024) {
                    val detail = "Vosk模型文件损坏(${modelDir.name} graph=${graphSize}B am=${amSize}B)"
                    ctx.lastErrorDetail = detail
                    logToFile("processVoskInChunks: [v2.0.76] $detail")
                    callback.onError("$detail 请重新下载模型。")
                    sendSubtitleBroadcast("com.radio.app.SUBTITLE_ERROR", mapOf(
                        "episodeId" to ctx.episodeId, "message" to detail))
                    return false
                }
                logToFile("processVoskInChunks: [v2.0.65] Model dir check passed: am=${amDir.exists()}, graph=${graphDir.exists()}, conf=${confFile.exists()}")

                // [v2.3.0] Optimize for accuracy: use 60s trailing silence for ALL endpoint rules.
                // This allows Vosk to accumulate very long speech segments (sentences/paragraphs)
                // before committing a final result, dramatically improving context for the
                // language model and reducing word errors from premature segmentation.
                try {
                    val modelConf = File(confFile, "model.conf")
                    val originalContent = if (modelConf.exists()) modelConf.readText() else ""
                    logToFile("processVoskInChunks: [v2.3.0] Original model.conf: ${originalContent.take(300)}")
                    // Append endpoint configuration with very long silence thresholds for ALL rules
                    val endpointRules = """
                        --endpoint.rule1.min-trailing-silence=60.0
                        --endpoint.rule2.min-trailing-silence=60.0
                        --endpoint.rule3.min-trailing-silence=60.0
                        --endpoint.rule4.min-trailing-silence=60.0
                    """.trimIndent()
                    val modifiedContent = if (originalContent.isBlank()) {
                        endpointRules
                    } else if (!originalContent.contains("endpoint.rule1.min-trailing-silence")) {
                        // Add rule1 + replace existing rule2/3/4
                        val withRule1 = originalContent.trimEnd() + "\n--endpoint.rule1.min-trailing-silence=60.0"
                        withRule1
                            .replace(Regex("--endpoint\\.rule2\\.min-trailing-silence=\\S+"), "--endpoint.rule2.min-trailing-silence=60.0")
                            .replace(Regex("--endpoint\\.rule3\\.min-trailing-silence=\\S+"), "--endpoint.rule3.min-trailing-silence=60.0")
                            .replace(Regex("--endpoint\\.rule4\\.min-trailing-silence=\\S+"), "--endpoint.rule4.min-trailing-silence=60.0")
                    } else {
                        // All rules already present, just replace values
                        originalContent
                            .replace(Regex("--endpoint\\.rule1\\.min-trailing-silence=\\S+"), "--endpoint.rule1.min-trailing-silence=60.0")
                            .replace(Regex("--endpoint\\.rule2\\.min-trailing-silence=\\S+"), "--endpoint.rule2.min-trailing-silence=60.0")
                            .replace(Regex("--endpoint\\.rule3\\.min-trailing-silence=\\S+"), "--endpoint.rule3.min-trailing-silence=60.0")
                            .replace(Regex("--endpoint\\.rule4\\.min-trailing-silence=\\S+"), "--endpoint.rule4.min-trailing-silence=60.0")
                    }
                    if (modifiedContent != originalContent) {
                        modelConf.writeText(modifiedContent)
                        logToFile("processVoskInChunks: [v2.3.0] Modified model.conf: ${modifiedContent.take(300)}")
                    }
                } catch (e: Exception) {
                    logToFile("processVoskInChunks: [v2.3.0] model.conf modification failed: ${e.message}")
                }

                try {
                    model = voskModelClass!!.getConstructor(String::class.java).newInstance(modelPath)
                } catch (me: java.lang.reflect.InvocationTargetException) {
                    val cause = me.cause
                    val isLargeModelError = nameSuggestsLarge || modelSizeMB > 200
                    val detail = if (isLargeModelError) {
                        "Vosk大模型(${modelSizeMB}MB)加载失败(${cause?.javaClass?.simpleName}: ${cause?.message ?: me.message})"
                    } else {
                        "Vosk模型加载失败(${cause?.javaClass?.simpleName}: ${cause?.message ?: me.message})"
                    }
                    ctx.lastErrorDetail = detail
                    logToFile("processVoskInChunks: [v2.0.76] Model constructor FAILED: $detail")
                    ctx.log("ERROR: $detail")
                    callback.onError("$detail 请重新下载模型或使用小模型。")
                    sendSubtitleBroadcast("com.radio.app.SUBTITLE_ERROR", mapOf(
                        "episodeId" to ctx.episodeId, "message" to detail))
                    return false
                }
                logToFile("processVoskInChunks: Vosk model path=$modelPath, exists=${modelDir.exists()}, size=${if (modelDir.exists()) modelDir.walkTopDown().map { it.length() }.sum() / 1024 / 1024 else 0}MB")
                logToFile("processVoskInChunks: Model created successfully")
                writeVoskLog("Vosk Model created: path=$modelPath, size=${if (modelDir.exists()) modelDir.walkTopDown().map { it.length() }.sum() / 1024 / 1024 else 0}MB")
                recognizer = voskRecognizerClass!!.getConstructor(voskModelClass, Float::class.javaPrimitiveType)
                    .newInstance(model, 16000.0f as java.lang.Float)
                logToFile("processVoskInChunks: Recognizer created successfully")
                writeVoskLog("Vosk Recognizer created: sampleRate=16000.0, setWords=true")
                // Enable word-level output for timestamps
                try {
                    val setWordsMethod = voskRecognizerClass!!.getMethod("setWords", Boolean::class.javaPrimitiveType)
                    setWordsMethod.invoke(recognizer, true)
                    logToFile("processVoskInChunks: setWords(true) called")
                } catch (e: Exception) {
                    logToFile("processVoskInChunks: setWords failed: ${e.message}")
                }
                // [v2.0.66] Issue 3 Fix: Enable partial word output for dense partial results
                try {
                    val setPartialWordsMethod = voskRecognizerClass!!.getMethod("setPartialWords", Boolean::class.javaPrimitiveType)
                    setPartialWordsMethod.invoke(recognizer, true)
                    logToFile("processVoskInChunks: setPartialWords(true) called")
                } catch (e: Exception) {
                    logToFile("processVoskInChunks: setPartialWords failed: ${e.message}")
                }
                // [v2.0.66] Issue 4 Fix: Disable Vosk internal logging to reduce memory usage on large models
                try {
                    val setLogLevelMethod = voskRecognizerClass!!.getMethod("setLogLevel", Int::class.javaPrimitiveType)
                    setLogLevelMethod.invoke(recognizer, -1)
                    logToFile("processVoskInChunks: setLogLevel(-1) called")
                } catch (e: Exception) {
                    logToFile("processVoskInChunks: setLogLevel failed: ${e.message}")
                }
                // [v2.3.0] Optimize for accuracy: Try setEndpointerMode to VERY_LONG (3) first,
                // falling back to LONG (2). VERY_LONG mode maximizes context before endpoint detection,
                // giving the decoder more speech history to disambiguate words.
                try {
                    // Try int parameter version first - VERY_LONG=3
                    val setEpMethod = voskRecognizerClass!!.getMethod("setEndpointerMode", Int::class.javaPrimitiveType)
                    setEpMethod.invoke(recognizer, 3)  // 3 = VERY_LONG
                    logToFile("processVoskInChunks: [v2.3.0] setEndpointerMode(3=VERY_LONG) called via int param")
                } catch (e: NoSuchMethodException) {
                    // Try enum parameter version
                    try {
                        val epModeClass = Class.forName("org.vosk.android.EndpointerMode")
                        val setEpMethod = voskRecognizerClass!!.getMethod("setEndpointerMode", epModeClass)
                        val veryLongMode = epModeClass.enumConstants?.find { it.toString() == "VERY_LONG" }
                            ?: epModeClass.enumConstants?.find { it.toString() == "LONG" }
                        if (veryLongMode != null) {
                            setEpMethod.invoke(recognizer, veryLongMode)
                            logToFile("processVoskInChunks: [v2.3.0] setEndpointerMode(${veryLongMode}) called via enum param")
                        } else {
                            logToFile("processVoskInChunks: [v2.3.0] EndpointerMode.VERY_LONG/LONG not found in enum: ${epModeClass.enumConstants?.map { it.toString() }}")
                        }
                    } catch (e2: Exception) {
                        logToFile("processVoskInChunks: [v2.3.0] setEndpointerMode not available (int nor enum), relying on endpoint.conf: ${e2.message}")
                    }
                } catch (e: Exception) {
                    // If VERY_LONG (3) fails, try LONG (2)
                    try {
                        val setEpMethod = voskRecognizerClass!!.getMethod("setEndpointerMode", Int::class.javaPrimitiveType)
                        setEpMethod.invoke(recognizer, 2)  // 2 = LONG fallback
                        logToFile("processVoskInChunks: [v2.3.0] setEndpointerMode(3) failed, fell back to 2=LONG: ${e.message}")
                    } catch (e2: Exception) {
                        logToFile("processVoskInChunks: [v2.3.0] setEndpointerMode failed entirely: ${e2.message}")
                    }
                }
                // [v2.0.66] Issue 6 Fix: Extract model name for display
                val modelName = modelDir.name
                currentModelName = modelName  // [v2.0.66] Issue 6: Set for broadcast
                logToFile("processVoskInChunks: [v2.0.66] using model: $modelName")
                logToFile("processVoskInChunks: Recognizer methods: ${voskRecognizerClass!!.declaredMethods.map { "${it.name}(${it.parameterTypes.map { p -> p.simpleName }})" }}")
            } catch (oom: OutOfMemoryError) {
                val detail = "Vosk加载模型时内存不足(OutOfMemoryError: ${oom.message})"
                ctx.lastErrorDetail = detail
                logToFile("processVoskInChunks: [v2.0.76] $detail")
                callback.onError("$detail 请使用小模型或关闭其他应用。")
                sendSubtitleBroadcast("com.radio.app.SUBTITLE_ERROR", mapOf(
                    "episodeId" to ctx.episodeId, "message" to detail))
                return false
            } catch (e: Exception) {
                val detail = when {
                    e.message?.contains("Failed to create a model") == true ->
                        "Vosk模型创建失败(模型可能损坏或内存不足)"
                    e is OutOfMemoryError -> "Vosk加载模型时内存不足"
                    else -> "Vosk模型初始化失败(${e.javaClass.simpleName}: ${e.message})"
                }
                ctx.lastErrorDetail = detail
                logToFile("processVoskInChunks: [v2.0.76] FAILED to create Model/Recognizer: $detail")
                e.stackTrace.take(10).forEach { logToFile("processVoskInChunks: at $it") }
                callback.onError("$detail 请尝试重新下载模型或使用小模型。")
                sendSubtitleBroadcast("com.radio.app.SUBTITLE_ERROR", mapOf(
                    "episodeId" to ctx.episodeId, "message" to detail))
                return false
            } catch (e: UnsatisfiedLinkError) {
                val detail = "Vosk原生方法调用失败(${e.message})"
                ctx.lastErrorDetail = detail
                logToFile("processVoskInChunks: [v2.0.76] UnsatisfiedLinkError: $detail")
                callback.onError("$detail 请重新安装Vosk引擎。")
                sendSubtitleBroadcast("com.radio.app.SUBTITLE_ERROR", mapOf(
                    "episodeId" to ctx.episodeId, "message" to detail))
                return false
            }
            ctx.log("Vosk recognizer created for chunked processing")

            val acceptWaveFormMethod = voskRecognizerClass!!.getMethod("acceptWaveForm", ByteArray::class.java, Int::class.javaPrimitiveType)
            val getResultMethod = voskRecognizerClass!!.getMethod("getResult")
            val getPartialResultMethod = voskRecognizerClass!!.getMethod("getPartialResult")
            val getFinalResultMethod = voskRecognizerClass!!.getMethod("getFinalResult")
            // [v2.0.94] Get reset() method for explicit state clearing after endpoint detection
            val resetMethod = voskRecognizerClass!!.getMethod("reset")

            // Issue 7: Verify PCM format - read first bytes for debug, then continue from current position
            logToFile("processVoskInChunks: PCM file size=${totalSize} bytes, expected duration=${totalSize / (16000 * 2)}s, sampleRate=16000, channels=1, bitsPerSample=16")
            val firstChunk = ByteArray(32)
            val firstBytesRead = inputStream.read(firstChunk)
            logToFile("processVoskInChunks: first 32 bytes (hex): ${firstChunk.joinToString(" ") { String.format("%02x", it) }}")
            // [v2.0.62] Issue 3 Fix: DO NOT reset stream position! The stream is already at the correct
            // processing position (after skip + firstChunk read). The previous channel.position(0) bug
            // caused processing to start from file beginning instead of the 15-min offset, processing
            // wrong audio segment (usually music/intro with no speech → no output).
            // The 32 bytes consumed by firstChunk is negligible (1ms of audio).

            // Issue 8: Track time from processing start to first transcript
            val processingStartTime = System.currentTimeMillis()

            val allTranscripts = mutableListOf<com.radio.app.models.Transcript>()
            while (!ctx.cancelled.get() && offset < processLimit) {
                // Issue 9: Use readFully to ensure full chunk is read (FileInputStream.read may return fewer bytes)
                val bytesToRead = minOf(chunkSize.toLong(), processLimit - offset).toInt()
                if (bytesToRead <= 0) break
                val chunk: ByteArray
                if (bytesToRead == chunkSize) {
                    try {
                        inputStream.readFully(buffer, 0, bytesToRead)
                        chunk = buffer
                    } catch (_: java.io.EOFException) {
                        break
                    }
                } else {
                    // Last partial chunk
                    val partialRead = inputStream.read(buffer, 0, bytesToRead)
                    if (partialRead <= 0) break
                    chunk = buffer.copyOf(partialRead)
                }
                val bytesRead = if (bytesToRead == chunkSize) chunkSize else chunk.size

                val acceptResult = acceptWaveFormMethod.invoke(recognizer, chunk, chunk.size) as? Boolean ?: false
                if (acceptResult) acceptTrueCount++ else acceptFalseCount++
                chunkCount++
                // [v2.3.0] currentTimeMs uses chunk END position (offset + bytesRead) for accuracy
                val currentTimeMs = (offset + bytesRead) * 1000L / 32000L
                // [v2.3.0] Log every 10 chunks = 50s at 5s/chunk
                if (chunkCount % 10 == 0) {
                    logToFile("processVoskInChunks: [v2.3.0] chunk=$chunkCount, offset=$offset (${currentTimeMs}ms), accept=$acceptResult (T=$acceptTrueCount/F=$acceptFalseCount), transcripts=${allTranscripts.size}")
                }

                // [v2.0.67] Issue 3 Fix: Only call getResult() when acceptWaveForm=true (silence boundary).
                if (acceptResult) {
                    val result = getResultMethod.invoke(recognizer) as? String ?: ""
                    writeVoskLog("processVoskInChunks: [v2.3.0] acceptWaveForm=true at chunk $chunkCount, offset=$offset, result='${result.take(200)}'")
                    logToFile("processVoskInChunks: raw result: '${result.take(200)}'")
                    // ALWAYS reset partial state when acceptWaveForm returns true,
                    // because Vosk resets its internal buffer regardless of whether result text is empty.
                    lastPartialText = ""
                    lastForceEmitTime = currentTimeMs
                    lastPartialEmitTime = currentTimeMs - 1000  // allow new partial after 1s cooldown
                    if (result.isNotBlank()) {
                        try {
                            val json = org.json.JSONObject(result)
                            val text = json.optString("text", "").trim()
                            if (text.isNotBlank()) {
                                val offsetMs = if (skipped15Min) 15L * 60 * 1000 else 0L
                                var startTime = offsetMs
                                var endTime = offsetMs
                                val resultArr = json.optJSONArray("result")
                                if (resultArr != null && resultArr.length() > 0) {
                                    val firstWord = resultArr.getJSONObject(0)
                                    val lastWord = resultArr.getJSONObject(resultArr.length() - 1)
                                    startTime = offsetMs + (firstWord.optDouble("start", 0.0) * 1000).toLong()
                                    endTime = offsetMs + (lastWord.optDouble("end", 0.0) * 1000).toLong()
                                } else {
                                    startTime = offsetMs + offset * 1000L / 32000L
                                    endTime = offsetMs + (offset + chunk.size) * 1000L / 32000L
                                }
                                val transcript = com.radio.app.models.Transcript(text = text, segmentStart = startTime, segmentEnd = endTime)
                                logToFile("processVoskInChunks: [v2.3.0] FINAL transcript #${allTranscripts.size + 1}: start=${startTime}ms, end=${endTime}ms, text='${text.take(80)}'")
                                allTranscripts.add(transcript)
                                if (allTranscripts.size == 1) {
                                    val firstResultTime = System.currentTimeMillis() - processingStartTime
                                    logToFile("processVoskInChunks: FIRST transcript at ${firstResultTime}ms from processing start")
                                }
                                callback.onSubtitleGenerated(transcript)
                                lastFinalEmitTime = currentTimeMs
                            }
                        } catch (_: Exception) { /* skip malformed JSON */ }
                    }
                    // [v2.3.0] Remove partial-after-final emission: it was often inaccurate (e.g. "九九" instead of "就像")
                    // and duplicated the just-emitted final result with errors. Final results from getResult() are
                    // the authoritative output; partial-after-final adds noise without value.
                    // [v2.0.94] Explicitly call reset() after endpoint detection to clear internal state
                    try { resetMethod.invoke(recognizer) } catch (_: Exception) { }
                } else if (chunkCount % partialQueryInterval == 0) {
                    // [v2.3.0] CPU optimization: only query partial every N chunks to reduce JSON parsing overhead.
                    // With 5s chunks and interval=2, this means one partial query every 10s instead of every 5s.
                    val partial = getPartialResultMethod.invoke(recognizer) as? String ?: ""
                    if (chunkCount % 10 == 0) {
                        logToFile("processVoskInChunks: [v2.3.0] chunk=$chunkCount, rawPartial='${partial.take(100)}', accepted=$acceptResult, timeMs=$currentTimeMs, lastPartialEmit=$lastPartialEmitTime, lastForceEmit=$lastForceEmitTime")
                    }
                    if (partial.isNotBlank()) {
                        try {
                            val partialJson = org.json.JSONObject(partial)
                            val partialText = partialJson.optString("partial", "").trim()
                            if (chunkCount % 10 == 0) {
                                logToFile("processVoskInChunks: [v2.3.0] chunk=$chunkCount, partialText='$partialText', lastEmittedPartial='$lastPartialText', len=${partialText.length}")
                            }
                            // [v2.3.0] More conservative partial emit rules for accuracy:
                            // - Require minimum 2 Chinese chars (6 bytes in UTF-8, ~2 chars)
                            // - Emit only when text has changed AND at least 1000ms since last partial
                            // - Force emit every 3000ms for user feedback (slower but more stable)
                            val shouldEmit = partialText.length >= 2 && (
                                (partialText != lastPartialText && currentTimeMs - lastPartialEmitTime >= 1000) ||
                                (currentTimeMs - lastForceEmitTime >= 3000)
                            )
                            if (shouldEmit) {
                                val partialOffsetMs = if (skipped15Min) 15L * 60 * 1000 else 0L
                                val partialStartTime = partialOffsetMs + offset * 1000L / 32000L
                                val partialEndTime = partialOffsetMs + (offset + bytesRead) * 1000L / 32000L
                                val transcript = com.radio.app.models.Transcript(
                                    text = partialText,
                                    segmentStart = partialStartTime,
                                    segmentEnd = partialEndTime
                                )
                                logToFile("processVoskInChunks: [v2.3.0] PARTIAL: chunk=$chunkCount, start=${partialStartTime}ms, text='${partialText.take(80)}'")
                                writeVoskLog("partial: chunk=$chunkCount, text='${partialText.take(100)}'")
                                allTranscripts.add(transcript)
                                callback.onSubtitleGenerated(transcript)
                                lastPartialEmitTime = currentTimeMs
                                lastForceEmitTime = currentTimeMs
                                lastPartialText = partialText
                            }
                        } catch (_: Exception) { /* skip */ }
                    }
                }

                // [v2.0.91] Log progress every 40 chunks = 40s at 1s/chunk
                if (chunkCount % 40 == 0) {
                    writeVoskLog("processVoskInChunks: [v2.0.91] progress - chunk=$chunkCount, offset=$offset (${currentTimeMs}ms), transcripts=${allTranscripts.size}, acceptTrue=$acceptTrueCount, acceptFalse=$acceptFalseCount")
                }

                offset += bytesRead
                if (chunkCount % 40 == 0) {
                    logToFile("processVoskInChunks: [v2.0.91] processed chunk $chunkCount, totalBytes=$offset, transcripts so far=${allTranscripts.size}")
                }
                val progress = (offset * 100 / totalSize).toInt()
                if (progress > lastProgress + 2) {
                    lastProgress = progress
                    callback.onProgressUpdate(progress, 100)
                }
            }

            // Issue 9: Log if processing was truncated at the 5-minute limit
            if (offset < totalSize) {
                logToFile("processVoskInChunks: processing truncated at 5 minutes, offset=$offset, totalSize=$totalSize")
            }

            logToFile("processVoskInChunks: loop complete, totalChunks=$chunkCount, acceptTrueCount=$acceptTrueCount, acceptFalseCount=$acceptFalseCount, transcripts=${allTranscripts.size}")
            writeVoskLog("processVoskInChunks loop complete: totalChunks=$chunkCount, acceptTrueCount=$acceptTrueCount, acceptFalseCount=$acceptFalseCount, totalTranscripts=${allTranscripts.size}")

            // [v2.0.69] Issue 3 Fix: Flush buffered text. endOfUtterance() doesn't exist in this
            // Vosk Android version (throws NoSuchMethodException). Instead, call getFinalResult()
            // which internally flushes the recognizer and returns any remaining text.
            try {
                val flushResult = getFinalResultMethod.invoke(recognizer) as? String ?: ""
                if (flushResult.isNotBlank()) {
                    val flushJson = org.json.JSONObject(flushResult)
                    val flushText = flushJson.optString("text", "").trim()
                    if (flushText.isNotEmpty() && flushText != lastPartialText) {
                        val flushOffsetMs = if (skipped15Min) 15L * 60 * 1000 else 0L
                        val flushStart = flushOffsetMs + offset * 1000L / 32000L
                        val flushEnd = flushOffsetMs + (offset + chunkSize) * 1000L / 32000L
                        val transcript = com.radio.app.models.Transcript(text = flushText, segmentStart = flushStart, segmentEnd = flushEnd)
                        logToFile("processVoskInChunks: [v2.0.69] getFinalResult flush transcript at ${flushStart}ms: '${flushText.take(50)}...'")
                        writeVoskLog("getFinalResult flush: chunk=$chunkCount, text='${flushText.take(100)}'")
                        allTranscripts.add(transcript)
                        callback.onSubtitleGenerated(transcript)
                    }
                }
                // [v2.0.69] Also check for remaining partial text
                val remainingPartial = getPartialResultMethod.invoke(recognizer) as? String ?: ""
                if (remainingPartial.isNotBlank()) {
                    val partialJson = org.json.JSONObject(remainingPartial)
                    val partialText = partialJson.optString("partial", "").trim()
                    if (partialText.isNotEmpty() && partialText != lastPartialText) {
                        val partialOffsetMs = if (skipped15Min) 15L * 60 * 1000 else 0L
                        val partialStart = partialOffsetMs + offset * 1000L / 32000L
                        val partialEnd = partialOffsetMs + (offset + chunkSize) * 1000L / 32000L
                        val transcript = com.radio.app.models.Transcript(text = partialText, segmentStart = partialStart, segmentEnd = partialEnd)
                        logToFile("processVoskInChunks: [v2.0.69] remaining partial flush at ${partialStart}ms: '${partialText.take(50)}...'")
                        writeVoskLog("remaining partial flush: text='${partialText.take(100)}'")
                        allTranscripts.add(transcript)
                        callback.onSubtitleGenerated(transcript)
                    }
                }
            } catch (e: Exception) {
                logToFile("processVoskInChunks: [v2.0.69] flush attempt failed: ${e.message}")
            }

            // Get final result
            val finalResult = getFinalResultMethod.invoke(recognizer) as? String ?: ""
            if (finalResult.isNotBlank()) {
                try {
                    val json = org.json.JSONObject(finalResult)
                    val text = json.optString("text", "")
                    if (text.isNotBlank()) {
                        // [v2.0.55] Issue 3 Fix: Add 15-min offset only if we actually skipped 15 min
                        val offsetMs = if (skipped15Min) 15L * 60 * 1000 else 0L
                        var startTime = offsetMs
                        var endTime = offsetMs
                        val resultArr = json.optJSONArray("result")
                        if (resultArr != null && resultArr.length() > 0) {
                            val firstWord = resultArr.getJSONObject(0)
                            val lastWord = resultArr.getJSONObject(resultArr.length() - 1)
                            startTime = offsetMs + (firstWord.optDouble("start", 0.0) * 1000).toLong()
                            endTime = offsetMs + (lastWord.optDouble("end", 0.0) * 1000).toLong()
                        } else {
                            // Fallback: use accumulated byte offset for approximate timestamps
                            // offset is in bytes, 16000Hz * 2 bytes/sample = 32000 bytes per second
                            // chunk is out of scope after the loop, use chunkSize for end estimate
                            startTime = offsetMs + offset * 1000L / 32000L
                            endTime = offsetMs + (offset + chunkSize) * 1000L / 32000L
                        }
                        val transcript = com.radio.app.models.Transcript(text = text, segmentStart = startTime, segmentEnd = endTime)
                        logToFile("processVoskInChunks: final transcript #${allTranscripts.size + 1}: start=${startTime}ms, end=${endTime}ms, text='${text.take(50)}...'")
                        allTranscripts.add(transcript)
                        callback.onSubtitleGenerated(transcript)
                    }
                } catch (_: Exception) { /* skip */ }
            }

            logToFile("processVoskInChunks: COMPLETE, totalTranscripts=${allTranscripts.size}, totalBytes=$offset")
            ctx.log("Chunked Vosk processing complete: ${allTranscripts.size} transcripts from ${offset} bytes")
            callback.onComplete(allTranscripts)
            return true
        } catch (e: Throwable) {
            val detail = "Vosk处理异常(${e.javaClass.name}: ${e.message})"
            ctx.lastErrorDetail = detail
            logToFile("processVoskInChunks: [v2.0.76] OUTER CATCH: $detail")
            e.stackTrace.take(10).forEach { logToFile("processVoskInChunks: at $it") }
            ctx.log("ERROR: $detail")
            callback.onError("$detail")
            sendSubtitleBroadcast("com.radio.app.SUBTITLE_ERROR", mapOf(
                "episodeId" to ctx.episodeId, "message" to detail))
            return false
        } finally {
            // [v2.0.90] Close BOTH recognizer AND model to free native memory before Whisper loads
            try { recognizer?.let { voskRecognizerClass!!.getMethod("close").invoke(it) } } catch (_: Exception) {}
            try { model?.let { voskModelClass!!.getMethod("close").invoke(it) } } catch (_: Exception) {}
            try { inputStream.close() } catch (_: Exception) {}
            // [v2.0.90] Force GC after Vosk cleanup to release native memory references before Whisper
            logToFile("processVoskInChunks: [v2.0.90] Vosk resources closed, forcing GC to free native memory")
            System.gc()
        }
    }

    private fun writeCrashLog(tag: String, title: String, extra: String, throwable: Throwable) {
        try {
            // [v2.0.43] Issue 7: Write crash logs to the same logs directory as other logs
            val crashLogDir = java.io.File(com.radio.app.RadioApplication.getLogDir(this), "crash")
            if (!crashLogDir.exists()) crashLogDir.mkdirs()
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).format(java.util.Date())
            val crashFile = java.io.File(crashLogDir, "${tag}_${ts}.txt")
            java.io.FileWriter(crashFile).use { writer ->
                writer.appendLine("===== $title =====")
                writer.appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}")
                writer.appendLine("设备: ${android.os.Build.BRAND} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})")
                writer.appendLine(extra)
                java.io.PrintWriter(writer).use { pw -> throwable.printStackTrace(pw) }
            }
            // Also log to the subtitle service log so it appears in the main log
            logToFile("writeCrashLog: $tag - $title: ${throwable.javaClass.name}: ${throwable.message}")
        } catch (_: Exception) {}
    }

    private fun getAudioDataForProcessing(episodeId: String, audioUrl: String, ctx: TaskContext): ByteArray? {
        // Issue 8: Log each step with timing
        val startTime = System.currentTimeMillis()
        logToFile("getAudioDataForProcessing: START, audioUrl=$audioUrl")
        // [v2.1.0] Unified PCM cache: ${episodeId}_5min.pcm is always 16kHz mono.
        // No more separate _16k file. RadioPlaybackService and SubtitleGeneratorService
        // both write to the same _5min.pcm file with 16kHz mono data.
        val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this)
        val pcmFile = File(pcmCacheDir, "${episodeId}_5min.pcm")
        val minValidPcmBytes = 1024 * 1024  // [v2.0.67] Require at least ~30s of audio
        if (pcmFile.exists() && pcmFile.length() >= minValidPcmBytes) {
            ctx.log("Using PCM cache: ${pcmFile.length()} bytes")
            logToFile("getAudioDataForProcessing: PCM cache hit, size=${pcmFile.length()}")
            // PCM cache is 16kHz mono, safe to read entirely if < 50MB
            if (pcmFile.length() < 50_000_000) {
                val data = pcmFile.readBytes()
                if (data.isEmpty() || data.size < minValidPcmBytes) {
                    ctx.log("ERROR: PCM cache data is empty or too small (${data.size} bytes), falling through")
                } else {
                    val cacheTime = System.currentTimeMillis() - startTime
                    logToFile("getAudioDataForProcessing: PCM cache hit, time=${cacheTime}ms, size=${data.size}")
                    return data
                }
            } else {
                // File too large for in-memory: signal caller to use chunked processing
                val sizeMB = pcmFile.length() / 1024 / 1024
                ctx.log("音频文件过大（${sizeMB}MB），需要分块处理")
                return null
            }
        } else if (pcmFile.exists()) {
            logToFile("getAudioDataForProcessing: PCM cache too small (${pcmFile.length()} bytes), will regenerate")
        }
        // 3) Download and process
        ctx.log("No PCM cache, downloading from $audioUrl")
        logToFile("generateSubtitlesForEpisode: downloading audio from $audioUrl")
        val downloadedData = downloadAndProcessAudio(audioUrl, episodeId, ctx)
        val downloadTime = System.currentTimeMillis() - startTime
        logToFile("getAudioDataForProcessing: download completed in ${downloadTime}ms, size=${downloadedData?.size ?: 0}")
        logToFile("generateSubtitlesForEpisode: audio downloaded, size=${downloadedData?.size ?: 0}")
        if (downloadedData != null && downloadedData.size >= 1024) {
            return downloadedData
        }
        ctx.log("ERROR: downloadAndProcessAudio returned null or too small data (${downloadedData?.size ?: 0} bytes)")
        return null
    }

    private fun streamResampleTo16kMono(pcmFile: File, inSampleRate: Int, inChannels: Int, ctx: TaskContext): ByteArray? {
        try {
            val pcmSize = pcmFile.length()
            if (pcmSize > 200 * 1024 * 1024) {
                ctx.log("ERROR: PCM file too large (${pcmSize / 1024 / 1024}MB) for in-memory processing")
                return null
            }
            val ratio = inSampleRate.toDouble() / 16000.0
            val totalInFrames = pcmFile.length() / (inChannels * 2)
            val totalOutFrames = (totalInFrames / ratio).toInt()
            val outSize = totalOutFrames * 2  // 16-bit mono
            
            // Limit output to ~80MB max (about 40min @ 16kHz)
            if (outSize > 80_000_000) {
                ctx.log("WARNING: Output would be ${outSize} bytes, limiting to 30 minutes")
            }
            
            val byteBuf = ByteArray(65536)
            val outBuf = java.io.ByteArrayOutputStream()
            val pcmIn = pcmFile.inputStream()
            try {
                var read: Int
                while (pcmIn.read(byteBuf).also { read = it } > 0 && !ctx.cancelled.get()) {
                    if (read < inChannels * 2) continue
                    val numFrames = read / (inChannels * 2)
                    val shorts = ShortArray(numFrames * inChannels)
                    java.nio.ByteBuffer.wrap(byteBuf, 0, read).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                    
                    // Convert to mono
                    val monoIn = ShortArray(numFrames)
                    for (i in 0 until numFrames) {
                        var sum = 0
                        for (c in 0 until inChannels) sum += shorts[i * inChannels + c].toInt()
                        monoIn[i] = (sum / inChannels).toShort()
                    }
                    
                    // Resample to 16kHz
                    val outFrames = (numFrames / ratio).toInt()
                    val monoOut = ShortArray(outFrames)
                    for (i in 0 until outFrames) {
                        val srcPos = i * ratio
                        val srcIdx = srcPos.toInt()
                        val frac = srcPos - srcIdx
                        if (srcIdx + 1 >= monoIn.size) {
                            monoOut[i] = monoIn[monoIn.size - 1]
                        } else {
                            monoOut[i] = ((monoIn[srcIdx] * (1.0 - frac) + monoIn[srcIdx + 1] * frac).toInt()).toShort()
                        }
                    }
                    val outBytes = ByteArray(monoOut.size * 2)
                    java.nio.ByteBuffer.wrap(outBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(monoOut)
                    outBuf.write(outBytes)
                }
            } finally {
                pcmIn.close()
            }
            ctx.log("Stream-resampled: ${outBuf.size()} bytes output")
            return outBuf.toByteArray()
        } catch (e: Exception) {
            ctx.log("ERROR: stream resample failed: ${e.message}")
            return null
        }
    }

    private fun resampleTo16kMono(input: ShortArray, inSampleRate: Int, inChannels: Int): ByteArray {
        if (inSampleRate == 16000 && inChannels == 1) {
            val out = ByteArray(input.size * 2)
            java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(input)
            return out
        }
        val ratio = inSampleRate.toDouble() / 16000.0
        val inputFrames = input.size / inChannels
        val outFrames = (inputFrames / ratio).toInt()
        val monoInput: ShortArray = if (inChannels > 1) {
            val arr = ShortArray(inputFrames)
            for (i in 0 until inputFrames) {
                var sum = 0
                for (c in 0 until inChannels) sum += input[i * inChannels + c].toInt()
                arr[i] = (sum / inChannels).toShort()
            }
            arr
        } else { input }
        val monoOut = ShortArray(outFrames)
        for (i in 0 until outFrames) {
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx
            if (srcIdx + 1 >= monoInput.size) {
                monoOut[i] = monoInput[monoInput.size - 1]
            } else {
                monoOut[i] = ((monoInput[srcIdx] * (1.0 - frac) + monoInput[srcIdx + 1] * frac).toInt()).toShort()
            }
        }
        val out = ByteArray(monoOut.size * 2)
        java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(monoOut)
        return out
    }

    private fun downloadAndProcessAudio(audioUrl: String, episodeId: String, ctx: TaskContext): ByteArray? {
        // [v2.0.62] Issue 3 Fix: Download audio, decode to 16kHz mono PCM, return PCM bytes.
        // Vosk ONLY accepts 16kHz mono 16-bit PCM, NOT raw MP3/AAC.
        // Previous bug: returned raw compressed bytes, causing Vosk to produce no output.
        try {
            val url = java.net.URL(audioUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.connect()
            if (conn.responseCode != 200) {
                ctx.log("ERROR: Download failed with code ${conn.responseCode}")
                return null
            }
            // Download to temp file
            val tempAudioFile = File.createTempFile("audio_dl_", ".tmp", cacheDir)
            tempAudioFile.deleteOnExit()
            try {
                conn.inputStream.use { input ->
                    tempAudioFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } finally {
                conn.disconnect()
            }
            ctx.log("Downloaded ${tempAudioFile.length()} bytes, decoding to PCM...")
            logToFile("downloadAndProcessAudio: downloaded ${tempAudioFile.length()} bytes to ${tempAudioFile.absolutePath}")

            // [v2.1.0] Decode to 16kHz mono PCM, save to centralized cache dir
            val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this)
            if (!pcmCacheDir.exists()) pcmCacheDir.mkdirs()
            val pcmFile = File(pcmCacheDir, "${episodeId}_5min.pcm")  // [v2.0.99] unified file name
            var decodedOk = false
            var fallbackUsed = false
            try {
                // [v2.0.74] Issue 2 Fix: Try multiple time ranges if primary range produces 0 bytes.
                // v2.0.73 logs showed "decoded to 0 bytes PCM" for some episodes when seeking to 15min,
                // likely due to VBR files, truncated downloads, or seek landing near EOF.
                // Fallback chain: 15-20min -> 10-15min -> 5-10min -> 0-5min
                val ranges = arrayOf(
                    15L * 60 * 1000 * 1000 to 5L * 60 * 1000 * 1000,  // 15-20min (primary)
                    10L * 60 * 1000 * 1000 to 5L * 60 * 1000 * 1000,  // 10-15min (fallback 1)
                    5L * 60 * 1000 * 1000 to 5L * 60 * 1000 * 1000,   // 5-10min (fallback 2)
                    0L to 5L * 60 * 1000 * 1000                        // 0-5min (fallback 3)
                )
                for ((start, dur) in ranges) {
                    if (pcmFile.exists()) pcmFile.delete()
                    val rangeLabel = "${start/60000000}-${(start+dur)/60000000}min"
                    try {
                        logToFile("downloadAndProcessAudio: [v2.0.74] attempting decode range $rangeLabel")
                        decodeToPcm(tempAudioFile, pcmFile, dur, ctx, startUs = start)
                        val pcmSize = pcmFile.length()
                        if (pcmSize > 100000) {  // At least ~3 seconds of audio
                            logToFile("downloadAndProcessAudio: [v2.0.74] decode OK for $rangeLabel: $pcmSize bytes")
                            decodedOk = true
                            if (start > 0) fallbackUsed = true
                            break
                        } else {
                            logToFile("downloadAndProcessAudio: [v2.0.74] decode for $rangeLabel produced only $pcmSize bytes, trying next range")
                        }
                    } catch (e: Exception) {
                        logToFile("downloadAndProcessAudio: [v2.0.74] decode for $rangeLabel failed: ${e.message}")
                    }
                }
                if (!decodedOk) {
                    ctx.log("ERROR: Failed to decode any PCM data from audio file")
                    logToFile("downloadAndProcessAudio: [v2.0.74] ALL decode ranges failed")
                    return null
                }
                val pcmData = pcmFile.readBytes()
                ctx.log("Decoded to ${pcmData.size} bytes of 16kHz PCM (${pcmData.size / 32000}s)${if (fallbackUsed) " (fallback range used)" else " from 15-20 min"}")
                logToFile("downloadAndProcessAudio: [v2.0.74] decoded to ${pcmData.size} bytes PCM, cached to ${pcmFile.absolutePath}${if (fallbackUsed) " (FALLBACK)" else ""}")
                return pcmData
            } finally {
                tempAudioFile.delete()
            }
        } catch (e: Exception) {
            ctx.log("ERROR: Download/decode failed: ${e.message}")
            logToFile("downloadAndProcessAudio: ERROR: ${e.javaClass.name}: ${e.message}")
            return null
        }
    }

    private fun parseVoskPartial(jsonResult: String): String {
        return try {
            val json = org.json.JSONObject(jsonResult)
            if (json.has("partial")) json.getString("partial") else ""
        } catch (_: Exception) { "" }
    }

    /**
     * 验证目录是否包含有效的Vosk模型
     * v2.0.56 Issue 3 Fix: 严格验证 - 有效的 Vosk 模型目录必须至少包含 am/ 和 graph/ 子目录。
     * 这样可以拒绝未完成的下载（目录里只有 .zip.tmp 文件，缺少实际模型文件 am/、graph/ 等）。
     * 同时检测一层嵌套子目录（zip解压可能产生嵌套）
     */
    private fun isValidVoskModel(dir: File): Boolean {
        if (!dir.isDirectory) return false
        // [v2.0.56] Issue 3 Fix: A valid Vosk model directory must contain at least
        // am/ and graph/ subdirectories.
        val amDir = File(dir, "am")
        val graphDir = File(dir, "graph")
        if (amDir.exists() && amDir.isDirectory && graphDir.exists() && graphDir.isDirectory) {
            // [v2.0.72] Issue 4 Fix: Validate minimum file sizes to catch incomplete downloads.
            // Small model (~50MB) has am/final.mdl ~30MB, graph/HCLr.fst ~20MB.
            // Large model (~1.3GB) has am/final.mdl ~100MB+, graph/HCLr.fst ~300MB+.
            val amFiles = amDir.listFiles()?.filter { it.isFile } ?: emptyList()
            val graphFiles = graphDir.listFiles()?.filter { it.isFile } ?: emptyList()

            // Check for required critical files
            val hasFinalMdl = amFiles.any { it.name == "final.mdl" && it.length() > 1024 * 1024 }  // >1MB
            val hasHclrFst = graphFiles.any { it.name == "HCLr.fst" && it.length() > 1024 * 1024 }  // >1MB
            val hasGfst = graphFiles.any { it.name == "G.fst" && it.length() > 1024 }  // >1KB
            val hasGraphFst = graphFiles.any { it.name.endsWith(".fst") && it.length() > 1024 }
            val wordsFile = graphFiles.firstOrNull { it.name == "words.txt" || it.name == "phones.txt" }

            // Calculate total model size
            val totalAmSize = amFiles.sumOf { it.length() }
            val totalGraphSize = graphFiles.sumOf { it.length() }
            val totalModelSize = totalAmSize + totalGraphSize

            val isLargeModel = dir.name.contains("cn-0.22") && !dir.name.contains("small")
            val minModelSize = if (isLargeModel) 500L * 1024 * 1024 else 30L * 1024 * 1024  // 500MB for large, 30MB for small

            val hasCriticalFiles = hasFinalMdl && (hasHclrFst || hasGraphFst)
            val hasMinSize = totalModelSize >= minModelSize

            if (hasCriticalFiles && hasMinSize) {
                logToFile("isValidVoskModel: [v2.0.72] ${dir.name} ACCEPTED (am=${amFiles.size} files/${totalAmSize/1024/1024}MB, graph=${graphFiles.size} files/${totalGraphSize/1024/1024}MB, total=${totalModelSize/1024/1024}MB, hasFinalMdl=$hasFinalMdl, hasHCLr=$hasHclrFst)")
                return true
            } else {
                logToFile("isValidVoskModel: [v2.0.72] ${dir.name} REJECTED - hasFinalMdl=$hasFinalMdl, hasHCLr=$hasHclrFst, totalModelSize=${totalModelSize/1024/1024}MB (need ${minModelSize/1024/1024}MB), amFiles=${amFiles.map { "${it.name}(${it.length()/1024}KB)" }}, graphFiles=${graphFiles.map { "${it.name}(${it.length()/1024}KB)" }}")
                return false
            }
        }
        // Fallback: handle nested extraction (zip may produce dir/<model-name>/am)
        val subdirs = dir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (sub in subdirs) {
            val subAm = File(sub, "am")
            val subGraph = File(sub, "graph")
            if (subAm.exists() && subAm.isDirectory && subGraph.exists() && subGraph.isDirectory) {
                // [v2.0.72] Recursively validate nested models too
                if (isValidVoskModel(sub)) {
                    logToFile("isValidVoskModel: [v2.0.72] ${dir.name} accepted (nested valid model in ${sub.name})")
                    return true
                }
            }
        }
        logToFile("isValidVoskModel: ${dir.name} rejected (missing am/ or graph/ subdirs)")
        return false
    }

    /**
     * 检查目录是否是Whisper模型（ggml格式，供whisper.cpp使用）
     * 支持子目录嵌套检测（处理zip解压后模型文件在子目录中的情况）
     */
    private fun isWhisperModel(dir: File): Boolean {
        if (!dir.isDirectory) return false
        // 检查当前目录中是否有ggml*.bin 或 *whisper*.bin 文件
        val hasBin = dir.listFiles()?.any {
            it.isFile && it.name.endsWith(".bin") && (it.name.startsWith("ggml") || it.name.contains("whisper", ignoreCase = true))
        } == true
        if (hasBin) return true
        // 检查子目录（嵌套解压的情况）
        val subdirs = dir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (sub in subdirs) {
            val subHasBin = sub.listFiles()?.any {
                it.isFile && it.name.endsWith(".bin") && (it.name.startsWith("ggml") || it.name.contains("whisper", ignoreCase = true))
            } == true
            if (subHasBin) return true
        }
        return false
    }

    /**
     * 在目录（含子目录）中查找Whisper .bin模型文件，返回文件绝对路径
     */
    private fun findWhisperBinFile(dir: File): File? {
        if (!dir.isDirectory) return null
        // 先查当前目录
        dir.listFiles()?.firstOrNull {
            it.isFile && it.name.endsWith(".bin") && (it.name.startsWith("ggml") || it.name.contains("whisper", ignoreCase = true))
        }?.let { return it }
        // 递归查子目录（只查一层，避免过深）
        val subdirs = dir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (sub in subdirs) {
            sub.listFiles()?.firstOrNull {
                it.isFile && it.name.endsWith(".bin") && (it.name.startsWith("ggml") || it.name.contains("whisper", ignoreCase = true))
            }?.let { return it }
        }
        return null
    }

    private fun findVoskModel(): String? {
        logToFile("findVoskModel: searching for Vosk models...")
        // [v2.0.61] Issue 6 Fix: Use saved Vosk model directory if set
        val savedVoskDir = AppSettings.getInstance(this).voskModelDir
        if (savedVoskDir.isNotEmpty()) {
            logToFile("findVoskModel: [v2.0.61] savedVoskDir=$savedVoskDir, looking for this specific model")
        }
        // 1. 检查外部存储手动下载的模型（只查找Vosk模型，不查找Whisper ggml模型）
        val modelsDir = getExternalFilesDir("models")
        if (modelsDir != null && modelsDir.exists()) {
            val modelDirs = modelsDir.listFiles()?.filter { it.isDirectory }
            logToFile("findVoskModel: all modelDirs: ${modelDirs?.map { it.name }}")
            if (!modelDirs.isNullOrEmpty()) {
                // [v2.0.61] Issue 6 Fix: If savedVoskDir is set, find it FIRST (before sorting)
                if (savedVoskDir.isNotEmpty()) {
                    val savedDir = modelDirs.find { it.name == savedVoskDir }
                    if (savedDir != null && isValidVoskModel(savedDir)) {
                        logToFile("findVoskModel: [v2.0.61] FOUND saved model: ${savedDir.name}")
                        return savedDir.absolutePath
                    }
                    logToFile("findVoskModel: [v2.0.61] saved model $savedVoskDir not found or invalid, falling back to auto-detect")
                }
                // [v2.0.71] Issue 9 Fix: Sort SMALL models first (not large). Large models
                // (1.3GB) often fail to load due to memory constraints. Small models (~50MB)
                // are more reliable. Only use large if no small model is available.
                val voskDirs = modelDirs.filter { it.name.contains("vosk", ignoreCase = true) }
                    .sortedBy { !it.name.contains("small", ignoreCase = true) }  // Small models first
                logToFile("findVoskModel: [v2.0.71] voskDirs found (sorted, SMALL models first): ${voskDirs.map { it.name }}")
                for (dir in voskDirs) {
                    val dirFiles = dir.listFiles()?.map { "${it.name}(${if (it.isDirectory) "dir" else "${it.length()}B"})" } ?: listOf("(null listFiles)")
                    logToFile("findVoskModel: checking ${dir.name}, files=${dirFiles.take(20)}")
                    if (dir.name.startsWith("vosk-model", ignoreCase = true) && dir.listFiles()?.isNotEmpty() == true) {
                        if (!isValidVoskModel(dir)) {
                            logToFile("findVoskModel: REJECTED ${dir.name} - missing am/ or graph/ subdirs (incomplete download?)")
                            continue  // Skip this directory, try next
                        }
                        logToFile("findVoskModel: ACCEPTED ${dir.name} (valid model with am/ and graph/)")
                        return dir.absolutePath
                    }
                    if (isValidVoskModel(dir)) {
                        logToFile("findVoskModel: VALID model at ${dir.absolutePath}")
                        return dir.absolutePath
                    }
                }
                // 其次查找其他目录，但验证是否是有效Vosk模型（排除Whisper）
                for (dir in modelDirs) {
                    if (isWhisperModel(dir)) {
                        logToFile("findVoskModel: skipping Whisper model in ${dir.name} (not supported in this version)")
                        continue
                    }
                    val dirFiles = dir.listFiles()?.map { "${it.name}(${if (it.isDirectory) "dir" else "${it.length()}B"})" } ?: listOf("(null listFiles)")
                    logToFile("findVoskModel: checking ${dir.name}, files=${dirFiles.take(20)}")
                    if (dir.name.startsWith("vosk-model", ignoreCase = true) && dir.listFiles()?.isNotEmpty() == true) {
                        if (!isValidVoskModel(dir)) {
                            logToFile("findVoskModel: REJECTED ${dir.name} - missing am/ or graph/ subdirs (incomplete download?)")
                            continue  // Skip this directory, try next
                        }
                        logToFile("findVoskModel: ACCEPTED ${dir.name} (valid model with am/ and graph/)")
                        return dir.absolutePath
                    }
                    if (isValidVoskModel(dir)) {
                        logToFile("findVoskModel: VALID model at ${dir.absolutePath}")
                        return dir.absolutePath
                    }
                }
            }
            logToFile("findVoskModel: no Vosk model found in ${modelsDir.absolutePath}, dirs=${modelDirs?.map { it.name }}")
        }
        // 2. 检查内置模型
        val internalModelDir = File(filesDir, "vosk-model-small-cn-0.22")
        if (internalModelDir.exists() && isValidVoskModel(internalModelDir)) {
            logToFile("findVoskModel: found built-in Vosk model at ${internalModelDir.absolutePath}")
            return internalModelDir.absolutePath
        }
        // 3. Also check the OfflineEngineActivity download directory
        val engineDir = File(filesDir, "engines")
        if (engineDir.exists()) {
            val voskDirs = engineDir.listFiles()?.filter { it.isDirectory && it.name.contains("vosk", ignoreCase = true) }
                // [v2.0.72] Issue 4 Fix: Sort SMALL models first (consistent with external dirs).
                // Large models (1.3GB) often OOM on mid/low-end devices.
                ?.sortedBy { !it.name.contains("small", ignoreCase = true) }
            for (dir in voskDirs ?: emptyList()) {
                val dirFiles = dir.listFiles()?.map { "${it.name}(${if (it.isDirectory) "dir" else "${it.length()}B"})" } ?: listOf("(null listFiles)")
                logToFile("findVoskModel: checking ${dir.name}, files=${dirFiles.take(20)}")
                if (dir.name.startsWith("vosk-model", ignoreCase = true) && dir.listFiles()?.isNotEmpty() == true) {
                    if (!isValidVoskModel(dir)) {
                        logToFile("findVoskModel: REJECTED ${dir.name} - missing am/ or graph/ subdirs (incomplete download?)")
                        continue  // Skip this directory, try next
                    }
                    logToFile("findVoskModel: ACCEPTED ${dir.name} (valid model with am/ and graph/)")
                    return dir.absolutePath
                }
                if (isValidVoskModel(dir)) {
                    logToFile("findVoskModel: VALID model at ${dir.absolutePath}")
                    return dir.absolutePath
                }
            }
        }
        logToFile("findVoskModel: no Vosk model found. Checked ${modelsDir?.absolutePath} and ${internalModelDir.absolutePath}")
        return null
    }

    /**
     * 查找Whisper模型（ggml格式，供whisper.cpp使用）
     * 返回模型.bin文件的绝对路径（不是目录路径），找不到返回null
     */

    // [v2.4.2] Convert model file/directory path to friendly display name
    private fun getFriendlyModelName(path: String): String {
        val name = File(path).name.lowercase()
        return when {
            // Whisper models: ggml-tiny.bin -> "Whisper Tiny"
            name.contains("tiny") -> "Whisper Tiny"
            name.contains("base") -> "Whisper Base"
            name.contains("small") && !name.contains("vosk") -> "Whisper Small"
            name.contains("medium") -> "Whisper Medium"
            name.contains("large") -> "Whisper Large"
            // Vosk models: vosk-model-small-cn-0.22 -> "Vosk 小模型"
            name.contains("vosk") && name.contains("small") -> "Vosk 小模型"
            name.contains("vosk") && name.contains("large") -> "Vosk 大模型"
            name.contains("vosk") -> "Vosk 模型"
            // Fallback: capitalize
            else -> name.replace(".bin", "").replace("ggml-", "Whisper ").replace("vosk-model-", "Vosk ")
                .split("-").joinToString(" ") { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() } }
        }
    }

    private fun findWhisperModel(): String? {
        logToFile("findWhisperModel: searching for Whisper ggml models... (forceWhisperBaseModel=$forceWhisperBaseModel)")
        // [v2.4.17] Changed from Whisper base to Whisper tiny for pre-cache subtitle generation (faster)
        if (forceWhisperBaseModel) {
            logToFile("findWhisperModel: [v2.4.17] forceWhisperBaseModel=true, looking for Whisper tiny model specifically")
            val modelsDir = getExternalFilesDir("models")
            if (modelsDir != null && modelsDir.exists()) {
                val modelDirs = modelsDir.listFiles()?.filter { it.isDirectory }
                if (!modelDirs.isNullOrEmpty()) {
                    // Look for "tiny" model specifically
                    val tinyDirs = modelDirs.filter {
                        it.name.contains("whisper", ignoreCase = true) &&
                        it.name.contains("tiny", ignoreCase = true) &&
                        it.name != "whisper-engine"
                    }
                    for (dir in tinyDirs) {
                        val binFile = findWhisperBinFile(dir)
                        if (binFile != null) {
                            logToFile("findWhisperModel: [v2.4.17] FOUND Whisper tiny model for pre-cache: ${binFile.absolutePath}")
                            return binFile.absolutePath
                        }
                    }
                    // [v2.4.17] Fallback to base if tiny not found
                    logToFile("findWhisperModel: [v2.4.17] Whisper tiny not found, trying base model")
                    val baseDirs = modelDirs.filter {
                        it.name.contains("whisper", ignoreCase = true) &&
                        it.name.contains("base", ignoreCase = true) &&
                        it.name != "whisper-engine"
                    }
                    for (dir in baseDirs) {
                        val binFile = findWhisperBinFile(dir)
                        if (binFile != null) {
                            logToFile("findWhisperModel: [v2.4.17] FOUND Whisper base model (fallback): ${binFile.absolutePath}")
                            return binFile.absolutePath
                        }
                    }
                    logToFile("findWhisperModel: [v2.4.17] Whisper tiny/base not found, falling back to normal search")
                }
            }
            // Fall through to normal search if tiny not found
        }
        // [v2.2.9] Check for saved Whisper model preference first
        val savedWhisperDir = AppSettings.getInstance(this).whisperModelDir
        if (savedWhisperDir.isNotEmpty()) {
            logToFile("findWhisperModel: [v2.2.9] savedWhisperDir=$savedWhisperDir, looking for this specific model")
        }
        val modelsDir = getExternalFilesDir("models")
        if (modelsDir != null && modelsDir.exists()) {
            val modelDirs = modelsDir.listFiles()?.filter { it.isDirectory }
            if (!modelDirs.isNullOrEmpty()) {
                // [v2.2.9] Priority 1: Look for the user's saved Whisper model directory first
                if (savedWhisperDir.isNotEmpty()) {
                    val savedDir = modelDirs.find { it.name == savedWhisperDir }
                    if (savedDir != null) {
                        val binFile = findWhisperBinFile(savedDir)
                        if (binFile != null) {
                            logToFile("findWhisperModel: [v2.2.9] FOUND saved Whisper model: ${binFile.absolutePath}")
                            return binFile.absolutePath
                        }
                    }
                    logToFile("findWhisperModel: [v2.2.9] saved model $savedWhisperDir not found or invalid, falling back to auto-detect")
                }
                // Priority 2: Search directories containing "whisper" in name
                val whisperDirs = modelDirs.filter { it.name.contains("whisper", ignoreCase = true) && it.name != "whisper-engine" }
                // Sort: prefer smaller models first (tiny < base < small < medium < large) for reliability
                val sortedWhisperDirs = whisperDirs.sortedBy { dir ->
                    when {
                        dir.name.contains("tiny", ignoreCase = true) -> 0
                        dir.name.contains("base", ignoreCase = true) -> 1
                        dir.name.contains("small", ignoreCase = true) -> 2
                        dir.name.contains("medium", ignoreCase = true) -> 3
                        dir.name.contains("large", ignoreCase = true) -> 4
                        else -> 5
                    }
                }
                logToFile("findWhisperModel: whisper directories found (sorted, smaller first): ${sortedWhisperDirs.map { it.name }}")
                for (dir in sortedWhisperDirs) {
                    val binFile = findWhisperBinFile(dir)
                    if (binFile != null) {
                        logToFile("findWhisperModel: found Whisper model file: ${binFile.absolutePath}")
                        return binFile.absolutePath
                    }
                }
                // Priority 3: Search all other directories
                for (dir in modelDirs) {
                    if (dir.name.contains("whisper", ignoreCase = true)) continue // already checked
                    val binFile = findWhisperBinFile(dir)
                    if (binFile != null) {
                        logToFile("findWhisperModel: found Whisper model file in ${dir.name}: ${binFile.absolutePath}")
                        return binFile.absolutePath
                    }
                }
            }
            logToFile("findWhisperModel: no Whisper model found in ${modelsDir.absolutePath}, dirs=${modelDirs?.map { it.name }}")
        }
        // Also check engines dir
        val engineDir = File(filesDir, "engines")
        if (engineDir.exists()) {
            val whisperDirs = engineDir.listFiles()?.filter { it.isDirectory && it.name.contains("whisper", ignoreCase = true) }
            for (dir in whisperDirs ?: emptyList()) {
                val binFile = findWhisperBinFile(dir)
                if (binFile != null) {
                    logToFile("findWhisperModel: found Whisper model file in engines dir: ${binFile.absolutePath}")
                    return binFile.absolutePath
                }
            }
        }
        logToFile("findWhisperModel: no Whisper model found")
        return null
    }

    /**
     * 从用户下载的引擎目录中动态加载 libvosk.so。
     * 搜索路径：getExternalFilesDir("models") 和 filesDir/engines 下的子目录。
     * 使用 System.load(absolutePath) 加载，避免将 ~40MB 的 .so 打包进 APK。
     * @return 是否成功加载
     */
    private fun loadVoskNativeLibrary(): Boolean {
        try {
            // 检查是否已经通过 System.loadLibrary 加载
            try {
                System.loadLibrary("vosk")
                logToFile("loadVoskNativeLibrary: libvosk.so already loaded via System.loadLibrary")
                return true
            } catch (_: UnsatisfiedLinkError) {
                // 未加载，继续尝试动态加载
            }

            // 搜索 models 目录
            val modelsDir = getExternalFilesDir("models")
            if (modelsDir != null && modelsDir.exists()) {
                modelsDir.walkTopDown().filter { it.isFile && it.name == "libvosk.so" }.forEach { soFile ->
                    // 复制 .so 到内部存储后再加载，避免外部存储安全限制
                    val internalSo = File(codeCacheDir, "libvosk.so")
                    try {
                        logToFile("loadVoskNativeLibrary: copying .so from ${soFile.absolutePath} to ${internalSo.absolutePath}")
                        soFile.copyTo(internalSo, overwrite = true)
                        logToFile("loadVoskNativeLibrary: copied ${soFile.absolutePath} to ${internalSo.absolutePath}")
                        logToFile("loadVoskNativeLibrary: loading .so from ${internalSo.absolutePath}")
                        System.load(internalSo.absolutePath)
                        logToFile("loadVoskNativeLibrary: loaded libvosk.so from ${internalSo.absolutePath}")
                        return true
                    } catch (e: UnsatisfiedLinkError) {
                        logToFile("loadVoskNativeLibrary: failed to load from ${internalSo.absolutePath}: ${e.message}")
                    } catch (e: Exception) {
                        logToFile("loadVoskNativeLibrary: copy/load error: ${e.message}")
                    }
                }
            }

            // 搜索 engines 目录
            val engineDir = File(filesDir, "engines")
            if (engineDir.exists()) {
                engineDir.walkTopDown().filter { it.isFile && it.name == "libvosk.so" }.forEach { soFile ->
                    // 复制 .so 到内部存储后再加载，避免外部存储安全限制
                    val internalSo = File(codeCacheDir, "libvosk.so")
                    try {
                        logToFile("loadVoskNativeLibrary: copying .so from ${soFile.absolutePath} to ${internalSo.absolutePath}")
                        soFile.copyTo(internalSo, overwrite = true)
                        logToFile("loadVoskNativeLibrary: copied ${soFile.absolutePath} to ${internalSo.absolutePath}")
                        logToFile("loadVoskNativeLibrary: loading .so from ${internalSo.absolutePath}")
                        System.load(internalSo.absolutePath)
                        logToFile("loadVoskNativeLibrary: loaded libvosk.so from ${internalSo.absolutePath}")
                        return true
                    } catch (e: UnsatisfiedLinkError) {
                        logToFile("loadVoskNativeLibrary: failed to load from ${internalSo.absolutePath}: ${e.message}")
                    } catch (e: Exception) {
                        logToFile("loadVoskNativeLibrary: copy/load error: ${e.message}")
                    }
                }
            }

            logToFile("loadVoskNativeLibrary: libvosk.so not found in any engine directory")
            return false
        } catch (e: Exception) {
            logToFile("loadVoskNativeLibrary: error: ${e.message}")
            return false
        }
    }

    /**
     * 从用户下载的引擎目录中动态加载 libwhisper.so。
     * 搜索路径与 loadVoskNativeLibrary 相同。
     * @return 是否成功加载
     */
    private fun loadWhisperNativeLibrary(): Boolean {
        try {
            // v2.4.49: Try bundled .so files first (from APK jniLibs)
            try {
                System.loadLibrary("ggml-base-whisper")
                System.loadLibrary("ggml-cpu-whisper")
                System.loadLibrary("ggml-whisper")
                System.loadLibrary("whisper")
                System.loadLibrary("whisper_jni")
                logToFile("loadWhisperNativeLibrary: all bundled .so loaded via System.loadLibrary")
                return true
            } catch (e: UnsatisfiedLinkError) {
                if (e.message?.contains("already loaded") == true) {
                    logToFile("loadWhisperNativeLibrary: bundled .so already loaded")
                    return true
                }
                logToFile("loadWhisperNativeLibrary: bundled load failed: ${e.message}, trying external...")
            }

            // Fallback: Load from external storage (old method)
            try {
                System.loadLibrary("whisper")
                logToFile("loadWhisperNativeLibrary: libwhisper.so already loaded via System.loadLibrary")
                return true
            } catch (_: UnsatisfiedLinkError) {
                // 未加载，继续尝试动态加载
            }

            // Issue 5: Load all 4 Whisper .so files in dependency order.
            // Whisper.net runtime package contains:
            //   libggml-base-whisper.so -> libggml-cpu-whisper.so -> libggml-whisper.so -> libwhisper.so
            // They must be loaded in this order or System.load will fail with UnsatisfiedLinkError.
            val searchDirs = mutableListOf<File>()
            getExternalFilesDir("models")?.let { if (it.exists()) searchDirs.add(it) }
            val engineDir = File(filesDir, "engines")
            if (engineDir.exists()) searchDirs.add(engineDir)

            val soFiles = listOf("libggml-base-whisper.so", "libggml-cpu-whisper.so", "libggml-whisper.so", "libwhisper.so")

            for (searchDir in searchDirs) {
                val foundFiles = mutableMapOf<String, File>()
                searchDir.walkTopDown().filter { it.isFile && it.name in soFiles }.forEach {
                    foundFiles[it.name] = it
                }

                // Only attempt loading if libwhisper.so (the top-level lib) is present
                if (foundFiles.containsKey("libwhisper.so")) {
                    // Load all 4 .so files in dependency order
                    var allLoaded = true
                    for (soName in soFiles) {
                        val soFile = foundFiles[soName] ?: continue
                        try {
                            // Copy to internal storage first (external storage may have loading restrictions)
                            val targetFile = File(codeCacheDir, soName)
                            if (targetFile.exists()) targetFile.delete()
                            soFile.copyTo(targetFile, overwrite = true)
                            System.load(targetFile.absolutePath)
                            logToFile("loadWhisperNativeLibrary: loaded $soName from ${soFile.absolutePath} (via ${targetFile.absolutePath})")
                        } catch (e: UnsatisfiedLinkError) {
                            // If it's already loaded, that's OK
                            if (e.message?.contains("already loaded") == true) {
                                logToFile("loadWhisperNativeLibrary: $soName already loaded, skipping")
                            } else {
                                logToFile("loadWhisperNativeLibrary: failed to load $soName: ${e.message}")
                                allLoaded = false
                            }
                        } catch (e: Exception) {
                            logToFile("loadWhisperNativeLibrary: error loading $soName: ${e.message}")
                            allLoaded = false
                        }
                    }
                    if (allLoaded) {
                        logToFile("loadWhisperNativeLibrary: all Whisper .so files loaded successfully")
                        // Also load the JNI bridge - try multiple approaches
                        var jniBridgeLoaded = false
                        try {
                            System.loadLibrary("whisper_jni")
                            jniBridgeLoaded = true
                            logToFile("loadWhisperNativeLibrary: JNI bridge loaded via System.loadLibrary")
                        } catch (e: UnsatisfiedLinkError) {
                            if (e.message?.contains("already loaded") == true) {
                                jniBridgeLoaded = true
                                logToFile("loadWhisperNativeLibrary: JNI bridge already loaded")
                            } else {
                                logToFile("loadWhisperNativeLibrary: System.loadLibrary failed: ${e.message}, trying alternative load...")
                                // Try loading from the application's native library directory
                                try {
                                    val nativeLibDir = java.io.File(applicationInfo.nativeLibraryDir)
                                    val jniFile = java.io.File(nativeLibDir, "libwhisper_jni.so")
                                    logToFile("loadWhisperNativeLibrary: looking for libwhisper_jni.so at ${jniFile.absolutePath}, exists=${jniFile.exists()}")
                                    if (jniFile.exists()) {
                                        System.load(jniFile.absolutePath)
                                        jniBridgeLoaded = true
                                        logToFile("loadWhisperNativeLibrary: JNI bridge loaded via System.load from ${jniFile.absolutePath}")
                                    } else {
                                        // List all .so files in native lib dir for debugging
                                        val soFiles = nativeLibDir.listFiles()?.filter { it.name.endsWith(".so") }?.map { it.name } ?: emptyList()
                                        logToFile("loadWhisperNativeLibrary: libwhisper_jni.so NOT FOUND in ${nativeLibDir.absolutePath}, available .so files: $soFiles")
                                    }
                                } catch (e2: UnsatisfiedLinkError) {
                                    logToFile("loadWhisperNativeLibrary: alternative load also failed: ${e2.message}")
                                } catch (e2: Exception) {
                                    logToFile("loadWhisperNativeLibrary: alternative load exception: ${e2.message}")
                                }
                            }
                        }
                        if (!jniBridgeLoaded) {
                            logToFile("loadWhisperNativeLibrary: whisper.cpp JNI bridge not available (will retry in processWhisperInChunks)")
                            // Don't return false - the bridge might be loaded by processWhisperInChunks later
                        }
                        return true
                    }
                }
            }

            logToFile("loadWhisperNativeLibrary: libwhisper.so not found in any engine directory")
            return false
        } catch (e: Exception) {
            logToFile("loadWhisperNativeLibrary: error: ${e.message}")
            return false
        }
    }

    /**
     * 使用Whisper模型生成字幕（当前版本需要whisper.cpp JNI集成）
     * 如果whisper.cpp库不可用，回退到Vosk引擎
     */
    private fun generateWithWhisper(
        episodeId: String, audioUrl: String, callback: SubtitleCallback, ctx: TaskContext
    ): Boolean {
        // v2.4.38: Prevent concurrent whisper processing.
        // Log showed two processWhisperInChunks running simultaneously,
        // causing chunk times of 124s instead of 14s.
        if (whisperProcessingActive) {
            logToFile("generateWithWhisper: [v2.4.38] SKIPPED - another whisper processing is active")
            ctx.log("Whisper处理跳过：另一个处理正在进行中")
            return false
        }
        whisperProcessingActive = true
        logToFile("generateWithWhisper: START [v2.1.2], episodeId=$episodeId, audioUrl=$audioUrl")

        // [v2.1.2] Check if this episode caused a native crash recently.
        // If so, skip Whisper and return error to prevent infinite crash loop.
        try {
            val crashMarker = getSharedPreferences("whisper_crash_marker", MODE_PRIVATE)
            val crashedEpisode = crashMarker.getString("crashed_episode", null)
            val crashTime = crashMarker.getLong("crash_time", 0L)
            val now = System.currentTimeMillis()
            // [v2.4.2] Removed 5-minute crash cooldown per user request.
            // User wants all Whisper models to be available immediately after failure.
            // The crash marker is still cleared to prevent stale data.
            if (crashedEpisode != null) {
                crashMarker.edit().clear().apply()
                logToFile("generateWithWhisper: [v2.4.2] cleared crash marker (was episode=$crashedEpisode, ${(now-crashTime)/1000}s ago)")
            }
        } catch (_: Exception) {}

        // [v2.0.76] Mark engine type for OOM kill detection
        try {
            getSharedPreferences("subtitle_restart_guard", MODE_PRIVATE).edit()
                .putString("lastEngine", "whisper")
                .apply()
        } catch (_: Exception) {}
        try {
            // 尝试通过反射调用whisper.cpp JNI
            ctx.log("Whisper engine: attempting to use on-device recognition...")

            // Issue 5: Check whisper native library first (no point processing audio if JNI unavailable)
            logToFile("generateWithWhisper: checking whisper native library")
            val nativeLibResult = loadWhisperNativeLibrary()
            logToFile("generateWithWhisper: loadWhisperNativeLibrary result=$nativeLibResult")
            if (!nativeLibResult) {
                val detail = "Whisper原生库(libwhisper.so)未找到。模型文件已下载但缺少JNI库。"
                ctx.lastErrorDetail = detail
                logToFile("generateWithWhisper: [v2.0.76] FAILED: $detail")
                ctx.log("ERROR: $detail")
                callback.onError("$detail 请在设置→离线引擎管理→安装Whisper引擎。")
                return false
            }

            // Issue 5: Check model file
            val whisperModel = findWhisperModel()
            val modelFile = if (whisperModel != null) java.io.File(whisperModel) else null
            logToFile("generateWithWhisper: checking model file at ${modelFile?.absolutePath}, exists=${modelFile?.exists()}")
            if (whisperModel == null || modelFile == null || !modelFile.exists()) {
                val detail = "Whisper模型文件未找到（路径=${modelFile?.absolutePath ?: whisperModel ?: "null"}）"
                ctx.lastErrorDetail = detail
                logToFile("generateWithWhisper: [v2.0.76] FAILED: $detail")
                ctx.log("ERROR: $detail")
                callback.onError("$detail 请在设置→离线引擎管理→下载Whisper引擎（tiny版约75MB）。")
                return false
            }

            // [v2.1.0] Unified PCM cache: always use ${episodeId}_5min.pcm (16kHz mono)
            val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this)
            val pcm16kFile = File(pcmCacheDir, "${episodeId}_5min.pcm")

            // [v2.4.10] For pre-cache subtitle generation, use FULL audio PCM instead of 5-min clip
            if (forceWhisperBaseModel) {
                logToFile("generateWithWhisper: [v2.4.10] pre-cache mode, generating FULL audio PCM")
                val fullPcmFile = File(pcmCacheDir, "${episodeId}_full.pcm")
                val statsStartTime = System.currentTimeMillis()  // [v2.4.16] For speed statistics
                var pcmDecodeTimeMs = 0L  // [v2.4.18] Track PCM decode time separately

                // [v2.4.20] Resume support: check if partial subtitles exist for this episode
                // If so, skip already-processed audio and append new subtitles instead of deleting old ones
                isResumeMode = false  // [v2.4.20] Reset for each episode
                var resumeFromSample = 0
                try {
                    val dbHelper = com.radio.app.database.RadioDatabaseHelper.getInstance(this)
                    val existingCount = dbHelper.getTranscriptCount(episodeId)
                    val isComplete = dbHelper.hasCompleteSubtitles(episodeId)
                    if (existingCount > 0 && !isComplete) {
                        // Partial subtitles exist — resume from last position
                        val maxEndMs = dbHelper.getMaxTranscriptEndMs(episodeId)
                        if (maxEndMs > 5000) {  // At least 5s of audio processed
                            resumeFromSample = ((maxEndMs / 1000.0) * 16000).toInt()
                            isResumeMode = true
                            logToFile("generateWithWhisper: [v2.4.20] RESUME MODE: found $existingCount partial transcripts, last end=${maxEndMs}ms, resuming from sample $resumeFromSample (${maxEndMs/1000}s)")
                            ctx.log("续传字幕 (从 ${maxEndMs/1000}s 处继续)")
                        }
                    }
                } catch (e: Exception) {
                    logToFile("generateWithWhisper: [v2.4.20] failed to check resume status: ${e.message}")
                }

                try {
                    // Check if full PCM already exists (from a previous run)
                    if (fullPcmFile.exists() && fullPcmFile.length() > 1024 * 100) {
                        val sizeMB = fullPcmFile.length() / 1024 / 1024
                        // [v2.4.18] Read PCM length BEFORE deleting
                        val pcmFileBytes = fullPcmFile.length()
                        val pcmDurationSec = pcmFileBytes / 2 / 16000  // 16kHz mono 16-bit
                        logToFile("generateWithWhisper: [v2.4.14] full PCM cache found (${sizeMB}MB, ${pcmDurationSec}s), resuming processing")
                        ctx.log("完整PCM缓存 (${sizeMB}MB)")
                        val result = processWhisperInChunks(fullPcmFile, whisperModel, callback, ctx, episodeId, resumeFromSample)  // [v2.4.20] pass resumeFromSample
                        // [v2.4.14] Only delete full PCM on success; keep for resume on failure
                        if (result) {
                            // [v2.4.18] Compute stats BEFORE deleting file
                            val totalTimeMs = System.currentTimeMillis() - statsStartTime
                            val speedRatio = if (totalTimeMs > 0) String.format("%.2f", pcmDurationSec.toDouble() / (totalTimeMs / 1000.0)) else "N/A"
                            logToFile("generateWithWhisper: [v2.4.18] STATS: episode=$episodeId, audioDuration=${pcmDurationSec}s, pcmDecodeTime=0ms (cached), whisperTime=${totalTimeMs}ms, totalTime=${totalTimeMs}ms, speed=${speedRatio}x (resumed from cache)")
                            fullPcmFile.delete()
                            // [v2.4.16] Fix: Also delete corresponding info file
                            val fullInfoFile = java.io.File(fullPcmFile.parentFile, fullPcmFile.nameWithoutExtension + ".info")
                            if (fullInfoFile.exists()) fullInfoFile.delete()
                            logToFile("generateWithWhisper: [v2.4.14] deleted full PCM + info after successful processing")
                        } else {
                            logToFile("generateWithWhisper: [v2.4.14] keeping full PCM for resume (processing failed)")
                        }
                        return result
                    }

                    // Need to generate full PCM from cached audio file
                    // Find the cached audio file
                    val audioFileName = try {
                        val url = java.net.URL(audioUrl)
                        url.path.substringAfterLast("/")
                    } catch (e: Exception) {
                        audioUrl.substringAfterLast("/")
                    }
                    val cacheFile = java.io.File(com.radio.app.RadioApplication.getEpisodesCacheDir(this), audioFileName)
                    if (!cacheFile.exists() || cacheFile.length() < 1024) {
                        logToFile("generateWithWhisper: [v2.4.10] cached audio not found: ${cacheFile.absolutePath}, falling back to 5min PCM")
                        // Fall through to 5-min PCM logic below
                    } else {
                        ctx.log("正在解码完整音频为PCM...")
                        logToFile("generateWithWhisper: [v2.4.10] decoding full audio to PCM: ${cacheFile.absolutePath} (${cacheFile.length()/1024/1024}MB)")
                        val decodeStartTime = System.currentTimeMillis()  // [v2.4.18] Track PCM decode time
                        val success = decodeFullAudioToPcm(cacheFile, fullPcmFile, ctx)
                        pcmDecodeTimeMs = System.currentTimeMillis() - decodeStartTime  // [v2.4.18] Record decode time
                        logToFile("generateWithWhisper: [v2.4.18] PCM decode took ${pcmDecodeTimeMs}ms")
                        if (success && fullPcmFile.exists() && fullPcmFile.length() > 1024 * 100) {
                            val sizeMB = fullPcmFile.length() / 1024 / 1024
                            // [v2.4.18] Read PCM length BEFORE deleting
                            val pcmFileBytes = fullPcmFile.length()
                            val pcmDurationSec = pcmFileBytes / 2 / 16000  // 16kHz mono 16-bit
                            logToFile("generateWithWhisper: [v2.4.10] full PCM generated (${sizeMB}MB, ${pcmDurationSec}s), processing with Whisper")
                            ctx.log("完整PCM解码完成 (${sizeMB}MB)")
                            // v2.4.39: PCM decode takes 130-370s. During that time, the service
                            // may have been killed and restarted by Android, setting globalCancelled=true.
                            // If this task's ctx is not cancelled, clear globalCancelled so the
                            // while loop in processWhisperInChunks can execute.
                            if (!ctx.cancelled.get()) {
                                logToFile("generateWithWhisper: [v2.4.39] clearing globalCancelled (was ${globalCancelled.get()}) before Whisper processing")
                                globalCancelled.set(false)
                            }
                            val whisperStartTime = System.currentTimeMillis()  // [v2.4.18] Track Whisper processing time
                            val result = processWhisperInChunks(fullPcmFile, whisperModel, callback, ctx, episodeId, resumeFromSample)  // [v2.4.20] pass resumeFromSample
                            val whisperTimeMs = System.currentTimeMillis() - whisperStartTime
                            // [v2.4.14] Only delete full PCM on success; keep for resume on failure
                            if (result) {
                                // [v2.4.18] Compute stats BEFORE deleting file
                                val totalTimeMs = System.currentTimeMillis() - statsStartTime
                                val speedRatio = if (totalTimeMs > 0) String.format("%.2f", pcmDurationSec.toDouble() / (totalTimeMs / 1000.0)) else "N/A"
                                logToFile("generateWithWhisper: [v2.4.18] STATS: episode=$episodeId, audioDuration=${pcmDurationSec}s, pcmDecodeTime=${pcmDecodeTimeMs}ms, whisperTime=${whisperTimeMs}ms, totalTime=${totalTimeMs}ms, speed=${speedRatio}x (full decode + whisper)")
                                fullPcmFile.delete()
                                // [v2.4.16] Fix: Also delete corresponding info file
                                val fullInfoFile = java.io.File(fullPcmFile.parentFile, fullPcmFile.nameWithoutExtension + ".info")
                                if (fullInfoFile.exists()) fullInfoFile.delete()
                                logToFile("generateWithWhisper: [v2.4.14] deleted full PCM + info after successful processing")
                            } else {
                                logToFile("generateWithWhisper: [v2.4.14] keeping full PCM for resume (processing failed)")
                            }
                            return result
                        } else {
                            logToFile("generateWithWhisper: [v2.4.10] full PCM decode failed, falling back to 5min PCM")
                            fullPcmFile.delete()
                            // [v2.4.16] Fix: Also delete corresponding info file on failure
                            val failInfoFile = java.io.File(fullPcmFile.parentFile, fullPcmFile.nameWithoutExtension + ".info")
                            if (failInfoFile.exists()) failInfoFile.delete()
                        }
                    }
                } catch (e: Exception) {
                    logToFile("generateWithWhisper: [v2.4.10] full PCM generation error: ${e.message}, falling back to 5min PCM")
                    fullPcmFile.delete()
                    // [v2.4.16] Fix: Also delete corresponding info file on error
                    val errInfoFile = java.io.File(fullPcmFile.parentFile, fullPcmFile.nameWithoutExtension + ".info")
                    if (errInfoFile.exists()) errInfoFile.delete()
                }
            }

            if (pcm16kFile.exists() && pcm16kFile.length() > 1024 * 500) {
                val sizeMB = pcm16kFile.length() / 1024 / 1024
                ctx.log("PCM cache found (${sizeMB}MB), using chunked Whisper processing")
                logToFile("generateWithWhisper: [v2.0.99] using PCM cache (${sizeMB}MB)")
                return processWhisperInChunks(pcm16kFile, whisperModel, callback, ctx, episodeId)
            }

            // No PCM cache — download and decode to 16kHz PCM
            val audioData = getAudioDataForProcessing(episodeId, audioUrl, ctx)
            if (audioData == null) {
                val detail = "音频数据获取失败（PCM缓存不可用，网络可能断开）"
                ctx.lastErrorDetail = detail
                ctx.log("ERROR: $detail")
                logToFile("generateWithWhisper: [v2.0.76] FAILED: $detail")
                callback.onError("$detail 请检查网络连接后重试。")
                return false
            }

            // [v2.0.99] Save to unified _5min.pcm file
            pcm16kFile.writeBytes(audioData)
            logToFile("generateWithWhisper: [v2.0.99] saved audio data to PCM cache (${audioData.size} bytes), calling processWhisperInChunks")
            return processWhisperInChunks(pcm16kFile, whisperModel, callback, ctx, episodeId)
        } catch (e: Exception) {
            val detail = if (e is OutOfMemoryError) "内存不足(OutOfMemoryError)" else "Whisper处理异常(${e.javaClass.simpleName}: ${e.message})"
            ctx.lastErrorDetail = detail
            logToFile("generateWithWhisper: [v2.0.76] EXCEPTION: $detail")
            ctx.log("ERROR: $detail")
            if (e is OutOfMemoryError) {
                callback.onError("内存不足：Whisper处理需要更多内存。请尝试：1)关闭其他应用 2)使用更小的Whisper模型（tiny版）")
            } else {
                callback.onError("Whisper处理失败: $detail")
            }
            return false
        } finally {
            // v2.4.38: Always clear the processing flag
            whisperProcessingActive = false
        }
    }

    /**
     * 分块处理大PCM文件（16kHz mono），供Whisper引擎使用
     * Issue 5: 通过 WhisperBridge JNI 桥接调用 whisper.cpp C API 进行识别。
     */
    private fun processWhisperInChunks(
        pcmFile: File, modelPath: String, callback: SubtitleCallback, ctx: TaskContext,
        episodeId: String = "",  // [v2.1.2] For crash marker
        resumeFromSampleParam: Int = 0  // [v2.4.20] Resume support: skip first N samples already processed
    ): Boolean {
        var resumeFromSample = resumeFromSampleParam  // v2.4.40: mutable copy for reset
        logToFile("processWhisperInChunks: START, pcmFile=${pcmFile.absolutePath}, modelPath=$modelPath, resumeFromSample=$resumeFromSample")
        // [v2.0.74] Issue 2 Fix: Report initial progress immediately so UI shows progress bar
        callback.onProgressUpdate(1, 100)
        // [v2.0.66] Issue 6: Set model name for broadcast
        // [v2.4.2] Use friendly name instead of raw filename
        currentModelName = getFriendlyModelName(modelPath)
        // [v2.4.31] Record the start time of Whisper processing to compute total processing
        // time (总耗时) and the speed ratio (倍率 = audio_duration / processing_time) for display.
        val processingStartTime = System.currentTimeMillis()

        try {
            val bridge = com.radio.app.whisper.WhisperBridge()

            // Load JNI bridge - try multiple approaches
            var jniBridgeLoaded = false
            try {
                System.loadLibrary("whisper_jni")
                jniBridgeLoaded = true
                logToFile("processWhisperInChunks: JNI bridge loaded via System.loadLibrary")
            } catch (e: UnsatisfiedLinkError) {
                if (e.message?.contains("already loaded") == true) {
                    jniBridgeLoaded = true
                    logToFile("processWhisperInChunks: JNI bridge already loaded")
                } else {
                    logToFile("processWhisperInChunks: System.loadLibrary failed: ${e.message}, trying alternative load...")
                    // Try loading from the application's native library directory
                    try {
                        val nativeLibDir = java.io.File(applicationInfo.nativeLibraryDir)
                        val jniFile = java.io.File(nativeLibDir, "libwhisper_jni.so")
                        logToFile("processWhisperInChunks: looking for libwhisper_jni.so at ${jniFile.absolutePath}, exists=${jniFile.exists()}")
                        if (jniFile.exists()) {
                            System.load(jniFile.absolutePath)
                            jniBridgeLoaded = true
                            logToFile("processWhisperInChunks: JNI bridge loaded via System.load from ${jniFile.absolutePath}")
                        } else {
                            // List all .so files in native lib dir for debugging
                            val soFiles = nativeLibDir.listFiles()?.filter { it.name.endsWith(".so") }?.map { it.name } ?: emptyList()
                            logToFile("processWhisperInChunks: libwhisper_jni.so NOT FOUND in ${nativeLibDir.absolutePath}, available .so files: $soFiles")
                        }
                    } catch (e2: UnsatisfiedLinkError) {
                        logToFile("processWhisperInChunks: alternative load also failed: ${e2.message}")
                    } catch (e2: Exception) {
                        logToFile("processWhisperInChunks: alternative load exception: ${e2.message}")
                    }
                }
            }
            if (!jniBridgeLoaded) {
                logToFile("processWhisperInChunks: whisper.cpp JNI bridge not available, reporting error")
                callback.onError("Whisper JNI桥接加载失败。libwhisper_jni.so未找到。")
                return false
            }

            // Set library path for dlopen (use full path to avoid soname mismatch issues)
            com.radio.app.whisper.WhisperBridge.getWhisperSoPath()?.let { soPath ->
                logToFile("processWhisperInChunks: setting whisper library path to $soPath")
                bridge.setLibraryPath(soPath)
            }

            // [v2.0.75] Issue 2 Fix: Check available memory BEFORE loading Whisper model.
            // [v2.0.76] Issue 2 Fix: More conservative memory check for Whisper.
            // Whisper tiny model (75MB on disk) needs ~200-300MB NATIVE memory for inference
            // (model weights + computation graph + mel spectrogram + attention buffers).
            // Java freeMemory() doesn't reflect native allocations, so we must check system availMem.
            // LMKD kills processes based on RSS, not Java heap.
            val actManager = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            val availMemMB = memInfo.availMem / 1024 / 1024
            val freeHeapMB = Runtime.getRuntime().freeMemory() / 1024 / 1024
            val maxHeapMB = Runtime.getRuntime().maxMemory() / 1024 / 1024
            val modelFile = java.io.File(modelPath)
            val modelSizeMB = if (modelFile.exists()) modelFile.length() / 1024 / 1024 else 0
            // [v2.0.93] Removed 10-minute cooldown per user request. Previously, after a Whisper OOM,
            // the service would refuse to retry Whisper for 10 minutes. Now we always allow retry.
            // Only log a warning if there was a recent OOM, but don't block the attempt.
            val oomMarker = getSharedPreferences("subtitle_oom_guard", MODE_PRIVATE)
            val lastOomTime = oomMarker.getLong("whisper_oom_time", 0L)
            val now = System.currentTimeMillis()
            if (lastOomTime > 0 && (now - lastOomTime) < 10L * 60 * 1000) {
                logToFile("processWhisperInChunks: [v2.0.93] WARNING: Recent OOM ${((now - lastOomTime) / 1000)}s ago, but proceeding per user request (no cooldown)")
            }
            logToFile("processWhisperInChunks: [v2.0.93] Memory: availMem=${availMemMB}MB, freeHeap=${freeHeapMB}MB, modelSize=${modelSizeMB}MB, lowRam=${memInfo.lowMemory}")
            // [v2.3.8] For large models (small=465MB), require more available memory.
            // Small model needs ~1.5GB to load + inference. If availMem < modelSize*2, abort early.
            if (modelSizeMB > 300 && availMemMB < modelSizeMB * 2) {
                val detail = "Whisper ${modelSizeMB}MB模型需要至少${modelSizeMB * 2}MB可用内存（当前${availMemMB}MB）"
                ctx.lastErrorDetail = detail
                logToFile("processWhisperInChunks: [v2.3.8] INSUFFICIENT MEMORY for large model: $detail")
                callback.onError("$detail。建议使用tiny(74MB)或base(141MB)模型，或关闭其他应用后重试。")
                return false
            }
            // [v2.4.25] REMOVED all pre-init GC calls and heap checks.
            // These were causing:
            // 1. 900ms delay from repeat(3) { gc + runFinalization + sleep(300) }
            // 2. 600ms delay from aggressive GC when heap < 4MB
            // 3. ABORT when heap < 2MB after GC ("INSUFFICIENT JAVA HEAP")
            // Native whisper uses native memory (mmap/malloc), NOT Java heap.
            // Low Java heap is NORMAL when native memory is in use.
            // The abort at freeHeap < 2MB was preventing processing entirely.
            logToFile("processWhisperInChunks: [v2.4.25] skipping heap checks (native code uses native memory)")

            // [v2.0.93] Lowered system memory requirement.
            // tiny model: ~75MB weights (mmap'd) + ~30-50MB runtime = ~105-125MB total.
            val requiredMemMB = modelSizeMB + 50
            if (availMemMB < requiredMemMB) {
                val detail = "Whisper内存不足（可用${availMemMB}MB，需要${requiredMemMB}MB=模型${modelSizeMB}MB+推理${requiredMemMB - modelSizeMB}MB）"
                ctx.lastErrorDetail = detail
                logToFile("processWhisperInChunks: [v2.0.93] INSUFFICIENT MEMORY for Whisper: availMem=${availMemMB}MB < required=$requiredMemMB")
                callback.onError("$detail。请关闭其他应用释放内存后重试。")
                return false
            }
            // [v2.0.91] Don't hard-block on lowMemory flag — it can be true even when enough mem exists.
            // Log warning but continue; LMKD will kill us if truly out of memory.
            if (memInfo.lowMemory) {
                logToFile("processWhisperInChunks: [v2.0.91] WARNING: System reports lowMemory=true, but availMem=${availMemMB}MB >= required=${requiredMemMB}MB. Attempting to load anyway...")
            }

            // Initialize whisper context
            logToFile("processWhisperInChunks: initializing whisper context with model=$modelPath")
            var ctxPtr: Long
            try {
                ctxPtr = bridge.initFromFile(modelPath)
            } catch (oom: OutOfMemoryError) {
                logToFile("processWhisperInChunks: [v2.0.75] OOM during whisper_init_from_file: ${oom.message}")
                callback.onError("Whisper模型加载内存不足。请使用tiny模型或减少同时运行的应用。")
                return false
            }
            if (ctxPtr == 0L) {
                logToFile("processWhisperInChunks: whisper_init_from_file failed (ctxPtr=0)")
                callback.onError("Whisper模型初始化失败。模型文件可能损坏或不兼容。")
                return false
            }
            logToFile("processWhisperInChunks: whisper context initialized, ctxPtr=$ctxPtr")
            // [v2.4.25] REMOVED post-init heap check and abort — was blocking processing when Java heap was low

            // [v2.0.74] Issue 2 Fix: PCM may be from different ranges due to fallback decode chain.
            // If PCM is smaller than expected (~9.6MB for 5min @ 16kHz), it's from a fallback range.
            val maxPcmBytes = 5L * 60 * 16000 * 2  // 5 minutes = 9,600,000 bytes
            val fileBytes = pcmFile.length()
            // [v2.4.10] For full PCM files (pre-cache mode), read entire file
            val isFullPcm = pcmFile.name.endsWith("_full.pcm")
            val expected5minBytes = maxPcmBytes
            val isFrom15MinRange = fileBytes >= expected5minBytes * 0.9
            // [v2.4.10] Full PCM starts from beginning, no offset needed
            val whisperOffsetMs = if (isFullPcm) 0L else (if (isFrom15MinRange) 15L * 60 * 1000 else 0L)
            // v2.4.54: For tiny model, process entire PCM file (not just 5 minutes).
            // The 5-min limit was designed for base model to avoid OOM, but tiny model
            // can handle full 2-hour audio. This fixes "未完成完整字幕，却标记完整字幕".
            val isTinyModel = modelPath.contains("tiny", ignoreCase = true)
            val bytesToRead = if (isFullPcm || isTinyModel) fileBytes.toInt() else minOf(fileBytes, maxPcmBytes).toInt()
            logToFile("[v2.4.54] processWhisperInChunks: isTinyModel=$isTinyModel, isFullPcm=$isFullPcm, fileBytes=$fileBytes, bytesToRead=$bytesToRead")
            if (bytesToRead <= 0) {
                logToFile("processWhisperInChunks: PCM too small (${fileBytes} bytes), aborting")
                callback.onError("音频文件太短，无法处理")
                try { bridge.free(ctxPtr) } catch (_: Exception) {}
                return false
            }
            logToFile("processWhisperInChunks: [v2.0.75] PCM=${fileBytes} bytes, reading ${bytesToRead} bytes (${bytesToRead / 16000 / 2}s), isFrom15Min=$isFrom15MinRange, isFullPcm=$isFullPcm, offsetMs=$whisperOffsetMs")

            // [v2.0.89] Issue 5 Fix: STREAMING PCM processing — do NOT load entire file into memory.
            // v2.0.78 loaded entire PCM as ByteArray(9.6MB) + FloatArray(19.2MB) = 29MB Java heap.
            // With only 1-11MB free heap, this always failed with OOM.
            // [v2.0.92] Use 3-second chunks (48000 samples) instead of 5s:
            // - 5s chunks (v2.0.90) caused SIGSEGV during whisper_full on devices with limited
            //   native memory. 3s chunks reduce mel spectrogram and encoder memory by 40%.
            // - JNI audio_ctx=128 covers ~2.56s, sufficient for 3s chunks (150 tokens).
            // - Per-chunk Java heap: 96KB ByteArray + 192KB FloatArray = 288KB total
            // - Whisper needs >=2s of audio for meaningful recognition; 3s is a good balance
            // [v2.0.98] Use 5-second chunks (80000 samples).
            // v2.0.97 used 3s chunks but still crashed with SIGSEGV.
            // Root cause: audio_ctx=0 (auto) defaults to 1500 (30s) which allocates too much
            // memory. v2.1.2 reduces chunk size from 5s to 1s (16000 samples) to reduce
            // encoder memory by 5x. audio_ctx=50 (1s) in JNI.
            // single_segment=false lets Whisper manage segments internally.
            // [v2.2.3] Use 4-second chunks (64000 samples).
            // [v2.3.0-fix] STRICT mode: consecutive failures abort with error (no engine switching).
            // [v2.3.2] Use 10-second chunks (160000 samples). Root cause of "0 segments" issue:
            // 4s chunks were too short for Whisper's internal VAD to detect Chinese speech reliably.
            // Additionally, two concurrent Whisper instances (subtitle task + segment task) were
            // running simultaneously, exhausting Java heap to 0-1MB, causing silent failures where
            // whisper_full() returned 0 but produced 0 segments due to OOM.
            // 10s chunks provide better speech context, and we also add concurrency guard below.
            // [v2.4.30] Smaller chunks for all models to reduce per-chunk processing time
            // and mitigate CPU thermal throttling on phones.
            // [v2.4.35] Reduce chunk sizes further - SIGABRT crash after chunk 0 suggests
            // memory pressure. Smaller chunks = less native memory per whisper_full() call.
            // - tiny (74MB): 15s chunks (was 20s)
            // - base (141MB): 10s chunks (was 15s)
            // - small (465MB): 10s chunks (was 10s)
            val chunkSize = when {
                modelSizeMB > 300 -> 10 * 16000  // small/medium: 10s (160000 samples)
                modelSizeMB > 100 -> 10 * 16000  // base: 10s (160000 samples)
                else -> 15 * 16000  // tiny: 15s (240000 samples)
            }
            // [v2.4.23] All models use SPEED mode (greedy) for faster processing
            // Previously tiny used ACCURACY(beam5) which was 3x slower (0.86x vs 2.71x speed)
            // Beam search doesn't significantly improve Chinese broadcast quality
            val optMode = com.radio.app.whisper.WhisperBridge.OPT_SPEED
            val optModeName = "SPEED(greedy)"
            logToFile("processWhisperInChunks: [v2.4.23] modelSize=${modelSizeMB}MB, chunkSize=${chunkSize/16000}s, optMode=$optModeName")

            // [v2.4.24] Reusable buffers to reduce GC pressure
            var pcmChunk: ByteArray? = null
            var chunkSamples: FloatArray? = null

            // [v2.4.8] Set optimization mode on native bridge before processing
            bridge.setOptMode(optMode)

            val allTranscripts = mutableListOf<com.radio.app.models.Transcript>()
            var chunkIdx = 0
            var consecutiveCrashes = 0
            val maxConsecutiveCrashes = 3

            // Open PCM file for streaming
            val pcmInput = java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(pcmFile), 65536))
            // [v2.4.20] Resume support: skip already-processed samples
            var totalSamplesRead = resumeFromSample
            val totalSamplesToRead = bytesToRead / 2  // 2 bytes per sample
            // v2.4.40: Critical fix - resumeFromSample from a PREVIOUS episode can be larger
            // than the current episode's PCM file. This causes the while loop to never execute
            // (totalSamplesRead >= totalSamplesToRead), producing 0 transcripts from 0 chunks.
            if (resumeFromSample > 0 && resumeFromSample >= totalSamplesToRead) {
                logToFile("processWhisperInChunks: [v2.4.40] RESUME RESET: resumeFromSample ($resumeFromSample) >= totalSamplesToRead ($totalSamplesToRead), starting from beginning")
                resumeFromSample = 0
                totalSamplesRead = 0
            }
            if (resumeFromSample > 0) {
                // Skip the first resumeFromSample samples (2 bytes each)
                val skipBytes = resumeFromSample.toLong() * 2
                val actuallySkipped = pcmInput.skip(skipBytes)
                logToFile("processWhisperInChunks: [v2.4.20] RESUME: skipped $actuallySkipped bytes ($resumeFromSample samples), starting from sample $resumeFromSample")
                if (actuallySkipped < skipBytes) {
                    logToFile("processWhisperInChunks: [v2.4.20] RESUME WARNING: could not skip all bytes, skipping ${actuallySkipped / 2} samples instead")
                    totalSamplesRead = (actuallySkipped / 2).toInt()
                }
            }

            logToFile("processWhisperInChunks: [v2.3.0] STREAMING processing $totalSamplesToRead samples in ${chunkSize/16000}s chunks (STRICT mode, no engine switching), offsetMs=$whisperOffsetMs")

            // [v2.4.18] Track 10-minute interval timing for Whisper processing
            val tenMinStartSamples = 10 * 60 * 16000  // 10 minutes of audio at 16kHz
            var lastTenMinCheckpoint = resumeFromSample  // [v2.4.20] Start checkpoint from resume position
            var tenMinSegmentStartTime = System.currentTimeMillis()
            var tenMinSegmentIdx = 0

            // [v2.1.2] Write crash marker BEFORE first chunk. If native crash kills process,
            // on restart we'll detect this and skip this episode.
            try {
                getSharedPreferences("whisper_crash_marker", MODE_PRIVATE).edit()
                    .putString("crashed_episode", episodeId)
                    .putLong("crash_time", System.currentTimeMillis())
                    .apply()
            } catch (_: Exception) {}

            while (totalSamplesRead < totalSamplesToRead && !ctx.cancelled.get()) {
                // Read one chunk of PCM bytes from file
                val samplesToRead = minOf(chunkSize, totalSamplesToRead - totalSamplesRead)
                if (samplesToRead < chunkSize / 10) {  // [v2.4.6] Dynamic threshold = 10% of chunk size
                    logToFile("processWhisperInChunks: [v2.3.2] last chunk too small ($samplesToRead samples), skipping")
                    break
                }
                val bytesForChunk = (samplesToRead * 2).toInt()
                // [v2.4.24] Reuse buffers to reduce GC pressure
                if (pcmChunk == null || pcmChunk!!.size < bytesForChunk) {
                    pcmChunk = ByteArray(bytesForChunk)
                }
                pcmInput.readFully(pcmChunk!!, 0, bytesForChunk)

                // Convert this chunk's PCM bytes to float samples
                // [v2.4.24] Reuse float buffer too
                if (chunkSamples == null || chunkSamples!!.size < samplesToRead) {
                    chunkSamples = FloatArray(samplesToRead)
                }
                for (i in 0 until samplesToRead) {
                    val sample = (pcmChunk!![i * 2].toInt() and 0xFF) or (pcmChunk!![i * 2 + 1].toInt() shl 8)
                    val shortSample = if (sample > 32767) sample - 65536 else sample
                    chunkSamples!![i] = shortSample.toFloat() / 32768.0f
                }
                // Free PCM byte array immediately
                // (Kotlin will GC it, but we can help by nulling the reference)

                val chunkStartSec = totalSamplesRead / 16000
                val chunkEndSec = (totalSamplesRead + samplesToRead) / 16000
                logToFile("processWhisperInChunks: [v2.0.90] chunk $chunkIdx: samples [$totalSamplesRead-${totalSamplesRead + samplesToRead}) ($samplesToRead samples, ${chunkStartSec}s-${chunkEndSec}s)")

                // [v2.0.91] Heap check — lower thresholds
                // [v2.4.3] Removed CRITICALLY LOW HEAP abort entirely.
                // whisper_full runs in NATIVE code and doesn't need Java heap.
                // The Java heap drops to 0-4MB after model init because Android compresses
                // Java heap when native heap uses memory. This is NORMAL and doesn't affect
                // whisper_full. Aborting here was the root cause of "输出条数偏少".
                // [v2.4.24] REMOVED aggressive GC calls — they were stealing CPU from the native
                // Whisper thread, causing 166s+ chunk times (0.18x speed).
                // Native code (whisper_full) doesn't need Java heap. GC pauses were the bottleneck.
                // Only log the heap status, don't trigger GC.
                val freeBeforeMB = Runtime.getRuntime().freeMemory() / 1024 / 1024
                if (freeBeforeMB < 5) {
                    // [v2.4.24] Only log, do NOT call System.gc() — it steals CPU from native thread
                    // Each gc() call was adding 50-150ms pause, with 3 calls = 450ms per chunk
                    // That's 450ms * 240 chunks = 108 seconds of pure GC overhead
                }

                // [v2.0.91] Check NATIVE available memory — lowered threshold for tiny model
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                val mi = android.app.ActivityManager.MemoryInfo()
                am?.getMemoryInfo(mi)
                val availNativeMB = mi.availMem / 1024 / 1024
                // [v2.2.5] Adjusted native threshold for 10s chunks with tiny model
                val whisperNativeThreshold = 100L
                if (availNativeMB < whisperNativeThreshold) {
                    logToFile("processWhisperInChunks: [v2.0.93] LOW NATIVE MEMORY before chunk $chunkIdx: availMem=${availNativeMB}MB < ${whisperNativeThreshold}MB, aborting")
                    if (allTranscripts.isEmpty()) {
                        val detail = "Whisper推理时系统可用内存不足（可用${availNativeMB}MB，推理需要约${whisperNativeThreshold}MB）"
                        ctx.lastErrorDetail = detail
                        callback.onError("$detail。请关闭其他应用释放内存后重试，或使用Vosk引擎。")
                        try { bridge.free(ctxPtr) } catch (_: Exception) {}
                        pcmInput.close()
                        return false
                    } else {
                        logToFile("processWhisperInChunks: [v2.0.91] Returning ${allTranscripts.size} partial transcripts before native OOM")
                        break
                    }
                }

                var chunkSuccess = false
                var chunkErrorCode = 0

                // [v2.4.35] DISABLED context recreation - it was causing SIGABRT crashes.
                // The crash occurs during bridge.free() when the whisper context is freed
                // while another thread might still be using it. Instead, let the context
                // accumulate state - the slowdown is acceptable compared to crashing.
                // if (chunkIdx > 0 && chunkIdx % 2 == 0) { ... }

                try {
                    // [v2.1.9] Add logging right before JNI call to pinpoint crash location
                    logToFile("processWhisperInChunks: [v2.3.0] chunk $chunkIdx: BEFORE bridge.full, ctxPtr=$ctxPtr, samples=$samplesToRead")
                    val result = bridge.full(ctxPtr, chunkSamples!!, samplesToRead)
                    logToFile("processWhisperInChunks: [v2.3.0] chunk $chunkIdx: AFTER bridge.full returned $result")

                    if (result == 0) {
                        val nSeg = bridge.fullNSegments(ctxPtr)
                        logToFile("processWhisperInChunks: chunk $chunkIdx: got $nSeg segments")
                        for (i in 0 until nSeg) {
                            if (ctx.cancelled.get()) break
                            val text = bridge.fullGetSegmentText(ctxPtr, i)
                            val t0 = bridge.fullGetSegmentT0(ctxPtr, i)
                            val t1 = bridge.fullGetSegmentT1(ctxPtr, i)
                            if (text.isNotBlank()) {
                                // [v2.4.3] Fix integer overflow: totalSamplesRead * 1000 overflows Int.MAX
                                // when totalSamplesRead >= 2147483 (chunk 5+ with 480000 samples/chunk).
                                // This caused timestamps to show 13:01 instead of 17:30.
                                // Fix: use Long arithmetic via .toLong()
                                val chunkOffsetMs = totalSamplesRead.toLong() * 1000L / 16000L
                                val transcript = com.radio.app.models.Transcript(
                                    text = text.trim(),
                                    segmentStart = whisperOffsetMs + chunkOffsetMs + t0 * 10,
                                    segmentEnd = whisperOffsetMs + chunkOffsetMs + t1 * 10
                                )
                                allTranscripts.add(transcript)
                                logToFile("processWhisperInChunks: chunk $chunkIdx seg $i: [${transcript.segmentStart}-${transcript.segmentEnd}ms] ${text.take(80)}")
                                callback.onSubtitleGenerated(transcript)
                            }
                        }
                        chunkSuccess = true
                        consecutiveCrashes = 0
                        // [v2.4.24] REMOVED post-chunk GC — was stealing CPU from native thread
                    } else {
                        chunkErrorCode = result
                        // [v2.3.0] Non-zero return from whisper_full is also a failure (not just exceptions).
                        // whisper_full returns: 0=success, 1=mel compute failed, 2=encode failed, 3=decode failed, etc.
                        // Count consecutive errors just like crashes to trigger Vosk fallback quickly.
                        consecutiveCrashes++
                        logToFile("processWhisperInChunks: [v2.3.0] chunk $chunkIdx failed with code $result (consecutive errors=$consecutiveCrashes)")
                    }
                } catch (oom: OutOfMemoryError) {
                    logToFile("processWhisperInChunks: [v2.0.90] chunk $chunkIdx OOM: ${oom.message}")
                    consecutiveCrashes++
                    System.gc()
                    Thread.sleep(500)
                } catch (e: Throwable) {
                    logToFile("processWhisperInChunks: [v2.0.90] chunk $chunkIdx CRASHED: ${e.javaClass.name}: ${e.message}")
                    consecutiveCrashes++
                }

                // [v2.3.0] Abort early after 3 consecutive failures (crashes OR error codes).
                // Previously we only counted exceptions, but whisper_full returning -1/1/2/3 also means failure.
                if (consecutiveCrashes >= 3) {
                    logToFile("processWhisperInChunks: [v2.3.0] $consecutiveCrashes consecutive failures (code=$chunkErrorCode), aborting. Transcripts so far: ${allTranscripts.size}")
                    // [v2.3.0] Dump native log tail for diagnostics
                    try {
                        val nativeLog = java.io.File(filesDir, "logs/subtitle/native.log")
                        if (nativeLog.exists()) {
                            val tail = nativeLog.readText().takeLast(2000)
                            logToFile("processWhisperInChunks: [v2.3.0] native log tail:\n$tail")
                        }
                    } catch (_: Exception) {}
                    if (allTranscripts.isEmpty()) {
                        val detail = "Whisper引擎推理失败（连续${consecutiveCrashes}次chunk返回错误码$chunkErrorCode）"
                        ctx.lastErrorDetail = detail
                        logToFile("processWhisperInChunks: [v2.3.0] $detail - aborting (STRICT mode, no fallback)")
                        callback.onError("$detail")
                        try { bridge.free(ctxPtr) } catch (_: Exception) {}
                        pcmInput.close()
                        return false
                    }
                    break
                }

                // [v2.4.41] Cooldown reduced from 2s to 300ms.
                // v2.4.38 used 2s to prevent thermal throttling with 8 threads.
                // v2.4.40 reduced threads to 2, which generates 4x less heat.
                // With 2 threads, 300ms is sufficient to prevent throttling.
                // 2s cooldown was wasting: 120 chunks * 2s = 240s = 4min of pure sleeping.
                // Expected speed improvement: from ~0.77x to ~1.3x.
                if (!ctx.cancelled.get() && !globalCancelled.get()) {
                    Thread.sleep(300)
                }

                totalSamplesRead += samplesToRead
                chunkIdx++
                val progress = ((totalSamplesRead.toLong() * 100) / totalSamplesToRead).toInt()
                callback.onProgressUpdate(progress, 100)

                // [v2.4.18] Log timing every 10 minutes of audio processed
                if (totalSamplesRead - lastTenMinCheckpoint >= tenMinStartSamples) {
                    val now = System.currentTimeMillis()
                    val segmentTimeMs = now - tenMinSegmentStartTime
                    val audioProcessedSec = totalSamplesRead / 16000
                    val segmentAudioSec = (totalSamplesRead - lastTenMinCheckpoint) / 16000
                    logToFile("processWhisperInChunks: [v2.4.18] 10-MIN-CHECKPOINT #${tenMinSegmentIdx}: audioAt=${audioProcessedSec}s, segmentAudio=${segmentAudioSec}s, segmentTime=${segmentTimeMs}ms, speed=${String.format("%.2f", segmentAudioSec.toDouble() / (segmentTimeMs / 1000.0))}x")
                    lastTenMinCheckpoint = totalSamplesRead
                    tenMinSegmentStartTime = now
                    tenMinSegmentIdx++
                }
            }

            // Close PCM input stream
            try { pcmInput.close() } catch (_: Exception) {}

            // [v2.1.2] Clear crash marker - processing completed without crash
            try {
                getSharedPreferences("whisper_crash_marker", MODE_PRIVATE).edit().clear().apply()
            } catch (_: Exception) {}

            // Free context
            try { bridge.free(ctxPtr) } catch (_: Exception) {}
            logToFile("processWhisperInChunks: COMPLETE, ${allTranscripts.size} transcripts generated from $chunkIdx chunks")
            if (allTranscripts.isEmpty()) {
                // [v2.3.8] Better error message — mention memory as possible cause for large models
                val memNote = if (modelSizeMB > 300) {
                    " 大模型(${modelSizeMB}MB)可能因内存不足导致推理失败，建议使用tiny(74MB)或base(141MB)模型。"
                } else {
                    " 音频可能是音乐/静音，或模型不匹配。"
                }
                val detail = "Whisper处理完成但未识别到任何内容（${chunkIdx}个chunk，0条字幕）${memNote}"
                ctx.lastErrorDetail = detail
                logToFile("processWhisperInChunks: [v2.3.8] WARNING: 0 transcripts generated! $detail")
                callback.onError("$detail")
                return false
            }
            // [v2.4.31] Record total processing time and audio duration for speed ratio display.
            // processing_time = wall-clock time of this run; audio_duration = samples processed
            // in THIS run (excludes resume-skipped samples so the ratio stays accurate).
            // speed ratio (倍率) = audio_duration / processing_time.
            currentProcessingTimeMs = System.currentTimeMillis() - processingStartTime
            currentAudioDurationMs = (totalSamplesRead - resumeFromSample).toLong() * 1000L / 16000L
            val ratioStr = if (currentProcessingTimeMs > 0)
                String.format("%.2f", currentAudioDurationMs.toDouble() / currentProcessingTimeMs) else "N/A"
            logToFile("processWhisperInChunks: [v2.4.31] timing: processingTime=${currentProcessingTimeMs}ms (${currentProcessingTimeMs / 1000}s), audioDuration=${currentAudioDurationMs}ms (${currentAudioDurationMs / 1000}s), ratio=${ratioStr}x")
            callback.onComplete(allTranscripts)
            return true

        } catch (e: UnsatisfiedLinkError) {
            val detail = "Whisper JNI桥接未找到(${e.message})"
            ctx.lastErrorDetail = detail
            logToFile("processWhisperInChunks: [v2.0.76] UnsatisfiedLinkError: $detail")
            callback.onError("$detail 请确保引擎文件已正确安装。")
            return false
        } catch (oom: OutOfMemoryError) {
            val detail = "Whisper内存不足(OutOfMemoryError: ${oom.message})"
            ctx.lastErrorDetail = detail
            logToFile("processWhisperInChunks: [v2.0.76] OOM EXCEPTION: $detail")
            callback.onError("$detail 请关闭其他应用或使用更小的模型。")
            return false
        } catch (e: Exception) {
            val detail = "Whisper处理异常(${e.javaClass.name}: ${e.message})"
            ctx.lastErrorDetail = detail
            logToFile("processWhisperInChunks: [v2.0.76] EXCEPTION: $detail")
            callback.onError("$detail")
            return false
        }
    }

    /**
     * 检查是否有预缓存的16kHz PCM文件（由RadioPlaybackService.decodeToPcmForPreCache生成）
     * 返回PCM文件路径，如果不存在则返回null
     */
    private fun find16kHzPcmCache(episodeId: String): File? {
        try {
            // [v2.1.0] Use centralized cache dir from RadioApplication
            val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this)
            // [v2.4.15] Cleaned up legacy _5min_16k.pcm migration code (v2.0.98 era, no longer needed)
            // [v2.1.0] Unified _5min.pcm file (always 16kHz mono)
            val pcmFile = File(pcmCacheDir, "${episodeId}_5min.pcm")
            // [v2.1.0] Raised minimum from 1024 to 500KB to reject corrupt tiny files
            val minValid = 500 * 1024  // ~15s of 16kHz mono audio
            if (pcmFile.exists() && pcmFile.length() >= minValid) {
                logToFile("find16kHzPcmCache: found PCM cache: ${pcmFile.absolutePath} (${pcmFile.length()} bytes)")
                return pcmFile
            } else if (pcmFile.exists()) {
                logToFile("find16kHzPcmCache: [v2.1.0] PCM too small (${pcmFile.length()} bytes < ${minValid}), deleting")
                try { pcmFile.delete() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            logToFile("find16kHzPcmCache: error: ${e.message}")
        }
        return null
    }

    private fun getAudioFile(audioUrl: String, onProgress: ((Int) -> Unit)? = null): File? {
        val cacheDir = getExternalFilesDir("audio")
        if (cacheDir != null) {
            val cachedFile = File(cacheDir, "${Math.abs(audioUrl.hashCode())}.mp3")
            if (cachedFile.exists() && cachedFile.length() > 1024) {
                onProgress?.invoke(14)
                Log.d(TAG, "Using external cache: ${cachedFile.absolutePath}")
                return cachedFile
            }
        }
        val episodesCacheDir = File(this.cacheDir, "episodes")
        if (episodesCacheDir.exists()) {
            try {
                val path = URL(audioUrl).path
                val fileName = path.substringAfterLast("/")
                if (fileName.isNotBlank()) {
                    val playbackCachedFile = File(episodesCacheDir, fileName)
                    if (playbackCachedFile.exists() && playbackCachedFile.length() > 1024) {
                        Log.d(TAG, "Reusing playback cache: ${playbackCachedFile.absolutePath}")
                        onProgress?.invoke(14)
                        return playbackCachedFile
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check playback cache: ${e.message}")
            }
        }
        if (audioUrl.endsWith(".m3u8", ignoreCase = true) || audioUrl.contains("/live/", ignoreCase = true)) {
            Log.w(TAG, "M3U8 stream cannot be downloaded: $audioUrl")
            return null
        }
        return downloadAudioWithProgress(audioUrl) { progress ->
            onProgress?.invoke((progress * 14 / 30).coerceAtMost(14))
        }
    }

    /**
     * v2.4.10: Decode entire audio file to 16kHz mono PCM for pre-cache subtitle generation.
     * Unlike decodeToPcm which only decodes 5 minutes, this decodes the FULL audio.
     * The resulting PCM file is temporary and should be deleted after Whisper processing.
     */
    private fun decodeFullAudioToPcm(
        audioFile: File, pcmFile: File, ctx: TaskContext
    ): Boolean {
        logToFile("decodeFullAudioToPcm: [v2.4.10] START, audio=${audioFile.absolutePath} (${audioFile.length()/1024/1024}MB)")
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var fos: FileOutputStream? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(audioFile.absolutePath)

            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    break
                }
            }
            if (audioTrack < 0) {
                logToFile("decodeFullAudioToPcm: [v2.4.10] no audio track found")
                return false
            }

            val format = extractor.getTrackFormat(audioTrack)
            extractor.selectTrack(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // [v2.4.15] Fix: Use var for sample rate/channels — they may change after INFO_OUTPUT_FORMAT_CHANGED
            // (e.g. HE-AAC v2: container says 22050Hz/1ch, but codec outputs 44100Hz/2ch after SBR+PS)
            var inSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var inChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val outSampleRate = 16000
            val outChannels = 1

            logToFile("decodeFullAudioToPcm: [v2.4.10] input: ${inSampleRate}Hz ${inChannels}ch -> output: ${outSampleRate}Hz ${outChannels}ch")

            fos = FileOutputStream(pcmFile)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var resampledBytes = 0L

            val maxDecodeTimeMs = 10 * 60 * 1000L  // 10 min max decode time
            val decodeStartTime = System.currentTimeMillis()

            var resamplePhase = 0.0
            var lastSample: Short = 0

            while (!outputDone && !ctx.cancelled.get()) {
                val now = System.currentTimeMillis()
                if (now - decodeStartTime > maxDecodeTimeMs) {
                    logToFile("decodeFullAudioToPcm: [v2.4.10] max decode time reached, stopping")
                    outputDone = true
                    break
                }

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val buffer = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val sampleTime = extractor.sampleTime
                            codec.queueInputBuffer(inIdx, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10000)
                // [v2.4.15] Fix: Handle INFO_OUTPUT_FORMAT_CHANGED — codec actual output may differ from container
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    try {
                        inSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        inChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        logToFile("decodeFullAudioToPcm: [v2.4.15] FORMAT_CHANGED: actual sampleRate=$inSampleRate, channels=$inChannels")
                    } catch (e: Exception) {
                        logToFile("decodeFullAudioToPcm: [v2.4.15] FORMAT_CHANGED but failed to read format: ${e.message}")
                    }
                }
                if (outIdx >= 0) {
                    val buffer = codec.getOutputBuffer(outIdx)
                    if (buffer != null && bufferInfo.size > 0) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        val pcmBytes = ByteArray(buffer.remaining())
                        buffer.get(pcmBytes)

                        val chunkShorts = ShortArray(pcmBytes.size / 2)
                        java.nio.ByteBuffer.wrap(pcmBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(chunkShorts)
                        val resampledTriple = resampleChunkContinuousSG(
                            chunkShorts, inSampleRate, inChannels,
                            outSampleRate, 1, resamplePhase, lastSample
                        )
                        val outBytes = resampledTriple.first
                        resamplePhase = resampledTriple.second
                        lastSample = resampledTriple.third
                        if (outBytes.isNotEmpty()) {
                            fos.write(outBytes)
                            resampledBytes += outBytes.size
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }

            fos.flush()
            fos.close()
            fos = null

            // Write info file
            val infoFile = File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".info")
            infoFile.writeText("sampleRate=$outSampleRate\nchannels=$outChannels\nversion=4")

            val sizeMB = pcmFile.length() / 1024 / 1024
            val durationSec = pcmFile.length() / 2 / outSampleRate
            logToFile("decodeFullAudioToPcm: [v2.4.10] DONE, output=${sizeMB}MB (${durationSec}s), took ${System.currentTimeMillis()-decodeStartTime}ms")
            return pcmFile.exists() && pcmFile.length() > 1024 * 100

        } catch (e: Exception) {
            logToFile("decodeFullAudioToPcm: [v2.4.10] EXCEPTION: ${e.message}")
            return false
        } finally {
            try { fos?.close() } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun decodeToPcm(
        audioFile: File, pcmFile: File, durationUs: Long,
        ctx: TaskContext, onProgress: ((Int) -> Unit)? = null,
        startUs: Long = 0L  // [v2.0.65] Issue 7 Fix: Start decoding from this position
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var fos: FileOutputStream? = null
        try {
            extractor.setDataSource(audioFile.absolutePath)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i; audioFormat = format; break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) throw Exception("No audio track found")

            extractor.selectTrack(audioTrackIndex)
            // [v2.0.72] Issue 2/3/6 Fix: Seek to startUs (15 min) so PCM contains 15-20 min audio.
            // Track the ACTUAL sample time we start at (after SEEK_TO_CLOSEST_SYNC, it may differ
            // from requested startUs). We need this to correctly compute the stop time.
            var actualStartUs = 0L
            if (startUs > 0) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                actualStartUs = extractor.sampleTime
                ctx.log("Decode: [v2.0.72] seeked to ~${startUs / 1000000}s (actual=${actualStartUs / 1000000}s), decoding ${durationUs / 1000000}s from there")
            }
            // [v2.0.72] Compute absolute stop time: startUs + durationUs
            val stopAtUs = if (startUs > 0) actualStartUs + durationUs else durationUs
            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            ctx.log("Decode: [v2.0.72] audio mime=$mime, durationUs=$durationUs, stopAtUs=${stopAtUs / 1000000}s, startUs=${actualStartUs / 1000000}s")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            fos = FileOutputStream(pcmFile)
            val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            // [v2.1.1] Mutable: will be updated on INFO_OUTPUT_FORMAT_CHANGED
            var actualInSampleRate = sampleRate
            var actualInChannels = channelCount
            ctx.log("Decode: sampleRate=$sampleRate, channels=$channelCount")

            var inputDone = false
            var outputDone = false
            var decodedBytes = 0L
            var lastProgressBytes = 0L
            var lastProgressTime = System.currentTimeMillis()
            var decodeStartTime = System.currentTimeMillis()
            // [v2.1.1] Global continuous resampler state
            var resamplePhase = 0.0
            var lastSample: Short = 0
            // 最大解码时长：5分钟（足够解码30分钟音频）
            val MAX_DECODE_TIME_MS = 5 * 60 * 1000L
            // 无进展超时：30秒没有新输出就认为卡住了
            val NO_PROGRESS_TIMEOUT_MS = 30000L
            // [v2.0.72] Expected PCM output for 5min @ 16kHz-mono-16bit = 5*60*16000*2 = 9,600,000 bytes.
            // Use 20MB cap (2x expected) to be safe.
            val EXPECTED_PCM_BYTES = (durationUs / 1_000_000.0 * SAMPLE_RATE * 2).toLong()  // 16-bit mono
            val MAX_PCM_BYTES = maxOf(EXPECTED_PCM_BYTES * 2, 20_000_000L)

            ctx.log("Decode: [v2.0.72] expected PCM ~${EXPECTED_PCM_BYTES / 1024}KB, max cap ${MAX_PCM_BYTES / 1024}KB")

            while (!outputDone) {
                if (ctx.cancelled.get() || globalCancelled.get()) break

                // 硬限制检查：解码输出超过预期上限时强制停止
                if (decodedBytes >= MAX_PCM_BYTES) {
                    ctx.log("Decode: hard byte limit reached ($decodedBytes >= $MAX_PCM_BYTES), stopping")
                    outputDone = true
                    break
                }

                // 解码超时检查
                val now = System.currentTimeMillis()
                val elapsed = now - decodeStartTime
                if (elapsed > MAX_DECODE_TIME_MS) {
                    ctx.log("Decode: timeout after ${elapsed/1000}s, forcing end")
                    outputDone = true
                    break
                }
                // 无进展超时检查
                if (decodedBytes > lastProgressBytes) {
                    lastProgressBytes = decodedBytes
                    lastProgressTime = now
                } else if (now - lastProgressTime > NO_PROGRESS_TIMEOUT_MS && inputDone) {
                    ctx.log("Decode: no output for ${(now-lastProgressTime)/1000}s after input done, forcing end")
                    outputDone = true
                    break
                }

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val buffer = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                            ctx.log("Decode: input EOF reached")
                        } else {
                            val currentSampleTime = extractor.sampleTime
                            // [v2.0.72] Bug PCM1 Fix: Compare ABSOLUTE sample time against stopAtUs
                            // (which is also absolute: actualStartUs + durationUs).
                            // Previously compared extractor.sampleTime (absolute, ~15min) against
                            // durationUs (relative, 5min=300M), which was ALWAYS true after seek,
                            // causing immediate EOS and 0 bytes PCM output.
                            if (durationUs > 0 && currentSampleTime >= stopAtUs) {
                                // Process the current sample first, then send EOS
                                codec.queueInputBuffer(inIdx, 0, sampleSize, currentSampleTime, 0)
                                extractor.advance()
                                // Send EOS on next buffer (need to dequeue another input buffer)
                                val eosIdx = codec.dequeueInputBuffer(10000)
                                if (eosIdx >= 0) {
                                    codec.queueInputBuffer(eosIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                }
                                inputDone = true
                                ctx.log("Decode: [v2.0.72] reached stop time at ${currentSampleTime / 1000000}s (stopAt=${stopAtUs / 1000000}s), sent EOS after last sample")
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, currentSampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // 多次尝试 dequeue output 以确保解码器完全排空
                var outputDrained = false
                var drainAttempts = 0
                while (!outputDrained && drainAttempts < 10) {
                    drainAttempts++
                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 5000)
                    when {
                        outIdx >= 0 -> {
                            val buffer = codec.getOutputBuffer(outIdx)!!
                            if (bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                val pcmBytes = ByteArray(bufferInfo.size)
                                buffer.position(bufferInfo.offset)
                                buffer.get(pcmBytes)
                                // [v2.1.1] Use continuous resampling with phase carry-over
                                val chunkShorts = ShortArray(pcmBytes.size / 2)
                                java.nio.ByteBuffer.wrap(pcmBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(chunkShorts)
                                val resampledTriple = resampleChunkContinuousSG(
                                    chunkShorts, actualInSampleRate, actualInChannels,
                                    SAMPLE_RATE, 1, resamplePhase, lastSample
                                )
                                resamplePhase = resampledTriple.second
                                lastSample = resampledTriple.third
                                val resampled = resampledTriple.first
                                fos.write(resampled)
                                decodedBytes += resampled.size
                                // [v2.0.72] Progress based on expected bytes, not hardcoded 60MB
                                val pct = if (EXPECTED_PCM_BYTES > 0) {
                                    (decodedBytes * 100 / EXPECTED_PCM_BYTES).toInt().coerceIn(0, 99)
                                } else {
                                    (decodedBytes * 100 / MAX_PCM_BYTES).toInt().coerceIn(0, 99)
                                }
                                onProgress?.invoke(pct)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                                outputDrained = true
                                ctx.log("Decode: output EOF received")
                            }
                            // 硬限制检查在输出后立即执行
                            if (decodedBytes >= MAX_PCM_BYTES) {
                                ctx.log("Decode: hard byte limit reached during drain, stopping")
                                outputDone = true
                                outputDrained = true
                            }
                        }
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            outputDrained = true
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // [v2.1.1] Re-read actual output format from codec
                            val newFormat = codec.outputFormat
                            try {
                                actualInSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                actualInChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                ctx.log("Decode: [v2.1.1] FORMAT_CHANGED: sampleRate=$actualInSampleRate, channels=$actualInChannels")
                            } catch (e: Exception) {
                                ctx.log("Decode: [v2.1.1] FORMAT_CHANGED but failed: ${e.message}")
                            }
                        }
                        else -> {
                            outputDrained = true
                        }
                    }
                }
            }
            // 确保进度报告到100%
            onProgress?.invoke(100)
            ctx.log("Decode [v2.0.72] complete: $decodedBytes PCM bytes in ${(System.currentTimeMillis() - decodeStartTime)/1000}s (expected ~${EXPECTED_PCM_BYTES} bytes)")
            // [v2.0.96] Write .info file so PCM playback uses correct sample rate.
            // decodeToPcm resamples to 16kHz mono via resamplePcmV2, so the output is always 16kHz mono.
            // Without this .info file, playPcmFile defaults to 16000 (correct by luck), but if
            // RadioPlaybackService previously created the .info with a different rate (e.g., 44100),
            // playback would use the wrong rate.
            try {
                val infoFile = File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".info")
                infoFile.writeText("sampleRate=${SAMPLE_RATE}\nchannels=1\nversion=3\nsource=SubtitleGeneratorService")
                logToFile("decodeToPcm: [v2.0.96] wrote .info file: sampleRate=${SAMPLE_RATE}, channels=1")
            } catch (e: Exception) {
                logToFile("decodeToPcm: [v2.0.96] failed to write .info file: ${e.message}")
            }
            try { codec.stop(); codec.release() } catch (_: Exception) {}
            try { fos.close() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "decodeToPcm failed", e)
            ctx.log("Decode EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            try { fos?.close() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * v2.1.1: Continuous resampling with cross-chunk phase preservation.
     * Same algorithm as RadioPlaybackService.resampleChunkContinuous.
     * Returns: Triple(outputBytes, newPhase, lastSample)
     */
    private fun resampleChunkContinuousSG(
        input: ShortArray, inSampleRate: Int, inChannels: Int,
        outSampleRate: Int, outChannels: Int,
        prevPhase: Double, prevLastSample: Short
    ): Triple<ByteArray, Double, Short> {
        if (inSampleRate == outSampleRate && inChannels == outChannels) {
            val bytes = ByteArray(input.size * 2)
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(input)
            return Triple(bytes, 0.0, if (input.isNotEmpty()) input[input.size - 1] else prevLastSample)
        }

        val ratio = inSampleRate.toDouble() / outSampleRate
        val inputFrames = input.size / inChannels

        val monoInput: ShortArray = if (inChannels > 1) {
            val arr = ShortArray(inputFrames)
            for (i in 0 until inputFrames) {
                var sum = 0
                for (c in 0 until inChannels) {
                    sum += input[i * inChannels + c].toInt()
                }
                arr[i] = (sum / inChannels).toShort()
            }
            arr
        } else {
            input
        }

        if (monoInput.size < 1) {
            return Triple(ByteArray(0), prevPhase, prevLastSample)
        }

        val extendedInput = ShortArray(monoInput.size + 1)
        extendedInput[0] = prevLastSample
        System.arraycopy(monoInput, 0, extendedInput, 1, monoInput.size)

        val availableInputRange = extendedInput.size - 1
        var currentPhase = prevPhase
        val outputSamples = ArrayList<Short>(512)

        while (currentPhase < availableInputRange) {
            val srcIdx = currentPhase.toInt()
            val frac = currentPhase - srcIdx
            if (srcIdx + 1 < extendedInput.size) {
                val sample = (extendedInput[srcIdx] * (1.0 - frac) + extendedInput[srcIdx + 1] * frac).toInt().toShort()
                outputSamples.add(sample)
            }
            currentPhase += ratio
        }

        val newPhase = currentPhase - availableInputRange
        val newLastSample = monoInput[monoInput.size - 1]

        val outShorts = outputSamples.toShortArray()
        val outBytes = ByteArray(outShorts.size * 2)
        java.nio.ByteBuffer.wrap(outBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outShorts)
        return Triple(outBytes, newPhase, newLastSample)
    }

    /**
     * v2.0.72 Improved PCM resampler with proper stereo-to-mono downmix.
     * Previous resamplePcm() only used the first (Left) channel sample,
     * losing the Right channel and causing reduced volume/distortion.
     * This version:
     * 1. First downmixes multi-channel to mono by averaging all channels per frame
     * 2. Then does linear-interpolation resampling to target sample rate
     */
    private fun resamplePcmV2(input: ByteArray, inSampleRate: Int, inChannels: Int,
                              outSampleRate: Int, outChannels: Int): ByteArray {
        if (inSampleRate == outSampleRate && inChannels == outChannels) {
            return input
        }

        // Step 1: Convert bytes to shorts
        val inShorts = ShortArray(input.size / 2)
        ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(inShorts)

        // Step 2: Downmix to mono if needed (average all channels per frame)
        val monoShorts: ShortArray = if (inChannels > 1) {
            val frames = inShorts.size / inChannels
            val mono = ShortArray(frames)
            for (f in 0 until frames) {
                var sum = 0
                for (c in 0 until inChannels) {
                    sum += inShorts[f * inChannels + c].toInt()
                }
                mono[f] = (sum / inChannels).toShort()
            }
            mono
        } else {
            inShorts
        }

        // If sample rates match and output is mono, just convert back to bytes
        if (inSampleRate == outSampleRate && outChannels == 1) {
            val result = ByteArray(monoShorts.size * 2)
            ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(monoShorts)
            return result
        }

        // Step 3: Resample to target sample rate (linear interpolation)
        val ratio = inSampleRate.toDouble() / outSampleRate
        val outFrames = if (outChannels > 1) {
            // For multi-channel output, we'd need channel duplication, but we always output mono
            (monoShorts.size / ratio).toInt()
        } else {
            (monoShorts.size / ratio).toInt()
        }
        val outLength = outFrames * outChannels
        val output = ShortArray(outLength.coerceAtLeast(1))
        var outIdx = 0
        var inIdx = 0.0
        while (outIdx < outLength) {
            val srcFrame = inIdx.toInt().coerceAtMost(monoShorts.size - 1)
            val nextFrame = (srcFrame + 1).coerceAtMost(monoShorts.size - 1)
            val frac = inIdx - inIdx.toInt()
            val interpolated = (monoShorts[srcFrame] * (1.0 - frac) + monoShorts[nextFrame] * frac).toInt().toShort()
            // Fill all output channels with same mono sample
            for (c in 0 until outChannels) {
                if (outIdx < outLength) output[outIdx++] = interpolated
            }
            inIdx += ratio
        }
        val result = ByteArray(output.size * 2)
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(output)
        return result
    }

    private fun resamplePcm(input: ByteArray, inSampleRate: Int, inChannels: Int,
                            outSampleRate: Int, outChannels: Int): ByteArray {
        if (inSampleRate == outSampleRate && inChannels == outChannels) {
            return input
        }

        val shorts = ShortArray(input.size / 2)
        ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        val ratio = inSampleRate.toDouble() / outSampleRate
        val outLength = (shorts.size / inChannels / ratio).toInt() * outChannels
        val output = ShortArray(outLength.coerceAtLeast(1))
        var outIdx = 0; var inIdx = 0.0
        while (outIdx < outLength) {
            val srcIdx = inIdx.toInt() * inChannels
            val nextIdx = ((inIdx.toInt() + 1) * inChannels).coerceAtMost(shorts.size - inChannels)
            val frac = inIdx - inIdx.toInt()
            val interpolated = (shorts[srcIdx] * (1.0 - frac) + shorts[nextIdx] * frac).toInt().toShort()
            output[outIdx] = interpolated
            outIdx++; inIdx += ratio
        }
        val result = ByteArray(output.size * 2)
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(output)
        return result
    }

    private fun parseVoskResult(jsonResult: String): String {
        return try {
            val json = org.json.JSONObject(jsonResult)
            json.optString("text", "")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Vosk result: $jsonResult")
            ""
        }
    }

    private fun downloadAudioWithProgress(audioUrl: String, onProgress: (Int) -> Unit): File? {
        var outFile: File? = null; var conn: HttpURLConnection? = null
        try {
            outFile = File(cacheDir, "subtitle_audio_${Math.abs(audioUrl.hashCode())}.tmp")
            var downloadUrl = audioUrl; var redirectCount = 0
            while (redirectCount < 5) {
                val url = URL(downloadUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"; conn.connectTimeout = 15000; conn.readTimeout = 60000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
                conn.connect()
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location"); conn.disconnect(); conn = null
                    if (location.isNullOrBlank()) break
                    downloadUrl = if (location.startsWith("http")) location else URL(URL(downloadUrl), location).toString()
                    redirectCount++
                } else if (code == 200) break
                else { conn.disconnect(); return null }
            }
            if (conn == null || conn.responseCode != 200) return null
            val totalBytes = conn.contentLength.toLong()
            var downloadedBytes = 0L; var lastReportedPct = 0
            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    val buf = ByteArray(8192); var len: Int
                    while (input.read(buf).also { len = it } != -1) {
                        if (globalCancelled.get()) { outFile?.delete(); return null }
                        output.write(buf, 0, len); downloadedBytes += len
                        if (totalBytes > 0) {
                            val pct = (downloadedBytes * 30 / totalBytes).toInt()
                            if (pct > lastReportedPct) { lastReportedPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
            return outFile
        } catch (e: Exception) {
            Log.e(TAG, "downloadAudio failed: ${e.message}")
            outFile?.delete(); return null
        } finally { conn?.disconnect() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel_all") {
            cancelAllTasks()
            cancelProgressNotification()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        globalCancelled.set(false)
        if (intent != null) {
            val action = intent.action

            val episodeId = intent.getStringExtra("episode_id") ?: return START_NOT_STICKY
            val audioUrl = intent.getStringExtra("audio_url") ?: return START_NOT_STICKY
            val taskType = intent.getStringExtra("task_type") ?: "subtitle"
            val isPreCacheSubtitle = intent.getBooleanExtra("precache_subtitle", false)
            val forceWhisperBase = intent.getBooleanExtra("force_whisper_base", false)
            val taskLabel = if (taskType == "segment") "AI分段" else "字幕生成"

            logToFile("onStartCommand: starting foreground for $taskLabel, episode=$episodeId")

            // [v2.0.70] Issue 2 Fix: Save restart guard info for OOM detection
            try {
                getSharedPreferences("subtitle_restart_guard", MODE_PRIVATE).edit()
                    .putString("lastEpisodeId", episodeId)
                    .putLong("lastStartTime", System.currentTimeMillis())
                    .apply()
            } catch (_: Exception) {}

            if (wakeLock == null) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SubtitleGenService:WakeLock")
            }
            wakeLock?.acquire(3600000)

            // [v2.0.58] Issue 2+3+4 Fix: Start task directly in onStartCommand
            // When service runs in :subtitle process, Activity binder call fails (BinderProxy != LocalBinder)
            // So we must start the task here. Broadcasts are sent by wrappedCallback for cross-process communication.
            // v2.4.54: Build initial notification WITH episode date+title (was empty before)
            val initialDisplayTitle = try {
                buildDisplayTitle(episodeId, audioUrl)
            } catch (e: Exception) { "" }
            val progressNotification = createProgressNotification(0, taskLabel, initialDisplayTitle)
            try { startForeground(NOTIFICATION_ID, progressNotification) }
            catch (e: Exception) { Log.w(TAG, "startForeground failed: ${e.message}") }

            // Only start if not already running (prevents duplicate when Activity also binds in-process)
            if (!activeTasks.containsKey(episodeId)) {
                // [v2.4.10] Set force Whisper base flag for pre-cache subtitle generation
                forceWhisperBaseModel = forceWhisperBase
                logToFile("onStartCommand: [v2.0.58] starting $taskType task for episode=$episodeId (cross-process mode), forceWhisperBase=$forceWhisperBase, isPreCache=$isPreCacheSubtitle")
                if (taskType == "segment") {
                    val dummyCallback = object : SegmentCallback {
                        override fun onSegmentGenerated(segment: VoiceSegment) {}
                        override fun onProgressUpdate(progress: Int, total: Int) {}
                        override fun onError(error: String) {}
                        override fun onComplete(segments: List<VoiceSegment>) {}
                    }
                    generateSegmentsForEpisode(episodeId, audioUrl, dummyCallback)
                } else {
                    val dummyCallback = object : SubtitleCallback {
                        override fun onSubtitleGenerated(transcript: Transcript) {}
                        override fun onProgressUpdate(progress: Int, total: Int) {}
                        override fun onError(error: String) {}
                        override fun onComplete(transcripts: List<Transcript>) {}
                    }
                    generateSubtitlesForEpisode(episodeId, audioUrl, dummyCallback)
                }
            } else {
                logToFile("onStartCommand: task already running for $episodeId, skipping")
            }
        }
        return START_NOT_STICKY
    }

    private fun cleanupTask() {
        // [v2.0.70] Issue 2 Fix: Clear restart guard when task completes normally
        try {
            getSharedPreferences("subtitle_restart_guard", MODE_PRIVATE).edit().clear().apply()
        } catch (_: Exception) {}
        // [v2.4.10] Reset force Whisper base flag after task completes
        forceWhisperBaseModel = false
        // 只有当没有任何活跃任务时才停止前台服务和移除通知
        if (activeTasks.isEmpty()) {
            logToFile("cleanupTask: no more active tasks, stopping foreground")
            // [v2.4.13] Clear busy flag — subtitle service is now idle
            try { SUBTITLE_BUSY_FLAG.delete() } catch (_: Exception) {}
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            releaseWakeLock()
            stopSelf()
        } else {
            logToFile("cleanupTask: ${activeTasks.size} tasks still active, keeping notification")
            // 还有其他任务在运行，更新通知显示剩余任务
            val remainingTask = activeTasks.values.firstOrNull()
            if (remainingTask != null) {
                updateProgressNotification(remainingTask.lastReportedProgress, remainingTask.taskType)
            }
        }
    }

    // [Fix] Ensure episode_info has a non-empty date and title before subtitle generation
    // proceeds. Auto-started (pre-cache/background) tasks are launched via Intent with only
    // episodeId + audioUrl, so the episode_info row may be missing entirely or exist with an
    // empty date/title. When that happens we fill in the current date and a default title
    // ("广播节目录音_YYYY-MM-DD") so the episode always shows a date and title, and so the
    // notification display title (buildDisplayTitle) and the episode list render correctly.
    private fun ensureEpisodeInfo(episodeId: String, audioUrl: String) {
        try {
            val dbHelper = RadioDatabaseHelper.getInstance(this)
            val existing = dbHelper.getEpisodeInfo(episodeId)
            val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            if (existing == null) {
                // No episode_info row at all — create one with default date/title.
                val ep = com.radio.app.models.Episode().apply {
                    this.id = episodeId
                    this.title = "广播节目录音_$currentDate"
                    this.broadcastAt = currentDate
                    this.audioUrl = audioUrl
                }
                dbHelper.saveEpisodeInfo(ep)
                logToFile("ensureEpisodeInfo: created missing episode_info for $episodeId (title=广播节目录音_$currentDate, date=$currentDate)")
            } else {
                val dateBlank = existing.broadcastAt.isNullOrBlank()
                val titleBlank = existing.title.isNullOrBlank()
                if (dateBlank || titleBlank) {
                    // Row exists but is missing date and/or title — fill them in, preserving
                    // any already-present metadata (station, duration, etc.).
                    if (dateBlank) existing.broadcastAt = currentDate
                    if (titleBlank) existing.title = "广播节目录音_$currentDate"
                    dbHelper.saveEpisodeInfo(existing)
                    logToFile("ensureEpisodeInfo: filled missing date/title for $episodeId (title=${existing.title}, broadcastAt=${existing.broadcastAt})")
                }
            }
        } catch (e: Exception) {
            logToFile("ensureEpisodeInfo: failed for $episodeId: ${e.message}")
        }
    }

    // [v2.4.18] Build display title from episodeId for notification
    private fun buildDisplayTitle(episodeId: String, audioUrl: String): String {
        return try {
            // episodeId format: "station-date-index" e.g. "henan-private-car-2024-07-23-2"
            // Extract date from episodeId
            val dateMatch = Regex("(\\d{4}-\\d{2}-\\d{2})").find(episodeId)
            val dateStr = dateMatch?.value ?: ""
            // Try to get title from database
            val dbHelper = com.radio.app.database.RadioDatabaseHelper.getInstance(this)
            val episodeInfo = dbHelper.getEpisodeInfo(episodeId)
            val title = episodeInfo?.title ?: audioUrl.substringAfterLast("/").substringBeforeLast(".")
            if (dateStr.isNotEmpty()) "$dateStr $title" else title
        } catch (e: Exception) {
            episodeId
        }
    }

    private fun createProgressNotification(progress: Int, taskLabel: String, overrideTitle: String? = null): Notification {
        // [v2.4.19] Split date+title into title and content for better display
        // v2.4.54: Allow override title for initial notification before task is in activeTasks
        val currentTitle = overrideTitle ?: activeTasks.values.firstOrNull()?.displayTitle ?: ""
        // [v2.4.19] Use short title for collapsed view, full info in expanded view
        val notifTitle = if (currentTitle.isNotBlank()) currentTitle else "正在处理音频"
        val notifContent = "$taskLabel: ${progress}%"
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Add cancel action
        val cancelIntent = Intent(this, SubtitleGeneratorService::class.java).apply { action = "cancel_all" }
        val cancelPending = PendingIntent.getService(
            this, 99, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notifTitle)
            .setContentText(notifContent)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$notifTitle\n$notifContent")
                .setSummaryText("正在处理音频"))
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateProgressNotification(progress: Int, taskLabel: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, createProgressNotification(progress, taskLabel))
        } catch (_: Exception) {}
    }

    /**
     * Cancel the progress notification immediately (used on error/cancel).
     * Explicitly removes the notification via NotificationManager.cancel() and
     * stops foreground state, so the ongoing "正在处理音频" notification can be dismissed.
     */
    private fun cancelProgressNotification() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
    }

    fun getSubtitles(episodeId: String): List<Transcript>? = dbHelper?.getTranscripts(episodeId)
    fun isOfflineAvailable(): Boolean = findVoskModel() != null || findWhisperModel() != null

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        logToFile("onDestroy: cleaning up, activeTasks=${activeTasks.size}")
        cancelAllTasks()
        executor?.shutdownNow()
        try {
            getSharedPreferences("player_processing_state", MODE_PRIVATE).edit().clear().apply()
        } catch (_: Exception) {}
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }
}
