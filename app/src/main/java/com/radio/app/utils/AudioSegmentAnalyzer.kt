package com.radio.app.utils

import android.content.Context
import android.util.Log
import com.radio.app.models.VoiceSegment
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/**
 * v2.4.95: Audio-based AI segment analyzer using dual models.
 *
 * Silero VAD (ONNX, ~2.3MB): High-precision voice activity detection
 * YAMNet (TFLite, ~4.1MB): Audio classification (521 categories: Speech, Music, Silence, etc.)
 *
 * Processing:
 * 1. Read 16kHz mono PCM data
 * 2. Slide window (YAMNet: 0.975s, Silero VAD: 0.5s step)
 * 3. For each window:
 *    - YAMNet: classify into Speech/Music/Silence probabilities
 *    - Silero VAD: get speech probability
 * 4. Fuse results: Speech+VAD → 干货, Music → 水货, Silence → boundary
 * 5. Merge consecutive same-type frames into segments
 *
 * Requires runtime libraries (downloaded from offline engine management):
 * - libonnxruntime.so, libonnxruntime4j_jni.so (for Silero VAD)
 * - libtensorflowlite_jni.so (for YAMNet)
 * - silero_vad.onnx (model file)
 * - yamnet.tflite (model file)
 */
object AudioSegmentAnalyzer {
    private const val TAG = "AudioSegmentAnalyzer"

    // v2.4.115: File-based logger for VAD diagnostics (Log.i/Log.e not captured by app's log system)
    private var logFile: File? = null
    private var logContext: Context? = null

    // v2.4.115: Counter for limiting diagnostic logs in runSileroVad
    @Volatile
    private var vadRunCount: Int = 0

    fun setLogContext(context: Context) {
        logContext = context
        try {
            // v2.4.116: Use RadioApplication.getLogDir() so logs are collected by the log system.
            // Previously used getExternalFilesDir(null)/logs/audio_segment/ which is a different path.
            val baseLogDir = com.radio.app.RadioApplication.getLogDir(context)
            val logDir = File(baseLogDir, "audio_segment")
            if (!logDir.exists()) logDir.mkdirs()
            logFile = File(logDir, "audio_segment.log")
            vadLog("=== setLogContext: logFile=${logFile?.absolutePath} ===")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create log file: ${e.message}")
        }
    }

    private fun vadLog(msg: String) {
        Log.i(TAG, msg)
        try {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            logFile?.let { f ->
                f.appendText("[$timestamp] $msg\n")
            }
        } catch (_: Exception) {}
    }

    // YAMNet: 16kHz, 0.975s window = 15600 samples
    private const val YAMNET_SAMPLE_RATE = 16000
    private const val YAMNET_WINDOW_SAMPLES = 15600
    private const val YAMNET_NUM_CLASSES = 521

    // YAMNet class indices (from AudioSet ontology)
    private const val YAMNET_IDX_SPEECH = 0       // Speech
    private const val YAMNET_IDX_SILENCE = 78     // Silence
    private const val YAMNET_IDX_MUSIC = 137      // Music
    private const val YAMNET_IDX_SONG = 138       // Singing

    // Frame step: 0.5s (8000 samples at 16kHz)
    private const val FRAME_STEP_SAMPLES = 8000

    // Silero VAD: 512 samples per chunk
    private const val VAD_FRAME_SIZE = 512
    // v2.4.142: Silero VAD expects 64 samples of previous audio as context prepended to each 512-sample chunk.
    private const val VAD_CONTEXT_SIZE = 64
    private const val VAD_DRY_THRESHOLD = 0.45f
    private const val VAD_WATER_THRESHOLD = 0.15f

    // Energy thresholds (relative to max energy)
    private const val ENERGY_SILENCE_RATIO = 0.05f
    private const val ENERGY_MUSIC_RATIO = 0.3f

    // Classification results
    private enum class FrameType { DRY, WATER, SILENCE }

    /**
     * Check if YAMNet model file exists.
     */
    fun isYamnetInstalled(modelDir: File): Boolean {
        val f = File(modelDir, "yamnet.tflite")
        return f.exists() && f.length() > 1_000_000
    }

    /**
     * Check if Silero VAD model file exists.
     */
    fun isSileroVadInstalled(modelDir: File): Boolean {
        val f = File(modelDir, "silero_vad.onnx")
        return f.exists() && f.length() > 50_000
    }

    /**
     * Check if native libraries are downloaded.
     */
    fun areNativeLibsDownloaded(modelDir: File): Boolean {
        return NativeLibLoader.areLibsDownloaded(modelDir)
    }

    /**
     * Check if all required models are installed.
     * v2.4.95: Requires both YAMNet and Silero VAD + native libs.
     */
    fun isModelInstalled(modelDir: File): Boolean {
        return isYamnetInstalled(modelDir) && isSileroVadInstalled(modelDir) && areNativeLibsDownloaded(modelDir)
    }

    // v2.4.138: Required PCM cache version. Bump when info file format or resampling changes.
    private const val REQUIRED_PCM_VERSION = 7

    /**
     * v2.4.138: PCM info file metadata.
     */
    private data class PcmInfo(
        val version: Int,
        val mp4DurationMs: Long,
        val pcmDurationMs: Long,
        val sampleRate: Int,
        val channels: Int
    ) {
        fun isValid(): Boolean = version >= REQUIRED_PCM_VERSION && mp4DurationMs > 0 && pcmDurationMs > 0
    }

    /**
     * v2.4.138: Read .info file for a PCM file.
     */
    private fun readPcmInfo(infoFile: File): PcmInfo? {
        if (!infoFile.exists()) return null
        return try {
            val text = infoFile.readText()
            fun longOf(name: String): Long = Regex("$name=(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            fun intOf(name: String): Int = Regex("$name=(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            PcmInfo(
                version = intOf("version"),
                mp4DurationMs = longOf("mp4DurationMs"),
                pcmDurationMs = longOf("pcmDurationMs"),
                sampleRate = intOf("sampleRate"),
                channels = intOf("channels")
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * v2.4.138: Write .info file for a PCM file with duration metadata.
     */
    private fun writePcmInfo(infoFile: File, mp4DurationMs: Long, pcmDurationMs: Long, sampleRate: Int = 16000, channels: Int = 1) {
        try {
            infoFile.writeText(
                "version=$REQUIRED_PCM_VERSION\n" +
                "mp4DurationMs=$mp4DurationMs\n" +
                "pcmDurationMs=$pcmDurationMs\n" +
                "sampleRate=$sampleRate\n" +
                "channels=$channels\n"
            )
        } catch (e: Exception) {
            Log.w(TAG, "[v2.4.138] writePcmInfo failed: ${e.message}")
        }
    }

    /**
     * v2.4.138: Determine whether cached PCM is valid by comparing its info-file durations
     * with the source MP4 duration. Returns the matching PcmInfo if valid, null otherwise.
     */
    private fun validatePcmWithInfo(
        pcmFile: File,
        infoFile: File,
        currentMp4DurationMs: Long,
        toleranceRatio: Double = 0.1
    ): PcmInfo? {
        if (!pcmFile.exists() || pcmFile.length() <= 16000) return null
        val info = readPcmInfo(infoFile) ?: return null
        if (!info.isValid()) return null
        // If the source MP4 duration is known and differs from the recorded MP4 duration,
        // the source file may have been replaced (e.g. re-downloaded). Re-generate PCM.
        if (currentMp4DurationMs > 0 && kotlin.math.abs(info.mp4DurationMs - currentMp4DurationMs) > (currentMp4DurationMs * 0.05)) {
            return null
        }
        // PCM duration must be within tolerance of MP4 duration.
        val expectedDurationMs = if (currentMp4DurationMs > 0) currentMp4DurationMs else info.mp4DurationMs
        if (expectedDurationMs <= 0) return null
        val delta = kotlin.math.abs(info.pcmDurationMs - expectedDurationMs)
        if (delta > expectedDurationMs * toleranceRatio) {
            return null
        }
        return info
    }

    /**
     * Get the model directory.
     * v2.4.95: Uses same path as OfflineEngineActivity (models/audio-models).
     */
    fun getModelDir(context: Context): File {
        val modelsDir = context.getExternalFilesDir("models") ?: context.getExternalFilesDir(null)
        val modelDir = File(modelsDir, "audio-models")
        if (!modelDir.exists()) modelDir.mkdirs()
        // v2.4.95: Migrate from old path if needed
        val oldDir = File(context.getExternalFilesDir(null), "audio-models")
        if (oldDir.exists() && oldDir.listFiles()?.isNotEmpty() == true && modelDir.listFiles()?.isEmpty() == true) {
            oldDir.copyRecursively(modelDir, overwrite = true)
            Log.i(TAG, "Migrated audio models from ${oldDir.absolutePath} to ${modelDir.absolutePath}")
        }
        return modelDir
    }

    /**
     * v2.4.138: Locate the cached audio file for an episode by URL or episode ID.
     */
    private fun getCachedAudioFile(context: Context, episodeId: String, audioUrl: String?): java.io.File? {
        val episodesDir = java.io.File(context.getExternalFilesDir(null), "RadioApp/episodes")
        if (!episodesDir.exists()) return null
        val cachedFiles = episodesDir.listFiles()?.filter { it.isFile && (it.name.endsWith(".mp4") || it.name.endsWith(".m4a") || it.name.endsWith(".aac")) } ?: emptyList()
        return if (audioUrl != null) {
            val urlFileName = audioUrl.substringAfterLast("/")
            cachedFiles.find { it.name == urlFileName || it.name.startsWith(urlFileName.substringBeforeLast(".")) }
                ?: cachedFiles.find { it.name.contains(episodeId) }
                ?: cachedFiles.maxByOrNull { it.lastModified() }
        } else {
            cachedFiles.find { it.name.contains(episodeId) } ?: cachedFiles.maxByOrNull { it.lastModified() }
        }
    }

    /**
     * v2.4.138: Get the audio track duration of a cached media file in milliseconds.
     * KEY_DURATION is in microseconds.
     */
    private fun getMp4DurationMs(audioFile: java.io.File): Long {
        var durationMs = 0L
        var ex: android.media.MediaExtractor? = null
        try {
            ex = android.media.MediaExtractor()
            ex.setDataSource(audioFile.absolutePath)
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    if (fmt.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                        durationMs = fmt.getLong(android.media.MediaFormat.KEY_DURATION) / 1000
                    }
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[v2.4.138] getMp4DurationMs failed: ${e.message}")
        } finally {
            ex?.release()
        }
        return durationMs
    }

    /**
     * v2.4.138: Pre-generate PCM files (5-min and full) for an episode with strict duration validation.
     *
     * Rules:
     * - Read the source MP4 duration and the existing .info file.
     * - If .info is missing, version is old, or durations differ by more than 10%, delete PCM and regenerate.
     * - If PCM duration < MP4 duration, regenerate the full PCM (no append, to avoid seek/sync bugs).
     * - If PCM duration > MP4 duration, trim or regenerate.
     * - After successful generation, write mp4DurationMs and pcmDurationMs into .info.
     * - If info duration already matches and PCM exists, skip decoding entirely.
     *
     * @param context Application context
     * @param episodeId Episode ID
     * @param audioUrl Audio URL (for finding cached audio file)
     * @return true if PCM files were generated or already valid
      */
    fun preGeneratePcmFiles(context: Context, episodeId: String, audioUrl: String?, expectedDurationMs: Long = 0): Boolean {
        val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(context)
        val fullPcmFile = File(pcmCacheDir, "${episodeId}_full.pcm")
        val min5PcmFile = File(pcmCacheDir, "${episodeId}_5min.pcm")
        val fullInfoFile = File(pcmCacheDir, "${episodeId}_full.info")
        val min5InfoFile = File(pcmCacheDir, "${episodeId}_5min.info")
        val precacheLog = java.io.File(context.getExternalFilesDir(null), "RadioApp/logs/precache/precache.log")
        precacheLog.parentFile?.mkdirs()
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())

        // v2.4.139: Resolve MP4 duration from multiple sources, never trust a 0 value.
        val audioFile = getCachedAudioFile(context, episodeId, audioUrl)
        var mp4DurationMs = if (audioFile != null && audioFile.exists()) {
            val d = getMp4DurationMs(audioFile)
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [v2.4.139] MediaExtractor duration for $episodeId: ${d}ms (${d / 60000} min), audioFile=${audioFile.name}\n")
            d
        } else {
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [v2.4.139] no cached audio file found for $episodeId\n")
            0L
        }

        // v2.4.139: Fallback 1 — episode metadata duration (seconds -> ms).
        if (mp4DurationMs <= 0 && expectedDurationMs > 0) {
            mp4DurationMs = expectedDurationMs
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [v2.4.139] using expectedDurationMs fallback for $episodeId: ${mp4DurationMs}ms\n")
        }

        // v2.4.139: Fallback 2 — parse start/end time from the audio URL path.
        if (mp4DurationMs <= 0) {
            val urlDurationMs = parseDurationFromAudioUrl(audioUrl)
            if (urlDurationMs > 0) {
                mp4DurationMs = urlDurationMs
                precacheLog.appendText("[$ts] preGeneratePcmFiles: [v2.4.139] using URL time-range fallback for $episodeId: ${mp4DurationMs}ms\n")
            }
        }

        // v2.4.139: Validate using .info file. If valid and durations match, skip all decoding.
        // If we still don't know the MP4 duration, never consider the cached info valid — regenerate.
        val validInfo = if (mp4DurationMs > 0) validatePcmWithInfo(fullPcmFile, fullInfoFile, mp4DurationMs) else null
        if (validInfo != null && min5PcmFile.exists() && min5PcmFile.length() > 16000) {
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [v2.4.139] PCM valid per .info for $episodeId (mp4=${validInfo.mp4DurationMs}ms, pcm=${validInfo.pcmDurationMs}ms). Skipping decode.\n")
            return true
        }

        // Not valid — delete old PCM and .info files to force regeneration.
        if (fullPcmFile.exists()) fullPcmFile.delete()
        if (min5PcmFile.exists()) min5PcmFile.delete()
        if (fullInfoFile.exists()) fullInfoFile.delete()
        if (min5InfoFile.exists()) min5InfoFile.delete()
        precacheLog.appendText("[$ts] preGeneratePcmFiles: [v2.4.139] PCM invalid, .info mismatch, or unknown MP4 duration for $episodeId (mp4DurationMs=$mp4DurationMs). Deleting and regenerating.\n")

        // Decode full PCM from scratch.
        precacheLog.appendText("[$ts] preGeneratePcmFiles: [v2.4.139] decoding full PCM for $episodeId (audioUrl=$audioUrl, mp4DurationMs=$mp4DurationMs)\n")
        val decoded = decodeAudioToPcm(context, episodeId, pcmCacheDir, audioUrl)
        if (decoded == null || !decoded.exists() || decoded.length() <= 16000) {
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [v2.4.139] FAILED to decode full PCM for $episodeId\n")
            return false
        }

        // v2.4.139: Clamp PCM to expected length and compute duration.
        val clampedFile = clampPcmToExpectedLength(decoded, mp4DurationMs, episodeId)
        val pcmDurationMs = clampedFile.length() / (16000 * 2) * 1000
        precacheLog.appendText("[$ts] preGeneratePcmFiles: [v2.4.139] full PCM generated: ${clampedFile.name} (${clampedFile.length()} bytes, ${pcmDurationMs}ms=${pcmDurationMs / 60000} min, expected ${mp4DurationMs}ms=${mp4DurationMs / 60000} min)\n")

        // v2.4.139: If PCM is still significantly shorter than MP4, we cannot trust it. Fail loudly.
        if (mp4DurationMs > 0 && pcmDurationMs < mp4DurationMs * 0.85) {
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [v2.4.139] ERROR: PCM duration (${pcmDurationMs}ms) still < 85% of MP4 duration (${mp4DurationMs}ms). Keeping file but marking unreliable.\n")
        }

        // v2.4.139: Only write .info when we have a non-zero MP4 duration. A 0 duration makes
        // the info file useless for future validation and causes the bug "Info文件中mp4时长为0".
        if (mp4DurationMs > 0) {
            writePcmInfo(fullInfoFile, mp4DurationMs, pcmDurationMs, 16000, 1)

            // Generate 5-min PCM from full PCM.
            try {
                val fiveMinBytes = 5 * 60 * 16000 * 2
                val fullBytes = clampedFile.length().toInt()
                val copyBytes = minOf(fiveMinBytes, fullBytes)
                java.io.FileInputStream(clampedFile).use { fis ->
                    java.io.FileOutputStream(min5PcmFile).use { fos ->
                        val buffer = ByteArray(8192)
                        var remaining = copyBytes
                        while (remaining > 0) {
                            val toRead = minOf(remaining, buffer.size)
                            val read = fis.read(buffer, 0, toRead)
                            if (read < 0) break
                            fos.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                }
                writePcmInfo(min5InfoFile, mp4DurationMs, min5PcmFile.length() / (16000 * 2) * 1000, 16000, 1)
                precacheLog.appendText("[$ts] preGeneratePcmFiles: [v2.4.139] 5-min PCM generated: ${min5PcmFile.name} (${min5PcmFile.length()} bytes)\n")
            } catch (e: Exception) {
                precacheLog.appendText("[$ts] preGeneratePcmFiles: [v2.4.139] failed to create 5-min PCM: ${e.message}\n")
            }
        } else {
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [v2.4.139] WARNING: mp4DurationMs is 0, skipping .info write for $episodeId to avoid invalid info files.\n")
        }

        return fullPcmFile.exists() && fullPcmFile.length() > 16000
    }

    /**
     * v2.4.139: Try to derive episode duration from the audio URL when MediaExtractor returns 0.
     * Many URLs contain the start and end time in the path, e.g. .../YYYYMMDD_HHMM_HHMM_....mp3
     * or .../HHMM_HHMM_....m4a. Returns duration in milliseconds, or 0 if parsing fails.
     */
    private fun parseDurationFromAudioUrl(audioUrl: String?): Long {
        if (audioUrl.isNullOrBlank()) return 0L
        val path = audioUrl.substringBeforeLast("?").substringAfterLast("/")
        // Look for two consecutive 4-digit times in the path: HHMM_HHMM
        val regex = Regex("(\\d{2})(\\d{2})_(\\d{2})(\\d{2})")
        val match = regex.find(path) ?: return 0L
        val (_, startHour, startMin, endHour, endMin) = match.groupValues
        return try {
            var start = startHour.toInt() * 3600000L + startMin.toInt() * 60000L
            var end = endHour.toInt() * 3600000L + endMin.toInt() * 60000L
            // Handle programs that cross midnight (e.g. 23:00-01:00).
            if (end < start) end += 24 * 3600000L
            val duration = end - start
            if (duration > 0) duration else 0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * v2.4.96: Analyze an episode by finding its PCM file and running dual-model segmentation.
     * v2.4.99: Added audioUrl parameter for finding cached audio file.
     *
     * PCM file search order:
     * 1. Pre-decoded full PCM: /sdcard/RadioApp/pcm_cache/{episodeId}_full.pcm
     * 2. Pre-decoded 5-min PCM: /sdcard/RadioApp/pcm_cache/{episodeId}_5min.pcm
     * 3. Whisper chunk PCM: /sdcard/RadioApp/pcm_cache/{episodeId}_chunk_*.pcm
     * 4. Decode from cached audio file (mp4/m4a) to PCM on-the-fly
     *
     * @param context Application context
     * @param episodeId Episode ID
     * @param durationMs Duration in milliseconds
     * @param audioUrl Audio URL (for finding cached audio file)
     * @return List of VoiceSegments
     */
    fun analyzeEpisode(context: Context, episodeId: String, durationMs: Long, audioUrl: String? = null): List<VoiceSegment> {
        // v2.4.115: Initialize file-based logger for VAD diagnostics
        setLogContext(context)

        // v2.4.95: Load native libraries before any ONNX/TFLite usage
        if (!NativeLibLoader.ensureLoaded(context)) {
            Log.e(TAG, "Native libraries not loaded. Please download audio segmentation runtime.")
            throw RuntimeException("音频分段运行库未安装，请在离线引擎管理中下载")
        }

        val modelDir = getModelDir(context)
        if (!isModelInstalled(modelDir)) {
            Log.e(TAG, "Models not installed. YAMNet=${isYamnetInstalled(modelDir)}, VAD=${isSileroVadInstalled(modelDir)}")
            throw RuntimeException("音频分段模型未安装，请在离线引擎管理中下载 Silero VAD 和 YAMNet")
        }

        // v2.4.96: Find PCM file - prioritize pre-decoded files from preprocessing
        val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(context)
        Log.i(TAG, "analyzeEpisode: searching for PCM in ${pcmCacheDir.absolutePath}")

        // v2.4.138: Determine the source MP4 duration for validation.
        // durationMs passed from caller is usually the MP4 duration. Fall back to reading the file.
        val audioFile = getCachedAudioFile(context, episodeId, audioUrl)
        val mp4DurationMs = when {
            durationMs > 0 -> durationMs
            audioFile != null && audioFile.exists() -> getMp4DurationMs(audioFile)
            else -> 0L
        }
        if (mp4DurationMs <= 0) {
            vadLog("[v2.4.138] analyzeEpisode: WARNING could not determine MP4 duration for $episodeId")
        }

        // v2.4.138: Validate full PCM using .info file duration metadata.
        val fullPcmFile = File(pcmCacheDir, "${episodeId}_full.pcm")
        val fullInfoFile = File(pcmCacheDir, "${episodeId}_full.info")
        var pcmFile: File? = null

        if (fullPcmFile.exists() && fullPcmFile.length() > 16000) {
            val validInfo = validatePcmWithInfo(fullPcmFile, fullInfoFile, mp4DurationMs)
            if (validInfo != null) {
                vadLog("[v2.4.138] analyzeEpisode: full PCM valid per .info for $episodeId (mp4=${validInfo.mp4DurationMs}ms, pcm=${validInfo.pcmDurationMs}ms)")
                pcmFile = fullPcmFile
            } else {
                vadLog("[v2.4.138] analyzeEpisode: full PCM .info mismatch for $episodeId. Deleting and regenerating.")
                fullPcmFile.delete()
                File(pcmCacheDir, "${episodeId}_5min.pcm").takeIf { it.exists() }?.delete()
                fullInfoFile.delete()
                File(pcmCacheDir, "${episodeId}_5min.info").takeIf { it.exists() }?.delete()
            }
        }

        // v2.4.138: If no valid full PCM, try 5-min PCM (also validated by .info).
        if (pcmFile == null) {
            val min5PcmFile = File(pcmCacheDir, "${episodeId}_5min.pcm")
            val min5InfoFile = File(pcmCacheDir, "${episodeId}_5min.info")
            if (min5PcmFile.exists() && min5PcmFile.length() > 16000) {
                val validInfo = validatePcmWithInfo(min5PcmFile, min5InfoFile, mp4DurationMs)
                if (validInfo != null) {
                    vadLog("[v2.4.138] analyzeEpisode: 5-min PCM valid per .info for $episodeId")
                    pcmFile = min5PcmFile
                } else {
                    vadLog("[v2.4.138] analyzeEpisode: 5-min PCM .info mismatch for $episodeId. Deleting.")
                    min5PcmFile.delete()
                    min5InfoFile.delete()
                }
            }
        }

        // v2.4.138: If still no valid PCM, decode from scratch.
        if (pcmFile == null) {
            pcmFile = decodeAudioToPcm(context, episodeId, pcmCacheDir, audioUrl)
            if (pcmFile == null) {
                Log.e(TAG, "analyzeEpisode: no PCM file found for $episodeId (audioUrl=$audioUrl)")
                throw RuntimeException("无法获取音频数据: 未找到PCM缓存文件，本地无缓存音频，URL解码失败(可能需要联网)")
            }
            // Guard against excessive length, then record duration in .info.
            pcmFile = clampPcmToExpectedLength(pcmFile, mp4DurationMs, episodeId)
            val pcmDurationMs = pcmFile.length() / (16000 * 2) * 1000
            writePcmInfo(fullInfoFile, mp4DurationMs, pcmDurationMs, 16000, 1)
            vadLog("[v2.4.138] analyzeEpisode: decoded fresh PCM for $episodeId (${pcmFile.length()} bytes, pcmDuration=${pcmDurationMs}ms)")
        }

        return analyzePcmFile(context, pcmFile, durationMs)
    }

    /**
     * v2.4.137: Some decoders (or damaged source files) can produce PCM that is significantly longer
     * than the episode duration. Trim the file to the expected byte length plus a small margin so VAD
     * does not process trailing silence/zeros or duplicated audio.
     */
    private fun clampPcmToExpectedLength(pcmFile: File, durationMs: Long, episodeId: String): File {
        if (durationMs <= 0) return pcmFile
        val expectedBytes = (durationMs * 16000L * 2L / 1000L)
        val maxAllowedBytes = (expectedBytes * 1.15).toLong() // 15% margin for container/duration inaccuracy
        if (pcmFile.length() > maxAllowedBytes) {
            val trimBytes = maxAllowedBytes
            vadLog("[v2.4.137] clampPcmToExpectedLength: trimming ${pcmFile.length()} bytes -> $trimBytes bytes (duration=${durationMs}ms, expected=$expectedBytes)")
            Log.w(TAG, "[v2.4.137] PCM too long for $episodeId: ${pcmFile.length()} > $maxAllowedBytes, trimming to $trimBytes")
            try {
                java.io.RandomAccessFile(pcmFile, "rw").use { raf ->
                    raf.setLength(trimBytes)
                }
            } catch (e: Exception) {
                vadLog("[v2.4.137] clampPcmToExpectedLength: failed to trim: ${e.message}")
                Log.e(TAG, "[v2.4.137] failed to trim PCM", e)
            }
        }
        return pcmFile
    }

    /**
     * v2.4.96: Decode cached audio file to PCM using MediaExtractor + MediaCodec.
     * v2.4.99: Find audio file by URL-based filename (not episode ID).
     * This is a fallback when no pre-decoded PCM file exists.
     */
    private fun decodeAudioToPcm(context: Context, episodeId: String, outputDir: File, audioUrl: String? = null, startOffsetBytes: Long = 0): File? {
        try {
            val episodesDir = com.radio.app.RadioApplication.getEpisodesCacheDir(context)
            val cachedFiles = episodesDir.listFiles()?.filter {
                it.isFile && it.length() > 1024 && (it.name.endsWith(".mp4") || it.name.endsWith(".m4a") || it.name.endsWith(".aac"))
            } ?: emptyList()

            // v2.4.99: Find audio file by URL-based filename first
            var audioFile: File? = null
            if (audioUrl != null) {
                val urlFileName = try {
                    val path = java.net.URL(audioUrl).path
                    path.substringAfterLast("/")
                } catch (e: Exception) {
                    audioUrl.substringAfterLast("/")
                }
                if (urlFileName.isNotBlank()) {
                    audioFile = cachedFiles.find { it.name == urlFileName || it.name.startsWith(urlFileName.substringBeforeLast(".")) }
                    Log.i(TAG, "decodeAudioToPcm: searching by URL filename '$urlFileName', found=${audioFile?.name}")
                }
            }

            // Fallback: search by episode ID prefix
            if (audioFile == null) {
                audioFile = cachedFiles.find {
                    it.name.startsWith(episodeId) || it.name.startsWith(episodeId.substringBefore("-"))
                }
                Log.i(TAG, "decodeAudioToPcm: searching by episodeId '$episodeId', found=${audioFile?.name}")
            }

            // Last resort: use the most recently modified audio file
            if (audioFile == null && cachedFiles.isNotEmpty()) {
                audioFile = cachedFiles.maxByOrNull { it.lastModified() }
                Log.i(TAG, "decodeAudioToPcm: using most recent audio file: ${audioFile?.name}")
            }

            if (audioFile == null) {
                // v2.4.101: No cached file — try streaming from URL directly via MediaExtractor
                if (audioUrl != null && audioUrl.startsWith("http")) {
                    Log.i(TAG, "decodeAudioToPcm: no cached file, trying URL: $audioUrl")
                    return decodeUrlToPcm(audioUrl, File(outputDir, "${episodeId}_full.pcm"))
                }
                Log.e(TAG, "decodeAudioToPcm: no cached audio file found for $episodeId, files in episodes dir: ${cachedFiles.map { it.name }}")
                return null
            }
            Log.i(TAG, "decodeAudioToPcm: decoding ${audioFile.name} to PCM")

            val outputFile = File(outputDir, "${episodeId}_full.pcm")
            // Use Android MediaExtractor + MediaCodec to decode
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(audioFile.absolutePath)
            val trackCount = extractor.trackCount
            var audioTrackIndex = -1
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    break
                }
            }
            if (audioTrackIndex < 0) {
                Log.e(TAG, "decodeAudioToPcm: no audio track found")
                extractor.release()
                return null
            }
            extractor.selectTrack(audioTrackIndex)
            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            // v2.4.132: Use var for sample rate/channels — they may change after INFO_OUTPUT_FORMAT_CHANGED
            // (e.g. HE-AAC v2: container says 22050Hz/1ch, but codec outputs 44100Hz/2ch after SBR+PS)
            // This is the SAME approach used by SubtitleGeneratorService.decodeFullAudioToPcm (proven correct).
            var sampleRate = inputFormat.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = inputFormat.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            val mime = inputFormat.getString(android.media.MediaFormat.KEY_MIME) ?: ""
            Log.i(TAG, "decodeAudioToPcm: container format: ${sampleRate}Hz ${channelCount}ch mime=$mime")

            // v2.4.130: If resuming from truncated PCM, seek the extractor to the
            // corresponding position in the source audio. This skips already-decoded content.
            if (startOffsetBytes > 0) {
                // Calculate how many seconds of 16kHz mono 16-bit PCM we already have
                val decodedSeconds = startOffsetBytes.toDouble() / (16000 * 2)
                // Convert to microseconds for MediaExtractor.seekTo
                val seekToUs = (decodedSeconds * 1_000_000).toLong()
                vadLog("[v2.4.130] decodeAudioToPcm: APPEND MODE — seeking to ${seekToUs}us (${decodedSeconds}s), existing PCM=$startOffsetBytes bytes")
                extractor.seekTo(seekToUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            // Create decoder
            val decoder = android.media.MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val bufferInfo = android.media.MediaCodec.BufferInfo()
            val outputDirFile = outputFile
            // v2.4.130: Support append mode for continuing truncated PCM.
            // If startOffsetBytes > 0, we're resuming from a truncated PCM file.
            // Open in append mode and skip already-decoded bytes.
            val appendMode = startOffsetBytes > 0
            val fos = if (appendMode) {
                java.io.FileOutputStream(outputDirFile, true)  // append
            } else {
                java.io.FileOutputStream(outputDirFile)  // overwrite
            }
            var totalPcmBytes = if (appendMode) startOffsetBytes else 0
            // v2.4.132: Fixed max PCM size — 300MB = ~2.6 hours at 16kHz mono 16-bit.
            // Old value was 500MB which was way too high (indicated wrong sample rate).
            val maxPcmBytes = 300 * 1024 * 1024  // 300MB max

            // v2.4.132: Resampling state — continuous phase tracking (same as SubtitleGeneratorService)
            var resamplePhase = 0.0
            var lastSample: Short = 0
            // needResample is re-evaluated after FORMAT_CHANGED
            var needResample = sampleRate != 16000 || channelCount != 1
            // fos already created above (append or overwrite mode)

            try {
                var inputEos = false
                while (true) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0 && !inputEos) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }

                    val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                    // v2.4.132: Handle INFO_OUTPUT_FORMAT_CHANGED — codec actual output may differ from container
                    // This is the SAME approach as SubtitleGeneratorService.decodeFullAudioToPcm (proven correct).
                    // For HE-AAC v2: container says 22050Hz/1ch, but codec outputs 44100Hz/2ch after SBR+PS.
                    if (outputBufferIndex == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = decoder.outputFormat
                        try {
                            sampleRate = newFormat.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                            channelCount = newFormat.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                            needResample = sampleRate != 16000 || channelCount != 1
                            Log.i(TAG, "decodeAudioToPcm: FORMAT_CHANGED: actual sampleRate=$sampleRate, channels=$channelCount, needResample=$needResample")
                            vadLog("[v2.4.132] decodeAudioToPcm: FORMAT_CHANGED: ${sampleRate}Hz ${channelCount}ch (was container format)")
                        } catch (e: Exception) {
                            Log.w(TAG, "decodeAudioToPcm: FORMAT_CHANGED but failed to read format: ${e.message}")
                        }
                    }
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)

                            if (needResample) {
                                // v2.4.132: Use continuous-phase linear interpolation resampling
                                // (same algorithm as SubtitleGeneratorService.resampleChunkContinuousSG).
                                // This properly handles non-integer ratios (e.g., 44100/16000=2.75625).
                                val pcmShort = java.nio.ByteBuffer.wrap(chunk).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                val inFrames = pcmShort.remaining() / channelCount

                                // Mix to mono first
                                val monoInput = ShortArray(inFrames)
                                for (i in 0 until inFrames) {
                                    var sum = 0
                                    for (c in 0 until channelCount) {
                                        sum += pcmShort.get(i * channelCount + c).toInt()
                                    }
                                    monoInput[i] = (sum / channelCount).toShort()
                                }

                                // Continuous-phase linear interpolation
                                val ratio = sampleRate.toDouble() / 16000.0
                                val extendedInput = ShortArray(monoInput.size + 1)
                                extendedInput[0] = lastSample
                                System.arraycopy(monoInput, 0, extendedInput, 1, monoInput.size)
                                val availableInputRange = extendedInput.size - 1
                                var currentPhase = resamplePhase
                                val outputSamples = ArrayList<Short>(512)

                                while (currentPhase < availableInputRange) {
                                    val srcIdx = currentPhase.toInt()
                                    val frac = currentPhase - srcIdx
                                    val s0 = extendedInput[srcIdx].toInt()
                                    val s1 = extendedInput[srcIdx + 1].toInt()
                                    val interpolated = s0 + ((s1 - s0) * frac).toInt()
                                    outputSamples.add(interpolated.toShort())
                                    currentPhase += ratio
                                }

                                // Update state for next chunk
                                resamplePhase = currentPhase - availableInputRange
                                lastSample = if (monoInput.isNotEmpty()) monoInput[monoInput.size - 1] else lastSample

                                // Write output
                                val outBuf = java.nio.ByteBuffer.allocate(outputSamples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                for (s in outputSamples) outBuf.putShort(s)
                                val outBytes = ByteArray(outBuf.position())
                                outBuf.rewind()
                                outBuf.get(outBytes)
                                fos.write(outBytes)
                                totalPcmBytes += outBytes.size
                            } else {
                                fos.write(chunk)
                                totalPcmBytes += chunk.size
                            }

                            if (totalPcmBytes >= maxPcmBytes) {
                                Log.i(TAG, "decodeAudioToPcm: reached max size limit ($maxPcmBytes bytes)")
                                break
                            }
                        }
                        decoder.releaseOutputBuffer(outputBufferIndex, false)
                        if (bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    }
                }
            } finally {
                fos.close()
                decoder.stop()
                decoder.release()
                extractor.release()
            }

            Log.i(TAG, "decodeAudioToPcm: decoded $totalPcmBytes bytes to ${outputFile.name} (final rate: ${sampleRate}Hz ${channelCount}ch -> 16kHz mono)")
            // v2.4.138: .info file with duration metadata is now written by the caller
            // (preGeneratePcmFiles / analyzeEpisode) so that mp4DurationMs and pcmDurationMs
            // can be validated together.
            return if (totalPcmBytes > 16000) outputFile else null
        } catch (e: Exception) {
            Log.e(TAG, "decodeAudioToPcm failed: ${e.message}")
            return null
        }
    }

    /**
     * v2.4.101: Download and decode audio from URL directly using MediaExtractor.
     * MediaExtractor supports HTTP URLs natively. Decodes to 16kHz mono PCM.
     */
    private fun decodeUrlToPcm(audioUrl: String, outputFile: File): File? {
        try {
            Log.i(TAG, "decodeUrlToPcm: downloading and decoding from $audioUrl")
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(audioUrl)
            val trackCount = extractor.trackCount
            var audioTrackIndex = -1
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    break
                }
            }
            if (audioTrackIndex < 0) {
                Log.e(TAG, "decodeUrlToPcm: no audio track found")
                extractor.release()
                return null
            }
            extractor.selectTrack(audioTrackIndex)
            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            val sampleRate = inputFormat.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = inputFormat.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            val mime = inputFormat.getString(android.media.MediaFormat.KEY_MIME) ?: ""

            val decoder = android.media.MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val bufferInfo = android.media.MediaCodec.BufferInfo()
            var totalPcmBytes = 0
            // v2.4.128: FIX — was 50MB, which truncated PCM to ~26 minutes.
            // A 2-hour audio at 16kHz mono 16-bit produces ~230MB of PCM.
            val maxPcmBytes = 500 * 1024 * 1024  // 500MB max (~2.7 hours at 16kHz mono)
            val needResample = sampleRate != 16000 || channelCount != 1
            val fos = java.io.FileOutputStream(outputFile)

            try {
                var inputEos = false
                while (true) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0 && !inputEos) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }

                    val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)

                            if (needResample) {
                                // v2.4.130: FIXED resampling — linear interpolation instead of integer division
                                val pcmShort = java.nio.ByteBuffer.wrap(chunk).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                val inFrames = pcmShort.remaining() / channelCount
                                val outFrames = (inFrames.toLong() * 16000 / sampleRate).toInt()
                                val outBuf = java.nio.ByteBuffer.allocate(outFrames * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                val ratioFloat = sampleRate.toFloat() / 16000f
                                for (outIdx in 0 until outFrames) {
                                    val srcPos = outIdx * ratioFloat
                                    val srcIdx = srcPos.toInt()
                                    val frac = srcPos - srcIdx
                                    var sample0 = 0
                                    var sample1 = 0
                                    for (c in 0 until channelCount) {
                                        sample0 += if (srcIdx * channelCount + c < pcmShort.remaining()) pcmShort.get(srcIdx * channelCount + c) else 0
                                        sample1 += if ((srcIdx + 1) * channelCount + c < pcmShort.remaining()) pcmShort.get((srcIdx + 1) * channelCount + c) else 0
                                    }
                                    sample0 /= channelCount
                                    sample1 /= channelCount
                                    val interpolated = sample0 + ((sample1 - sample0) * frac).toInt()
                                    outBuf.putShort(interpolated.toShort())
                                }
                                val outBytes = ByteArray(outBuf.position())
                                outBuf.rewind()
                                outBuf.get(outBytes)
                                fos.write(outBytes)
                                totalPcmBytes += outBytes.size
                            } else {
                                fos.write(chunk)
                                totalPcmBytes += chunk.size
                            }

                            if (totalPcmBytes >= maxPcmBytes) {
                                Log.i(TAG, "decodeUrlToPcm: reached max size limit ($maxPcmBytes bytes)")
                                break
                            }
                        }
                        decoder.releaseOutputBuffer(outputBufferIndex, false)
                        if (bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    }
                }
            } finally {
                fos.close()
                decoder.stop()
                decoder.release()
                extractor.release()
            }

            Log.i(TAG, "decodeUrlToPcm: decoded $totalPcmBytes bytes to ${outputFile.name}")
            // v2.4.138: .info file with duration metadata is now written by the caller.
            return if (totalPcmBytes > 16000) outputFile else null
        } catch (e: Exception) {
            Log.e(TAG, "decodeUrlToPcm failed: ${e.message}")
            return null
        }
    }

    /**
     * Analyze PCM audio file and generate segments using dual models.
     *
     * @param context Application context
     * @param pcmFile 16kHz mono 16-bit PCM file
     * @param durationMs Total duration in milliseconds
     * @return List of VoiceSegments
     */
    fun analyzePcmFile(
        context: Context,
        pcmFile: File,
        durationMs: Long
    ): List<VoiceSegment> {
        if (!pcmFile.exists() || pcmFile.length() < 16000) {
            Log.w(TAG, "PCM file too small or missing: ${pcmFile.absolutePath}")
            throw RuntimeException("PCM文件太小或不存在: ${pcmFile.name} (${pcmFile.length()} bytes)")
        }

        // v2.4.134: 重置 object 级状态，防止跨集泄漏。
        // 故障原因：vadMalfunction / yamnetCallCount / vadRunCount 是 object 单例字段，
        // 一旦某一集触发 VAD 故障并把 vadMalfunction 置为 true，后续所有集都会进入
        // YAMNet-only 模式（见 line ~974 classifyFrameYamnetOnly），即使该集 VAD
        // 完全正常也无法使用双模型融合，导致分段异常。
        // yamnetCallCount / vadRunCount 用于日志采样窗口，跨集累积会让首帧日志失效。
        // 注意：yamnetInputShape 不在此处重置——它由 loadYamnetModel() 在每次调用
        // 时根据当前模型重新设置，没有跨集污染问题。
        synchronized(this) {
            vadMalfunction = false
            yamnetCallCount = 0
            vadRunCount = 0
        }

        // v2.4.95: Load native libraries before any ONNX/TFLite usage
        if (!NativeLibLoader.ensureLoaded(context)) {
            Log.e(TAG, "Native libraries not loaded.")
            throw RuntimeException("音频分段运行库未加载，请重新下载运行库")
        }

        val modelDir = getModelDir(context)
        if (!isYamnetInstalled(modelDir) || !isSileroVadInstalled(modelDir)) {
            Log.w(TAG, "Models not installed. YAMNet=${isYamnetInstalled(modelDir)}, VAD=${isSileroVadInstalled(modelDir)}")
            throw RuntimeException("模型未安装: YAMNet=${isYamnetInstalled(modelDir)}, VAD=${isSileroVadInstalled(modelDir)}")
        }

        // Load YAMNet (TFLite) - throws exception on failure
        val yamnetInterpreter = loadYamnetModel(File(modelDir, "yamnet.tflite"))

        // Load Silero VAD (ONNX Runtime via reflection) - throws exception on failure
        val vadModel = loadSileroVad(File(modelDir, "silero_vad.onnx"))

        try {
            val samples = readPcmAsFloats(pcmFile)
            if (samples.size < YAMNET_WINDOW_SAMPLES) {
                Log.w(TAG, "PCM too short: ${samples.size} samples")
                throw RuntimeException("PCM数据太短: ${samples.size} 样本 (需要至少 $YAMNET_WINDOW_SAMPLES)")
            }

            // Compute max energy for normalization
            var maxEnergy = 0f
            var pos = 0
            while (pos + FRAME_STEP_SAMPLES <= samples.size) {
                val winSize = minOf(YAMNET_WINDOW_SAMPLES, samples.size - pos)
                val energy = computeRmsEnergy(samples, pos, winSize)
                if (energy > maxEnergy) maxEnergy = energy
                pos += FRAME_STEP_SAMPLES
            }
            if (maxEnergy < 1e-6f) maxEnergy = 1e-6f
            Log.i(TAG, "PCM: ${samples.size} samples, maxEnergy=$maxEnergy")

            val frameResults = mutableListOf<FrameResult>()
            pos = 0

            // VAD state buffer - size depends on model version
            // v1/v2: separate h [2,1,32] and c [2,1,32] → 64 floats each
            // v3/v4: combined state [2,1,N] → N*2 floats
            var vadState = FloatBuffer.wrap(FloatArray(vadModel.stateSize))
            // v2.4.142: Context buffer for Silero VAD (last 64 samples of previous chunk).
            var vadContext = FloatArray(VAD_CONTEXT_SIZE) { 0f }

            // v2.4.141: Count consecutive low-confidence VAD windows before declaring malfunction.
            var vadLowConfidenceFrames = 0

            while (pos + YAMNET_WINDOW_SAMPLES <= samples.size) {
                val window = samples.copyOfRange(pos, pos + YAMNET_WINDOW_SAMPLES)
                val timestampMs = (pos.toLong() * 1000 / YAMNET_SAMPLE_RATE)

                // YAMNet classification
                val (yamnetSpeech, yamnetMusic, yamnetSilence) = classifyWithYamnet(yamnetInterpreter, window)

                // v2.4.126: Log YAMNet results for first 5 frames to diagnose "全部是水货"
                if (frameResults.size < 5) {
                    Log.i(TAG, "YAMNet frame #${frameResults.size}: speech=$yamnetSpeech, music=$yamnetMusic, silence=$yamnetSilence")
                    vadLog("YAMNet frame #${frameResults.size}: speech=$yamnetSpeech, music=$yamnetMusic, silence=$yamnetSilence, rmsEnergy=${computeRmsEnergy(window, 0, window.size)}")
                }

                // Silero VAD
                var vadProb = 0f
                var vadChunks = 0
                var vadPos = 0
                while (vadPos + VAD_FRAME_SIZE <= window.size) {
                    val chunk = window.copyOfRange(vadPos, vadPos + VAD_FRAME_SIZE)
                    val (prob, newState, newContext) = runSileroVad(vadModel, chunk, vadContext, vadState)
                    vadState = newState
                    vadContext = newContext
                    if (frameResults.size < 3 && vadChunks < 10) {
                        vadLog("VAD chunk #$vadChunks: prob=$prob, energy=${computeRmsEnergy(chunk, 0, chunk.size)}")
                    }
                    vadProb += prob
                    vadChunks++
                    vadPos += VAD_FRAME_SIZE
                }
                if (vadChunks > 0) vadProb /= vadChunks
                // v2.4.109: Log averaged VAD prob for first 5 frames
                if (frameResults.size < 5) {
                    Log.i(TAG, "VAD frame #${frameResults.size}: avgProb=$vadProb, vadChunks=$vadChunks, energy=${computeRmsEnergy(window, 0, window.size)}")
                }

                // v2.4.126: Detect VAD malfunction (prob always < 0.01)
                // v2.4.128: When VAD OR YAMNet malfunction is detected, preserve error info
                // and STOP segmentation. Do NOT fall back to other classification methods
                // (energy+ZCR, YAMNet-only, etc.). Per user request:
                // "音频分段出错时，保留错误信息，不使用其他分类方法跳转"
                // v2.4.128: Moved rmsEnergy/zcr computation here for diagnostics
                val rmsEnergy = computeRmsEnergy(window, 0, window.size)
                val zcr = computeZeroCrossingRate(window)
                // v2.4.141: Early VAD malfunction detection. Require several consecutive low-prob
                // frames before switching to YAMNet-only mode. A single window can be low simply
                // because the audio is quiet/intro; only a persistent stream of near-zero probabilities
                // while YAMNet still detects speech indicates that VAD is not useful for this episode.
                if (!vadMalfunction && vadProb < 0.01f && yamnetSpeech > 0.3f) {
                    vadLowConfidenceFrames++
                    if (vadLowConfidenceFrames >= 3) {
                        vadLog("[v2.4.141] VAD malfunction detected after $vadLowConfidenceFrames low-prob frames (vadProb=$vadProb < 0.01, yamnetSpeech=$yamnetSpeech > 0.3). Switching to YAMNet-only mode.")
                        Log.w(TAG, "[v2.4.141] VAD malfunction — falling back to YAMNet-only mode")
                        vadMalfunction = true  // Flag to use YAMNet-only classification
                    } else {
                        vadLog("[v2.4.141] VAD low confidence frame $vadLowConfidenceFrames/3 (vadProb=$vadProb, yamnetSpeech=$yamnetSpeech), not switching yet")
                    }
                } else if (!vadMalfunction) {
                    vadLowConfidenceFrames = 0
                }

                // v2.4.137: Wait until 30 frames (~30s) before declaring VAD malfunction. Some episodes
                // start with a long intro/silence, and aborting after only 5 frames falsely rejects them.
                if (frameResults.size == 29 && vadProb < 0.01f && !vadMalfunction) {
                    // v2.4.133: Check if YAMNet is working. If YAMNet speech > 0.3 for any frame,
                    // fall back to YAMNet-only mode instead of aborting.
                    // v2.4.135: Also consider the current frame's YAMNet speech.
                    val yamnetWorking = frameResults.any { it.yamnetSpeech > 0.3f } || yamnetSpeech > 0.3f
                    if (yamnetWorking) {
                        vadLog("[v2.4.137] VAD malfunction at frame 30 but YAMNet is working. Switching to YAMNet-only mode.")
                        Log.w(TAG, "[v2.4.137] VAD malfunction at frame 30 — falling back to YAMNet-only mode")
                        vadMalfunction = true  // Flag to use YAMNet-only classification
                    } else {
                        val diag = buildString {
                            append("VAD模型故障: 前30帧prob值全部<0.01 (avg=$vadProb)。\n")
                            append("VAD模型未检测到语音，YAMNet也未检测到语音(speech<0.3)。分段已中止。\n")
                            append("PCM: ${samples.size} samples, maxEnergy=$maxEnergy\n")
                            var sMin = Float.MAX_VALUE
                            var sMax = -Float.MAX_VALUE
                            var sSum = 0.0
                            for (s in samples) {
                                if (s < sMin) sMin = s
                                if (s > sMax) sMax = s
                                sSum += s
                            }
                            val sMean = (sSum / samples.size).toFloat()
                            append("PCM sample range: [$sMin, $sMax], mean=$sMean\n")
                            append("First 10 samples: ${samples.take(10).joinToString(", ")}\n")
                            append("PCM file: ${pcmFile.name} (${pcmFile.length()} bytes)\n")
                            append("VAD model: stateSize=${vadModel.stateSize}\n")
                            append("Window energy: rmsEnergy=$rmsEnergy, zcr=$zcr\n")
                        }
                        vadLog("[v2.4.128] ERROR: VAD malfunction.\n$diag")
                        Log.e(TAG, "[v2.4.128] VAD malfunction:\n$diag")
                        throw RuntimeException(diag)
                    }  // end of else block
                }

                // v2.4.128/v2.4.140: Detect YAMNet malfunction (all values ~0.5 = sigmoid(0)).
                // Originally this aborted segmentation to catch wrong input-shape bugs. Now that
                // the input shape is correct, a single transitional frame near 0.5 is not a model
                // failure. We only abort if YAMNet consistently outputs ~0.5 for many frames and
                // the audio clearly has energy (which would mean the model is truly broken).
                // Otherwise we let classifyFrameYamnetOnly fall back to energy-based classification.
                if (frameResults.size == 4) {
                    val yamnetAllHalf = kotlin.math.abs(yamnetSpeech - 0.5f) < 0.05f &&
                                        kotlin.math.abs(yamnetMusic - 0.5f) < 0.05f &&
                                        kotlin.math.abs(yamnetSilence - 0.5f) < 0.05f
                    if (yamnetAllHalf) {
                        // Check how many of the first 5 frames are all ~0.5
                        val halfFrames = frameResults.take(4).count { fr ->
                            kotlin.math.abs(fr.yamnetSpeech - 0.5f) < 0.05f &&
                            kotlin.math.abs(fr.yamnetMusic - 0.5f) < 0.05f &&
                            kotlin.math.abs(fr.yamnetSilence - 0.5f) < 0.05f
                        } + 1
                        vadLog("[v2.4.140] YAMNet all-half at frame 4: halfFrames=$halfFrames/5, rmsEnergy=$rmsEnergy, maxEnergy=$maxEnergy")
                        // Only abort if at least 4 of the first 5 frames are all ~0.5 AND there is
                        // meaningful audio energy. If VAD already malfunctioned and we switched to
                        // YAMNet-only, YAMNet worked for the earlier frames, so a single half frame
                        // is just an uncertain classification — keep going.
                        if (halfFrames >= 4 && rmsEnergy > maxEnergy * 0.01f && !vadMalfunction) {
                            val diag = buildString {
                                append("YAMNet模型故障: speech=$yamnetSpeech, music=$yamnetMusic, silence=$yamnetSilence\n")
                                append("前5帧中$halfFrames 帧全部≈0.5（sigmoid(0)，模型未处理输入）。分段已中止，不使用其他分类方法。\n")
                                append("PCM: ${samples.size} samples, maxEnergy=$maxEnergy\n")
                                var sMin = Float.MAX_VALUE
                                var sMax = -Float.MAX_VALUE
                                var sSum = 0.0
                                for (s in samples) {
                                    if (s < sMin) sMin = s
                                    if (s > sMax) sMax = s
                                    sSum += s
                                }
                                val sMean = (sSum / samples.size).toFloat()
                                append("PCM sample range: [$sMin, $sMax], mean=$sMean\n")
                                append("First 10 samples: ${samples.take(10).joinToString(", ")}\n")
                                append("PCM file: ${pcmFile.name} (${pcmFile.length()} bytes)\n")
                                append("Window energy: rmsEnergy=$rmsEnergy, zcr=$zcr\n")
                            }
                            vadLog("[v2.4.140] ERROR: YAMNet malfunction.\n$diag")
                            Log.e(TAG, "[v2.4.140] YAMNet malfunction:\n$diag")
                            throw RuntimeException(diag)
                        } else {
                            vadLog("[v2.4.140] YAMNet single/all-half frame treated as uncertain; continuing with energy fallback (vadMalfunction=$vadMalfunction)")
                        }
                    }
                }

                // Energy features (supplementary) — computed above for v2.4.128 diagnostics

                // Fuse: dual-model classification
                // v2.4.133: When VAD malfunctions, use YAMNet-only classification
                val type = if (vadMalfunction) {
                    classifyFrameYamnetOnly(yamnetSpeech, yamnetMusic, yamnetSilence, rmsEnergy, zcr, maxEnergy)
                } else {
                    classifyFrameDualModel(
                        yamnetSpeech, yamnetMusic, yamnetSilence, vadProb, rmsEnergy, zcr, maxEnergy
                    )
                }
                frameResults.add(FrameResult(timestampMs, type, vadProb, yamnetSpeech, yamnetMusic, yamnetSilence, rmsEnergy))

                pos += FRAME_STEP_SAMPLES
            }

            Log.i(TAG, "Analyzed ${frameResults.size} frames")
            val segments = mergeFramesIntoSegments(frameResults, durationMs)
            Log.i(TAG, "Generated ${segments.size} segments (dry=${segments.count { it.label == "干货" }}, water=${segments.count { it.label == "水货" }})")
            return segments

        } finally {
            yamnetInterpreter.close()
            try { vadModel.session.close() } catch (_: Exception) {}
        }
    }

    // ===== YAMNet (TFLite) =====

    private fun loadYamnetModel(modelFile: File): Interpreter {
        try {
            // v2.4.129: Log model file info to file log for diagnostics
            vadLog("[v2.4.129] loadYamnetModel: file=${modelFile.name}, size=${modelFile.length()} bytes, exists=${modelFile.exists()}")
            val mappedBuffer = FileInputStream(modelFile).channel.map(
                FileChannel.MapMode.READ_ONLY, 0, modelFile.length()
            )
            val options = Interpreter.Options()
            options.setNumThreads(2)
            val interp = Interpreter(mappedBuffer, options)
            val inputShape = interp.getInputTensor(0).shape()
            val inputType = interp.getInputTensor(0).dataType()
            val outputShape = interp.getOutputTensor(0).shape()
            val outputType = interp.getOutputTensor(0).dataType()
            // v2.4.130: Store actual input shape for use in classifyWithYamnet
            yamnetInputShape = inputShape
            Log.i(TAG, "YAMNet loaded: input=${inputShape.contentToString()} ($inputType), output=${outputShape.contentToString()} ($outputType)")
            // v2.4.129: Log to file log for diagnostics
            vadLog("[v2.4.129] loadYamnetModel: loaded successfully. input=${inputShape.contentToString()} ($inputType), output=${outputShape.contentToString()} ($outputType)")
            // v2.4.130: Removed the warning about unexpected input shape.
            // The model's input shape [15600] is valid — YAMNet expects 1D raw waveform input.
            return interp
        } catch (e: Throwable) {
            // v2.4.112: Catch Throwable (not Exception) to catch UnsatisfiedLinkError
            // which extends Error, not Exception. When libtensorflowlite_jni.so is not
            // loaded, the Interpreter constructor throws UnsatisfiedLinkError.
            vadLog("Failed to load YAMNet TFLite model: ${e.javaClass.name}: ${e.message}")
            throw RuntimeException("YAMNet模型加载失败(${e.javaClass.simpleName}): ${e.message}", e)
        }
    }

    private var yamnetCallCount = 0
    // v2.4.133: Flag for YAMNet-only mode when VAD malfunctions
    private var vadMalfunction = false
    // v2.4.130: Store YAMNet input shape from model for correct tensor creation
    private var yamnetInputShape: IntArray = intArrayOf(1, 15600)

    private fun classifyWithYamnet(
        interpreter: Interpreter,
        samples: FloatArray
    ): Triple<Float, Float, Float> {
        try {
            // v2.4.130: Use model's actual input shape instead of hardcoded [1, 15600].
            // The loaded model reports input=[15600] (1D), not [1, 15600] (2D).
            // Creating a buffer with wrong shape causes TFLite to process data incorrectly,
            // producing all-zero outputs (sigmoid(0) = 0.5 for all classes).
            val inputBuffer = TensorBuffer.createFixedSize(
                yamnetInputShape,
                org.tensorflow.lite.DataType.FLOAT32
            )
            inputBuffer.loadArray(samples)

            // v2.4.129: Log input diagnostics for first 3 calls
            yamnetCallCount++
            if (yamnetCallCount <= 3) {
                var nonZero = 0
                var sum = 0.0
                for (s in samples) { if (s != 0f) nonZero++; sum += kotlin.math.abs(s) }
                val avgAbs = (sum / samples.size).toFloat()
                vadLog("[v2.4.129] classifyWithYamnet #$yamnetCallCount: input samples=${samples.size}, nonZero=$nonZero, avgAbs=$avgAbs, first10=${samples.take(10).joinToString(",")}")
            }

            // Output: [1, 521] float
            val outputBuffer = TensorBuffer.createFixedSize(
                intArrayOf(1, YAMNET_NUM_CLASSES),
                org.tensorflow.lite.DataType.FLOAT32
            )

            interpreter.run(inputBuffer.buffer, outputBuffer.buffer)
            val scores = outputBuffer.floatArray

            // v2.4.129: Log raw output scores for first 3 calls
            if (yamnetCallCount <= 3) {
                val rawSpeech = scores.getOrElse(YAMNET_IDX_SPEECH) { 0f }
                val rawSilence = scores.getOrElse(YAMNET_IDX_SILENCE) { 0f }
                val rawMusic = scores.getOrElse(YAMNET_IDX_MUSIC) { 0f }
                val rawSong = scores.getOrElse(YAMNET_IDX_SONG) { 0f }
                // Find max score and its index
                var maxIdx = 0
                var maxScore = -Float.MAX_VALUE
                for (i in scores.indices) {
                    if (scores[i] > maxScore) { maxScore = scores[i]; maxIdx = i }
                }
                vadLog("[v2.4.129] classifyWithYamnet #$yamnetCallCount: raw scores: speech[$YAMNET_IDX_SPEECH]=$rawSpeech, silence[$YAMNET_IDX_SILENCE]=$rawSilence, music[$YAMNET_IDX_MUSIC]=$rawMusic, song[$YAMNET_IDX_SONG]=$rawSong")
                vadLog("[v2.4.129] classifyWithYamnet #$yamnetCallCount: max score=$maxScore at idx=$maxIdx, all zeros=${scores.all { it == 0f }}, output size=${scores.size}")
            }

            // Extract key categories (apply sigmoid to raw scores)
            val speechProb = sigmoid(scores.getOrElse(YAMNET_IDX_SPEECH) { 0f })
            val silenceProb = sigmoid(scores.getOrElse(YAMNET_IDX_SILENCE) { 0f })
            val musicProb = maxOf(
                sigmoid(scores.getOrElse(YAMNET_IDX_MUSIC) { 0f }),
                sigmoid(scores.getOrElse(YAMNET_IDX_SONG) { 0f })
            )
            return Triple(speechProb, musicProb, silenceProb)
        } catch (e: Exception) {
            Log.e(TAG, "YAMNet classification failed: ${e.message}")
            vadLog("[v2.4.129] classifyWithYamnet FAILED: ${e.javaClass.simpleName}: ${e.message}")
            return Triple(0f, 0f, 0f)
        }
    }

    private fun sigmoid(x: Float): Float {
        val exp = kotlin.math.exp(-x.toDouble())
        return (1.0 / (1.0 + exp)).toFloat()
    }

    // ===== Silero VAD (ONNX Runtime via reflection, no SessionOptions) =====
    // v2.4.111: Query model's actual input/output names and adapt to model version.
    // Root cause of "1 segment" bug: code used v1/v2 names ("h", "c", "output", "hn", "cn")
    // but the 2.3MB model is v3/v4 which uses "state", "sr", "prob", "stateN".
    // session.run() with wrong input names throws, caught by outer catch → 0.5f for all chunks.

    private data class VadModelInfo(
        val session: AiSession,
        val inputNames: Set<String>,
        val outputNames: Set<String>,
        val isV4Style: Boolean,     // true if model uses "state" input (v3/v4)
        val stateSize: Int,         // total float elements in state buffer
        val stateShape: LongArray,  // shape of state tensor
        val outputProbName: String, // "output" (v1/v2) or "prob" (v3/v4)
        val outputStateName: String // "hn" (v1/v2) or "stateN" (v3/v4)
    )

    private fun loadSileroVad(modelFile: File): VadModelInfo {
        try {
            val envClass = Class.forName("ai.onnxruntime.OrtEnvironment")
            val env = envClass.getMethod("getEnvironment").invoke(null)
            vadLog("loadSileroVad: OrtEnvironment obtained")

            val createSessionMethod = envClass.methods.first {
                it.name == "createSession" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java
            }
            val session = createSessionMethod.invoke(env, modelFile.absolutePath)
            vadLog("loadSileroVad: session created from ${modelFile.name} (${modelFile.length()} bytes)")

            val sessionObj = session!!
            val sessionClass = sessionObj.javaClass

            // v2.4.111: Query actual input/output names from the model
            val inputNames: Set<String> = try {
                val getInputNamesMethod = sessionClass.getMethod("getInputNames")
                (getInputNamesMethod.invoke(sessionObj) as? Set<String>) ?: emptySet()
            } catch (e: Exception) {
                vadLog("loadSileroVad WARN: getInputNames failed: ${e.message}")
                emptySet()
            }

            val outputNames: Set<String> = try {
                val getOutputNamesMethod = sessionClass.getMethod("getOutputNames")
                (getOutputNamesMethod.invoke(sessionObj) as? Set<String>) ?: emptySet()
            } catch (e: Exception) {
                vadLog("loadSileroVad WARN: getOutputNames failed: ${e.message}")
                emptySet()
            }

            vadLog("loadSileroVad: inputNames=$inputNames, outputNames=$outputNames")

            // Detect model version
            // v2.4.113: If getInputNames() failed (returned empty set), default to v3/v4
            // because the 2.3MB model is v3/v4. Using v1/v2 names with v3/v4 model causes
            // session.run() to throw, caught by outer catch → 0.5f for all chunks → 1 segment.
            val isV4Style = if (inputNames.isNotEmpty()) {
                inputNames.contains("state")
            } else {
                vadLog("loadSileroVad WARN: getInputNames returned empty, defaulting to v3/v4 (2.3MB model is v3/v4)")
                true
            }
            val stateInputName = if (isV4Style) "state" else "h"
            val hasSr = inputNames.contains("sr") || isV4Style  // v3/v4 always has sr

            // Determine output names
            val outputProbName = when {
                outputNames.contains("prob") -> "prob"
                outputNames.contains("output") -> "output"
                else -> "output"
            }
            val outputStateName = when {
                outputNames.contains("stateN") -> "stateN"
                outputNames.contains("hn") -> "hn"
                else -> "hn"
            }

            // Query state shape from model metadata
            // v2.4.114: Default state shape depends on model version:
            // v1/v2: [2, 1, 32] → 64 floats per h/c, total 128
            // v3/v4: [2, 1, 128] → 256 floats (single combined state)
            var stateShape = if (isV4Style) longArrayOf(2, 1, 128) else longArrayOf(2, 1, 32)
            try {
                val getInputInfoMethod = sessionClass.getMethod("getInputInfo")
                val inputInfo = getInputInfoMethod.invoke(sessionObj) as? Map<*, *>
                if (inputInfo != null) {
                    val stateNodeInfo = inputInfo[stateInputName]
                    if (stateNodeInfo != null) {
                        val getInfoMethod = stateNodeInfo.javaClass.getMethod("getInfo")
                        val tensorInfo = getInfoMethod.invoke(stateNodeInfo)
                        if (tensorInfo != null) {
                            val getShapeMethod = tensorInfo.javaClass.getMethod("getShape")
                            val shape = getShapeMethod.invoke(tensorInfo) as? LongArray
                            if (shape != null && shape.isNotEmpty()) {
                                stateShape = shape
                                vadLog("loadSileroVad: state '$stateInputName' shape=${shape.contentToString()}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                vadLog("loadSileroVad WARN: getInputInfo failed: ${e.message}")
            }

            // Calculate total state buffer size
            // For v1/v2: two buffers of stateShape, total = 2 * product(stateShape)
            // For v3/v4: one buffer of stateShape
            // v2.4.121: Replace -1 with 1 (batch_size=1), NOT 2.
            // ONNX model error: "Input initial_h must have shape {1,1,128}. Actual:{1,2,128}"
            // The state shape [2, -1, 128] means:
            //   dim 0 = 2: number of LSTM states (h and c)
            //   dim 1 = -1: batch_size (dynamic, should be 1 for single stream)
            //   dim 2 = 128: hidden_size
            // So safeShape = [2, 1, 128], stateSize = 2 * 1 * 128 = 256
            val safeShape = stateShape.map { if (it <= 0) 1L else it }.toLongArray()
            val stateElementCount = safeShape.fold(1L) { acc, dim -> acc * dim }.toInt()
            val stateSize = if (isV4Style) stateElementCount else stateElementCount * 2

            vadLog("loadSileroVad: model version=${if (isV4Style) "v3/v4" else "v1/v2"}, " +
                    "hasSr=$hasSr, stateShape=${stateShape.contentToString()} (safe=${safeShape.contentToString()}), " +
                    "stateSize=$stateSize, " +
                    "outputProbName='$outputProbName', outputStateName='$outputStateName'")

            return VadModelInfo(
                session = AiSession(session),
                inputNames = inputNames,
                outputNames = outputNames,
                isV4Style = isV4Style,
                stateSize = stateSize,
                // v2.4.119: Use safeShape (with -1 replaced by 2) instead of original stateShape.
                // ONNX Runtime's OrtUtil.elementCount() throws IllegalArgumentException on negative
                // shape values. The model reports [2, -1, 128] but actual batch size is 2.
                stateShape = safeShape,
                outputProbName = outputProbName,
                outputStateName = outputStateName
            )
        } catch (e: Throwable) {
            vadLog("loadSileroVad FAILED: ${e.javaClass.name}: ${e.message}")
            throw RuntimeException("Silero VAD模型加载失败(${e.javaClass.simpleName}): ${e.message}", e)
        }
    }

    private fun runSileroVad(
        model: VadModelInfo,
        chunk: FloatArray,
        context: FloatArray,
        state: FloatBuffer
    ): Triple<Float, FloatBuffer, FloatArray> {

        try {
            vadRunCount++

            // v2.4.142: Silero VAD expects the previous 64 samples as context prepended to the
            // current 512-sample chunk. Without this context the model outputs near-zero
            // probabilities and appears to malfunction on normal speech.
            val vadInput = FloatArray(VAD_CONTEXT_SIZE + chunk.size)
            System.arraycopy(context, 0, vadInput, 0, VAD_CONTEXT_SIZE)
            System.arraycopy(chunk, 0, vadInput, VAD_CONTEXT_SIZE, chunk.size)

            val sessionObj = model.session.session
            val sessionClass = sessionObj.javaClass
            val envClass = Class.forName("ai.onnxruntime.OrtEnvironment")
            val env = envClass.getMethod("getEnvironment").invoke(null)
            val onnxTensorClass = Class.forName("ai.onnxruntime.OnnxTensor")

            // v2.4.110: Sort createTensor methods to prefer specific types over Object
            val tensorMethods = onnxTensorClass.methods.filter {
                it.name == "createTensor" &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == envClass &&
                it.parameterTypes[2] == LongArray::class.java
            }.sortedByDescending { method ->
                when (method.parameterTypes[1]) {
                    FloatArray::class.java -> 3
                    LongArray::class.java -> 2
                    java.lang.Object::class.java, Any::class.java -> 0
                    else -> 1
                }
            }

            // v2.4.117: Diagnostic logging for tensor method discovery (first 3 calls only)
            if (vadRunCount <= 3) {
                vadLog("createTensor discovery: found ${tensorMethods.size} methods")
                for (m in tensorMethods) {
                    vadLog("  method: ${m.parameterTypes.map { it.simpleName }}")
                }
            }

            // v2.4.118: ONNX Runtime Android only has Buffer-based createTensor methods.
            // FloatBuffer.wrap() creates a non-direct buffer which causes InvocationTargetException.
            // Must use ByteBuffer.allocateDirect() + asFloatBuffer() for direct memory.
            val floatBufferMethod = tensorMethods.find { it.parameterTypes[1] == java.nio.FloatBuffer::class.java }
            val longBufferMethod = tensorMethods.find { it.parameterTypes[1] == java.nio.LongBuffer::class.java }

            fun createTensor(data: Any, shape: LongArray): Any? {
                try {
                    when (data) {
                        is FloatArray -> {
                            // v2.4.118: Use direct ByteBuffer → FloatBuffer
                            val bb = java.nio.ByteBuffer.allocateDirect(data.size * 4)
                            bb.order(java.nio.ByteOrder.nativeOrder())
                            val fb = bb.asFloatBuffer()
                            fb.put(data)
                            fb.rewind()
                            if (floatBufferMethod != null) {
                                val result = floatBufferMethod.invoke(null, env, fb, shape)
                                if (result != null) return result
                            }
                            // Fallback: try generic Buffer method
                            val bufMethod = tensorMethods.find { it.parameterTypes[1] == java.nio.Buffer::class.java }
                            if (bufMethod != null) {
                                val result = bufMethod.invoke(null, env, fb, shape)
                                if (result != null) return result
                            }
                        }
                        is LongArray -> {
                            // v2.4.118: Use direct ByteBuffer → LongBuffer
                            val bb = java.nio.ByteBuffer.allocateDirect(data.size * 8)
                            bb.order(java.nio.ByteOrder.nativeOrder())
                            val lb = bb.asLongBuffer()
                            lb.put(data)
                            lb.rewind()
                            if (longBufferMethod != null) {
                                val result = longBufferMethod.invoke(null, env, lb, shape)
                                if (result != null) return result
                            }
                            val bufMethod = tensorMethods.find { it.parameterTypes[1] == java.nio.Buffer::class.java }
                            if (bufMethod != null) {
                                val result = bufMethod.invoke(null, env, lb, shape)
                                if (result != null) return result
                            }
                        }
                        is java.nio.FloatBuffer -> {
                            if (floatBufferMethod != null) {
                                val result = floatBufferMethod.invoke(null, env, data, shape)
                                if (result != null) return result
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // v2.4.118: Unwrap InvocationTargetException to get real cause
                    val cause = if (e is java.lang.reflect.InvocationTargetException) e.targetException else e
                    if (vadRunCount <= 3) {
                        vadLog("createTensor: ${data.javaClass.simpleName} shape=${shape.toList()} threw ${cause.javaClass.simpleName}: ${cause.message}")
                        // Log stack trace for first failure
                        val sw = java.io.StringWriter()
                        cause.printStackTrace(java.io.PrintWriter(sw))
                        vadLog("createTensor stack: ${sw.toString().take(500)}")
                    }
                }
                return null
            }

            // Build input map with correct names based on model version
            val inputMap = HashMap<String, Any>()

            // "input" tensor: shape [1, vadInput.size] — same for all versions
            val inputTensor = createTensor(vadInput, longArrayOf(1, vadInput.size.toLong()))
                ?: throw RuntimeException("createTensor failed for input")
            inputMap[model.inputNames.firstOrNull() ?: "input"] = inputTensor

            // v2.4.129/v2.4.142: Log VAD input diagnostics for first 5 calls
            if (vadRunCount <= 5) {
                var chunkNonZero = 0
                var chunkSum = 0.0
                for (s in vadInput) { if (s != 0f) chunkNonZero++; chunkSum += kotlin.math.abs(s) }
                val chunkAvgAbs = (chunkSum / vadInput.size).toFloat()
                vadLog("[v2.4.142] runSileroVad #$vadRunCount: input size=${vadInput.size} (context=$VAD_CONTEXT_SIZE + chunk=${chunk.size}), nonZero=$chunkNonZero, avgAbs=$chunkAvgAbs, first10=${vadInput.take(10).joinToString(",")}")
            }

            if (model.isV4Style) {
                // v3/v4: single "state" input
                val stateData = state.array()
                val stateTensor = createTensor(stateData, model.stateShape)
                    ?: throw RuntimeException("createTensor failed for state")
                inputMap["state"] = stateTensor!!

                // v2.4.115 CRITICAL FIX: "sr" input is REQUIRED by all v3/v4 models.
                // Previously checked model.inputNames.contains("sr"), but when getInputNames()
                // fails (returns empty set on some devices), "sr" was never added → session.run()
                // throws → catch returns 0.5f → all chunks classified as speech → 1 segment.
                // Now: always add "sr" for v3/v4 models.
                // v3/v4 sr is a scalar int64 (shape []); try scalar first, then [1] fallback.
                val srTensor = createTensor(longArrayOf(16000L), longArrayOf())
                    ?: createTensor(longArrayOf(16000L), longArrayOf(1))
                if (srTensor != null) {
                    inputMap["sr"] = srTensor
                } else {
                    vadLog("runSileroVad WARN: Failed to create sr tensor (scalar and [1] both failed)")
                }
            } else {
                // v1/v2: separate "h" and "c" inputs
                val stateData = state.array()
                val halfSize = model.stateSize / 2
                val hData = stateData.copyOfRange(0, halfSize)
                val cData = stateData.copyOfRange(halfSize, model.stateSize)

                val hTensor = createTensor(hData, model.stateShape)
                    ?: throw RuntimeException("createTensor failed for h")
                inputMap["h"] = hTensor!!

                val cTensor = createTensor(cData, model.stateShape)
                    ?: throw RuntimeException("createTensor failed for c")
                inputMap["c"] = cTensor!!

                // v2.4.115: Same fix — always add "sr" for v2 models that use it
                if (model.inputNames.isEmpty() || model.inputNames.contains("sr")) {
                    val srTensor = createTensor(longArrayOf(16000L), longArrayOf(1))
                        ?: createTensor(longArrayOf(16000L), longArrayOf())
                    if (srTensor != null) {
                        inputMap["sr"] = srTensor
                    }
                }
            }

            // v2.4.115: Log input map before inference (first 5 calls only)
            if (vadRunCount <= 5) {
                vadLog("runSileroVad: inputMap keys=${inputMap.keys}, isV4Style=${model.isV4Style}, stateSize=${model.stateSize}, stateShape=${model.stateShape.toList()}")
                // v2.4.120: Log tensor details for debugging session.run failure
                for ((key, value) in inputMap) {
                    try {
                        val tensorClass = value.javaClass
                        val infoMethod = tensorClass.getMethod("getInfo")
                        val info = infoMethod.invoke(value)
                        vadLog("  tensor[$key]: ${info.toString()}")
                    } catch (diag: Exception) {
                        vadLog("  tensor[$key]: (can't get info: ${diag.message})")
                    }
                }
            }

            // Run inference
            val runMethod = sessionClass.getMethod("run", Map::class.java)
            if (vadRunCount <= 5) {
                vadLog("runSileroVad: calling session.run()...")
            }
            val results = runMethod.invoke(sessionObj, inputMap)
            if (vadRunCount <= 5) {
                vadLog("runSileroVad: session.run() returned successfully!")
            }

            val resultsClass = results.javaClass
            val getMethod = resultsClass.getMethod("get", String::class.java)

            // Use getFloatBuffer() for output extraction (works for any tensor shape)
            val getFloatBufferMethod = onnxTensorClass.getMethod("getFloatBuffer")

            // v2.4.122: session.run() returns Map<String, Optional<OnnxTensor>> on Android.
            // The map values are java.util.Optional, not OnnxTensor directly.
            // Need to unwrap Optional before accessing tensor methods.
            val optionalClass = java.util.Optional::class.java
            val optionalGetMethod = optionalClass.getMethod("get")
            val optionalIsPresentMethod = optionalClass.getMethod("isPresent")

            fun tensorToFloatArray(tensorObj: Any): FloatArray {
                // v2.4.122: Unwrap Optional if needed
                val tensor = if (optionalClass.isInstance(tensorObj)) {
                    if (optionalIsPresentMethod.invoke(tensorObj) as Boolean) {
                        optionalGetMethod.invoke(tensorObj)
                    } else {
                        return FloatArray(0)  // Optional is empty
                    }
                } else {
                    tensorObj
                }
                val fb = getFloatBufferMethod.invoke(tensor) as FloatBuffer
                val arr = FloatArray(fb.remaining())
                fb.get(arr)
                return arr
            }

            // Extract probability output
            val outputTensor = getMethod.invoke(results, model.outputProbName)
            val outputArr = tensorToFloatArray(outputTensor)
            val prob = if (outputArr.isNotEmpty()) outputArr[0] else 0.5f

            // Extract new state
            val newStateTensor = getMethod.invoke(results, model.outputStateName)
            val newStateArr = tensorToFloatArray(newStateTensor)

            // Build new state FloatBuffer
            val newBuffer: FloatBuffer
            if (model.isV4Style) {
                // v3/v4: single "stateN" output
                newBuffer = if (newStateArr.size >= model.stateSize) {
                    FloatBuffer.wrap(newStateArr.copyOf(model.stateSize))
                } else {
                    FloatBuffer.wrap(FloatArray(model.stateSize))
                }
            } else {
                // v1/v2: two outputs "hn" and "cn" — concatenate into single buffer
                val hnTensor = getMethod.invoke(results, model.outputStateName) // "hn"
                val hnArr = tensorToFloatArray(hnTensor)

                // For v1/v2, also get "cn" output
                val cnName = if (model.outputNames.contains("cn")) "cn" else "cn"
                val cnTensor = getMethod.invoke(results, cnName)
                val cnArr = tensorToFloatArray(cnTensor)

                val combined = FloatArray(model.stateSize)
                val halfSize = model.stateSize / 2
                System.arraycopy(hnArr, 0, combined, 0, minOf(halfSize, hnArr.size))
                System.arraycopy(cnArr, 0, combined, halfSize, minOf(halfSize, cnArr.size))
                newBuffer = FloatBuffer.wrap(combined)
            }

            // Cleanup
            try { resultsClass.getMethod("close").invoke(results) } catch (_: Exception) {}
            try { onnxTensorClass.getMethod("close").invoke(inputTensor) } catch (_: Exception) {}
            for (v in inputMap.values) {
                if (v !== inputTensor) {
                    try { onnxTensorClass.getMethod("close").invoke(v) } catch (_: Exception) {}
                }
            }

            // v2.4.142: Update context to the last 64 samples of the current chunk for the next call.
            val newContext = chunk.copyOfRange(chunk.size - VAD_CONTEXT_SIZE, chunk.size)
            return Triple(prob, newBuffer, newContext)
        } catch (e: Throwable) {
            // v2.4.120: Unwrap InvocationTargetException to get real cause from session.run()
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.targetException else e
            vadLog("Silero VAD inference FAILED: ${cause.javaClass.name}: ${cause.message}")
            // v2.4.120: Log full stack trace for first 5 failures
            if (vadRunCount <= 5) {
                val sw = java.io.StringWriter()
                cause.printStackTrace(java.io.PrintWriter(sw))
                vadLog("FAILED stack trace: ${sw.toString().take(800)}")
            }
            return Triple(0.5f, state, context)
        }
    }

    // ===== Feature computation =====

    private fun readPcmAsFloats(pcmFile: File): FloatArray {
        // v2.4.131: Use streaming read to avoid OOM on large PCM files (>400MB).
        // Previously used pcmFile.readBytes() which loaded entire file into memory.
        val numSamples = (pcmFile.length() / 2).toInt()
        val samples = FloatArray(numSamples)
        val byteBuffer = ByteBuffer.allocate(8192 * 2).order(ByteOrder.LITTLE_ENDIAN)
        var sampleIdx = 0
        java.io.FileInputStream(pcmFile).use { fis ->
            while (true) {
                val read = fis.read(byteBuffer.array())
                if (read <= 0) break
                byteBuffer.clear()
                byteBuffer.limit(read)
                while (byteBuffer.remaining() >= 2 && sampleIdx < numSamples) {
                    samples[sampleIdx++] = byteBuffer.short.toFloat() / 32768.0f
                }
            }
        }
        return samples
    }

    private fun computeRmsEnergy(samples: FloatArray, offset: Int, length: Int): Float {
        var sumSquares = 0.0
        for (i in offset until offset + length) {
            if (i < samples.size) sumSquares += samples[i].toDouble() * samples[i]
        }
        return kotlin.math.sqrt(sumSquares / length).toFloat()
    }

    private fun computeZeroCrossingRate(samples: FloatArray): Float {
        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] >= 0) != (samples[i - 1] >= 0)) crossings++
        }
        return crossings.toFloat() / (samples.size - 1)
    }

    // ===== Dual-model classification =====

    /**
     * Classify a frame using both YAMNet and Silero VAD outputs.
     *
     * Fusion rules:
     * 1. YAMNet Silence + low VAD → SILENCE (boundary)
     * 2. YAMNet Music high + VAD low → WATER (music/水货)
     * 3. YAMNet Speech high + VAD high → DRY (干货)
     * 4. Disagreement: trust VAD for speech, YAMNet for music
     */
    private fun classifyFrameDualModel(
        yamnetSpeech: Float,
        yamnetMusic: Float,
        yamnetSilence: Float,
        vadProb: Float,
        rmsEnergy: Float,
        zcr: Float,
        maxEnergy: Float
    ): FrameType {
        val energyRatio = rmsEnergy / maxEnergy

        // v2.4.128: Removed all fallback classification logic.
        // When VAD or YAMNet malfunction, analyzePcmFile() throws an exception
        // and aborts segmentation. This function is only reached when both
        // models are working correctly.

        // 1. Silence: both models agree on low energy
        if (energyRatio < ENERGY_SILENCE_RATIO && vadProb < VAD_WATER_THRESHOLD) {
            return FrameType.SILENCE
        }
        if (yamnetSilence > 0.6f && vadProb < 0.2f) {
            return FrameType.SILENCE
        }

        // 2. Music: YAMNet says music, VAD says no speech
        if (yamnetMusic > 0.5f && vadProb < VAD_DRY_THRESHOLD) {
            return FrameType.WATER
        }

        // 3. Speech: YAMNet says speech, VAD confirms
        if (yamnetSpeech > 0.3f && vadProb > VAD_DRY_THRESHOLD) {
            return FrameType.DRY
        }

        // 4. VAD says speech but YAMNet doesn't detect music → trust VAD
        if (vadProb > VAD_DRY_THRESHOLD && yamnetMusic < 0.3f) {
            return FrameType.DRY
        }

        // 5. YAMNet says music but VAD is moderate → trust YAMNet for music
        if (yamnetMusic > 0.4f) {
            return FrameType.WATER
        }

        // 6. Low VAD + high energy → water (non-speech content)
        if (vadProb < VAD_WATER_THRESHOLD && energyRatio > ENERGY_MUSIC_RATIO) {
            return FrameType.WATER
        }

        // 7. Default: use VAD as tiebreaker
        return if (vadProb > 0.4f) FrameType.DRY else FrameType.WATER
    }

    // v2.4.133: YAMNet-only classification when VAD malfunctions.
    // Uses YAMNet speech/music/silence scores + energy features.
    private fun classifyFrameYamnetOnly(
        yamnetSpeech: Float,
        yamnetMusic: Float,
        yamnetSilence: Float,
        rmsEnergy: Float,
        zcr: Float,
        maxEnergy: Float
    ): FrameType {
        val energyRatio = rmsEnergy / maxEnergy

        // 1. Silence: YAMNet says silence + low energy
        if (yamnetSilence > 0.6f && energyRatio < ENERGY_SILENCE_RATIO) {
            return FrameType.SILENCE
        }
        if (energyRatio < ENERGY_SILENCE_RATIO * 0.5f) {
            return FrameType.SILENCE
        }

        // 2. Music: YAMNet says music
        if (yamnetMusic > 0.5f) {
            return FrameType.WATER
        }

        // 3. Speech: YAMNet says speech
        if (yamnetSpeech > 0.3f) {
            return FrameType.DRY
        }

        // 4. High energy but no speech → water (non-speech content)
        if (energyRatio > ENERGY_MUSIC_RATIO) {
            return FrameType.WATER
        }

        // 5. Default: use YAMNet speech as tiebreaker
        return if (yamnetSpeech > 0.2f) FrameType.DRY else FrameType.WATER
    }

    // ===== Segment merging =====

    private fun mergeFramesIntoSegments(
        frames: List<FrameResult>,
        durationMs: Long
    ): List<VoiceSegment> {
        if (frames.isEmpty()) return emptyList()

        val minSegmentMs = 30_000L
        val segments = mutableListOf<VoiceSegment>()
        var segStart = frames[0].timestampMs
        var segType = frames[0].type

        for (i in 1 until frames.size) {
            val frame = frames[i]
            if (frame.type != segType) {
                val segEnd = frame.timestampMs
                if (segEnd - segStart >= minSegmentMs || segments.isEmpty()) {
                    segments.add(createSegment(segStart, segEnd, segType))
                } else {
                    if (segments.isNotEmpty()) {
                        segments.last().end = segEnd
                    } else {
                        segments.add(createSegment(segStart, segEnd, segType))
                    }
                }
                segStart = frame.timestampMs
                segType = frame.type
            }
        }
        val lastEnd = durationMs
        if (lastEnd - segStart >= minSegmentMs || segments.isEmpty()) {
            segments.add(createSegment(segStart, lastEnd, segType))
        } else if (segments.isNotEmpty()) {
            segments.last().end = lastEnd
        }

        // Merge silence segments into adjacent segments
        val merged = mutableListOf<VoiceSegment>()
        for (seg in segments) {
            if (seg.label == "静音") {
                if (merged.isNotEmpty()) merged.last().end = seg.end
            } else {
                merged.add(seg)
            }
        }
        if (merged.isEmpty()) {
            merged.add(VoiceSegment().apply {
                start = 0L; end = durationMs; hasVoice = true; label = "干货"; isSimulated = false
            })
        }
        return merged
    }

    private fun createSegment(start: Long, end: Long, type: FrameType): VoiceSegment {
        return VoiceSegment().apply {
            this.start = start; this.end = end
            this.hasVoice = type == FrameType.DRY
            this.label = when (type) {
                FrameType.DRY -> "干货"; FrameType.WATER -> "水货"; FrameType.SILENCE -> "静音"
            }
            this.isSimulated = false
        }
    }

    // ===== Inner classes =====

    private data class FrameResult(
        val timestampMs: Long,
        val type: FrameType,
        val vadProb: Float,
        val yamnetSpeech: Float,
        val yamnetMusic: Float,
        val yamnetSilence: Float, // v2.4.140: stored for robust YAMNet malfunction detection
        val rmsEnergy: Float
    )

    private class AiSession(val session: Any) {
        fun close() {
            try { session.javaClass.getMethod("close").invoke(session) } catch (_: Exception) {}
        }
    }
}
