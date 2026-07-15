package com.radio.app.utils

import android.content.Context
import android.util.Log
import java.io.File

/**
 * v2.4.104: Load libonnxruntime.so from the APK (not external storage).
 *
 * Root cause of previous failures (v2.4.97-v2.4.103):
 * - System.load(absolutePath) loads the .so into a different linker namespace
 *   than System.loadLibrary(). When ONNX Runtime's static initializer calls
 *   System.loadLibrary("onnxruntime4j_jni"), dlopen resolves the
 *   DT_NEEDED(libonnxruntime.so) dependency by searching the APK's lib/
 *   directory — but libonnxruntime.so was NOT in the APK (it was excluded
 *   and loaded via System.load() from external storage). The System.load()
 *   copy was invisible to dlopen's DT_NEEDED resolution on Android 12+.
 *
 * Fix (v2.4.103+):
 * - Include libonnxruntime.so in the APK (remove the exclude rule)
 * - Call System.loadLibrary("onnxruntime") instead of System.load(path)
 *   This loads from the APK's lib/ directory, in the SAME namespace as
 *   System.loadLibrary("onnxruntime4j_jni"), so DT_NEEDED resolution works.
 *
 * The external copy (downloaded via audio-models package) is no longer needed
 * but is kept for backward compatibility (older APK versions without the .so
 * in the APK still need it).
 */
object NativeLibLoader {
    private const val TAG = "NativeLibLoader"
    private var loaded = false

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
     * v2.4.104: Load libonnxruntime.so using System.loadLibrary() (from APK).
     *
     * Previous versions used System.load(absolutePath) which loads into a
     * different linker namespace, making the .so invisible to DT_NEEDED
     * resolution when libonnxruntime4j_jni.so is loaded later.
     *
     * Now we try System.loadLibrary("onnxruntime") first, which loads from
     * the APK's lib/ directory in the correct namespace. If that fails
     * (e.g., .so not in APK on older builds), fall back to System.load().
     */
    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (loaded) return true

        Log.i(TAG, "ensureLoaded: starting (v2.4.104)")

        // v2.4.104: Try loading from APK first (correct linker namespace)
        try {
            System.loadLibrary("onnxruntime")
            loaded = true
            Log.i(TAG, "ensureLoaded: loaded libonnxruntime.so from APK via System.loadLibrary()")
            return true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "ensureLoaded: System.loadLibrary(\"onnxruntime\") failed: ${e.message}")
            // Fall through to external storage fallback
        }

        // Fallback: Load from external storage (downloaded audio-models package)
        // This is for backward compatibility with APKs that don't include libonnxruntime.so
        val externalDir = AudioSegmentAnalyzer.getModelDir(context)
        val internalDir = File(context.codeCacheDir, "audio-libs")
        if (!internalDir.exists()) internalDir.mkdirs()
        val externalSo = File(externalDir, REQUIRED_SO)
        val internalSo = File(internalDir, REQUIRED_SO)

        Log.i(TAG, "ensureLoaded: fallback to external, externalDir=${externalDir.absolutePath}, exists=${externalDir.exists()}")

        if (!externalSo.exists()) {
            Log.e(TAG, "ensureLoaded: $REQUIRED_SO not found in APK or external storage")
            return false
        }

        // Copy to internal storage (SELinux fix for external storage paths)
        if (!internalSo.exists() || internalSo.length() != externalSo.length()) {
            Log.i(TAG, "ensureLoaded: copying $REQUIRED_SO (${externalSo.length()} bytes) to internal storage")
            try {
                externalSo.copyTo(internalSo, overwrite = true)
            } catch (e: Exception) {
                Log.e(TAG, "ensureLoaded: failed to copy: ${e.message}")
                return false
            }
        }

        try {
            Log.i(TAG, "ensureLoaded: loading from internal: ${internalSo.absolutePath}")
            System.load(internalSo.absolutePath)
            loaded = true
            Log.i(TAG, "ensureLoaded: loaded $REQUIRED_SO from internal storage")
            return true
        } catch (e: UnsatisfiedLinkError) {
            if (e.message?.contains("already loaded") == true || e.message?.contains("Library already loaded") == true) {
                loaded = true
                Log.i(TAG, "ensureLoaded: already loaded, continuing")
                return true
            }
            Log.e(TAG, "ensureLoaded: Failed to load: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "ensureLoaded: Failed to load: ${e.message}")
            return false
        }
    }

    /**
     * Reset the loaded state (for testing or after re-download).
     */
    fun reset() {
        loaded = false
    }
}
