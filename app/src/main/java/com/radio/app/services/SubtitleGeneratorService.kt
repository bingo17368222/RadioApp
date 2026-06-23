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
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SubtitleGeneratorService : Service() {

    companion object {
        private const val TAG = "SubtitleGenService"
        private const val SAMPLE_RATE = 16000
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "subtitle_progress_channel"
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

    private val binder = LocalBinder()
    private var executor: ExecutorService? = null
    private var dbHelper: RadioDatabaseHelper? = null
    private var wakeLock: PowerManager.WakeLock? = null
    // 防止进度回退
    @Volatile
    private var lastReportedProgress = 0

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newFixedThreadPool(2)  // 支持字幕和AI分段同时进行
        dbHelper = RadioDatabaseHelper.getInstance(this)

        // 创建通知渠道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音频处理",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * 生成ASR字幕。
     * 优先使用Vosk离线引擎，不可用则提示用户下载Vosk模型
     */
    fun generateSubtitlesForEpisode(episodeId: String, audioUrl: String, callback: SubtitleCallback) {
        Log.d(TAG, "generateSubtitlesForEpisode: episodeId=$episodeId, audioUrl=$audioUrl")
        executor?.execute {
            try {
                val voskModel = findVoskModel()
                if (voskModel != null) {
                    Log.d(TAG, "Using Vosk model: $voskModel")
                    val voskSuccess = generateWithVosk(episodeId, audioUrl, callback)
                    if (!voskSuccess) {
                        Log.e(TAG, "Vosk subtitle generation failed for episodeId=$episodeId")
                        callback.onError("字幕生成失败：音频识别模型不可用，请在离线引擎管理中下载")
                    }
                } else {
                    Log.e(TAG, "No Vosk model found, cannot generate subtitles")
                    callback.onError("未找到离线识别模型，请在离线引擎管理中下载")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Subtitle generation failed for episodeId=$episodeId", e)
                callback.onError("字幕生成失败: ${e.message}")
            }
        }
    }

    /**
     * AI分段：基于ASR结果生成语音分段（VoiceSegment列表）
     * 使用与字幕生成相同的引擎，但输出为VoiceSegment而非Transcript
     */
    fun generateSegmentsForEpisode(episodeId: String, audioUrl: String, callback: SegmentCallback) {
        Log.d(TAG, "generateSegmentsForEpisode: episodeId=$episodeId, audioUrl=$audioUrl")
        executor?.execute {
            try {
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
                        callback.onSegmentGenerated(segment)
                    }
                    override fun onProgressUpdate(progress: Int, total: Int) {
                        callback.onProgressUpdate(progress, total)
                    }
                    override fun onError(error: String) {
                        Log.e(TAG, "Segment generation error: $error")
                        callback.onError(error)
                    }
                    override fun onComplete(transcripts: List<Transcript>) {
                        callback.onComplete(allSegments)
                    }
                }
                val voskModel = findVoskModel()
                if (voskModel != null) {
                    Log.d(TAG, "Using Vosk model for segments: $voskModel")
                    val voskSuccess = generateWithVosk(episodeId, audioUrl, subtitleCallback)
                    if (!voskSuccess) {
                        Log.e(TAG, "Vosk segment generation failed for episodeId=$episodeId")
                        callback.onError("AI分段失败：音频识别模型不可用，请在离线引擎管理中下载模型")
                    }
                } else {
                    Log.e(TAG, "No Vosk model for segments")
                    callback.onError("AI分段失败：未找到离线识别模型，请在离线引擎管理中下载")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Segment generation failed for episodeId=$episodeId", e)
                callback.onError("AI分段失败: ${e.message}")
            }
        }
    }

    /**
     * 单调进度上报：只有当前进度 >= 上次进度时才上报，防止进度回退
     */
    private fun reportProgress(callback: SubtitleCallback, progress: Int, total: Int) {
        // 只有进度更大时才更新（防止并发导致的回退）
        if (progress >= lastReportedProgress) {
            lastReportedProgress = progress
            callback.onProgressUpdate(progress, total)
        } else {
            Log.w(TAG, "Progress regression prevented: $progress < $lastReportedProgress (ignored)")
        }
    }

    private fun generateWithVosk(episodeId: String, audioUrl: String, callback: SubtitleCallback): Boolean {
        // 重置进度计数器
        lastReportedProgress = 0
        
        // 1. 查找可用的Vosk模型
        val modelPath = findVoskModel()
        if (modelPath == null) {
            Log.e(TAG, "No Vosk model found for episodeId=$episodeId")
            callback.onError("未找到Vosk模型，请在离线引擎管理中下载Vosk模型")
            return false
        }

        reportProgress(callback, 0, 100)
        Log.d(TAG, "[Phase 0] Using Vosk model: $modelPath")

        // 2. 下载音频文件（优先使用缓存，带进度回调 0-14%）
        reportProgress(callback, 1, 100)
        Log.d(TAG, "[Phase 1] Starting audio download: $audioUrl")
        val audioFile = getAudioFile(audioUrl) { progress ->
            // 下载进度映射到 1-14%
            val mappedProgress = (1 + progress * 13 / 14).coerceAtMost(14)
            reportProgress(callback, mappedProgress, 100)
        }
        if (audioFile == null) {
            Log.e(TAG, "Audio download failed for $audioUrl")
            callback.onError("音频下载失败：网络不通或音频链接失效")
            return false
        }
        if (!audioFile.exists()) {
            Log.e(TAG, "Audio file not found after download: ${audioFile.absolutePath}")
            callback.onError("音频文件下载后丢失，请重试")
            return false
        }
        if (audioFile.length() < 1024) {
            Log.e(TAG, "Audio file too small: ${audioFile.length()} bytes")
            callback.onError("音频文件下载不完整（${audioFile.length()}字节），请检查网络后重试")
            return false
        }

        reportProgress(callback, 15, 100)
        Log.d(TAG, "[Phase 2] Audio file ready: ${audioFile.absolutePath} size=${audioFile.length()}")

        // 3. 将音频解码为16kHz单声道PCM（16-40%进度）
        val pcmFile = File(cacheDir, "subtitle_pcm_${Math.abs(audioUrl.hashCode())}.pcm")
        try {
            reportProgress(callback, 16, 100)
            Log.d(TAG, "[Phase 3] Starting PCM decode...")
            // Get audio duration for progress calculation
            val durationExtractor = MediaExtractor()
            var audioDurationUs = 0L
            try {
                durationExtractor.setDataSource(audioFile.absolutePath)
                for (i in 0 until durationExtractor.trackCount) {
                    val fmt = durationExtractor.getTrackFormat(i)
                    if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        audioDurationUs = fmt.getLong(MediaFormat.KEY_DURATION)
                        Log.d(TAG, "Audio duration: ${audioDurationUs}us (${audioDurationUs / 1000000}s)")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get duration for progress: ${e.message}")
            } finally {
                durationExtractor.release()
            }
            decodeToPcm(audioFile, pcmFile, audioDurationUs) { pct ->
                // PCM解码进度: 16-40%
                reportProgress(callback, 16 + pct * 24 / 100, 100)
            }
            Log.d(TAG, "[Phase 4] PCM decoded: ${pcmFile.length()} bytes")
            reportProgress(callback, 40, 100)

            // 4. 使用Vosk进行语音识别（40-95%进度）
            Log.d(TAG, "[Phase 5] Starting Vosk recognition...")
            Log.d(TAG, "Loading Vosk model from: $modelPath")
            val model = Model(modelPath)
            val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            Log.d(TAG, "Vosk model loaded, starting recognition")

            val pcmData = pcmFile.readBytes()
            val totalBytes = pcmData.size
            val bytesPerChunk = SAMPLE_RATE * 2 * 3  // 3秒每块，更快出结果 (16bit = 2 bytes)
            var offset = 0
            var segmentIndex = 0
            val allTranscripts = mutableListOf<Transcript>()
            var fullText = StringBuilder()
            var lastPartialOutput = 0L  // 上次输出部分结果的偏移量
            var lastLogTime = System.currentTimeMillis()
            var lastOutputTime = System.currentTimeMillis()  // 上次有输出的时间

            val startTime = System.currentTimeMillis()
            while (offset < totalBytes) {
                // 检查是否超时（超过30秒无输出则告警，超过120秒无输出则中止）
                val now = System.currentTimeMillis()
                if (allTranscripts.isEmpty() && now - startTime > 120000) {
                    Log.e(TAG, "Vosk recognition timeout: no output after 120 seconds, aborting")
                    recognizer.close()
                    model.close()
                    callback.onError("语音识别超时：音频可能无法识别，请检查模型是否匹配")
                    return false
                }
                if (now - lastOutputTime > 30000) {
                    Log.w(TAG, "Vosk recognition: no output for 30 seconds (offset=$offset/$totalBytes)")
                    lastOutputTime = now  // 防止重复告警
                }
                
                val chunkSize = minOf(bytesPerChunk, totalBytes - offset)
                val chunk = pcmData.copyOfRange(offset, offset + chunkSize)
                offset += chunkSize

                // 将byte[]转换为short[]（16bit PCM，小端序）
                val shortBuffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                val shorts = ShortArray(shortBuffer.remaining())
                shortBuffer.get(shorts)

                if (recognizer.acceptWaveForm(shorts, SAMPLE_RATE)) {
                    // 获得完整识别结果
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
                        lastOutputTime = System.currentTimeMillis()
                        Log.d(TAG, "Vosk result: $startSec-$endSec: $text")
                    }
                } else {
                    // 尝试获取部分识别结果，确保任何时候都有输出
                    if (offset - lastPartialOutput >= SAMPLE_RATE * 2 * 2) {
                        // 每2秒PCM数据尝试一次partialResult
                        try {
                            val partial = recognizer.partialResult
                            val partialText = parseVoskResult(partial)
                            // 只要有文字就输出，不限制长度
                            if (partialText.isNotBlank()) {
                                val startSec = ((offset - chunkSize) / (SAMPLE_RATE * 2.0)).toInt()
                                val endSec = (offset / (SAMPLE_RATE * 2.0)).toInt()
                                val t = Transcript().apply {
                                    this.episodeId = episodeId
                                    this.segmentStart = startSec.toLong()
                                    this.segmentEnd = endSec.toLong()
                                    this.text = "[识别中] $partialText"
                                    this.confidence = 0.5
                                }
                                callback.onSubtitleGenerated(t)
                                lastPartialOutput = offset.toLong()
                                lastOutputTime = System.currentTimeMillis()
                                Log.d(TAG, "Vosk partial: $startSec-$endSec: $partialText")
                            }
                        } catch (_: Exception) {
                            // partialResult is optional
                        }
                    }
                }

                // 更新进度 (40-95%)
                val progress = 40 + (offset * 55 / totalBytes).toInt()
                reportProgress(callback, progress.coerceAtMost(95), 100)
                
                // 每10秒输出一次状态日志
                if (now - lastLogTime > 10000) {
                    Log.d(TAG, "Vosk progress: offset=$offset/$totalBytes (${offset * 100 / totalBytes}%), segments=${allTranscripts.size}, text='${fullText.take(50)}'")
                    lastLogTime = now
                }
                
                segmentIndex++
            }

            // 获取最后的结果
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
                Log.d(TAG, "Vosk final: $finalText")
            }

            recognizer.close()
            model.close()

            reportProgress(callback, 100, 100)
            val elapsed = (System.currentTimeMillis() - startTime) / 1000
            Log.d(TAG, "Vosk recognition complete: ${allTranscripts.size} segments in ${elapsed}s, text='${fullText.take(100)}'")
            callback.onComplete(allTranscripts)

            // 清理临时文件
            pcmFile.delete()
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Vosk recognition failed for episodeId=$episodeId", e)
            pcmFile.delete()
            return false
        }
    }

    /**
     * 查找可用的Vosk模型目录
     */
    private fun findVoskModel(): String? {
        // 检查离线引擎目录
        val modelsDir = getExternalFilesDir("models")
        if (modelsDir != null && modelsDir.exists()) {
            val voskDirs = modelsDir.listFiles()?.filter { it.isDirectory && it.name.contains("vosk") }
            if (!voskDirs.isNullOrEmpty()) {
                for (dir in voskDirs) {
                    // Vosk模型目录应包含 README 或 am/ 目录
                    if (dir.listFiles()?.isNotEmpty() == true) {
                        return dir.absolutePath
                    }
                }
            }
        }

        // 检查内部存储
        val internalModelDir = File(filesDir, "vosk-model-small-cn-0.22")
        if (internalModelDir.exists() && internalModelDir.listFiles()?.isNotEmpty() == true) {
            return internalModelDir.absolutePath
        }

        return null
    }

    /**
     * 获取音频文件（优先缓存）
     * 支持M3U8流（直播）和直接音频文件（回放）
     * 优先复用播放服务已下载的缓存文件，避免重复下载
     */
    private fun getAudioFile(audioUrl: String, onProgress: ((Int) -> Unit)? = null): File? {
        // 1. 检查SubtitleGeneratorService自身缓存
        val cacheDir = getExternalFilesDir("audio")
        if (cacheDir != null) {
            val cachedFile = File(cacheDir, "${Math.abs(audioUrl.hashCode())}.mp3")
            if (cachedFile.exists() && cachedFile.length() > 1024) {
                onProgress?.invoke(5)
                return cachedFile
            }
        }

        // 2. 检查RadioPlaybackService的下载缓存 (cacheDir/episodes/)
        //    播放服务下载音频后缓存在此目录，字幕服务应复用而非重新下载
        val episodesCacheDir = File(this.cacheDir, "episodes")
        if (episodesCacheDir.exists()) {
            try {
                val path = java.net.URL(audioUrl).path
                val fileName = path.substringAfterLast("/")
                if (fileName.isNotBlank()) {
                    val playbackCachedFile = File(episodesCacheDir, fileName)
                    if (playbackCachedFile.exists() && playbackCachedFile.length() > 1024) {
                        Log.d(TAG, "Reusing playback cache: ${playbackCachedFile.absolutePath} (${playbackCachedFile.length()} bytes)")
                        onProgress?.invoke(5)
                        return playbackCachedFile
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check playback cache: ${e.message}")
            }
        }

        // M3U8流不能下载，返回null
        if (audioUrl.endsWith(".m3u8", ignoreCase = true) ||
            audioUrl.contains("/live/", ignoreCase = true)) {
            Log.w(TAG, "M3U8 stream cannot be downloaded for ASR: $audioUrl")
            return null
        }

        // 下载直接音频文件，带进度回调
        return downloadAudioWithProgress(audioUrl) { progress ->
            onProgress?.invoke((progress * 14 / 30).coerceAtMost(14))
        }
    }

    /**
     * 使用MediaExtractor + MediaCodec将音频解码为16kHz单声道PCM
     */
    private fun decodeToPcm(audioFile: File, pcmFile: File, durationUs: Long = 0L, onProgress: ((Int) -> Unit)? = null) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(audioFile.absolutePath)

            // 查找音频轨道
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                throw Exception("No audio track found in ${audioFile.name}")
            }

            extractor.selectTrack(audioTrackIndex)

            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val inputBufferIndex = IntArray(1)
            val outputBufferIndex = IntArray(1)
            val bufferInfo = MediaCodec.BufferInfo()
            val fos = FileOutputStream(pcmFile)

            // 重采样参数
            val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val bitsPerSample = 16
            val bytesPerSample = bitsPerSample / 8

            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val buffer = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10000)
                when {
                    outIdx >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIdx)!!
                        if (bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            // 将解码后的PCM数据重采样为16kHz单声道
                            val pcmBytes = ByteArray(bufferInfo.size)
                            buffer.position(bufferInfo.offset)
                            buffer.get(pcmBytes)

                            // 简单重采样：如果采样率不是16kHz，进行降采样
                            // 如果是立体声，取左声道
                            val resampled = resamplePcm(pcmBytes, sampleRate, channelCount, SAMPLE_RATE, 1)
                            fos.write(resampled)
                            // Report progress based on presentation time
                            if (durationUs > 0 && onProgress != null) {
                                val pct = (bufferInfo.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 100)
                                onProgress.invoke(pct)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 格式变化，忽略
                    }
                }
            }

            codec.stop()
            codec.release()
            fos.close()
            extractor.release()

        } catch (e: Exception) {
            Log.e(TAG, "decodeToPcm failed", e)
            try { extractor.release() } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * 简单PCM重采样：调整采样率和声道数
     */
    private fun resamplePcm(input: ByteArray, inSampleRate: Int, inChannels: Int,
                            outSampleRate: Int, outChannels: Int): ByteArray {
        val shorts = ShortArray(input.size / 2)
        ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

        val ratio = inSampleRate.toDouble() / outSampleRate
        val outLength = (shorts.size / inChannels / ratio).toInt() * outChannels
        val output = ShortArray(outLength)

        var outIdx = 0
        var inIdx = 0.0
        while (outIdx < outLength) {
            val srcIdx = (inIdx * inChannels).toInt()
            // 取第一个声道
            if (srcIdx < shorts.size) {
                output[outIdx] = shorts[srcIdx]
            }
            outIdx++
            inIdx += ratio
        }

        // 转回byte[]
        val result = ByteArray(output.size * 2)
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(output)
        return result
    }

    /**
     * 解析Vosk JSON结果，提取文本
     */
    private fun parseVoskResult(jsonResult: String): String {
        return try {
            val json = org.json.JSONObject(jsonResult)
            if (json.has("text")) {
                json.getString("text")
            } else if (json.has("partial")) {
                "" // 部分结果不返回
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Vosk result: $jsonResult")
            ""
        }
    }

    private fun downloadAudio(audioUrl: String): File? {
        var outFile: File? = null
        var conn: HttpURLConnection? = null
        try {
            outFile = File(cacheDir, "subtitle_audio_${Math.abs(audioUrl.hashCode())}.tmp")

            // 手动处理重定向
            var downloadUrl = audioUrl
            var redirectCount = 0
            while (redirectCount < 5) {
                val url = URL(downloadUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36")

                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    conn = null
                    if (location.isNullOrBlank()) break
                    downloadUrl = if (location.startsWith("http")) location
                    else URL(URL(downloadUrl), location).toString()
                    redirectCount++
                } else if (code == 200) {
                    break
                } else {
                    conn.disconnect()
                    conn = null
                    Log.w(TAG, "Audio download HTTP $code")
                    return null
                }
            }

            if (conn == null) return null

            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    val buffer = ByteArray(8192)
                    var len: Int
                    while (input.read(buffer).also { len = it } != -1) {
                        output.write(buffer, 0, len)
                    }
                }
            }
            return outFile
        } catch (e: Exception) {
            Log.e(TAG, "Audio download failed: ${e.message}")
            outFile?.delete()
            return null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 下载音频文件，并实时报告进度（0-20%）
     */
    private fun downloadAudioWithProgress(audioUrl: String, onProgress: (Int) -> Unit): File? {
        var outFile: File? = null
        var conn: HttpURLConnection? = null
        try {
            outFile = File(cacheDir, "subtitle_audio_${Math.abs(audioUrl.hashCode())}.tmp")

            var downloadUrl = audioUrl
            var redirectCount = 0
            while (redirectCount < 5) {
                val url = URL(downloadUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36")
                conn.connect()

                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    conn = null
                    if (location.isNullOrBlank()) break
                    downloadUrl = if (location.startsWith("http")) location
                    else URL(URL(downloadUrl), location).toString()
                    redirectCount++
                } else if (code == 200) {
                    break
                } else {
                    conn.disconnect()
                    conn = null
                    Log.w(TAG, "Audio download HTTP $code for $downloadUrl")
                    return null
                }
            }

            if (conn == null) {
                Log.w(TAG, "Audio download failed: connection is null after redirects")
                return null
            }

            if (conn.responseCode != 200) {
                Log.w(TAG, "Audio download failed: HTTP ${conn.responseCode}")
                conn.disconnect()
                return null
            }

            val totalBytes = conn.contentLength.toLong()
            var downloadedBytes = 0L
            var lastReportedPct = 0

            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    val buffer = ByteArray(8192)
                    var len: Int
                    while (input.read(buffer).also { len = it } != -1) {
                        output.write(buffer, 0, len)
                        downloadedBytes += len
                        if (totalBytes > 0) {
                            // 下载进度映射到 0-30%
                            val pct = (downloadedBytes * 30 / totalBytes).toInt()
                            if (pct > lastReportedPct) {
                                lastReportedPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            return outFile
        } catch (e: Exception) {
            Log.e(TAG, "Audio download failed: ${e.message}")
            outFile?.delete()
            return null
        } finally {
            conn?.disconnect()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val episodeId = intent.getStringExtra("episode_id") ?: return START_NOT_STICKY
            val audioUrl = intent.getStringExtra("audio_url") ?: return START_NOT_STICKY
            val taskType = intent.getStringExtra("task_type") ?: "subtitle"
            val taskLabel = if (taskType == "segment") "AI分段" else "字幕生成"

            // 获取WakeLock防止CPU休眠
            if (wakeLock == null) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SubtitleGenService:WakeLock")
            }
            wakeLock?.acquire(3600000) // 最多1小时

            // 必须调用startForeground()，否则ForegroundServiceDidNotStartInTimeException
            // 使用低优先级通知，避免与播放通知冲突
            val progressNotification = createProgressNotification(0, taskLabel)
            try {
                startForeground(NOTIFICATION_ID, progressNotification)
            } catch (e: Exception) {
                Log.w(TAG, "startForeground failed: ${e.message}")
            }

            if (taskType == "segment") {
                generateSegmentsForEpisode(episodeId, audioUrl, object : SegmentCallback {
                    override fun onProgressUpdate(progress: Int, total: Int) {
                        updateProgressNotification(progress, taskLabel)
                    }
                    override fun onSegmentGenerated(segment: VoiceSegment) {
                        // 每个片段生成时保存到DB
                        dbHelper?.saveVoiceSegment(episodeId, segment)
                    }
                    override fun onComplete(segments: List<VoiceSegment>) {
                        Log.d(TAG, "Segment generation complete: ${segments.size} segments")
                        // 保存所有分段到DB
                        dbHelper?.saveVoiceSegments(episodeId, segments)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        releaseWakeLock()
                        stopSelf()
                    }
                    override fun onError(error: String) {
                        Log.e(TAG, "Segment generation error in onStartCommand: $error")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        releaseWakeLock()
                        stopSelf()
                    }
                })
            } else {
                generateSubtitlesForEpisode(episodeId, audioUrl, object : SubtitleCallback {
                    override fun onProgressUpdate(progress: Int, total: Int) {
                        updateProgressNotification(progress, taskLabel)
                    }
                    override fun onSubtitleGenerated(transcript: Transcript) {
                        // 每个字幕生成时保存到DB
                        dbHelper?.saveTranscript(transcript)
                    }
                    override fun onComplete(transcripts: List<Transcript>) {
                        Log.d(TAG, "Subtitle generation complete: ${transcripts.size} transcripts")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        releaseWakeLock()
                        stopSelf()
                    }
                    override fun onError(error: String) {
                        Log.e(TAG, "Subtitle generation error in onStartCommand: $error")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        releaseWakeLock()
                        stopSelf()
                    }
                })
            }
        }
        return START_NOT_STICKY
    }

    private fun createProgressNotification(progress: Int, taskLabel: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在处理音频")
            .setContentText("$taskLabel: ${progress}%")
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateProgressNotification(progress: Int, taskLabel: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createProgressNotification(progress, taskLabel))
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
    }

    fun getSubtitles(episodeId: String): List<Transcript>? {
        return dbHelper?.getTranscripts(episodeId)
    }

    fun isOfflineAvailable(): Boolean {
        return findVoskModel() != null
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: cleaning up resources")
        releaseWakeLock()
        executor?.shutdown()
        // 清除处理状态，防止下次启动时显示虚假的处理中状态
        try {
            getSharedPreferences("player_processing_state", MODE_PRIVATE).edit().clear().apply()
            Log.d(TAG, "onDestroy: cleared processing state")
        } catch (_: Exception) {}
        // 移除通知
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
    }
}
