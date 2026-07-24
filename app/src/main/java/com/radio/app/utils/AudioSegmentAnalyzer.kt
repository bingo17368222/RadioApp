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
 * v2.4.163: Audio-based AI segment analyzer using Silero + YAMNet cascade.
 *
 * Silero VAD (ONNX, ~2.3MB): Coarse speech/silence region segmentation
 * YAMNet (TFLite, ~4.1MB): Audio classification (521 categories: Speech, Narration, Singing, Music, etc.)
 *
 * Processing:
 * 1. Read 16kHz mono PCM data.
 * 2. Silero VAD: traverse whole audio (512 samples/chunk) to split speech/silence intervals.
 * 3. Speech intervals: run YAMNet densely (0.975s window, 0.5s hop), merge same-type windows
 *    within each interval, and return interval-local sub-segments.
 * 4. Silence intervals: default to silence segment, then sparse YAMNet sampling (every 3s,
 *    1.2s window, center 0.975s fed to YAMNet) to recover missed voice; hits split the
 *    interval into dry/silence sub-segments.
 * 5. Post-process: merge fragments (<1500ms), absorb short music gaps (<800ms), merge close dry segments (<2.5s).
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

    // v2.4.156: Hold the currently running analysis thread so the notification cancel
    // action (or a new segment request) can interrupt it, even when PlayerActivity is gone.
    @Volatile
    private var currentAnalysisThread: Thread? = null

    /**
     * Interrupt the currently running audio-segment analysis (decode + classify).
     * Called from the notification cancel action or when starting a new segment task.
     */
    fun cancelCurrentAnalysis(): Boolean {
        val t = currentAnalysisThread ?: return false
        return if (!t.isInterrupted) {
            t.interrupt()
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] AudioSegmentAnalyzer: interrupted analysis thread")
            true
        } else {
            false
        }
    }

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("音频分段已取消")
        }
    }

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

    // v2.4.150: Format milliseconds as mm:ss or hh:mm:ss for user-friendly logs and UI.
    fun formatDurationMs(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    // YAMNet: 16kHz, 0.975s window = 15600 samples
    private const val YAMNET_SAMPLE_RATE = 16000
    private const val YAMNET_WINDOW_SAMPLES = 15600
    private const val YAMNET_NUM_CLASSES = 521

    // YAMNet class indices (from AudioSet ontology)
    // v2.4.161: Expanded indices for Silero + YAMNet cascade
    private const val YAMNET_IDX_SPEECH = 0             // Speech
    private const val YAMNET_IDX_NARRATION = 3          // Narration, monologue
    private const val YAMNET_IDX_SINGING = 24           // Singing
    private const val YAMNET_IDX_MUSIC = 132            // Music
    private const val YAMNET_IDX_INSTRUMENTAL = 133     // Musical instrument
    private const val YAMNET_IDX_POP_MUSIC = 211        // Pop music
    private const val YAMNET_IDX_SONG = 261             // Song
    private const val YAMNET_IDX_BACKGROUND_MUSIC = 262 // Background music
    private const val YAMNET_IDX_THEME_MUSIC = 263      // Theme music
    private const val YAMNET_IDX_JINGLE = 264           // Jingle
    private const val YAMNET_IDX_SILENCE = 494          // Silence

    // v2.4.161: YAMNet hop in speech intervals (0.5s = 8000 samples at 16kHz)
    private const val YAMNET_SPEECH_HOP_SAMPLES = 8000

    // v2.4.161: Sparse sampling in silence intervals
    private const val SILENCE_SAMPLE_INTERVAL_MS = 3000L
    private const val SILENCE_SAMPLE_WINDOW_MS = 1200L
    private const val SILENCE_SAMPLE_WINDOW_SAMPLES = 19200
    private const val SILENCE_SAMPLE_HALF_SPREAD_MS = 1800L

    // Silero VAD: 512 samples per chunk (32ms at 16kHz)
    private const val VAD_FRAME_SIZE = 512
    // v2.4.142: Silero VAD expects 64 samples of previous audio as context prepended to each 512-sample chunk.
    private const val VAD_CONTEXT_SIZE = 64
    // v2.4.163: Silero VAD parameters for coarse region segmentation only
    // Raised thresholds to reduce over-segmentation from breath-pauses and short noises.
    private const val VAD_THRESHOLD = 0.50f
    private const val VAD_MIN_SPEECH_DURATION_MS = 500L
    private const val VAD_MIN_SILENCE_DURATION_MS = 800L

    // v2.4.165: YAMNet decision thresholds
    // Balanced thresholds: avoid both false-positive dry (ads/jingles) and false-negative dry
    // (host speech with light background music or weak microphone signal).
    // - VOICE_SUM_THRESHOLD: a frame needs clear combined voice activation to be considered dry.
    // - BG_MUSIC_SUM_THRESHOLD: music must be a notable fraction of voice before voice+music becomes water.
    // - SINGING_FORCE_THRESHOLD: singing must dominate voice activity to force dry protection.
    private const val VOICE_SUM_THRESHOLD = 0.10f
    private const val BG_MUSIC_SUM_THRESHOLD = 0.30f
    private const val SINGING_RATIO_THRESHOLD = 0.35f
    private const val SINGING_FORCE_THRESHOLD = 0.25f
    private const val MUSIC_AD_THRESHOLD = 0.25f

    // v2.4.165: Post-processing thresholds
    // Moderate merging: absorb short water/silence gaps into dry to reduce fragment count,
    // while keeping obvious ads/jingles longer than the gap threshold as standalone water.
    private const val MIN_FRAGMENT_MS = 1000L
    private const val MAX_PURE_MUSIC_GAP_MS = 1000L
    private const val MAX_DRY_GAP_MS = 3000L

    // Classification results
    private enum class FrameType { DRY, WATER, SILENCE }

    // v2.4.150: Result bundle for audio segmentation, including the engine used and timing.
    data class SegmentAnalysisResult(
        val segments: List<VoiceSegment>,
        val engineName: String,
        val processingTimeMs: Long,
        val audioDurationMs: Long
    )

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
            Log.w(TAG, "[${com.radio.app.RadioApplication.appVersionTag()}] writePcmInfo failed: ${e.message}")
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
            Log.w(TAG, "[${com.radio.app.RadioApplication.appVersionTag()}] getMp4DurationMs failed: ${e.message}")
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
    fun preGeneratePcmFiles(
        context: Context,
        episodeId: String,
        audioUrl: String?,
        expectedDurationMs: Long = 0,
        generateFullPcm: Boolean = true
    ): Boolean {
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
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] MediaExtractor duration for $episodeId: ${d}ms (${d / 60000} min), audioFile=${audioFile.name}\n")
            d
        } else {
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] no cached audio file found for $episodeId\n")
            0L
        }

        // v2.4.139: Fallback 1 — episode metadata duration (seconds -> ms).
        if (mp4DurationMs <= 0 && expectedDurationMs > 0) {
            mp4DurationMs = expectedDurationMs
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] using expectedDurationMs fallback for $episodeId: ${mp4DurationMs}ms\n")
        }

        // v2.4.139: Fallback 2 — parse start/end time from the audio URL path.
        if (mp4DurationMs <= 0) {
            val urlDurationMs = parseDurationFromAudioUrl(audioUrl)
            if (urlDurationMs > 0) {
                mp4DurationMs = urlDurationMs
                precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] using URL time-range fallback for $episodeId: ${mp4DurationMs}ms\n")
            }
        }

        // v2.4.148: Lightweight mode — patrol only needs the 5-min preview PCM.
        // Validate the 5-min preview independently; it does not require a full-duration check.
        if (!generateFullPcm) {
            val min5Valid = min5PcmFile.exists() &&
                    min5PcmFile.length() >= 4 * 60 * 16000 * 2 && // at least 4 minutes
                    min5PcmFile.length() <= 6 * 60 * 16000 * 2     // at most 6 minutes
            if (min5Valid) {
                precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] 5-min PCM already valid for $episodeId (${min5PcmFile.length()} bytes). Skipping decode.\n")
                return true
            }
            // v2.4.148: Delete only the stale 5-min preview, never the full PCM, when in lightweight mode.
            if (min5PcmFile.exists()) min5PcmFile.delete()
            if (min5InfoFile.exists()) min5InfoFile.delete()
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] generating 5-min PCM only for $episodeId\n")
            val fiveMinMs = 5 * 60 * 1000L
            val decoded = decodeAudioToPcm(context, episodeId, pcmCacheDir, audioUrl, mp4DurationMs, maxDecodeDurationMs = fiveMinMs)
            if (decoded == null || !decoded.exists() || decoded.length() <= 16000) {
                precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] FAILED to decode 5-min PCM for $episodeId\n")
                return false
            }
            // Rename decoded file to the 5-min target name.
            if (decoded.absolutePath != min5PcmFile.absolutePath) {
                decoded.renameTo(min5PcmFile)
            }
            val min5DurationMs = min5PcmFile.length() / (16000 * 2) * 1000
            writePcmInfo(min5InfoFile, mp4DurationMs.coerceAtLeast(min5DurationMs), min5DurationMs, 16000, 1)
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] 5-min PCM generated for $episodeId (${min5PcmFile.length()} bytes, ${min5DurationMs}ms)\n")
            return min5PcmFile.exists() && min5PcmFile.length() > 16000
        }

        // v2.4.139: Validate using .info file. If valid and durations match, skip all decoding.
        val validInfo = if (mp4DurationMs > 0) validatePcmWithInfo(fullPcmFile, fullInfoFile, mp4DurationMs) else null
        if (validInfo != null && min5PcmFile.exists() && min5PcmFile.length() > 16000) {
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] PCM valid per .info for $episodeId (mp4=${validInfo.mp4DurationMs}ms, pcm=${validInfo.pcmDurationMs}ms). Skipping decode.\n")
            return true
        }

        // v2.4.148: If we still don't know the MP4 duration but both PCM files already exist,
        // keep them. Deleting valid, large PCM just because MediaExtractor returned 0 is the root
        // cause of repeated full-PCM regeneration and 100MB+ file accumulation.
        if (mp4DurationMs <= 0 && fullPcmFile.exists() && fullPcmFile.length() > 1024 * 100 &&
            min5PcmFile.exists() && min5PcmFile.length() > 16000) {
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] keeping existing PCM for $episodeId because MediaExtractor duration is 0 but files exist (full=${fullPcmFile.length()}, min5=${min5PcmFile.length()}).\n")
            return true
        }

        // Not valid — delete old PCM and .info files to force regeneration.
        if (fullPcmFile.exists()) fullPcmFile.delete()
        if (min5PcmFile.exists()) min5PcmFile.delete()
        if (fullInfoFile.exists()) fullInfoFile.delete()
        if (min5InfoFile.exists()) min5InfoFile.delete()
        precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] PCM invalid, .info mismatch, or unknown MP4 duration for $episodeId (mp4DurationMs=$mp4DurationMs). Deleting and regenerating.\n")

        // Decode full PCM from scratch.
        precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] decoding full PCM for $episodeId (audioUrl=$audioUrl, mp4DurationMs=$mp4DurationMs)\n")
        val decoded = decodeAudioToPcm(context, episodeId, pcmCacheDir, audioUrl, mp4DurationMs)
        if (decoded == null || !decoded.exists() || decoded.length() <= 16000) {
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] FAILED to decode full PCM for $episodeId\n")
            return false
        }

        // v2.4.139: Clamp PCM to expected length and compute duration.
        val clampedFile = clampPcmToExpectedLength(decoded, mp4DurationMs, episodeId)
        val pcmDurationMs = clampedFile.length() / (16000 * 2) * 1000
        precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] full PCM generated: ${clampedFile.name} (${clampedFile.length()} bytes, ${pcmDurationMs}ms=${pcmDurationMs / 60000} min, expected ${mp4DurationMs}ms=${mp4DurationMs / 60000} min)\n")

        // v2.4.139: If PCM is still significantly shorter than MP4, we cannot trust it. Fail loudly.
        if (mp4DurationMs > 0 && pcmDurationMs < mp4DurationMs * 0.85) {
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] ERROR: PCM duration (${pcmDurationMs}ms) still < 85% of MP4 duration (${mp4DurationMs}ms). Keeping file but marking unreliable.\n")
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
                precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] 5-min PCM generated: ${min5PcmFile.name} (${min5PcmFile.length()} bytes)\n")
            } catch (e: Exception) {
                precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] failed to create 5-min PCM: ${e.message}\n")
            }
        } else {
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] WARNING: mp4DurationMs is 0, skipping .info write for $episodeId to avoid invalid info files.\n")
        }

        // v2.4.149: Enforce the user-configurable PCM cache size limit after generating a full PCM.
        val settings = com.radio.app.models.AppSettings.getInstance(context)
        val maxBytes = (settings.pcmCacheMaxSizeGb * 1024L * 1024L * 1024L).toLong()
        cleanupPcmCache(context, maxSizeBytes = maxBytes)

        return fullPcmFile.exists() && fullPcmFile.length() > 16000
    }

    /**
     * v2.4.148: Limit total PCM cache size. Called after generating a full PCM.
     * Keeps the most-recently-used files up to maxSizeBytes and deletes the rest.
     * Default limit: 1 GB. Always preserves files touched within the last 10 minutes.
     */
    fun cleanupPcmCache(context: Context, maxSizeBytes: Long = 1024L * 1024L * 1024L, minAgeMs: Long = 10 * 60 * 1000L) {
        try {
            val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(context)
            val files = pcmCacheDir.listFiles()?.filter { it.isFile && it.name.endsWith(".pcm") } ?: return
            if (files.isEmpty()) return
            val totalBytes = files.sumOf { it.length() }
            if (totalBytes <= maxSizeBytes) return
            val now = System.currentTimeMillis()
            // Sort by lastModified ascending (oldest first).
            val sorted = files.sortedBy { it.lastModified() }
            var deleted = 0L
            var deletedCount = 0
            for (f in sorted) {
                if (totalBytes - deleted <= maxSizeBytes) break
                // Never delete files touched in the last 10 minutes (current generation).
                if (now - f.lastModified() < minAgeMs) continue
                val len = f.length()
                val infoFile = File(pcmCacheDir, f.name.replace(".pcm", ".info"))
                if (f.delete()) {
                    deleted += len
                    deletedCount++
                    if (infoFile.exists()) infoFile.delete()
                }
            }
            if (deletedCount > 0) {
                val precacheLog = java.io.File(context.getExternalFilesDir(null), "RadioApp/logs/precache/precache.log")
                precacheLog.parentFile?.mkdirs()
                val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
                precacheLog.appendText("[$ts] cleanupPcmCache: deleted $deletedCount files, freed ${deleted / 1024 / 1024}MB (remaining ${(totalBytes - deleted) / 1024 / 1024}MB)\n")
            }
        } catch (_: Exception) {}
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
     * @param progressCallback (progressPermille 0-1000, elapsedMs, etaMs)
     * @return SegmentAnalysisResult containing segments, engine name and timing
     */
    fun analyzeEpisode(
        context: Context,
        episodeId: String,
        durationMs: Long,
        audioUrl: String? = null,
        progressCallback: ((Int, Long, Long) -> Unit)? = null
    ): SegmentAnalysisResult {
        // v2.4.115: Initialize file-based logger for VAD diagnostics
        setLogContext(context)

        // v2.4.156: Track this thread so the notification cancel action can interrupt it.
        currentAnalysisThread = Thread.currentThread()
        vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] analyzeEpisode: started on thread ${currentAnalysisThread?.name}")

        try {
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
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] analyzeEpisode: WARNING could not determine MP4 duration for $episodeId")
        }

        // v2.4.138: Validate full PCM using .info file duration metadata.
        val fullPcmFile = File(pcmCacheDir, "${episodeId}_full.pcm")
        val fullInfoFile = File(pcmCacheDir, "${episodeId}_full.info")
        var pcmFile: File? = null

        if (fullPcmFile.exists() && fullPcmFile.length() > 16000) {
            val validInfo = validatePcmWithInfo(fullPcmFile, fullInfoFile, mp4DurationMs)
            if (validInfo != null) {
                vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] analyzeEpisode: full PCM valid per .info for $episodeId (mp4=${validInfo.mp4DurationMs}ms, pcm=${validInfo.pcmDurationMs}ms)")
                pcmFile = fullPcmFile
            } else {
                vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] analyzeEpisode: full PCM .info mismatch for $episodeId. Deleting and regenerating.")
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
                    vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] analyzeEpisode: 5-min PCM valid per .info for $episodeId")
                    pcmFile = min5PcmFile
                } else {
                    vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] analyzeEpisode: 5-min PCM .info mismatch for $episodeId. Deleting.")
                    min5PcmFile.delete()
                    min5InfoFile.delete()
                }
            }
        }

        // v2.4.148: Wrap the caller's progress callback so decode reports 0-200‰
        // and analysis reports 201-1000‰. This fixes the "stuck at 0%" problem where
        // decodeAudioToPcm took several minutes without any progress update.
        // v2.4.152: Use 0-1000 permille so the progress bar/text can move in 0.1% steps.
        // Even when integer percent stays the same (e.g. "34%"), the UI still advances.
        var decodeProgressPct = -1
        var analysisProgressPermille = -1
        var lastDecodeForwardTimeMs = 0L
        var lastAnalysisForwardTimeMs = 0L
        val wrappedProgressCallback: ((Int, Long, Long) -> Unit)? = progressCallback?.let { original ->
            { analysisPermille, elapsedMs, etaMs ->
                val nowMs = System.currentTimeMillis()
                // Analysis phase occupies the 200-1000‰ range.
                val mapped = 200 + (analysisPermille * 800 / 1000).coerceIn(0, 800)
                if (mapped != analysisProgressPermille || nowMs - lastAnalysisForwardTimeMs >= 1000) {
                    analysisProgressPermille = mapped
                    lastAnalysisForwardTimeMs = nowMs
                    original(mapped, elapsedMs, etaMs)
                }
            }
        }

        // v2.4.138: If still no valid PCM, decode from scratch.
        if (pcmFile == null) {
            val decodeStartMs = System.currentTimeMillis()
            val decodeCallback: ((Int) -> Unit)? = progressCallback?.let { original ->
                { pct ->
                    val nowMs = System.currentTimeMillis()
                    if (pct != decodeProgressPct || nowMs - lastDecodeForwardTimeMs >= 1000) {
                        decodeProgressPct = pct
                        lastDecodeForwardTimeMs = nowMs
                        val decodeElapsedMs = nowMs - decodeStartMs
                        // pct is 0-20% of total work (0-200‰); ETA is still in decode-time domain.
                        val decodeEtaMs = if (pct > 0) (decodeElapsedMs * (20 - pct) / pct) else 0L
                        // Map the 0-20% decode range to 0-200‰ of the overall progress.
                        original(pct * 10, decodeElapsedMs, decodeEtaMs)
                    }
                }
            }
            pcmFile = decodeAudioToPcm(context, episodeId, pcmCacheDir, audioUrl, mp4DurationMs, progressCallback = decodeCallback)
            if (pcmFile == null) {
                Log.e(TAG, "analyzeEpisode: no PCM file found for $episodeId (audioUrl=$audioUrl)")
                throw RuntimeException("无法获取音频数据: 未找到PCM缓存文件，本地无缓存音频，URL解码失败(可能需要联网)")
            }
            // Guard against excessive length, then record duration in .info.
            pcmFile = clampPcmToExpectedLength(pcmFile, mp4DurationMs, episodeId)
            val pcmDurationMs = pcmFile.length() / (16000 * 2) * 1000
            writePcmInfo(fullInfoFile, mp4DurationMs, pcmDurationMs, 16000, 1)
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] analyzeEpisode: decoded fresh PCM for $episodeId (${pcmFile.length()} bytes, pcmDuration=${pcmDurationMs}ms)")
        }

        return analyzePcmFile(context, pcmFile, durationMs, wrappedProgressCallback)
        } finally {
            currentAnalysisThread = null
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] analyzeEpisode: cleared analysis thread reference")
        }
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
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] clampPcmToExpectedLength: trimming ${pcmFile.length()} bytes -> $trimBytes bytes (duration=${durationMs}ms, expected=$expectedBytes)")
            Log.w(TAG, "[${com.radio.app.RadioApplication.appVersionTag()}] PCM too long for $episodeId: ${pcmFile.length()} > $maxAllowedBytes, trimming to $trimBytes")
            try {
                java.io.RandomAccessFile(pcmFile, "rw").use { raf ->
                    raf.setLength(trimBytes)
                }
            } catch (e: Exception) {
                vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] clampPcmToExpectedLength: failed to trim: ${e.message}")
                Log.e(TAG, "[${com.radio.app.RadioApplication.appVersionTag()}] failed to trim PCM", e)
            }
        }
        return pcmFile
    }

    /**
     * v2.4.96: Decode cached audio file to PCM using MediaExtractor + MediaCodec.
     * v2.4.99: Find audio file by URL-based filename (not episode ID).
     * This is a fallback when no pre-decoded PCM file exists.
     */
    private fun decodeAudioToPcm(
        context: Context,
        episodeId: String,
        outputDir: File,
        audioUrl: String? = null,
        durationMs: Long = 0,
        startOffsetBytes: Long = 0,
        maxDecodeDurationMs: Long = 0,
        progressCallback: ((Int) -> Unit)? = null
    ): File? {
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
                    return decodeUrlToPcm(audioUrl, File(outputDir, "${episodeId}_full.pcm"), durationMs, maxDecodeDurationMs, progressCallback)
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
                vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeAudioToPcm: APPEND MODE — seeking to ${seekToUs}us (${decodedSeconds}s), existing PCM=$startOffsetBytes bytes")
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
            // v2.4.149: Raise max PCM size to 600MB (~5.2 hours at 16kHz mono 16-bit) so 3-4 hour
            // episodes are not silently truncated to "only tens of MB".
            val maxPcmBytes = 600 * 1024 * 1024  // 600MB max

            // v2.4.148: Estimate expected PCM size for progress reporting.
            // 16kHz mono 16-bit = 32000 bytes/sec. Use duration if known, else rough file-size estimate.
            val expectedPcmBytes = when {
                durationMs > 0 -> (durationMs * 16000L * 2L / 1000L).coerceAtLeast(1L)
                audioFile.length() > 1024 -> (audioFile.length() * 10).coerceAtLeast(1L)
                else -> 1L
            }
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeAudioToPcm: START episode=$episodeId, audioFile=${audioFile.name} (${audioFile.length()} bytes), durationMs=$durationMs, expectedPcmBytes=$expectedPcmBytes, maxPcmBytes=$maxPcmBytes, maxDecodeDurationMs=$maxDecodeDurationMs")
            var lastReportedDecodeProgress = -1
            var lastDecodeProgressTimeMs = 0L
            fun reportDecodeProgressIfNeeded() {
                if (progressCallback == null) return
                val nowMs = System.currentTimeMillis()
                val pct = (totalPcmBytes * 20 / expectedPcmBytes).toInt().coerceIn(0, 20)
                // v2.4.151: Also report once per second so elapsed/ETA keep refreshing.
                if (pct != lastReportedDecodeProgress || nowMs - lastDecodeProgressTimeMs >= 1000) {
                    lastReportedDecodeProgress = pct
                    lastDecodeProgressTimeMs = nowMs
                    try { progressCallback.invoke(pct) } catch (_: Exception) {}
                }
            }

            // v2.4.132: Resampling state — continuous phase tracking (same as SubtitleGeneratorService)
            var resamplePhase = 0.0
            var lastSample: Short = 0
            // needResample is re-evaluated after FORMAT_CHANGED
            var needResample = sampleRate != 16000 || channelCount != 1
            // fos already created above (append or overwrite mode)

            try {
                var inputEos = false
                while (true) {
                    checkCancelled()
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
                            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeAudioToPcm: FORMAT_CHANGED: ${sampleRate}Hz ${channelCount}ch (was container format)")
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
                                reportDecodeProgressIfNeeded()
                            } else {
                                fos.write(chunk)
                                totalPcmBytes += chunk.size
                                reportDecodeProgressIfNeeded()
                            }

                            if (totalPcmBytes >= maxPcmBytes) {
                                vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeAudioToPcm: STOPPED by maxPcmBytes limit ($maxPcmBytes bytes) for $episodeId")
                                Log.i(TAG, "decodeAudioToPcm: reached max size limit ($maxPcmBytes bytes)")
                                break
                            }
                            // v2.4.148: Stop early if caller only needs a prefix (e.g. 5-min preview).
                            if (maxDecodeDurationMs > 0) {
                                val decodedMs = totalPcmBytes * 1000L / (16000L * 2L)
                                if (decodedMs >= maxDecodeDurationMs) {
                                    vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeAudioToPcm: STOPPED by maxDecodeDurationMs ($maxDecodeDurationMs ms) for $episodeId")
                                    Log.i(TAG, "decodeAudioToPcm: reached maxDecodeDurationMs ($maxDecodeDurationMs ms)")
                                    break
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outputBufferIndex, false)
                        if (bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeAudioToPcm: END_OF_STREAM reached for $episodeId")
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

            val finalDurationMs = totalPcmBytes * 1000L / (16000L * 2L)
            val completenessRatio = if (expectedPcmBytes > 1) totalPcmBytes.toDouble() / expectedPcmBytes.toDouble() else 1.0
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeAudioToPcm: DONE episode=$episodeId, totalPcmBytes=$totalPcmBytes, finalDurationMs=$finalDurationMs, expectedPcmBytes=$expectedPcmBytes, completeness=${String.format("%.2f", completenessRatio)}")
            Log.i(TAG, "decodeAudioToPcm: decoded $totalPcmBytes bytes ($finalDurationMs ms) to ${outputFile.name} (final rate: ${sampleRate}Hz ${channelCount}ch -> 16kHz mono)")
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
    private fun decodeUrlToPcm(
        audioUrl: String,
        outputFile: File,
        durationMs: Long = 0,
        maxDecodeDurationMs: Long = 0,
        progressCallback: ((Int) -> Unit)? = null
    ): File? {
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
            // v2.4.149: Raise max PCM size to 600MB (~5.2 hours) for long episodes.
            val maxPcmBytes = 600 * 1024 * 1024
            val needResample = sampleRate != 16000 || channelCount != 1
            val fos = java.io.FileOutputStream(outputFile)

            // v2.4.148: Progress reporting for URL decode (streaming fallback).
            val expectedPcmBytes = if (durationMs > 0) (durationMs * 16000L * 2L / 1000L).coerceAtLeast(1L) else 1L
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeUrlToPcm: START url=$audioUrl, durationMs=$durationMs, expectedPcmBytes=$expectedPcmBytes, maxPcmBytes=$maxPcmBytes, maxDecodeDurationMs=$maxDecodeDurationMs")
            var lastReportedDecodeProgress = -1
            var lastDecodeProgressTimeMs = 0L
            fun reportDecodeProgressIfNeeded() {
                if (progressCallback == null) return
                val nowMs = System.currentTimeMillis()
                val pct = (totalPcmBytes * 20 / expectedPcmBytes).toInt().coerceIn(0, 20)
                // v2.4.151: Also report once per second so elapsed/ETA keep refreshing.
                if (pct != lastReportedDecodeProgress || nowMs - lastDecodeProgressTimeMs >= 1000) {
                    lastReportedDecodeProgress = pct
                    lastDecodeProgressTimeMs = nowMs
                    try { progressCallback.invoke(pct) } catch (_: Exception) {}
                }
            }

            try {
                var inputEos = false
                while (true) {
                    checkCancelled()
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
                                reportDecodeProgressIfNeeded()
                            } else {
                                fos.write(chunk)
                                totalPcmBytes += chunk.size
                                reportDecodeProgressIfNeeded()
                            }

                            if (totalPcmBytes >= maxPcmBytes) {
                                vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeUrlToPcm: STOPPED by maxPcmBytes limit ($maxPcmBytes bytes)")
                                Log.i(TAG, "decodeUrlToPcm: reached max size limit ($maxPcmBytes bytes)")
                                break
                            }
                            // v2.4.148: Stop early if caller only needs a prefix.
                            if (maxDecodeDurationMs > 0) {
                                val decodedMs = totalPcmBytes * 1000L / (16000L * 2L)
                                if (decodedMs >= maxDecodeDurationMs) {
                                    vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeUrlToPcm: STOPPED by maxDecodeDurationMs ($maxDecodeDurationMs ms)")
                                    Log.i(TAG, "decodeUrlToPcm: reached maxDecodeDurationMs ($maxDecodeDurationMs ms)")
                                    break
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outputBufferIndex, false)
                        if (bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeUrlToPcm: END_OF_STREAM reached")
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

            val finalDurationMs = totalPcmBytes * 1000L / (16000L * 2L)
            val completenessRatio = if (expectedPcmBytes > 1) totalPcmBytes.toDouble() / expectedPcmBytes.toDouble() else 1.0
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeUrlToPcm: DONE totalPcmBytes=$totalPcmBytes, finalDurationMs=$finalDurationMs, expectedPcmBytes=$expectedPcmBytes, completeness=${String.format("%.2f", completenessRatio)}")
            Log.i(TAG, "decodeUrlToPcm: decoded $totalPcmBytes bytes ($finalDurationMs ms) to ${outputFile.name}")
            // v2.4.138: .info file with duration metadata is now written by the caller.
            return if (totalPcmBytes > 16000) outputFile else null
        } catch (e: Exception) {
            Log.e(TAG, "decodeUrlToPcm failed: ${e.message}")
            return null
        }
    }

    /**
     * v2.4.162: Analyze PCM audio file using Silero + YAMNet cascade.
     *
     * Pipeline:
     * 1. Silero VAD coarse segmentation (whole audio, 512-sample chunks).
     * 2. YAMNet classification on speech intervals (0.975s window, 0.5s hop), producing
     *    interval-local sub-segments so that adjacent speech intervals separated by silence
     *    are no longer merged into a single segment.
     * 3. Sparse YAMNet sampling in silence intervals (every 3s, 1.2s window, center 0.975s
     *    fed to YAMNet). Silence intervals are first marked as silence, then split by hits.
     * 4. Collect and sort all interval-local sub-segments, merge adjacent same-type segments,
     *    then post-process.
     *
     * @param context Application context
     * @param pcmFile 16kHz mono 16-bit PCM file
     * @param durationMs Total duration in milliseconds
     * @param progressCallback (progressPermille 0-1000, elapsedMs, etaMs)
     * @return SegmentAnalysisResult containing segments, engine name and timing
     */
    fun analyzePcmFile(
        context: Context,
        pcmFile: File,
        durationMs: Long,
        progressCallback: ((Int, Long, Long) -> Unit)? = null
    ): SegmentAnalysisResult {
        if (!pcmFile.exists() || pcmFile.length() < 16000) {
            Log.w(TAG, "PCM file too small or missing: ${pcmFile.absolutePath}")
            throw RuntimeException("PCM文件太小或不存在: ${pcmFile.name} (${pcmFile.length()} bytes)")
        }

        // v2.4.161: Reset object-level counters
        synchronized(this) {
            yamnetCallCount = 0
            vadRunCount = 0
        }

        if (!NativeLibLoader.ensureLoaded(context)) {
            Log.e(TAG, "Native libraries not loaded.")
            throw RuntimeException("音频分段运行库未加载，请重新下载运行库")
        }

        val modelDir = getModelDir(context)
        if (!isYamnetInstalled(modelDir) || !isSileroVadInstalled(modelDir)) {
            Log.w(TAG, "Models not installed. YAMNet=${isYamnetInstalled(modelDir)}, VAD=${isSileroVadInstalled(modelDir)}")
            throw RuntimeException("模型未安装: YAMNet=${isYamnetInstalled(modelDir)}, VAD=${isSileroVadInstalled(modelDir)}")
        }

        val yamnetInterpreter = loadYamnetModel(File(modelDir, "yamnet.tflite"))
        val vadModel = loadSileroVad(File(modelDir, "silero_vad.onnx"))

        try {
            val samples = readPcmAsFloats(pcmFile)
            if (samples.size < YAMNET_WINDOW_SAMPLES) {
                Log.w(TAG, "PCM too short: ${samples.size} samples")
                throw RuntimeException("PCM数据太短: ${samples.size} 样本 (需要至少 $YAMNET_WINDOW_SAMPLES)")
            }

            val totalSamples = samples.size
            val totalDurationMs = (totalSamples * 1000L / YAMNET_SAMPLE_RATE)
            val outputDurationMs = if (durationMs > 0) durationMs else totalDurationMs
            val analysisStartTimeMs = System.currentTimeMillis()
            var lastReportedProgress = -1
            var lastCallbackTimeMs = 0L
            var lastFriendlyLogTimeMs = 0L

            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] 开始音频分段分析：总时长 ${formatDurationMs(totalDurationMs)}，共 $totalSamples 样本")

            fun reportProgress(mapped: Int, force: Boolean = false) {
                val nowMs = System.currentTimeMillis()
                if (mapped != lastReportedProgress || force || nowMs - lastCallbackTimeMs >= 1000) {
                    lastReportedProgress = mapped
                    lastCallbackTimeMs = nowMs
                    val elapsedMs = nowMs - analysisStartTimeMs
                    val etaMs = if (mapped > 0) (elapsedMs * (1000 - mapped) / mapped) else 0L
                    try { progressCallback?.invoke(mapped, elapsedMs, etaMs) } catch (_: Exception) { }
                }
                if ((mapped > 0 && mapped % 50 == 0 && nowMs - lastFriendlyLogTimeMs > 10_000)
                    || (nowMs - lastFriendlyLogTimeMs > 30_000)) {
                    lastFriendlyLogTimeMs = nowMs
                    val processedMs = (mapped * totalDurationMs / 1000L).coerceAtMost(totalDurationMs)
                    val elapsedMs = nowMs - analysisStartTimeMs
                    val etaMs = if (mapped > 0) (elapsedMs * (1000 - mapped) / mapped) else 0L
                    val progressPercent = String.format(java.util.Locale.US, "%.1f", mapped / 10f)
                    vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] 音频分段进度 ${progressPercent}%：已处理 ${formatDurationMs(processedMs)} / ${formatDurationMs(totalDurationMs)}，已用 ${formatDurationMs(elapsedMs)}，预计剩余 ${formatDurationMs(etaMs)}")
                }
            }

            // ===== Phase 1: Silero VAD coarse segmentation (0-300‰) =====
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] Phase 1/3: Silero VAD coarse segmentation")
            val (speechRanges, silenceRanges) = runSileroVadIntervals(samples, vadModel) { progressPermille ->
                reportProgress((progressPermille * 300 / 1000).coerceIn(0, 300))
            }

            // ===== Phase 2: YAMNet classification of speech intervals (300-900‰) =====
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] Phase 2/3: YAMNet classification of ${speechRanges.size} speech intervals")
            val intervalSegments = mutableListOf<VoiceSegment>()
            val totalSpeechDurationMs = speechRanges.sumOf { it.durationMs }
            var processedSpeechMs = 0L

            for (range in speechRanges) {
                checkCancelled()
                val subSegments = classifySpeechInterval(samples, range, yamnetInterpreter)
                intervalSegments.addAll(subSegments)
                processedSpeechMs += range.durationMs
                val mapped = if (totalSpeechDurationMs > 0) {
                    300 + (processedSpeechMs * 600 / totalSpeechDurationMs).toInt()
                } else 300
                reportProgress(mapped.coerceIn(300, 900))
            }

            // ===== Phase 3: Sparse YAMNet sampling in silence intervals (900-1000‰) =====
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] Phase 3/3: sparse YAMNet sampling of ${silenceRanges.size} silence intervals")
            for ((index, range) in silenceRanges.withIndex()) {
                checkCancelled()
                val subSegments = sampleSilenceInterval(samples, range, yamnetInterpreter)
                intervalSegments.addAll(subSegments)
                val mapped = 900 + ((index + 1) * 100 / silenceRanges.size.coerceAtLeast(1)).coerceIn(0, 100)
                reportProgress(mapped.coerceIn(900, 1000))
            }

            // Sort and merge adjacent same-type segments, then post-process
            intervalSegments.sortBy { it.start }
            var segments = mergeAdjacentSameTypeSegments(intervalSegments).toMutableList()
            segments = postProcessSegments(segments).toMutableList()

            // Ensure the full duration is covered
            if (segments.isEmpty()) {
                segments = mutableListOf(VoiceSegment().apply {
                    start = 0L; end = outputDurationMs; hasVoice = true; label = "干货"; isSimulated = false
                })
            }

            val totalElapsedMs = System.currentTimeMillis() - analysisStartTimeMs
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] 音频分段分析完成：共 ${segments.size} 段（干货 ${segments.count { it.label == "干货" }} 段，水货 ${segments.count { it.label == "水货" }} 段，静音 ${segments.count { it.label == "静音" }} 段），分析模式=Silero+YAMNet 折中级联，总耗时 ${formatDurationMs(totalElapsedMs)}")
            reportProgress(1000, force = true)

            return SegmentAnalysisResult(
                segments = segments,
                engineName = "Silero+YAMNet",
                processingTimeMs = totalElapsedMs,
                audioDurationMs = totalDurationMs
            )

        } finally {
            yamnetInterpreter.close()
            try { vadModel.session.close() } catch (_: Exception) {}
        }
    }

    // ===== YAMNet (TFLite) =====

    private fun loadYamnetModel(modelFile: File): Interpreter {
        try {
            // v2.4.129: Log model file info to file log for diagnostics
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] loadYamnetModel: file=${modelFile.name}, size=${modelFile.length()} bytes, exists=${modelFile.exists()}")
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
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] loadYamnetModel: loaded successfully. input=${inputShape.contentToString()} ($inputType), output=${outputShape.contentToString()} ($outputType)")
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
    // v2.4.130: Store YAMNet input shape from model for correct tensor creation
    private var yamnetInputShape: IntArray = intArrayOf(1, 15600)

    private data class YamnetResult(
        val speech: Float,
        val narration: Float,
        val singing: Float,
        val music: Float,
        val instrumental: Float,
        val popMusic: Float,
        val jingle: Float,
        val song: Float,
        val backgroundMusic: Float,
        val themeMusic: Float,
        val silence: Float,
        // v2.4.161: Aggregated scores for cascade decision
        val voiceSum: Float,
        val bgMusicSum: Float,
        // v2.4.143: Raw max logit. If all logits are near 0, every sigmoid is ~0.5 and the model
        // is effectively unresponsive. If some other class has a high logit while speech/music/
        // silence are 0.5, the model is still working — just not detecting those categories.
        val maxRawScore: Float
    )

    private fun classifyWithYamnet(
        interpreter: Interpreter,
        samples: FloatArray
    ): YamnetResult {
        try {
            // v2.4.130: Use model's actual input shape instead of hardcoded [1, 15600].
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
                vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] classifyWithYamnet #$yamnetCallCount: input samples=${samples.size}, nonZero=$nonZero, avgAbs=$avgAbs, first10=${samples.take(10).joinToString(",")}")
            }

            // Output: [1, 521] float
            val outputBuffer = TensorBuffer.createFixedSize(
                intArrayOf(1, YAMNET_NUM_CLASSES),
                org.tensorflow.lite.DataType.FLOAT32
            )

            interpreter.run(inputBuffer.buffer, outputBuffer.buffer)
            checkCancelled()
            val scores = outputBuffer.floatArray

            // Find max raw logit for every frame (used by malfunction detection)
            var maxRawScore = -Float.MAX_VALUE
            for (i in scores.indices) {
                if (scores[i] > maxRawScore) maxRawScore = scores[i]
            }

            // v2.4.161: Log raw output scores for first 3 calls
            if (yamnetCallCount <= 3) {
                val rawSpeech = scores.getOrElse(YAMNET_IDX_SPEECH) { 0f }
                val rawNarration = scores.getOrElse(YAMNET_IDX_NARRATION) { 0f }
                val rawSinging = scores.getOrElse(YAMNET_IDX_SINGING) { 0f }
                val rawSilence = scores.getOrElse(YAMNET_IDX_SILENCE) { 0f }
                val rawMusic = scores.getOrElse(YAMNET_IDX_MUSIC) { 0f }
                var maxIdx = 0
                var maxScore = -Float.MAX_VALUE
                for (i in scores.indices) {
                    if (scores[i] > maxScore) { maxScore = scores[i]; maxIdx = i }
                }
                vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] classifyWithYamnet #$yamnetCallCount: raw scores: speech[$YAMNET_IDX_SPEECH]=$rawSpeech, narration[$YAMNET_IDX_NARRATION]=$rawNarration, singing[$YAMNET_IDX_SINGING]=$rawSinging, silence[$YAMNET_IDX_SILENCE]=$rawSilence, music[$YAMNET_IDX_MUSIC]=$rawMusic")
                vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] classifyWithYamnet #$yamnetCallCount: max score=$maxScore at idx=$maxIdx, all zeros=${scores.all { it == 0f }}, output size=${scores.size}")
            }

            // v2.4.161: Compute sigmoid probabilities for all relevant classes
            val speechProb = sigmoid(scores.getOrElse(YAMNET_IDX_SPEECH) { 0f })
            val narrationProb = sigmoid(scores.getOrElse(YAMNET_IDX_NARRATION) { 0f })
            val singingProb = sigmoid(scores.getOrElse(YAMNET_IDX_SINGING) { 0f })
            val musicProb = sigmoid(scores.getOrElse(YAMNET_IDX_MUSIC) { 0f })
            val instrumentalProb = sigmoid(scores.getOrElse(YAMNET_IDX_INSTRUMENTAL) { 0f })
            val popMusicProb = sigmoid(scores.getOrElse(YAMNET_IDX_POP_MUSIC) { 0f })
            val songProb = sigmoid(scores.getOrElse(YAMNET_IDX_SONG) { 0f })
            val bgMusicProb = sigmoid(scores.getOrElse(YAMNET_IDX_BACKGROUND_MUSIC) { 0f })
            val themeMusicProb = sigmoid(scores.getOrElse(YAMNET_IDX_THEME_MUSIC) { 0f })
            val jingleProb = sigmoid(scores.getOrElse(YAMNET_IDX_JINGLE) { 0f })
            val silenceProb = sigmoid(scores.getOrElse(YAMNET_IDX_SILENCE) { 0f })

            val voiceSum = speechProb + narrationProb + singingProb
            val bgMusicSum = musicProb + instrumentalProb + popMusicProb + jingleProb + songProb + bgMusicProb + themeMusicProb

            return YamnetResult(
                speech = speechProb,
                narration = narrationProb,
                singing = singingProb,
                music = musicProb,
                instrumental = instrumentalProb,
                popMusic = popMusicProb,
                jingle = jingleProb,
                song = songProb,
                backgroundMusic = bgMusicProb,
                themeMusic = themeMusicProb,
                silence = silenceProb,
                voiceSum = voiceSum,
                bgMusicSum = bgMusicSum,
                maxRawScore = maxRawScore
            )
        } catch (e: Exception) {
            Log.e(TAG, "YAMNet classification failed: ${e.message}")
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] classifyWithYamnet FAILED: ${e.javaClass.simpleName}: ${e.message}")
            return YamnetResult(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
    }

    private fun sigmoid(x: Float): Float {
        val exp = kotlin.math.exp(-x.toDouble())
        return (1.0 / (1.0 + exp)).toFloat()
    }

    // v2.4.161: Time range for coarse VAD segmentation
    private data class TimeRange(val startMs: Long, val endMs: Long) {
        val durationMs: Long get() = endMs - startMs
    }

    /**
     * v2.4.165: Apply YAMNet decision rules using calibrated probabilities.
     *
     * YAMNet outputs logits; after sigmoid, an inactive class has probability ~0.5.
     * Using raw sigmoid values directly makes sum-based thresholds meaningless because
     * every inactive class contributes 0.5. We therefore subtract 0.5 to obtain an
     * "activation strength" relative to the neutral baseline.
     *
     * Balanced strategy:
     * - Clear voice activity -> dry (host speech / dialogue).
     * - Voice with notable music -> water (ads / accompanied songs).
     * - Strong singing -> dry (preserve host singing).
     * - Weak ambiguous frames default to silence, not dry.
     */
    private fun classifyYamnetScores(yamnet: YamnetResult): FrameType {
        // Calibrated activation strengths (0.5 = neutral/unactivated)
        val calSpeech = yamnet.speech - 0.5f
        val calNarration = yamnet.narration - 0.5f
        val calSinging = yamnet.singing - 0.5f
        val calMusic = yamnet.music - 0.5f
        val calInstrumental = yamnet.instrumental - 0.5f
        val calPopMusic = yamnet.popMusic - 0.5f
        val calJingle = yamnet.jingle - 0.5f
        val calSong = yamnet.song - 0.5f
        val calBgMusic = yamnet.backgroundMusic - 0.5f
        val calThemeMusic = yamnet.themeMusic - 0.5f
        val calSilence = yamnet.silence - 0.5f

        val calVoice = maxOf(0f, calSpeech) + maxOf(0f, calNarration) + maxOf(0f, calSinging)
        val calMusicSum = maxOf(0f, calMusic) + maxOf(0f, calInstrumental) + maxOf(0f, calPopMusic) +
                maxOf(0f, calJingle) + maxOf(0f, calSong) + maxOf(0f, calBgMusic) + maxOf(0f, calThemeMusic)

        // Silence first: if nothing meaningful is present, mark silence.
        if (calVoice < 0.05f && calMusicSum < 0.05f && calSilence > 0.05f) {
            return FrameType.SILENCE
        }

        // Strong singing protection: keep clear singing (even with accompaniment) as dry.
        // Require singing to be strongly activated and to dominate overall voice activity.
        if (calSinging > 0.10f && calSinging >= calVoice * SINGING_FORCE_THRESHOLD) {
            return FrameType.DRY
        }

        // Significant voice activity: decide dry vs water based on music presence.
        if (calVoice >= VOICE_SUM_THRESHOLD) {
            return if (calMusicSum > 0.05f && calMusicSum >= calVoice * BG_MUSIC_SUM_THRESHOLD) {
                // Voice with strong music -> ads / accompanied songs -> water
                FrameType.WATER
            } else {
                // Voice dominates -> host speech / dialogue -> dry
                FrameType.DRY
            }
        }

        // Weak voice but clear music: if music clearly outweighs voice, treat as water.
        if (calMusicSum > 0.05f && calMusicSum > calVoice * 2.0f) {
            return FrameType.WATER
        }

        // Weak ambiguous frames default to silence (avoid false-positive dry).
        return FrameType.SILENCE
    }

    /**
     * v2.4.161: Run Silero VAD over the whole audio to produce coarse speech/silence intervals.
     */
    private fun runSileroVadIntervals(
        samples: FloatArray,
        vadModel: VadModelInfo,
        onProgress: ((Int) -> Unit)? = null
    ): Pair<List<TimeRange>, List<TimeRange>> {
        val chunkDurationMs = VAD_FRAME_SIZE * 1000L / YAMNET_SAMPLE_RATE
        val minSpeechChunks = (VAD_MIN_SPEECH_DURATION_MS / chunkDurationMs).toInt().coerceAtLeast(1)
        val minSilenceChunks = (VAD_MIN_SILENCE_DURATION_MS / chunkDurationMs).toInt().coerceAtLeast(1)

        val probs = mutableListOf<Float>()
        var vadState = FloatBuffer.wrap(FloatArray(vadModel.stateSize))
        var vadContext = FloatArray(VAD_CONTEXT_SIZE) { 0f }
        var pos = 0
        var reportPos = 0
        val totalSamples = samples.size

        while (pos + VAD_FRAME_SIZE <= totalSamples) {
            val chunk = samples.copyOfRange(pos, pos + VAD_FRAME_SIZE)
            val (prob, newState, newContext) = runSileroVad(vadModel, chunk, vadContext, vadState)
            vadState = newState
            vadContext = newContext
            probs.add(prob)
            pos += VAD_FRAME_SIZE

            if (onProgress != null && pos >= reportPos) {
                onProgress((pos.toLong() * 1000L / totalSamples).toInt().coerceIn(0, 1000))
                reportPos += YAMNET_SAMPLE_RATE * 5
            }
        }

        // Initial thresholding into speech/silence chunks
        val rawSegments = mutableListOf<Pair<Boolean, Int>>()
        for (prob in probs) {
            val isSpeech = prob >= VAD_THRESHOLD
            if (rawSegments.isEmpty() || rawSegments.last().first != isSpeech) {
                rawSegments.add(isSpeech to 1)
            } else {
                rawSegments[rawSegments.size - 1] = isSpeech to (rawSegments.last().second + 1)
            }
        }

        // Apply min speech/silence duration rules
        var changed = true
        while (changed) {
            changed = false
            for (i in rawSegments.indices) {
                val (isSpeech, count) = rawSegments[i]
                if (isSpeech && count < minSpeechChunks) {
                    if (rawSegments.size == 1) {
                        rawSegments[0] = false to count
                        changed = true
                        break
                    }
                    if (i == 0) {
                        rawSegments[1] = false to (rawSegments[1].second + count)
                        rawSegments.removeAt(0)
                    } else if (i == rawSegments.size - 1) {
                        rawSegments[i - 1] = false to (rawSegments[i - 1].second + count)
                        rawSegments.removeAt(i)
                    } else {
                        rawSegments[i - 1] = false to (rawSegments[i - 1].second + count)
                        rawSegments.removeAt(i)
                    }
                    changed = true
                    break
                } else if (!isSpeech && count < minSilenceChunks) {
                    if (rawSegments.size == 1) {
                        rawSegments[0] = true to count
                        changed = true
                        break
                    }
                    if (i == 0) {
                        rawSegments[1] = true to (rawSegments[1].second + count)
                        rawSegments.removeAt(0)
                    } else if (i == rawSegments.size - 1) {
                        rawSegments[i - 1] = true to (rawSegments[i - 1].second + count)
                        rawSegments.removeAt(i)
                    } else {
                        rawSegments[i - 1] = true to (rawSegments[i - 1].second + count)
                        rawSegments.removeAt(i)
                    }
                    changed = true
                    break
                }
            }
        }

        // Build time ranges
        val speechRanges = mutableListOf<TimeRange>()
        val silenceRanges = mutableListOf<TimeRange>()
        var chunkStart = 0
        for ((isSpeech, count) in rawSegments) {
            val startMs = chunkStart * chunkDurationMs
            val endMs = (chunkStart + count) * chunkDurationMs
            if (isSpeech) {
                speechRanges.add(TimeRange(startMs, endMs))
            } else {
                silenceRanges.add(TimeRange(startMs, endMs))
            }
            chunkStart += count
        }

        vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] VAD coarse segmentation: ${speechRanges.size} speech ranges (${formatDurationMs(speechRanges.sumOf { it.durationMs })}) and ${silenceRanges.size} silence ranges (${formatDurationMs(silenceRanges.sumOf { it.durationMs })})")
        return speechRanges to silenceRanges
    }

    /**
     * v2.4.165: Classify a speech interval densely with YAMNet (0.975s window, 0.5s hop),
     * merging consecutive same-type windows into interval-local sub-segments.
     *
     * To reduce over-segmentation caused by single noisy YAMNet windows, we apply a
     * small majority-vote smoothing over the last 3 windows before deciding to switch
     * segment type. This keeps real content boundaries while removing flicker.
     */
    private fun classifySpeechInterval(
        samples: FloatArray,
        range: TimeRange,
        yamnetInterpreter: Interpreter
    ): List<VoiceSegment> {
        val startSample = (range.startMs * YAMNET_SAMPLE_RATE / 1000L).toInt()
        val endSample = (range.endMs * YAMNET_SAMPLE_RATE / 1000L).toInt()
        val intervalEndSample = endSample.coerceAtMost(samples.size)
        val intervalEndMs = (intervalEndSample.toLong() * 1000L / YAMNET_SAMPLE_RATE)

        // Interval too short for a full YAMNet window: VAD already marked it as speech, default to dry.
        if (intervalEndSample - startSample < YAMNET_WINDOW_SAMPLES) {
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] Speech interval ${formatDurationMs(range.startMs)}-${formatDurationMs(range.endMs)}: too short for YAMNet (${intervalEndSample - startSample} samples), defaulting to dry")
            return listOf(createSegment(range.startMs, range.endMs, FrameType.DRY))
        }

        val segments = mutableListOf<VoiceSegment>()
        var currentType: FrameType? = null
        var currentStartMs = range.startMs

        fun flushSegment(upToMs: Long) {
            val t = currentType ?: return
            if (upToMs > currentStartMs) {
                segments.add(createSegment(currentStartMs, upToMs, t))
            }
        }

        // Smoothing buffer: majority vote over recent windows to suppress flicker.
        val recentTypes = mutableListOf<FrameType>()

        var pos = startSample.coerceIn(0, samples.size)
        while (pos + YAMNET_WINDOW_SAMPLES <= intervalEndSample && pos + YAMNET_WINDOW_SAMPLES <= samples.size) {
            val window = samples.copyOfRange(pos, pos + YAMNET_WINDOW_SAMPLES)
            val windowStartMs = (pos.toLong() * 1000L / YAMNET_SAMPLE_RATE)
            val yamnet = classifyWithYamnet(yamnetInterpreter, window)
            val type = classifyYamnetScores(yamnet)

            recentTypes.add(type)
            if (recentTypes.size > 3) recentTypes.removeAt(0)
            val stableType = recentTypes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: type

            if (currentType == null) {
                currentType = stableType
                // Anchor the first segment at the interval start to avoid tiny gaps.
                currentStartMs = range.startMs
            } else if (stableType != currentType) {
                flushSegment(windowStartMs)
                currentType = stableType
                currentStartMs = windowStartMs
            }

            pos += YAMNET_SPEECH_HOP_SAMPLES
        }

        // Flush final segment up to the interval end so the interval is fully covered.
        flushSegment(intervalEndMs)

        vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] Speech interval ${formatDurationMs(range.startMs)}-${formatDurationMs(range.endMs)}: produced ${segments.size} sub-segment(s)")
        return segments
    }

    /**
     * v2.4.164: Sparse YAMNet sampling in a silence interval to find missed content.
     * Returns interval-local sub-segments: a default silence segment covering the interval,
     * split by dry/water sub-segments around each hit. The hit type is determined by the
     * same calibrated classifier used for speech intervals, so ads/songs in silence are
     * marked as water rather than dry.
     */
    private fun sampleSilenceInterval(
        samples: FloatArray,
        range: TimeRange,
        yamnetInterpreter: Interpreter
    ): List<VoiceSegment> {
        var currentMs = range.startMs
        var consecutiveDryHits = 0
        var consecutiveWaterHits = 0
        val dryHitRanges = mutableListOf<TimeRange>()
        val waterHitRanges = mutableListOf<TimeRange>()

        while (currentMs + SILENCE_SAMPLE_WINDOW_MS <= range.endMs) {
            val centerMs = currentMs + SILENCE_SAMPLE_WINDOW_MS / 2
            val centerSample = (centerMs * YAMNET_SAMPLE_RATE / 1000L).toInt()
            val halfWindow = SILENCE_SAMPLE_WINDOW_SAMPLES / 2
            val windowStartSample = centerSample - halfWindow
            // v2.4.162 CRITICAL FIX: feed YAMNet the center 15600 samples of the 19200-sample window.
            val yamnetStartSample = windowStartSample + (SILENCE_SAMPLE_WINDOW_SAMPLES - YAMNET_WINDOW_SAMPLES) / 2
            val yamnetEndSample = yamnetStartSample + YAMNET_WINDOW_SAMPLES

            if (yamnetStartSample < 0 || yamnetEndSample > samples.size) {
                currentMs += SILENCE_SAMPLE_INTERVAL_MS
                continue
            }

            val window = samples.copyOfRange(yamnetStartSample, yamnetEndSample)
            val yamnet = classifyWithYamnet(yamnetInterpreter, window)
            val type = classifyYamnetScores(yamnet)

            if (type == FrameType.DRY || type == FrameType.WATER) {
                val spreadStartMs = (centerMs - SILENCE_SAMPLE_HALF_SPREAD_MS).coerceAtLeast(range.startMs)
                val spreadEndMs = (centerMs + SILENCE_SAMPLE_HALF_SPREAD_MS).coerceAtMost(range.endMs)
                if (type == FrameType.DRY) {
                    consecutiveDryHits++
                    consecutiveWaterHits = 0
                    dryHitRanges.add(TimeRange(spreadStartMs, spreadEndMs))
                } else {
                    consecutiveWaterHits++
                    consecutiveDryHits = 0
                    waterHitRanges.add(TimeRange(spreadStartMs, spreadEndMs))
                }

                // If we see two consecutive same-type hits, fill the whole gap with that type.
                if (consecutiveDryHits >= 2) {
                    vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] Silence interval ${formatDurationMs(range.startMs)}-${formatDurationMs(range.endMs)}: two consecutive dry YAMNet hits -> mark whole as dry")
                    return listOf(createSegment(range.startMs, range.endMs, FrameType.DRY))
                }
                if (consecutiveWaterHits >= 2) {
                    vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] Silence interval ${formatDurationMs(range.startMs)}-${formatDurationMs(range.endMs)}: two consecutive water YAMNet hits -> mark whole as water")
                    return listOf(createSegment(range.startMs, range.endMs, FrameType.WATER))
                }
            } else {
                consecutiveDryHits = 0
                consecutiveWaterHits = 0
            }
            currentMs += SILENCE_SAMPLE_INTERVAL_MS
        }

        // No hits: the whole silence interval remains silent.
        if (dryHitRanges.isEmpty() && waterHitRanges.isEmpty()) {
            return listOf(createSegment(range.startMs, range.endMs, FrameType.SILENCE))
        }

        // Merge dry/water hit ranges and build segments that fully cover the interval.
        val allHits = (dryHitRanges + waterHitRanges).sortedBy { it.startMs }
        val segments = mutableListOf<VoiceSegment>()
        var cursorMs = range.startMs
        for (hit in allHits) {
            if (hit.startMs > cursorMs) {
                segments.add(createSegment(cursorMs, hit.startMs, FrameType.SILENCE))
            }
            val hitType = if (hit in dryHitRanges) FrameType.DRY else FrameType.WATER
            segments.add(createSegment(hit.startMs, hit.endMs, hitType))
            cursorMs = maxOf(cursorMs, hit.endMs)
        }
        if (cursorMs < range.endMs) {
            segments.add(createSegment(cursorMs, range.endMs, FrameType.SILENCE))
        }

        vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] Silence interval ${formatDurationMs(range.startMs)}-${formatDurationMs(range.endMs)}: ${dryHitRanges.size} dry hit(s), ${waterHitRanges.size} water hit(s) -> ${segments.size} sub-segment(s)")
        return segments
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
                vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] runSileroVad #$vadRunCount: input size=${vadInput.size} (context=$VAD_CONTEXT_SIZE + chunk=${chunk.size}), nonZero=$chunkNonZero, avgAbs=$chunkAvgAbs, first10=${vadInput.take(10).joinToString(",")}")
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
            checkCancelled()

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

    // ===== Segment merging =====

    /**
     * v2.4.156: Smooth frame-level labels with a small majority-vote window.
     * This removes isolated 1-frame misclassifications without swallowing
     * genuine short dry segments into long water segments.
     */
    private fun smoothFrameTypes(frames: List<FrameResult>): List<FrameResult> {
        if (frames.size < 5) return frames
        val windowSize = 5
        val half = windowSize / 2
        return frames.mapIndexed { i, fr ->
            val start = maxOf(0, i - half)
            val end = minOf(frames.size, i + half + 1)
            val window = frames.subList(start, end)
            val counts = mutableMapOf<FrameType, Int>()
            for (w in window) {
                counts[w.type] = counts.getOrDefault(w.type, 0) + 1
            }
            val majority = counts.maxByOrNull { it.value }?.key ?: fr.type
            fr.copy(type = majority)
        }
    }

    private fun mergeFramesIntoSegments(
        frames: List<FrameResult>,
        durationMs: Long
    ): MutableList<VoiceSegment> {
        if (frames.isEmpty()) return mutableListOf()

        // v2.4.161: Smooth before merging so single-frame flips don't create tiny segments.
        val smoothed = smoothFrameTypes(frames)

        val segments = mutableListOf<VoiceSegment>()
        var segStart = smoothed[0].timestampMs
        var segType = smoothed[0].type

        for (i in 1 until smoothed.size) {
            val frame = smoothed[i]
            if (frame.type != segType) {
                val segEnd = frame.timestampMs
                segments.add(createSegment(segStart, segEnd, segType))
                segStart = frame.timestampMs
                segType = frame.type
            }
        }
        val lastEnd = durationMs
        segments.add(createSegment(segStart, lastEnd, segType))
        return segments
    }

    /**
     * v2.4.162: Merge adjacent (or 1ms-overlapping due to rounding) segments of the same label.
     * Called after collecting interval-local sub-segments from all speech/silence ranges.
     */
    private fun mergeAdjacentSameTypeSegments(segments: List<VoiceSegment>): List<VoiceSegment> {
        if (segments.isEmpty()) return emptyList()
        val sorted = segments.sortedBy { it.start }
        val merged = mutableListOf<VoiceSegment>()
        var current = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (current.label == next.label && next.start <= current.end + 1) {
                current.end = maxOf(current.end, next.end)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    /**
     * v2.4.161: Post-process segments:
     * 1. Merge same-type overlapping/adjacent segments.
     * 2. Merge/remove fragments shorter than MIN_FRAGMENT_MS.
     * 3. Absorb short (<1s) water segments inside/between dry segments into dry.
     * 4. Merge dry segments separated by less than MAX_DRY_GAP_MS.
     */
    private fun postProcessSegments(segments: List<VoiceSegment>): List<VoiceSegment> {
        if (segments.isEmpty()) return segments

        // Pass 1: merge same-type overlapping/adjacent segments
        val sorted = segments.sortedBy { it.start }.map { it.copy() }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            for (i in 0 until sorted.size - 1) {
                val curr = sorted[i]
                val next = sorted[i + 1]
                if (curr.label == next.label && next.start <= curr.end + 1) {
                    curr.end = maxOf(curr.end, next.end)
                    sorted.removeAt(i + 1)
                    changed = true
                    break
                }
            }
        }

        // Pass 2: merge fragments shorter than MIN_FRAGMENT_MS
        changed = true
        while (changed) {
            changed = false
            for (i in sorted.indices) {
                val seg = sorted[i]
                val duration = seg.end - seg.start
                if (duration < MIN_FRAGMENT_MS) {
                    val prev = sorted.getOrNull(i - 1)
                    val next = sorted.getOrNull(i + 1)
                    val target = when {
                        prev != null && next != null && prev.label == seg.label -> prev
                        prev != null && next != null && next.label == seg.label -> next
                        prev != null && next != null -> if ((prev.end - prev.start) >= (next.end - next.start)) prev else next
                        prev != null -> prev
                        next != null -> next
                        else -> null
                    }
                    if (target != null) {
                        target.start = minOf(target.start, seg.start)
                        target.end = maxOf(target.end, seg.end)
                        sorted.removeAt(i)
                        changed = true
                        break
                    }
                }
            }
        }

        // Pass 3: absorb short pure-music (<1s) gaps inside/between dry segments into dry
        changed = true
        while (changed) {
            changed = false
            for (i in sorted.indices) {
                val seg = sorted[i]
                if (seg.label == "水货" && seg.end - seg.start < MAX_PURE_MUSIC_GAP_MS) {
                    val prev = sorted.getOrNull(i - 1)
                    val next = sorted.getOrNull(i + 1)
                    if (prev?.label == "干货" && next?.label == "干货") {
                        prev.end = maxOf(prev.end, next.end)
                        sorted.removeAt(i + 1)
                        sorted.removeAt(i)
                        changed = true
                        break
                    } else if (prev?.label == "干货") {
                        prev.end = maxOf(prev.end, seg.end)
                        sorted.removeAt(i)
                        changed = true
                        break
                    } else if (next?.label == "干货") {
                        next.start = minOf(next.start, seg.start)
                        next.end = maxOf(next.end, seg.end)
                        sorted.removeAt(i)
                        changed = true
                        break
                    }
                }
            }
        }

        // Pass 4: merge dry segments with gap < MAX_DRY_GAP_MS
        changed = true
        while (changed) {
            changed = false
            for (i in 0 until sorted.size - 1) {
                val curr = sorted[i]
                val next = sorted[i + 1]
                if (curr.label == "干货" && next.label == "干货" && next.start - curr.end < MAX_DRY_GAP_MS) {
                    curr.end = next.end
                    sorted.removeAt(i + 1)
                    changed = true
                    break
                }
            }
        }

        return sorted
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
        // v2.4.143: Raw max logit from the full 521-class output. Needed to distinguish
        // "model is dead (all logits ~0)" from "speech/music/silence classes happen to be 0.5".
        val yamnetMaxRawScore: Float,
        val rmsEnergy: Float
    )

    private class AiSession(val session: Any) {
        fun close() {
            try { session.javaClass.getMethod("close").invoke(session) } catch (_: Exception) {}
        }
    }
}
