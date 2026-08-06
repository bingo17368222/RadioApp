package com.radio.app.utils

import android.util.Log
import com.radio.app.RadioApplication
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v3.1.44: 统一的日志文件写入工具。
 * 将 Log.e 异常日志自动保存到 logs/error/ 目录下，方便排查问题。
 * 所有 info 文件相关日志也保存到 logs/audio_segment/ 目录。
 *
 * 使用方式：FileLogUtils.e(TAG, "message", exception)
 * 等价于 Log.e(TAG, "message", exception) + 写入文件
 */
object FileLogUtils {

    private const val TAG = "FileLogUtils"
    private const val MAX_LOG_SIZE = 500_000L  // 500KB
    private const val MAX_LOG_LINES = 2000     // 保留最近2000行

    // ===== Log.e 异常日志 =====

    /**
     * 写入 Log.e 级别的日志，同时输出到 Logcat 和文件。
     */
    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        // 输出到 Logcat
        if (tr != null) {
            Log.e(tag, msg, tr)
        } else {
            Log.e(tag, msg)
        }
        // 写入文件
        writeErrorLog(tag, msg, tr)
    }

    /**
     * 写入 Log.w 级别的日志，同时输出到 Logcat 和文件。
     */
    @JvmStatic
    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        writeErrorLog(tag, "[WARN] $msg", null)
    }

    /**
     * 写入 Log.i 级别的日志到文件（不输出到 Logcat 避免刷屏）。
     */
    @JvmStatic
    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        writeErrorLog(tag, "[INFO] $msg", null)
    }

    /**
     * 将异常日志写入 logs/error/error.log 文件。
     */
    private fun writeErrorLog(tag: String, msg: String, tr: Throwable?) {
        try {
            val context = RadioApplication.instance
            val logDir = File(RadioApplication.getLogDir(context), "error")
            if (!logDir.exists()) logDir.mkdirs()

            val logFile = File(logDir, "error.log")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val versionTag = RadioApplication.appVersionTag()
            val sb = StringBuilder()
            sb.append("[$timestamp][$versionTag][$tag] $msg\n")
            if (tr != null) {
                sb.append("  Exception: ${tr.javaClass.simpleName}: ${tr.message}\n")
                // 写入前5行堆栈
                val stackLines = tr.stackTraceToString().split("\n")
                stackLines.take(5).forEach { line ->
                    sb.append("    $line\n")
                }
            }

            FileWriter(logFile, true).use { it.append(sb.toString()) }

            // 限制文件大小
            if (logFile.length() > MAX_LOG_SIZE) {
                trimLogFile(logFile)
            }
        } catch (_: Exception) {
            // 静默失败，不影响主流程
        }
    }

    /**
     * 当日志文件过大时，保留最近 N 行。
     * v3.1.55: 使用逐行读取而非 readLines()，避免 OOM。
     */
    private fun trimLogFile(file: File) {
        try {
            if (!file.exists()) return
            // 先计算总行数（逐行读取，不加载到内存）
            var totalLines = 0
            file.useLines { lines ->
                lines.forEach { totalLines++ }
            }
            if (totalLines <= MAX_LOG_LINES) return

            // 需要保留的行数
            val linesToKeep = MAX_LOG_LINES
            val skipLines = totalLines - linesToKeep

            // 逐行写入临时文件
            val tempFile = File(file.absolutePath + ".tmp")
            var lineNum = 0
            file.useLines { lines ->
                tempFile.bufferedWriter().use { writer ->
                    lines.forEach { line ->
                        lineNum++
                        if (lineNum > skipLines) {
                            writer.write(line)
                            writer.newLine()
                        }
                    }
                }
            }
            // 替换原文件
            tempFile.renameTo(file)
        } catch (_: Exception) {}
    }

    /**
     * 写入 info 文件相关的详细日志到 logs/audio_segment/ 目录。
     * 与 AudioSegmentAnalyzer 的 vadLog 互补。
     */
    @JvmStatic
    fun logInfoFile(msg: String) {
        try {
            val context = RadioApplication.instance
            val logDir = File(RadioApplication.getLogDir(context), "audio_segment")
            if (!logDir.exists()) logDir.mkdirs()

            val logFile = File(logDir, "audio_segment.log")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val versionTag = RadioApplication.appVersionTag()
            FileWriter(logFile, true).use { it.append("[$timestamp][$versionTag] [InfoFile] $msg\n") }

            if (logFile.length() > MAX_LOG_SIZE) {
                trimLogFile(logFile)
            }
        } catch (_: Exception) {}
    }
}