package com.radio.app.utils

import android.content.Context
import android.util.Log
import java.io.File

/**
 * v3.0.2: Chromaprint 原生库加载器。
 * libchromaprint.so 默认不包含在 APK 中，用户需通过「离线引擎管理」下载到
 * /sdcard/Android/data/.../files/models/chromaprint-engine/libchromaprint-arm64-v8a.so。
 * 加载器优先从该外部路径加载，失败则尝试从 APK 内置路径加载。
 */
object ChromaprintLoader {
    private const val TAG = "ChromaprintLoader"
    private const val SO_NAME = "libchromaprint-arm64-v8a.so"

    @Volatile
    private var loaded = false

    /**
     * 获取 Chromaprint 运行库目录。
     */
    fun getChromaprintDir(context: Context): File {
        val modelsDir = context.getExternalFilesDir("models") ?: context.getExternalFilesDir(null)
        val dir = File(modelsDir, "chromaprint-engine")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 检查 Chromaprint 库是否已下载。
     */
    fun isLibraryDownloaded(context: Context): Boolean {
        return File(getChromaprintDir(context), SO_NAME).exists()
    }

    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (loaded) return true
        Log.i(TAG, "ensureLoaded: starting")

        // 1. 尝试从 APK 内置库加载（标准名称）
        try {
            System.loadLibrary("chromaprint")
            loaded = true
            Log.i(TAG, "ensureLoaded: loaded libchromaprint.so from APK")
            return true
        } catch (e: UnsatisfiedLinkError) {
            if (e.message?.contains("already loaded") == true ||
                e.message?.contains("Library already loaded") == true) {
                loaded = true
                Log.i(TAG, "ensureLoaded: libchromaprint.so already loaded")
                return true
            }
            Log.w(TAG, "ensureLoaded: APK loading failed: ${e.message}")
        }

        // 2. 从外部存储加载（离线引擎管理下载的路径）
        val externalSo = File(getChromaprintDir(context), SO_NAME)
        if (!externalSo.exists()) {
            Log.e(TAG, "ensureLoaded: $SO_NAME not found at ${externalSo.absolutePath}")
            return false
        }

        // 复制到 codeCacheDir 再加载，避免某些 Android 版本对外部存储 .so 的加载限制
        val internalDir = File(context.codeCacheDir, "chromaprint-lib")
        if (!internalDir.exists()) internalDir.mkdirs()
        val internalSo = File(internalDir, "libchromaprint.so")
        try {
            if (!internalSo.exists() || internalSo.length() != externalSo.length()) {
                externalSo.copyTo(internalSo, overwrite = true)
            }
            System.load(internalSo.absolutePath)
            loaded = true
            Log.i(TAG, "ensureLoaded: loaded libchromaprint.so from external storage")
            // v3.0.6: 将绝对路径传给 JNI，解决 dlopen 短名称在 Android linker 命名空间下找不到的问题
            ChromaprintExtractor.setNativeLibraryPath(internalSo.absolutePath)
            return true
        } catch (e: UnsatisfiedLinkError) {
            if (e.message?.contains("already loaded") == true) {
                loaded = true
                return true
            }
            Log.e(TAG, "ensureLoaded: failed to load from external: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "ensureLoaded: failed to copy/load external .so: ${e.message}")
        }
        return false
    }

    fun reset() {
        loaded = false
    }
}
