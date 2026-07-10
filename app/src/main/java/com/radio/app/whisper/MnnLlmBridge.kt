package com.radio.app.whisper

import android.content.Context
import android.util.Log
import java.io.File

/**
 * v2.4.35 Bridge for MNN-LLM offline model inference.
 * MNN .so files are downloaded with the model (NOT in APK).
 * v2.4.35: .so files are copied to internal storage before dlopen,
 * because Android 7+ blocks dlopen from external storage.
 */
class MnnLlmBridge {
    companion object {
        private const val TAG = "MnnLlmBridge"

        @Volatile
        var lastError: String = ""
            private set

        // Only load our JNI bridge - MNN libs are loaded via dlopen in native code
        init {
            try {
                System.loadLibrary("mnn_llm_jni")
                Log.i(TAG, "Loaded libmnn_llm_jni.so")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libmnn_llm_jni.so: ${e.message}")
            }
        }

        @JvmStatic
        private external fun nativeInit(libDir: String): Boolean
        @JvmStatic
        private external fun nativeCreateLlm(configPath: String): Long
        @JvmStatic
        private external fun nativeLoad(ptr: Long): Boolean
        @JvmStatic
        private external fun nativeGenerate(ptr: Long, prompt: String, maxTokens: Int): String
        @JvmStatic
        private external fun nativeFree(ptr: Long)
        @JvmStatic
        private external fun nativeSetConfig(ptr: Long, configJson: String): Boolean

        private var initialized = false
        private var llmPtr: Long = 0L

        /**
         * Check if MNN model + libs are installed.
         */
        fun isModelInstalled(modelDir: File): Boolean {
            val hasLlmMnn = File(modelDir, "llm.mnn").let { it.exists() && it.length() > 1_000_000 }
            val hasWeight = File(modelDir, "llm.mnn.weight").let { it.exists() && it.length() > 100_000_000 }
            val hasConfig = File(modelDir, "config.json").exists() || File(modelDir, "llm.mnn.json").exists()
            // v2.4.31: Also check for .so files in the libs subdirectory
            val libsDir = File(modelDir.parentFile, "mnn-libs")
            val hasLibllm = File(libsDir, "libllm.so").exists()
            val installed = hasLlmMnn && hasWeight && hasConfig && hasLibllm
            if (!installed) {
                val missing = mutableListOf<String>()
                if (!hasLlmMnn) missing.add("llm.mnn")
                if (!hasWeight) missing.add("llm.mnn.weight(>100MB)")
                if (!hasConfig) missing.add("config.json/llm.mnn.json")
                if (!hasLibllm) missing.add("libllm.so(MNN运行库)")
                lastError = "文件缺失: ${missing.joinToString(", ")}"
                Log.e(TAG, "isModelInstalled: FALSE - missing: ${missing.joinToString(", ")}")
            }
            return installed
        }

        /**
         * Initialize the MNN-LLM engine.
         * @param modelDir Directory containing llm.mnn, llm.mnn.weight, config.json etc.
         * @param context Application context for accessing internal storage.
         * @return true if initialization succeeded
         */
        fun init(modelDir: File, context: Context? = null): Boolean {
            lastError = ""

            // v2.4.37: Use unified log directory
            val logDir = if (context != null) {
                java.io.File(com.radio.app.RadioApplication.getLogDir(context), "subtitle")
            } else {
                java.io.File("/storage/emulated/0/RadioApp/logs/subtitle")
            }
            try { logDir.mkdirs() } catch (_: Exception) {}
            val logFile = java.io.File(logDir, "mnn_init.log")
            try {
                logFile.parentFile?.mkdirs()
                val log = java.io.FileWriter(logFile, true)
                fun mnnLog(msg: String) {
                    Log.i(TAG, msg)
                    log.write("[${System.currentTimeMillis()}] $msg\n")
                    log.flush()
                }

                val files = modelDir.listFiles()?.map { "${it.name}(${it.length()})" } ?: emptyList()
                mnnLog("init: modelDir=${modelDir.absolutePath}, files=$files")

                // v2.4.31: Get the libs directory (sibling of model dir) - on external storage
                val extLibsDir = File(modelDir.parentFile, "mnn-libs")
                mnnLog("init: extLibsDir=${extLibsDir.absolutePath}, exists=${extLibsDir.exists()}")
                val libFiles = extLibsDir.listFiles()?.map { "${it.name}(${it.length()})" } ?: emptyList()
                mnnLog("init: extLibFiles=$libFiles")

                if (!initialized) {
                    // v2.4.35: Copy .so files to internal storage because Android 7+ blocks
                    // dlopen from external storage (/storage/emulated/0/...)
                    val internalLibsDir = if (context != null) {
                        File(context.filesDir, "mnn-libs")
                    } else {
                        File("/data/data/com.radio.app/files/mnn-libs")
                    }
                    mnnLog("init: internalLibsDir=${internalLibsDir.absolutePath}")

                    if (!internalLibsDir.exists()) internalLibsDir.mkdirs()

                    // Check if we need to copy (if internal dir is empty or missing libllm.so)
                    // v2.4.38: Also re-copy if any .so file size doesn't match known-good size
                    val knownGoodSizes = mapOf(
                        "libMNN.so" to 2410968L,
                        "libMNN_Express.so" to 747344L,
                        "libMNN_Vulkan.so" to 775696L,
                        "libMNN_CL.so" to 2212056L,
                        "libMNNOpenCV.so" to 264424L,
                        "libMNNAudio.so" to 70952L,
                        "libmnncore.so" to 22816L,
                        "libllm.so" to 1257112L
                    )
                    var needsCopy = !File(internalLibsDir, "libllm.so").exists()
                    // Check if any existing file has wrong size
                    if (!needsCopy) {
                        for ((name, expectedSize) in knownGoodSizes) {
                            val f = File(internalLibsDir, name)
                            if (!f.exists() || f.length() != expectedSize) {
                                needsCopy = true
                                mnnLog("init: $name needs re-copy (exists=${f.exists()}, size=${if (f.exists()) f.length() else 0}, expected=$expectedSize)")
                                break
                            }
                        }
                    }
                    mnnLog("init: needsCopy=$needsCopy, extLibsDir.exists=${extLibsDir.exists()}")
                    if (needsCopy && extLibsDir.exists()) {
                        mnnLog("init: copying .so files from ${extLibsDir.absolutePath} to ${internalLibsDir.absolutePath}")
                        // v2.4.38: Delete existing internal files that have wrong size
                        for ((libName, expectedSize) in knownGoodSizes) {
                            val existing = File(internalLibsDir, libName)
                            if (existing.exists() && existing.length() != expectedSize) {
                                mnnLog("init: deleting corrupted $libName (size=${existing.length()}, expected=$expectedSize)")
                                existing.delete()
                            }
                        }
                        val requiredLibs = listOf(
                            "libMNN.so", "libMNN_Express.so", "libMNN_Vulkan.so", "libMNN_CL.so",
                            "libMNNOpenCV.so", "libMNNAudio.so", "libmnncore.so", "libllm.so"
                        )
                        for (libName in requiredLibs) {
                            val srcFile = File(extLibsDir, libName)
                            val dstFile = File(internalLibsDir, libName)
                            mnnLog("init: checking $libName: src exists=${srcFile.exists()}, src size=${if (srcFile.exists()) srcFile.length() else 0}")
                            if (srcFile.exists() && srcFile.length() > 1000) {
                                if (!dstFile.exists() || dstFile.length() != srcFile.length()) {
                                    srcFile.inputStream().use { input ->
                                        dstFile.outputStream().use { output -> input.copyTo(output) }
                                    }
                                    dstFile.setExecutable(true, false)
                                    mnnLog("init: copied $libName (${dstFile.length()} bytes)")
                                } else {
                                    mnnLog("init: $libName already copied, same size")
                                }
                            } else {
                                mnnLog("init: ERROR missing $libName in external libs dir")
                            }
                        }
                    }

                    val internalFiles = internalLibsDir.listFiles()?.map { "${it.name}(${it.length()})" } ?: emptyList()
                    mnnLog("init: internalLibs after copy: $internalFiles")

                    mnnLog("init: calling nativeInit(libDir=${internalLibsDir.absolutePath})...")
                    initialized = nativeInit(internalLibsDir.absolutePath)
                    mnnLog("init: nativeInit returned $initialized")
                    if (!initialized) {
                        lastError = "nativeInit失败: 无法加载MNN运行库(检查mnn-libs目录)"
                        mnnLog("init: FAILED - $lastError")
                        log.close()
                        return false
                    }
                    mnnLog("init: nativeInit OK")
                    log.close()
                }

                val configFile = File(modelDir, "llm.mnn.json").takeIf { it.exists() }
                    ?: File(modelDir, "config.json").takeIf { it.exists() }
                val configPath = configFile?.absolutePath ?: (modelDir.absolutePath + "/")

                mnnLog("init: creating LLM with config: $configPath")
                llmPtr = nativeCreateLlm(configPath)
                if (llmPtr == 0L) {
                    lastError = "createLLM失败: 无法创建LLM实例(config=$configPath)"
                    mnnLog("init: FAILED - nativeCreateLlm returned 0")
                    log.close()
                    return false
                }
                mnnLog("init: nativeCreateLlm OK, ptr=$llmPtr")

                mnnLog("init: loading model...")
                val loaded = nativeLoad(llmPtr)
                if (!loaded) {
                    lastError = "模型加载失败: load()返回false(模型文件可能损坏)"
                    mnnLog("init: FAILED - nativeLoad returned false")
                    nativeFree(llmPtr)
                    llmPtr = 0L
                    log.close()
                    return false
                }

                mnnLog("init: MNN LLM ready!")
                log.close()
                return true
            } catch (e: Exception) {
                Log.e(TAG, "init: EXCEPTION: ${e.message}", e)
                try {
                    val log = java.io.FileWriter(logFile, true)
                    log.write("[${System.currentTimeMillis()}] init: EXCEPTION: ${e.message}\n")
                    log.close()
                } catch (_: Exception) {}
                return false
            }
        }

        fun generate(prompt: String, maxTokens: Int = 2000): String {
            if (llmPtr == 0L) {
                Log.e(TAG, "generate: LLM not initialized")
                return ""
            }
            return nativeGenerate(llmPtr, prompt, maxTokens)
        }

        fun release() {
            if (llmPtr != 0L) {
                nativeFree(llmPtr)
                llmPtr = 0L
            }
        }

        /**
         * Classify subtitle segments in batches of 1 segment.
         * v2.4.31: batch size = 1 for faster progress feedback
         */
        fun classifySubtitles(
            subtitles: List<Triple<Long, Long, String>>,
            segmentDurationMs: Long = 3 * 60 * 1000L,
            onProgress: ((Int, Int) -> Unit)? = null
        ): List<MnnSegmentResult>? {
            if (llmPtr == 0L) {
                Log.e(TAG, "classifySubtitles: LLM not initialized")
                return null
            }

            val groups = mutableListOf<Triple<Long, Long, String>>()
            var currentStart = 0L
            var currentEnd = segmentDurationMs
            var currentText = StringBuilder()

            for ((start, end, text) in subtitles) {
                while (start >= currentEnd && currentText.isNotEmpty()) {
                    groups.add(Triple(currentStart, currentEnd, currentText.toString()))
                    currentStart = currentEnd
                    currentEnd = currentStart + segmentDurationMs
                    currentText = StringBuilder()
                }
                currentText.append(text)
            }
            if (currentText.isNotEmpty()) {
                groups.add(Triple(currentStart, currentEnd, currentText.toString()))
            }

            if (groups.isEmpty()) return null

            // v2.4.31: Process ONE segment at a time for fastest progress feedback
            val allResults = mutableListOf<MnnSegmentResult>()
            Log.i(TAG, "classifySubtitles: ${groups.size} segments, batch=1")

            for ((idx, group) in groups.withIndex()) {
                onProgress?.invoke(idx, groups.size)

                val startTime = group.first / 1000
                val endTime = group.second / 1000
                val text = if (group.third.length > 300) group.third.substring(0, 300) + "..." else group.third

                // v2.4.31: Simpler prompt, fewer tokens
                val prompt = "判断以下广播内容是「干货」(新闻/资讯/访谈)还是「水货」(广告/音乐/闲聊)。只回答\"干货\"或\"水货\"。\n${startTime}s-${endTime}s: $text"

                val response = generate(prompt, 50)
                if (response.isNotBlank()) {
                    val isDry = response.contains("干货")
                    allResults.add(MnnSegmentResult(
                        start = group.first,
                        end = group.second,
                        isDry = isDry,
                        label = if (isDry) "干货" else "水货"
                    ))
                    Log.i(TAG, "classifySubtitles: seg ${idx + 1}/${groups.size} -> ${if (isDry) "干货" else "水货"} (resp=${response.take(20)})")
                } else {
                    // Default to dry if no response
                    allResults.add(MnnSegmentResult(
                        start = group.first,
                        end = group.second,
                        isDry = true,
                        label = "干货"
                    ))
                    Log.w(TAG, "classifySubtitles: seg ${idx + 1}/${groups.size} -> empty, defaulting to 干货")
                }
            }

            onProgress?.invoke(groups.size, groups.size)
            Log.i(TAG, "classifySubtitles: total results=${allResults.size}")
            return allResults
        }

        data class MnnSegmentResult(
            val start: Long,
            val end: Long,
            val isDry: Boolean,
            val label: String
        )
    }
}
