package com.radio.app.utils

import android.content.Context
import android.util.Log
import java.io.File

/**
 * v2.4.95: Runtime native library loader for ONNX Runtime and TFLite.
 *
 * The .so files are excluded from the APK to reduce its size.
 * They are downloaded from the offline engine management and stored in the
 * audio-models directory. This class loads them via System.load() before any
 * ONNX Runtime or TFLite class is accessed.
 *
 * After System.load("/path/libonnxruntime.so"), the library is registered in
 * the JVM's loaded library table. When the ONNX Runtime's OrtEnvironment
 * static initializer calls System.loadLibrary("onnxruntime"), it finds the
 * library already loaded and returns immediately.
 */
object NativeLibLoader {
    private const val TAG = "NativeLibLoader"
    private var loaded = false
    private var loadAttempted = false

    // Required .so files for audio segmentation
    private val REQUIRED_SO_FILES = listOf(
        "libonnxruntime.so",
        "libonnxruntime4j_jni.so",
        "libtensorflowlite_jni.so"
    )

    /**
     * Check if all required .so files are downloaded.
     */
    fun areLibsDownloaded(modelDir: File): Boolean {
        return REQUIRED_SO_FILES.all { File(modelDir, it).exists() }
    }

    /**
     * v2.4.97: Get detailed status of each .so file for diagnostics.
     */
    fun getLibsStatus(modelDir: File): String {
        return REQUIRED_SO_FILES.joinToString(", ") { name ->
            val f = File(modelDir, name)
            "$name=${if (f.exists()) "${f.length()}B" else "MISSING"}"
        }
    }

    /**
     * Load all native libraries. Must be called before any ONNX Runtime or TFLite usage.
     * Returns true if all libraries loaded successfully (or were already loaded).
     */
    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (loaded) return true
        // v2.4.97: Allow retry after failure (user may download runtime after first attempt)

        val modelDir = AudioSegmentAnalyzer.getModelDir(context)

        // v2.4.97: Detailed logging for debugging
        Log.i(TAG, "ensureLoaded: modelDir=${modelDir.absolutePath}, exists=${modelDir.exists()}")
        Log.i(TAG, "ensureLoaded: dir contents=${modelDir.listFiles()?.map { "${it.name}(${it.length()})" } ?: "null listFiles"}")
        Log.i(TAG, "ensureLoaded: libs status: ${getLibsStatus(modelDir)}")

        if (!areLibsDownloaded(modelDir)) {
            Log.e(TAG, "Native libs not downloaded in ${modelDir.absolutePath}")
            Log.e(TAG, "Required: $REQUIRED_SO_FILES")
            Log.e(TAG, "Found: ${modelDir.listFiles()?.map { "${it.name}(${it.length()})" } ?: "null"}")
            return false
        }

        // Load in dependency order: onnxruntime first, then JNI bridge, then TFLite
        for (soName in REQUIRED_SO_FILES) {
            val soFile = File(modelDir, soName)
            if (!soFile.exists()) {
                Log.e(TAG, "Missing: ${soFile.absolutePath}")
                return false
            }
            try {
                Log.i(TAG, "Loading native lib: ${soFile.name} (${soFile.length()} bytes)")
                System.load(soFile.absolutePath)
                Log.i(TAG, "Loaded: ${soFile.name}")
            } catch (e: UnsatisfiedLinkError) {
                // Library might already be loaded by the system
                if (e.message?.contains("already loaded") == true) {
                    Log.i(TAG, "${soFile.name} already loaded, continuing")
                } else {
                    Log.e(TAG, "Failed to load ${soFile.name}: ${e.message}")
                    return false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load ${soFile.name}: ${e.message}")
                return false
            }
        }

        loaded = true
        Log.i(TAG, "All native libraries loaded successfully")
        return true
    }

    /**
     * Reset the loaded state (for testing or after re-download).
     */
    fun reset() {
        loaded = false
        loadAttempted = false
    }
}
