package com.radio.app.utils

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashHandler private constructor() : Thread.UncaughtExceptionHandler {

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var appContext: Context

    companion object {
        @Volatile
        private var instance: CrashHandler? = null

        fun getInstance(): CrashHandler {
            return instance ?: synchronized(this) {
                instance ?: CrashHandler().also { instance = it }
            }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            saveCrashLog(thread, throwable)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // 让系统默认处理器处理（显示崩溃对话框）
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun saveCrashLog(thread: Thread, throwable: Throwable) {
        val time = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        val fileName = "crash_${time}.txt"

        // 写入 /sdcard/RadioApp/logs/crash/ 目录（用户可直接访问）
        val dir = com.radio.app.RadioApplication.getCrashLogDir()
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)

        FileWriter(file).use { writer ->
            writer.appendLine("===== RadioApp 崩溃日志 =====")
            writer.appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            writer.appendLine("线程: ${thread.name}")
            writer.appendLine("")
            writer.appendLine("===== 设备信息 =====")
            writer.appendLine("品牌: ${Build.BRAND}")
            writer.appendLine("型号: ${Build.MODEL}")
            writer.appendLine("Android版本: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            writer.appendLine("制造商: ${Build.MANUFACTURER}")
            writer.appendLine("设备: ${Build.DEVICE}")
            writer.appendLine("")
            writer.appendLine("===== 崩溃堆栈 =====")
            PrintWriter(writer).use { pw ->
                throwable.printStackTrace(pw)
            }
            writer.appendLine("")
            writer.appendLine("===== 根因 =====")
            val cause = throwable.cause
            if (cause != null) {
                writer.appendLine("Caused by:")
                PrintWriter(writer).use { pw ->
                    cause.printStackTrace(pw)
                }
            } else {
                writer.appendLine("无根因")
            }
            writer.appendLine("")
            writer.appendLine("===== 日志文件位置 =====")
            writer.appendLine(file.absolutePath)
            writer.appendLine("===== 结束 =====")
        }
    }

    fun getCrashLogDir(): File {
        return com.radio.app.RadioApplication.getCrashLogDir()
    }
}
