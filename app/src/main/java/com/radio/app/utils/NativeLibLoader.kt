package com.radio.app.utils

import android.content.Context
import android.util.Log
import java.io.File

/**
 * v2.4.112: Pre-load ALL native libraries from the APK or external storage.
 *
 * Root cause of v2.4.111 VAD failure (-256 error):
 * When System.loadLibrary() failed for onnxruntime4j_jni or tensorflowlite_jni
 * (e.g., DT_NEEDED resolution, linker namespace), the fallback path only loaded
 * libonnxruntime.so via System.load() and returned true. This left TFLite and
 * ONNX Runtime JNI libraries unloaded, causing "audio-vad error: -256".
 *
 * Fix: The fallback now loads ALL three libraries from external storage, not
 * just libonnxruntime.so. Each library is loaded via System.load(absolutePath)
 * from the model directory (or copied to codeCacheDir first for permission).
 *
 * Also: loadYamnetModel and PlayerActivity now catch Throwable, not just Exception,
 * to properly catch UnsatisfiedLinkError (extends Error, not Exception).
 */
object NativeLibLoader {
    private const val TAG = "NativeLibLoader"
    private var loaded = false

    // All three libraries required for audio segmentation
    private val ALL_LIBS = listOf(
        "libonnxruntime.so",         // Main ONNX Runtime library
        "libonnxruntime4j_jni.so",   // JNI wrapper (DT_NEEDED on libonnxruntime.so)
        "libtensorflowlite_jni.so"   // TFLite JNI wrapper
    )

    fun areLibsDownloaded(modelDir: File): Boolean {
        return ALL_LIBS.all { File(modelDir, it).exists() }
    }

    fun getLibsStatus(modelDir: File): String {
        return ALL_LIBS.joinToString(", ") { lib ->
            val f = File(modelDir, lib)
            "$lib=${if (f.exists()) "${f.length()}B" else "MISSING"}"
        }
    }

    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (loaded) return true

        Log.i(TAG, "ensureLoaded: starting (v2.4.112)")

        // Step 1: Try loading ALL libraries from APK via System.loadLibrary
        val apkLibNames = listOf(
            "onnxruntime",           // libonnxruntime.so
            "onnxruntime4j_jni",     // libonnxruntime4j_jni.so
            "tensorflowlite_jni"     // libtensorflowlite_jni.so
        )

        var apkAllLoaded = true
        val apkLoadedLibs = mutableListOf<String>()
        for (libName in apkLibNames) {
            try {
                System.loadLibrary(libName)
                apkLoadedLibs.add(libName)
                Log.i(TAG, "ensureLoaded: APK loaded lib$libName.so")
            } catch (e: UnsatisfiedLinkError) {
                if (e.message?.contains("already loaded") == true ||
                    e.message?.contains("Library already loaded") == true) {
                    apkLoadedLibs.add(libName)
                    Log.i(TAG, "ensureLoaded: lib$libName.so already loaded (APK)")
                } else {
                    Log.e(TAG, "ensureLoaded: APK FAILED lib$libName.so: ${e.message}")
                    apkAllLoaded = false
                }
            }
        }

        if (apkAllLoaded) {
            loaded = true
            Log.i(TAG, "ensureLoaded: ALL native libraries loaded from APK successfully")
            return true
        }

        // Step 2: Fallback - load missing libraries from external storage (audio-models dir)
        Log.w(TAG, "ensureLoaded: APK loading incomplete ($apkLoadedLibs loaded), trying external storage")
        val externalDir = AudioSegmentAnalyzer.getModelDir(context)
        val internalDir = File(context.codeCacheDir, "audio-libs")
        if (!internalDir.exists()) internalDir.mkdirs()

        // v2.4.112: Load ALL missing libraries from external storage, not just libonnxruntime.so
        // Map: external filename → short name for System.loadLibrary check
        val externalLibFiles = listOf(
            "libonnxruntime.so" to "onnxruntime",
            "libonnxruntime4j_jni.so" to "onnxruntime4j_jni",
            "libtensorflowlite_jni.so" to "tensorflowlite_jni"
        )

        for ((soFileName, shortName) in externalLibFiles) {
            // Skip if already loaded from APK
            if (shortName in apkLoadedLibs) {
                Log.i(TAG, "ensureLoaded: $soFileName already loaded from APK, skipping external")
                continue
            }

            val externalSo = File(externalDir, soFileName)
            if (!externalSo.exists()) {
                Log.e(TAG, "ensureLoaded: $soFileName not found in external storage")
                return false
            }

            // Copy to internal dir (codeCacheDir) for reliable System.load()
            val internalSo = File(internalDir, soFileName)
            if (!internalSo.exists() || internalSo.length() != externalSo.length()) {
                try {
                    externalSo.copyTo(internalSo, overwrite = true)
                } catch (e: Exception) {
                    Log.e(TAG, "ensureLoaded: failed to copy $soFileName: ${e.message}")
                    return false
                }
            }

            try {
                System.load(internalSo.absolutePath)
                Log.i(TAG, "ensureLoaded: loaded $soFileName from internal storage (fallback)")
            } catch (e: UnsatisfiedLinkError) {
                if (e.message?.contains("already loaded") == true) {
                    Log.i(TAG, "ensureLoaded: $soFileName already loaded (fallback)")
                } else {
                    Log.e(TAG, "ensureLoaded: FAILED to load $soFileName from internal: ${e.message}")
                    return false
                }
            }
        }

        loaded = true
        Log.i(TAG, "ensureLoaded: ALL native libraries loaded (APK + external fallback)")
        return true
    }

    fun reset() {
        loaded = false
    }
}
