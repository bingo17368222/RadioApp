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
        private external fun nativeReset(ptr: Long)  // v2.4.69: Reset KV cache and position_id
        @JvmStatic
        private external fun nativeFree(ptr: Long)
        @JvmStatic
        private external fun nativeSetConfig(ptr: Long, configJson: String): Boolean
        @JvmStatic
        private external fun nativeGetCompileMarker(): String

        private var initialized = false
        private var llmPtr: Long = 0L

        // v2.4.57: Moved mnnLog to companion object level so generate() can use it
        private fun mnnLog(msg: String) {
            Log.i(TAG, msg)
            try {
                val logDir = java.io.File(android.os.Environment.getExternalStorageDirectory(), "Android/data/com.radio.app/files/logs/subtitle")
                if (!logDir.exists()) logDir.mkdirs()
                val logFile = java.io.File(logDir, "mnn_init.log")
                java.io.FileWriter(logFile, true).use { it.append("[${System.currentTimeMillis()}] $msg\n") }
            } catch (_: Exception) {}
        }

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
                // v2.4.40: Make mnnLog exception-safe so it doesn't propagate IOException
                // and cause init() to fail. The "Stream closed" exception was happening
                // on the first init() call, causing MNN to fail on first attempt.
                fun mnnLog(msg: String) {
                    Log.i(TAG, msg)
                    try {
                        log.write("[${System.currentTimeMillis()}] $msg\n")
                        log.flush()
                    } catch (_: Exception) {
                        // Ignore log write errors
                    }
                }

                val files = modelDir.listFiles()?.map { "${it.name}(${it.length()})" } ?: emptyList()
                mnnLog("init: modelDir=${modelDir.absolutePath}, files=$files")

                // v2.4.31: Get the libs directory (sibling of model dir) - on external storage
                val extLibsDir = File(modelDir.parentFile, "mnn-libs")
                mnnLog("init: extLibsDir=${extLibsDir.absolutePath}, exists=${extLibsDir.exists()}")
                val libFiles = extLibsDir.listFiles()?.map { "${it.name}(${it.length()})" } ?: emptyList()
                mnnLog("init: extLibFiles=$libFiles")

                if (!initialized) {
                    // v2.4.48: .so files are now bundled in APK jniLibs.
                    // Try nativeInit with empty path first (uses default dlopen search).
                    mnnLog("init: using bundled .so files (v2.4.48), calling nativeInit('')...")
                    initialized = nativeInit("")
                    mnnLog("init: nativeInit('') returned $initialized")

                    if (!initialized) {
                        // Fallback: try loading from internal storage (old method)
                        mnnLog("init: bundled load failed, trying external storage copy...")
                        val internalLibsDir = if (context != null) {
                            File(context.filesDir, "mnn-libs")
                        } else {
                            File("/data/data/com.radio.app/files/mnn-libs")
                        }
                        mnnLog("init: internalLibsDir=${internalLibsDir.absolutePath}")
                        if (!internalLibsDir.exists()) internalLibsDir.mkdirs()

                        if (extLibsDir.exists()) {
                            val requiredLibs = listOf(
                                "libMNN.so", "libMNN_Express.so", "libMNN_Vulkan.so", "libMNN_CL.so",
                                "libMNNOpenCV.so", "libMNNAudio.so", "libmnncore.so", "libllm.so"
                            )
                            for (libName in requiredLibs) {
                                val srcFile = File(extLibsDir, libName)
                                val dstFile = File(internalLibsDir, libName)
                                if (srcFile.exists() && srcFile.length() > 1000) {
                                    if (!dstFile.exists() || dstFile.length() != srcFile.length()) {
                                        srcFile.inputStream().use { input ->
                                            dstFile.outputStream().use { output -> input.copyTo(output) }
                                        }
                                        dstFile.setExecutable(true, false)
                                        mnnLog("init: copied $libName (${dstFile.length()} bytes)")
                                    }
                                }
                            }
                        }

                        mnnLog("init: calling nativeInit(libDir=${internalLibsDir.absolutePath})...")
                        initialized = nativeInit(internalLibsDir.absolutePath)
                        mnnLog("init: nativeInit returned $initialized")
                    }

                    if (!initialized) {
                        lastError = "nativeInit失败: 无法加载MNN运行库"
                        mnnLog("init: FAILED - $lastError")
                        log.close()
                        return false
                    }
                    mnnLog("init: nativeInit OK")

                    // v2.4.58: Verify the loaded .so is the current version.
                    // If the :subtitle process kept an OLD .so in memory (from before APK update),
                    // nativeGetCompileMarker() will return the OLD marker string.
                    // We compare it against the expected marker and force-kill the process
                    // so the next init attempt loads the fresh .so.
                    val expectedMarker = "MNN_JNI_v2.4.71"
                    try {
                        val actualMarker = nativeGetCompileMarker()
                        mnnLog("init: compile marker check: expected=$expectedMarker, actual=$actualMarker")
                        if (actualMarker != expectedMarker) {
                            mnnLog("init: OLD .so DETECTED (marker=$actualMarker, expected=$expectedMarker)! Force-killing :subtitle process to load fresh .so on next init.")
                            lastError = "检测到旧版MNN运行库(已加载)，正在强制重启进程以加载新版本。请重新点击AI分段。"
                            log.close()
                            // Kill this process so next init loads the new .so
                            android.os.Process.killProcess(android.os.Process.myPid())
                            return false
                        }
                    } catch (e: Exception) {
                        mnnLog("init: nativeGetCompileMarker not available (old .so without this function), assuming old version")
                        // If nativeGetCompileMarker doesn't exist, it's definitely an old .so
                        lastError = "检测到旧版MNN运行库(无版本标记)，正在强制重启进程。请重新点击AI分段。"
                        log.close()
                        android.os.Process.killProcess(android.os.Process.myPid())
                        return false
                    }
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

                // v2.4.71: CRITICAL FIX - Set use_template=false + manual ChatML formatting in JNI.
                // ROOT CAUSE of persistent garbage output ("慰慰慰"):
                //   v2.4.70 injected jinja.chat_template via set_config, but MNN's
                //   response(string) → apply_chat_template(string, system_prompt) → tokenizer's
                //   apply_chat_template(string) does NOT properly render the Jinja2 template for
                //   string inputs. The Jinja2 template expects a `messages` array (from ChatMessages),
                //   but the string overload can't construct it correctly → template renders empty →
                //   response(string) falls back to raw text (no ChatML) → Qwen2 outputs garbage.
                //
                // FIX: Set use_template=false so MNN's response(string) does NOT try to apply any
                //   template. Instead, we manually format the prompt as full ChatML in the JNI code
                //   (mnn_llm_jni.cpp nativeGenerate). With use_template=false, response(string)
                //   passes the raw (but already ChatML-formatted) text directly to the tokenizer,
                //   which correctly tokenizes the ChatML special tokens (<|im_start|>, <|im_end|>).
                val genConfig = """{"temperature":0.1,"top_p":0.8,"max_new_tokens":2000,"repetition_penalty":1.3,"use_template":false}"""
                try {
                    val configOk = nativeSetConfig(llmPtr, genConfig)
                    mnnLog("init: [v2.4.71] set_config result=$configOk, use_template=false (manual ChatML in JNI)")
                    mnnLog("init: [v2.4.71] genConfig=$genConfig")
                } catch (e: Exception) {
                    mnnLog("init: set_config FAILED: ${e.message}")
                }
                // v2.4.39: Close log at the very end of successful init
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
            // v2.4.69: CRITICAL FIX - Call reset() before each generate to clear KV cache
            // and reset position_id to 0. Without this, position_id accumulates across calls,
            // causing RoPE position embedding corruption → garbage output ("集结漏", "慰慰慰").
            // The reset() is also called inside nativeGenerate before each response() attempt,
            // but calling it here too ensures the Kotlin-level caller has a clean session.
            try {
                nativeReset(llmPtr)
                mnnLog("generate: [v2.4.69] reset() called (KV cache cleared, position_id=0)")
            } catch (e: Exception) {
                mnnLog("generate: [v2.4.69] nativeReset failed: ${e.message}")
            }
            mnnLog("generate: calling nativeGenerate with raw prompt (len=${prompt.length}), maxTokens=$maxTokens")
            val result = nativeGenerate(llmPtr, prompt, maxTokens)
            mnnLog("generate: response len=${result.length}, first200=${result.take(200)}")
            return result
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

            // v2.4.41: Write classification results to mnn_classify.log for debugging
            val classifyLogFile = java.io.File("/storage/emulated/0/RadioApp/logs/subtitle/mnn_classify.log")
            try { classifyLogFile.parentFile?.mkdirs() } catch (_: Exception) {}
            val classifyLog = try {
                java.io.FileWriter(classifyLogFile, true)
            } catch (_: Exception) { null }
            try {
                classifyLog?.write("[${System.currentTimeMillis()}] classifySubtitles: START, ${groups.size} segments\n")
            } catch (_: Exception) {}

            for ((idx, group) in groups.withIndex()) {
                onProgress?.invoke(idx, groups.size)

                val startTime = group.first / 1000
                val endTime = group.second / 1000
                val text = if (group.third.length > 280) group.third.substring(0, 280) else group.third

                // v2.4.61: Improved prompt - more direct, ask for single word answer only
                val prompt = "判断以下广播内容是「干货」还是「水货」。干货=新闻/资讯/有用信息，水货=广告/音乐/闲聊废话。只回答干货或水货，不要其他内容。\n内容(${startTime}s-${endTime}s): $text"

                val response = generate(prompt, 50).trim()
                // v2.4.40: Log full response for debugging MNN classification
                Log.i(TAG, "classifySubtitles: seg ${idx + 1}/${groups.size} response='${response.take(80)}' textLen=${text.length}")
                // v2.4.41: Also write to file log
                try {
                    classifyLog?.write("[${System.currentTimeMillis()}] seg ${idx+1}/${groups.size}: textLen=${text.length}, text='${text.take(80)}', response='${response.take(80)}'\n")
                    classifyLog?.flush()
                } catch (_: Exception) {}
                if (response.isNotBlank()) {
                    // v2.4.61: Clean response - remove punctuation/whitespace around keywords
                    val cleanedResponse = response.replace(Regex("[\\s\\p{Punct}]"), "")
                    val isDry = cleanedResponse.contains("干货")
                    val isWater = cleanedResponse.contains("水货")
                    // v2.4.70: Enhanced garbage detection - catch multi-language garbage output
                    val uniqueChars = response.take(50).toSet().size
                    // v2.4.70: Check for non-CJK characters (Russian, Japanese, English letters in long responses)
                    val hasNonCJK = response.length > 10 && response.any { c ->
                        val code = c.code
                        // Russian/Cyrillic range
                        (code in 0x0400..0x04FF) ||
                        // Japanese Hiragana/Katakana
                        (code in 0x3040..0x30FF) ||
                        // Latin letters (English) in responses >20 chars (short responses like "干货" are OK)
                        (code in 0x41..0x7A && response.length > 20)
                    }
                    // v2.4.70: Garbage if: few unique chars, OR contains non-CJK chars (multi-language garbage),
                    // OR response >30 chars but doesn't contain either keyword (model is outputting random text)
                    val isGarbage = when {
                        uniqueChars <= 4 && response.length > 10 -> true  // Repetitive garbage (慰慰慰, FFFFFFFF)
                        hasNonCJK -> true  // Multi-language garbage (Russian, Japanese, English mixed in)
                        response.length > 30 && !isDry && !isWater -> true  // Long response with no keywords
                        else -> false
                    }
                    // v2.4.61: If both keywords appear (unlikely), prioritize based on position (first match wins)
                    val finalIsDry = when {
                        isGarbage -> {
                            mnnLog("classifySubtitles: seg ${idx+1}/${groups.size} GARBAGE detected (unique=$uniqueChars, len=${response.length}), defaulting to 干货, response=${response.take(60)}")
                            true
                        }
                        isDry && isWater -> {
                            // Both keywords present - use first occurrence
                            val dryPos = cleanedResponse.indexOf("干货")
                            val waterPos = cleanedResponse.indexOf("水货")
                            val firstIsDry = dryPos >= 0 && (waterPos < 0 || dryPos < waterPos)
                            mnnLog("classifySubtitles: seg ${idx+1}/${groups.size} both keywords, first=${if (firstIsDry) "干货" else "水货"}, response=${response.take(60)}")
                            firstIsDry
                        }
                        isWater -> false
                        isDry -> true
                        else -> {
                            mnnLog("classifySubtitles: seg ${idx+1}/${groups.size} no keyword in response, defaulting to 干货, response=${response.take(60)}")
                            true
                        }
                    }
                    allResults.add(MnnSegmentResult(
                        start = group.first,
                        end = group.second,
                        isDry = finalIsDry,
                        // v2.4.63: Mark garbage responses for global check
                        label = if (isGarbage) "GARBAGE" else if (finalIsDry) "干货" else "水货"
                    ))
                    Log.i(TAG, "classifySubtitles: seg ${idx + 1}/${groups.size} -> ${if (finalIsDry) "干货" else "水货"} (resp=${response.take(30)})")
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
            // v2.4.63: Global garbage check - if ALL responses were garbage (model is broken),
            // override all segments to 干货 since MNN classification is unreliable.
            val garbageCount = allResults.count { it.label == "GARBAGE" }
            if (garbageCount > 0 && garbageCount == allResults.size && allResults.size > 0) {
                mnnLog("classifySubtitles: ALL ${allResults.size} responses were GARBAGE - model appears broken, overriding all to 干货")
                try {
                    classifyLog?.write("[${System.currentTimeMillis()}] classifySubtitles: ALL GARBAGE - overriding all to 干货\n")
                    classifyLog?.flush()  // v2.4.67: Flush before returning
                    classifyLog?.close()
                } catch (_: Exception) {}
                return allResults.map { MnnSegmentResult(it.start, it.end, true, "干货") }
            }
            // v2.4.41: Close classify log
            try {
                classifyLog?.write("[${System.currentTimeMillis()}] classifySubtitles: END, ${allResults.size} results, dry=${allResults.count{it.isDry}}, water=${allResults.count{!it.isDry}}, garbage=$garbageCount\n")
                classifyLog?.close()
            } catch (_: Exception) {}
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
