package com.radio.app.utils

import android.content.Context
import android.util.Log
import java.io.File

/**
 * v2.4.105: Pre-load ALL native libraries from the APK.
 *
 * Root cause of ALL previous failures (v2.4.97-v2.4.104):
 * The ONNX Runtime static initializer (OrtEnvironment.<clinit>) calls
 * System.loadLibrary("onnxruntime4j_jni"). If this fails for ANY reason
 * (e.g., DT_NEEDED resolution, linker namespace, etc.), the class is
 * marked "erroneous" and ALL subsequent ONNX Runtime usage fails.
 *
 * The error "ai.onnxruntime.SessionOptions" in logs is misleading —
 * it's actually a NoClassDefFoundError caused by OrtEnvironment being
 * in an erroneous state after a failed static init.
 *
 * Fix: Pre-load ALL .so files from the APK in the correct order:
 * 1. libonnxruntime.so (the main library)
 * 2. libonnxruntime4j_jni.so (JNI wrapper, depends on libonnxruntime.so)
 * 3. libtensorflowlite_jni.so (TFLite JNI wrapper)
 *
 * When the ONNX Runtime static initializer later calls
 * System.loadLibrary("onnxruntime4j_jni"), the library is already
 * loaded → no-op → static init succeeds.
 */
object NativeLibLoader {
    private const val TAG = "NativeLibLoader"
    private var loaded = false

    private const val REQUIRED_SO = "libonnxruntime.so"

    fun areLibsDownloaded(modelDir: File): Boolean {
        return File(modelDir, REQUIRED_SO).exists()
    }

    fun getLibsStatus(modelDir: File): String {
        val f = File(modelDir, REQUIRED_SO)
        return "$REQUIRED_SO=${if (f.exists()) "${f.length()}B" else "MISSING"}"
    }

    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (loaded) return true

        Log.i(TAG, "ensureLoaded: starting (v2.4.105)")

        // v2.4.105: Pre-load ALL native libraries from APK in dependency order.
        // This ensures that when ONNX Runtime's static initializer calls
        // System.loadLibrary("onnxruntime4j_jni"), the library is already loaded.
        val libsToLoad = listOf(
            "onnxruntime",           // libonnxruntime.so (main library)
            "onnxruntime4j_jni",     // libonnxruntime4j_jni.so (JNI wrapper, depends on onnxruntime)
            "tensorflowlite_jni"     // libtensorflowlite_jni.so (TFLite JNI wrapper)
        )

        var allLoaded = true
        for (libName in libsToLoad) {
            try {
                System.loadLibrary(libName)
                Log.i(TAG, "ensureLoaded: loaded lib$libName.so from APK")
            } catch (e: UnsatisfiedLinkError) {
                if (e.message?.contains("already loaded") == true ||
                    e.message?.contains("Library already loaded") == true) {
                    Log.i(TAG, "ensureLoaded: lib$libName.so already loaded")
                } else {
                    Log.e(TAG, "ensureLoaded: FAILED to load lib$libName.so: ${e.message}")
                    allLoaded = false
                }
            }
        }

        if (allLoaded) {
            loaded = true
            Log.i(TAG, "ensureLoaded: ALL native libraries loaded successfully from APK")
            return true
        }

        // Fallback: try loading from external storage (downloaded audio-models package)
        Log.w(TAG, "ensureLoaded: APK loading failed, trying external storage fallback")
        val externalDir = AudioSegmentAnalyzer.getModelDir(context)
        val internalDir = File(context.codeCacheDir, "audio-libs")
        if (!internalDir.exists()) internalDir.mkdirs()
        val externalSo = File(externalDir, REQUIRED_SO)
        val internalSo = File(internalDir, REQUIRED_SO)

        if (!externalSo.exists()) {
            Log.e(TAG, "ensureLoaded: $REQUIRED_SO not found in APK or external storage")
            return false
        }

        if (!internalSo.exists() || internalSo.length() != externalSo.length()) {
            try {
                externalSo.copyTo(internalSo, overwrite = true)
            } catch (e: Exception) {
                Log.e(TAG, "ensureLoaded: failed to copy: ${e.message}")
                return false
            }
        }

        try {
            System.load(internalSo.absolutePath)
            loaded = true
            Log.i(TAG, "ensureLoaded: loaded $REQUIRED_SO from internal storage (fallback)")
            return true
        } catch (e: UnsatisfiedLinkError) {
            if (e.message?.contains("already loaded") == true) {
                loaded = true
                return true
            }
            Log.e(TAG, "ensureLoaded: fallback also failed: ${e.message}")
            return false
        }
    }

    fun reset() {
        loaded = false
    }
}
