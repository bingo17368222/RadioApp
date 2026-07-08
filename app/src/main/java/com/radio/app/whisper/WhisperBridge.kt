package com.radio.app.whisper

import android.util.Log

/**
 * JNI bridge for whisper.cpp C API.
 * The native methods are implemented in libwhisper_jni.so (compiled from whisper_jni.c).
 * The whisper.cpp runtime .so files (libggml-base-whisper.so, libggml-cpu-whisper.so,
 * libggml-whisper.so, libwhisper.so) are downloaded from GitHub releases.
 */
class WhisperBridge {
    companion object {
        private const val TAG = "WhisperBridge"
        private var loaded = false
        private var whisperSoPath: String? = null

        /** Optimization mode constants for full() call */
        const val OPT_ACCURACY = 0   // For tiny model: beam search, temperature fallback
        const val OPT_BALANCED = 1   // For base model: greedy, multi-threaded
        const val OPT_SPEED = 2      // For small model: greedy, max threads, VAD skip

        fun getWhisperSoPath(): String? = whisperSoPath

        fun loadNativeLibraries(codeCacheDir: java.io.File, modelsDir: java.io.File?): Boolean {
            if (loaded) return true
            val soFiles = listOf(
                "libggml-base-whisper.so",
                "libggml-cpu-whisper.so",
                "libggml-whisper.so",
                "libwhisper.so"
            )
            val searchDirs = mutableListOf<java.io.File>()
            modelsDir?.let { if (it.exists()) searchDirs.add(it) }

            for (searchDir in searchDirs) {
                val foundFiles = mutableMapOf<String, java.io.File>()
                searchDir.walkTopDown().filter { it.isFile && it.name in soFiles }.forEach {
                    foundFiles[it.name] = it
                }
                if (foundFiles.containsKey("libwhisper.so")) {
                    var allLoaded = true
                    for (soName in soFiles) {
                        val soFile = foundFiles[soName] ?: continue
                        try {
                            val targetFile = java.io.File(codeCacheDir, soName)
                            if (targetFile.exists()) targetFile.delete()
                            soFile.copyTo(targetFile, overwrite = true)
                            Log.d(TAG, "Loading native library: ${targetFile.absolutePath}")
                            System.load(targetFile.absolutePath)
                            if (soName == "libwhisper.so") {
                                whisperSoPath = targetFile.absolutePath
                            }
                        } catch (e: UnsatisfiedLinkError) {
                            if (e.message?.contains("already loaded") != true) {
                                Log.e(TAG, "Failed to load $soName: ${e.message}")
                                allLoaded = false
                            }
                        }
                    }
                    if (allLoaded) {
                        // Load the JNI bridge (libwhisper_jni.so compiled by CMake)
                        try {
                            System.loadLibrary("whisper_jni")
                            Log.d(TAG, "Loaded JNI bridge: libwhisper_jni.so")
                        } catch (e: UnsatisfiedLinkError) {
                            Log.e(TAG, "Failed to load libwhisper_jni.so: ${e.message}")
                            allLoaded = false
                        }
                    }
                    if (allLoaded) {
                        loaded = true
                        Log.d(TAG, "All native libraries loaded successfully, whisperSoPath=$whisperSoPath")
                        return true
                    }
                }
            }
            Log.e(TAG, "Failed to load native libraries")
            return false
        }
    }

    /**
     * Set the full path to libwhisper.so for dlopen.
     * Must be called before initFromFile().
     */
    fun setLibraryPath(path: String) {
        Log.d(TAG, "setLibraryPath: $path")
        setLibraryPathNative(path)
    }

    // Native method declarations

    private external fun setLibraryPathNative(path: String)

    /**
     * Initialize whisper context from model file.
     * Returns a pointer (as Long) to the whisper_context.
     */
    external fun initFromFile(modelPath: String): Long

    /**
     * Run full transcription on PCM float samples.
     * @param opt_mode Optimization mode: 0=ACCURACY (tiny, beam search),
     *                 1=BALANCED (base, greedy+threads), 2=SPEED (small, greedy+VAD skip)
     * Returns 0 on success, non-zero on error.
     */
    external fun full(ctxPtr: Long, samples: FloatArray, nSamples: Int, optMode: Int): Int

    /**
     * Backward-compatible overload (defaults to BALANCED mode).
     */
    fun full(ctxPtr: Long, samples: FloatArray, nSamples: Int): Int {
        return full(ctxPtr, samples, nSamples, OPT_BALANCED)
    }

    /**
     * Get the number of segments from the last full() call.
     */
    external fun fullNSegments(ctxPtr: Long): Int

    /**
     * Get text of segment i.
     */
    external fun fullGetSegmentText(ctxPtr: Long, segmentIndex: Int): String

    /**
     * Get start time of segment i (in centiseconds, multiply by 10 for ms).
     */
    external fun fullGetSegmentT0(ctxPtr: Long, segmentIndex: Int): Long

    /**
     * Get end time of segment i (in centiseconds, multiply by 10 for ms).
     */
    external fun fullGetSegmentT1(ctxPtr: Long, segmentIndex: Int): Long

    /**
     * Free the whisper context.
     */
    external fun free(ctxPtr: Long)
}
