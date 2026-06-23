package com.radio.app.services

import android.app.Service
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Binder
import android.os.IBinder
import android.util.Log
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

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor()
        dbHelper = RadioDatabaseHelper.getInstance(this)
    }

    /**
     * 生成ASR字幕。
     * 优先使用Vosk离线引擎，不可用则提示用户下载Vosk模型
     */
    fun generateSubtitlesForEpisode(episodeId: String, audioUrl: String, callback: SubtitleCallback) {
        executor?.execute {
            try {
                val voskModel = findVoskModel()
                if (voskModel != null) {
                    val voskSuccess = generateWithVosk(episodeId, audioUrl, callback)
                    if (!voskSuccess) {
                        Log.w(TAG, "Vosk failed, no fallback available")
                        callback.onError("字幕生成失败：请检查Vosk模型是否完整，或尝试更换ASR引擎")
                    }
                } else {
                    Log.w(TAG, "No Vosk model, cannot generate subtitles")
                    callback.onError("未找到Vosk离线模型，请在设置中下载Vosk模型后再试")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Subtitle generation failed", e)
                callback.onError("字幕生成失败: ${e.message}")
            }
        }
    }

    /**
     * AI分段：基于ASR结果生成语音分段（VoiceSegment列表）
     * 使用与字幕生成相同的引擎，但输出为VoiceSegment而非Transcript
     */
    fun generateSegmentsForEpisode(episodeId: String, audioUrl: String, callback: SegmentCallback) {
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
                        callback.onError(error)
                    }
                    override fun onComplete(transcripts: List<Transcript>) {
                        callback.onComplete(allSegments)
                    }
                }
                val voskModel = findVoskModel()
                if (voskModel != null) {
                    val voskSuccess = generateWithVosk(episodeId, audioUrl, subtitleCallback)
                    if (!voskSuccess) {
                        Log.w(TAG, "Vosk segment failed, no fallback")
                        callback.onError("AI分段失败：请检查Vosk模型是否完整，或尝试更换AI模型")
                    }
                } else {
                    Log.w(TAG, "No Vosk model, cannot generate segments")
                    callback.onError("未找到Vosk离线模型，AI分段需要Vosk离线引擎，请在设置中下载")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Segment generation failed", e)
                callback.onError("AI分段失败: ${e.message}")
            }
        }
    }

    private fun generateWithVosk(episodeId: String, audioUrl: String, callback: SubtitleCallback): Boolean {
        // 1. 查找可用的Vosk模型
        val modelPath = findVoskModel()
        if (modelPath == null) {
            Log.w(TAG, "No Vosk model found")
            callback.onError("未找到Vosk模型，请在离线引擎管理中下载Vosk模型")
            return false
        }

        callback.onProgressUpdate(0, 100)
        Log.d(TAG, "Using Vosk model: $modelPath")

        // 2. 下载音频文件（优先使用缓存，带进度回调）
        callback.onProgressUpdate(1, 100)
        val audioFile = getAudioFile(audioUrl) { progress -> callback.onProgressUpdate(progress, 100) }
        if (audioFile == null || !audioFile.exists() || audioFile.length() < 1024) {
            Log.w(TAG, "Audio file not available")
            callback.onError("音频文件不可用，无法生成字幕")
            return false
        }

        callback.onProgressUpdate(15, 100)
        Log.d(TAG, "Audio file ready: ${audioFile.absolutePath} size=${audioFile.length()}")

        // 3. 将音频解码为16kHz单声道PCM
        val pcmFile = File(cacheDir, "subtitle_pcm_${Math.abs(audioUrl.hashCode())}.pcm")
        try {
            callback.onProgressUpdate(20, 100)
            decodeToPcm(audioFile, pcmFile)
            Log.d(TAG, "PCM decoded: ${pcmFile.length()} bytes")
            callback.onProgressUpdate(40, 100)

            // 4. 使用Vosk进行语音识别
            val model = Model(modelPath)
            val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())

            val pcmData = pcmFile.readBytes()
            val totalBytes = pcmData.size
            val bytesPerChunk = SAMPLE_RATE * 2 * 10  // 10秒每块 (16bit = 2 bytes)
            var offset = 0
            var segmentIndex = 0
            val allTranscripts = mutableListOf<Transcript>()
            var fullText = StringBuilder()

            while (offset < totalBytes) {
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
                    }
                }

                // 更新进度
                val progress = 40 + (offset * 55 / totalBytes).toInt()
                callback.onProgressUpdate(progress.coerceAtMost(95), 100)
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
            }

            recognizer.close()
            model.close()

            callback.onProgressUpdate(100, 100)
            Log.d(TAG, "Vosk recognition complete: ${allTranscripts.size} segments")
            callback.onComplete(allTranscripts)

            // 清理临时文件
            pcmFile.delete()
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Vosk recognition failed", e)
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
     */
    private fun getAudioFile(audioUrl: String, onProgress: ((Int) -> Unit)? = null): File? {
        // 优先检查缓存
        val cacheDir = getExternalFilesDir("audio")
        if (cacheDir != null) {
            val cachedFile = File(cacheDir, "${Math.abs(audioUrl.hashCode())}.mp3")
            if (cachedFile.exists() && cachedFile.length() > 1024) {
                onProgress?.invoke(5)
                return cachedFile
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
    private fun decodeToPcm(audioFile: File, pcmFile: File) {
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

    fun getSubtitles(episodeId: String): List<Transcript>? {
        return dbHelper?.getTranscripts(episodeId)
    }

    fun isOfflineAvailable(): Boolean {
        return findVoskModel() != null
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        executor?.shutdown()
    }
}
