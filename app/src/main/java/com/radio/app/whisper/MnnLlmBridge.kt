package com.radio.app.whisper

import android.util.Log
import java.io.File

/**
 * v2.4.27 Bridge for MNN-LLM offline model inference.
 * Uses JNI to call MNN Llm API via dlopen/dlsym.
 * Requires libMNN.so, libllm.so, libMNN_Express.so, libc++_shared.so in jniLibs.
 */
class MnnLlmBridge {
    companion object {
        private const val TAG = "MnnLlmBridge"

        // Load native libraries in dependency order
        init {
            try {
                System.loadLibrary("MNN")
                Log.i(TAG, "Loaded libMNN.so")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libMNN.so: ${e.message}")
            }
            try {
                System.loadLibrary("MNN_Express")
                Log.i(TAG, "Loaded libMNN_Express.so")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libMNN_Express.so: ${e.message}")
            }
            try {
                System.loadLibrary("llm")
                Log.i(TAG, "Loaded libllm.so")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libllm.so: ${e.message}")
            }
            try {
                System.loadLibrary("mnn_llm_jni")
                Log.i(TAG, "Loaded libmnn_llm_jni.so")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libmnn_llm_jni.so: ${e.message}")
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
            val hasLlmMnn = File(modelDir, "llm.mnn").exists()
            val hasWeight = File(modelDir, "llm.mnn.weight").let { it.exists() && it.length() > 100_000_000 }
            val hasConfig = File(modelDir, "config.json").exists() || File(modelDir, "llm.mnn.json").exists()
            return hasLlmMnn && hasWeight && hasConfig
        }

        /**
         * Initialize the MNN-LLM engine.
         * @param modelDir Directory containing llm.mnn, llm.mnn.weight, config.json etc.
         * @return true if initialization succeeded
         */
        fun init(modelDir: File): Boolean {
            if (!initialized) {
                initialized = nativeInit()
                if (!initialized) {
                    Log.e(TAG, "nativeInit failed")
                    return false
                }
            }

            // Use config file from model directory - check llm.mnn.json first, then config.json
            val configFile = File(modelDir, "llm.mnn.json").takeIf { it.exists() }
                ?: File(modelDir, "config.json").takeIf { it.exists() }
            val configPath = configFile?.absolutePath ?: (modelDir.absolutePath + "/")

            Log.i(TAG, "Creating LLM with config: $configPath")
            llmPtr = nativeCreateLlm(configPath)
            if (llmPtr == 0L) {
                Log.e(TAG, "nativeCreateLlm returned 0")
                return false
            }

            Log.i(TAG, "Loading model...")
            val loaded = nativeLoad(llmPtr)
            if (!loaded) {
                Log.e(TAG, "nativeLoad failed")
                nativeFree(llmPtr)
                llmPtr = 0L
                return false
            }

            Log.i(TAG, "MNN LLM ready!")
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
                Log.e(TAG, "LLM not initialized")
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
         * Feeds all subtitles to the model and asks it to classify each segment.
         */
        fun classifySubtitles(
            subtitles: List<Triple<Long, Long, String>>,
            segmentDurationMs: Long = 3 * 60 * 1000L
        ): List<MnnSegmentResult>? {
            if (llmPtr == 0L) {
                Log.e(TAG, "LLM not initialized for classifySubtitles")
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

            // Build prompt
            val prompt = buildString {
                append("你是广播电台内容分析专家。以下是广播节目字幕，按时间段分组。\n")
                append("请分析每段内容，判断是「干货」（新闻、资讯、访谈、评论）还是「水货」（广告、音乐、片头片尾、闲聊）。\n")
                append("只返回JSON数组，格式：[{\"index\":1,\"type\":\"干货\",\"reason\":\"新闻资讯\"}]\n\n")
                append("字幕内容：\n")
                for ((i, group) in groups.withIndex()) {
                    val startTime = group.first / 1000
                    val endTime = group.second / 1000
                    val text = if (group.third.length > 500) group.third.substring(0, 500) + "..." else group.third
                    append("[段落${i + 1}] ${startTime}s-${endTime}s: $text\n")
                }
            }

            Log.i(TAG, "Sending ${groups.size} segments to MNN-LLM, prompt length=${prompt.length}")

            val response = generate(prompt, 2000)
            if (response.isBlank()) {
                Log.e(TAG, "Empty response from MNN-LLM")
                return null
            }

            Log.i(TAG, "MNN-LLM response length=${response.length}")

            // Parse JSON array from response
            return parseClassification(response, groups)
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
                    Log.e(TAG, "No JSON array in response: ${response.take(200)}")
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

                Log.i(TAG, "Parsed ${results.size} results (${results.count { it.isDry }} dry, ${results.count { !it.isDry }} water)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse response: ${e.message}")
            }
            return results
        }
    }
}
