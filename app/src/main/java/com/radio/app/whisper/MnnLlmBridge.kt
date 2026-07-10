package com.radio.app.whisper

import android.util.Log
import java.io.File

/**
 * v2.4.29 Bridge for MNN-LLM offline model inference.
 * Uses JNI to call MNN Llm API via dlopen/dlsym.
 * Requires libMNN.so, libllm.so, libMNN_Express.so, libMNN_Vulkan.so, libMNN_CL.so,
 * libMNNOpenCV.so, libMNNAudio.so, libmnncore.so, libc++_shared.so in jniLibs.
 */
class MnnLlmBridge {
    companion object {
        private const val TAG = "MnnLlmBridge"

        // Last error message for UI display
        @Volatile
        var lastError: String = ""
            private set

        // Track which libraries failed to load
        private val loadErrors = mutableListOf<String>()

        // Load native libraries in dependency order
        // v2.4.29: Must load ALL dependencies of libllm.so, not just the base ones
        init {
            // libMNN.so has no MNN-specific dependencies
            tryLib("MNN")
            // libMNN_Express.so depends on libMNN.so
            tryLib("MNN_Express")
            // libMNN_Vulkan.so depends on libMNN.so
            tryLib("MNN_Vulkan")
            // libMNN_CL.so depends on libMNN.so
            tryLib("MNN_CL")
            // libMNNOpenCV.so depends on libMNN.so + libMNN_Express.so
            tryLib("MNNOpenCV")
            // libMNNAudio.so depends on libMNN.so + libMNN_Express.so
            tryLib("MNNAudio")
            // libmnncore.so depends on libMNN.so + libjnigraphics.so (system)
            tryLib("mnncore")
            // libllm.so depends on ALL of the above
            tryLib("llm")
            // Our JNI bridge - depends on nothing at link time (uses dlopen)
            tryLib("mnn_llm_jni")

            if (loadErrors.isNotEmpty()) {
                Log.e(TAG, "Failed to load libraries: ${loadErrors.joinToString(", ")}")
            }
        }

        private fun tryLib(name: String) {
            try {
                System.loadLibrary(name)
                Log.i(TAG, "Loaded lib$name.so")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load lib$name.so: ${e.message}")
                loadErrors.add(name)
            }
        }

        @JvmStatic
        private external fun nativeInit(): Boolean
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
         * Check if MNN model is installed at the given directory.
         */
        fun isModelInstalled(modelDir: File): Boolean {
            val hasLlmMnn = File(modelDir, "llm.mnn").let { it.exists() && it.length() > 1_000_000 }
            val hasWeight = File(modelDir, "llm.mnn.weight").let { it.exists() && it.length() > 100_000_000 }
            val hasConfig = File(modelDir, "config.json").exists() || File(modelDir, "llm.mnn.json").exists()
            val installed = hasLlmMnn && hasWeight && hasConfig
            if (!installed) {
                val missing = mutableListOf<String>()
                if (!hasLlmMnn) missing.add("llm.mnn")
                if (!hasWeight) missing.add("llm.mnn.weight(>100MB)")
                if (!hasConfig) missing.add("config.json/llm.mnn.json")
                lastError = "模型文件缺失: ${missing.joinToString(", ")}"
                Log.e(TAG, "isModelInstalled: FALSE - missing: ${missing.joinToString(", ")}")
            }
            return installed
        }

        /**
         * Initialize the MNN-LLM engine.
         * @param modelDir Directory containing llm.mnn, llm.mnn.weight, config.json etc.
         * @return true if initialization succeeded
         */
        fun init(modelDir: File): Boolean {
            lastError = ""

            // List all files in model dir for debugging
            val files = modelDir.listFiles()?.map { "${it.name}(${it.length()})" } ?: emptyList()
            Log.i(TAG, "init: modelDir=${modelDir.absolutePath}, files=$files")

            if (!initialized) {
                // v2.4.29: Check if all native libraries were loaded
                if (loadErrors.isNotEmpty()) {
                    lastError = "原生库加载失败: ${loadErrors.joinToString(", ")}"
                    Log.e(TAG, "init: cannot proceed, libraries failed to load: $loadErrors")
                    return false
                }
                Log.i(TAG, "init: calling nativeInit()...")
                initialized = nativeInit()
                if (!initialized) {
                    lastError = "nativeInit失败: dlopen(libllm.so)失败，请查看native.log"
                    Log.e(TAG, "init: nativeInit FAILED - check native.log for dlopen error details")
                    return false
                }
                Log.i(TAG, "init: nativeInit OK")
            }

            // Use config file from model directory - check llm.mnn.json first, then config.json
            val configFile = File(modelDir, "llm.mnn.json").takeIf { it.exists() }
                ?: File(modelDir, "config.json").takeIf { it.exists() }
            val configPath = configFile?.absolutePath ?: (modelDir.absolutePath + "/")

            Log.i(TAG, "init: creating LLM with config: $configPath")
            llmPtr = nativeCreateLlm(configPath)
            if (llmPtr == 0L) {
                lastError = "createLLM失败: 无法创建LLM实例(config=$configPath)"
                Log.e(TAG, "init: nativeCreateLlm returned 0 for config: $configPath")
                return false
            }
            Log.i(TAG, "init: nativeCreateLlm OK, ptr=$llmPtr")

            Log.i(TAG, "init: loading model...")
            val loaded = nativeLoad(llmPtr)
            if (!loaded) {
                lastError = "模型加载失败: load()返回false(模型文件可能损坏或不完整)"
                Log.e(TAG, "init: nativeLoad FAILED")
                nativeFree(llmPtr)
                llmPtr = 0L
                return false
            }

            Log.i(TAG, "init: MNN LLM ready!")
            return true
        }

        /**
         * Generate text from a prompt.
         * @param prompt Input text
         * @param maxTokens Maximum tokens to generate (0 = unlimited)
         * @return Generated text
         */
        fun generate(prompt: String, maxTokens: Int = 2000): String {
            if (llmPtr == 0L) {
                Log.e(TAG, "generate: LLM not initialized")
                return ""
            }
            return nativeGenerate(llmPtr, prompt, maxTokens)
        }

        /**
         * Free the LLM instance.
         */
        fun release() {
            if (llmPtr != 0L) {
                nativeFree(llmPtr)
                llmPtr = 0L
            }
        }

        /**
         * Classify subtitle segments as dry(content) or water(filler) using MNN-LLM.
         * Processes subtitles in batches to show progress and avoid blocking too long.
         * @param onProgress callback(current, total) for progress updates
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

            // Group subtitles into segments
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

            // v2.4.30: Process in batches of 3 segments to show progress and avoid blocking
            val batchSize = 3
            val allResults = mutableListOf<MnnSegmentResult>()
            val numBatches = (groups.size + batchSize - 1) / batchSize

            Log.i(TAG, "classifySubtitles: ${groups.size} segments in $numBatches batches")

            for (batchIdx in 0 until numBatches) {
                val batchStart = batchIdx * batchSize
                val batchEnd = minOf(batchStart + batchSize, groups.size)
                val batch = groups.subList(batchStart, batchEnd)

                onProgress?.invoke(batchIdx, numBatches)
                Log.i(TAG, "classifySubtitles: batch ${batchIdx + 1}/$numBatches, segments ${batchStart + 1}-$batchEnd")

                val prompt = buildString {
                    append("你是广播电台内容分析专家。以下是广播节目字幕，按时间段分组。\n")
                    append("请分析每段内容，判断是「干货」（新闻、资讯、访谈、评论）还是「水货」（广告、音乐、片头片尾、闲聊）。\n")
                    append("只返回JSON数组，格式：[{\"index\":1,\"type\":\"干货\",\"reason\":\"新闻资讯\"}]\n\n")
                    append("字幕内容：\n")
                    for ((i, group) in batch.withIndex()) {
                        val startTime = group.first / 1000
                        val endTime = group.second / 1000
                        val text = if (group.third.length > 500) group.third.substring(0, 500) + "..." else group.third
                        append("[段落${i + 1}] ${startTime}s-${endTime}s: $text\n")
                    }
                }

                val response = generate(prompt, 500)
                if (response.isNotBlank()) {
                    Log.i(TAG, "classifySubtitles: batch ${batchIdx + 1} response length=${response.length}")
                    val batchResults = parseClassification(response, batch)
                    // Adjust indices: batch results are relative to batch, need to map to global groups
                    for ((i, result) in batchResults.withIndex()) {
                        if (i + batchStart < groups.size) {
                            val group = groups[i + batchStart]
                            allResults.add(MnnSegmentResult(
                                start = group.first,
                                end = group.second,
                                isDry = result.isDry,
                                label = result.label
                            ))
                        }
                    }
                } else {
                    Log.w(TAG, "classifySubtitles: batch ${batchIdx + 1} returned empty response")
                }
            }

            onProgress?.invoke(numBatches, numBatches)
            Log.i(TAG, "classifySubtitles: total results=${allResults.size}")
            return allResults
        }

        data class MnnSegmentResult(
            val start: Long,
            val end: Long,
            val isDry: Boolean,
            val label: String
        )

        private fun parseClassification(
            response: String,
            groups: List<Triple<Long, Long, String>>
        ): List<MnnSegmentResult> {
            val results = mutableListOf<MnnSegmentResult>()
            try {
                val jsonStart = response.indexOf('[')
                val jsonEnd = response.lastIndexOf(']')
                if (jsonStart < 0 || jsonEnd < 0) {
                    Log.e(TAG, "parseClassification: no JSON array in response: ${response.take(200)}")
                    return emptyList()
                }

                val jsonStr = response.substring(jsonStart, jsonEnd + 1)
                val jsonArray = org.json.JSONArray(jsonStr)

                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val index = item.optInt("index", i + 1) - 1
                    val type = item.optString("type", "")
                    val isDry = type.contains("干货")

                    if (index in groups.indices) {
                        val group = groups[index]
                        results.add(MnnSegmentResult(
                            start = group.first,
                            end = group.second,
                            isDry = isDry,
                            label = if (isDry) "干货" else "水货"
                        ))
                    }
                }

                Log.i(TAG, "parseClassification: ${results.size} results (${results.count { it.isDry }} dry, ${results.count { !it.isDry }} water)")
            } catch (e: Exception) {
                Log.e(TAG, "parseClassification: failed: ${e.message}")
            }
            return results
        }
    }
}
