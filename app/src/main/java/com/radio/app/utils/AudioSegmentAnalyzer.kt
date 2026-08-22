package com.radio.app.utils

import android.content.Context
import android.util.Log
import com.radio.app.models.VoiceSegment
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock

/**
 * v2.4.168: Audio-based AI segment analyzer using Silero + YAMNet cascade.
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
/**
 * v3.1.80: 选择首选的音频解码器，优先使用MTK硬件解码器以缩短PCM生成时间。
 *
 * 策略：
 * 1. 直接尝试创建已知的MTK硬件解码器（c2.mtk.aac.decoder / omx.mtk.aac.decoder）
 * 2. 通过MediaCodecList查找其他非Google硬件解码器
 * 3. 回退到null（调用者使用createDecoderByType默认选择）
 *
 * @param mime 音频MIME类型（如"audio/mp4a-latm"）
 * @param logger 可选的文件日志回调，将选择过程写入文件日志
 */
fun selectPreferredAudioDecoder(mime: String, logger: ((String) -> Unit)? = null): String? {
    val tag = "selectPreferredAudioDecoder"
    val logMsg = { msg: String ->
        android.util.Log.i("AudioSegmentAnalyzer", "$tag: $msg")
        logger?.invoke("$tag: $msg")
    }

    // 策略1：直接尝试创建已知的MTK硬件解码器
    // 天玑8100上MTK AAC硬件解码器名称为c2.mtk.aac.decoder
    // 旧版OMX名称为omx.mtk.aac.decoder
    val mtkDecoderNames = listOf("c2.mtk.aac.decoder", "omx.mtk.aac.decoder")
    for (name in mtkDecoderNames) {
        try {
            val testDecoder = android.media.MediaCodec.createByCodecName(name)
            testDecoder.release()
            logMsg("selected MTK HW decoder by direct name: $name")
            return name
        } catch (e: Exception) {
            logMsg("MTK decoder $name not available: ${e.message}")
        }
    }

    // 策略2：使用ALL_CODECS查找所有解码器（包括隐藏的secure变体）
    // REGULAR_CODECS可能不包含某些隐藏的硬件解码器
    val codecList = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
    val decoderInfos = mutableListOf<android.media.MediaCodecInfo>()

    for (info in codecList.codecInfos) {
        if (info.isEncoder) continue
        for (type in info.supportedTypes) {
            if (type.equals(mime, ignoreCase = true)) {
                decoderInfos.add(info)
                break
            }
        }
    }

    // 列出所有解码器用于诊断
    decoderInfos.forEach { info ->
        logMsg("available decoder: ${info.name} (isSoftwareOnly=${tryOrNull { info.isSoftwareOnly() } ?: "N/A"})")
    }

    // 策略2a：尝试直接创建每个非Google解码器，跳过isSoftwareOnly检查
    // 因为某些MTK硬件解码器可能错误地报告isSoftwareOnly=true
    // 并且MediaCodecList可能包含隐藏的硬件解码器（如c2.mtk.aac.decoder.secure）
    for (info in decoderInfos) {
        val name = info.name.lowercase()
        if (name.contains("c2.android.") || name.contains("omx.google.")) {
            continue  // 跳过Google软件解码器
        }
        try {
            val testDecoder = android.media.MediaCodec.createByCodecName(info.name)
            testDecoder.release()
            logMsg("selected HW decoder by direct creation: ${info.name}")
            return info.name
        } catch (e: Exception) {
            logMsg("decoder ${info.name} creation failed: ${e.message}")
        }
    }

    // 策略2b：兜底策略——尝试所有可用解码器（包括Google），优先选非软件解码器
    // 按isSoftwareOnly排序，非软件解码器优先
    val sortedInfos = decoderInfos.sortedBy { info ->
        try { if (info.isSoftwareOnly()) 1 else 0 } catch (_: Exception) { 1 }
    }
    for (info in sortedInfos) {
        try {
            val testDecoder = android.media.MediaCodec.createByCodecName(info.name)
            testDecoder.release()
            logMsg("selected decoder by direct creation (fallback): ${info.name}")
            return info.name
        } catch (e: Exception) {
            logMsg("decoder ${info.name} creation failed: ${e.message}")
        }
    }

    // 回退到null（使用系统默认）
    val fallbackName = decoderInfos.firstOrNull()?.name
    logMsg("no decoder could be created directly, fallback to default (first=$fallbackName)")
    return null
}

/** 辅助函数：try-catch包装，返回null或结果 */
private fun <T> tryOrNull(block: () -> T): T? {
    return try { block() } catch (_: Exception) { null }
}

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

    // v2.4.171: A dedicated cancel flag is more reliable than Thread.interrupt() alone.
    // Some loops/Native calls may swallow or delay interrupts, so we check this flag
    // explicitly in every heavy loop to stop work immediately after the user clicks cancel.
    @Volatile
    private var analysisCancelled = false

    // v2.4.186: Global lock so only one heavy analyzeEpisode runs at a time. Pre-segmentation
    // patrols use tryLock to skip when another analysis is already in progress; manual
    // segmentation from PlayerActivity blocks briefly after cancelling the previous run.
    private val analysisLock = ReentrantLock()

    // v3.1.41: PCM生成锁，确保同时只生成一个PCM文件
    private val pcmGenerateLock = java.util.concurrent.locks.ReentrantLock()

    // v3.1.103: VAD语音帧占比（用于classifyYamnetScores中speech_prob锁定0.30）
    @Volatile
    private var vadSpeechRatio: Float = 0f

    /**
     * Interrupt the currently running audio-segment analysis (decode + classify).
     * Called from the notification cancel action or when starting a new segment task.
     */
    fun cancelCurrentAnalysis(): Boolean {
        analysisCancelled = true
        // v3.1.110: 记录取消来源的完整调用栈到指纹日志，用于排查非用户触发的取消
        val stackTrace = Thread.currentThread().stackTrace
        val caller = stackTrace.getOrNull(2)?.let { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: "unknown"
        Log.e("AudioSegmentAnalyzer", "cancelCurrentAnalysis called by $caller")
        // 记录完整调用栈到logcat
        val sw = java.io.StringWriter()
        val pw = java.io.PrintWriter(sw)
        pw.println("cancelCurrentAnalysis called by $caller")
        for (element in stackTrace.take(20)) {
            pw.println("\tat ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
        }
        Log.e("AudioSegmentAnalyzer", sw.toString())
        // 写入指纹日志文件
        writeFingerprintLog("cancelCurrentAnalysis called by $caller")
        val t = currentAnalysisThread ?: return false
        return if (!t.isInterrupted) {
            t.interrupt()
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] AudioSegmentAnalyzer: cancelled analysis thread (flag + interrupt)")
            true
        } else {
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] AudioSegmentAnalyzer: cancel flag set, thread already interrupted")
            true
        }
    }

    /**
     * v2.4.171: Reset the cancel flag at the beginning of a new analysis.
     * Without this a subsequent run would exit immediately if the previous run was cancelled.
     */
    fun resetCancellation() {
        analysisCancelled = false
    }

    /**
     * v2.4.171: Expose the cancel state so UI can distinguish a user cancellation from
     * a genuine analysis error.
     */
    @JvmStatic
    fun isAnalysisCancelled(): Boolean = analysisCancelled

    // v3.1.108: 公开getter/setter，使generateJiuAiTingSegments也能设置当前分析线程
    // 原来只有analyzeEpisode设置了currentAnalysisThread，三层分段流程未设置，
    // 导致cancelCurrentAnalysis()只能设analysisCancelled标志，不能中断线程
    @JvmStatic
    fun getCurrentAnalysisThread(): Thread? = currentAnalysisThread

    @JvmStatic
    fun setCurrentAnalysisThread(t: Thread?) {
        currentAnalysisThread = t
    }

    private fun checkCancelled() {
        if (analysisCancelled || Thread.currentThread().isInterrupted) {
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
            // v3.1.77: 懒初始化logFile，确保即使setLogContext未调用也能写入日志
            if (logFile == null && logContext != null) {
                val baseLogDir = com.radio.app.RadioApplication.getLogDir(logContext!!)
                val logDir = File(baseLogDir, "audio_segment")
                if (!logDir.exists()) logDir.mkdirs()
                logFile = File(logDir, "audio_segment.log")
            }
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            logFile?.let { f ->
                f.appendText("[$timestamp] $msg\n")
            }
        } catch (_: Exception) {}
    }

    // v3.1.110: 将调试日志写入指纹日志文件（logs/fingerprint/fingerprint_segment.log）
    // 用于跨模块排查时统一日志位置，不依赖logcat
    private fun writeFingerprintLog(msg: String) {
        try {
            val ctx = logContext ?: return
            val logDir = File(com.radio.app.RadioApplication.getLogDir(ctx), "fingerprint")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = File(logDir, "fingerprint_segment.log")
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            logFile.appendText("[$ts] $msg\n")
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
    // v3.1.141: 改为internal，供SegmentGenerator等同一模块内的类访问
    internal const val YAMNET_SAMPLE_RATE = 16000
    internal const val YAMNET_WINDOW_SAMPLES = 15600
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

    // v2.4.161: Sparse sampling in silence intervals
    private const val YAMNET_SPEECH_HOP_SAMPLES = 32000

    // v2.4.161: Sparse sampling in silence intervals
    private const val SILENCE_SAMPLE_INTERVAL_MS = 3000L
    private const val SILENCE_SAMPLE_WINDOW_MS = 1200L
    private const val SILENCE_SAMPLE_WINDOW_SAMPLES = 19200
    private const val SILENCE_SAMPLE_HALF_SPREAD_MS = 1800L

    // Silero VAD: 512 samples per chunk (32ms at 16kHz)
    private const val VAD_FRAME_SIZE = 512
    // v2.4.142: Silero VAD expects 64 samples of previous audio as context prepended to each 512-sample chunk.
    private const val VAD_CONTEXT_SIZE = 64
    private const val VAD_THRESHOLD = 0.30f
    private const val VAD_MIN_SPEECH_DURATION_MS = 3000L
    private const val VAD_MIN_SILENCE_DURATION_MS = 3500L

    // v2.4.168/v2.4.173: YAMNet decision thresholds
    // Host speech is the primary dry signal. Music must be prominent relative to voice
    // before a frame is treated as water (ad / song), so light BGM under talking stays dry.
    private const val VOICE_SUM_THRESHOLD = 0.10f
    private const val BG_MUSIC_SUM_THRESHOLD = 0.90f
    private const val SINGING_RATIO_THRESHOLD = 0.35f
    private const val SINGING_FORCE_THRESHOLD = 0.25f
    private const val MUSIC_AD_THRESHOLD = 0.25f

    // v2.4.171: Post-processing thresholds
    // v3.1.112: MIN_FRAGMENT_MS从3s降到1.5s，避免短干货片段（主持人短句1.5-3s）被误吸收
    // 3s导致大量短干货被合并到相邻水段，经Pass3转干→Pass4合并为一个大段，是干货间丢失的主因
    private const val MIN_FRAGMENT_MS = 1500L
    // v3.1.112: 孤立水分片段 < 1s 才归入模糊段，避免过度吞并中间干货
    private const val MAX_PURE_MUSIC_GAP_MS = 1000L
    // v3.1.112: 干货合并间隔从3s降到500ms，仅合并极短间隔的相邻干货
    // 3s导致Pass3转换水段→干后层叠触发Pass4合并，分段数从180→72
    private const val MAX_DRY_GAP_MS = 500L
    // v2.4.173: Merge consecutive/nearby water segments separated by short silence.
    // Ad breaks and song blocks often have 5-10s pauses between them.
    // v3.1.98: 水分合并间隔从10s放宽到15s
    private const val MAX_WATER_GAP_MS = 15000L

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

    /**
     * v3.1.83: 加载YAMNet TFLite解释器，供SegmentGenerator在优化三层架构中使用。
     * 调用方负责在完成后调用interpreter.close()释放资源。
     * 返回null表示加载失败（模型未安装或native库未就绪）。
     */
    fun loadYamnetInterpreter(context: Context): Interpreter? {
        if (!NativeLibLoader.ensureLoaded(context)) {
            Log.e(TAG, "loadYamnetInterpreter: Native libraries not loaded.")
            return null
        }
        val modelDir = getModelDir(context)
        if (!isYamnetInstalled(modelDir)) {
            Log.w(TAG, "loadYamnetInterpreter: YAMNet模型未安装")
            return null
        }
        return try {
            // v3.1.146-fix: 同时设置类级字段，供classifyPcmIntervalInner等使用类级Interpreter
            // 不close旧的Interpreter（可能因推理挂死处于不可用状态），直接放弃
            val modelFile = File(modelDir, "yamnet.tflite")
            yamnetModelFile = modelFile
            val interp = loadYamnetModel(modelFile)
            currentYamnetInterpreter = interp
            interp
        } catch (e: Throwable) {
            Log.e(TAG, "loadYamnetInterpreter failed: ${e.message}")
            null
        }
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

    // v3.1.73: 写入PCM生成日志到独立文件 /sdcard/RadioApp/logs/pcm_gen/pcm_gen.log
    private fun writePcmGenLog(context: Context, episodeId: String, audioUrl: String?, totalTimeMs: Long, pcmSizeBytes: Long, success: Boolean, detail: String? = null) {
        try {
            val logDir = java.io.File(com.radio.app.RadioApplication.getLogDir(context), "pcm_gen")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = java.io.File(logDir, "pcm_gen.log")
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            val url = audioUrl?.substringAfterLast("/") ?: "null"
            val detailStr = if (detail != null) " detail=$detail" else ""
            val result = if (success) "SUCCESS" else "FAILED"
            logFile.appendText("[$ts][${com.radio.app.RadioApplication.appVersionTag()}] ep=$episodeId url=$url result=$result totalTimeMs=$totalTimeMs pcmSizeBytes=$pcmSizeBytes${detailStr}\n")
            Log.i(TAG, "[PcmGen] ep=$episodeId result=$result totalTimeMs=${totalTimeMs}ms pcmSizeBytes=$pcmSizeBytes")
            // 限制文件大小到500KB
            if (logFile.length() > 500_000) {
                val lines = logFile.readLines()
                val keep = lines.takeLast(500)
                logFile.writeText(keep.joinToString("\n") + "\n")
            }
        } catch (_: Exception) {}
    }

    /**
     * v2.4.138: Determine whether cached PCM is valid by comparing its info-file durations
     * with the source MP4 duration. Returns the matching PcmInfo if valid, null otherwise.
     * v3.1.41: 添加详细日志记录不匹配原因，用于排查真正原因。
     * v3.1.43: 详细记录每个校验步骤的结果，精确指出哪个字段不达标。
     */
    private fun validatePcmWithInfo(
        pcmFile: File,
        infoFile: File,
        currentMp4DurationMs: Long,
        toleranceRatio: Double = 0.2
    ): PcmInfo? {
        val pcmName = pcmFile.name
        if (!pcmFile.exists() || pcmFile.length() <= 16000) {
            val msg = "validatePcmWithInfo: $pcmName 不存在或太小 (${pcmFile.length()} bytes)"
            Log.w(TAG, msg)
            FileLogUtils.logInfoFile(msg)
            return null
        }
        val info = readPcmInfo(infoFile)
        if (info == null) {
            val msg = "validatePcmWithInfo: ${infoFile.name} 不存在或读取失败 -> 需要重新生成info文件"
            Log.w(TAG, msg)
            FileLogUtils.logInfoFile(msg)
            return null
        }
        // v3.1.43: 精确指出isValid()中哪个字段不达标
        // v3.1.44: 所有info文件校验日志同时写入FileLogUtils
        if (info.version < REQUIRED_PCM_VERSION) {
            val msg = "validatePcmWithInfo: ${infoFile.name} version=${info.version} < REQUIRED=$REQUIRED_PCM_VERSION -> 版本过旧，需要重新生成"
            Log.w(TAG, msg)
            FileLogUtils.logInfoFile(msg)
            return null
        }
        if (info.mp4DurationMs <= 0) {
            val msg = "validatePcmWithInfo: ${infoFile.name} mp4DurationMs=${info.mp4DurationMs} <= 0 -> info文件中的mp4DurationMs无效，需要重新生成"
            Log.w(TAG, msg)
            FileLogUtils.logInfoFile(msg)
            return null
        }
        if (info.pcmDurationMs <= 0) {
            val msg = "validatePcmWithInfo: ${infoFile.name} pcmDurationMs=${info.pcmDurationMs} <= 0 -> info文件中的pcmDurationMs无效，需要重新生成"
            Log.w(TAG, msg)
            FileLogUtils.logInfoFile(msg)
            return null
        }
        // v3.1.41-fix: 当PCM文件较大（>50MB）时，直接跳过mp4DurationMs校验，
        // 因为MediaExtractor读取的时长可能因文件重新下载或编解码器差异而不同，
        // 但PCM文件本身是有效的，不应因mp4DurationMs不一致而删除。
        if (pcmFile.length() > 50 * 1024 * 1024L) {
            if (currentMp4DurationMs > 0 && info.mp4DurationMs > 0) {
                val diff = kotlin.math.abs(info.mp4DurationMs - currentMp4DurationMs)
                val ratio = diff.toDouble() / currentMp4DurationMs
                if (ratio > 0.03) {
                    val msg = "validatePcmWithInfo: mp4DurationMs 不匹配但PCM文件较大(${pcmFile.length()/1024/1024}MB)，跳过校验 - info=${info.mp4DurationMs}ms, current=${currentMp4DurationMs}ms, ratio=${String.format(java.util.Locale.US, "%.4f", ratio)}"
                    Log.w(TAG, msg)
                    FileLogUtils.logInfoFile(msg)
                }
            }
            // PCM文件较大时，信任info文件中的pcmDurationMs
            if (info.pcmDurationMs > 0) {
                val msg = "validatePcmWithInfo: 大文件校验通过 - $pcmName version=${info.version} mp4DurationMs=${info.mp4DurationMs}ms pcmDurationMs=${info.pcmDurationMs}ms pcmSize=${pcmFile.length()}"
                Log.d(TAG, msg)
                FileLogUtils.logInfoFile(msg)
                return info
            }
        }
        // v3.1.41: 记录详细不匹配日志，排查真正原因
        if (currentMp4DurationMs > 0) {
            val diff = kotlin.math.abs(info.mp4DurationMs - currentMp4DurationMs)
            val ratio = diff.toDouble() / currentMp4DurationMs
            if (ratio > 0.03) {
                val msg = "validatePcmWithInfo: mp4DurationMs 不匹配 - info=${info.mp4DurationMs}ms, current=${currentMp4DurationMs}ms, diff=${diff}ms, ratio=${String.format(java.util.Locale.US, "%.4f", ratio)}, file=${pcmName}"
                Log.w(TAG, msg)
                FileLogUtils.logInfoFile(msg)
                // v3.1.41-fix: 提高容差至30%，避免因MediaExtractor读取时长波动导致已正常使用的info文件被判定不匹配
                if (ratio > 0.30) {
                    val msg2 = "validatePcmWithInfo: mp4DurationMs 差异超过30%(${String.format(java.util.Locale.US, "%.1f", ratio * 100)}%)，info文件不匹配，需要重新生成PCM"
                    Log.w(TAG, msg2)
                    FileLogUtils.logInfoFile(msg2)
                    return null
                }
            }
        } else {
            val msg = "validatePcmWithInfo: currentMp4DurationMs=0(MediaExtractor或API均未提供时长)，跳过mp4DurationMs校验，使用info文件中的mp4DurationMs=${info.mp4DurationMs}ms"
            Log.d(TAG, msg)
            FileLogUtils.logInfoFile(msg)
        }
        // PCM duration must be within tolerance of MP4 duration.
        val expectedDurationMs = if (currentMp4DurationMs > 0) currentMp4DurationMs else info.mp4DurationMs
        if (expectedDurationMs <= 0) {
            val msg = "validatePcmWithInfo: expectedDurationMs <= 0 (currentMp4=$currentMp4DurationMs, info.mp4=${info.mp4DurationMs}) -> 无法确定预期时长，跳过校验"
            Log.w(TAG, msg)
            FileLogUtils.logInfoFile(msg)
            return null
        }
        val delta = kotlin.math.abs(info.pcmDurationMs - expectedDurationMs)
        if (delta > expectedDurationMs * toleranceRatio) {
            val msg = "validatePcmWithInfo: pcmDurationMs 不匹配 - pcm=${info.pcmDurationMs}ms, expected=${expectedDurationMs}ms, delta=${delta}ms, tolerance=${(expectedDurationMs * toleranceRatio).toLong()}ms (${String.format(java.util.Locale.US, "%.1f", toleranceRatio * 100)}%), file=${pcmName}"
            Log.w(TAG, msg)
            FileLogUtils.logInfoFile(msg)
            return null
        }
        val msg = "validatePcmWithInfo: 校验通过 - $pcmName version=${info.version} mp4DurationMs=${info.mp4DurationMs}ms pcmDurationMs=${info.pcmDurationMs}ms pcmSize=${pcmFile.length()}"
        Log.d(TAG, msg)
        FileLogUtils.logInfoFile(msg)
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
     *
     * v2.4.188: Use the centralized episodes cache dir from RadioApplication so the
     * analyzer looks in the same directory that RadioPlaybackService downloads into
     * (/sdcard/RadioApp/episodes/). Previously it hard-coded getExternalFilesDir(null)/
     * RadioApp/episodes, which could be a different path and caused "audio file may not
     * be cached" failures even though the patrol saw the file as cached.
     */
    fun getCachedAudioFile(context: Context, episodeId: String, audioUrl: String?): java.io.File? {
        val episodesDir = com.radio.app.RadioApplication.getEpisodesCacheDir(context)
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

    // v3.1.73: 下载音频文件到缓存目录，用于替代流式解码
    private fun downloadAudioFile(url: String, targetFile: java.io.File): Boolean {
        val downloadStartTime = System.currentTimeMillis()
        try {
            Log.i(TAG, "downloadAudioFile: downloading $url to ${targetFile.absolutePath}")
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 180000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
            connection.setRequestProperty("Referer", "https://www.hndt.com/")
            connection.connect()
            if (connection.responseCode != 200) {
                Log.e(TAG, "downloadAudioFile: HTTP ${connection.responseCode} for $url")
                connection.disconnect()
                return false
            }
            val expectedSize = connection.contentLengthLong
            val input = connection.inputStream
            val output = java.io.FileOutputStream(targetFile)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead = 0L
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                totalRead += bytesRead
            }
            output.close()
            input.close()
            connection.disconnect()

            // 检查下载是否完整
            if (expectedSize > 0 && totalRead < expectedSize * 0.99) {
                Log.e(TAG, "downloadAudioFile: incomplete download: $totalRead / $expectedSize bytes")
                targetFile.delete()
                return false
            }

            val elapsedMs = System.currentTimeMillis() - downloadStartTime
            Log.i(TAG, "downloadAudioFile: downloaded ${targetFile.length()} bytes to ${targetFile.name} in ${elapsedMs}ms")
            return true
        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - downloadStartTime
            Log.e(TAG, "downloadAudioFile: failed after ${elapsedMs}ms: ${e.message}")
            if (targetFile.exists()) {
                try { targetFile.delete() } catch (_: Exception) {}
            }
            return false
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
            // v3.1.41: 记录MediaExtractor读取的时长，用于排查时长不匹配问题
            Log.i(TAG, "getMp4DurationMs: ${audioFile.name} -> ${durationMs}ms (${durationMs / 60000}min), fileSize=${audioFile.length()}")
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
        progressCallback: ((Int) -> Unit)? = null
    ): Boolean {
        // v3.1.73: PCM生成锁，等待锁而非跳过，避免因锁竞争导致PCM生成失败
        pcmGenerateLock.lock()
        try {
            // v3.1.41: 保存并设置当前线程引用，使取消操作能中断PCM解码过程
            // preGeneratePcmFiles可能从协程或三层分段流程中调用，此时currentAnalysisThread为null
            val savedThread = currentAnalysisThread
            if (savedThread == null) {
                currentAnalysisThread = Thread.currentThread()
            }
            try {
                return preGeneratePcmFilesInner(context, episodeId, audioUrl, expectedDurationMs, progressCallback)
            } finally {
                // v3.1.41: 恢复之前保存的线程引用
                if (savedThread == null) {
                    currentAnalysisThread = null
                }
            }
        } finally {
            pcmGenerateLock.unlock()
        }
    }

    private fun preGeneratePcmFilesInner(
        context: Context,
        episodeId: String,
        audioUrl: String?,
        expectedDurationMs: Long = 0,
        progressCallback: ((Int) -> Unit)? = null
    ): Boolean {
        val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(context)
        val fullPcmFile = File(pcmCacheDir, "${episodeId}_full.pcm")
        val fullInfoFile = File(pcmCacheDir, "${episodeId}_full.info")
        // v3.1.77: 使用getLogDir()确保日志路径统一，均在logs目录下
        val precacheLog = java.io.File(com.radio.app.RadioApplication.getLogDir(context), "precache/precache.log")
        precacheLog.parentFile?.mkdirs()
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        val pcmGenStartTime = System.currentTimeMillis()

        // v2.4.139: Resolve MP4 duration from multiple sources, never trust a 0 value.
        val audioFile = getCachedAudioFile(context, episodeId, audioUrl)
        checkCancelled()
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

        // v3.1.69: 已取消5分钟PCM自动生成，只生成完整PCM。

        // v2.4.139: Validate using .info file. If valid and durations match, skip all decoding.
        // v3.1.41-fix: 不再要求5分钟版PCM必须存在（v3.1.40已不再自动生成5分钟版PCM），
        // 只要完整版PCM有效即可跳过解码，避免每次循环都重新生成完整PCM。
        val validInfo = if (mp4DurationMs > 0) validatePcmWithInfo(fullPcmFile, fullInfoFile, mp4DurationMs) else null
        if (validInfo != null) {
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] PCM valid per .info for $episodeId (mp4=${validInfo.mp4DurationMs}ms, pcm=${validInfo.pcmDurationMs}ms). Skipping decode.\n")
            return true
        }

        // v2.4.148: If we still don't know the MP4 duration but PCM files already exist,
        // keep them. Deleting valid, large PCM just because MediaExtractor returned 0 is the root
        // cause of repeated full-PCM regeneration and 100MB+ file accumulation.
        // v3.1.68: 不再要求5分钟PCM必须存在——v3.1.40已不再自动生成5分钟版PCM，
        // 只要全量PCM存在且足够大即可保留，避免PCM被反复删除重建。
        if (mp4DurationMs <= 0 && fullPcmFile.exists() && fullPcmFile.length() > 1024 * 100) {
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] keeping existing PCM for $episodeId because MediaExtractor duration is 0 but full PCM exists (${fullPcmFile.length()} bytes).\n")
            return true
        }

        // v3.1.41-fix: 在删除旧PCM前，确认存在可用的解码源（音频文件或URL），
        // 避免PCM被删除后无法重新生成导致全部丢失。
        val decodeSourceAvailable = (audioFile != null && audioFile.exists()) || (audioUrl != null && audioUrl.startsWith("http"))
        if (!decodeSourceAvailable) {
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] WARNING: 无可用解码源（音频文件或URL），保留现有PCM文件。episode=$episodeId\n")
            if (fullPcmFile.exists() && fullPcmFile.length() > 1024 * 100) {
                return true
            }
            // 无PCM文件且无解码源，继续尝试流式解码
        }

        // Not valid — delete old PCM and .info files to force regeneration.
        // v3.1.43: 记录删除前的详细状态，便于分析info文件不匹配原因
        val pcmExists = fullPcmFile.exists()
        val pcmSize = if (pcmExists) fullPcmFile.length() else 0L
        val infoExists = fullInfoFile.exists()
        val infoContent = if (infoExists) {
            try { fullInfoFile.readText().replace("\n", " | ") } catch (_: Exception) { "读取失败" }
        } else { "不存在" }
        precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] PCM/info异常，删除前状态: pcmExists=$pcmExists pcmSize=${pcmSize/1024/1024}MB infoExists=$infoExists infoContent=[$infoContent] mp4DurationMs=$mp4DurationMs episode=$episodeId\n")
        if (fullPcmFile.exists()) fullPcmFile.delete()
        if (fullInfoFile.exists()) fullInfoFile.delete()

        // Decode full PCM from scratch.
        precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] decoding full PCM for $episodeId (audioUrl=$audioUrl, mp4DurationMs=$mp4DurationMs)\n")
        val scaledCbFull = progressCallback
        checkCancelled()
        // v3.1.77: 通过onDecoderName回调记录解码器名称到precache日志
        var decoderName = "unknown"
        val decoded = decodeAudioToPcm(context, episodeId, pcmCacheDir, audioUrl, mp4DurationMs,
            progressCallback = scaledCbFull,
            onDecoderName = { name -> decoderName = name; precacheLog.appendText("[$ts] decodeAudioToPcm: [${com.radio.app.RadioApplication.appVersionTag()}] decoder name=$name\n") }
        )
        if (decoded == null || !decoded.exists() || decoded.length() <= 16000) {
            val failTimeMs = System.currentTimeMillis() - pcmGenStartTime
            progressCallback?.invoke(100)
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] FAILED to decode full PCM for $episodeId\n")
            // v3.1.73: 记录失败到pcm_gen.log
            writePcmGenLog(context, episodeId, audioUrl, failTimeMs, 0L, false, "decode_failed audioFile=${audioFile?.name} audioUrl=$audioUrl decoder=$decoderName")
            return false
        }

        // v2.4.139: Clamp PCM to expected length and compute duration.
        val clampedFile = clampPcmToExpectedLength(decoded, mp4DurationMs, episodeId)
        checkCancelled()
        val pcmDurationMs = clampedFile.length() / (16000 * 2) * 1000
        precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] full PCM generated: ${clampedFile.name} (${clampedFile.length()} bytes, ${pcmDurationMs}ms=${pcmDurationMs / 60000} min, expected ${mp4DurationMs}ms=${mp4DurationMs / 60000} min)\n")

        // v2.4.139: If PCM is still significantly shorter than MP4, we cannot trust it. Fail loudly.
        if (mp4DurationMs > 0 && pcmDurationMs < mp4DurationMs * 0.85) {
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] ERROR: PCM duration (${pcmDurationMs}ms) still < 85% of MP4 duration (${mp4DurationMs}ms). Keeping file but marking unreliable.\n")
        }

        // v3.1.69: 已取消5分钟PCM自动生成，只记录完整PCM的info文件。
        if (mp4DurationMs > 0) {
            writePcmInfo(fullInfoFile, mp4DurationMs, pcmDurationMs, 16000, 1)
        } else {
            precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] WARNING: mp4DurationMs is 0, skipping .info write for $episodeId to avoid invalid info files.\n")
        }

        // v3.1.69: 记录PCM生成总耗时
        val pcmGenTotalMs = System.currentTimeMillis() - pcmGenStartTime
        precacheLog.appendText("[$ts] preGeneratePcmFiles: [${com.radio.app.RadioApplication.appVersionTag()}] PCM generation completed for $episodeId, total time=${pcmGenTotalMs}ms (${pcmGenTotalMs/1000}s), pcmSize=${clampedFile.length()/1024/1024}MB\n")
        Log.i(TAG, "preGeneratePcmFiles: PCM generation completed for $episodeId, total time=${pcmGenTotalMs}ms (${pcmGenTotalMs/1000}s)")
        // v3.1.73: 记录成功到pcm_gen.log
        val audioFileLog = audioFile?.let { "${it.name}(${it.length()})" } ?: "none"
        writePcmGenLog(context, episodeId, audioUrl, pcmGenTotalMs, clampedFile.length(), true, "audioFile=$audioFileLog mp4DurationMs=$mp4DurationMs decoder=$decoderName")

        // v2.4.149: Enforce the user-configurable PCM cache size limit after generating a full PCM.
        val settings = com.radio.app.models.AppSettings.getInstance(context)
        val maxBytes = (settings.pcmCacheMaxSizeGb * 1024L * 1024L * 1024L).toLong()
        cleanupPcmCache(context, maxSizeBytes = maxBytes)

        progressCallback?.invoke(100)
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
                val precacheLog = java.io.File(com.radio.app.RadioApplication.getLogDir(context), "precache/precache.log")
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
     * v2.4.186: Added [blocking] parameter. Background pre-segmentation uses non-blocking
     * mode and skips when another analysis is already running, so patrols don't pile up.
     * Manual segmentation uses blocking mode and cancels any running analysis first.
     *
     * PCM file search order:
     * 1. Pre-decoded full PCM: /sdcard/RadioApp/pcm_cache/{episodeId}_full.pcm
     * 2. Whisper chunk PCM: /sdcard/RadioApp/pcm_cache/{episodeId}_chunk_*.pcm
     * 3. Decode from cached audio file (mp4/m4a) to PCM on-the-fly
     *
     * @param context Application context
     * @param episodeId Episode ID
     * @param durationMs Duration in milliseconds
     * @param audioUrl Audio URL (for finding cached audio file)
     * @param progressCallback (progressPermille 0-1000, elapsedMs, etaMs)
     * @param blocking true to wait for the global analysis lock, false to fail immediately if busy
     * @return SegmentAnalysisResult containing segments, engine name and timing
     */
    fun analyzeEpisode(
        context: Context,
        episodeId: String,
        durationMs: Long,
        audioUrl: String? = null,
        progressCallback: ((Int, Long, Long) -> Unit)? = null,
        blocking: Boolean = true
    ): SegmentAnalysisResult {
        // v2.4.186: Serialize heavy audio analyses. Only one analyzeEpisode may run at a time
        // to prevent concurrent segmentation tasks from fighting over CPU/memory and from
        // cycling the shared segment notification.
        val lockAcquired = if (blocking) {
            analysisLock.lock()
            true
        } else {
            analysisLock.tryLock()
        }
        if (!lockAcquired) {
            throw IllegalStateException("Another audio analysis is already running; skipping episode=$episodeId")
        }

        try {
            // v2.4.115: Initialize file-based logger for VAD diagnostics
            setLogContext(context)

            // v2.4.171: Reset cancellation state and notification lock so a fresh analysis
            // is not killed by the previous run's cancel flag.
            resetCancellation()
            SegmentNotificationHelper.reset()

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
            // v3.1.44: 使用5%容差检查完整PCM时长，缺少5%以上重新生成
            val validInfo = validatePcmWithInfo(fullPcmFile, fullInfoFile, mp4DurationMs, 0.05)
            if (validInfo != null) {
                vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] analyzeEpisode: full PCM valid per .info for $episodeId (mp4=${validInfo.mp4DurationMs}ms, pcm=${validInfo.pcmDurationMs}ms)")
                pcmFile = fullPcmFile
            } else if (mp4DurationMs <= 0 && fullPcmFile.length() > 1024 * 500) {
                // v3.1.39: 当mp4DurationMs为0（MediaExtractor暂时无法读取）但PCM文件较大时，
                // 保留PCM文件，避免因MediaExtractor间歇性失败而反复删除重建PCM，造成流量和CPU浪费
                vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] analyzeEpisode: mp4DurationMs=0 but full PCM exists (${fullPcmFile.length()} bytes), keeping it")
                pcmFile = fullPcmFile
            } else {
                vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] analyzeEpisode: full PCM .info mismatch for $episodeId. Deleting and regenerating.")
                fullPcmFile.delete()
                fullInfoFile.delete()
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

        return try {
            analyzePcmFile(context, pcmFile, durationMs, wrappedProgressCallback)
        } catch (e: Throwable) {
            // v3.1.58: 捕获所有异常/错误(含UnsatisfiedLinkError/RuntimeException)，返回空结果而不是崩溃
            Log.e(TAG, "analyzePcmFile threw in analyzeEpisode: ${e.message}")
            SegmentAnalysisResult(emptyList(), "none", 0L, 0L)
        }
            } finally {
                currentAnalysisThread = null
                vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] analyzeEpisode: cleared analysis thread reference")
            }
        } finally {
            analysisLock.unlock()
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
        progressCallback: ((Int) -> Unit)? = null,
        onDecoderName: ((String) -> Unit)? = null  // v3.1.77: 解码器名称回调，用于日志
    ): File? {
        val pcmGenStartTime = System.currentTimeMillis()
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
                // v3.1.73: 音频缺失时，下载MP4再解码，替代流式解码
                if (audioUrl != null && audioUrl.startsWith("http")) {
                    Log.i(TAG, "decodeAudioToPcm: no cached audio file for $episodeId, downloading MP4 from URL: $audioUrl")
                    vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeAudioToPcm: no cached audio for $episodeId, downloading MP4 instead of streaming")
                    val urlFileName = try {
                        val path = java.net.URL(audioUrl).path
                        path.substringAfterLast("/")
                    } catch (e: Exception) {
                        audioUrl.substringAfterLast("/")
                    }
                    if (urlFileName.isNotBlank()) {
                        val downloadedFile = File(episodesDir, urlFileName)
                        val downloadSuccess = downloadAudioFile(audioUrl, downloadedFile)
                        if (downloadSuccess && downloadedFile.exists() && downloadedFile.length() > 1024) {
                            Log.i(TAG, "decodeAudioToPcm: downloaded ${downloadedFile.length()} bytes to ${downloadedFile.name}, now decoding locally")
                            audioFile = downloadedFile
                        } else {
                            Log.e(TAG, "decodeAudioToPcm: download failed for $episodeId, url=$audioUrl")
                            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeAudioToPcm: download FAILED for $episodeId, url=$audioUrl")
                        }
                    }
                }
                // v3.1.73: 如果下载后仍无音频文件，记录详细失败原因
                if (audioFile == null) {
                    val cachedFileNames = cachedFiles.map { "${it.name}=${it.length()}" }
                    Log.e(TAG, "decodeAudioToPcm: FAILED for $episodeId — no cached audio file found via any strategy after download attempt")
                    Log.e(TAG, "decodeAudioToPcm:   search strategies attempted: URL filename, episodeId prefix, most recent")
                    Log.e(TAG, "decodeAudioToPcm:   audioUrl=$audioUrl")
                    Log.e(TAG, "decodeAudioToPcm:   cached files in episodes dir: ${cachedFileNames.joinToString(", ")}")
                    Log.e(TAG, "decodeAudioToPcm:   episodes dir path: ${episodesDir.absolutePath}")
                    return null
                }
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
            // v3.1.78: 从MediaExtractor读取真实音频轨道时长，用于进度计算
            // 不依赖调用者传入的durationMs——该参数可能为0或来自错误的数据源（如节目元数据仅30分钟）
            val trackDurationUs = if (inputFormat.containsKey(android.media.MediaFormat.KEY_DURATION))
                inputFormat.getLong(android.media.MediaFormat.KEY_DURATION) else 0L
            Log.i(TAG, "decodeAudioToPcm: container format: ${sampleRate}Hz ${channelCount}ch mime=$mime trackDuration=${trackDurationUs}us (${trackDurationUs/1000000}s)")

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

            // v3.1.80: 优先选择MTK硬件解码器，缩短解码耗时
            val preferredName = selectPreferredAudioDecoder(mime) { msg ->
                Log.i(TAG, "decodeAudioToPcm: $msg")
                vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeAudioToPcm: $msg")
            }
            val decoder = if (preferredName != null) {
                try {
                    android.media.MediaCodec.createByCodecName(preferredName)
                } catch (e: Exception) {
                    Log.w(TAG, "decodeAudioToPcm: failed to create preferred decoder $preferredName, falling back: ${e.message}")
                    android.media.MediaCodec.createDecoderByType(mime)
                }
            } else {
                android.media.MediaCodec.createDecoderByType(mime)
            }
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            // v3.1.80: 记录实际使用的解码器名称
            val decoderName = decoder.name
            Log.i(TAG, "decodeAudioToPcm: decoder name=$decoderName")
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeAudioToPcm: decoder name=$decoderName")
            // v3.1.77: 通过回调将解码器名称传递给调用者（preGeneratePcmFilesInner写入日志）
            onDecoderName?.invoke(decoderName)

            val bufferInfo = android.media.MediaCodec.BufferInfo()
            val outputDirFile = outputFile
            // v2.4.130: Support append mode for continuing truncated PCM.
            // If startOffsetBytes > 0, we're resuming from a truncated PCM file.
            // Open in append mode and skip already-decoded bytes.
            // v3.1.74: 使用BufferedOutputStream减少文件写入系统调用次数，大幅提升写入性能
            val appendMode = startOffsetBytes > 0
            val rawFos = if (appendMode) {
                java.io.FileOutputStream(outputDirFile, true)  // append
            } else {
                java.io.FileOutputStream(outputDirFile)  // overwrite
            }
            val fos = java.io.BufferedOutputStream(rawFos, 256 * 1024)
            var totalPcmBytes = if (appendMode) startOffsetBytes else 0
            // v2.4.149: Raise max PCM size to 600MB (~5.2 hours at 16kHz mono 16-bit) so 3-4 hour
            // episodes are not silently truncated to "only tens of MB".
            val maxPcmBytes = 600 * 1024 * 1024  // 600MB max

            // v3.1.78: 优先使用MediaExtractor读取的真实音频轨道时长计算expectedPcmBytes
            // 不依赖调用者传入的durationMs——该参数可能为0或来自错误的数据源（如节目元数据仅30分钟）
            // 16kHz mono 16-bit = 32000 bytes/sec
            val expectedPcmBytes = when {
                trackDurationUs > 0 -> (trackDurationUs * 16000L * 2L / 1_000_000L).coerceAtLeast(1L)
                durationMs > 0 -> (durationMs * 16000L * 2L / 1000L).coerceAtLeast(1L)
                audioFile.length() > 1024 -> (audioFile.length() * 10).coerceAtLeast(1L)
                else -> 1L
            }
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeAudioToPcm: START episode=$episodeId, audioFile=${audioFile.name} (${audioFile.length()} bytes), durationMs=$durationMs, trackDurationUs=$trackDurationUs expectedPcmBytes=$expectedPcmBytes, maxPcmBytes=$maxPcmBytes, maxDecodeDurationMs=$maxDecodeDurationMs")
            var lastReportedDecodeProgress = -1
            var lastDecodeProgressTimeMs = 0L
            fun reportDecodeProgressIfNeeded() {
                if (progressCallback == null) return
                val nowMs = System.currentTimeMillis()
                // v3.1.78: 进度直接映射0-100，不再经过0-20→0-95的奇怪缩放
                val pct = (totalPcmBytes * 100 / expectedPcmBytes).toInt().coerceIn(0, 100)
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
                            if (needResample) {
                                // v3.1.75: 大幅优化重采样性能
                                // 1. 直接从outputBuffer读取，避免ByteArray分配和拷贝
                                // 2. 移除extendedInput，用边界检查替代
                                // 3. 移除outBytes中间数组，直接用array()写入
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val pcmShort = outputBuffer.asShortBuffer()
                                val totalSamples = bufferInfo.size / 2
                                val inFrames = totalSamples / channelCount

                                // Mix to mono first
                                val monoInput = ShortArray(inFrames)
                                for (i in 0 until inFrames) {
                                    var sum = 0
                                    for (c in 0 until channelCount) {
                                        sum += pcmShort.get(i * channelCount + c).toInt()
                                    }
                                    monoInput[i] = (sum / channelCount).toShort()
                                }

                                // Continuous-phase linear interpolation (无extendedInput)
                                val ratio = sampleRate.toDouble() / 16000.0
                                var currentPhase = resamplePhase
                                val estimatedOutFrames = ((inFrames - currentPhase) / ratio).toInt() + 1
                                val outBuf = java.nio.ByteBuffer.allocate(estimatedOutFrames * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)

                                while (currentPhase < inFrames) {
                                    val srcIdx = currentPhase.toInt()
                                    val frac = currentPhase - srcIdx
                                    // 边界检查替代extendedInput: srcIdx==0时用lastSample
                                    val s0 = if (srcIdx == 0) lastSample.toInt() else monoInput[srcIdx - 1].toInt()
                                    val s1 = monoInput[srcIdx].toInt()
                                    val interpolated = s0 + ((s1 - s0) * frac).toInt()
                                    outBuf.putShort(interpolated.toShort())
                                    currentPhase += ratio
                                }

                                // Update state for next chunk
                                resamplePhase = currentPhase - inFrames
                                if (inFrames > 0) {
                                    lastSample = monoInput[inFrames - 1]
                                }

                                // 直接写入，避免outBytes中间数组
                                fos.write(outBuf.array(), 0, outBuf.position())
                                totalPcmBytes += outBuf.position()
                                reportDecodeProgressIfNeeded()
                            } else {
                                val chunk = ByteArray(bufferInfo.size)
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.get(chunk)
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
            val decodeElapsedMs = System.currentTimeMillis() - pcmGenStartTime
            val speedMBps = if (decodeElapsedMs > 0) String.format("%.2f", totalPcmBytes.toDouble() / 1024 / 1024 / (decodeElapsedMs / 1000.0)) else "?"
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeAudioToPcm: DONE episode=$episodeId, totalPcmBytes=$totalPcmBytes, finalDurationMs=$finalDurationMs, expectedPcmBytes=$expectedPcmBytes, completeness=${String.format("%.2f", completenessRatio)}")
            Log.i(TAG, "decodeAudioToPcm: decoded $totalPcmBytes bytes ($finalDurationMs ms) to ${outputFile.name}, elapsed=${decodeElapsedMs}ms, speed=${speedMBps}MB/s (final rate: ${sampleRate}Hz ${channelCount}ch -> 16kHz mono)")
            // v2.4.138: .info file with duration metadata is now written by the caller
            // (preGeneratePcmFiles / analyzeEpisode) so that mp4DurationMs and pcmDurationMs
            // can be validated together.
            return if (totalPcmBytes > 16000) outputFile else null
        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - pcmGenStartTime
            Log.e(TAG, "decodeAudioToPcm failed after ${elapsedMs}ms: ${e.message}")
            Log.e(TAG, "decodeAudioToPcm:   episodeId=$episodeId audioUrl=$audioUrl durationMs=$durationMs")
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
        val decodeStartTime = System.currentTimeMillis()
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
            // v3.1.71: 使用var使sampleRate/channelCount可变——HE-AAC v2解码后格式会变化
            // 容器格式可能是22050Hz/1ch，但解码器实际输出44100Hz/2ch（SBR+PS之后）
            var sampleRate = inputFormat.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = inputFormat.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            val mime = inputFormat.getString(android.media.MediaFormat.KEY_MIME) ?: ""
            Log.i(TAG, "decodeUrlToPcm: container format: ${sampleRate}Hz ${channelCount}ch mime=$mime")

            // v3.1.80: 优先选择MTK硬件解码器，缩短解码耗时
            val preferredName = selectPreferredAudioDecoder(mime) { msg ->
                Log.i(TAG, "decodeUrlToPcm: $msg")
                vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeUrlToPcm: $msg")
            }
            val decoder = if (preferredName != null) {
                try {
                    android.media.MediaCodec.createByCodecName(preferredName)
                } catch (e: Exception) {
                    Log.w(TAG, "decodeUrlToPcm: failed to create preferred decoder $preferredName, falling back: ${e.message}")
                    android.media.MediaCodec.createDecoderByType(mime)
                }
            } else {
                android.media.MediaCodec.createDecoderByType(mime)
            }
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            // v3.1.80: 记录实际使用的解码器名称
            val decoderName = decoder.name
            Log.i(TAG, "decodeUrlToPcm: decoder name=$decoderName")

            val bufferInfo = android.media.MediaCodec.BufferInfo()
            var totalPcmBytes = 0
            // v2.4.149: Raise max PCM size to 600MB (~5.2 hours) for long episodes.
            val maxPcmBytes = 600 * 1024 * 1024
            // v3.1.71: 连续相位重采样状态（跨chunk），与decodeAudioToPcm保持一致
            var resamplePhase = 0.0
            var lastSample: Short = 0
            var needResample = sampleRate != 16000 || channelCount != 1
            // v3.1.72: 使用BufferedOutputStream减少文件写入系统调用次数，大幅提升写入性能
            val bos = java.io.BufferedOutputStream(java.io.FileOutputStream(outputFile), 256 * 1024)

            // v2.4.148: Progress reporting for URL decode (streaming fallback).
            val expectedPcmBytes = if (durationMs > 0) (durationMs * 16000L * 2L / 1000L).coerceAtLeast(1L) else 1L
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeUrlToPcm: START url=$audioUrl, durationMs=$durationMs, expectedPcmBytes=$expectedPcmBytes, maxPcmBytes=$maxPcmBytes, maxDecodeDurationMs=$maxDecodeDurationMs")
            var lastReportedDecodeProgress = -1
            var lastDecodeProgressTimeMs = 0L
            fun reportDecodeProgressIfNeeded() {
                if (progressCallback == null) return
                val nowMs = System.currentTimeMillis()
                val pct = (totalPcmBytes * 100 / expectedPcmBytes).toInt().coerceIn(0, 100)
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
                    // v3.1.71: 处理INFO_OUTPUT_FORMAT_CHANGED——解码器实际输出格式可能与容器不同
                    // HE-AAC v2：容器说22050Hz/1ch，但解码器输出44100Hz/2ch（SBR+PS之后）
                    if (outputBufferIndex == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = decoder.outputFormat
                        try {
                            sampleRate = newFormat.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                            channelCount = newFormat.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                            needResample = sampleRate != 16000 || channelCount != 1
                            Log.i(TAG, "decodeUrlToPcm: FORMAT_CHANGED: actual sampleRate=$sampleRate, channels=$channelCount, needResample=$needResample")
                            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeUrlToPcm: FORMAT_CHANGED: ${sampleRate}Hz ${channelCount}ch (was container format)")
                        } catch (e: Exception) {
                            Log.w(TAG, "decodeUrlToPcm: FORMAT_CHANGED but failed to read format: ${e.message}")
                        }
                    }
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)

                            if (needResample) {
                                // v3.1.71: 优化重采样方案——连续相位线性插值+ByteBuffer直接写入，与decodeAudioToPcm一致
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

                                // 预分配ByteBuffer，直接写入，消除ArrayList<Short>装箱
                                val estimatedOutFrames = ((monoInput.size - currentPhase) / ratio).toInt() + 1
                                val outBuf = java.nio.ByteBuffer.allocate(estimatedOutFrames * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)

                                while (currentPhase < availableInputRange) {
                                    val srcIdx = currentPhase.toInt()
                                    val frac = currentPhase - srcIdx
                                    val s0 = extendedInput[srcIdx].toInt()
                                    val s1 = extendedInput[srcIdx + 1].toInt()
                                    val interpolated = s0 + ((s1 - s0) * frac).toInt()
                                    outBuf.putShort(interpolated.toShort())
                                    currentPhase += ratio
                                }

                                // Update phase state for next chunk
                                resamplePhase = currentPhase - availableInputRange
                                lastSample = if (monoInput.isNotEmpty()) monoInput[monoInput.size - 1] else lastSample

                                // Write output directly
                                val outBytes = ByteArray(outBuf.position())
                                outBuf.rewind()
                                outBuf.get(outBytes)
                                bos.write(outBytes)
                                totalPcmBytes += outBytes.size
                                reportDecodeProgressIfNeeded()
                            } else {
                                bos.write(chunk)
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
                bos.flush()
                bos.close()
                decoder.stop()
                decoder.release()
                extractor.release()
            }

            val finalDurationMs = totalPcmBytes * 1000L / (16000L * 2L)
            val completenessRatio = if (expectedPcmBytes > 1) totalPcmBytes.toDouble() / expectedPcmBytes.toDouble() else 1.0
            val decodeElapsedMs = System.currentTimeMillis() - decodeStartTime
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeUrlToPcm: DONE totalPcmBytes=$totalPcmBytes, finalDurationMs=$finalDurationMs, expectedPcmBytes=$expectedPcmBytes, completeness=${String.format("%.2f", completenessRatio)}")
            Log.i(TAG, "decodeUrlToPcm: decoded $totalPcmBytes bytes ($finalDurationMs ms) to ${outputFile.name}, elapsed=${decodeElapsedMs}ms")
            // v2.4.138: .info file with duration metadata is now written by the caller.
            return if (totalPcmBytes > 16000) outputFile else null
        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - decodeStartTime
            Log.e(TAG, "decodeUrlToPcm failed after ${elapsedMs}ms: ${e.message}")
            Log.e(TAG, "decodeUrlToPcm:   url=$audioUrl durationMs=$durationMs")
            // v3.1.72: 记录完整异常堆栈，便于定位流式解码失败原因
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            Log.e(TAG, "decodeUrlToPcm:   stacktrace: ${sw.toString()}")
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] decodeUrlToPcm FAILED after ${elapsedMs}ms: ${e.javaClass.simpleName}: ${e.message}")
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

        // v2.4.170: Allow notifications for a new analysis (clears any previous cancel flag).
        SegmentNotificationHelper.reset()
        // v3.1.32: 清除分析取消标志，确保VAD不会因历史取消信号而立即失败
        resetCancellation()

        // v3.1.41: 保存并设置当前线程引用，使取消操作能中断分析过程
        val savedThread = currentAnalysisThread
        if (savedThread == null) {
            currentAnalysisThread = Thread.currentThread()
        }
        try {
        // v2.4.161: Reset object-level counters
        synchronized(this) {
            yamnetCallCount = 0
            vadRunCount = 0
        }

        // v3.1.58: 检查NativeLibLoader，失败时返回空结果而非崩溃
        if (!NativeLibLoader.ensureLoaded(context)) {
            Log.e(TAG, "Native libraries not loaded.")
            return SegmentAnalysisResult(emptyList(), "none", 0L, 0L)
        }

        val modelDir = getModelDir(context)
        if (!isYamnetInstalled(modelDir) || !isSileroVadInstalled(modelDir)) {
            Log.w(TAG, "Models not installed. YAMNet=${isYamnetInstalled(modelDir)}, VAD=${isSileroVadInstalled(modelDir)}")
            return SegmentAnalysisResult(emptyList(), "none", 0L, 0L)
        }

        // v3.1.58: 在加载模型前再次检查NativeLibLoader，防御性避免UnsatisfiedLinkError/RuntimeException崩溃
        if (!NativeLibLoader.ensureLoaded(context)) {
            Log.e(TAG, "Native libraries not available before model loading, returning empty result")
            return SegmentAnalysisResult(emptyList(), "none", 0L, 0L)
        }
        // v3.1.145-fix: 使用类级字段管理YAMNet Interpreter，超时后可重建
        val yamnetModelFileObj = File(modelDir, "yamnet.tflite")
        yamnetModelFile = yamnetModelFileObj
        val yamnetInterpreter = try {
            val interp = loadYamnetModel(yamnetModelFileObj)
            currentYamnetInterpreter = interp
            interp
        } catch (e: Throwable) {
            Log.e(TAG, "loadYamnetModel failed: ${e.message}")
            return SegmentAnalysisResult(emptyList(), "none", 0L, 0L)
        }
        val vadModel = try {
            loadSileroVad(File(modelDir, "silero_vad.onnx"))
        } catch (e: Throwable) {
            Log.e(TAG, "loadSileroVad failed: ${e.message}")
            try { yamnetInterpreter.close() } catch (_: Exception) {}
            currentYamnetInterpreter = null
            return SegmentAnalysisResult(emptyList(), "none", 0L, 0L)
        }

        try {
            // v2.4.178: Memory-map the PCM file so huge files do not require a heap-sized FloatArray.
            val samples = try {
                openPcmSamples(pcmFile)
            } catch (e: Throwable) {
                Log.e(TAG, "openPcmSamples 失败: ${e.javaClass.name}: ${e.message}")
                return SegmentAnalysisResult(emptyList(), "none", 0L, 0L)
            }
            samples.use { samplesProvider ->
                if (samplesProvider.size < YAMNET_WINDOW_SAMPLES) {
                    Log.w(TAG, "PCM too short: ${samplesProvider.size} samples")
                    throw RuntimeException("PCM数据太短: ${samplesProvider.size} 样本 (需要至少 $YAMNET_WINDOW_SAMPLES)")
                }

                val totalSamples = samplesProvider.size
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
                    // v3.1.112: 每10%和每30秒写入指纹日志，记录VAD/YAMNet进度
                    if ((mapped > 0 && mapped % 100 == 0 && nowMs - lastFriendlyLogTimeMs > 10_000)
                        || (nowMs - lastFriendlyLogTimeMs > 30_000)) {
                        lastFriendlyLogTimeMs = nowMs
                        val processedMs = (mapped * totalDurationMs / 1000L).coerceAtMost(totalDurationMs)
                        val elapsedMs = nowMs - analysisStartTimeMs
                        val etaMs = if (mapped > 0) (elapsedMs * (1000 - mapped) / mapped) else 0L
                        val progressPercent = String.format(java.util.Locale.US, "%.1f", mapped / 10f)
                        val logMsg = "[${com.radio.app.RadioApplication.appVersionTag()}] 音频分段进度 ${progressPercent}%：已处理 ${formatDurationMs(processedMs)} / ${formatDurationMs(totalDurationMs)}，已用 ${formatDurationMs(elapsedMs)}，预计剩余 ${formatDurationMs(etaMs)}"
                        vadLog(logMsg)
                        // v3.1.112: 同时写入指纹日志
                        val phaseName = when {
                            mapped < 300 -> "Phase1 VAD"
                            mapped < 900 -> "Phase2 YAMNet"
                            else -> "Phase3 合并"
                        }
                        writeFingerprintLog("音频分段进度 $phaseName: ${progressPercent}% (已用${formatDurationMs(elapsedMs)}/剩余${formatDurationMs(etaMs)})")
                    }
                }

                // ===== Phase 1: Silero VAD coarse segmentation (0-300‰) =====
                vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] Phase 1/3: Silero VAD coarse segmentation")
                val (speechRanges, silenceRanges) = runSileroVadIntervals(samplesProvider, vadModel) { progressPermille ->
                    reportProgress((progressPermille * 300 / 1000).coerceIn(0, 300))
                }

                // ===== Phase 2: YAMNet classification of speech intervals (300-900‰) =====
                vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] Phase 2/3: YAMNet classification of ${speechRanges.size} speech intervals")
                val intervalSegments = mutableListOf<VoiceSegment>()
                val totalSpeechDurationMs = speechRanges.sumOf { it.durationMs }
                var processedSpeechMs = 0L

                for (range in speechRanges) {
                    checkCancelled()
                    // v3.1.140-fix: 传递区间内进度回调，避免长时间卡在单个区间
                    val subSegments = classifySpeechInterval(samplesProvider, range) { subProgress ->
                        val subMapped = if (totalSpeechDurationMs > 0) {
                            300 + ((processedSpeechMs + range.durationMs * subProgress / 1000) * 600 / totalSpeechDurationMs).toInt()
                        } else 300
                        reportProgress(subMapped.coerceIn(300, 900))
                    }
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
                    val subSegments = sampleSilenceInterval(samplesProvider, range)
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
            }

        } catch (e: Throwable) {
            Log.e(TAG, "analyzePcmFile 分析循环崩溃: ${e.javaClass.name}: ${e.message}")
            return SegmentAnalysisResult(emptyList(), "none", 0L, 0L)
        } finally {
            // v3.1.146-fix: 不close YAMNet Interpreter（可能因推理挂死处于不可用状态，close也会挂死）
            currentYamnetInterpreter = null
            try { vadModel.session.close() } catch (_: Exception) {}
        }
        } finally {
            if (savedThread == null) {
                currentAnalysisThread = null
            }
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

    // v3.1.145-fix: 类级字段，YAMNet模型文件路径，用于超时后重建Interpreter
    private var yamnetModelFile: File? = null
    // v3.1.145-fix: 当前YAMNet Interpreter实例，超时后关闭重建
    @Volatile
    private var currentYamnetInterpreter: Interpreter? = null
    // v3.1.145-fix: 重建Interpreter时的同步锁
    private val yamnetInterpreterLock = Any()

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
        val maxRawScore: Float,
        // v3.1.99: 1kHz~4kHz能量 / 全频能量比值，用于人声频谱前置检测
        val spectrumRatio: Float = 0f
    )

    // v3.1.146-fix: 单线程执行器，用于YAMNet推理超时保护
    // 使用SingleThreadExecutor：一次只允许一个推理任务，超时后重建整个executor避免线程爆炸
    @Volatile
    private var yamnetExecutor = Executors.newSingleThreadExecutor()
    // v3.1.149-fix: 将超时从15秒缩短到10秒，减少每次挂死浪费的时间
    private const val YAMNET_INFERENCE_TIMEOUT_SECONDS = 10L

    // v3.1.149-fix: YAMNet推理连续超时与全局重载计数
    // 连续2次超时则跳过当前区间，避免interpreter反复挂死导致的死循环
    @Volatile
    private var consecutiveTimeoutCount = 0
    @Volatile
    private var totalReloadCount = 0
    // v3.1.149-fix: 缩短到2次连续超时就跳过区间
    private const val MAX_CONSECUTIVE_TIMEOUTS = 2
    // v3.1.149-fix: 全局重载超过15次则跳过剩余YAMNet处理，避免整个分段流程被拖死
    private const val MAX_TOTAL_RELOADS = 15

    // v3.1.150-fix: 归一化增益上限，防止极静音频产生超大值导致TFLite推理挂死
    // 当RMS=0.05时增益=3x，RMS<0.05时增益上限为3x
    private const val MAX_NORMALIZE_GAIN = 3.0f
    // v3.1.150-fix: YAMNet输入值钳位上限，防止极端值触发TFLite原生bug
    // 原始YAMNet输入范围约为[-1,1]，钳位到±3.0提供足够动态范围同时避免极端值
    private const val MAX_YAMNET_INPUT_VALUE = 3.0f

    // v3.1.147-fix: 获取当前Interpreter（从类级字段读取）
    private fun getCurrentInterpreter(): Interpreter {
        return currentYamnetInterpreter
            ?: throw RuntimeException("YAMNet interpreter not initialized")
    }

    // v3.1.147-fix: 重置YAMNet超时计数，每次开始新的YAMNet处理轮次前调用
    // 避免前一节目残留的计时影响下一轮处理
    fun resetYamnetTimeoutCounters() {
        consecutiveTimeoutCount = 0
        totalReloadCount = 0
        Log.i(TAG, "resetYamnetTimeoutCounters: 已重置超时计数")
    }

    // v3.1.146-fix: 放弃旧Interpreter并重建新实例
    // 不close旧Interpreter！interpreter.close()在native推理挂死时同样会挂死
    // 同时重建SingleThreadExecutor，放弃挂死的旧线程
    private fun reloadYamnetInterpreter() {
        synchronized(yamnetInterpreterLock) {
            // 放弃旧Interpreter（不close，避免挂死）
            currentYamnetInterpreter = null
            // 关闭旧executor（放弃旧线程），创建新executor
            try { yamnetExecutor.shutdown() } catch (_: Exception) {}
            yamnetExecutor = Executors.newSingleThreadExecutor()
            Log.w(TAG, "reloadYamnetInterpreter: abandoned old interpreter + executor, creating new one")
            val modelFile = yamnetModelFile
                ?: throw RuntimeException("YAMNet model file path not set")
            try {
                val newInterp = loadYamnetModel(modelFile)
                currentYamnetInterpreter = newInterp
                Log.i(TAG, "reloadYamnetInterpreter: new interpreter created successfully")
            } catch (e: Throwable) {
                Log.e(TAG, "reloadYamnetInterpreter: failed to create new interpreter: ${e.message}")
                currentYamnetInterpreter = null
            }
        }
    }

    private fun classifyWithYamnet(
        samples: FloatArray
    ): YamnetResult {
        // v3.1.149-fix: 在入口处检查全局重载阈值，避免不必要的推理尝试
        val currentTotalReload = totalReloadCount
        if (currentTotalReload >= MAX_TOTAL_RELOADS) {
            val skipMsg = "classifyWithYamnet: 入口检查总重载${currentTotalReload}≥${MAX_TOTAL_RELOADS}，跳过推理，返回默认结果"
            Log.w(TAG, skipMsg)
            return YamnetResult(
                speech = 0f, narration = 0f, singing = 0f, music = 0f,
                instrumental = 0f, popMusic = 0f, jingle = 0f, song = 0f,
                backgroundMusic = 0f, themeMusic = 0f, silence = 0f,
                voiceSum = 0f, bgMusicSum = 0f, maxRawScore = 0f,
                spectrumRatio = 0f
            )
        }

        val entryMs = System.currentTimeMillis()
        try {
            // v3.1.149-fix: 详细步骤计时日志
            var stepMs: Long

            // v3.1.145-fix: 通过类级字段获取当前Interpreter，而非参数传入
            // 这样超时后reloadYamnetInterpreter()替换字段值，后续推理自动使用新Interpreter
            val interpreter = getCurrentInterpreter()
            stepMs = System.currentTimeMillis()
            val getInterpreterElapsed = stepMs - entryMs

            // v3.1.152-fix: YAMNet使用原始PCM样本（范围[-1,1]），不做响度归一化
            // 归一化会放大静音段的噪声，产生极端值触发TFLite原生推理bug
            // 保留normalizeLoudness仅用于computeSpectrumRatio诊断
            val normalizedSamples = normalizeLoudness(samples)
            val normalizeElapsed = System.currentTimeMillis() - stepMs
            // v3.1.99: 计算人声频谱比值（1kHz~4kHz能量占比），使用归一化样本
            stepMs = System.currentTimeMillis()
            val spectrumRatio = computeSpectrumRatio(normalizedSamples)
            val spectrumElapsed = System.currentTimeMillis() - stepMs
            stepMs = System.currentTimeMillis()

            // v3.1.152-fix: 对原始样本做NaN/Infinity检查和值域钳位
            // 原始PCM样本应在[-1,1]范围内，钳位到±1.0作为安全边界
            var hasInvalid = false
            var rawMaxVal = 0f
            for (s in samples) {
                if (s.isNaN() || s.isInfinite()) { hasInvalid = true; break }
                if (kotlin.math.abs(s) > rawMaxVal) rawMaxVal = kotlin.math.abs(s)
            }
            if (hasInvalid) {
                Log.w(TAG, "classifyWithYamnet: 原始输入包含NaN/Infinity，返回默认结果")
                return YamnetResult(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, spectrumRatio)
            }
            // 钳位原始样本到安全范围，避免极端值触发TFLite bug
            val clampedSamples: FloatArray = if (rawMaxVal > 1.0f) {
                val scale = 1.0f / rawMaxVal
                FloatArray(samples.size) { (samples[it] * scale).coerceIn(-1.0f, 1.0f) }
            } else {
                samples
            }

            // v2.4.130: Use model's actual input shape instead of hardcoded [1, 15600].
            val inputBuffer = TensorBuffer.createFixedSize(
                yamnetInputShape,
                org.tensorflow.lite.DataType.FLOAT32
            )
            // v3.1.152-fix: 使用原始PCM样本（钳位后），而非归一化样本
            inputBuffer.loadArray(clampedSamples)
            val bufferElapsed = System.currentTimeMillis() - stepMs
            stepMs = System.currentTimeMillis()

            // v2.4.129: Log input diagnostics for first 3 calls
            yamnetCallCount++
            if (yamnetCallCount <= 3) {
                var nonZero = 0
                var sum = 0.0
                for (s in samples) { if (s != 0f) nonZero++; sum += kotlin.math.abs(s) }
                val avgAbs = (sum / samples.size).toFloat()
                vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] classifyWithYamnet #$yamnetCallCount: input samples=${samples.size}, nonZero=$nonZero, avgAbs=$avgAbs, first10=${samples.take(10).joinToString(",")}")
            }
            // v3.1.149-fix: 每100次调用记录一次步骤耗时
            if (yamnetCallCount % 100 == 0) {
                Log.d(TAG, "classifyWithYamnet #$yamnetCallCount: 步骤耗时(ms) getInterpreter=$getInterpreterElapsed normalize=$normalizeElapsed spectrum=$spectrumElapsed buffer=$bufferElapsed 总重载=$currentTotalReload")
            }

            // Output: [1, 521] float
            val outputBuffer = TensorBuffer.createFixedSize(
                intArrayOf(1, YAMNET_NUM_CLASSES),
                org.tensorflow.lite.DataType.FLOAT32
            )

            // v3.1.145-fix: YAMNet推理超时保护——interpreter.run()可能挂死，使用Future+超时
            // 超时后关闭旧Interpreter并重建新实例，确保后续推理不使用损坏的Interpreter
            val inferenceStartMs = System.currentTimeMillis()
            try {
                val inferenceFuture: Future<Boolean> = yamnetExecutor.submit(Callable {
                    interpreter.run(inputBuffer.buffer, outputBuffer.buffer)
                    true
                })
                try {
                    inferenceFuture.get(YAMNET_INFERENCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    // v3.1.147-fix: 推理成功，重置连续超时计数
                    consecutiveTimeoutCount = 0
                } catch (e: TimeoutException) {
                    // 推理超时，取消任务
                    inferenceFuture.cancel(true)
                    consecutiveTimeoutCount++
                    totalReloadCount++
                    val elapsedMs = System.currentTimeMillis() - inferenceStartMs
                    // v3.1.152-fix: 记录原始样本信息（而非归一化样本），用于诊断根因
                    var nonZero = 0; var sum = 0.0; var maxVal = 0f; var minVal = 0f
                    for (s in samples) { if (s != 0f) nonZero++; sum += kotlin.math.abs(s); if (s > maxVal) maxVal = s; if (s < minVal) minVal = s }
                    val avgAbs = (sum / samples.size).toFloat()
                    val timeoutMsg = "classifyWithYamnet: #${yamnetCallCount} 推理超时(${YAMNET_INFERENCE_TIMEOUT_SECONDS}秒，实际${elapsedMs}ms)，连续超时=${consecutiveTimeoutCount}，总重载=${totalReloadCount}，前置步骤耗时(ms) getInterpreter=$getInterpreterElapsed normalize=$normalizeElapsed spectrum=$spectrumElapsed buffer=$bufferElapsed，原始样本非零=${nonZero}，平均绝对值=${"%.4f".format(avgAbs)}，max=${"%.4f".format(maxVal)}，min=${"%.4f".format(minVal)}，首位10=${samples.take(10).joinToString(",") { "%.4f".format(it) }}"
                    Log.w(TAG, timeoutMsg)
                    vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] $timeoutMsg")
                    // v3.1.145-fix: 关闭旧Interpreter并重建新实例，确保后续推理不再挂死
                    reloadYamnetInterpreter()
                    // v3.1.149-fix: 连续超时超过阈值(2次)，抛出异常让调用者跳过当前区间
                    if (consecutiveTimeoutCount >= MAX_CONSECUTIVE_TIMEOUTS) {
                        val abortMsg = "classifyWithYamnet: 连续${consecutiveTimeoutCount}次超时(${elapsedMs}ms/次)，跳过当前区间，总重载=${totalReloadCount}"
                        Log.w(TAG, abortMsg)
                        vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] $abortMsg")
                        throw RuntimeException(abortMsg)
                    }
                    // v3.1.149-fix: 全局重载超过阈值(15次)，抛出异常让调用者跳过剩余所有YAMNet处理
                    if (totalReloadCount >= MAX_TOTAL_RELOADS) {
                        val abortAllMsg = "classifyWithYamnet: 总重载${totalReloadCount}次超过阈值=${MAX_TOTAL_RELOADS}，跳过剩余所有YAMNet处理"
                        Log.w(TAG, abortAllMsg)
                        vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] $abortAllMsg")
                        throw RuntimeException(abortAllMsg)
                    }
                    // 输出缓冲可能不完整，返回默认结果
                    return YamnetResult(
                        speech = 0f, narration = 0f, singing = 0f, music = 0f,
                        instrumental = 0f, popMusic = 0f, jingle = 0f, song = 0f,
                        backgroundMusic = 0f, themeMusic = 0f, silence = 0f,
                        voiceSum = 0f, bgMusicSum = 0f, maxRawScore = 0f,
                        spectrumRatio = spectrumRatio
                    )
                }
            } catch (e: RuntimeException) {
                // v3.1.147-fix: 传递连续超时/全局重载异常，不在此处捕获
                if (e.message?.contains("跳过") == true) {
                    Log.w(TAG, "classifyWithYamnet: 传递跳过异常: ${e.message}")
                }
                throw e
            } catch (e: Throwable) {
                // v3.1.149-fix: 区分InterruptedException和其他异常
                if (e is InterruptedException) {
                    Log.w(TAG, "classifyWithYamnet: Future.get被中断(InterruptedException)，总重载=${totalReloadCount}")
                }
                val errMsg = "classifyWithYamnet: interpreter.run 崩溃: ${e.javaClass.name}: ${e.message}"
                Log.w(TAG, errMsg)
                vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] $errMsg")
                // v3.1.145-fix: interpreter崩溃后也重建，确保后续推理可用
                reloadYamnetInterpreter()
                // 返回默认结果，避免崩溃传播
                return YamnetResult(
                    speech = 0f, narration = 0f, singing = 0f, music = 0f,
                    instrumental = 0f, popMusic = 0f, jingle = 0f, song = 0f,
                    backgroundMusic = 0f, themeMusic = 0f, silence = 0f,
                    voiceSum = 0f, bgMusicSum = 0f, maxRawScore = 0f,
                    spectrumRatio = spectrumRatio
                )
            }
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

            // v3.1.102: YAMNet输出已经是sigmoid概率值（0~1），直接使用原始值，避免双重sigmoid压缩差值
            val speechProb = scores.getOrElse(YAMNET_IDX_SPEECH) { 0f }
            val narrationProb = scores.getOrElse(YAMNET_IDX_NARRATION) { 0f }
            val singingProb = scores.getOrElse(YAMNET_IDX_SINGING) { 0f }
            val musicProb = scores.getOrElse(YAMNET_IDX_MUSIC) { 0f }
            val instrumentalProb = scores.getOrElse(YAMNET_IDX_INSTRUMENTAL) { 0f }
            val popMusicProb = scores.getOrElse(YAMNET_IDX_POP_MUSIC) { 0f }
            val songProb = scores.getOrElse(YAMNET_IDX_SONG) { 0f }
            val bgMusicProb = scores.getOrElse(YAMNET_IDX_BACKGROUND_MUSIC) { 0f }
            val themeMusicProb = scores.getOrElse(YAMNET_IDX_THEME_MUSIC) { 0f }
            val jingleProb = scores.getOrElse(YAMNET_IDX_JINGLE) { 0f }
            val silenceProb = scores.getOrElse(YAMNET_IDX_SILENCE) { 0f }

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
                maxRawScore = maxRawScore,
                spectrumRatio = spectrumRatio
            )
        } catch (e: RuntimeException) {
            // v3.1.147-fix: 连续超时/全局重载异常，直接向上传播让调用者跳过区间
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "YAMNet classification failed: ${e.message}")
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] classifyWithYamnet FAILED: ${e.javaClass.simpleName}: ${e.message}")
            return YamnetResult(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
    }

    private fun sigmoid(x: Float): Float {
        val exp = kotlin.math.exp(-x.toDouble())
        return (1.0 / (1.0 + exp)).toFloat()
    }

    // v3.1.150-fix: 响度归一化：计算RMS并归一化到目标RMS
    // 新增增益上限MAX_NORMALIZE_GAIN和值域钳位MAX_YAMNET_INPUT_VALUE，
    // 防止极静音频产生超大值导致TFLite原生推理挂死
    private fun normalizeLoudness(samples: FloatArray, targetRms: Float = 0.15f): FloatArray {
        var sumSq = 0.0
        for (s in samples) {
            sumSq += s.toDouble() * s.toDouble()
        }
        val rms = kotlin.math.sqrt(sumSq / samples.size)
        if (rms < 1e-6f) return samples.copyOf() // 避免除零
        // v3.1.150-fix: 限制最大增益，防止极静音频产生超大值导致TFLite推理挂死
        val rawGain = targetRms / rms.toFloat()
        val gain = kotlin.math.min(rawGain, MAX_NORMALIZE_GAIN)
        val result = FloatArray(samples.size)
        for (i in samples.indices) {
            result[i] = (samples[i] * gain).coerceIn(-MAX_YAMNET_INPUT_VALUE, MAX_YAMNET_INPUT_VALUE)
        }
        return result
    }

    // v3.1.99: 计算人声频谱比值（1kHz~4kHz能量 / 全频能量）
    // 对15600样本补零到16384做基2FFT，计算频谱能量比
    private fun computeSpectrumRatio(samples: FloatArray): Float {
        val n = 16384 // 2^14，大于15600的最小2的幂
        if (samples.size < 100) return 0f // 样本太少，无法计算

        val real = FloatArray(n)
        val imag = FloatArray(n)
        // 补零
        for (i in 0 until minOf(samples.size, n)) {
            real[i] = samples[i]
        }

        // 基2FFT（Cooley-Tukey迭代算法）
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val temp = real[i]; real[i] = real[j]; real[j] = temp
            }
        }

        var len = 1
        while (len < n) {
            val step = len shl 1
            val angle = -Math.PI / len
            for (i in 0 until n step step) {
                for (k in 0 until len) {
                    val wReal = kotlin.math.cos(k * angle).toFloat()
                    val wImag = kotlin.math.sin(k * angle).toFloat()
                    val idx = i + k
                    val tReal = real[idx + len] * wReal - imag[idx + len] * wImag
                    val tImag = real[idx + len] * wImag + imag[idx + len] * wReal
                    real[idx + len] = real[idx] - tReal
                    imag[idx + len] = imag[idx] - tImag
                    real[idx] += tReal
                    imag[idx] += tImag
                }
            }
            len = step
        }

        // 计算能量
        val nyquist = n / 2
        val bin1k = (1000 * n / YAMNET_SAMPLE_RATE)  // 1kHz对应的bin索引
        val bin4k = (4000 * n / YAMNET_SAMPLE_RATE)  // 4kHz对应的bin索引
        var totalEnergy = 0.0
        var voiceBandEnergy = 0.0
        for (i in 0 until nyquist) {
            val energy = real[i].toDouble() * real[i].toDouble() + imag[i].toDouble() * imag[i].toDouble()
            totalEnergy += energy
            if (i in bin1k..bin4k) {
                voiceBandEnergy += energy
            }
        }

        return if (totalEnergy > 0.0) (voiceBandEnergy / totalEnergy).toFloat() else 0f
    }

    // v2.4.161: Time range for coarse VAD segmentation
    // v3.1.83: 改为public，供SegmentGenerator使用runVadOnly的返回类型
    data class TimeRange(val startMs: Long, val endMs: Long) {
        val durationMs: Long get() = endMs - startMs
    }

    /**
     * v3.1.104: 新优先级体系。
     *
     * 判定优先级（从高到低）：
     * 1. 静音检测：silence > 0.60 且 speech < 0.15 → SILENCE
     * 2. 频谱比值 > 0.16 → DRY（连续3帧生效由classifyIntervalRange保证）
     * 3. VAD语音帧占比 > 20% → speech_prob锁定0.30
     * 4. (music - effectiveSpeechScore) > 0.42 且 effectiveSpeechScore < 0.35 → WATER
     * 5. 其余 → DRY（模糊段，等指纹二次校验）
     *
     * 上下文保护（classifyIntervalRange中执行）：
     * - 人声保护范围仅前后1.5秒
     * - 仅spectrumRatio>0.16（实际人声）触发保护
     * - 间隔超出1.5s不再触发上下文豁免
     *
     * @param enableSpectrumCheck 是否启用频谱比值检查（用于classifyIntervalRange的3帧约束）
     */
    private fun classifyYamnetScores(yamnet: YamnetResult, enableSpectrumCheck: Boolean = true): FrameType {
        // 直接使用YAMNet原始sigmoid概率值
        val speechScore = maxOf(yamnet.speech, yamnet.narration)

        // 优先级1：静音检测
        if (yamnet.silence > 0.60f && yamnet.speech < 0.15f) {
            return FrameType.SILENCE
        }

        // 优先级2：频谱比值 > 0.16 → DRY（连续3帧生效由外层保证）
        if (enableSpectrumCheck && yamnet.spectrumRatio > 0.16f) {
            return FrameType.DRY
        }

        // 优先级3：VAD语音帧占比 > 20% → speech_prob锁定0.30
        val effectiveSpeechScore = if (vadSpeechRatio > 0.20f) {
            maxOf(speechScore, 0.30f)
        } else {
            speechScore
        }

        // 优先级4：YAMNet判定
        if ((yamnet.music - effectiveSpeechScore) > 0.42f && effectiveSpeechScore < 0.35f) {
            return FrameType.WATER
        }

        // 优先级5：其余 → DRY（模糊段，等指纹二次校验）
        return FrameType.DRY
    }

    /**
     * v2.4.161: Run Silero VAD over the whole audio to produce coarse speech/silence intervals.
     */
    private fun runSileroVadIntervals(
        samples: SampleProvider,
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
            checkCancelled()
            val chunk = samples.copyOfRange(pos, pos + VAD_FRAME_SIZE)
            val (prob, newState, newContext) = try {
                runSileroVad(vadModel, chunk, vadContext, vadState)
            } catch (e: Throwable) {
                vadLog("runSileroVad 崩溃在位置${pos}: ${e.javaClass.name}: ${e.message}")
                // 返回默认值，继续处理
                Triple(0.5f, vadState, vadContext)
            }
            vadState = newState
            vadContext = newContext
            probs.add(prob)
            pos += VAD_FRAME_SIZE

            if (onProgress != null && pos >= reportPos) {
                onProgress((pos.toLong() * 1000L / totalSamples).toInt().coerceIn(0, 1000))
                reportPos += YAMNET_SAMPLE_RATE * 5
            }
        }

        // v3.1.99: 计算VAD语音帧占比
        val speechCount = probs.count { it >= VAD_THRESHOLD }
        vadSpeechRatio = if (probs.isNotEmpty()) speechCount.toFloat() / probs.size else 0f
        vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] runSileroVadIntervals: VAD语音帧占比 = ${String.format(java.util.Locale.US, "%.2f", vadSpeechRatio)} (${speechCount}/${probs.size}帧 >= VAD_THRESHOLD)")

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
     * v2.4.170/v3.1.94: Classify a speech interval sparsely with YAMNet (0.975s window, 2.0s hop)
     * and return a single segment per interval using majority vote.
     *
     * Per-hop sub-segmentation produced too many fragments for the user's use case.
     * Since Silero VAD already provides coarse content boundaries, we classify the
     * whole interval by the dominant frame type. This keeps the boundary precision
     * of VAD while avoiding YAMNet flicker inside an interval.
     *
     * v3.1.94: Removed the <5s early-return default. Short intervals now go through
     * normal YAMNet classification; the post-processing merge logic (Pass 2/4/5/6)
     * consolidates small fragments with adjacent segments of the same type.
     * This eliminates the arbitrary default assignment and lets YAMNet's actual
     * classification determine the type, consistent with the dual-model scheme.
     *
     * Bias: speech intervals are assumed to be host speech unless music is clearly
     * dominant. Tie or slight dry lead keeps host talking continuous.
     */
    private fun classifySpeechInterval(
        samples: SampleProvider,
        range: TimeRange,
        progressCallback: ((subProgress: Int) -> Unit)? = null
    ): List<VoiceSegment> {
        val startSample = (range.startMs * YAMNET_SAMPLE_RATE / 1000L).toInt()
        val endSample = (range.endMs * YAMNET_SAMPLE_RATE / 1000L).toInt()
        val intervalEndSample = endSample.coerceAtMost(samples.size)
        val intervalEndMs = (intervalEndSample.toLong() * 1000L / YAMNET_SAMPLE_RATE)
        val totalIntervalSamples = intervalEndSample - startSample

        // Interval too short for a full YAMNet window: VAD already marked it as speech, default to dry.
        if (totalIntervalSamples < YAMNET_WINDOW_SAMPLES) {
            vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] Speech interval ${formatDurationMs(range.startMs)}-${formatDurationMs(range.endMs)}: too short for YAMNet (${totalIntervalSamples} samples), defaulting to dry")
            return listOf(createSegment(range.startMs, range.endMs, FrameType.DRY))
        }

        val typeVotes = mutableMapOf<FrameType, Int>()
        var lastProgressMs = 0L

        var pos = startSample.coerceIn(0, samples.size)
        while (pos + YAMNET_WINDOW_SAMPLES <= intervalEndSample && pos + YAMNET_WINDOW_SAMPLES <= samples.size) {
            checkCancelled()
            val window = samples.copyOfRange(pos, pos + YAMNET_WINDOW_SAMPLES)
            val yamnet: YamnetResult
            try {
                yamnet = classifyWithYamnet(window)
            } catch (e: RuntimeException) {
                // v3.1.147-fix: YAMNet连续超时/全局重载，跳过当前区间
                Log.w(TAG, "classifyIntervalRange: 跳过区间剩余帧: ${e.message}")
                break
            }
            val type = classifyYamnetScores(yamnet)
            typeVotes[type] = typeVotes.getOrDefault(type, 0) + 1
            pos += YAMNET_SPEECH_HOP_SAMPLES

            // v3.1.140-fix: 区间内定期回调进度，避免长时间卡在一个区间
            if (progressCallback != null) {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastProgressMs >= 5000) { // 每5秒回调一次
                    lastProgressMs = nowMs
                    val subProgress = ((pos - startSample) * 1000 / totalIntervalSamples).coerceIn(0, 1000)
                    progressCallback(subProgress)
                }
            }
        }

        val dryVotes = typeVotes.getOrDefault(FrameType.DRY, 0)
        val waterVotes = typeVotes.getOrDefault(FrameType.WATER, 0)
        val dominantType = when {
            // Tie or dry lead keeps host talking continuous; water must clearly win.
            dryVotes >= waterVotes -> FrameType.DRY
            else -> typeVotes.maxByOrNull { it.value }?.key ?: FrameType.DRY
        }
        val segment = createSegment(range.startMs, intervalEndMs, dominantType)
        vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] Speech interval ${formatDurationMs(range.startMs)}-${formatDurationMs(range.endMs)}: votes=$typeVotes -> $dominantType")
        return listOf(segment)
    }

    /**
     * v2.4.167: Sparse YAMNet sampling in a silence interval to find missed content.
     * Returns a single segment per interval using majority vote across the sparse samples.
     *
     * Like classifySpeechInterval, we avoid per-hit sub-segmentation to keep the segment
     * count low. If most sparse samples detect content, the whole interval is labeled
     * according to the dominant type; otherwise it stays silent.
     */
    private fun sampleSilenceInterval(
        samples: SampleProvider,
        range: TimeRange
    ): List<VoiceSegment> {
        val typeVotes = mutableMapOf<FrameType, Int>()

        var currentMs = range.startMs
        while (currentMs + SILENCE_SAMPLE_WINDOW_MS <= range.endMs) {
            checkCancelled()
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
            val yamnet = classifyWithYamnet(window)
            val type = classifyYamnetScores(yamnet)
            typeVotes[type] = typeVotes.getOrDefault(type, 0) + 1

            currentMs += SILENCE_SAMPLE_INTERVAL_MS
        }

        // No samples or all silence: default to silence.
        if (typeVotes.isEmpty() || (typeVotes.size == 1 && typeVotes.containsKey(FrameType.SILENCE))) {
            return listOf(createSegment(range.startMs, range.endMs, FrameType.SILENCE))
        }

        // Pick dominant non-silence type, falling back to silence on tie.
        val dominantType = typeVotes.filter { it.key != FrameType.SILENCE }
            .maxByOrNull { it.value }
            ?.key
            ?: FrameType.SILENCE
        val segment = createSegment(range.startMs, range.endMs, dominantType)
        vadLog("[${com.radio.app.RadioApplication.appVersionTag()}] Silence interval ${formatDurationMs(range.startMs)}-${formatDurationMs(range.endMs)}: votes=$typeVotes -> $dominantType")
        return listOf(segment)
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

    /**
     * v2.4.178: Abstract sample access so large PCM files can be analyzed without loading the
     * whole FloatArray into the Java heap. The default implementation memory-maps the PCM file
     * and converts 16-bit little-endian samples to floats on demand.
     */
    internal interface SampleProvider : Closeable {
        val size: Int
        fun copyOfRange(start: Int, end: Int): FloatArray
    }

    /**
     * v2.4.178: Memory-mapped PCM sample provider. The PCM file is mapped into off-heap memory
     * via NIO, so files much larger than the Java heap limit can be analyzed. Each call to
     * copyOfRange allocates only the requested small window on the Java heap.
     */
    internal class MappedPcmSampleProvider(pcmFile: File) : SampleProvider {
        private val raf: RandomAccessFile
        private val channel: FileChannel
        private val mapped: java.nio.MappedByteBuffer
        private val shortBuffer: ShortBuffer

        init {
            // 检查文件大小，超过500MB的PCM文件使用分块读取而非内存映射
            val fileSize = pcmFile.length()
            if (fileSize > 500 * 1024 * 1024L) {
                throw RuntimeException("PCM文件过大: ${fileSize / 1024 / 1024}MB，无法映射")
            }
            raf = RandomAccessFile(pcmFile, "r")
            channel = raf.channel
            mapped = try {
                channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize)
            } catch (e: Exception) {
                try { channel.close() } catch (_: Exception) {}
                try { raf.close() } catch (_: Exception) {}
                throw RuntimeException("PCM文件映射失败: ${e.message}", e)
            }
            shortBuffer = mapped.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        }

        override val size: Int = shortBuffer.remaining()

        override fun copyOfRange(start: Int, end: Int): FloatArray {
            val from = start.coerceIn(0, size)
            val to = end.coerceIn(from, size)
            val count = to - from
            val result = FloatArray(count)
            if (count > 0) {
                shortBuffer.position(from)
                val temp = ShortArray(count)
                shortBuffer.get(temp)
                for (i in temp.indices) {
                    result[i] = temp[i].toFloat() / 32768.0f
                }
            }
            return result
        }

        override fun close() {
            try { channel.close() } catch (_: Exception) {}
            try { raf.close() } catch (_: Exception) {}
        }
    }

    internal fun openPcmSamples(pcmFile: File): SampleProvider {
        // v2.4.178: Use memory-mapped sample provider to support large PCM files without OOM.
        return MappedPcmSampleProvider(pcmFile)
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
            // v3.1.44: 合并容差从1ms放宽至10ms，避免Silero VAD帧边界取整导致相邻同类型段未合并
            if (current.label == next.label && next.start <= current.end + 10) {
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
     * v2.4.161/v2.4.173: Post-process segments:
     * 1. Merge same-type overlapping/adjacent segments.
     * 2. Merge/remove fragments shorter than MIN_FRAGMENT_MS.
     * 3. Absorb short (<1s) water segments inside/between dry segments into dry.
     * 4. Merge dry segments separated by less than MAX_DRY_GAP_MS.
     * 5. Merge dry segments separated by short pure-silence gaps.
     * 6. Merge consecutive/nearby water segments separated by short pure-silence gaps.
     * 7. Final merge of adjacent same-type segments created by earlier absorption passes.
     */
    // v3.1.95: Made internal so SegmentGenerator (three-layer architecture) can also
    // apply the full post-processing merge logic (fragment merge, dry merging, water merging).
    internal fun postProcessSegments(segments: List<VoiceSegment>): List<VoiceSegment> {
        if (segments.isEmpty()) return segments

        // Pass 1: merge same-type overlapping/adjacent segments
        // v3.1.44: 合并容差从1ms放宽至10ms
        val sorted = segments.sortedBy { it.start }.map { it.copy() }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            for (i in 0 until sorted.size - 1) {
                val curr = sorted[i]
                val next = sorted[i + 1]
                if (curr.label == next.label && next.start <= curr.end + 10) {
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

        // Pass 3: v3.1.103 孤立水分片段 < 1s 归入模糊段（DRY）
        // v3.1.112: 只转标签不合并，防止层叠触发Pass4合并导致干货丢失
        changed = true
        while (changed) {
            changed = false
            for (i in sorted.indices) {
                val seg = sorted[i]
                if (seg.label == "水货" && seg.end - seg.start < MAX_PURE_MUSIC_GAP_MS) {
                    val prev = sorted.getOrNull(i - 1)
                    val next = sorted.getOrNull(i + 1)
                    if (prev?.label == "干货" && next?.label == "干货") {
                        // v3.1.112: 只转干货，不合并前后段，防止层叠触发Pass4合并
                        seg.label = "干货"
                        seg.hasVoice = true
                        changed = true
                        break
                    } else if (prev?.label == "干货" || next?.label == "干货") {
                        // v3.1.112: 只转干货，不合并到相邻干货段，保持独立分段
                        seg.label = "干货"
                        seg.hasVoice = true
                        changed = true
                        break
                    } else {
                        // 孤立水分片段 → 转为干货（模糊段，等指纹二次校验）
                        seg.label = "干货"
                        seg.hasVoice = true
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

        // Pass 5: v2.4.171 merge dry segments separated by short pure-silence gaps.
        // Radio broadcasts often contain 3-10s pauses between host sentences; keeping
        // those as separate "静音" segments bloats the segment count without adding value.
        changed = true
        while (changed) {
            changed = false
            for (i in 0 until sorted.size - 1) {
                val curr = sorted[i]
                if (curr.label != "干货") continue
                val gapSegments = mutableListOf<VoiceSegment>()
                var j = i + 1
                while (j < sorted.size) {
                    val mid = sorted[j]
                    if (mid.label == "干货") break
                    gapSegments.add(mid)
                    val gapMs = gapSegments.sumOf { it.end - it.start }
                    // Stop scanning once the gap is too long or contains non-silence.
                    if (gapMs > MAX_DRY_GAP_MS || !gapSegments.all { it.label == "静音" }) break
                    if (j + 1 < sorted.size && sorted[j + 1].label == "干货") {
                        val nextDry = sorted[j + 1]
                        curr.end = nextDry.end
                        repeat(j - i + 1) { sorted.removeAt(i + 1) }
                        changed = true
                        break
                    }
                    j++
                }
                if (changed) break
            }
        }

        // Pass 6: v2.4.173 merge consecutive/nearby water segments separated by short
        // pure-silence gaps. Ad breaks and song blocks are often split by a few seconds
        // of silence/jingle; keeping them as separate "水货" segments makes the list noisy.
        changed = true
        while (changed) {
            changed = false
            for (i in 0 until sorted.size - 1) {
                val curr = sorted[i]
                if (curr.label != "水货") continue
                val gapSegments = mutableListOf<VoiceSegment>()
                var j = i + 1
                while (j < sorted.size) {
                    val mid = sorted[j]
                    if (mid.label == "水货") break
                    gapSegments.add(mid)
                    val gapMs = gapSegments.sumOf { it.end - it.start }
                    // Only bridge gaps made entirely of silence and not longer than the threshold.
                    if (gapMs > MAX_WATER_GAP_MS || !gapSegments.all { it.label == "静音" }) break
                    if (j + 1 < sorted.size && sorted[j + 1].label == "水货") {
                        val nextWater = sorted[j + 1]
                        curr.end = nextWater.end
                        repeat(j - i + 1) { sorted.removeAt(i + 1) }
                        changed = true
                        break
                    }
                    j++
                }
                if (changed) break
            }
        }

        // Pass 7: v2.4.173 final merge of any adjacent same-type segments created by
        // earlier absorption passes (e.g. a short silence fragment absorbed into one of
        // two neighboring water segments, leaving two adjacent "水货" segments).
        // v3.1.44: 合并容差从1ms放宽至10ms
        changed = true
        while (changed) {
            changed = false
            for (i in 0 until sorted.size - 1) {
                val curr = sorted[i]
                val next = sorted[i + 1]
                if (curr.label == next.label && next.start <= curr.end + 10) {
                    curr.end = maxOf(curr.end, next.end)
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

    /**
     * v3.1.83: 运行VAD粗分段，返回全时间轴语音活动区间（speech ranges）。
     * 不加载YAMNet模型，不执行YAMNet推理。
     * 用于优化三层架构：VAD只输出活动段，YAMNet仅在指纹未覆盖区间∩VAD活动段上执行。
     */
    fun runVadOnly(
        context: Context,
        pcmFile: File,
        durationMs: Long,
        progressCallback: ((Int) -> Unit)? = null
    ): List<TimeRange> {
        if (!pcmFile.exists() || pcmFile.length() < 16000) {
            Log.w(TAG, "runVadOnly: PCM文件太小或不存在: ${pcmFile.absolutePath}")
            return emptyList()
        }

        // v3.1.86: 验证PCM采样率 - 检查文件大小与预期时长是否匹配16kHz
        val pcmBytes = pcmFile.length()
        val expectedBytesAt16kHz = (durationMs * 16000L * 2L / 1000L)
        val actualDurationMsAt16kHz = pcmBytes * 1000L / (16000L * 2L)
        val ratio = pcmBytes.toDouble() / expectedBytesAt16kHz.toDouble()
        Log.i(TAG, "runVadOnly: PCM采样率验证: pcmBytes=$pcmBytes, expectedBytesAt16kHz=$expectedBytesAt16kHz, " +
                "actualDurationMsAt16kHz=$actualDurationMsAt16kHz, durationMs=$durationMs, ratio=$ratio")
        if (ratio < 0.5 || ratio > 2.0) {
            Log.w(TAG, "runVadOnly: PCM采样率异常! ratio=$ratio, 预期16kHz但实际可能不是16kHz for ${pcmFile.name}")
        }

        if (!NativeLibLoader.ensureLoaded(context)) {
            Log.e(TAG, "runVadOnly: Native libraries not loaded.")
            return emptyList()
        }

        val modelDir = getModelDir(context)
        if (!isSileroVadInstalled(modelDir)) {
            Log.w(TAG, "runVadOnly: VAD模型未安装")
            return emptyList()
        }

        val vadModel = try {
            loadSileroVad(File(modelDir, "silero_vad.onnx"))
        } catch (e: Throwable) {
            Log.e(TAG, "runVadOnly: loadSileroVad failed: ${e.message}")
            return emptyList()
        }

        try {
            val samples = try {
                openPcmSamples(pcmFile)
            } catch (e: Throwable) {
                Log.e(TAG, "runVadOnly: openPcmSamples 失败: ${e.message}")
                return emptyList()
            }
            samples.use { samplesProvider ->
                if (samplesProvider.size < VAD_FRAME_SIZE) {
                    Log.w(TAG, "runVadOnly: PCM太短: ${samplesProvider.size} samples")
                    return emptyList()
                }

                val speechRanges: List<TimeRange>
                try {
                    val result = runSileroVadIntervals(samplesProvider, vadModel) { permille ->
                        progressCallback?.invoke(permille)
                    }
                    speechRanges = result.first
                } catch (e: InterruptedException) {
                    // v3.1.110: 捕获InterruptedException，返回空列表让调用方自然处理
                    // 调用方会在speechRanges.isEmpty()时使用pending段作为YAMNet区间
                    Log.w(TAG, "runVadOnly: VAD被中断: ${e.message}")
                    writeFingerprintLog("runVadOnly: VAD被中断: ${e.message}")
                    val sw = java.io.StringWriter()
                    val pw = java.io.PrintWriter(sw)
                    e.printStackTrace(pw)
                    writeFingerprintLog("runVadOnly: VAD中断调用栈:\n${sw.toString().take(500)}")
                    return emptyList()
                }

                // v3.1.86: 详细日志输出VAD活动段信息
                val totalSpeechMs = speechRanges.sumOf { it.durationMs }
                val speechDetail = if (speechRanges.size <= 10) {
                    speechRanges.joinToString("; ") { "${it.startMs}~${it.endMs}ms(${it.durationMs}ms)" }
                } else {
                    "${speechRanges.size}个段, 总时长${totalSpeechMs}ms, 首段[${speechRanges.first().startMs}~${speechRanges.first().endMs}ms], 末段[${speechRanges.last().startMs}~${speechRanges.last().endMs}ms]"
                }
                Log.i(TAG, "runVadOnly: VAD产出${speechRanges.size}个活动段, 总${totalSpeechMs}ms: $speechDetail")

                return speechRanges
            }
        } finally {
            try { vadModel.session.close() } catch (_: Exception) {}
        }
    }

    /**
     * v3.1.83: 对单个PCM区间执行YAMNet子段提取推理。
     * 使用零拷贝内存映射PCM视图，前后各加0.5s缓冲padding，推理完成后裁回原始边界。
     * 返回该区间内的子段列表（DRY/WATER/SILENCE），坐标已回填到原始时间轴。
     *
     * @param context 上下文
     * @param pcmFile 完整PCM文件（内存映射）
     * @param intervalStartMs 区间起始时间（毫秒，原始时间轴）
     * @param intervalEndMs 区间结束时间（毫秒，原始时间轴）
     * @param progressCallback 进度回调
     * @return 子段列表，坐标已回填到原始时间轴
     */
    fun classifyPcmInterval(
        context: Context,
        pcmFile: File,
        intervalStartMs: Long,
        intervalEndMs: Long,
        progressCallback: ((Int) -> Unit)? = null
    ): List<VoiceSegment> {
        if (!pcmFile.exists() || pcmFile.length() < 16000) {
            Log.w(TAG, "classifyPcmInterval: PCM文件不存在 or 太小: ${pcmFile.absolutePath}")
            return emptyList()
        }

        // v3.1.86: 验证PCM采样率
        val pcmBytes = pcmFile.length()
        val pcmDurationMsAt16kHz = pcmBytes * 1000L / (16000L * 2L)
        val intervalDurationMs = intervalEndMs - intervalStartMs
        if (intervalStartMs > pcmDurationMsAt16kHz) {
            Log.w(TAG, "classifyPcmInterval: 区间起始时间(${intervalStartMs}ms)超过PCM时长(${pcmDurationMsAt16kHz}ms)，可能采样率不匹配 for ${pcmFile.name}")
        }

        if (intervalDurationMs < 1500) {
            Log.d(TAG, "classifyPcmInterval: 区间太短(${intervalDurationMs}ms)，跳过")
            return emptyList()
        }

        try {
            val samples = openPcmSamples(pcmFile)
            samples.use { samplesProvider ->
                return classifyPcmInterval(samplesProvider, intervalStartMs, intervalEndMs, progressCallback)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "classifyPcmInterval failed: ${e.message}")
            return emptyList()
        }
    }

    /**
     * v3.1.92: classifyPcmInterval的重载版本，使用已打开的SampleProvider，避免重复打开PCM文件。
     * 调用者负责关闭SampleProvider。
     */
    internal fun classifyPcmInterval(
        samples: SampleProvider,
        intervalStartMs: Long,
        intervalEndMs: Long,
        progressCallback: ((Int) -> Unit)? = null
    ): List<VoiceSegment> {
        val intervalDurationMs = intervalEndMs - intervalStartMs
        if (intervalDurationMs < 1500) {
            Log.d(TAG, "classifyPcmInterval: 区间太短(${intervalDurationMs}ms)，跳过")
            return emptyList()
        }
        try {
            return classifyPcmIntervalInner(samples, intervalStartMs, intervalEndMs, progressCallback)
        } catch (e: Throwable) {
            Log.e(TAG, "classifyPcmInterval(samples) failed: ${e.message}")
            return emptyList()
        }
    }

    /**
     * v3.1.115: 对单个PCM区间执行YAMNet多数投票分类。
     * 使用滑动窗口对区间内所有帧做YAMNet推理，然后统计干/水/静音票数，取多数作为整区间类型。
     * 返回单个VoiceSegment（1段/区间），消除classifyIntervalRange逐帧分类产生的交替子段问题。
     * 与旧方案classifySpeechInterval的多数投票策略一致，但使用已打开的SampleProvider。
     */
    internal fun classifyIntervalMajority(
        samples: SampleProvider,
        intervalStartMs: Long,
        intervalEndMs: Long
    ): VoiceSegment? {
        val intervalDurationMs = intervalEndMs - intervalStartMs
        if (intervalDurationMs < 1500) return null

        val startSample = (intervalStartMs * YAMNET_SAMPLE_RATE / 1000L).toInt().coerceIn(0, samples.size)
        val endSample = (intervalEndMs * YAMNET_SAMPLE_RATE / 1000L).toInt().coerceIn(0, samples.size)
        if (endSample - startSample < YAMNET_WINDOW_SAMPLES) return null

        val typeVotes = mutableMapOf<FrameType, Int>()

        var pos = startSample
        var skippedIntervals = 0
        while (pos + YAMNET_WINDOW_SAMPLES <= endSample && pos + YAMNET_WINDOW_SAMPLES <= samples.size) {
            val window = samples.copyOfRange(pos, pos + YAMNET_WINDOW_SAMPLES)
            val yamnet: YamnetResult
            try {
                yamnet = classifyWithYamnet(window)
            } catch (e: RuntimeException) {
                // v3.1.147-fix: YAMNet连续超时/全局重载，跳过当前区间
                Log.w(TAG, "classifyIntervalMajority: 跳过区间 ${intervalStartMs}~${intervalEndMs}ms: ${e.message}")
                skippedIntervals++
                break
            }
            // v3.1.147-fix: 如果返回默认结果且连续超时计数>0，说明最近发生过超时，记录但不中断
            if (consecutiveTimeoutCount > 0) {
                skippedIntervals++
            }
            val type = classifyYamnetScores(yamnet)
            typeVotes[type] = typeVotes.getOrDefault(type, 0) + 1
            pos += YAMNET_SPEECH_HOP_SAMPLES
        }

        if (skippedIntervals > 0) {
            Log.w(TAG, "classifyIntervalMajority: 区间 ${intervalStartMs}~${intervalEndMs}ms 跳过${skippedIntervals}个帧，剩余${typeVotes.size}个投票")
        }

        if (typeVotes.isEmpty()) return null

        val dryVotes = typeVotes.getOrDefault(FrameType.DRY, 0)
        val waterVotes = typeVotes.getOrDefault(FrameType.WATER, 0)
        val dominantType = when {
            // Tie or dry lead keeps host talking continuous; water must clearly win.
            dryVotes >= waterVotes -> FrameType.DRY
            else -> typeVotes.maxByOrNull { it.value }?.key ?: FrameType.DRY
        }

        return createSegment(intervalStartMs, intervalEndMs, dominantType)
    }

    /**
     * v3.1.83: classifyPcmInterval的内部实现，使用已打开的SampleProvider。
     * 前后各加0.5s缓冲padding，零拷贝切片，推理完成后裁回原始边界。
     */
    internal fun classifyPcmIntervalInner(
        samples: SampleProvider,
        intervalStartMs: Long,
        intervalEndMs: Long,
        progressCallback: ((Int) -> Unit)? = null
    ): List<VoiceSegment> {
        val totalSamples = samples.size
        val sampleRate = YAMNET_SAMPLE_RATE

        // 0.5s padding = 8000 samples at 16kHz
        val paddingSamples = (sampleRate / 2).toInt()

        // 计算带padding的样本范围
        val startSample = ((intervalStartMs * sampleRate / 1000L) - paddingSamples).toInt().coerceIn(0, totalSamples)
        val endSample = ((intervalEndMs * sampleRate / 1000L) + paddingSamples).toInt().coerceIn(0, totalSamples)

        // 原始区间样本范围（无padding）
        val origStartSample = (intervalStartMs * sampleRate / 1000L).toInt().coerceIn(0, totalSamples)
        val origEndSample = (intervalEndMs * sampleRate / 1000L).toInt().coerceIn(0, totalSamples)

        // 如果带padding的范围不足以容纳一个YAMNet窗口，直接返回
        if (endSample - startSample < YAMNET_WINDOW_SAMPLES) {
            // 尝试用无padding的样本
            if (origEndSample - origStartSample < YAMNET_WINDOW_SAMPLES) {
                Log.d(TAG, "classifyPcmInterval: 区间太短(${intervalEndMs - intervalStartMs}ms)，无法执行YAMNet，totalSamples=$totalSamples, origRange=${origEndSample - origStartSample}")
                return emptyList()
            }
            // v3.1.93: refStartMs=0L，因为PCM文件从时间0开始，样本索引直接对应绝对时间
            return classifyIntervalRange(samples, origStartSample, origEndSample, 0L, progressCallback)
        }

        // 使用带padding的区间执行YAMNet推理
        // v3.1.93: refStartMs=0L，PCM文件从时间0开始，样本索引直接对应绝对时间
        val segments = classifyIntervalRange(samples, startSample, endSample, 0L, progressCallback)

        // 裁回原始边界：修正子段坐标，移除超出原始区间的部分
        val trimmed = mutableListOf<VoiceSegment>()
        for (seg in segments) {
            val clippedStart = maxOf(seg.start, intervalStartMs)
            val clippedEnd = minOf(seg.end, intervalEndMs)
            if (clippedEnd - clippedStart >= 500) { // 保留至少500ms的片段
                trimmed.add(VoiceSegment().apply {
                    start = clippedStart
                    end = clippedEnd
                    hasVoice = seg.hasVoice
                    label = seg.label
                    isSimulated = false
                })
            }
        }

        // 合并同类型相邻子段（裁切后可能产生边界碎片）
        if (trimmed.size <= 1) return trimmed
        val merged = mutableListOf(trimmed[0])
        for (i in 1 until trimmed.size) {
            val last = merged.last()
            val cur = trimmed[i]
            if (last.label == cur.label && cur.start <= last.end + 10) {
                last.end = maxOf(last.end, cur.end)
            } else {
                merged.add(cur)
            }
        }
        return merged
    }

    /**
     * v3.1.124: 对单个VAD区间YAMNet产出的子段应用后处理规则2/3/4。
     *
     * 规则2：＜1.5秒短段直接合并到相邻主导段（左右取更长者）
     * 规则3：单VAD区间水分占比＜30%全合并为干货，单区间最多3段子段
     * 规则4：交替结构中间段＜2秒直接合并
     *
     * @param segments 单个VAD区间YAMNet产出的子段列表（已排序）
     * @return 后处理后的子段列表
     */
    internal fun postProcessYamnetSubSegments(segments: List<VoiceSegment>): List<VoiceSegment> {
        if (segments.size <= 1) return segments

        // 深拷贝，避免修改原始数据
        var result = segments.map { it.copy() }.toMutableList()

        // ===== 规则4：交替结构中间段＜2秒直接合并 =====
        // 先做交替合并，因为短中间段是最常见的交替碎片
        var changed = true
        while (changed) {
            changed = false
            val newList = mutableListOf<VoiceSegment>()
            var i = 0
            while (i < result.size) {
                if (i > 0 && i < result.size - 1) {
                    val prev = result[i - 1]
                    val cur = result[i]
                    val next = result[i + 1]
                    // 检查是否是交替模式：prev.label == next.label 且 cur.label != prev.label
                    if (prev.label == next.label && cur.label != prev.label) {
                        val curDuration = cur.end - cur.start
                        if (curDuration < 2000) {
                            // 中间段＜2s，合并到前后段（延长prev的end）
                            prev.end = next.end
                            newList.add(prev)
                            i += 2 // 跳过cur和next
                            changed = true
                            continue
                        }
                    }
                }
                newList.add(result[i])
                i++
            }
            result = newList
        }

        // ===== 规则2：＜1.5秒短段直接合并到相邻主导段 =====
        changed = true
        while (changed) {
            changed = false
            val newList = mutableListOf<VoiceSegment>()
            var i = 0
            while (i < result.size) {
                val cur = result[i]
                val curDuration = cur.end - cur.start
                if (curDuration >= 1500) {
                    newList.add(cur)
                    i++
                    continue
                }
                // 短段＜1.5s，需要合并
                val hasPrev = i > 0
                val hasNext = i < result.size - 1

                if (hasPrev && hasNext) {
                    val prev = result[i - 1]
                    val next = result[i + 1]
                    val prevDuration = prev.end - prev.start
                    val nextDuration = next.end - next.start
                    // 合并到相邻更长的段
                    if (prevDuration >= nextDuration) {
                        // 合并到前一段
                        prev.end = cur.end
                    } else {
                        // 合并到后一段，后一段的start前移
                        next.start = cur.start
                    }
                } else if (hasPrev) {
                    // 只有前一段，合并到前一段
                    result[i - 1].end = cur.end
                } else if (hasNext) {
                    // 只有后一段，合并到后一段
                    result[i + 1].start = cur.start
                }
                // 移除当前短段
                changed = true
                i++
            }
            // 重新收集（由于合并修改了相邻段的边界，需要重新构建列表）
            if (changed) {
                val rebuilt = mutableListOf<VoiceSegment>()
                for (seg in result) {
                    if (rebuilt.isNotEmpty() && rebuilt.last().label == seg.label && rebuilt.last().end >= seg.start) {
                        // 合并相邻同类型重叠段
                        rebuilt.last().end = maxOf(rebuilt.last().end, seg.end)
                    } else {
                        rebuilt.add(seg.copy())
                    }
                }
                result = rebuilt
            }
        }

        // ===== 规则3：单VAD区间水分占比＜30%全合并为干货 =====
        val totalDuration = result.sumOf { it.end - it.start }
        val waterDuration = result.filter { !it.hasVoice }.sumOf { it.end - it.start }
        val waterRatio = if (totalDuration > 0) waterDuration.toFloat() / totalDuration else 0f

        if (waterRatio < 0.30f) {
            // 水分占比＜30%，全合并为干货
            val mergedStart = result.minOf { it.start }
            val mergedEnd = result.maxOf { it.end }
            return listOf(createSegment(mergedStart, mergedEnd, FrameType.DRY))
        }

        // 单区间最多3段子段：如果超过3段，合并相邻同类型
        if (result.size > 3) {
            val merged = mutableListOf(result.first())
            for (i in 1 until result.size) {
                val last = merged.last()
                val cur = result[i]
                if (last.label == cur.label) {
                    last.end = maxOf(last.end, cur.end)
                } else {
                    merged.add(cur)
                }
            }
            // 如果合并后还是超过3段，强制合并最短的相邻同类型段
            while (merged.size > 3) {
                var minDuration = Long.MAX_VALUE
                var mergeIdx = -1
                for (i in 0 until merged.size - 1) {
                    // 找到最短的段，合并到邻居
                    val d = merged[i].end - merged[i].start
                    if (d < minDuration) {
                        minDuration = d
                        mergeIdx = i
                    }
                }
                if (mergeIdx < 0) break
                // 将最短段合并到后一段
                val shortest = merged.removeAt(mergeIdx)
                if (mergeIdx < merged.size) {
                    merged[mergeIdx].start = shortest.start
                } else if (mergeIdx > 0) {
                    merged[mergeIdx - 1].end = shortest.end
                } else {
                    merged.add(shortest) // 不应该发生
                }
            }
            return merged
        }

        return result
    }

    /**
     * v3.1.83: 在指定的样本范围内执行YAMNet密集分类，返回子段列表。
     * 使用滑动窗口（窗口大小=YAMNET_WINDOW_SAMPLES，步长=YAMNET_SPEECH_HOP_SAMPLES）。
     * 子段坐标已回填到以refStartMs为基准的原始时间轴。
     * v3.1.103: 新增频谱比值连续3帧约束、上下文窗口7s/1.5s。
     * v3.1.124: 新增5帧滑动均值滤波（YAMNet得分先平滑再分类）。
     */
    private fun classifyIntervalRange(
        samples: SampleProvider,
        rangeStartSample: Int,
        rangeEndSample: Int,
        refStartMs: Long,
        progressCallback: ((Int) -> Unit)? = null
    ): List<VoiceSegment> {
        // 第一阶段：收集所有帧的原始YAMNet得分（不立即分类）
        data class RawFrameScores(
            val timestampMs: Long,
            val speech: Float, val narration: Float, val singing: Float,
            val music: Float, val silence: Float,
            val spectrumRatio: Float
        )
        val rawScores = mutableListOf<RawFrameScores>()

        // v3.1.150-fix: 预暖推理——使用实际第一帧数据验证interpreter可用
        // 如果第一帧数据导致超时，会触发reload并重试，最多2次后仍失败则跳过整个区间
        var pos = rangeStartSample
        val totalSamples = rangeEndSample - rangeStartSample
        var lastProgressMs = 0L
        // v3.1.149-fix: 单个区间最长时间限制（30秒），防止YAMNet推理卡死
        val intervalStartTimeMs = System.currentTimeMillis()
        val MAX_INTERVAL_MS = 30_000L
        // v3.1.149-fix: 记录区间内每个classifyWithYamnet调用的耗时，用于诊断
        var intervalFrameCount = 0
        var intervalTimeoutCount = 0

        // v3.1.150-fix: 用实际第一帧数据做预暖，比随机数据更可靠
        if (pos + YAMNET_WINDOW_SAMPLES <= rangeEndSample && pos + YAMNET_WINDOW_SAMPLES <= samples.size) {
            var warmupOk = false
            for (warmupTry in 1..2) {
                if (warmupTry > 1) {
                    Log.w(TAG, "classifyIntervalRange: 预暖第${warmupTry}次尝试（重载interpreter后）")
                }
                val warmupWindow = samples.copyOfRange(pos, pos + YAMNET_WINDOW_SAMPLES)
                var nonZero = 0; var maxVal = 0f
                for (s in warmupWindow) { if (s != 0f) nonZero++; if (kotlin.math.abs(s) > maxVal) maxVal = kotlin.math.abs(s) }
                val warmupResult = try {
                    classifyWithYamnet(warmupWindow)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "classifyIntervalRange: 预暖推理超时/失败(第${warmupTry}次, 非零样本=${nonZero}, maxAbs=${"%.4f".format(maxVal)}): ${e.message}")
                    reloadYamnetInterpreter()
                    null
                }
                if (warmupResult != null) {
                    warmupOk = true
                    Log.d(TAG, "classifyIntervalRange: 预暖推理成功(第${warmupTry}次), 非零样本=${nonZero}, maxAbs=${"%.4f".format(maxVal)}, voiceSum=${"%.2f".format(warmupResult.voiceSum)}")
                    break
                }
            }
            if (!warmupOk) {
                Log.w(TAG, "classifyIntervalRange: 预暖推理全部失败，跳过当前区间，pos=${pos}, rangeEndSample=${rangeEndSample}")
                return emptyList()
            }
            // 预暖成功，第一帧已经处理，直接将结果加入rawScores
            val windowCenterSample = pos + YAMNET_WINDOW_SAMPLES / 2
            val windowCenterMs = 0L + (windowCenterSample.toLong() * 1000L / YAMNET_SAMPLE_RATE)
            // 从预暖结果重建：需要保存预暖的YamnetResult
            // 由于预暖在for循环中，重新调用一次来获取结果（不会超时，因为刚预暖成功）
            // 或者直接推进pos不加入结果——安全起见，重新调用一次
            try {
                val warmupScores = classifyWithYamnet(samples.copyOfRange(pos, pos + YAMNET_WINDOW_SAMPLES))
                rawScores.add(RawFrameScores(
                    timestampMs = windowCenterMs,
                    speech = warmupScores.speech,
                    narration = warmupScores.narration,
                    singing = warmupScores.singing,
                    music = warmupScores.music,
                    silence = warmupScores.silence,
                    spectrumRatio = warmupScores.spectrumRatio
                ))
                intervalFrameCount++
            } catch (_: Exception) {
                // 预暖后第二次调用不应失败，但以防万一
                Log.w(TAG, "classifyIntervalRange: 预暖后第二次调用失败，跳过预暖帧")
            }
            pos += YAMNET_SPEECH_HOP_SAMPLES
        }

        while (pos + YAMNET_WINDOW_SAMPLES <= rangeEndSample && pos + YAMNET_WINDOW_SAMPLES <= samples.size) {
            checkCancelled()
            // v3.1.149-fix: 超时保护——单个区间运行超过30秒则跳过
            if (System.currentTimeMillis() - intervalStartTimeMs >= MAX_INTERVAL_MS) {
                Log.w("AudioSegmentAnalyzer", "classifyIntervalRange: 区间超时(${MAX_INTERVAL_MS / 1000}秒)，已处理${intervalFrameCount}帧，跳过剩余${(rangeEndSample - pos) / YAMNET_SAMPLE_RATE}秒音频")
                break
            }
            val window = samples.copyOfRange(pos, pos + YAMNET_WINDOW_SAMPLES)
            // v3.1.147-fix: 添加try-catch，捕获classifyWithYamnet抛出的连续超时/全局重载异常
            // v3.1.150-fix: 超时后自动重试一次（重载interpreter后重试同一帧）
            var yamnet: YamnetResult? = null
            var frameSucceeded = false
            for (retry in 0..1) {
                try {
                    yamnet = classifyWithYamnet(window)
                    frameSucceeded = true
                    break
                } catch (e: RuntimeException) {
                    if (retry == 0 && e.message?.contains("跳过") == true) {
                        // 第一次失败：重载interpreter后重试同一帧
                        Log.w("AudioSegmentAnalyzer", "classifyIntervalRange: 推理超时/失败，重载interpreter后重试同一帧: ${e.message}")
                        reloadYamnetInterpreter()
                        // 继续重试
                    } else {
                        // 第二次失败或非跳过异常：跳过整个区间
                        intervalTimeoutCount++
                        Log.w("AudioSegmentAnalyzer", "classifyIntervalRange: 跳过区间剩余帧(第${intervalTimeoutCount}次): ${e.message}")
                        frameSucceeded = false
                        break
                    }
                }
            }
            if (!frameSucceeded || yamnet == null) {
                break
            }

            intervalFrameCount++
            // 窗口中心时间戳（相对于refStartMs）
            val windowCenterSample = pos + YAMNET_WINDOW_SAMPLES / 2
            val windowCenterMs = refStartMs + (windowCenterSample.toLong() * 1000L / YAMNET_SAMPLE_RATE)
            rawScores.add(RawFrameScores(
                timestampMs = windowCenterMs,
                speech = yamnet!!.speech,
                narration = yamnet!!.narration,
                singing = yamnet!!.singing,
                music = yamnet!!.music,
                silence = yamnet!!.silence,
                spectrumRatio = yamnet!!.spectrumRatio
            ))
            pos += YAMNET_SPEECH_HOP_SAMPLES

            // v3.1.142-fix: 区间内定期回调进度，避免长时间卡在单个大区间
            if (progressCallback != null) {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastProgressMs >= 5000) { // 每5秒回调一次
                    lastProgressMs = nowMs
                    val subProgress = ((pos - rangeStartSample) * 1000 / totalSamples).coerceIn(0, 1000)
                    progressCallback(subProgress)
                }
            }
        }

        if (rawScores.isEmpty()) return emptyList()

        // 第二阶段：5帧滑动均值滤波
        // 对每个关键得分字段，frame[i] = average of frame[i-2]..frame[i+2]，边界处取有效帧平均
        val n = rawScores.size
        fun smoothed(index: Int, extractor: (RawFrameScores) -> Float): Float {
            var sum = 0f
            var count = 0
            val start = maxOf(0, index - 2)
            val end = minOf(n - 1, index + 2)
            for (j in start..end) {
                sum += extractor(rawScores[j])
                count++
            }
            return sum / count
        }

        // 第三阶段：用平滑后的得分构造YamnetResult并调用classifyYamnetScores分类
        data class FrameInfo(
            val timestampMs: Long,
            val type: FrameType,          // 完整判定（含频谱检查）
            val typeNoSpectrum: FrameType, // 无频谱判定的YAMNet-only判定
            val isSpeechContaining: Boolean,
            val spectrumRatio: Float       // 平滑后的频谱比值，用于3帧约束
        )
        val frames = mutableListOf<FrameInfo>()

        for (i in rawScores.indices) {
            val smoothSpeech = smoothed(i) { it.speech }
            val smoothNarration = smoothed(i) { it.narration }
            val smoothSinging = smoothed(i) { it.singing }
            val smoothMusic = smoothed(i) { it.music }
            val smoothSilence = smoothed(i) { it.silence }
            val smoothSpectrumRatio = smoothed(i) { it.spectrumRatio }

            // 构造平滑后的YamnetResult对象（仅填充classifyYamnetScores使用的字段）
            val smoothedYamnet = YamnetResult(
                speech = smoothSpeech,
                narration = smoothNarration,
                singing = smoothSinging,
                music = smoothMusic,
                instrumental = 0f,
                popMusic = 0f,
                jingle = 0f,
                song = 0f,
                backgroundMusic = 0f,
                themeMusic = 0f,
                silence = smoothSilence,
                voiceSum = smoothSpeech + smoothNarration + smoothSinging,
                bgMusicSum = 0f,
                maxRawScore = 0f,
                spectrumRatio = smoothSpectrumRatio
            )

            val type = classifyYamnetScores(smoothedYamnet)                           // 含频谱检查
            val typeNoSpectrum = classifyYamnetScores(smoothedYamnet, false)          // 无频谱检查

            // v3.1.104: 判断是否含人声（仅频谱比值>0.16，不依赖YAMNet的DRY标记）
            // 避免将音乐、静音等非语音干帧误判为"含语音"而触发上下文保护
            val isSpeechContaining = (smoothSpectrumRatio > 0.16f)

            frames.add(FrameInfo(rawScores[i].timestampMs, type, typeNoSpectrum, isSpeechContaining, smoothSpectrumRatio))
        }

        // v3.1.103: 频谱比值连续3帧生效约束
        // 如果某帧因spectrumRatio>0.16被判DRY但不在连续3帧内，回退到YAMNet-only判定
        for (i in frames.indices) {
            if (frames[i].type == FrameType.DRY &&
                frames[i].typeNoSpectrum != FrameType.DRY &&
                frames[i].spectrumRatio > 0.16f) {
                val inThreeInARow = (i >= 2 &&
                    frames[i-1].spectrumRatio > 0.16f &&
                    frames[i-2].spectrumRatio > 0.16f) ||
                    (i >= 1 && i < frames.size - 1 &&
                        frames[i-1].spectrumRatio > 0.16f &&
                        frames[i+1].spectrumRatio > 0.16f) ||
                    (i < frames.size - 2 &&
                        frames[i+1].spectrumRatio > 0.16f &&
                        frames[i+2].spectrumRatio > 0.16f)
                if (!inThreeInARow) {
                    frames[i] = frames[i].copy(type = frames[i].typeNoSpectrum,
                        isSpeechContaining = (frames[i].spectrumRatio > 0.16f))
                }
            }
        }

        // v3.1.104: 上下文连续性约束（人声保护范围仅前后1.5秒）
        // 对每个WATER帧，检查前后1.5s内是否有其他帧含人声（spectrumRatio>0.16）
        // 如果超出1.5s间隔，不再触发上下文豁免
        // 只有在频谱比值>0.16（实际人声）时才触发保护，避免音乐帧误触发
        val contextWindowMs = 1500L
        for (i in frames.indices) {
            val frame = frames[i]
            if (frame.type == FrameType.WATER) {
                val hasSpeechNearby = frames.any { other ->
                    other != frame &&
                        kotlin.math.abs(other.timestampMs - frame.timestampMs) <= contextWindowMs &&
                        other.isSpeechContaining
                }
                if (hasSpeechNearby) {
                    // 降级为模糊段，等指纹二次判定
                    frames[i] = frame.copy(type = FrameType.DRY, isSpeechContaining = true)
                }
            }
        }

        // 将帧级结果合并为子段
        // 窗口覆盖范围：每个窗口覆盖 [windowCenterMs - halfWindowMs, windowCenterMs + halfWindowMs]
        val halfWindowMs = (YAMNET_WINDOW_SAMPLES * 1000L / (2 * YAMNET_SAMPLE_RATE))
        val segments = mutableListOf<VoiceSegment>()

        var segStartMs = maxOf(0L, frames[0].timestampMs - halfWindowMs)
        var segType = frames[0].type

        for (i in 1 until frames.size) {
            val (currentTs, currentType, _, _, _) = frames[i]
            if (currentType != segType) {
                val segEndMs = currentTs - halfWindowMs
                if (segEndMs > segStartMs) {
                    segments.add(createSegment(segStartMs, segEndMs, segType))
                }
                segStartMs = segEndMs
                segType = currentType
            }
        }
        // 最后一个子段
        // v3.1.139-fix: 修复coerceAtMost使用区间长度而非绝对位置导致的bug。
        // 根因：refStartMs=0时，`(rangeEndSample - rangeStartSample) * 1000 / 16000` 是带padding区间的长度，
        // 但frames.last().timestampMs是绝对时间（如600秒处=600000ms）。
        // 对于非首区间，帧时间戳远大于区间长度，coerceAtMost将段尾截断到区间长度以内，
        // 导致所有非首区间的段[start > end]被丢弃，YAMNet仅能在首区间产出段。
        // 修复：使用rangeEndSample的绝对时间作为限制。
        val paddedRangeEndMs = (rangeEndSample.toLong() * 1000L / YAMNET_SAMPLE_RATE)
        val lastEndMs = (frames.last().timestampMs + halfWindowMs).coerceAtMost(paddedRangeEndMs)
        segments.add(createSegment(segStartMs, lastEndMs, segType))

        return segments
    }

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
