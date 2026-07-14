package com.radio.app.utils

import android.content.Context
import android.util.Log
import java.io.File

/**
 * v2.4.98: Runtime native library loader for ONNX Runtime and TFLite.
 *
 * The .so files are excluded from the APK to reduce its size.
 * They are downloaded from the offline engine management and stored in the
 * audio-models directory (external storage).
 *
 * v2.4.98 FIX: Android SELinux prevents dlopen() from external storage paths.
 * Solution: Copy .so files to the app's internal code cache directory
 * (getCodeCacheDir()) before calling System.load().
 */
object NativeLibLoader {
    private const val TAG = "NativeLibLoader"
    private var loaded = false

    // Required .so files for audio segmentation
    private val REQUIRED_SO_FILES = listOf(
        "libonnxruntime.so",
        "libonnxruntime4j_jni.so",
        "libtensorflowlite_jni.so"
    )

    /**
     * Check if all required .so files are downloaded (in external storage).
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
     * v2.4.98: Get the internal code cache directory for .so files.
     * This is where we copy .so files so System.load() can work.
     */
    private fun getInternalLibDir(context: Context): File {
        val dir = File(context.codeCacheDir, "audio-libs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * v2.4.98: Check if .so files are already copied to internal storage.
     */
    private fun areLibsCopiedInternally(internalDir: File): Boolean {
        return REQUIRED_SO_FILES.all { File(internalDir, it).exists() }
    }

    /**
     * v2.4.98: Copy .so files from external storage to internal code cache.
     * Only copies if the file doesn't exist or has a different size.
     */
    private fun copyLibsToInternal(externalDir: File, internalDir: File): Boolean {
        for (soName in REQUIRED_SO_FILES) {
            val srcFile = File(externalDir, soName)
            val dstFile = File(internalDir, soName)
            if (!srcFile.exists()) {
                Log.e(TAG, "copyLibsToInternal: source missing: ${srcFile.absolutePath}")
                return false
            }
            // Only copy if destination doesn't exist or size differs
            if (!dstFile.exists() || dstFile.length() != srcFile.length()) {
                Log.i(TAG, "copyLibsToInternal: copying $soName (${srcFile.length()} bytes)")
                try {
                    srcFile.copyTo(dstFile, overwrite = true)
                } catch (e: Exception) {
                    Log.e(TAG, "copyLibsToInternal: failed to copy $soName: ${e.message}")
                    return false
                }
            }
        }
        Log.i(TAG, "copyLibsToInternal: all .so files copied to ${internalDir.absolutePath}")
        return true
    }

    /**
     * Load all native libraries. Must be called before any ONNX Runtime or TFLite usage.
     * Returns true if all libraries loaded successfully (or were already loaded).
     *
     * v2.4.98: Copies .so files to internal codeCacheDir before System.load()
     * to avoid SELinux dlopen failures on external storage paths.
     */
    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (loaded) return true

        val externalDir = AudioSegmentAnalyzer.getModelDir(context)
        val internalDir = getInternalLibDir(context)

        Log.i(TAG, "ensureLoaded: externalDir=${externalDir.absolutePath}, exists=${externalDir.exists()}")
        Log.i(TAG, "ensureLoaded: dir contents=${externalDir.listFiles()?.map { "${it.name}(${it.length()})" } ?: "null"}")
        Log.i(TAG, "ensureLoaded: libs status: ${getLibsStatus(externalDir)}")

        // Step 1: Check if .so files are downloaded
        if (!areLibsDownloaded(externalDir)) {
            Log.e(TAG, "ensureLoaded: .so files not downloaded in ${externalDir.absolutePath}")
            return false
        }

        // Step 2: Copy .so files to internal storage (v2.4.98 fix)
        if (!areLibsCopiedInternally(internalDir)) {
            Log.i(TAG, "ensureLoaded: copying .so files to internal storage")
            if (!copyLibsToInternal(externalDir, internalDir)) {
                Log.e(TAG, "ensureLoaded: failed to copy .so files to internal storage")
                return false
            }
        } else {
            // Verify internal copies are up-to-date (same size as external)
            var needsRefresh = false
            for (soName in REQUIRED_SO_FILES) {
                val ext = File(externalDir, soName)
                val int = File(internalDir, soName)
                if (ext.length() != int.length()) {
                    needsRefresh = true
                    break
                }
            }
            if (needsRefresh) {
                Log.i(TAG, "ensureLoaded: refreshing .so files (size mismatch)")
                if (!copyLibsToInternal(externalDir, internalDir)) {
                    Log.e(TAG, "ensureLoaded: failed to refresh .so files")
                    return false
                }
            }
        }

        // Step 3: Load from internal storage
        for (soName in REQUIRED_SO_FILES) {
            val soFile = File(internalDir, soName)
            if (!soFile.exists()) {
                Log.e(TAG, "ensureLoaded: missing internal: ${soFile.absolutePath}")
                return false
            }
            try {
                Log.i(TAG, "ensureLoaded: loading ${soFile.name} (${soFile.length()} bytes) from ${soFile.parent}")
                System.load(soFile.absolutePath)
                Log.i(TAG, "ensureLoaded: loaded ${soFile.name}")
            } catch (e: UnsatisfiedLinkError) {
                if (e.message?.contains("already loaded") == true) {
                    Log.i(TAG, "ensureLoaded: ${soFile.name} already loaded, continuing")
                } else {
                    Log.e(TAG, "ensureLoaded: Failed to load ${soFile.name}: ${e.message}")
                    return false
                }
            } catch (e: Exception) {
                Log.e(TAG, "ensureLoaded: Failed to load ${soFile.name}: ${e.message}")
                return false
            }
        }

        loaded = true
        Log.i(TAG, "ensureLoaded: All native libraries loaded successfully from ${internalDir.absolutePath}")
        return true
    }

    /**
     * Reset the loaded state (for testing or after re-download).
     */
    fun reset() {
        loaded = false
    }
}
