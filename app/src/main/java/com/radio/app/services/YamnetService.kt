package com.radio.app.services

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.util.Log
import com.radio.app.RadioApplication
import com.radio.app.models.VoiceSegment
import com.radio.app.utils.AudioSegmentAnalyzer
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * v3.1.161: YAMNet推理服务，运行在独立进程(:yamnet)中。
 * 当TFLite原生代码发生SIGSEGV崩溃时，只会杀死本进程，不会影响主进程。
 *
 * v3.1.161: 添加了每个关键步骤前的详细日志，用于定位崩溃点。
 * SIGSEGV是原生崩溃，崩溃后的日志不会被写入，因此必须在每个操作前记录日志。
 *
 * 通信协议：主进程通过startService()发送Intent，携带：
 *   - EXTRA_PCM_PATH: PCM文件绝对路径
 *   - EXTRA_INTERVAL_STARTS: 区间起始时间数组(long[])
 *   - EXTRA_INTERVAL_ENDS: 区间结束时间数组(long[])
 *   - EXTRA_CANCEL_FILE: 取消标记文件路径（可选），服务端定期检查该文件是否存在
 *   - EXTRA_RECEIVER: ResultReceiver，用于接收结果和进度回调
 *
 * 本服务处理完成后，通过ResultReceiver.send()返回结果：
 *   - CODE_SUCCESS + RESULT_SEGMENTS (ArrayList<VoiceSegment>)
 *   - CODE_ERROR + RESULT_ERROR (String)
 *   - CODE_PROGRESS + RESULT_PROGRESS_COUNT/RESULT_PROGRESS_TOTAL (Int)
 */
class YamnetService : Service() {
    companion object {
        const val TAG = "YamnetService"
        const val EXTRA_PCM_PATH = "pcm_path"
        const val EXTRA_INTERVAL_STARTS = "interval_starts"
        const val EXTRA_INTERVAL_ENDS = "interval_ends"
        const val EXTRA_CANCEL_FILE = "cancel_file"
        const val EXTRA_RECEIVER = "receiver"
        const val RESULT_SEGMENTS = "segments"
        const val RESULT_ERROR = "error"
        const val RESULT_ERROR_DETAIL = "error_detail"
        const val RESULT_PROGRESS_COUNT = "progress_count"
        const val RESULT_PROGRESS_TOTAL = "progress_total"
        const val CODE_SUCCESS = 0
        const val CODE_ERROR = 1
        const val CODE_PROGRESS = 2

        // 整体超时：600秒（10分钟），用于处理大量区间（如354个）
        private const val OVERALL_TIMEOUT_MS = 600_000L
        // 单个区间超时：30秒
        private const val INTERVAL_TIMEOUT_MS = 30_000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        // ===== STEP 0: 进程启动日志 =====
        val pid = android.os.Process.myPid()
        Log.i(TAG, "========================================")
        Log.i(TAG, "YamnetService: 启动，进程PID=$pid, 线程=${Thread.currentThread().name}")
        writeFingerprintLog("YamnetService: 启动 PID=$pid")

        // 使用独立线程执行，避免服务主线程阻塞
        Thread {
            var pcmSamples: AudioSegmentAnalyzer.SampleProvider? = null
            try {
                // ===== STEP 1: 解析Intent参数 =====
                Log.i(TAG, "YamnetService: [STEP 1] 解析Intent参数 PID=$pid")
                val pcmPath = intent.getStringExtra(EXTRA_PCM_PATH)
                    ?: throw RuntimeException("缺少PCM文件路径")
                val intervalStarts = intent.getLongArrayExtra(EXTRA_INTERVAL_STARTS)
                    ?: throw RuntimeException("缺少intervalStarts")
                val intervalEnds = intent.getLongArrayExtra(EXTRA_INTERVAL_ENDS)
                    ?: throw RuntimeException("缺少intervalEnds")
                val cancelFilePath = intent.getStringExtra(EXTRA_CANCEL_FILE)
                val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RECEIVER)
                    ?: throw RuntimeException("缺少ResultReceiver")

                val cancelFile = if (cancelFilePath != null) File(cancelFilePath) else null
                Log.i(TAG, "YamnetService: 参数解析完成: pcmPath=$pcmPath, intervals=${intervalStarts.size}, cancelFile=$cancelFilePath")
                writeFingerprintLog("YamnetService: 参数解析完成 intervals=${intervalStarts.size}")

                // 检查取消函数
                fun isCancelled(): Boolean {
                    if (AudioSegmentAnalyzer.isAnalysisCancelled()) return true
                    if (cancelFile != null && cancelFile.exists()) return true
                    return false
                }

                // ===== STEP 2: 检查PCM文件 =====
                Log.i(TAG, "YamnetService: [STEP 2] 检查PCM文件: $pcmPath PID=$pid")
                val pcmFile = File(pcmPath)
                if (!pcmFile.exists()) {
                    throw RuntimeException("PCM文件不存在: $pcmPath")
                }
                val pcmSizeBytes = pcmFile.length()
                Log.i(TAG, "YamnetService: PCM文件存在: size=${pcmSizeBytes / 1024 / 1024}MB, absolutePath=${pcmFile.absolutePath}")
                writeFingerprintLog("YamnetService: PCM文件 size=${pcmSizeBytes / 1024 / 1024}MB")

                // ===== STEP 3: 检查模型目录和文件 =====
                Log.i(TAG, "YamnetService: [STEP 3] 检查模型文件 PID=$pid")
                val modelDir = AudioSegmentAnalyzer.getModelDir(this)
                Log.i(TAG, "YamnetService: 模型目录: ${modelDir.absolutePath}")
                if (!modelDir.exists()) {
                    Log.e(TAG, "YamnetService: 模型目录不存在: ${modelDir.absolutePath}")
                    throw RuntimeException("模型目录不存在: ${modelDir.absolutePath}")
                }
                val modelFiles = modelDir.listFiles()
                Log.i(TAG, "YamnetService: 模型目录文件列表: ${modelFiles?.map { "${it.name}(${it.length() / 1024}KB)" }?.joinToString(", ") ?: "空"}")
                writeFingerprintLog("YamnetService: 模型目录: ${modelFiles?.size ?: 0}个文件")

                // ===== STEP 4: 检查Native库加载 =====
                Log.i(TAG, "YamnetService: [STEP 4] 检查Native库加载 PID=$pid")
                // 先检查native库是否已加载（通过反射检查或直接尝试加载）
                try {
                    System.loadLibrary("tensorflowlite_jni")
                    Log.i(TAG, "YamnetService: tensorflowlite_jni 已加载成功")
                } catch (e: UnsatisfiedLinkError) {
                    if (e.message?.contains("already loaded") == true) {
                        Log.i(TAG, "YamnetService: tensorflowlite_jni 已加载(already loaded)")
                    } else {
                        Log.w(TAG, "YamnetService: tensorflowlite_jni 加载失败: ${e.message}")
                        // 尝试从外部存储加载
                        val externalSo = File(modelDir, "libtensorflowlite_jni.so")
                        if (externalSo.exists()) {
                            Log.i(TAG, "YamnetService: 尝试从外部加载 tensorflowlite_jni: ${externalSo.absolutePath} (${externalSo.length() / 1024}KB)")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "YamnetService: tensorflowlite_jni 检查异常: ${e.javaClass.name}: ${e.message}")
                }
                writeFingerprintLog("YamnetService: Native库检查完成")

                // ===== STEP 5: 加载YAMNet模型 =====
                Log.i(TAG, "YamnetService: [STEP 5] 加载YAMNet模型 PID=$pid")
                writeFingerprintLog("YamnetService: [STEP 5] 开始加载YAMNet模型")
                AudioSegmentAnalyzer.resetYamnetTimeoutCounters()

                // 在loadYamnetInterpreter前记录详细信息
                Log.i(TAG, "YamnetService: 调用loadYamnetInterpreter(this)")
                val interp = AudioSegmentAnalyzer.loadYamnetInterpreter(this)
                if (interp == null) {
                    Log.e(TAG, "YamnetService: loadYamnetInterpreter返回null")
                    writeFingerprintLog("YamnetService: loadYamnetInterpreter返回null")
                    throw RuntimeException("YAMNet模型加载失败")
                }
                Log.i(TAG, "YamnetService: YAMNet模型加载成功 interp=$interp")
                writeFingerprintLog("YamnetService: YAMNet模型加载成功")

                // ===== STEP 6: 打开PCM文件（内存映射） =====
                Log.i(TAG, "YamnetService: [STEP 6] 打开PCM文件(内存映射) PID=$pid")
                writeFingerprintLog("YamnetService: [STEP 6] 开始内存映射PCM文件")
                pcmSamples = try {
                    AudioSegmentAnalyzer.openPcmSamples(pcmFile)
                } catch (e: Throwable) {
                    Log.e(TAG, "YamnetService: openPcmSamples异常: ${e.javaClass.name}: ${e.message}")
                    writeFingerprintLog("YamnetService: openPcmSamples异常: ${e.message}")
                    throw RuntimeException("PCM文件打开失败: ${e.message}", e)
                }
                Log.i(TAG, "YamnetService: PCM文件打开成功，样本数=${pcmSamples!!.size}")
                writeFingerprintLog("YamnetService: PCM文件打开成功 样本数=${pcmSamples!!.size}")

                // ===== STEP 7: 开始处理区间 =====
                val allSegments = ArrayList<VoiceSegment>()
                val total = intervalStarts.size
                var processedCount = 0
                val overallStartMs = System.currentTimeMillis()
                Log.i(TAG, "YamnetService: [STEP 7] 开始处理区间: 共$total 个区间 PID=$pid")
                writeFingerprintLog("YamnetService: [STEP 7] 开始处理 $total 个区间")

                // v3.1.159: 复用单个Executor处理所有区间
                val intervalExecutor = Executors.newSingleThreadExecutor()
                try {
                    for (i in 0 until total) {
                        // 检查取消
                        if (isCancelled()) {
                            Log.w(TAG, "YamnetService: 检测到取消信号，停止处理")
                            break
                        }

                        // 整体超时检查
                        if (System.currentTimeMillis() - overallStartMs >= OVERALL_TIMEOUT_MS) {
                            Log.w(TAG, "YamnetService: 整体处理超时(${OVERALL_TIMEOUT_MS / 1000}秒)，停止处理")
                            break
                        }

                        try {
                            val startMs = intervalStarts[i]
                            val endMs = intervalEnds[i]
                            val intervalDurationMs = endMs - startMs

                            // 单个区间过长？跳过超大区间
                            if (intervalDurationMs > 300_000L) {
                                Log.w(TAG, "YamnetService: 区间[${i+1}/$total] 过长(${intervalDurationMs / 1000}秒)，跳过")
                                processedCount++
                                continue
                            }

                            // 单个区间超时保护：使用复用的Executor + Future.get(timeout)
                            var intervalResult: List<VoiceSegment> = emptyList()
                            try {
                                // 记录每个区间开始前的日志，用于定位崩溃点
                                if (i % 10 == 0 || i == 0) {
                                    Log.i(TAG, "YamnetService: 区间[${i+1}/$total] 提交推理 startMs=${startMs}ms, endMs=${endMs}ms, duration=${intervalDurationMs}ms  PID=$pid")
                                    writeFingerprintLog("YamnetService: 区间[${i+1}/$total] 提交推理 duration=${intervalDurationMs}ms")
                                }
                                val future = intervalExecutor.submit(Callable {
                                    // 在classifyPcmIntervalInner内部的日志会记录更详细的信息
                                    AudioSegmentAnalyzer.classifyPcmIntervalInner(
                                        pcmSamples!!, startMs, endMs
                                    )
                                })
                                intervalResult = future.get(INTERVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            } catch (e: TimeoutException) {
                                Log.w(TAG, "YamnetService: 区间[${i+1}/$total] 超时(${INTERVAL_TIMEOUT_MS / 1000}秒)，跳过")
                                processedCount++
                                continue
                            } catch (e: Exception) {
                                val sw = StringWriter()
                                val pw = PrintWriter(sw)
                                e.printStackTrace(pw)
                                Log.w(TAG, "YamnetService: 区间[${i+1}/$total] 异常: ${e.javaClass.name}: ${e.message}")
                                Log.w(TAG, "YamnetService: 区间异常堆栈:\n${sw.toString().take(500)}")
                                processedCount++
                                continue
                            }

                            val processed = AudioSegmentAnalyzer.postProcessYamnetSubSegments(intervalResult)
                            allSegments.addAll(processed)
                            processedCount++

                            // 每处理5个区间或关键节点发送进度回调
                            if (i % 5 == 0 || i == total - 1) {
                                try {
                                    val progressBundle = Bundle().apply {
                                        putInt(RESULT_PROGRESS_COUNT, processedCount)
                                        putInt(RESULT_PROGRESS_TOTAL, total)
                                    }
                                    receiver.send(CODE_PROGRESS, progressBundle)
                                } catch (_: Exception) {}
                            }

                            if (i % 10 == 0) {
                                Log.i(TAG, "YamnetService: 区间[${i+1}/$total] 完成，${processed.size}段，累计${allSegments.size}段")
                            }
                        } catch (e: InterruptedException) {
                            Log.w(TAG, "YamnetService: 区间[${i+1}/$total] 被中断")
                            if (allSegments.isEmpty()) throw
                            break
                        } catch (e: Throwable) {
                            Log.w(TAG, "YamnetService: 区间[${i+1}/$total] 异常: ${e.javaClass.name}: ${e.message}")
                            processedCount++
                        }
                    }
                } finally {
                    intervalExecutor.shutdownNow()
                }

                Log.i(TAG, "YamnetService: 处理完成，共${allSegments.size}段(成功${processedCount}/${total}个区间)")
                writeFingerprintLog("YamnetService: 处理完成 共${allSegments.size}段 成功${processedCount}/${total}")
                val resultBundle = Bundle().apply {
                    putParcelableArrayList(RESULT_SEGMENTS, allSegments)
                }
                try { receiver.send(CODE_SUCCESS, resultBundle) } catch (_: Exception) {}
            } catch (e: Throwable) {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                e.printStackTrace(pw)
                val stackTrace = sw.toString().take(800)
                Log.e(TAG, "YamnetService: 处理异常: ${e.javaClass.name}: ${e.message}")
                Log.e(TAG, "YamnetService: 异常堆栈:\n$stackTrace")
                writeFingerprintLog("YamnetService: 处理异常 ${e.javaClass.name}: ${e.message}")
                val errBundle = Bundle().apply {
                    putString(RESULT_ERROR, "${e.javaClass.name}: ${e.message}")
                    putString(RESULT_ERROR_DETAIL, stackTrace)
                }
                try { intent?.getParcelableExtra<ResultReceiver>(EXTRA_RECEIVER)?.send(CODE_ERROR, errBundle) } catch (_: Exception) {}
            } finally {
                Log.i(TAG, "YamnetService: 清理资源 PID=$pid")
                try { pcmSamples?.close() } catch (_: Exception) {}
                stopSelf()
                Log.i(TAG, "YamnetService: 服务结束 PID=$pid")
                writeFingerprintLog("YamnetService: 服务结束")
            }
        }.start()
        return START_NOT_STICKY
    }

    /**
     * v3.1.161: 写入指纹日志到独立文件，即使用进程崩溃，日志也能保留。
     * 写入路径：/sdcard/RadioApp/logs/fingerprint/fingerprint_yamnet_service.log
     */
    private fun writeFingerprintLog(msg: String) {
        try {
            val logDir = File(RadioApplication.getLogDir(this), "fingerprint")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = File(logDir, "fingerprint_yamnet_service.log")
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            logFile.appendText("[$ts] $msg\n")
            // 限制文件大小到1MB
            if (logFile.length() > 1_000_000) {
                val lines = logFile.readLines()
                val keep = lines.takeLast(1000)
                logFile.writeText(keep.joinToString("\n") + "\n")
            }
        } catch (_: Exception) {}
    }
}