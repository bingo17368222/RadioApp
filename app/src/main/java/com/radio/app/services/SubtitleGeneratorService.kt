package com.radio.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
        val logFile: File
    ) {
        @Volatile var lastReportedProgress = 0
        @Volatile var startTime = System.currentTimeMillis()
        @Volatile var lastOutputTime = System.currentTimeMillis()
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

    override fun onCreate() {
        super.onCreate()
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
                    logToFile("[v2.0.51] onCreate: Whisper crash detected (crashCount=${settings.whisperCrashCount}), NOT disabling Whisper per user request")
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "音频处理", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
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

    /**
     * Generate subtitles with independent per-task progress
     */
    fun generateSubtitlesForEpisode(episodeId: String, audioUrl: String, callback: SubtitleCallback) {
        val taskId = "sub_${episodeId}_${System.currentTimeMillis()}"
        val logFile = prepareTaskLogFile("subtitle", episodeId)
        val ctx = TaskContext(taskId, episodeId, "SUBTITLE", logFile)
        if (activeTasks.putIfAbsent(episodeId, ctx) != null) {
            Log.w(TAG, "Subtitle task already running for $episodeId, skipping duplicate")
            return
        }
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
                        "endMs" to transcript.segmentEnd
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
                callback.onComplete(transcripts)
                // [跨进程] 发送完成广播
                sendSubtitleBroadcast(
                    "com.radio.app.SUBTITLE_COMPLETE",
                    mapOf(
                        "episodeId" to episodeId
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

                // 根据ASR引擎设置选择模型
                val settings = AppSettings.getInstance(this@SubtitleGeneratorService)
                val asrProvider = settings.safeAsrProvider()
                ctx.log("ASR provider: $asrProvider")
                logToFile("generateSubtitlesForEpisode: ASR engine selected = $asrProvider, episodeId=$episodeId")

                when {
                    asrProvider == AppSettings.ASR_WHISPER || asrProvider == "whisper-local" -> {
                        logToFile("generateSubtitlesForEpisode: entering Whisper branch")
                        val whisperModel = findWhisperModel()
                        if (whisperModel != null) {
                            ctx.log("Using Whisper model: $whisperModel")
                            val success = generateWithWhisper(episodeId, audioUrl, wrappedCallback, ctx)
                            if (!success && !ctx.cancelled.get()) {
                                ctx.log("Whisper subtitle generation FAILED")
                                // [v2.0.52] Issue 2: No Vosk fallback per user requirement
                                // Whisper must work on its own
                            }
                        } else {
                            ctx.log("ERROR: Whisper model selected but not found")
                            wrappedCallback.onError("Whisper引擎未安装：缺少ggml模型文件。请在设置→离线引擎管理→下载Whisper引擎（tiny版约75MB）")
                            // Ensure task is cleaned up on error
                            activeTasks.remove(episodeId)
                            cleanupTask()
                        }
                    }
                    else -> {
                        // Vosk or default
                        logToFile("generateSubtitlesForEpisode: entering Vosk/default branch")
                        val voskModel = findVoskModel()
                        if (voskModel != null) {
                            ctx.log("Using Vosk model: $voskModel")
                            val pcm16kCache = find16kHzPcmCache(episodeId)
                            if (pcm16kCache != null) {
                                ctx.log("Found 16kHz PCM cache for $episodeId: ${pcm16kCache.absolutePath}")
                            }
                            val success = generateWithVosk(episodeId, audioUrl, wrappedCallback, ctx)
                            if (!success && !ctx.cancelled.get()) {
                                ctx.log("Vosk subtitle generation FAILED (error already reported by generateWithVosk)")
                            }
                        } else {
                            ctx.log("WARNING: No Vosk model found, trying Whisper fallback...")
                            // Auto-fallback to Whisper
                            val whisperModel = findWhisperModel()
                            if (whisperModel != null) {
                                ctx.log("Fallback: Using Whisper model: $whisperModel")
                                val success = generateWithWhisper(episodeId, audioUrl, wrappedCallback, ctx)
                                if (!success && !ctx.cancelled.get()) {
                                    ctx.log("Whisper fallback FAILED")
                                }
                            } else {
                                ctx.log("ERROR: No Vosk model found and no Whisper model available")
                                wrappedCallback.onError("未找到离线识别模型，请在设置→离线引擎管理→下载Vosk或Whisper模型")
                                // Ensure task is cleaned up on error
                                activeTasks.remove(episodeId)
                                cleanupTask()
                            }
                        }
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
                val asrProvider = settings.safeAsrProvider()
                ctx.log("Segment ASR provider: $asrProvider")
                logToFile("generateSubtitlesForEpisode: ASR engine selected = $asrProvider, episodeId=$episodeId (segments)")

                when {
                    asrProvider == AppSettings.ASR_WHISPER || asrProvider == "whisper-local" -> {
                        logToFile("generateSubtitlesForEpisode: entering Whisper branch (segments)")
                        val whisperModel = findWhisperModel()
                        if (whisperModel != null) {
                            ctx.log("Using Whisper model for segments: $whisperModel")
                            val success = generateWithWhisper(episodeId, audioUrl, subtitleCallback, ctx)
                            if (!success && !ctx.cancelled.get()) {
                                ctx.log("Whisper segment generation FAILED")
                            }
                        } else {
                            ctx.log("ERROR: Whisper model selected but not found for segments")
                            wrappedCallback.onError("Whisper引擎未安装：缺少ggml模型文件。请在设置→离线引擎管理→下载Whisper引擎（tiny版约75MB）")
                            // Ensure task is cleaned up on error
                            activeTasks.remove(segKey)
                            cleanupTask()
                        }
                    }
                    else -> {
                        logToFile("generateSubtitlesForEpisode: entering Vosk/default branch (segments)")
                        val voskModel = findVoskModel()
                        if (voskModel != null) {
                            ctx.log("Using Vosk model for segments: $voskModel")
                            // Check for pre-cached 16kHz PCM file
                            val pcm16kCache = find16kHzPcmCache(episodeId)
                            if (pcm16kCache != null) {
                                ctx.log("Found 16kHz PCM cache for segments $episodeId: ${pcm16kCache.absolutePath}")
                            }
                            val success = generateWithVosk(episodeId, audioUrl, subtitleCallback, ctx)
                            if (!success && !ctx.cancelled.get()) {
                                ctx.log("Vosk segment generation FAILED (error already reported by generateWithVosk)")
                            }
                        } else {
                            ctx.log("ERROR: No Vosk model for segments")
                            wrappedCallback.onError("未找到离线识别模型，请在设置→离线引擎管理→下载Vosk或Whisper模型")
                            // Ensure task is cleaned up on error
                            activeTasks.remove(segKey)
                            cleanupTask()
                        }
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
        logToFile("generateWithVosk: START, episodeId=$episodeId, audioUrl=$audioUrl")
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

            // Check for 16kHz PCM cache that's too large for in-memory: use chunked processing
            val pcmCacheDir = File(getExternalFilesDir(null), "pcm_cache")
            val pcm16kFile = File(pcmCacheDir, "${episodeId}_5min_16k.pcm")
            logToFile("generateWithVosk: PCM file=${pcm16kFile.absolutePath}, size=${pcm16kFile.length()}, willChunk=${pcm16kFile.length() > 1024}")
            // [v2.0.57] Issue 3+4 Fix: Always use chunked processing to avoid OOM
            // Previous threshold was 50MB - but even 7MB PCM + 1.3GB model = OOM
            if (pcm16kFile.exists() && pcm16kFile.length() > 1024) {
                val sizeMB = pcm16kFile.length() / 1024 / 1024
                ctx.log("[v2.0.57] Using chunked Vosk processing (PCM=${sizeMB}MB)")
                return processVoskInChunks(pcm16kFile, modelPath, callback, ctx)
            }

            ctx.log("Initializing Vosk recognizer with model: $modelPath")
            var recognizer: Any? = null
            var model: Any? = null
            try {
                model = voskModelClass!!.getConstructor(String::class.java).newInstance(modelPath)
                recognizer = voskRecognizerClass!!.getConstructor(voskModelClass, Float::class.javaPrimitiveType)
                    .newInstance(model, 16000.0f as java.lang.Float)
            } catch (e: Throwable) {
                val errorMsg = "Vosk Recognizer init FAILED: ${e.javaClass.name}: ${e.message}"
                ctx.log(errorMsg)
                writeCrashLog("vosk_init_crash", "Vosk Recognizer 初始化崩溃", "模型路径: $modelPath\n", e)
                callback.onError("Vosk模型初始化失败：${e.message}。请尝试重新下载Vosk模型（设置→离线引擎管理）")
                return false
            }
            ctx.log("Vosk recognizer created successfully")

            // Enable word-level output for timestamps
            try {
                val setWordsMethod = voskRecognizerClass!!.getMethod("setWords", Boolean::class.javaPrimitiveType)
                setWordsMethod.invoke(recognizer, true)
                logToFile("generateWithVosk: setWords(true) called")
            } catch (e: Exception) {
                logToFile("generateWithVosk: setWords failed: ${e.message}")
            }

            // Get audio data - prefer 16kHz PCM cache
            val audioData = getAudioDataForProcessing(episodeId, audioUrl, ctx)
            if (audioData == null) {
                // Try chunked processing for original PCM cache if it exists
                val pcmCacheDir2 = File(getExternalFilesDir(null), "pcm_cache")
                val pcmFile = File(pcmCacheDir2, "${episodeId}_5min.pcm")
                if (pcmFile.exists() && pcmFile.length() > 1024) {
                    // Original PCM cache needs resampling - resample to 16kHz temp file first
                    ctx.log("Original PCM cache exists, attempting resample + chunked processing")
                    val infoFile = File(pcmCacheDir2, "${episodeId}_5min.info")
                    var inSampleRate = 44100
                    var inChannels = 2
                    if (infoFile.exists()) {
                        val info = infoFile.readText()
                        val srMatch = Regex("sampleRate=(\\d+)").find(info)
                        if (srMatch != null) inSampleRate = srMatch.groupValues[1].toInt()
                        val chMatch = Regex("channels=(\\d+)").find(info)
                        if (chMatch != null) inChannels = chMatch.groupValues[1].toInt()
                    }
                    // If already 16kHz mono, use chunked directly
                    if (inSampleRate == 16000 && inChannels == 1) {
                        try { voskRecognizerClass!!.getMethod("close").invoke(recognizer) } catch (_: Exception) {}
                        return processVoskInChunks(pcmFile, modelPath, callback, ctx)
                    }
                }
                ctx.log("ERROR: Failed to get audio data")
                callback.onError("音频处理失败：无法获取音频数据。请检查网络连接后重试")
                try { voskRecognizerClass!!.getMethod("close").invoke(recognizer) } catch (_: Exception) {}
                return false
            }

            ctx.log("Processing ${audioData.size} bytes of audio data with Vosk")
            val totalBytes = audioData.size
            // [v2.0.50] Issue 4 Fix: Go back to chunkSize=8192 (v2.0.39/40 produced good results with this size)
            // v2.0.45-49 used 16384-32768 which caused 100% noise output
            val chunkSize = 8192
            var offset = 0
            var lastProgress = 0
            val acceptWaveFormMethod = voskRecognizerClass!!.getMethod("acceptWaveForm", ByteArray::class.java, Int::class.javaPrimitiveType)
            val getResultMethod = voskRecognizerClass!!.getMethod("getResult")
            val getPartialResultMethod = voskRecognizerClass!!.getMethod("getPartialResult")
            val getFinalResultMethod = voskRecognizerClass!!.getMethod("getFinalResult")

            val allTranscripts = mutableListOf<com.radio.app.models.Transcript>()
            var lastPartialText = ""
            var chunkCount = 0
            while (offset < audioData.size && !ctx.cancelled.get()) {
                val end = minOf(offset + chunkSize, audioData.size)
                val chunk = audioData.copyOfRange(offset, end)
                val accepted = acceptWaveFormMethod.invoke(recognizer, chunk, chunk.size) as? Boolean ?: false
                chunkCount++
                // [v2.0.52] Issue 3 Fix: Only process when accepted=true (natural boundary)
                // Remove periodic partial saves (savePartial) which interfered with Vosk's recognition
                // by calling getResult()/getPartialResult() every 20 chunks, disrupting internal state
                if (accepted) {
                    lastPartialText = ""
                    val result = getResultMethod.invoke(recognizer) as? String ?: ""
                    if (result.isNotBlank()) {
                        try {
                            val json = org.json.JSONObject(result as String)
                            val text = json.optString("text", "").trim()
                            if (text.isNotBlank()) {
                                // Parse timestamps from Vosk result
                                // [v2.0.54] Add 15-min offset since we skip first 15 minutes of audio
                                val offsetMs = 15L * 60 * 1000  // 15 minutes in ms
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
                                // [v2.0.52] No filtering - let all transcripts through
                                val duration = endTime - startTime
                                val transcript = com.radio.app.models.Transcript(text = text, segmentStart = startTime, segmentEnd = endTime)
                                logToFile("generateWithVosk: [v2.0.52] transcript #${allTranscripts.size + 1}: start=${startTime}ms, end=${endTime}ms, dur=${duration}ms, chars=${text.replace(" ", "").length}, text='${text.take(80)}'")
                                allTranscripts.add(transcript)
                                callback.onSubtitleGenerated(transcript)
                            }
                        } catch (e: Exception) { /* skip */ }
                    }
                }
                offset = end
                val progress = (offset * 100 / totalBytes)
                if (progress > lastProgress + 5) {
                    lastProgress = progress
                    callback.onProgressUpdate(progress, 100)
                }
            }

            // Get final result
            val finalResult = getFinalResultMethod.invoke(recognizer) as? String ?: ""
            if (finalResult.isNotBlank()) {
                try {
                    val json = org.json.JSONObject(finalResult as String)
                    val text = json.optString("text", "")
                    if (text.isNotBlank()) {
                        // Parse timestamps from Vosk result
                        var startTime = 0L
                        var endTime = 0L
                        val resultArr = json.optJSONArray("result")
                        if (resultArr != null && resultArr.length() > 0) {
                            val firstWord = resultArr.getJSONObject(0)
                            val lastWord = resultArr.getJSONObject(resultArr.length() - 1)
                            startTime = (firstWord.optDouble("start", 0.0) * 1000).toLong()
                            endTime = (lastWord.optDouble("end", 0.0) * 1000).toLong()
                        } else {
                            // Fallback: use accumulated byte offset for approximate timestamps
                            // offset is in bytes, 16000Hz * 2 bytes/sample = 32000 bytes per second
                            // chunk is out of scope after the loop, use chunkSize for end estimate
                            startTime = offset * 1000L / 32000L
                            endTime = (offset + chunkSize) * 1000L / 32000L
                        }
                        val transcript = com.radio.app.models.Transcript(text = text, segmentStart = startTime, segmentEnd = endTime)
                        logToFile("generateWithVosk: final transcript #${allTranscripts.size + 1}: start=${startTime}ms, end=${endTime}ms, text='${text.take(50)}...'")
                        allTranscripts.add(transcript)
                        callback.onSubtitleGenerated(transcript)
                    }
                } catch (e: Exception) { /* skip */ }
            }

            try { voskRecognizerClass!!.getMethod("close").invoke(recognizer) } catch (_: Exception) {}
            ctx.log("Vosk processing complete, ${allTranscripts.size} transcripts")
            callback.onComplete(allTranscripts)
            return true
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
    private fun processVoskInChunks(
        pcmFile: File, modelPath: String, callback: SubtitleCallback, ctx: TaskContext
    ): Boolean {
        logToFile("processVoskInChunks: START, pcmFile=${pcmFile.absolutePath}, size=${pcmFile.length()}")
        val totalSize = pcmFile.length()
        // [v2.0.54] Issue 4: Process 15-20 min range to avoid background music in first 5 min
        val startOffsetBytes = 15L * 60 * 16000 * 2  // 15 min offset
        val maxBytes = 5L * 60 * 16000 * 2  // 5 minutes from offset
        // [v2.0.55] Issue 4 Fix: If PCM is under 15 min, process from beginning instead of erroring
        val skipped15Min = totalSize > startOffsetBytes
        val processLimit = if (skipped15Min) {
            if (totalSize > startOffsetBytes + maxBytes) maxBytes
            else (totalSize - startOffsetBytes)
        } else {
            // PCM too short for 15-min offset, process from beginning (max 5 min)
            minOf(totalSize, maxBytes)
        }
        logToFile("processVoskInChunks: [v2.0.55] totalSize=${totalSize / 1024}KB, skipped15Min=$skipped15Min, offset=${if (skipped15Min) startOffsetBytes / 1024 else 0}KB, limit=${processLimit / 1024}KB (5min)")
        ctx.log("processVoskInChunks: PCM file size=${totalSize / 1024 / 1024}MB, processing ${if (skipped15Min) "15-20min" else "0-5min"} range in 8192-byte chunks")
        callback.onProgressUpdate(0, 100)

        val fileInputStream = java.io.FileInputStream(pcmFile)
        val inputStream = java.io.DataInputStream(fileInputStream)
        // [v2.0.54] Skip first 15 minutes (only if PCM is long enough)
        // [v2.0.55] Issue 4 Fix: Use skipped15Min flag to decide whether to skip
        if (skipped15Min) {
            inputStream.skip(startOffsetBytes)
        }
        val chunkSize = 8192 // 256ms per chunk - better balance for Vosk speech detection
        val buffer = ByteArray(chunkSize)
        var offset = 0L  // [v2.0.54] offset relative to the 15-min mark
        var lastProgress = 0
        var chunkCount = 0
        var acceptTrueCount = 0
        var acceptFalseCount = 0
        var lastPartialText = ""
        // Issue 9: Dedicated Vosk log - log start parameters
        writeVoskLog("processVoskInChunks START: modelPath=$modelPath, chunkSize=$chunkSize, totalSize=$totalSize, processLimit=$processLimit")

        var recognizer: Any? = null
        var model: Any? = null
        try {
            // Initialize Vosk recognizer
            val nativeLoaded = loadVoskNativeLibrary()
            logToFile("processVoskInChunks: nativeLibraryLoaded=$nativeLoaded")
            if (!nativeLoaded) {
                ctx.log("ERROR: Failed to load Vosk native library in chunked mode")
                callback.onError("Vosk引擎未安装：缺少libvosk.so原生库。请在设置→离线引擎管理→下载Vosk引擎（约50MB）")
                return false
            }
            val classesLoaded = ensureVoskClasses()
            logToFile("processVoskInChunks: classesLoaded, voskModelClass=${voskModelClass != null}, voskRecognizerClass=${voskRecognizerClass != null}")
            if (!classesLoaded) {
                ctx.log("ERROR: Vosk classes not available in chunked mode")
                callback.onError("Vosk引擎未安装：缺少Java库文件。请在设置→离线引擎管理→下载Vosk引擎")
                return false
            }

            logToFile("processVoskInChunks: creating Vosk Model with path=$modelPath")
            logToFile("processVoskInChunks: model dir exists=${File(modelPath).exists()}, contents=${File(modelPath).list()?.toList()}")
            try {
                model = voskModelClass!!.getConstructor(String::class.java).newInstance(modelPath)
                val modelDir = java.io.File(modelPath)
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
                logToFile("processVoskInChunks: Recognizer methods: ${voskRecognizerClass!!.declaredMethods.map { "${it.name}(${it.parameterTypes.map { p -> p.simpleName }})" }}")
            } catch (oom: OutOfMemoryError) {
                logToFile("processVoskInChunks: [v2.0.57] OOM loading model: ${oom.message}")
                callback.onError("内存不足无法加载Vosk模型。大模型需要1.3GB内存，请使用小模型或关闭其他应用")
                return false
            } catch (e: Exception) {
                logToFile("processVoskInChunks: [v2.0.57] FAILED to create Model/Recognizer: ${e.javaClass.name}: ${e.message}")
                e.stackTrace.take(10).forEach { logToFile("processVoskInChunks: at $it") }
                callback.onError("Vosk模型初始化失败: ${e.message}")
                return false
            } catch (e: UnsatisfiedLinkError) {
                logToFile("processVoskInChunks: [v2.0.57] UnsatisfiedLinkError creating Model: ${e.message}")
                callback.onError("Vosk原生方法调用失败: ${e.message}")
                return false
            }
            ctx.log("Vosk recognizer created for chunked processing")

            val acceptWaveFormMethod = voskRecognizerClass!!.getMethod("acceptWaveForm", ByteArray::class.java, Int::class.javaPrimitiveType)
            val getResultMethod = voskRecognizerClass!!.getMethod("getResult")
            val getPartialResultMethod = voskRecognizerClass!!.getMethod("getPartialResult")
            val getFinalResultMethod = voskRecognizerClass!!.getMethod("getFinalResult")

            // Issue 7: Verify PCM format
            logToFile("processVoskInChunks: PCM file size=${totalSize} bytes, expected duration=${totalSize / (16000 * 2)}s, sampleRate=16000, channels=1, bitsPerSample=16")
            val firstChunk = ByteArray(32)
            val firstBytesRead = inputStream.read(firstChunk)
            logToFile("processVoskInChunks: first 32 bytes (hex): ${firstChunk.joinToString(" ") { String.format("%02x", it) }}")
            // Reset stream to beginning
            fileInputStream.channel.position(0)

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
                logToFile("processVoskInChunks: chunk $chunkCount, offset=$offset, bytesRead=$bytesRead, acceptWaveForm=$acceptResult")

                // Issue 6: Force getResult() every 2 chunks (~0.5s) even if acceptWaveForm returns false.
                // Radio audio is continuous speech with few silence boundaries, so acceptWaveForm rarely
                // returns true. Forcing getResult() more frequently ensures Vosk outputs recognized text.
                val forceResult = (chunkCount % 2 == 0)
                if (forceResult) {
                    writeVoskLog("processVoskInChunks: forceResult triggered at chunk=$chunkCount, offset=$offset, acceptResult=$acceptResult")
                }

                if (acceptResult || forceResult) {
                    val result = getResultMethod.invoke(recognizer) as? String ?: ""
                    if (acceptResult) {
                        writeVoskLog("processVoskInChunks: acceptWaveForm=true at chunk $chunkCount, offset=$offset, bytesRead=$bytesRead, result='${result.take(200)}'")
                    } else {
                        writeVoskLog("processVoskInChunks: forceResult at chunk $chunkCount, offset=$offset, result='${result.take(200)}'")
                    }
                    // Issue 6: log whether getResult returned empty or not after each forceResult
                    if (forceResult) {
                        writeVoskLog("processVoskInChunks: forceResult getResult empty=${result.isBlank()} at chunk=$chunkCount")
                    }
                    logToFile("processVoskInChunks: raw result: '${result.take(200)}'")
                    if (result.isNotBlank()) {
                        try {
                            val json = org.json.JSONObject(result)
                            val text = json.optString("text", "").trim()
                            if (text.isBlank()) {
                                logToFile("processVoskInChunks: chunk $chunkCount produced empty text")
                            }
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
                                    startTime = offsetMs + offset * 1000L / 32000L
                                    endTime = offsetMs + (offset + chunk.size) * 1000L / 32000L
                                }
                                val transcript = com.radio.app.models.Transcript(text = text, segmentStart = startTime, segmentEnd = endTime)
                                logToFile("processVoskInChunks: transcript #${allTranscripts.size + 1}: start=${startTime}ms, end=${endTime}ms, text='${text.take(50)}...'")
                                allTranscripts.add(transcript)
                                // Issue 8: Log time to first transcript
                                if (allTranscripts.size == 1) {
                                    val firstResultTime = System.currentTimeMillis() - processingStartTime
                                    logToFile("processVoskInChunks: FIRST transcript at ${firstResultTime}ms from processing start")
                                }
                                callback.onSubtitleGenerated(transcript)
                            }
                        } catch (_: Exception) { /* skip malformed JSON */ }
                    }
                }

                // Issue 6: On forceResult (every 2 chunks), if result text was empty, try to get partial result and save it
                if (chunkCount % 2 == 0) {
                    val partial = getPartialResultMethod.invoke(recognizer) as? String ?: ""
                    if (partial.isNotBlank()) {
                        try {
                            val partialJson = org.json.JSONObject(partial)
                            val partialText = partialJson.optString("partial", "").trim()
                            if (partialText.isNotEmpty() && partialText != lastPartialText) {
                                lastPartialText = partialText
                                // [v2.0.55] Issue 3 Fix: Add 15-min offset to partial timestamps
                                val partialOffsetMs = if (skipped15Min) 15L * 60 * 1000 else 0L
                                val partialStartTime = partialOffsetMs + offset * 1000L / 32000L
                                val partialEndTime = partialOffsetMs + (offset + chunk.size) * 1000L / 32000L
                                val transcript = com.radio.app.models.Transcript(
                                    text = partialText,
                                    segmentStart = partialStartTime,
                                    segmentEnd = partialEndTime
                                )
                                logToFile("processVoskInChunks: forceResult partial transcript at ${partialStartTime}ms: '${partialText.take(50)}...'")
                                writeVoskLog("forceResult partial transcript saved: chunk=$chunkCount, text='${partialText.take(100)}'")
                                allTranscripts.add(transcript)
                                callback.onSubtitleGenerated(transcript)
                            }
                        } catch (_: Exception) { /* skip */ }
                    } else {
                        writeVoskLog("forceResult at chunk $chunkCount: getResult and partial both returned empty")
                    }
                }

                // Issue 9: If both getResult and partial returned empty on forceResult,
                // try resetting the recognizer to flush buffered text
                if (forceResult && !acceptResult) {
                    try {
                        // Call endOfUtterance to force Vosk to finalize current recognition
                        val endOfUtteranceMethod = voskRecognizerClass?.getMethod("endOfUtterance")
                        if (endOfUtteranceMethod != null) {
                            endOfUtteranceMethod.invoke(recognizer)
                            val flushResult = getResultMethod.invoke(recognizer) as? String ?: ""
                            if (flushResult.isNotBlank()) {
                                val flushJson = org.json.JSONObject(flushResult)
                                val flushText = flushJson.optString("text", "").trim()
                                if (flushText.isNotEmpty() && flushText != lastPartialText) {
                                    lastPartialText = flushText
                                    // [v2.0.55] Issue 3 Fix: Add 15-min offset to flush timestamps
                                    val flushOffsetMs = if (skipped15Min) 15L * 60 * 1000 else 0L
                                    val flushStart = flushOffsetMs + offset * 1000L / 32000L
                                    val flushEnd = flushOffsetMs + (offset + chunk.size) * 1000L / 32000L
                                    val transcript = com.radio.app.models.Transcript(text = flushText, segmentStart = flushStart, segmentEnd = flushEnd)
                                    logToFile("processVoskInChunks: endOfUtterance flush transcript at ${flushStart}ms: '${flushText.take(50)}...'")
                                    writeVoskLog("endOfUtterance flush: chunk=$chunkCount, text='${flushText.take(100)}'")
                                    allTranscripts.add(transcript)
                                    callback.onSubtitleGenerated(transcript)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logToFile("processVoskInChunks: endOfUtterance attempt failed: ${e.message}")
                    }
                }

                // Issue 9: Log progress every 200 chunks (~50s)
                if (chunkCount % 200 == 0) {
                    writeVoskLog("processVoskInChunks: progress - chunk=$chunkCount, offset=$offset, transcripts=${allTranscripts.size}, acceptTrue=$acceptTrueCount, acceptFalse=$acceptFalseCount")
                }

                offset += bytesRead
                logToFile("processVoskInChunks: processed chunk $chunkCount, totalBytes=$offset, transcripts so far=${allTranscripts.size}")
                val progress = (offset * 100 / totalSize).toInt()
                if (progress > lastProgress + 5) {
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
            if (e is OutOfMemoryError) {
                logToFile("processVoskInChunks: OOM ERROR")
                ctx.log("ERROR: OOM during chunked Vosk processing")
                callback.onError("内存不足：音频处理需要更多内存。请尝试：1)关闭其他应用 2)在设置→离线引擎管理→切换到Whisper引擎")
            } else {
                logToFile("processVoskInChunks: EXCEPTION: ${e.javaClass.name}: ${e.message}")
                e.stackTrace.take(10).forEach { logToFile("processVoskInChunks: at $it") }
                ctx.log("ERROR: Vosk chunked processing failed: ${e.javaClass.name}: ${e.message}")
                callback.onError("Vosk处理失败: ${e.message}")
            }
            return false
        } finally {
            try { recognizer?.let { voskRecognizerClass!!.getMethod("close").invoke(it) } } catch (_: Exception) {}
            try { inputStream.close() } catch (_: Exception) {}
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
        // 1) Try 16kHz PCM cache first (should be small, ~60MB for 30min)
        val pcmCacheDir = File(getExternalFilesDir(null), "pcm_cache")
        val pcm16kFile = File(pcmCacheDir, "${episodeId}_5min_16k.pcm")
        if (pcm16kFile.exists() && pcm16kFile.length() > 1024) {
            ctx.log("Using 16kHz PCM cache: ${pcm16kFile.length()} bytes")
            logToFile("generateSubtitlesForEpisode: PCM converted, size=${pcm16kFile.length()}")
            // 16kHz PCM is ~60MB, safe to read entirely
            if (pcm16kFile.length() < 50_000_000) {
                val data = pcm16kFile.readBytes()
                if (data.isEmpty() || data.size < 1024) {
                    ctx.log("ERROR: 16kHz PCM cache data is empty or too small (${data.size} bytes), falling through")
                } else {
                    val cacheTime = System.currentTimeMillis() - startTime
                    logToFile("getAudioDataForProcessing: 16kHz PCM cache hit, time=${cacheTime}ms, size=${data.size}")
                    return data
                }
            } else {
                // File too large for in-memory: signal caller to use chunked processing
                val sizeMB = pcm16kFile.length() / 1024 / 1024
                ctx.log("音频文件过大（${sizeMB}MB），需要分块处理（16kHz PCM cache）")
                return null
            }
        }
        // 2) Try original PCM cache - stream resample to avoid OOM
        val pcmFile = File(pcmCacheDir, "${episodeId}_5min.pcm")
        if (pcmFile.exists() && pcmFile.length() > 1024) {
            // Safety check: skip PCM cache if file is too large for in-memory processing
            val pcmFileSize = pcmFile.length()
            if (pcmFileSize > 200 * 1024 * 1024) {
                val sizeMB = pcmFileSize / 1024 / 1024
                ctx.log("音频文件过大（${sizeMB}MB），需要分块处理（original PCM cache）")
                return null
            } else {
            ctx.log("Stream-resampling original PCM to 16kHz (file size=${pcmFile.length()})")
            // Read .info for sample rate
            val infoFile = File(pcmCacheDir, "${episodeId}_5min.info")
            var inSampleRate = 44100
            var inChannels = 2
            if (infoFile.exists()) {
                val info = infoFile.readText()
                val srMatch = Regex("sampleRate=(\\d+)").find(info)
                if (srMatch != null) inSampleRate = srMatch.groupValues[1].toInt()
                val chMatch = Regex("channels=(\\d+)").find(info)
                if (chMatch != null) inChannels = chMatch.groupValues[1].toInt()
            }
            logToFile("generateSubtitlesForEpisode: converting to PCM 16kHz mono")
            val resampledData = streamResampleTo16kMono(pcmFile, inSampleRate, inChannels, ctx)
            val conversionTime = System.currentTimeMillis() - startTime
            logToFile("getAudioDataForProcessing: PCM conversion completed, total time=${conversionTime}ms, pcmSize=${resampledData?.size ?: 0}")
            logToFile("generateSubtitlesForEpisode: PCM converted, size=${resampledData?.size ?: 0}")
            if (resampledData != null && resampledData.size >= 1024) {
                return resampledData
            }
            ctx.log("ERROR: streamResampleTo16kMono returned null or too small data (${resampledData?.size ?: 0} bytes), falling through to download")
            }
        }
        // 3) Download and process
        ctx.log("No PCM cache, downloading from $audioUrl")
        logToFile("generateSubtitlesForEpisode: downloading audio from $audioUrl")
        val downloadedData = downloadAndProcessAudio(audioUrl, ctx)
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

    private fun downloadAndProcessAudio(audioUrl: String, ctx: TaskContext): ByteArray? {
        // Simple download implementation
        try {
            val url = java.net.URL(audioUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.connect()
            if (conn.responseCode != 200) {
                ctx.log("ERROR: Download failed with code ${conn.responseCode}")
                return null
            }
            val inputStream = conn.inputStream
            val data = inputStream.readBytes()
            inputStream.close()
            conn.disconnect()
            ctx.log("Downloaded ${data.size} bytes, processing...")
            return data  // Return raw data, Vosk will handle format
        } catch (e: Exception) {
            ctx.log("ERROR: Download failed: ${e.message}")
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
            logToFile("isValidVoskModel: ${dir.name} accepted (has am/ and graph/)")
            return true
        }
        // Fallback: handle nested extraction (zip may produce dir/<model-name>/am)
        val subdirs = dir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (sub in subdirs) {
            val subAm = File(sub, "am")
            val subGraph = File(sub, "graph")
            if (subAm.exists() && subAm.isDirectory && subGraph.exists() && subGraph.isDirectory) {
                logToFile("isValidVoskModel: ${dir.name} accepted (nested ${sub.name} has am/ and graph/)")
                return true
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
        // 1. 检查外部存储手动下载的模型（只查找Vosk模型，不查找Whisper ggml模型）
        val modelsDir = getExternalFilesDir("models")
        if (modelsDir != null && modelsDir.exists()) {
            val modelDirs = modelsDir.listFiles()?.filter { it.isDirectory }
            logToFile("findVoskModel: all modelDirs: ${modelDirs?.map { it.name }}")
            if (!modelDirs.isNullOrEmpty()) {
                // 优先查找名称包含vosk的目录
                // [v2.0.55] Issue 5 Fix: Prefer large (non-"small") models over small models.
                // 用户安装的大模型如 vosk-model-cn-0.22 名称不含 "small"，应优先于 vosk-model-small-cn-0.22 被选中。
                val voskDirs = modelDirs.filter { it.name.contains("vosk", ignoreCase = true) }
                    .sortedByDescending { !it.name.contains("small", ignoreCase = true) }  // 名称不含 "small" 的(大模型)排在前面
                logToFile("findVoskModel: voskDirs found (sorted, large models first): ${voskDirs.map { it.name }}")
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
                ?.sortedByDescending { !it.name.contains("small", ignoreCase = true) }  // [v2.0.55] Issue 5 Fix: 大模型优先
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
    private fun findWhisperModel(): String? {
        logToFile("findWhisperModel: searching for Whisper ggml models...")
        val modelsDir = getExternalFilesDir("models")
        if (modelsDir != null && modelsDir.exists()) {
            val modelDirs = modelsDir.listFiles()?.filter { it.isDirectory }
            if (!modelDirs.isNullOrEmpty()) {
                // 优先查找名称包含whisper的目录
                val whisperDirs = modelDirs.filter { it.name.contains("whisper", ignoreCase = true) }
                for (dir in whisperDirs) {
                    val binFile = findWhisperBinFile(dir)
                    if (binFile != null) {
                        logToFile("findWhisperModel: found Whisper model file: ${binFile.absolutePath}")
                        return binFile.absolutePath
                    }
                }
                // 其次在所有目录中查找
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
        logToFile("generateWithWhisper: START, episodeId=$episodeId, audioUrl=$audioUrl")
        try {
            // 尝试通过反射调用whisper.cpp JNI
            ctx.log("Whisper engine: attempting to use on-device recognition...")

            // Issue 5: Check whisper native library first (no point processing audio if JNI unavailable)
            logToFile("generateWithWhisper: checking whisper native library")
            val nativeLibResult = loadWhisperNativeLibrary()
            logToFile("generateWithWhisper: loadWhisperNativeLibrary result=$nativeLibResult")
            if (!nativeLibResult) {
                logToFile("generateWithWhisper: whisper.cpp JNI not available - libwhisper.so not found. Whisper model files are downloaded but the native library (.so) is not installed. Please install the Whisper engine from Settings > Offline Engine Management.")
                ctx.log("ERROR: Whisper native library (libwhisper.so) not available")
                callback.onError("Whisper引擎暂不可用：whisper.cpp原生库（libwhisper.so）未安装。模型文件已下载但缺少原生库。请在设置→离线引擎管理→安装Whisper引擎")
                return false
            }

            // Issue 5: Check model file
            val whisperModel = findWhisperModel()
            val modelFile = if (whisperModel != null) java.io.File(whisperModel) else null
            logToFile("generateWithWhisper: checking model file at ${modelFile?.absolutePath}, exists=${modelFile?.exists()}")
            if (whisperModel == null || modelFile == null || !modelFile.exists()) {
                logToFile("generateWithWhisper: whisper model file not found")
                ctx.log("ERROR: Whisper model file not found")
                callback.onError("Whisper引擎未安装：缺少ggml模型文件。请在设置→离线引擎管理→下载Whisper引擎（tiny版约75MB）")
                return false
            }

            // Check for 16kHz PCM cache that's too large for in-memory: use chunked processing
            val pcmCacheDir = File(getExternalFilesDir(null), "pcm_cache")
            val pcm16kFile = File(pcmCacheDir, "${episodeId}_5min_16k.pcm")
            if (pcm16kFile.exists() && pcm16kFile.length() > 50_000_000) {
                val sizeMB = pcm16kFile.length() / 1024 / 1024
                ctx.log("16kHz PCM cache too large (${sizeMB}MB), using chunked Whisper processing")
                logToFile("generateWithWhisper: using chunked processing for large 16kHz PCM cache (${sizeMB}MB)")
                return processWhisperInChunks(pcm16kFile, whisperModel, callback, ctx)
            }

            // 获取音频数据
            val audioData = getAudioDataForProcessing(episodeId, audioUrl, ctx)
            if (audioData == null) {
                // Try chunked processing for original PCM cache if it exists
                val pcmFile = File(pcmCacheDir, "${episodeId}_5min.pcm")
                if (pcmFile.exists() && pcmFile.length() > 1024) {
                    ctx.log("Original PCM cache exists, attempting chunked Whisper processing")
                    logToFile("generateWithWhisper: using chunked processing for original PCM cache")
                    return processWhisperInChunks(pcmFile, whisperModel, callback, ctx)
                }
                ctx.log("ERROR: Failed to get audio data for Whisper")
                logToFile("generateWithWhisper: audio data is empty, no PCM cache available for chunked processing")
                callback.onError("音频处理失败：无法获取音频数据。请检查网络连接后重试")
                return false
            }

            // Save audio data to PCM cache for chunked processing
            val pcmFile = File(pcmCacheDir, "${episodeId}_5min.pcm")
            pcmFile.writeBytes(audioData)
            logToFile("generateWithWhisper: saved audio data to PCM cache (${audioData.size} bytes), calling processWhisperInChunks")
            return processWhisperInChunks(pcmFile, whisperModel, callback, ctx)
        } catch (e: Exception) {
            logToFile("generateWithWhisper: EXCEPTION: ${e.message}")
            ctx.log("ERROR: Whisper exception: ${e.message}")
            if (e is OutOfMemoryError) {
                callback.onError("内存不足：Whisper处理需要更多内存。请尝试：1)关闭其他应用 2)使用更小的Whisper模型（tiny版） 3)在设置→离线引擎管理→切换到Vosk引擎")
            } else {
                callback.onError("Whisper处理失败: ${e.message}")
            }
            return false
        }
    }

    /**
     * 分块处理大PCM文件（16kHz mono），供Whisper引擎使用
     * Issue 5: 通过 WhisperBridge JNI 桥接调用 whisper.cpp C API 进行识别。
     */
    private fun processWhisperInChunks(
        pcmFile: File, modelPath: String, callback: SubtitleCallback, ctx: TaskContext
    ): Boolean {
        logToFile("processWhisperInChunks: START, pcmFile=${pcmFile.absolutePath}, modelPath=$modelPath")

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

            // Initialize whisper context
            logToFile("processWhisperInChunks: initializing whisper context with model=$modelPath")
            val ctxPtr = bridge.initFromFile(modelPath)
            if (ctxPtr == 0L) {
                logToFile("processWhisperInChunks: whisper_init_from_file failed")
                callback.onError("Whisper模型初始化失败。模型文件可能损坏。")
                return false
            }
            logToFile("processWhisperInChunks: whisper context initialized, ctxPtr=$ctxPtr")

            // [v2.0.54] Issue 4: Change to 15-20 min range to avoid background music in first 5 min
            // Skip first 15 minutes, process 5 minutes (15*60*16000*2 to 20*60*16000*2)
            var startOffsetBytes = 15L * 60 * 16000 * 2  // 15 min offset
            val maxPcmBytes = 5L * 60 * 16000 * 2  // 5 minutes
            val fileBytes = pcmFile.length()
            // [v2.0.55] Issue 4 Fix: If PCM is under 15 min, process from beginning instead of erroring
            val skipped15Min = fileBytes > startOffsetBytes
            val bytesToRead = if (skipped15Min) {
                if (fileBytes > startOffsetBytes + maxPcmBytes) maxPcmBytes.toInt()
                else (fileBytes - startOffsetBytes).toInt()
            } else {
                // PCM too short for 15-min offset, process from beginning
                startOffsetBytes = 0
                minOf(fileBytes, maxPcmBytes).toInt()
            }
            if (bytesToRead <= 0) {
                logToFile("processWhisperInChunks: PCM too small (${fileBytes} bytes), aborting")
                callback.onError("音频文件太短，无法处理")
                bridge.free(ctxPtr)
                return false
            }
            logToFile("processWhisperInChunks: [v2.0.55] PCM=${fileBytes} bytes, skipped15Min=$skipped15Min, skip=${startOffsetBytes} bytes, read ${bytesToRead} bytes (${bytesToRead / 16000 / 2}s)")

            val pcmData = ByteArray(bytesToRead)
            var read = 0
            pcmFile.inputStream().use { input ->
                input.skip(startOffsetBytes)  // Skip first 15 minutes
                while (read < bytesToRead) {
                    val r = input.read(pcmData, read, bytesToRead - read)
                    if (r < 0) break
                    read += r
                }
            }
            val nSamples = read / 2  // 16-bit = 2 bytes per sample
            val samples = FloatArray(nSamples)
            for (i in 0 until nSamples) {
                val sample = (pcmData[i * 2].toInt() and 0xFF) or (pcmData[i * 2 + 1].toInt() shl 8)
                val shortSample = if (sample > 32767) sample - 65536 else sample
                samples[i] = shortSample.toFloat() / 32768.0f
            }
            logToFile("processWhisperInChunks: converted PCM to float samples, nSamples=$nSamples")

            // [v2.0.55] Issue 2 Fix: Cap samples to 5 seconds in Kotlin to reduce memory
            val maxSamples = 5 * 16000  // 5 seconds at 16kHz = 80000 samples
            val processSamples = if (nSamples > maxSamples) maxSamples else nSamples
            logToFile("processWhisperInChunks: [v2.0.55] nSamples=$nSamples, capping to $processSamples (5s)")

            // [v2.0.44] Issue 3&7: Write PRE-CRASH MARKER to BOTH the main service log AND whisper_crash.log
            // This ensures the crash info is in the user's log export (external storage)
            logToFile("[v2.0.44] PRE-CRASH MARKER: about to call whisper_full(ctxPtr=$ctxPtr, nSamples=$processSamples, pcmFile=${pcmFile.name}). If this is the last log entry, the process crashed in whisper_full.")
            try {
                val crashLogDir = java.io.File(filesDir, "logs/whisper")
                if (!crashLogDir.exists()) crashLogDir.mkdirs()
                val crashLogFile = java.io.File(crashLogDir, "whisper_crash.log")
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                FileWriter(crashLogFile, true).use { it.append("[$ts][$appVersion] PRE-CRASH MARKER: about to call whisper_full(ctxPtr=$ctxPtr, nSamples=$processSamples, pcmFile=${pcmFile.name})\n") }
            } catch (_: Exception) {}

            // Run full transcription
            logToFile("processWhisperInChunks: calling bridge.full(ctxPtr=$ctxPtr, nSamples=$processSamples)")
            val result = bridge.full(ctxPtr, samples, processSamples)
            logToFile("processWhisperInChunks: [v2.0.52] bridge.full returned $result")

            // [v2.0.52] Issue 2: Remove -777 crash recovery handling (siglongjmp removed)
            // If whisper crashes, the process dies. No fallback to Vosk per user requirement.

            // [v2.0.43] Issue 3&7: Check for crash log from native signal handler
            try {
                val crashLogFile = java.io.File(filesDir, "logs/whisper/whisper_crash.log")
                if (crashLogFile.exists()) {
                    val crashContent = crashLogFile.readText()
                    if (crashContent.contains("CRASH signal=")) {
                        logToFile("processWhisperInChunks: DETECTED NATIVE CRASH from whisper_crash.log: $crashContent")
                        // Clear the crash log so it doesn't trigger again
                        crashLogFile.writeText("")
                    }
                }
            } catch (_: Exception) {}

            if (result != 0) {
                logToFile("processWhisperInChunks: whisper_full failed with code $result")
                callback.onError("Whisper识别失败，错误码: $result")
                bridge.free(ctxPtr)
                return false
            }

            // Get segments
            val nSegments = bridge.fullNSegments(ctxPtr)
            logToFile("processWhisperInChunks: got $nSegments segments")

            val allTranscripts = mutableListOf<com.radio.app.models.Transcript>()
            for (i in 0 until nSegments) {
                if (ctx.cancelled.get()) break

                val text = bridge.fullGetSegmentText(ctxPtr, i)
                val t0 = bridge.fullGetSegmentT0(ctxPtr, i)  // in centiseconds (10ms)
                val t1 = bridge.fullGetSegmentT1(ctxPtr, i)

                if (text.isNotBlank()) {
                    // [v2.0.55] Issue 4 Fix: Add 15-min offset only if we actually skipped 15 min
                    val whisperOffsetMs = if (skipped15Min) 15L * 60 * 1000 else 0L
                    val transcript = com.radio.app.models.Transcript(
                        text = text.trim(),
                        segmentStart = whisperOffsetMs + t0 * 10,  // convert centiseconds to milliseconds
                        segmentEnd = whisperOffsetMs + t1 * 10
                    )
                    allTranscripts.add(transcript)
                    logToFile("processWhisperInChunks: segment $i: start=${transcript.segmentStart}ms, end=${transcript.segmentEnd}ms, text='${text.take(100)}...'")
                    callback.onSubtitleGenerated(transcript)
                }
            }

            // Free context
            bridge.free(ctxPtr)

            logToFile("processWhisperInChunks: COMPLETE, ${allTranscripts.size} transcripts generated")
            callback.onComplete(allTranscripts)
            return true

        } catch (e: UnsatisfiedLinkError) {
            logToFile("processWhisperInChunks: UnsatisfiedLinkError: ${e.message}")
            callback.onError("Whisper JNI桥接未找到。请确保引擎文件已正确安装。错误: ${e.message}")
            return false
        } catch (e: Exception) {
            logToFile("processWhisperInChunks: EXCEPTION: ${e.message}")
            callback.onError("Whisper处理异常: ${e.message}")
            return false
        }
    }

    /**
     * 检查是否有预缓存的16kHz PCM文件（由RadioPlaybackService.decodeToPcmForPreCache生成）
     * 返回PCM文件路径，如果不存在则返回null
     */
    private fun find16kHzPcmCache(episodeId: String): File? {
        try {
            val pcmCacheDir = getExternalFilesDir(null)?.let { File(it, "pcm_cache") }
            if (pcmCacheDir == null || !pcmCacheDir.exists()) return null
            val pcmFile = File(pcmCacheDir, "${episodeId}_5min_16k.pcm")
            if (pcmFile.exists() && pcmFile.length() > 1024) {
                logToFile("find16kHzPcmCache: found 16kHz PCM cache: ${pcmFile.absolutePath} (${pcmFile.length()} bytes)")
                return pcmFile
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

    private fun decodeToPcm(
        audioFile: File, pcmFile: File, durationUs: Long,
        ctx: TaskContext, onProgress: ((Int) -> Unit)? = null
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
            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            ctx.log("Decode: audio mime=$mime, durationUs=$durationUs")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            fos = FileOutputStream(pcmFile)
            val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            ctx.log("Decode: sampleRate=$sampleRate, channels=$channelCount")

            var inputDone = false
            var outputDone = false
            var decodedBytes = 0L
            var lastProgressBytes = 0L
            var lastProgressTime = System.currentTimeMillis()
            var decodeStartTime = System.currentTimeMillis()
            // 最大解码时长：5分钟（足够解码30分钟音频）
            val MAX_DECODE_TIME_MS = 5 * 60 * 1000L
            // 无进展超时：30秒没有新输出就认为卡住了
            val NO_PROGRESS_TIMEOUT_MS = 30000L
            // 硬限制：最大PCM输出字节数（对应30分钟@16kHz-mono-16bit）
            val MAX_PCM_BYTES = 60_000_000L

            // 计算每次进度报告应该间隔的字节数（约1%进度）
            val progressStepBytes = MAX_PCM_BYTES / 100

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
                            // 如果已到达durationUs限制，停止输入更多数据
                            if (durationUs > 0 && extractor.sampleTime >= durationUs) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                                ctx.log("Decode: reached duration limit at ${extractor.sampleTime / 1000}s, stopping input")
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
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
                                val resampled = resamplePcm(pcmBytes, sampleRate, channelCount, SAMPLE_RATE, 1)
                                fos.write(resampled)
                                decodedBytes += resampled.size
                                // 进度报告：基于字节偏移估算（避免时间戳不准确）
                                val pct = (decodedBytes * 100 / MAX_PCM_BYTES).toInt().coerceIn(0, 99)
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
                        else -> {
                            // INFO_OUTPUT_FORMAT_CHANGED etc.
                            outputDrained = true
                        }
                    }
                }
            }
            // 确保进度报告到100%
            onProgress?.invoke(100)
            ctx.log("Decode complete: $decodedBytes PCM bytes in ${(System.currentTimeMillis() - decodeStartTime)/1000}s")
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
            val taskLabel = if (taskType == "segment") "AI分段" else "字幕生成"

            logToFile("onStartCommand: starting foreground for $taskLabel, episode=$episodeId")

            if (wakeLock == null) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SubtitleGenService:WakeLock")
            }
            wakeLock?.acquire(3600000)

            // [v2.0.58] Issue 2+3+4 Fix: Start task directly in onStartCommand
            // When service runs in :subtitle process, Activity binder call fails (BinderProxy != LocalBinder)
            // So we must start the task here. Broadcasts are sent by wrappedCallback for cross-process communication.
            val progressNotification = createProgressNotification(0, taskLabel)
            try { startForeground(NOTIFICATION_ID, progressNotification) }
            catch (e: Exception) { Log.w(TAG, "startForeground failed: ${e.message}") }

            // Only start if not already running (prevents duplicate when Activity also binds in-process)
            if (!activeTasks.containsKey(episodeId)) {
                logToFile("onStartCommand: [v2.0.58] starting $taskType task for episode=$episodeId (cross-process mode)")
                if (taskType == "segment") {
                    val dummyCallback = object : SegmentCallback {
                        override fun onSegmentGenerated(episodeId: String, startMs: Long, endMs: Long, title: String?) {}
                        override fun onProgressUpdate(progress: Int, total: Int) {}
                        override fun onError(message: String) {}
                        override fun onComplete() {}
                    }
                    generateSegmentsForEpisode(episodeId, audioUrl, dummyCallback)
                } else {
                    val dummyCallback = object : SubtitleCallback {
                        override fun onSubtitleGenerated(transcript: Transcript) {}
                        override fun onProgressUpdate(progress: Int, total: Int) {}
                        override fun onError(message: String) {}
                        override fun onComplete() {}
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
        // 只有当没有任何活跃任务时才停止前台服务和移除通知
        if (activeTasks.isEmpty()) {
            logToFile("cleanupTask: no more active tasks, stopping foreground")
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

    private fun createProgressNotification(progress: Int, taskLabel: String): Notification {
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
            .setContentTitle("正在处理音频")
            .setContentText("$taskLabel: ${progress}%")
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
