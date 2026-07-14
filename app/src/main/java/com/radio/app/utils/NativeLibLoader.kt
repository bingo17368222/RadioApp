package com.radio.app.utils

import android.content.Context
import android.util.Log
import java.io.File

/**
 * v2.4.99: Runtime native library loader for ONNX Runtime.
 *
 * Only libonnxruntime.so (14.6MB) is excluded from the APK and downloaded at runtime.
 * The JNI wrapper .so files (libonnxruntime4j_jni.so, libtensorflowlite_jni.so) are
 * kept in the APK because the ONNX Runtime and TFLite Java SDKs call
 * System.loadLibrary() internally, which only searches standard Android library paths.
 *
 * v2.4.98 FIX: Android SELinux prevents dlopen() from external storage paths.
 * Solution: Copy libonnxruntime.so to the app's internal code cache directory
 * (getCodeCacheDir()) before calling System.load().
 *
 * v2.4.99 FIX: ONNX Runtime Java SDK calls System.loadLibrary("onnxruntime4j_jni")
 * which cannot find libraries loaded via System.load(path). Keeping the JNI .so
 * in the APK fixes this. When the JNI .so is loaded, the dynamic linker finds
 * libonnxruntime.so already loaded (via our System.load()) as a dependency.
 */
object NativeLibLoader {
    private const val TAG = "NativeLibLoader"
    private var loaded = false

    // v2.4.99: Only libonnxruntime.so needs to be downloaded and loaded via System.load().
    // The JNI wrapper .so files are in the APK and loaded via System.loadLibrary().
    private const val REQUIRED_SO = "libonnxruntime.so"

    /**
     * Check if the required .so file is downloaded (in external storage).
     */
    fun areLibsDownloaded(modelDir: File): Boolean {
        return File(modelDir, REQUIRED_SO).exists()
    }

    /**
     * Get detailed status of the .so file for diagnostics.
     */
    fun getLibsStatus(modelDir: File): String {
        val f = File(modelDir, REQUIRED_SO)
        return "$REQUIRED_SO=${if (f.exists()) "${f.length()}B" else "MISSING"}"
    }

    /**
     * Get the internal code cache directory for .so files.
     * This is where we copy .so files so System.load() can work.
     */
    private fun getInternalLibDir(context: Context): File {
        val dir = File(context.codeCacheDir, "audio-libs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * v2.4.99: Load libonnxruntime.so from downloaded location.
     * Copies to internal codeCacheDir first (SELinux fix), then System.load().
     *
     * The JNI wrapper .so files (libonnxruntime4j_jni.so, libtensorflowlite_jni.so)
     * are in the APK and will be found by System.loadLibrary() when the ONNX/TFLite
     * Java SDKs initialize.
     */
    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (loaded) return true

        val externalDir = AudioSegmentAnalyzer.getModelDir(context)
        val internalDir = getInternalLibDir(context)
        val externalSo = File(externalDir, REQUIRED_SO)
        val internalSo = File(internalDir, REQUIRED_SO)

        Log.i(TAG, "ensureLoaded: externalDir=${externalDir.absolutePath}, exists=${externalDir.exists()}")
        Log.i(TAG, "ensureLoaded: dir contents=${externalDir.listFiles()?.map { "${it.name}(${it.length()})" } ?: "null"}")
        Log.i(TAG, "ensureLoaded: $REQUIRED_SO status: ${getLibsStatus(externalDir)}")

        // Step 1: Check if .so file is downloaded
        if (!externalSo.exists()) {
            Log.e(TAG, "ensureLoaded: $REQUIRED_SO not downloaded in ${externalDir.absolutePath}")
            return false
        }

        // Step 2: Copy to internal storage if needed (SELinux fix)
        if (!internalSo.exists() || internalSo.length() != externalSo.length()) {
            Log.i(TAG, "ensureLoaded: copying $REQUIRED_SO (${externalSo.length()} bytes) to internal storage")
            try {
                externalSo.copyTo(internalSo, overwrite = true)
            } catch (e: Exception) {
                Log.e(TAG, "ensureLoaded: failed to copy $REQUIRED_SO: ${e.message}")
                return false
            }
        }

        // Step 3: Load from internal storage
        if (!internalSo.exists()) {
            Log.e(TAG, "ensureLoaded: missing internal: ${internalSo.absolutePath}")
            return false
        }
        try {
            Log.i(TAG, "ensureLoaded: loading $REQUIRED_SO (${internalSo.length()} bytes) from ${internalSo.parent}")
            System.load(internalSo.absolutePath)
            Log.i(TAG, "ensureLoaded: loaded $REQUIRED_SO")
        } catch (e: UnsatisfiedLinkError) {
            if (e.message?.contains("already loaded") == true || e.message?.contains("Library already loaded") == true) {
                Log.i(TAG, "ensureLoaded: $REQUIRED_SO already loaded, continuing")
            } else {
                Log.e(TAG, "ensureLoaded: Failed to load $REQUIRED_SO: ${e.message}")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensureLoaded: Failed to load $REQUIRED_SO: ${e.message}")
            return false
        }

        loaded = true
        Log.i(TAG, "ensureLoaded: $REQUIRED_SO loaded successfully from ${internalSo.absolutePath}")
        return true
    }

    /**
     * Reset the loaded state (for testing or after re-download).
     */
    fun reset() {
        loaded = false
    }
}
