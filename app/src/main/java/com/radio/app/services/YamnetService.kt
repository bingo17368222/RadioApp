package com.radio.app.services

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.util.Log
import com.radio.app.models.VoiceSegment
import com.radio.app.utils.AudioSegmentAnalyzer
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * v3.1.154: YAMNet推理服务，运行在独立进程(:yamnet)中。
 * 当TFLite原生代码发生SIGSEGV崩溃时，只会杀死本进程，不会影响主进程。
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

        // 整体超时：300秒（5分钟），用于处理大量区间（如291个）
        // v3.1.158: 从120秒增加到300秒，根因：291个区间×~0.5秒/区间+模型加载+文件映射>120秒
        private const val OVERALL_TIMEOUT_MS = 300_000L
        // 单个区间超时：30秒
        private const val INTERVAL_TIMEOUT_MS = 30_000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        Log.i(TAG, "YamnetService: 启动，进程PID=${android.os.Process.myPid()}")

        // 使用独立线程池执行，避免服务主线程阻塞
        Thread {
            var pcmSamples: AudioSegmentAnalyzer.SampleProvider? = null
            try {
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

                // 检查取消函数
                fun isCancelled(): Boolean {
                    if (AudioSegmentAnalyzer.isAnalysisCancelled()) return true
                    if (cancelFile != null && cancelFile.exists()) return true
                    return false
                }

                Log.i(TAG, "YamnetService: 加载YAMNet模型")
                AudioSegmentAnalyzer.resetYamnetTimeoutCounters()
                val interp = AudioSegmentAnalyzer.loadYamnetInterpreter(this)
                if (interp == null) {
                    throw RuntimeException("YAMNet模型加载失败")
                }

                Log.i(TAG, "YamnetService: 打开PCM文件: $pcmPath")
                pcmSamples = AudioSegmentAnalyzer.openPcmSamples(File(pcmPath))

                // 处理所有区间，每个区间独立try-catch
                val allSegments = ArrayList<VoiceSegment>()
                val total = intervalStarts.size
                var processedCount = 0
                val overallStartMs = System.currentTimeMillis()

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

                        // 单个区间超时保护：使用 Future 包裹
                        val intervalLatch = CountDownLatch(1)
                        var intervalResult = emptyList<VoiceSegment>()
                        var intervalError: Throwable? = null

                        val executor = Executors.newSingleThreadExecutor()
                        executor.submit {
                            try {
                                intervalResult = AudioSegmentAnalyzer.classifyPcmIntervalInner(
                                    pcmSamples, startMs, endMs
                                )
                            } catch (e: Throwable) {
                                intervalError = e
                            } finally {
                                intervalLatch.countDown()
                            }
                        }
                        executor.shutdown()

                        val intervalDone = intervalLatch.await(INTERVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        if (!intervalDone) {
                            // 区间超时，强制关闭 executor
                            executor.shutdownNow()
                            Log.w(TAG, "YamnetService: 区间[${i+1}/$total] 超时(${INTERVAL_TIMEOUT_MS / 1000}秒)，跳过")
                            processedCount++
                            continue
                        }

                        if (intervalError != null) {
                            val err = intervalError!!
                            val sw = StringWriter()
                            val pw = PrintWriter(sw)
                            err.printStackTrace(pw)
                            Log.w(TAG, "YamnetService: 区间[${i+1}/$total] 异常: ${err.javaClass.name}: ${err.message}")
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

                        Log.i(TAG, "YamnetService: 区间[${i+1}/$total] 完成，${processed.size}段")
                    } catch (e: InterruptedException) {
                        Log.w(TAG, "YamnetService: 区间[${i+1}/$total] 被中断")
                        if (allSegments.isEmpty()) throw
                        break
                    } catch (e: Throwable) {
                        // 单个区间异常不中断整体流程，继续处理下一个区间
                        Log.w(TAG, "YamnetService: 区间[${i+1}/$total] 异常: ${e.javaClass.name}: ${e.message}")
                        processedCount++
                    }
                }

                Log.i(TAG, "YamnetService: 处理完成，共${allSegments.size}段(成功${processedCount}/${total}个区间)")
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
                val errBundle = Bundle().apply {
                    putString(RESULT_ERROR, "${e.javaClass.name}: ${e.message}")
                    putString(RESULT_ERROR_DETAIL, stackTrace)
                }
                try { intent?.getParcelableExtra<ResultReceiver>(EXTRA_RECEIVER)?.send(CODE_ERROR, errBundle) } catch (_: Exception) {}
            } finally {
                try { pcmSamples?.close() } catch (_: Exception) {}
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }
}