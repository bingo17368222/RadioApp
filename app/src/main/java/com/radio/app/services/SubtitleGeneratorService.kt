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
        // 设置线程未捕获异常处理器，将崩溃日志写入 /sdcard/RadioApp/logs/crash/
        val crashLogDir = java.io.File(android.os.Environment.getExternalStorageDirectory(), "RadioApp/logs/crash")
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
                logFile.appendText("=== RadioApp v2.0.18 ===\n")
            }
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            FileWriter(logFile, true).use { it.append("[$ts] $msg\n") }
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

        // 包装回调，同时更新通知
        val wrappedCallback = object : SubtitleCallback {
            override fun onSubtitleGenerated(transcript: Transcript) {
                // Ensure episodeId is set for DB persistence
                if (transcript.episodeId.isNullOrBlank()) transcript.episodeId = episodeId
                callback.onSubtitleGenerated(transcript)
                // Incrementally save to database
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
                updateProgressNotification(progress, "字幕生成")
                ctx.lastReportedProgress = progress
            }
            override fun onError(error: String) {
                callback.onError(error)
                // Ensure task is cleaned up on error: cancel ongoing notification immediately
                cancelProgressNotification()
                activeTasks.remove(episodeId)
                cleanupTask()
            }
            override fun onComplete(transcripts: List<Transcript>) {
                callback.onComplete(transcripts)
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
            val pcm16kFile = File(pcmCacheDir, "${episodeId}_30min_16k.pcm")
            logToFile("generateWithVosk: PCM file=${pcm16kFile.absolutePath}, size=${pcm16kFile.length()}, willChunk=${pcm16kFile.length() > 50_000_000}")
            if (pcm16kFile.exists() && pcm16kFile.length() > 50_000_000) {
                val sizeMB = pcm16kFile.length() / 1024 / 1024
                ctx.log("16kHz PCM cache too large (${sizeMB}MB), using chunked Vosk processing")
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
                val pcmFile = File(pcmCacheDir2, "${episodeId}_30min.pcm")
                if (pcmFile.exists() && pcmFile.length() > 1024) {
                    // Original PCM cache needs resampling - resample to 16kHz temp file first
                    ctx.log("Original PCM cache exists, attempting resample + chunked processing")
                    val infoFile = File(pcmCacheDir2, "${episodeId}_30min.info")
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
            val chunkSize = 4096
            var offset = 0
            var lastProgress = 0
            val acceptWaveFormMethod = voskRecognizerClass!!.getMethod("acceptWaveForm", ByteArray::class.java, Int::class.javaPrimitiveType)
            val getResultMethod = voskRecognizerClass!!.getMethod("getResult")
            val getPartialResultMethod = voskRecognizerClass!!.getMethod("getPartialResult")
            val getFinalResultMethod = voskRecognizerClass!!.getMethod("getFinalResult")

            val allTranscripts = mutableListOf<com.radio.app.models.Transcript>()
            var lastPartialText = ""
            while (offset < audioData.size && !ctx.cancelled.get()) {
                val end = minOf(offset + chunkSize, audioData.size)
                val chunk = audioData.copyOfRange(offset, end)
                val accepted = acceptWaveFormMethod.invoke(recognizer, chunk, chunk.size) as? Boolean ?: false
                if (accepted) {
                    val result = getResultMethod.invoke(recognizer) as? String ?: ""
                    if (result.isNotBlank()) {
                        try {
                            val json = org.json.JSONObject(result as String)
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
                                    startTime = offset * 1000L / 32000L
                                    endTime = (offset + chunk.size) * 1000L / 32000L
                                }
                                val transcript = com.radio.app.models.Transcript(text = text, segmentStart = startTime, segmentEnd = endTime)
                                logToFile("generateWithVosk: transcript #${allTranscripts.size + 1}: start=${startTime}ms, end=${endTime}ms, text='${text.take(50)}...'")
                                allTranscripts.add(transcript)
                                callback.onSubtitleGenerated(transcript)
                            }
                        } catch (e: Exception) { /* skip */ }
                    }
                } else {
                    // Get partial result for logging only (not saved as transcript)
                    val partial = getPartialResultMethod.invoke(recognizer) as? String ?: ""
                    if (partial.isNotBlank()) {
                        try {
                            val json = org.json.JSONObject(partial)
                            val partialText = json.optString("partial", "")
                            if (partialText.isNotBlank() && partialText != lastPartialText) {
                                lastPartialText = partialText
                                logToFile("generateWithVosk: partial at ${offset}ms: '${partialText.take(50)}...'")
                            }
                        } catch (_: Exception) { /* skip */ }
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
        // Issue 9: Limit to first 5 minutes for testing (5 * 60 * 16000 * 2 = 9,600,000 bytes)
        val maxBytes = 5L * 60 * 16000 * 2
        val processLimit = if (totalSize > maxBytes) maxBytes else totalSize
        logToFile("processVoskInChunks: totalSize=${totalSize / 1024}KB, processing limit=${processLimit / 1024}KB (5 min), willProcessAll=${totalSize <= maxBytes}")
        ctx.log("processVoskInChunks: PCM file size=${totalSize / 1024 / 1024}MB, processing in 4096-byte chunks (128ms per chunk)")
        callback.onProgressUpdate(0, 100)

        val inputStream = java.io.FileInputStream(pcmFile)
        val chunkSize = 4096 // Small chunks for better Vosk accuracy (128ms per chunk)
        val buffer = ByteArray(chunkSize)
        var offset = 0L
        var lastProgress = 0
        var chunkCount = 0
        var lastPartialText = ""

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
                logToFile("processVoskInChunks: Model created successfully")
                recognizer = voskRecognizerClass!!.getConstructor(voskModelClass, Float::class.javaPrimitiveType)
                    .newInstance(model, 16000.0f as java.lang.Float)
                logToFile("processVoskInChunks: Recognizer created successfully")
                // Enable word-level output for timestamps
                try {
                    val setWordsMethod = voskRecognizerClass!!.getMethod("setWords", Boolean::class.javaPrimitiveType)
                    setWordsMethod.invoke(recognizer, true)
                    logToFile("processVoskInChunks: setWords(true) called")
                } catch (e: Exception) {
                    logToFile("processVoskInChunks: setWords failed: ${e.message}")
                }
                logToFile("processVoskInChunks: Recognizer methods: ${voskRecognizerClass!!.declaredMethods.map { "${it.name}(${it.parameterTypes.map { p -> p.simpleName }})" }}")
            } catch (e: Exception) {
                logToFile("processVoskInChunks: FAILED to create Model/Recognizer: ${e.javaClass.name}: ${e.message}")
                e.stackTrace.take(10).forEach { logToFile("processVoskInChunks: at $it") }
                callback.onError("Vosk模型初始化失败: ${e.message}")
                return false
            } catch (e: UnsatisfiedLinkError) {
                logToFile("processVoskInChunks: UnsatisfiedLinkError creating Model: ${e.message}")
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
            inputStream.channel.position(0)

            // Issue 8: Track time from processing start to first transcript
            val processingStartTime = System.currentTimeMillis()

            val allTranscripts = mutableListOf<com.radio.app.models.Transcript>()
            while (!ctx.cancelled.get() && offset < processLimit) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead <= 0) break
                val chunk = if (bytesRead == chunkSize) buffer else buffer.copyOf(bytesRead)

                val acceptResult = acceptWaveFormMethod.invoke(recognizer, chunk, chunk.size) as? Boolean ?: false
                logToFile("processVoskInChunks: chunk $chunkCount, offset=$offset, bytesRead=$bytesRead, acceptWaveForm=$acceptResult")
                if (acceptResult) {
                    val result = getResultMethod.invoke(recognizer) as? String ?: ""
                    logToFile("processVoskInChunks: raw result: '${result.take(200)}'")
                    if (result.isNotBlank()) {
                        try {
                            val json = org.json.JSONObject(result)
                            val text = json.optString("text", "")
                            if (text.isBlank()) {
                                logToFile("processVoskInChunks: chunk $chunkCount produced empty text")
                            }
                            if (text.isNotBlank()) {
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
                                    startTime = offset * 1000L / 32000L
                                    endTime = (offset + chunk.size) * 1000L / 32000L
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
                } else {
                    // Get partial result for logging only (not saved as transcript)
                    val partial = getPartialResultMethod.invoke(recognizer) as? String ?: ""
                    if (partial.isNotBlank()) {
                        try {
                            val json = org.json.JSONObject(partial)
                            val partialText = json.optString("partial", "")
                            if (partialText.isNotBlank() && partialText != lastPartialText) {
                                lastPartialText = partialText
                                logToFile("processVoskInChunks: partial at ${offset}ms: '${partialText.take(50)}...'")
                            }
                        } catch (_: Exception) { /* skip */ }
                    }
                }

                offset += bytesRead
                chunkCount++
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

            // Get final result
            val finalResult = getFinalResultMethod.invoke(recognizer) as? String ?: ""
            if (finalResult.isNotBlank()) {
                try {
                    val json = org.json.JSONObject(finalResult)
                    val text = json.optString("text", "")
                    if (text.isNotBlank()) {
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
            val crashLogDir = java.io.File(android.os.Environment.getExternalStorageDirectory(), "RadioApp/logs/crash")
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
        } catch (_: Exception) {}
    }

    private fun getAudioDataForProcessing(episodeId: String, audioUrl: String, ctx: TaskContext): ByteArray? {
        // Issue 8: Log each step with timing
        val startTime = System.currentTimeMillis()
        logToFile("getAudioDataForProcessing: START, audioUrl=$audioUrl")
        // 1) Try 16kHz PCM cache first (should be small, ~60MB for 30min)
        val pcmCacheDir = File(getExternalFilesDir(null), "pcm_cache")
        val pcm16kFile = File(pcmCacheDir, "${episodeId}_30min_16k.pcm")
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
        val pcmFile = File(pcmCacheDir, "${episodeId}_30min.pcm")
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
            val infoFile = File(pcmCacheDir, "${episodeId}_30min.info")
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
     * 宽松检测：支持多种Vosk模型结构
     * 1. am/final.mdl + conf/model.conf (标准结构)
     * 2. 根目录有 .mdl 文件 (简单模型)
     * 3. HCLG.fst 文件 (Kaldi模型)
     * 4. graph/Gr.fst 或 graph/HCLr.fst (替代graph结构)
     * 5. 目录名以 "vosk-model" 开头且包含 .mdl 或 .fst 文件
     * 同时检测一层嵌套子目录（zip解压可能产生嵌套）
     */
    private fun isValidVoskModel(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val files = dir.listFiles() ?: return false
        if (files.isEmpty()) return false

        // Accept any vosk-model-* directory that has content
        if (dir.name.startsWith("vosk-model", ignoreCase = true)) {
            logToFile("isValidVoskModel: ${dir.name} accepted (vosk-model prefix, ${files.size} entries)")
            return true
        }

        // For other directories, check for model files recursively
        fun hasModelFiles(d: File, depth: Int = 0): Boolean {
            if (depth > 3) return false
            val fs = d.listFiles() ?: return false
            for (f in fs) {
                if (f.isFile) {
                    val n = f.name.lowercase()
                    if (n.endsWith(".mdl") || n.endsWith(".fst") || n.endsWith(".conf") ||
                        n == "hclg.fst" || n == "gr.fst" || n == "hclr.fst") return true
                } else if (f.isDirectory) {
                    if (f.name in listOf("am", "conf", "graph", "ivector", "rescore")) return true
                    if (hasModelFiles(f, depth + 1)) return true
                }
            }
            return false
        }
        return hasModelFiles(dir)
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
                val voskDirs = modelDirs.filter { it.name.contains("vosk", ignoreCase = true) }
                logToFile("findVoskModel: voskDirs found: ${voskDirs.map { it.name }}")
                for (dir in voskDirs) {
                    val dirFiles = dir.listFiles()?.map { "${it.name}(${if (it.isDirectory) "dir" else "${it.length()}B"})" } ?: listOf("(null listFiles)")
                    logToFile("findVoskModel: checking ${dir.name}, files=${dirFiles.take(20)}")
                    if (dir.name.startsWith("vosk-model", ignoreCase = true) && dir.listFiles()?.isNotEmpty() == true) {
                        logToFile("findVoskModel: ACCEPTED ${dir.name} by name prefix (non-empty dir)")
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
                        logToFile("findVoskModel: ACCEPTED ${dir.name} by name prefix (non-empty dir)")
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
            for (dir in voskDirs ?: emptyList()) {
                val dirFiles = dir.listFiles()?.map { "${it.name}(${if (it.isDirectory) "dir" else "${it.length()}B"})" } ?: listOf("(null listFiles)")
                logToFile("findVoskModel: checking ${dir.name}, files=${dirFiles.take(20)}")
                if (dir.name.startsWith("vosk-model", ignoreCase = true) && dir.listFiles()?.isNotEmpty() == true) {
                    logToFile("findVoskModel: ACCEPTED ${dir.name} by name prefix (non-empty dir)")
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

            val modelsDir = getExternalFilesDir("models")
            if (modelsDir != null && modelsDir.exists()) {
                modelsDir.walkTopDown().filter { it.isFile && it.name == "libwhisper.so" }.forEach { soFile ->
                    try {
                        System.load(soFile.absolutePath)
                        logToFile("loadWhisperNativeLibrary: loaded libwhisper.so from ${soFile.absolutePath}")
                        return true
                    } catch (e: UnsatisfiedLinkError) {
                        logToFile("loadWhisperNativeLibrary: failed to load ${soFile.absolutePath}: ${e.message}")
                    }
                }
            }

            val engineDir = File(filesDir, "engines")
            if (engineDir.exists()) {
                engineDir.walkTopDown().filter { it.isFile && it.name == "libwhisper.so" }.forEach { soFile ->
                    try {
                        System.load(soFile.absolutePath)
                        logToFile("loadWhisperNativeLibrary: loaded libwhisper.so from ${soFile.absolutePath}")
                        return true
                    } catch (e: UnsatisfiedLinkError) {
                        logToFile("loadWhisperNativeLibrary: failed to load ${soFile.absolutePath}: ${e.message}")
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
            val pcm16kFile = File(pcmCacheDir, "${episodeId}_30min_16k.pcm")
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
                val pcmFile = File(pcmCacheDir, "${episodeId}_30min.pcm")
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

            // Native library loaded, but full JNI bridge not yet implemented in this build
            ctx.log("Whisper engine: libwhisper.so loaded, but full whisper.cpp JNI integration not yet implemented")
            logToFile("generateWithWhisper: libwhisper.so loaded but JNI bridge not implemented in this build, audioData.size=${audioData.size}")
            callback.onError("Whisper引擎暂不可用：whisper.cpp原生库已加载，但JNI桥接尚未集成。请使用Vosk引擎，或在设置中切换ASR引擎为Vosk。")
            return false
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
     * 当前版本whisper.cpp未集成，回退到Vosk分块处理（processVoskInChunks）
     */
    private fun processWhisperInChunks(
        pcmFile: File, modelPath: String, callback: SubtitleCallback, ctx: TaskContext
    ): Boolean {
        logToFile("processWhisperInChunks: START")
        logToFile("processWhisperInChunks: whisper.cpp JNI not available, reporting error")
        callback.onError("Whisper分块处理暂不可用：whisper.cpp原生库未集成。请使用Vosk引擎。")
        return false
    }

    /**
     * 检查是否有预缓存的16kHz PCM文件（由RadioPlaybackService.decodeToPcmForPreCache生成）
     * 返回PCM文件路径，如果不存在则返回null
     */
    private fun find16kHzPcmCache(episodeId: String): File? {
        try {
            val pcmCacheDir = getExternalFilesDir(null)?.let { File(it, "pcm_cache") }
            if (pcmCacheDir == null || !pcmCacheDir.exists()) return null
            val pcmFile = File(pcmCacheDir, "${episodeId}_30min_16k.pcm")
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

            // 关键修复：onStartCommand只启动前台通知，不启动任务
            // 任务由Activity通过binder调用generateSegmentsForEpisode/generateSubtitlesForEpisode启动
            // 这样Activity的回调才能正确接收进度和结果
            val progressNotification = createProgressNotification(0, taskLabel)
            try { startForeground(NOTIFICATION_ID, progressNotification) }
            catch (e: Exception) { Log.w(TAG, "startForeground failed: ${e.message}") }

            // 在Activity绑定之前，不启动任务
            // 任务启动时会通过回调更新通知进度
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
