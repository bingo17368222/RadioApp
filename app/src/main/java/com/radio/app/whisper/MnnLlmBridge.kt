package com.radio.app.whisper

import android.content.Context
import android.util.Log
import java.io.File

/**
 * v2.4.86 Bridge for MNN-LLM offline model inference.
 * MNN .so files are downloaded with the model (NOT in APK).
 * v2.4.35: .so files are copied to internal storage before dlopen,
 * because Android 7+ blocks dlopen from external storage.
 *
 * v2.4.86 fixes:
 *   1. Call set_config AFTER load() so use_template=true actually takes effect
 *   2. Native code tries JSON messages array first (cleanest approach)
 *   3. Native code reorders symbol resolution to try const char* first (prevents ABI mismatch)
 *   4. Removed keyword classification fallback - MNN should work correctly now
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
        private external fun nativeReset(ptr: Long)
        @JvmStatic
        private external fun nativeFree(ptr: Long)
        @JvmStatic
        private external fun nativeSetConfig(ptr: Long, configJson: String): Boolean
        @JvmStatic
        private external fun nativeSetConfigPostLoad(ptr: Long): Boolean  // v2.4.86: set config AFTER load
        @JvmStatic
        private external fun nativeGetCompileMarker(): String
        @JvmStatic
        private external fun nativeTestApplyChatTemplate(ptr: Long, userInput: String): String
        @JvmStatic
        private external fun nativeTestJsonTemplate(ptr: Long): String  // v2.4.86: test JSON messages

        private var initialized = false
        private var llmPtr: Long = 0L

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
         */
        fun init(modelDir: File, context: Context? = null): Boolean {
            lastError = ""

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
                    try {
                        log.write("[${System.currentTimeMillis()}] $msg\n")
                        log.flush()
                    } catch (_: Exception) {
                    }
                }

                val files = modelDir.listFiles()?.map { "${it.name}(${it.length()})" } ?: emptyList()
                mnnLog("init: [v2.4.86] modelDir=${modelDir.absolutePath}, files=$files")

                val extLibsDir = File(modelDir.parentFile, "mnn-libs")
                mnnLog("init: extLibsDir=${extLibsDir.absolutePath}, exists=${extLibsDir.exists()}")
                val libFiles = extLibsDir.listFiles()?.map { "${it.name}(${it.length()})" } ?: emptyList()
                mnnLog("init: extLibFiles=$libFiles")

                if (!initialized) {
                    mnnLog("init: using bundled .so files, calling nativeInit('')...")
                    initialized = nativeInit("")
                    mnnLog("init: nativeInit('') returned $initialized")

                    if (!initialized) {
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

                    // Version check - force process restart if old .so is cached
                    val expectedMarker = "MNN_JNI_v2.4.86"
                    try {
                        val actualMarker = nativeGetCompileMarker()
                        mnnLog("init: compile marker check: expected=$expectedMarker, actual=$actualMarker")
                        if (actualMarker != expectedMarker) {
                            mnnLog("init: OLD .so DETECTED! Force-killing :subtitle process.")
                            lastError = "检测到旧版MNN运行库，正在强制重启进程以加载新版本。请重新点击AI分段。"
                            log.close()
                            android.os.Process.killProcess(android.os.Process.myPid())
                            return false
                        }
                    } catch (e: Exception) {
                        mnnLog("init: nativeGetCompileMarker not available, assuming old version")
                        lastError = "检测到旧版MNN运行库，正在强制重启进程。请重新点击AI分段。"
                        log.close()
                        android.os.Process.killProcess(android.os.Process.myPid())
                        return false
                    }
                }

                // Inject jinja.chat_template into llm_config.json so the tokenizer
                // knows how to format ChatML and recognizes special tokens
                val llmConfigFile = File(modelDir, "llm_config.json")
                if (llmConfigFile.exists()) {
                    try {
                        val rawJson = llmConfigFile.readText()
                        val json = org.json.JSONObject(rawJson)
                        if (!json.has("jinja")) {
                            val jinjaObj = org.json.JSONObject()
                            jinjaObj.put("chat_template", "{%- for message in messages -%}{{ '<|im_start|>' + message.role + '\\n' + message.content + '<|im_end|>' + '\\n' }}{%- endfor -%}{{ '<|im_start|>assistant\\n' }}")
                            json.put("jinja", jinjaObj)
                            llmConfigFile.writeText(json.toString())
                            mnnLog("init: Injected jinja.chat_template into llm_config.json")
                        } else {
                            val jinjaObj = json.getJSONObject("jinja")
                            if (!jinjaObj.has("chat_template")) {
                                jinjaObj.put("chat_template", "{%- for message in messages -%}{{ '<|im_start|>' + message.role + '\\n' + message.content + '<|im_end|>' + '\\n' }}{%- endfor -%}{{ '<|im_start|>assistant\\n' }}")
                                json.put("jinja", jinjaObj)
                                llmConfigFile.writeText(json.toString())
                                mnnLog("init: Added chat_template to existing jinja object")
                            } else {
                                mnnLog("init: jinja.chat_template already exists")
                            }
                        }
                    } catch (e: Exception) {
                        mnnLog("init: Failed to inject jinja.chat_template: ${e.message}")
                    }
                } else {
                    mnnLog("init: llm_config.json not found")
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
                mnnLog("init: nativeLoad OK")

                // v2.4.86: CRITICAL - Call set_config AFTER load()!
                // Previously this was called before load(), which MNN ignores
                // because load() reads config from the file passed to createLlm.
                // Setting use_template=true after load ensures MNN applies the
                // Jinja chat template when response(string) is called.
                try {
                    val configOk = nativeSetConfigPostLoad(llmPtr)
                    mnnLog("init: [v2.4.86] nativeSetConfigPostLoad returned $configOk (use_template=true, temperature=0.1)")
                } catch (e: Exception) {
                    mnnLog("init: nativeSetConfigPostLoad FAILED: ${e.message}")
                }

                mnnLog("init: MNN LLM ready!")

                // v2.4.86: Diagnostics - test both plain text and JSON messages template
                try {
                    val plainResult = nativeTestApplyChatTemplate(llmPtr, "你好")
                    mnnLog("init: DIAGNOSTIC apply_chat_template('你好') = '${plainResult.take(300)}'")
                } catch (e: Exception) {
                    mnnLog("init: DIAGNOSTIC apply_chat_template failed: ${e.message}")
                }
                try {
                    val jsonResult = nativeTestJsonTemplate(llmPtr)
                    mnnLog("init: DIAGNOSTIC apply_chat_template(JSON messages) = '${jsonResult.take(300)}'")
                } catch (e: Exception) {
                    mnnLog("init: DIAGNOSTIC JSON template failed: ${e.message}")
                }

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
            try {
                nativeReset(llmPtr)
            } catch (e: Exception) {
                mnnLog("generate: nativeReset failed: ${e.message}")
            }
            mnnLog("generate: calling nativeGenerate with prompt len=${prompt.length}, maxTokens=$maxTokens")
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
         * v2.4.86: Removed keyword classification fallback. The MNN fixes should
         * make the model produce correct "干货"/"水货" answers.
         */
        fun classifySubtitles(
            subtitles: List<Triple<Long, Long, String>>,
            segmentDurationMs: Long = 3 * 60 * 1000L,
            onProgress: ((Int, Int, String) -> Unit)? = null
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

            val allResults = mutableListOf<MnnSegmentResult>()
            Log.i(TAG, "classifySubtitles: [v2.4.86] ${groups.size} segments, batch=1")

            val classifyLogFile = java.io.File("/storage/emulated/0/RadioApp/logs/subtitle/mnn_classify.log")
            try { classifyLogFile.parentFile?.mkdirs() } catch (_: Exception) {}
            val classifyLog = try {
                java.io.FileWriter(classifyLogFile, true)
            } catch (_: Exception) { null }
            try {
                classifyLog?.write("[${System.currentTimeMillis()}] classifySubtitles: [v2.4.86] START, ${groups.size} segments\n")
            } catch (_: Exception) {}

            var garbageCount = 0

            for ((idx, group) in groups.withIndex()) {
                val startTime = group.first / 1000
                val endTime = group.second / 1000
                val text = if (group.third.length > 280) group.third.substring(0, 280) else group.third

                // v2.4.86: Simpler prompt - native code now adds system prompt via JSON messages
                val prompt = "判断以下广播内容(${startTime}s-${endTime}s)是干货还是水货，只回答干货或水货：\n$text"

                val response = generate(prompt, 50).trim()
                onProgress?.invoke(idx, groups.size, response)
                Log.i(TAG, "classifySubtitles: seg ${idx + 1}/${groups.size} response='${response.take(80)}' textLen=${text.length}")
                try {
                    classifyLog?.write("[${System.currentTimeMillis()}] seg ${idx+1}/${groups.size}: textLen=${text.length}, text='${text.take(80)}', response='${response.take(80)}'\n")
                    classifyLog?.flush()
                } catch (_: Exception) {}

                if (response.isNotBlank()) {
                    val cleanedResponse = response.replace(Regex("[\\s\\p{Punct}]"), "")
                    val isDry = cleanedResponse.contains("干货")
                    val isWater = cleanedResponse.contains("水货")
                    val uniqueChars = response.take(50).toSet().size
                    val hasNonCJK = response.length > 10 && response.any { c ->
                        val code = c.code
                        (code in 0x0400..0x04FF) ||
                        (code in 0x3040..0x30FF) ||
                        (code in 0x41..0x7A && response.length > 20)
                    }
                    val isGarbage = when {
                        uniqueChars <= 4 && response.length > 10 -> true
                        hasNonCJK -> true
                        response.length > 30 && !isDry && !isWater -> true
                        else -> false
                    }
                    if (isGarbage) garbageCount++

                    val finalIsDry = when {
                        isGarbage -> {
                            mnnLog("classifySubtitles: seg ${idx+1}/${groups.size} GARBAGE (unique=$uniqueChars, len=${response.length}), defaulting to 干货, resp=${response.take(60)}")
                            true
                        }
                        isDry && isWater -> {
                            val dryPos = cleanedResponse.indexOf("干货")
                            val waterPos = cleanedResponse.indexOf("水货")
                            val firstIsDry = dryPos >= 0 && (waterPos < 0 || dryPos < waterPos)
                            mnnLog("classifySubtitles: seg ${idx+1}/${groups.size} both keywords, first=${if (firstIsDry) "干货" else "水货"}")
                            firstIsDry
                        }
                        isWater -> false
                        isDry -> true
                        else -> {
                            mnnLog("classifySubtitles: seg ${idx+1}/${groups.size} no keyword, defaulting to 干货, resp=${response.take(60)}")
                            true
                        }
                    }
                    allResults.add(MnnSegmentResult(
                        start = group.first,
                        end = group.second,
                        isDry = finalIsDry,
                        label = if (isGarbage) "GARBAGE" else if (finalIsDry) "干货" else "水货"
                    ))
                    Log.i(TAG, "classifySubtitles: seg ${idx + 1}/${groups.size} -> ${if (finalIsDry) "干货" else "水货"} (resp=${response.take(30)})")
                } else {
                    garbageCount++
                    allResults.add(MnnSegmentResult(
                        start = group.first,
                        end = group.second,
                        isDry = true,
                        label = "GARBAGE"
                    ))
                    Log.w(TAG, "classifySubtitles: seg ${idx + 1}/${groups.size} -> empty, defaulting to 干货")
                }
            }

            onProgress?.invoke(groups.size, groups.size, "")
            Log.i(TAG, "classifySubtitles: total results=${allResults.size}, garbage=$garbageCount/${allResults.size}")

            // v2.4.86: If ALL responses were garbage, log it but still return results
            // (defaulting to 干货). User requested no keyword classification fallback.
            if (garbageCount == allResults.size && allResults.size > 0) {
                mnnLog("classifySubtitles: WARNING - ALL ${allResults.size} responses were GARBAGE. MNN model may need attention.")
                try {
                    classifyLog?.write("[${System.currentTimeMillis()}] classifySubtitles: WARNING - ALL RESPONSES GARBAGE (MNN issue)\n")
                } catch (_: Exception) {}
            }

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
            val label: String,
            val wasGarbage: Boolean = false
        )
    }
}
