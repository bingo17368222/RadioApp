package com.radio.app.whisper

/**
 * JNI bridge for whisper.cpp C API.
 * The native methods are implemented in libwhisper_jni.so (compiled from whisper_jni.c).
 * The whisper.cpp runtime .so files (libggml-base-whisper.so, libggml-cpu-whisper.so,
 * libggml-whisper.so, libwhisper.so) are downloaded from GitHub releases.
 */
class WhisperBridge {
    companion object {
        private var loaded = false
        
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
                            System.load(targetFile.absolutePath)
                        } catch (e: UnsatisfiedLinkError) {
                            if (e.message?.contains("already loaded") != true) {
                                allLoaded = false
                            }
                        }
                    }
                    if (allLoaded) {
                        loaded = true
                        return true
                    }
                }
            }
            return false
        }
    }
    
    // Native method declarations - these will be implemented in whisper_jni.c
    // For now, they're placeholders that will throw UnsatisfiedLinkError if called
    // without the JNI bridge .so file
    
    /**
     * Initialize whisper context from model file.
     * Returns a pointer (as Long) to the whisper_context.
     */
    external fun initFromFile(modelPath: String): Long
    
    /**
     * Run full transcription on PCM float samples.
     * Returns number of segments.
     */
    external fun full(ctxPtr: Long, samples: FloatArray, nSamples: Int): Int
    
    /**
     * Get the number of segments from the last full() call.
     */
    external fun fullNSegments(ctxPtr: Long): Int
    
    /**
     * Get text of segment i.
     */
    external fun fullGetSegmentText(ctxPtr: Long, segmentIndex: Int): String
    
    /**
     * Get start time of segment i (in milliseconds).
     */
    external fun fullGetSegmentT0(ctxPtr: Long, segmentIndex: Int): Long
    
    /**
     * Get end time of segment i (in milliseconds).
     */
    external fun fullGetSegmentT1(ctxPtr: Long, segmentIndex: Int): Long
    
    /**
     * Free the whisper context.
     */
    external fun free(ctxPtr: Long)
}
