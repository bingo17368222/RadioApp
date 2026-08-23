package com.radio.app.utils

import android.content.Context
import android.util.Log
import com.radio.app.RadioApplication
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v3.1.161: 将logcat日志保存到 logs/logcat/ 目录。
 *
 * 崩溃时自动保存最近N行logcat，便于排查原生崩溃（SIGSEGV）等无法被Java try-catch捕获的异常。
 * 也可手动调用 dumpLogcat() 在关键节点保存日志快照。
 *
 * 使用方式：
 *   LogcatCapture.dumpLogcat(context)                    // 保存所有日志
 *   LogcatCapture.dumpLogcat(context, maxLines = 500)    // 保存最近500行
 *   LogcatCapture.dumpLogcat(context, tags = listOf("YamnetService", "AudioSegmentAnalyzer"))  // 只保存指定tag
 */
object LogcatCapture {
    private const val TAG = "LogcatCapture"
    private const val DEFAULT_MAX_LINES = 2000
    private const val MAX_LOG_SIZE = 1_000_000L  // 1MB
    private const val MAX_LOG_FILES = 10          // 最多保留10个文件

    // 关注的日志标签（TFLite/YAMNet相关）
    private val RELEVANT_TAGS = listOf(
        "YamnetService",
        "AudioSegmentAnalyzer",
        "SegmentGenerator",
        "NativeLibLoader",
        "TFLite",
        "System",
        "DEBUG",
        "CRASH"
    )

    /**
     * 保存logcat日志到文件。
     * 如果指定了tags，只保存匹配这些tag的行；否则保存所有行。
     * 崩溃时调用此方法，可以捕获导致崩溃的上下文日志。
     *
     * v3.1.164: 崩溃时不再截取，保存全部日志（最多5000行），避免YamnetService日志被截断。
     */
    @JvmStatic
    fun dumpLogcat(
        context: Context,
        maxLines: Int = DEFAULT_MAX_LINES,
        tags: List<String>? = null
    ): File? {
        return try {
            val logDir = File(RadioApplication.getLogDir(context), "logcat")
            if (!logDir.exists()) logDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val fileName = "logcat_${timestamp}.txt"
            val file = File(logDir, fileName)

            val targetTags = tags ?: RELEVANT_TAGS

            // 使用 logcat -d（dump一次后退出，不持续监听） + -v threadtime（带线程时间）
            // v3.1.164: 崩溃时保存全部行（不截取），避免YamnetService日志滚动出缓冲区
            val process = Runtime.getRuntime().exec(
                arrayOf(
                    "logcat",
                    "-d",           // dump and exit
                    "-v",           // verbose format
                    "threadtime"    // include PID, TID, timestamp
                )
            )

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val allLines = mutableListOf<String>()

            // 读取所有行
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                allLines.add(line!!)
            }
            // 读取错误流（如果有）
            while (errorReader.readLine().also { line = it } != null) {
                if (line!!.isNotBlank()) {
                    Log.w(TAG, "logcat stderr: $line")
                }
            }
            reader.close()
            errorReader.close()
            process.waitFor()

            // v3.1.164: 崩溃时保存全部行（最多5000行），不再截取
            // 避免YamnetService的日志在被截断的部分
            val effectiveMaxLines = if (maxLines == DEFAULT_MAX_LINES && targetTags.isEmpty()) {
                5000  // 崩溃时保存全部
            } else {
                maxLines
            }
            val recentLines = if (allLines.size > effectiveMaxLines) {
                allLines.subList(allLines.size - effectiveMaxLines, allLines.size)
            } else {
                allLines
            }

            // 按tag过滤（如果指定了标签）
            val filteredLines = if (targetTags.isEmpty()) {
                recentLines
            } else {
                recentLines.filter { line ->
                    targetTags.any { tag ->
                        line.contains(" $tag:") || line.contains("($tag:)") ||
                        line.contains(" $tag (") || line.contains(" $tag (")
                    }
                }
            }

            // 写入文件
            FileWriter(file).use { writer ->
                writer.appendLine("===== RadioApp Logcat 快照 =====")
                writer.appendLine("保存时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
                writer.appendLine("版本: ${RadioApplication.appVersionTag()}")
                writer.appendLine("过滤标签: ${if (targetTags.isEmpty()) "全部" else targetTags.joinToString(", ")}")
                writer.appendLine("总行数: ${allLines.size}, 过滤后: ${filteredLines.size}, 截取最近: ${effectiveMaxLines}行")
                writer.appendLine("进程PID: ${android.os.Process.myPid()}")
                writer.appendLine("===== 日志内容开始 =====")
                writer.appendLine("")

                for (logLine in filteredLines) {
                    writer.appendLine(logLine)
                }

                writer.appendLine("")
                writer.appendLine("===== 日志结束 =====")
            }

            Log.i(TAG, "logcat已保存: ${file.absolutePath} (${filteredLines.size}行, 过滤自${allLines.size}行)")

            // 清理旧文件（保留最近MAX_LOG_FILES个）
            cleanupOldFiles(logDir)

            file
        } catch (e: Exception) {
            Log.e(TAG, "保存logcat失败: ${e.javaClass.name}: ${e.message}")
            null
        }
    }

    /**
     * v3.1.164: 按进程PID保存logcat日志。
     * 使用 `logcat -d --pid=<PID>` 只获取指定进程的日志，避免被其他进程日志淹没。
     * 用于YamnetService在崩溃前保存自己的关键日志。
     */
    @JvmStatic
    fun dumpLogcatForPid(
        context: Context,
        pid: Int,
        maxLines: Int = 2000,
        suffix: String = ""
    ): File? {
        return try {
            val logDir = File(RadioApplication.getLogDir(context), "logcat")
            if (!logDir.exists()) logDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val pidLabel = if (suffix.isNotEmpty()) "_pid${pid}_${suffix}" else "_pid${pid}"
            val fileName = "logcat${pidLabel}_${timestamp}.txt"
            val file = File(logDir, fileName)

            // 使用 --pid 只获取指定进程的日志
            val process = Runtime.getRuntime().exec(
                arrayOf(
                    "logcat",
                    "-d",
                    "--pid", pid.toString(),
                    "-v", "threadtime"
                )
            )

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val allLines = mutableListOf<String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                allLines.add(line!!)
            }
            reader.close()
            process.waitFor()

            // 取最近N行
            val recentLines = if (allLines.size > maxLines) {
                allLines.subList(allLines.size - maxLines, allLines.size)
            } else {
                allLines
            }

            FileWriter(file).use { writer ->
                writer.appendLine("===== RadioApp Logcat 按PID快照 =====")
                writer.appendLine("保存时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
                writer.appendLine("版本: ${RadioApplication.appVersionTag()}")
                writer.appendLine("目标PID: $pid")
                writer.appendLine("总行数: ${allLines.size}, 保存: ${recentLines.size}行")
                writer.appendLine("===== 日志内容开始 =====")
                writer.appendLine("")
                for (logLine in recentLines) {
                    writer.appendLine(logLine)
                }
                writer.appendLine("")
                writer.appendLine("===== 日志结束 =====")
            }

            Log.i(TAG, "logcat(PID=$pid)已保存: ${file.absolutePath} (${recentLines.size}行)")

            cleanupOldFiles(logDir)
            file
        } catch (e: Exception) {
            Log.e(TAG, "保存logcat(PID=$pid)失败: ${e.javaClass.name}: ${e.message}")
            null
        }
    }

    /**
     * 保存最近N秒的logcat日志（指定时间范围，而非行数限制）。
     * 用于在崩溃时保存崩溃前一段时间内的日志。
     */
    @JvmStatic
    fun dumpLogcatRecent(context: Context, recentSeconds: Int = 60): File? {
        return try {
            val logDir = File(RadioApplication.getLogDir(context), "logcat")
            if (!logDir.exists()) logDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val fileName = "logcat_recent_${recentSeconds}s_${timestamp}.txt"
            val file = File(logDir, fileName)

            // 使用 logcat -t 指定最后N行（近似时间范围：约200行/分钟）
            val estimatedLines = recentSeconds * 4  // 约每秒4行logcat
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", estimatedLines.toString(), "-v", "threadtime")
            )

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val allLines = mutableListOf<String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                allLines.add(line!!)
            }
            reader.close()
            process.waitFor()

            FileWriter(file).use { writer ->
                writer.appendLine("===== RadioApp Logcat 最近${recentSeconds}秒快照 =====")
                writer.appendLine("保存时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
                writer.appendLine("版本: ${RadioApplication.appVersionTag()}")
                writer.appendLine("行数: ${allLines.size}")
                writer.appendLine("===== 日志内容开始 =====")
                writer.appendLine("")
                for (logLine in allLines) {
                    writer.appendLine(logLine)
                }
                writer.appendLine("")
                writer.appendLine("===== 日志结束 =====")
            }

            Log.i(TAG, "logcat(最近${recentSeconds}秒)已保存: ${file.absolutePath} (${allLines.size}行)")

            cleanupOldFiles(logDir)
            file
        } catch (e: Exception) {
            Log.e(TAG, "保存logcat(最近${recentSeconds}秒)失败: ${e.javaClass.name}: ${e.message}")
            null
        }
    }

    /**
     * 清理旧日志文件，保留最近MAX_LOG_FILES个。
     */
    private fun cleanupOldFiles(logDir: File) {
        try {
            val files = logDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("logcat_") }
                ?.sortedByDescending { it.lastModified() }
                ?: return

            if (files.size > MAX_LOG_FILES) {
                for (i in MAX_LOG_FILES until files.size) {
                    files[i].delete()
                }
            }

            // 如果日志目录总大小超过10MB，删除最旧的文件
            var totalSize = files.sumOf { it.length() }
            while (totalSize > 10 * MAX_LOG_SIZE && files.isNotEmpty()) {
                val oldest = files.maxByOrNull { it.lastModified() } ?: break
                totalSize -= oldest.length()
                oldest.delete()
            }
        } catch (_: Exception) {}
    }
}