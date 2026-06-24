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
import com.radio.app.models.Transcript
import com.radio.app.models.VoiceSegment
import org.vosk.Model
import org.vosk.Recognizer
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
        executor = Executors.newFixedThreadPool(2)
        dbHelper = RadioDatabaseHelper.getInstance(this)

        // 确保日志目录存在（外部存储，用户可访问）
        try {
            val logDir = getExternalFilesDir("subtitle_logs")
            if (logDir != null && !logDir.exists()) {
                logDir.mkdirs()
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
            val logDir = getExternalFilesDir("subtitle_logs")
            if (logDir != null) {
                if (!logDir.exists()) logDir.mkdirs()
                val logFile = File(logDir, "service.log")
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                FileWriter(logFile, true).use { it.append("[$ts] $msg\n") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "logToFile failed", e)
        }
    }

    /**
     * Cancel all running tasks and prevent new ones
     */
    fun cancelAllTasks() {
        logToFile("cancelAllTasks: cancelling ${activeTasks.size} active tasks")
        globalCancelled.set(true)
        activeTasks.values.forEach { it.cancelled.set(true) }
        activeTasks.clear()
        releaseWakeLock()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
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
                callback.onSubtitleGenerated(transcript)
            }
            override fun onProgressUpdate(progress: Int, total: Int) {
                callback.onProgressUpdate(progress, total)
                updateProgressNotification(progress, "字幕生成")
                ctx.lastReportedProgress = progress
            }
            override fun onError(error: String) {
                callback.onError(error)
                updateProgressNotification(0, "字幕生成")
            }
            override fun onComplete(transcripts: List<Transcript>) {
                callback.onComplete(transcripts)
                updateProgressNotification(100, "字幕生成完成")
            }
        }

        executor?.execute {
            var taskFailed = false
            try {
                if (ctx.cancelled.get() || globalCancelled.get()) {
                    ctx.log("Task cancelled before start")
                    return@execute
                }
                val voskModel = findVoskModel()
                if (voskModel != null) {
                    ctx.log("Using Vosk model: $voskModel")
                    val success = generateWithVosk(episodeId, audioUrl, wrappedCallback, ctx)
                    if (!success && !ctx.cancelled.get()) {
                        ctx.log("Vosk subtitle generation FAILED")
                        wrappedCallback.onError("字幕生成失败：音频识别模型不可用，请在离线引擎管理中下载")
                    }
                } else {
                    ctx.log("ERROR: No Vosk model found")
                    wrappedCallback.onError("未找到离线识别模型，请在离线引擎管理中下载Vosk模型")
                }
            } catch (e: Exception) {
                taskFailed = true
                ctx.log("EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "Subtitle generation failed for episodeId=$episodeId", e)
                if (!ctx.cancelled.get()) wrappedCallback.onError("字幕生成失败: ${e.message}")
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
                updateProgressNotification(0, "AI分段")
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
                val voskModel = findVoskModel()
                if (voskModel != null) {
                    ctx.log("Using Vosk model for segments: $voskModel")
                    val success = generateWithVosk(episodeId, audioUrl, subtitleCallback, ctx)
                    if (!success && !ctx.cancelled.get()) {
                        ctx.log("Vosk segment generation FAILED")
                        wrappedCallback.onError("AI分段失败：音频识别模型不可用，请在离线引擎管理中下载模型")
                    }
                } else {
                    ctx.log("ERROR: No Vosk model for segments")
                    wrappedCallback.onError("AI分段失败：未找到离线识别模型，请在离线引擎管理中下载Vosk模型")
                }
            } catch (e: Exception) {
                taskFailed = true
                ctx.log("EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "Segment generation failed for episodeId=$episodeId", e)
                if (!ctx.cancelled.get()) wrappedCallback.onError("AI分段失败: ${e.message}")
            } finally {
                activeTasks.remove(segKey)
                ctx.log("Segment task ended${if (taskFailed) " (failed)" else ""}, active tasks remaining: ${activeTasks.size}")
                cleanupTask()
            }
        }
    }

    private fun prepareTaskLogFile(taskType: String, episodeId: String): File {
        val logDir = getExternalFilesDir("subtitle_logs")
        if (logDir != null) {
            if (!logDir.exists()) logDir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(logDir, "${taskType}_${Math.abs(episodeId.hashCode())}_$ts.log")
            if (!file.exists()) file.createNewFile()
            return file
        }
        // Fallback to cache dir
        val fallbackDir = File(cacheDir, "subtitle_logs")
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

    private fun generateWithVosk(
        episodeId: String, audioUrl: String, callback: SubtitleCallback, ctx: TaskContext
    ): Boolean {
        ctx.lastReportedProgress = 0
        ctx.startTime = System.currentTimeMillis()
        ctx.lastOutputTime = ctx.startTime

        val modelPath = findVoskModel()
        if (modelPath == null) {
            ctx.log("ERROR: No Vosk model found")
            callback.onError("未找到Vosk模型，请在离线引擎管理中下载Vosk模型")
            return false
        }

        reportProgress(callback, 0, 100, ctx)
        ctx.log("[Phase 0] Using Vosk model: $modelPath")

        // Phase 0.5: 检查是否有预解码的PCM缓存
        var pcmCacheUsed = false
        val pcmCacheDir = getExternalFilesDir(null)?.let { File(it, "pcm_cache") }
        val pcmCacheFile = pcmCacheDir?.let { File(it, "${episodeId}_30min.pcm") }
        if (pcmCacheFile != null && pcmCacheFile.exists() && pcmCacheFile.length() > 1024) {
            ctx.log("[Phase 0.5] PCM cache FOUND: ${pcmCacheFile.absolutePath} (${pcmCacheFile.length()} bytes)")
            ctx.log("[Phase 0.5] Skipping download and decode phases, loading PCM cache directly")
            pcmCacheUsed = true

            reportProgress(callback, 15, 100, ctx)
            ctx.log("[Phase 2] PCM cache loaded, proceeding to recognition")

            val pcmData = pcmCacheFile.readBytes()
            val totalBytes = pcmData.size
            reportProgress(callback, 40, 100, ctx)

            // Phase 3: Vosk recognition (40-95%) -- 使用缓存的PCM
            ctx.log("[Phase 5] Loading Vosk model and starting recognition (from PCM cache)...")
            val model = Model(modelPath)
            val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            ctx.log("Vosk recognizer initialized")

            ctx.startTime = System.currentTimeMillis()
            ctx.lastOutputTime = ctx.startTime

            val bytesPerChunk = SAMPLE_RATE * 2 * 3
            var offset = 0
            val allTranscripts = mutableListOf<Transcript>()
            var fullText = StringBuilder()
            var lastPartialOutput = 0L
            var lastLogTime = System.currentTimeMillis()

            ctx.log("PCM cache data: $totalBytes bytes, expected ~${totalBytes / (SAMPLE_RATE * 2)} seconds of audio")

            while (offset < totalBytes) {
                if (ctx.cancelled.get() || globalCancelled.get()) {
                    ctx.log("Task cancelled at offset=$offset/$totalBytes")
                    recognizer.close()
                    model.close()
                    return false
                }
                val taskElapsed = System.currentTimeMillis() - (ctx.startTime - (totalBytes / (SAMPLE_RATE * 2 * 3)).toLong())
                if (System.currentTimeMillis() - ctx.startTime > TASK_TIMEOUT_MS) {
                    ctx.log("ERROR: Hard timeout after ${(System.currentTimeMillis() - ctx.startTime) / 1000}s, aborting")
                    recognizer.close()
                    model.close()
                    callback.onError("处理超时（超过10分钟），音频可能过长")
                    return false
                }
                if (allTranscripts.isEmpty() && System.currentTimeMillis() - ctx.startTime > 180000) {
                    ctx.log("ERROR: No result after 180s of recognition, PCM may be incompatible")
                    recognizer.close()
                    model.close()
                    callback.onError("语音识别超时：音频格式可能不兼容，请尝试其他节目")
                    return false
                }
                if (System.currentTimeMillis() - ctx.lastOutputTime > 60000) {
                    ctx.log("Warning: no output for ${(System.currentTimeMillis() - ctx.lastOutputTime) / 1000}s at offset=$offset/$totalBytes")
                }

                val chunkSize = minOf(bytesPerChunk, totalBytes - offset)
                val chunk = pcmData.copyOfRange(offset, offset + chunkSize)
                offset += chunkSize

                val shortBuffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val shorts = ShortArray(shortBuffer.remaining())
                shortBuffer.get(shorts)

                if (recognizer.acceptWaveForm(shorts, SAMPLE_RATE)) {
                    val result = recognizer.result
                    val text = parseVoskResult(result)
                    if (text.isNotEmpty()) {
                        val startSec = ((offset - chunkSize) / (SAMPLE_RATE * 2.0)).toInt()
                        val endSec = (offset / (SAMPLE_RATE * 2.0)).toInt()
                        val t = Transcript().apply {
                            this.episodeId = episodeId
                            this.segmentStart = startSec.toLong()
                            this.segmentEnd = endSec.toLong()
                            this.text = text
                            this.confidence = 0.9
                        }
                        allTranscripts.add(t)
                        dbHelper?.saveTranscript(t)
                        callback.onSubtitleGenerated(t)
                        fullText.append(text).append(" ")
                        ctx.lastOutputTime = System.currentTimeMillis()
                        ctx.log("Result [$startSec-$endSec]: $text")
                    }
                } else {
                    if (offset - lastPartialOutput >= SAMPLE_RATE * 2 * 2) {
                        try {
                            val partial = recognizer.partialResult
                            val partialText = parseVoskPartial(partial)
                            if (partialText.isNotBlank()) {
                                ctx.lastOutputTime = System.currentTimeMillis()
                                if (allTranscripts.size < 5) {
                                    ctx.log("Partial: $partialText")
                                }
                                lastPartialOutput = offset.toLong()
                            }
                        } catch (_: Exception) {}
                    }
                }

                val progress = 40 + (offset * 55 / totalBytes).toInt()
                reportProgress(callback, progress.coerceAtMost(95), 100, ctx)

                val now = System.currentTimeMillis()
                if (now - lastLogTime > 15000) {
                    ctx.log("Progress: ${offset * 100 / totalBytes}% (${offset / (SAMPLE_RATE * 2)}s / ${totalBytes / (SAMPLE_RATE * 2)}s), segments=${allTranscripts.size}")
                    lastLogTime = now
                }
            }

            val finalResult = recognizer.finalResult
            val finalText = parseVoskResult(finalResult)
            if (finalText.isNotEmpty()) {
                val startSec = (offset / (SAMPLE_RATE * 2.0)).toInt()
                val t = Transcript().apply {
                    this.episodeId = episodeId
                    this.segmentStart = startSec.toLong()
                    this.segmentEnd = (pcmData.size / (SAMPLE_RATE * 2.0)).toInt().toLong()
                    this.text = finalText
                    this.confidence = 0.9
                }
                allTranscripts.add(t)
                dbHelper?.saveTranscript(t)
                callback.onSubtitleGenerated(t)
                ctx.log("Final: $finalText")
            }

            recognizer.close()
            model.close()

            reportProgress(callback, 100, 100, ctx)
            val elapsed = (System.currentTimeMillis() - ctx.startTime) / 1000
            ctx.log("COMPLETE (from PCM cache): ${allTranscripts.size} segments in ${elapsed}s")
            callback.onComplete(allTranscripts)

            return true
        } else {
            ctx.log("[Phase 0.5] PCM cache NOT found for episodeId=$episodeId, will download and decode")
        }

        // Phase 1: Download audio (0-14%)
        reportProgress(callback, 1, 100, ctx)
        ctx.log("[Phase 1] Starting audio download: $audioUrl")
        val audioFile = getAudioFile(audioUrl) { progress ->
            if (ctx.cancelled.get()) return@getAudioFile
            val mapped = (1 + progress * 13 / 14).coerceAtMost(14)
            reportProgress(callback, mapped, 100, ctx)
        }
        if (ctx.cancelled.get()) { ctx.log("Cancelled during download"); return false }
        if (audioFile == null) {
            ctx.log("ERROR: Audio download failed")
            callback.onError("音频下载失败：网络不通或音频链接失效")
            return false
        }
        if (!audioFile.exists() || audioFile.length() < 1024) {
            ctx.log("ERROR: Audio file invalid: exists=${audioFile.exists()}, size=${audioFile.length()}")
            callback.onError("音频文件下载不完整，请重试")
            return false
        }

        reportProgress(callback, 15, 100, ctx)
        ctx.log("[Phase 2] Audio ready: ${audioFile.length()} bytes")

        // Phase 2: Decode to PCM (16-40%)
        val pcmFile = File(cacheDir, "subtitle_pcm_${Math.abs(audioUrl.hashCode())}.pcm")
        try {
            reportProgress(callback, 16, 100, ctx)
            ctx.log("[Phase 3] Starting PCM decode...")

            val durationExtractor = MediaExtractor()
            var audioDurationUs = 0L
            try {
                durationExtractor.setDataSource(audioFile.absolutePath)
                for (i in 0 until durationExtractor.trackCount) {
                    val fmt = durationExtractor.getTrackFormat(i)
                    if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        audioDurationUs = fmt.getLong(MediaFormat.KEY_DURATION)
                        ctx.log("Audio duration: ${audioDurationUs / 1000000}s")
                        break
                    }
                }
            } catch (e: Exception) {
                ctx.log("Warning: failed to get duration: ${e.message}")
            } finally {
                durationExtractor.release()
            }
            // 超长音频限制：只处理前 MAX_AUDIO_DURATION_SEC 秒
            val maxDurationUs = MAX_AUDIO_DURATION_SEC * 1000000L
            val effectiveDurationUs = if (audioDurationUs > 0 && audioDurationUs > maxDurationUs) {
                ctx.log("Audio too long (${audioDurationUs / 1000000}s), limiting to ${MAX_AUDIO_DURATION_SEC}s")
                maxDurationUs
            } else {
                audioDurationUs
            }

            decodeToPcm(audioFile, pcmFile, effectiveDurationUs, ctx) { pct ->
                if (ctx.cancelled.get()) return@decodeToPcm
                reportProgress(callback, 16 + pct * 24 / 100, 100, ctx)
            }
            if (ctx.cancelled.get()) { ctx.log("Cancelled during PCM decode"); return false }

            ctx.log("[Phase 4] PCM decoded: ${pcmFile.length()} bytes")
            reportProgress(callback, 40, 100, ctx)

            // Phase 3: Vosk recognition (40-95%)
            ctx.log("[Phase 5] Loading Vosk model and starting recognition...")
            val model = Model(modelPath)
            val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            ctx.log("Vosk recognizer initialized")

            // 重置开始时间，避免下载+解码阶段消耗的时间计入识别超时
            ctx.startTime = System.currentTimeMillis()
            ctx.lastOutputTime = ctx.startTime

            val pcmData = pcmFile.readBytes()
            val totalBytes = pcmData.size
            val bytesPerChunk = SAMPLE_RATE * 2 * 10  // 10 seconds per chunk
            var offset = 0
            val allTranscripts = mutableListOf<Transcript>()
            var fullText = StringBuilder()
            var lastPartialOutput = 0L
            var lastLogTime = System.currentTimeMillis()

            ctx.log("PCM data: $totalBytes bytes, expected ~${totalBytes / (SAMPLE_RATE * 2)} seconds of audio")

            while (offset < totalBytes) {
                // Cancellation check
                if (ctx.cancelled.get() || globalCancelled.get()) {
                    ctx.log("Task cancelled at offset=$offset/$totalBytes")
                    recognizer.close()
                    model.close()
                    return false
                }
                // Hard timeout check (10 min from task start, not just recognition)
                val taskElapsed = System.currentTimeMillis() - (ctx.startTime - (totalBytes / (SAMPLE_RATE * 2 * 3)).toLong()) // approximate
                if (System.currentTimeMillis() - ctx.startTime > TASK_TIMEOUT_MS) {
                    ctx.log("ERROR: Hard timeout after ${(System.currentTimeMillis() - ctx.startTime) / 1000}s, aborting")
                    recognizer.close()
                    model.close()
                    callback.onError("处理超时（超过10分钟），音频可能过长")
                    return false
                }
                // No-output timeout: 3分钟无结果才报错（之前120秒太短，下载+解码已消耗时间）
                if (allTranscripts.isEmpty() && System.currentTimeMillis() - ctx.startTime > 180000) {
                    ctx.log("ERROR: No result after 180s of recognition, PCM may be incompatible")
                    recognizer.close()
                    model.close()
                    callback.onError("语音识别超时：音频格式可能不兼容，请尝试其他节目")
                    return false
                }
                if (System.currentTimeMillis() - ctx.lastOutputTime > 60000) {
                    ctx.log("Warning: no output for ${(System.currentTimeMillis() - ctx.lastOutputTime) / 1000}s at offset=$offset/$totalBytes")
                    // 不立即失败，继续尝试
                }

                val chunkSize = minOf(bytesPerChunk, totalBytes - offset)
                val chunk = pcmData.copyOfRange(offset, offset + chunkSize)
                offset += chunkSize

                val shortBuffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val shorts = ShortArray(shortBuffer.remaining())
                shortBuffer.get(shorts)

                if (recognizer.acceptWaveForm(shorts, SAMPLE_RATE)) {
                    val result = recognizer.result
                    val text = parseVoskResult(result)
                    if (text.isNotEmpty()) {
                        val startSec = ((offset - chunkSize) / (SAMPLE_RATE * 2.0)).toInt()
                        val endSec = (offset / (SAMPLE_RATE * 2.0)).toInt()
                        val t = Transcript().apply {
                            this.episodeId = episodeId
                            this.segmentStart = startSec.toLong()
                            this.segmentEnd = endSec.toLong()
                            this.text = text
                            this.confidence = 0.9
                        }
                        allTranscripts.add(t)
                        dbHelper?.saveTranscript(t)
                        callback.onSubtitleGenerated(t)
                        fullText.append(text).append(" ")
                        ctx.lastOutputTime = System.currentTimeMillis()
                        ctx.log("Result [$startSec-$endSec]: $text")
                    }
                } else {
                    if (offset - lastPartialOutput >= SAMPLE_RATE * 2 * 2) {
                        try {
                            val partial = recognizer.partialResult
                            val partialText = parseVoskPartial(partial)
                            if (partialText.isNotBlank()) {
                                ctx.lastOutputTime = System.currentTimeMillis()
                                if (allTranscripts.size < 5) {
                                    ctx.log("Partial: $partialText")
                                }
                                lastPartialOutput = offset.toLong()
                            }
                        } catch (_: Exception) {}
                    }
                }

                val progress = 40 + (offset * 55 / totalBytes).toInt()
                reportProgress(callback, progress.coerceAtMost(95), 100, ctx)

                val now = System.currentTimeMillis()
                if (now - lastLogTime > 15000) {
                    ctx.log("Progress: ${offset * 100 / totalBytes}% (${offset / (SAMPLE_RATE * 2)}s / ${totalBytes / (SAMPLE_RATE * 2)}s), segments=${allTranscripts.size}")
                    lastLogTime = now
                }
            }

            // Final result
            val finalResult = recognizer.finalResult
            val finalText = parseVoskResult(finalResult)
            if (finalText.isNotEmpty()) {
                val startSec = (offset / (SAMPLE_RATE * 2.0)).toInt()
                val t = Transcript().apply {
                    this.episodeId = episodeId
                    this.segmentStart = startSec.toLong()
                    this.segmentEnd = (pcmData.size / (SAMPLE_RATE * 2.0)).toInt().toLong()
                    this.text = finalText
                    this.confidence = 0.9
                }
                allTranscripts.add(t)
                dbHelper?.saveTranscript(t)
                callback.onSubtitleGenerated(t)
                ctx.log("Final: $finalText")
            }

            recognizer.close()
            model.close()

            reportProgress(callback, 100, 100, ctx)
            val elapsed = (System.currentTimeMillis() - ctx.startTime) / 1000
            ctx.log("COMPLETE: ${allTranscripts.size} segments in ${elapsed}s")
            callback.onComplete(allTranscripts)

            pcmFile.delete()
            return true

        } catch (e: Exception) {
            ctx.log("EXCEPTION during Vosk processing: ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "Vosk recognition failed", e)
            pcmFile.delete()
            if (!ctx.cancelled.get()) callback.onError("识别失败: ${e.message}")
            return false
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
     * Vosk模型需要特定文件结构：am/final.mdl, conf/model.conf, ivector/final.ie 等
     */
    private fun isValidVoskModel(dir: File): Boolean {
        if (!dir.isDirectory) return false
        // 检查Vosk模型特征文件
        val hasAmDir = File(dir, "am").exists() && File(File(dir, "am"), "final.mdl").exists()
        val hasConf = File(dir, "conf").exists() && File(File(dir, "conf"), "model.conf").exists()
        val hasGraph = File(dir, "graph").exists() || File(dir, "HCLG.fst").exists()
        val hasMdlFile = dir.listFiles()?.any { it.name.endsWith(".mdl") } == true
        val valid = (hasAmDir && hasConf) || (hasMdlFile && hasConf) || (hasGraph && hasConf)
        if (valid) {
            logToFile("isValidVoskModel: ${dir.name} is a valid Vosk model")
        }
        return valid
    }

    /**
     * 检查目录是否是Whisper模型（ggml格式，供whisper.cpp使用，当前版本不支持）
     */
    private fun isWhisperModel(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val binFiles = dir.listFiles()?.filter { it.name.endsWith(".bin") && it.name.startsWith("ggml") }
        return binFiles != null && binFiles.isNotEmpty()
    }

    private fun findVoskModel(): String? {
        // 1. 检查外部存储手动下载的模型（只查找Vosk模型，不查找Whisper ggml模型）
        val modelsDir = getExternalFilesDir("models")
        if (modelsDir != null && modelsDir.exists()) {
            val modelDirs = modelsDir.listFiles()?.filter { it.isDirectory }
            if (!modelDirs.isNullOrEmpty()) {
                // 优先查找名称包含vosk的目录
                val voskDirs = modelDirs.filter { it.name.contains("vosk", ignoreCase = true) }
                for (dir in voskDirs) {
                    if (isValidVoskModel(dir)) {
                        logToFile("findVoskModel: found Vosk model (by name) in ${dir.absolutePath}")
                        return dir.absolutePath
                    }
                }
                // 其次查找其他目录，但验证是否是有效Vosk模型（排除Whisper）
                for (dir in modelDirs) {
                    if (isWhisperModel(dir)) {
                        logToFile("findVoskModel: skipping Whisper model in ${dir.name} (not supported in this version)")
                        continue
                    }
                    if (isValidVoskModel(dir)) {
                        logToFile("findVoskModel: found valid Vosk model in ${dir.absolutePath}")
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
        logToFile("findVoskModel: no Vosk model found. Checked ${modelsDir?.absolutePath} and ${internalModelDir.absolutePath}")
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
        val shorts = ShortArray(input.size / 2)
        ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        val ratio = inSampleRate.toDouble() / outSampleRate
        val outLength = (shorts.size / inChannels / ratio).toInt() * outChannels
        val output = ShortArray(outLength)
        var outIdx = 0; var inIdx = 0.0
        while (outIdx < outLength) {
            val srcIdx = inIdx.toInt() * inChannels
            val nextIdx = ((inIdx.toInt() + 1) * inChannels).coerceAtMost(shorts.size - 1)
            val frac = inIdx - inIdx.toInt()
            // Linear interpolation for better quality
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
        globalCancelled.set(false)
        if (intent != null) {
            val action = intent.action
            if (action == "cancel_all") {
                logToFile("onStartCommand: cancel_all action received")
                cancelAllTasks()
                stopSelf()
                return START_NOT_STICKY
            }

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

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
    }

    fun getSubtitles(episodeId: String): List<Transcript>? = dbHelper?.getTranscripts(episodeId)
    fun isOfflineAvailable(): Boolean = findVoskModel() != null

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
