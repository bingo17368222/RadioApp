package com.radio.app.activities

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.radio.app.R
import com.radio.app.utils.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class OfflineEngineActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OfflineEngineActivity"
        private const val MIN_INSTALL_SIZE = 1024L * 1024L // 1MB，低于此大小不认为已安装
    }

    private var tvTitle: TextView? = null
    private var btnBack: ImageButton? = null

    // 当前下载任务状态（用于取消下载支持）
    private var downloadJob: Job? = null
    private var activeConnection: HttpURLConnection? = null
    private var activeTempFile: File? = null
    private var activeBtn: Button? = null
    private var activeProgressBar: ProgressBar? = null
    private var activeEngine: EngineInfo? = null
    private var activeModelDir: File? = null
    @Volatile
    private var downloadCancelled = false

    // Issue 12: Vosk 引擎卡片的 UI 引用，供模型安装完成后自动触发 libvosk.so 安装使用
    private var voskEngineBtn: Button? = null
    private var voskEngineProgressBar: ProgressBar? = null
    private var voskEngineModelDir: File? = null

    // Issue 9: Whisper 引擎卡片的 UI 引用，供模型安装完成后自动触发 libwhisper.so 安装使用
    private var whisperEngineBtn: Button? = null
    private var whisperEngineProgressBar: ProgressBar? = null
    private var whisperEngineModelDir: File? = null

    private data class EngineInfo(
        val name: String,
        val desc: String,
        val size: String,
        val downloadUrl: String?,
        val modelDir: String,
        val unavailable: Boolean = false,
        val multiFileUrls: List<String>? = null  // [v2.4.21] For multi-file downloads (e.g. MNN model)
    )

    private val engines = arrayOf(
        // ===== Whisper 原生引擎 (4个.so文件，现已提供下载) =====
        EngineInfo(
            "Whisper 引擎文件",
            "Whisper原生库(4个.so文件)\n包含: libggml-base-whisper.so, libggml-cpu-whisper.so, libggml-whisper.so, libwhisper.so\n大小: 约5MB | 必需组件\n状态: 支持下载\n说明: 下载后自动解压安装，运行时按依赖顺序加载4个.so文件",
            "约5MB",
            "https://github.com/bingo17368222/RadioApp/releases/download/v2.0.34/whisper-engine.zip",
            "whisper-engine"
        ),

        // ===== Whisper 语音识别模型（hf-mirror.com 国内镜像源） =====
        EngineInfo(
            "Whisper Tiny",
            "OpenAI Whisper tiny模型\n大小: 约75MB | 识别率: ~85% | 速度: 极快(实时)\n适用: 快速字幕生成，对准确率要求不高的场景\n支持: 中文、英文及99种语言",
            "约75MB",
            "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
            "whisper-tiny"
        ),
        EngineInfo(
            "Whisper Base",
            "OpenAI Whisper base模型\n大小: 约142MB | 识别率: ~90% | 速度: 快(近实时)\n适用: 日常字幕生成，准确率和速度平衡\n支持: 中文、英文及99种语言",
            "约142MB",
            "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
            "whisper-base"
        ),
        EngineInfo(
            "Whisper Small",
            "OpenAI Whisper small模型\n大小: 约466MB | 识别率: ~93% | 速度: 中等(约2x实时)\n适用: 高质量字幕，推荐日常使用\n支持: 中文、英文及99种语言",
            "约466MB",
            "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
            "whisper-small"
        ),
        EngineInfo(
            "Whisper Medium",
            "OpenAI Whisper medium模型\n大小: 约1.5GB | 识别率: ~95% | 速度: 较慢(约4x实时)\n适用: 专业级字幕，对准确率要求极高\n支持: 中文、英文及99种语言",
            "约1.5GB",
            "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/ggml-medium.bin",
            "whisper-medium"
        ),
        EngineInfo(
            "Whisper Large-v3",
            "OpenAI Whisper large-v3模型\n大小: 约2.9GB | 识别率: ~97% | 速度: 慢(约8x实时)\n适用: 最高精度，专业转录场景\n支持: 中文、英文及99种语言",
            "约2.9GB",
            "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/ggml-large-v3.bin",
            "whisper-large"
        ),

        // ===== 阿里 MNN-LLM 离线引擎 =====
        // v2.4.89: Switched to Qwen2.5-Coder-1.5B-Instruct-MNN (updated May 2026)
        // because old Qwen2-1.5B-Instruct-MNN (Jan 2025) is incompatible with MNN 3.6.0 libllm.so.
        // New model uses tokenizer.mtok (binary format, MNN 3.5.0+) and has full jinja chat_template.
        // v2.4.64: Changed from Qwen1.5-1.8B-Chat-MNN to Qwen2-1.5B-Instruct-MNN
        // v2.4.65: Use official MNN org's Qwen2-1.5B-Instruct-MNN
        EngineInfo(
            "阿里 MNN-LLM (Qwen2.5-Coder-1.5B)",
            "阿里巴巴 MNN 推理引擎\n模型: Qwen2.5-Coder-1.5B-Instruct-MNN (4bit量化)\n大小: 约970MB | 来源: ModelScope官方\n状态: 支持下载\n适用: AI分段模型，区分干货片段和水货片段\n说明: 2026年5月更新版本，兼容MNN 3.6.0运行库\nv2.4.89: 更换为Qwen2.5-Coder，解决模型版本不兼容问题",
            "约970MB",
            null,  // 使用 multiFileUrls 多文件下载
            "mnn-llm/Qwen2.5-Coder-1.5B-Instruct-MNN",
            unavailable = false,
            multiFileUrls = listOf(
                "https://www.modelscope.cn/models/MNN/Qwen2.5-Coder-1.5B-Instruct-MNN/resolve/master/llm.mnn",
                "https://www.modelscope.cn/models/MNN/Qwen2.5-Coder-1.5B-Instruct-MNN/resolve/master/llm.mnn.weight",
                "https://www.modelscope.cn/models/MNN/Qwen2.5-Coder-1.5B-Instruct-MNN/resolve/master/llm.mnn.json",
                "https://www.modelscope.cn/models/MNN/Qwen2.5-Coder-1.5B-Instruct-MNN/resolve/master/tokenizer.mtok",
                "https://www.modelscope.cn/models/MNN/Qwen2.5-Coder-1.5B-Instruct-MNN/resolve/master/config.json",
                "https://www.modelscope.cn/models/MNN/Qwen2.5-Coder-1.5B-Instruct-MNN/resolve/master/llm_config.json"
            )
        ),

        // ===== Vosk 原生引擎 (libvosk.so，从 Maven AAR 中提取) =====
        EngineInfo(
            "Vosk 引擎 (libvosk.so)",
            "Vosk原生库文件\n大小: 约40MB | 必需组件\n状态: Vosk字幕生成必需\n说明: 下载后自动解压安装libvosk.so",
            "约40MB",
            "https://repo1.maven.org/maven2/com/alphacephei/vosk-android/0.3.47/vosk-android-0.3.47.aar",
            "vosk-engine"
        ),

        // ===== Vosk 离线语音识别（APK内置 + 可下载 | 国内镜像加速） =====
        EngineInfo(
            "Vosk 小模型 (中文)",
            "Vosk small-cn 中文模型\n大小: 约42MB | 识别率: ~88% | 速度: 快(实时)\n状态: 支持下载\n适用: 中文语音识别，低资源消耗",
            "约42MB",
            "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip",
            "vosk-model-small-cn-0.22"
        ),
        EngineInfo(
            "Vosk 大模型 (中文)",
            "Vosk cn 中文大模型\n大小: 约1.3GB | 识别率: ~95% | 速度: 中等\n适用: 高精度中文语音识别",
            "约1.3GB",
            "https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip",
            "vosk-model-cn-0.22"
        ),
        EngineInfo(
            "Vosk 小模型 (英文)",
            "Vosk small-en 英文模型\n大小: 约42MB | 识别率: ~90% | 速度: 快(实时)\n适用: 英文语音识别，低资源消耗",
            "约42MB",
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            "vosk-model-small-en-us-0.15"
        ),
        EngineInfo(
            "Vosk 中模型 (英文)",
            "Vosk en-us-0.22-lgraph 英文中模型(动态图)\n大小: 约128MB | 识别率: ~92% | 速度: 中等\n适用: 高精度英文语音识别",
            "约128MB",
            "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip",
            "vosk-model-en-us-0.22-lgraph"
        ),
        EngineInfo(
            "Vosk 大模型 (英文)",
            "Vosk large-en 英文大模型\n大小: 约1.8GB | 识别率: ~96% | 速度: 较慢\n适用: 最高精度英文语音识别",
            "约1.8GB",
            "https://ghfast.top/https://github.com/alphacep/vosk-space/releases/download/v0.3.47/vosk-model-en-us-daanzu-0.22.zip",
            "vosk-model-en-us-daanzu-0.22"
        ),

        // ===== v2.4.95: 音频分段模型 + 运行库（Silero VAD + YAMNet 双模型） =====
        EngineInfo(
            "音频分段运行库",
            "ONNX Runtime + TFLite 原生库\n大小: 约7MB(压缩) | 用途: 音频分段AI运行时库\n状态: 支持下载\n包含: libonnxruntime.so, libtensorflowlite_jni.so等\n必需: 使用音频分段(VAD+YAMNet)方案前必须下载安装",
            "约7MB",
            "https://ghfast.top/https://github.com/bingo17368222/RadioApp/releases/download/v2.4.94-audio-models/audio-segmentation-runtime.zip",
            "audio-models"
        ),
        EngineInfo(
            "Silero VAD 语音活动检测",
            "Silero VAD ONNX 模型\n大小: 约2.3MB | 用途: 高精度语音活动检测\n状态: 支持下载\n适用: AI音频分段，检测语音存在\n说明: 与YAMNet组合使用，双模型分析PCM音频生成精确分段",
            "约2.3MB",
            "https://ghfast.top/https://github.com/bingo17368222/RadioApp/releases/download/v2.4.94-audio-models/silero_vad.onnx",
            "audio-models"
        ),
        EngineInfo(
            "YAMNet 音频分类模型",
            "Google YAMNet TFLite 模型\n大小: 约4.1MB | 用途: 音频分类(语音/音乐/静音等521类)\n状态: 支持下载\n适用: AI音频分段，区分干货/水货片段\n说明: 与Silero VAD组合使用，双模型分析PCM音频生成精确分段",
            "约4.1MB",
            "https://ghfast.top/https://github.com/bingo17368222/RadioApp/releases/download/v2.4.94-audio-models/yamnet.tflite",
            "audio-models"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offline_engine)

        tvTitle = findViewById(R.id.tv_title)
        btnBack = findViewById(R.id.btn_back)
        tvTitle?.text = "离线引擎管理"
        btnBack?.setOnClickListener { finish() }

        // Issue 13: 记录引擎列表
        writeEngineLog("onCreate: ${engines.size} engines registered")
        engines.forEachIndexed { idx, engine ->
            Log.d(TAG, "onCreate: [$idx] '${engine.name}' (modelDir=${engine.modelDir}, hasUrl=${engine.downloadUrl != null})")
            writeEngineLog("onCreate: [$idx] '${engine.name}' (modelDir=${engine.modelDir}, hasUrl=${engine.downloadUrl != null})")
        }

        for (engine in engines) {
            setupEngineCard(engine)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Activity销毁时取消正在进行的下载
        cancelActiveDownload()
    }

    /**
     * 取消当前正在进行的下载任务
     */
    private fun cancelActiveDownload() {
        downloadCancelled = true
        downloadJob?.cancel()
        downloadJob = null
        activeConnection?.disconnect()
        activeConnection = null
        // 删除临时文件
        activeTempFile?.let { tmp ->
            try {
                if (tmp.exists()) tmp.delete()
            } catch (_: Exception) {}
        }
        activeTempFile = null
        // 重置UI
        val btn = activeBtn
        val pb = activeProgressBar
        val engine = activeEngine
        val modelDir = activeModelDir
        activeBtn = null
        activeProgressBar = null
        activeEngine = null
        activeModelDir = null
        btn?.post {
            btn.isEnabled = true
            btn.text = "安装"
            pb?.progress = 0
            pb?.visibility = View.GONE
            // 重新绑定安装按钮的点击事件
            if (engine != null && modelDir != null) {
                bindInstallAction(engine, btn, pb, modelDir)
            }
        }
    }

    /**
     * 为"安装/继续下载"按钮绑定点击事件（开始下载并切换到取消模式）
     */
    private fun bindInstallAction(engine: EngineInfo, btn: Button, progressBar: ProgressBar?, modelDir: File?) {
        Log.d(TAG, "bindInstallAction: engine=${engine.name}, isInstalled=${isEngineInstalled(engine, modelDir)}")
        writeEngineLog("bindInstallAction: engine='${engine.name}', isInstalled=${isEngineInstalled(engine, modelDir)}")
        btn.setOnClickListener {
            if (modelDir == null) {
                Toast.makeText(this, "存储不可用，无法下载", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            cancelActiveDownload()
            downloadCancelled = false
            btn.isEnabled = true
            btn.text = "取消下载"
            progressBar?.visibility = View.VISIBLE
            progressBar?.progress = 0
            btn.setOnClickListener {
                cancelActiveDownload()
                Toast.makeText(this, "下载已取消", Toast.LENGTH_SHORT).show()
            }
            downloadModel(engine, btn, progressBar, modelDir)
        }
    }

    private fun setupEngineCard(engine: EngineInfo) {
        val card = LayoutInflater.from(this).inflate(R.layout.item_engine_card, null, false)
        val tvName: TextView = card.findViewById(R.id.tv_engine_name)
        val tvDesc: TextView = card.findViewById(R.id.tv_engine_desc)
        val tvSize: TextView = card.findViewById(R.id.tv_engine_size)
        val btnAction: Button = card.findViewById(R.id.btn_engine_action)
        val progressBar: ProgressBar? = card.findViewById(R.id.progress_engine)

        tvName.text = engine.name
        tvDesc.text = engine.desc
        tvSize.text = engine.size
        progressBar?.visibility = View.GONE

        val isBuiltin = (engine.downloadUrl == null || engine.downloadUrl.isEmpty()) && engine.multiFileUrls == null
        if (isBuiltin) {
            btnAction.text = if (engine.unavailable) "暂不可用" else "已内置"
            btnAction.isEnabled = false
            btnAction.alpha = 0.6f
        } else {
            val modelsDir = getExternalFilesDir("models")
            val modelDir = modelsDir?.let { File(it, engine.modelDir) }
            // v2.4.97: Don't use modelDir.exists() — it can return false even when the directory exists
            // on some Android devices due to filesystem/mount issues. Instead, check for key files directly.
            writeEngineLog("setupEngineCard: modelDir path=${modelDir?.absolutePath}, exists=${modelDir?.exists()}, listFiles=${modelDir?.listFiles()?.map { "${it.name}(${it.length()})" }?.take(5)}")
            // v2.4.97: Check by specific file existence instead of dir.exists()
            val installed = modelDir != null && (when {
                engine.modelDir.contains("vosk", ignoreCase = true) -> getDirTotalSize(modelDir) >= MIN_INSTALL_SIZE && isValidVoskModelDir(modelDir)
                engine.modelDir.contains("whisper", ignoreCase = true) -> getDirTotalSize(modelDir) >= MIN_INSTALL_SIZE && isValidWhisperModelDir(modelDir)
                engine.modelDir.contains("mnn", ignoreCase = true) -> {
                    // v2.4.98: Dynamically search mnn-llm/ subdirectories for any valid model
                    val mnnBaseDir = File(modelsDir, "mnn-llm")
                    writeEngineLog("setupEngineCard: MNN check: mnnBaseDir=${mnnBaseDir.absolutePath}, exists=${mnnBaseDir.exists()}, subDirs=${mnnBaseDir.listFiles()?.filter { it.isDirectory }?.map { it.name }}")
                    
                    // Try the configured modelDir first
                    val weightFile = File(modelDir, "llm.mnn.weight")
                    val weightExists = weightFile.exists() && weightFile.length() > 100_000_000
                    var found = weightExists
                    var foundDir = modelDir
                    
                    // If not found, search all subdirectories of mnn-llm/
                    if (!found && mnnBaseDir.exists()) {
                        val subDirs = mnnBaseDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
                        for (subDir in subDirs) {
                            val wf = File(subDir, "llm.mnn.weight")
                            if (wf.exists() && wf.length() > 100_000_000) {
                                writeEngineLog("setupEngineCard: MNN found in alternate dir: ${subDir.name} (weight=${wf.length()})")
                                found = true
                                foundDir = subDir
                                break
                            }
                        }
                    }
                    
                    // Also check via MnnLlmBridge
                    if (!found) {
                        val bridgeResult = try {
                            com.radio.app.whisper.MnnLlmBridge.isModelInstalled(modelDir)
                        } catch (e: Exception) { false }
                        if (bridgeResult) {
                            found = true
                            foundDir = modelDir
                        }
                    }
                    
                    writeEngineLog("setupEngineCard: MNN check: found=$found, foundDir=${foundDir.absolutePath}")
                    found
                }
                engine.modelDir.contains("audio-models") -> when {
                    engine.name.contains("运行库") -> {
                        // v2.4.97: Check all 3 .so files (matching NativeLibLoader)
                        val so1 = File(modelDir, "libonnxruntime.so").exists()
                        val so2 = File(modelDir, "libonnxruntime4j_jni.so").exists()
                        val so3 = File(modelDir, "libtensorflowlite_jni.so").exists()
                        writeEngineLog("setupEngineCard: runtime check: onnxruntime=$so1, onnxruntime4j_jni=$so2, tflite_jni=$so3")
                        so1 && so2 && so3
                    }
                    engine.name.contains("Silero") -> File(modelDir, "silero_vad.onnx").exists() && File(modelDir, "silero_vad.onnx").length() > 50_000
                    engine.name.contains("YAMNet") -> File(modelDir, "yamnet.tflite").exists() && File(modelDir, "yamnet.tflite").length() > 1_000_000
                    else -> false
                }
                else -> getDirTotalSize(modelDir) >= MIN_INSTALL_SIZE
            })
            // Issue 13: 记录安装检查结果
            Log.d(TAG, "setupEngineCard: engine='${engine.name}', modelDir='${engine.modelDir}', installed=$installed")
            writeEngineLog("setupEngineCard: engine='${engine.name}', modelDir='${engine.modelDir}', installed=$installed")
            if (installed) {
                btnAction.text = "已安装(点击删除)"
                btnAction.setOnClickListener {
                    if (engine.modelDir.contains("audio-models")) {
                        // v2.4.95: Only delete specific file, not entire directory
                        when {
                            engine.name.contains("运行库") -> {
                                listOf("libonnxruntime.so", "libonnxruntime4j_jni.so", "libtensorflowlite_jni.so").forEach {
                                    File(modelDir, it).delete()
                                }
                            }
                            engine.name.contains("Silero") -> File(modelDir, "silero_vad.onnx").delete()
                            engine.name.contains("YAMNet") -> File(modelDir, "yamnet.tflite").delete()
                        }
                        Toast.makeText(this, "${engine.name} 已删除", Toast.LENGTH_SHORT).show()
                        btnAction.text = "安装"
                        modelDir?.let { dir -> bindInstallAction(engine, btnAction, progressBar, dir) }
                    } else {
                        modelDir?.let { deleteRecursive(it) }
                        Toast.makeText(this, "${engine.name} 已删除", Toast.LENGTH_SHORT).show()
                        modelDir?.let { dir -> bindInstallAction(engine, btnAction, progressBar, dir) }
                    }
                }
            } else {
                // 检查是否有未完成的下载（支持断点续传）
                val hasPartialDownload = modelDir?.let { dir ->
                    dir.listFiles()?.any { it.name.endsWith(".tmp") } == true
                } ?: false
                if (modelDir?.exists() == true) {
                    if (hasPartialDownload) {
                        // 有未完成的下载，保留临时文件以支持断点续传
                        btnAction.text = "继续下载"
                    } else {
                        // 没有临时文件，清理残留
                        deleteRecursive(modelDir)
                        btnAction.text = "安装"
                    }
                } else {
                    btnAction.text = "安装"
                }
                bindInstallAction(engine, btnAction, progressBar, modelDir)
            }
            // Issue 12: 保存 Vosk 引擎卡片的 UI 引用，供模型安装完成后自动触发 .so 安装使用
            if (engine.modelDir == "vosk-engine") {
                voskEngineBtn = btnAction
                voskEngineProgressBar = progressBar
                voskEngineModelDir = modelDir
            }
            // Issue 9: 保存 Whisper 引擎卡片的 UI 引用，供模型安装完成后自动触发 .so 安装使用
            if (engine.modelDir == "whisper-engine") {
                whisperEngineBtn = btnAction
                whisperEngineProgressBar = progressBar
                whisperEngineModelDir = modelDir
            }
        }

        val container = findViewById<LinearLayout>(R.id.engine_container)
        container?.addView(card)
    }

    /**
     * 根据原始URL生成备用下载URL列表
     *
     * 下载源策略:
     * - Vosk模型 (alphacephei.com): 唯一官方源，无镜像回退
     * - Whisper模型 (hf-mirror.com): 主用国内镜像 hf-mirror.com，备用 huggingface.co 官方直连
     * - GitHub releases: 多镜像回退 (ghfast.top, gh-proxy.com, ghp.ci, mirror.ghproxy.com, GitHub直连)
     */
    private fun buildDownloadUrls(originalUrl: String): List<String> {
        // Vosk模型 - alphacephei.com 是唯一可用源，不生成镜像
        if (originalUrl.contains("alphacephei.com/")) {
            return listOf(originalUrl)
        }

        // Whisper模型 - 主用 hf-mirror.com 国内镜像，备用 huggingface.co 官方直连
        if (originalUrl.contains("hf-mirror.com/")) {
            val hfPath = originalUrl.substringAfter("hf-mirror.com/")
            return listOf(originalUrl, "https://huggingface.co/$hfPath").distinct()
        }

        // 提取GitHub路径（无论原始URL是哪种镜像或直连）
        val ghPath = when {
            originalUrl.contains("ghfast.top/https://github.com/") ->
                originalUrl.substringAfter("ghfast.top/https://github.com/")
            originalUrl.contains("gh-proxy.com/https://github.com/") ->
                originalUrl.substringAfter("gh-proxy.com/https://github.com/")
            originalUrl.contains("ghp.ci/https://github.com/") ->
                originalUrl.substringAfter("ghp.ci/https://github.com/")
            originalUrl.contains("mirror.ghproxy.com/https://github.com/") ->
                originalUrl.substringAfter("mirror.ghproxy.com/https://github.com/")
            originalUrl.contains("ghproxy.net/https://github.com/") ->
                originalUrl.substringAfter("ghproxy.net/https://github.com/")
            originalUrl.contains("github.com/") ->
                originalUrl.substringAfter("github.com/")
            else -> null
        }

        if (ghPath != null) {
            return listOf(
                "https://ghfast.top/https://github.com/$ghPath",
                "https://gh-proxy.com/https://github.com/$ghPath",
                "https://ghp.ci/https://github.com/$ghPath",
                "https://mirror.ghproxy.com/https://github.com/$ghPath",
                "https://github.com/$ghPath"
            ).distinct()
        }

        return listOf(originalUrl)
    }

    // [v2.4.29] Download a single file with retry and proper resume support
    private fun downloadSingleFile(url: String, outFile: File, engine: EngineInfo, btn: Button, progressBar: ProgressBar?): Boolean {
        // Skip if already downloaded successfully
        if (outFile.exists() && outFile.length() > 100) {
            writeEngineLog("downloadSingleFile: already exists, skipping: ${outFile.name} (${outFile.length()} bytes)")
            return true
        }

        val maxRetries = 3
        for (attempt in 1..maxRetries) {
            if (downloadCancelled) return false
            writeEngineLog("downloadSingleFile: attempt $attempt/$maxRetries for ${outFile.name}")
            val success = downloadSingleFileAttempt(url, outFile, attempt)
            if (success) return true
            if (downloadCancelled) return false
            // Wait before retry
            try { Thread.sleep(2000L * attempt) } catch (_: InterruptedException) {}
        }
        writeEngineLog("downloadSingleFile: FAILED after $maxRetries attempts: ${outFile.name}")
        return false
    }

    private fun downloadSingleFileAttempt(url: String, outFile: File, attempt: Int): Boolean {
        try {
            val tmpFile = File(outFile.parentFile, outFile.name + ".tmp")
            val existingBytes = if (tmpFile.exists()) tmpFile.length() else 0L
            val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 30000
                readTimeout = 120000  // v2.4.29: increased from 60s to 120s for large files
                if (existingBytes > 0) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
            }
            val responseCode = connection.responseCode
            if (responseCode != 200 && responseCode != 206) {
                writeEngineLog("downloadSingleFile: HTTP $responseCode for ${outFile.name}")
                connection.disconnect()
                return false
            }

            // v2.4.29: If server returned 200 (not 206) but we have existing bytes,
            // the server doesn't support range - start fresh to avoid corruption
            val isResume = responseCode == 206 && existingBytes > 0
            val output = if (isResume) {
                java.io.FileOutputStream(tmpFile, true)
            } else {
                // Start fresh - delete old tmp file
                if (tmpFile.exists()) tmpFile.delete()
                java.io.FileOutputStream(tmpFile, false)
            }
            val effectiveExisting = if (isResume) existingBytes else 0L

            val input = connection.inputStream
            val buffer = ByteArray(16384)  // v2.4.29: larger buffer for better throughput
            var bytesRead: Int
            var totalRead = effectiveExisting
            while (true) {
                if (downloadCancelled) {
                    input.close(); output.close(); connection.disconnect()
                    return false
                }
                bytesRead = input.read(buffer)
                if (bytesRead == -1) break
                output.write(buffer, 0, bytesRead)
                totalRead += bytesRead
            }
            input.close(); output.close(); connection.disconnect()
            // v2.4.30: Lower minimum from 100 to 1 byte - small files like embedding_ids.txt can be < 100 bytes
            if (totalRead < 1) {
                writeEngineLog("downloadSingleFile: empty file (0 bytes), likely failed: ${outFile.name}")
                return false
            }
            tmpFile.renameTo(outFile)
            writeEngineLog("downloadSingleFile: COMPLETE: ${outFile.name} (${outFile.length()} bytes)")
            return true
        } catch (e: Exception) {
            writeEngineLog("downloadSingleFile: ERROR (attempt $attempt) for ${outFile.name}: ${e.message}")
            return false
        }
    }

    private fun downloadModel(engine: EngineInfo, btn: Button, progressBar: ProgressBar?, modelDir: File?) {
        // Issue 5/9: 综合日志记录下载开始
        writeEngineLog("downloadModel: START, engine=${engine.name}, modelDir=${engine.modelDir}, url=${engine.downloadUrl}")
        val job = lifecycleScope.launch {
            try {
                val modelsDir = withContext(Dispatchers.IO) {
                    getExternalFilesDir("models")
                }
                if (modelsDir == null) {
                    withContext(Dispatchers.Main) {
                        btn.isEnabled = true
                        btn.text = "安装"
                        progressBar?.visibility = View.GONE
                        modelDir?.let { bindInstallAction(engine, btn, progressBar, it) }
                    }
                    return@launch
                }
                if (!modelsDir.exists()) {
                    modelsDir.mkdirs()
                }

                // [v2.4.21] Multi-file download for MNN-LLM model
                if (engine.multiFileUrls != null) {
                    val targetDir = File(modelsDir, engine.modelDir)
                    if (!targetDir.exists()) targetDir.mkdirs()
                    writeEngineLog("downloadModel: multi-file download START, ${engine.multiFileUrls.size} files")
                    var downloadedCount = 0
                    for ((idx, url) in engine.multiFileUrls.withIndex()) {
                        if (downloadCancelled) break
                        // v2.4.66: Extract filename from URL correctly for both API and resolve formats.
                        // Old API format: .../repo?Revision=master&FilePath=llm.mnn → extract after "FilePath="
                        // New resolve format: .../resolve/master/llm.mnn → extract after last "/"
                        val fileName = if (url.contains("FilePath=")) {
                            url.substringAfter("FilePath=")
                        } else {
                            url.substringAfterLast("/")
                        }
                        val outFile = File(targetDir, fileName)
                        withContext(Dispatchers.Main) {
                            btn.text = "下载中(${idx+1}/${engine.multiFileUrls.size})"
                            progressBar?.progress = (idx * 100 / engine.multiFileUrls.size)
                        }
                        writeEngineLog("downloadModel: downloading file ${idx+1}/${engine.multiFileUrls.size}: $fileName (url=$url)")
                        val success = withContext(Dispatchers.IO) {
                            downloadSingleFile(url, outFile, engine, btn, progressBar)
                        }
                        if (success) downloadedCount++ else {
                            writeEngineLog("downloadModel: FAILED to download $fileName")
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@OfflineEngineActivity, "下载失败: $fileName", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    // v2.4.31: Download MNN .so files from GitHub releases
                    val isMnnEngine = engine.modelDir.contains("mnn", ignoreCase = true)
                    if (isMnnEngine) {
                        // v2.4.32: Fix path - must be sibling of modelDir, NOT sibling of modelsDir
                        // modelDir = models/mnn-llm/Qwen1.5-1.8B-Chat-MNN
                        // libsDir must be models/mnn-llm/mnn-libs (same as isValidMnnModelDir checks)
                        val libsDir = File(targetDir.parentFile, "mnn-libs")
                        if (!libsDir.exists()) libsDir.mkdirs()
                        writeEngineLog("downloadModel: [v2.4.31] downloading MNN .so files...")
                        withContext(Dispatchers.Main) {
                            btn.text = "下载运行库..."
                            progressBar?.progress = 90
                        }
                        val soSuccess = withContext(Dispatchers.IO) {
                            downloadAndExtractMnnLibs(libsDir)
                        }
                        writeEngineLog("downloadModel: [v2.4.31] MNN .so download result=$soSuccess")
                        if (!soSuccess) {
                            writeEngineLog("downloadModel: [v2.4.31] MNN .so download FAILED")
                            withContext(Dispatchers.Main) {
                                btn.text = "继续下载"
                                Toast.makeText(this@OfflineEngineActivity, "MNN运行库下载失败，请重试", Toast.LENGTH_LONG).show()
                                progressBar?.visibility = View.GONE
                                bindInstallAction(engine, btn, progressBar, targetDir)
                            }
                            return@launch
                        }
                    }

                    // v2.4.30: Post-download validation - verify ALL required files exist
                    val requiredFiles = if (isMnnEngine) {
                        listOf("llm.mnn", "llm.mnn.weight", "config.json")
                    } else {
                        emptyList()
                    }
                    val missingFiles = requiredFiles.filter { !File(targetDir, it).exists() || File(targetDir, it).length() < 1 }
                    // v2.4.31: For MNN, also check .so files exist
                    val libsOk = if (isMnnEngine) {
                        File(targetDir.parentFile, "mnn-libs/libllm.so").exists()
                    } else true
                    val allDownloaded = downloadedCount == engine.multiFileUrls.size
                    val valid = allDownloaded && missingFiles.isEmpty() && libsOk

                    withContext(Dispatchers.Main) {
                        progressBar?.progress = 100
                        if (valid) {
                            writeEngineLog("downloadModel: multi-file download COMPLETE, all ${downloadedCount} files downloaded and validated")
                            btn.text = "已安装(点击删除)"
                            Toast.makeText(this@OfflineEngineActivity, "${engine.name} 下载完成", Toast.LENGTH_SHORT).show()
                            progressBar?.visibility = View.GONE
                            btn.setOnClickListener {
                                targetDir.let { deleteRecursive(it) }
                                // v2.4.31: Also delete mnn-libs directory for MNN engines
                                if (isMnnEngine) {
                                    File(targetDir.parentFile, "mnn-libs").let { libsDir ->
                                        if (libsDir.exists()) {
                                            deleteRecursive(libsDir)
                                            writeEngineLog("downloadModel: deleted mnn-libs directory")
                                        }
                                    }
                                }
                                btn.text = "安装"
                                Toast.makeText(this@OfflineEngineActivity, "${engine.name} 已删除", Toast.LENGTH_SHORT).show()
                                bindInstallAction(engine, btn, progressBar, targetDir)
                            }
                        } else {
                            val reason = if (!allDownloaded) "只下载了${downloadedCount}/${engine.multiFileUrls.size}个文件" else "缺失文件: ${missingFiles.joinToString()}"
                            writeEngineLog("downloadModel: multi-file download INCOMPLETE: $reason")
                            btn.text = "继续下载"
                            Toast.makeText(this@OfflineEngineActivity, "下载不完整: $reason", Toast.LENGTH_LONG).show()
                            progressBar?.visibility = View.GONE
                            bindInstallAction(engine, btn, progressBar, targetDir)
                        }
                    }
                    return@launch
                }

                val baseUrl = engine.downloadUrl ?: return@launch
                val allUrls = buildDownloadUrls(baseUrl)
                val fileName = baseUrl.substring(baseUrl.lastIndexOf('/') + 1)
                val outFile = File(modelsDir, "${engine.modelDir}/$fileName")
                val tempFile = File(modelsDir, "${engine.modelDir}/$fileName.tmp")
                outFile.parentFile?.mkdirs()

                // 跟踪活动状态以便取消
                activeTempFile = tempFile
                activeBtn = btn
                activeProgressBar = progressBar
                activeEngine = engine
                activeModelDir = modelDir

                // 如果已存在有效文件，直接视为已安装
                if (outFile.exists() && outFile.length() > 1024 && !fileName.endsWith(".zip")) {
                    withContext(Dispatchers.Main) {
                        btn.isEnabled = true
                        btn.text = "已安装(点击删除)"
                        progressBar?.visibility = View.GONE
                        btn.setOnClickListener {
                            File(modelsDir, engine.modelDir).let { deleteRecursive(it) }
                            btn.text = "安装"
                            Toast.makeText(this@OfflineEngineActivity, "${engine.name} 已删除", Toast.LENGTH_SHORT).show()
                            modelDir?.let { bindInstallAction(engine, btn, progressBar, it) }
                        }
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    btn.text = "连接中..."
                }

                var downloadSucceeded = false
                var lastError: String? = null
                var usedFallback = false
                var usedMirrorName = ""

                // 尝试每个镜像URL，直到一个成功
                for ((urlIndex, currentUrl) in allUrls.withIndex()) {
                    if (downloadSucceeded || downloadCancelled) break

                    val mirrorName = when {
                        currentUrl.contains("ghfast.top") -> "ghfast"
                        currentUrl.contains("gh-proxy.com") -> "gh-proxy"
                        currentUrl.contains("ghp.ci") -> "ghp.ci"
                        currentUrl.contains("mirror.ghproxy.com") -> "ghproxy-mirror"
                        currentUrl.contains("ghproxy.net") -> "ghproxy"
                        currentUrl.contains("hf-mirror.com") -> "hf-mirror"
                        currentUrl.contains("github.com") -> "GitHub直连"
                        currentUrl.contains("huggingface.co") -> "HuggingFace"
                        else -> "源#${urlIndex + 1}"
                    }

                    Log.d(TAG, "downloadModel START: engine=${engine.name}, url=$currentUrl, mirror=$mirrorName")
                    writeEngineLog("downloadModel START: engine='${engine.name}', modelDir='${engine.modelDir}', url=$currentUrl, mirror=$mirrorName, targetDir=${outFile.parent}")

                    if (urlIndex > 0) {
                        usedFallback = true
                        Log.d(TAG, "Trying fallback mirror #$urlIndex: $mirrorName")
                        withContext(Dispatchers.Main) {
                            if (!downloadCancelled) btn.text = "切换镜像($mirrorName)..."
                        }
                        // 切换镜像时删除临时文件，从头开始下载（不同镜像的字节可能有差异）
                        if (tempFile.exists()) {
                            tempFile.delete()
                        }
                    }

                    try {
                        withContext(Dispatchers.IO) {
                            var conn: HttpURLConnection? = null
                            var raf: RandomAccessFile? = null
                            try {
                                if (downloadCancelled) return@withContext

                                val url = URL(currentUrl)
                                conn = url.openConnection() as HttpURLConnection
                                activeConnection = conn
                                conn.connectTimeout = 60000
                                conn.readTimeout = 600000
                                conn.instanceFollowRedirects = true
                                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")

                                // 断点续传：仅在首个URL尝试时使用（同镜像重试）
                                var downloaded = 0L
                                if (urlIndex == 0 && tempFile.exists()) {
                                    downloaded = tempFile.length()
                                    conn.setRequestProperty("Range", "bytes=$downloaded-")
                                    Log.d(TAG, "Resuming download from byte $downloaded for ${engine.name}")
                                }

                                val rc = conn.responseCode
                                Log.d(TAG, "Download HTTP $rc from $mirrorName for ${engine.name}")
                                if (rc !in 200..206) {
                                    throw Exception("HTTP $rc from $mirrorName")
                                }

                                val contentLength = conn.contentLengthLong
                                val totalSize = if (downloaded > 0 && rc == 206) {
                                    downloaded + contentLength
                                } else if (contentLength > 0) {
                                    contentLength
                                } else {
                                    when {
                                        engine.name.contains("Tiny") -> 75L * 1024 * 1024
                                        engine.name.contains("Base") -> 142L * 1024 * 1024
                                        engine.name.contains("Small") && !engine.name.contains("大") -> 466L * 1024 * 1024
                                        engine.name.contains("Medium") -> 1500L * 1024 * 1024
                                        engine.name.contains("Large") -> 2900L * 1024 * 1024
                                        else -> -1L
                                    }
                                }
                                Log.d(TAG, "Download total size: $totalSize from $mirrorName for ${engine.name}")
                                Log.d(TAG, "downloadModel: connected, contentLength=$totalSize")
                                writeEngineLog("downloadModel: connected to $mirrorName for '${engine.name}', totalSize=$totalSize bytes")

                                val input = conn.inputStream ?: throw Exception("连接失败 ($mirrorName)")
                                raf = RandomAccessFile(tempFile, "rw")
                                raf.seek(downloaded)

                                val buffer = ByteArray(8192)
                                var len: Int = 0
                                var startTime = System.currentTimeMillis()
                                var lastLoggedProgressStep = -1

                                while (!downloadCancelled && input.read(buffer).also { len = it } != -1) {
                                    raf.write(buffer, 0, len)
                                    downloaded += len
                                    val now = System.currentTimeMillis()
                                    if (now - startTime > 1000) {
                                        val progress: Int
                                        val progressText: String
                                        if (totalSize > 0) {
                                            progress = (downloaded * 100 / totalSize).toInt().coerceIn(0, 100)
                                            val elapsedSec = (now - startTime) / 1000.0
                                            val speed = if (elapsedSec > 0) downloaded / elapsedSec else 0.0
                                            val speedStr = if (speed >= 1024 * 1024) {
                                                String.format("%.1f MB/s", speed / (1024.0 * 1024))
                                            } else {
                                                String.format("%.0f KB/s", speed / 1024.0)
                                            }
                                            progressText = "下载($mirrorName): $progress% | $speedStr"
                                        } else {
                                            progress = 0
                                            val downloadedMB = downloaded / (1024.0 * 1024)
                                            progressText = "下载($mirrorName): ${String.format("%.1f", downloadedMB)} MB"
                                        }
                                        withContext(Dispatchers.Main) {
                                            if (!downloadCancelled) {
                                                btn.text = progressText
                                                progressBar?.max = 100
                                                progressBar?.progress = progress
                                            }
                                        }
                                        val progressStep = progress / 10
                                        if (progressStep != lastLoggedProgressStep) {
                                            lastLoggedProgressStep = progressStep
                                            Log.d(TAG, "downloadModel: progress=$progress%, downloaded=$downloaded, total=$totalSize")
                                            writeEngineLog("downloadModel: '${engine.name}' progress=$progress%, downloaded=$downloaded, total=$totalSize, mirror=$mirrorName")
                                        }
                                        startTime = now
                                    }
                                }

                                if (downloadCancelled) {
                                    Log.d(TAG, "Download cancelled for ${engine.name}")
                                    try { raf?.close() } catch (_: Exception) {}
                                    try { input.close() } catch (_: Exception) {}
                                    return@withContext
                                }

                                raf.close()
                                raf = null
                                input.close()

                                if (tempFile.length() < 1024) {
                                    throw Exception("下载文件过小: ${tempFile.length()} bytes")
                                }

                                // Verify zip file is valid (not an HTML error page)
                                if (fileName.endsWith(".zip")) {
                                    if (tempFile.length() < 1000) {
                                        tempFile.delete()
                                        throw Exception("下载的文件无效（可能是镜像不可用），请重试")
                                    }
                                    // Check if it starts with PK (zip magic bytes)
                                    val header = ByteArray(2)
                                    java.io.FileInputStream(tempFile).use { it.read(header) }
                                    if (header[0] != 0x50.toByte() || header[1] != 0x4B.toByte()) {
                                        tempFile.delete()
                                        throw Exception("下载的文件不是有效的zip，请更换镜像重试")
                                    }
                                }

                                downloadSucceeded = true
                                usedMirrorName = mirrorName

                                withContext(Dispatchers.Main) {
                                    btn.text = "下载完成"
                                }

                                // 将临时文件重命名为最终文件
                                if (outFile.exists()) {
                                    outFile.delete()
                                }
                                tempFile.renameTo(outFile)
                                Log.d(TAG, "downloadModel: download complete, file=${outFile.absolutePath}, size=${outFile.length()}")
                                writeEngineLog("downloadModel: download complete for '${engine.name}', file=${outFile.absolutePath}, size=${outFile.length()} bytes")

                                // If this is an AAR file, extract the .so
                                if (outFile.name.endsWith(".aar")) {
                                    try {
                                        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                                        val soFileName = "libvosk.so"
                                        val soOutputDir = File(modelsDir, "vosk-engine")
                                        soOutputDir.mkdirs()
                                        val soOutputFile = File(soOutputDir, soFileName)

                                        // AAR is a ZIP file
                                        Log.d(TAG, "AAR extraction: opening ${outFile.absolutePath}, size=${outFile.length()}")
                                        val zipFile = java.util.zip.ZipFile(outFile)
                                        // Find the .so for the device's ABI
                                        val soEntry = zipFile.getEntry("jni/$abi/$soFileName")
                                            ?: zipFile.getEntry("jni/armeabi-v7a/$soFileName") // fallback
                                        if (soEntry != null) {
                                            Log.d(TAG, "AAR extraction: found entry jni/$abi/$soFileName, size=${soEntry.size}")
                                            zipFile.getInputStream(soEntry).use { input ->
                                                soOutputFile.outputStream().use { output ->
                                                    input.copyTo(output)
                                                }
                                            }
                                            Log.d(TAG, "AAR extraction: extracted to ${soOutputFile.absolutePath}, size=${soOutputFile.length()}")
                                            writeEngineLog("AAR extraction: extracted libvosk.so to ${soOutputFile.absolutePath}, size=${soOutputFile.length()} bytes")
                                            zipFile.close()
                                            // Delete the AAR to save space
                                            outFile.delete()
                                            // Mark as installed
                                            withContext(Dispatchers.Main) {
                                                btn.text = "已安装"
                                                btn.isEnabled = false
                                                Toast.makeText(this@OfflineEngineActivity, "Vosk引擎安装成功", Toast.LENGTH_SHORT).show()
                                            }
                                            // Issue 5 fix: clean up the progress bar so it doesn't stay visible indefinitely
                                            withContext(Dispatchers.Main) {
                                                progressBar?.progress = 100
                                                progressBar?.visibility = View.GONE
                                            }
                                            Log.d(TAG, "Vosk engine (.so) installation complete: ${soOutputFile.absolutePath}, size=${soOutputFile.length()}")
                                            writeEngineLog("Vosk engine (.so) installation complete: ${soOutputFile.absolutePath}, size=${soOutputFile.length()} bytes")
                                            return@withContext
                                        } else {
                                            zipFile.close()
                                            throw Exception("AAR中未找到 $abi 架构的 $soFileName")
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "AAR extraction failed", e)
                                        throw e
                                    }
                                }

                                if (fileName.endsWith(".zip")) {
                                    withContext(Dispatchers.Main) {
                                        btn.text = "解压中..."
                                    }
                                    val destDir = File(modelsDir, engine.modelDir)
                                    unzipFile(outFile, destDir)
                                    outFile.delete()

                                    // 解压后验证模型文件
                                    Log.d(TAG, "downloadModel: validating ${engine.name}...")
                                    val isValid = when {
                                        engine.modelDir.contains("vosk", ignoreCase = true) -> isValidVoskModelDir(destDir)
                                        engine.modelDir.contains("whisper", ignoreCase = true) -> isValidWhisperModelDir(destDir)
                                        else -> true
                                    }
                                    Log.d(TAG, "downloadModel: validation ${if (isValid) "passed" else "failed"}")
                                    if (!isValid) {
                                        Log.w(TAG, "Model validation failed for ${engine.name} after unzip, cleaning up")
                                        deleteRecursive(destDir)
                                        throw Exception("解压后模型验证失败，请重试")
                                    }
                                } else if (engine.modelDir.contains("whisper", ignoreCase = true)) {
                                    // 验证Whisper .bin文件
                                    val destDir = File(modelsDir, engine.modelDir)
                                    Log.d(TAG, "downloadModel: validating ${engine.name}...")
                                    val isValid = isValidWhisperModelDir(destDir)
                                    Log.d(TAG, "downloadModel: validation ${if (isValid) "passed" else "failed"}")
                                    if (!isValid) {
                                        Log.w(TAG, "Whisper model validation failed for ${engine.name}")
                                        deleteRecursive(destDir)
                                        throw Exception("模型文件验证失败，请重试")
                                    }
                                }

                                // Issue 12: 模型安装完成后，自动检查并安装所需引擎 .so 文件
                                when {
                                    engine.modelDir.startsWith("vosk-model") -> {
                                        val voskSoInstalled = isVoskEngineInstalled()
                                        Log.d(TAG, "Issue12: vosk model '${engine.modelDir}' installed, libvosk.so installed=$voskSoInstalled")
                                        writeEngineLog("Issue12: vosk model '${engine.modelDir}' installed, libvosk.so installed=$voskSoInstalled")
                                        if (!voskSoInstalled) {
                                            Log.d(TAG, "Auto-installing Vosk engine (libvosk.so) after model install")
                                            writeEngineLog("Auto-installing Vosk engine (libvosk.so) after model '${engine.modelDir}' install")
                                            val voskEngine = engines.firstOrNull { it.modelDir == "vosk-engine" }
                                            if (voskEngine != null) {
                                                val vBtn = voskEngineBtn
                                                val vPb = voskEngineProgressBar
                                                val vDir = voskEngineModelDir
                                                if (vBtn != null && vPb != null && vDir != null) {
                                                    lifecycleScope.launch {
                                                        downloadCancelled = false
                                                        vBtn.isEnabled = true
                                                        vBtn.text = "取消下载"
                                                        vPb.visibility = View.VISIBLE
                                                        vPb.progress = 0
                                                        vBtn.setOnClickListener {
                                                            cancelActiveDownload()
                                                            Toast.makeText(this@OfflineEngineActivity, "下载已取消", Toast.LENGTH_SHORT).show()
                                                        }
                                                        writeEngineLog("Issue12: starting auto-download of vosk-engine (libvosk.so)")
                                                        downloadModel(voskEngine, vBtn, vPb, vDir)
                                                    }
                                                } else {
                                                    Log.w(TAG, "Vosk engine card UI refs not available, skipping auto-install")
                                                    writeEngineLog("Issue12: vosk-engine card UI refs null, cannot auto-install")
                                                }
                                            } else {
                                                Log.w(TAG, "vosk-engine entry not found in engines list")
                                                writeEngineLog("Issue12: vosk-engine entry not found in engines list")
                                            }
                                        }
                                    }
                                    engine.modelDir.startsWith("whisper") -> {
                                        val whisperSoInstalled = isWhisperEngineInstalled()
                                        Log.d(TAG, "Issue5: whisper model '${engine.modelDir}' installed, whisper engine .so installed=$whisperSoInstalled")
                                        writeEngineLog("Issue5: whisper model '${engine.modelDir}' installed, whisper engine .so installed=$whisperSoInstalled")
                                        if (!whisperSoInstalled) {
                                            Log.d(TAG, "Auto-installing Whisper engine (.so files) after model install")
                                            writeEngineLog("Auto-installing Whisper engine (.so files) after model '${engine.modelDir}' install")
                                            val whisperEngine = engines.firstOrNull { it.modelDir == "whisper-engine" }
                                            if (whisperEngine != null) {
                                                val wBtn = whisperEngineBtn
                                                val wPb = whisperEngineProgressBar
                                                val wDir = whisperEngineModelDir
                                                if (wBtn != null && wPb != null && wDir != null) {
                                                    lifecycleScope.launch {
                                                        downloadCancelled = false
                                                        wBtn.isEnabled = true
                                                        wBtn.text = "取消下载"
                                                        wPb.visibility = View.VISIBLE
                                                        wPb.progress = 0
                                                        wBtn.setOnClickListener {
                                                            cancelActiveDownload()
                                                            Toast.makeText(this@OfflineEngineActivity, "下载已取消", Toast.LENGTH_SHORT).show()
                                                        }
                                                        writeEngineLog("Issue5: starting auto-download of whisper-engine (.so files)")
                                                        downloadModel(whisperEngine, wBtn, wPb, wDir)
                                                    }
                                                } else {
                                                    Log.w(TAG, "Whisper engine card UI refs not available, skipping auto-install")
                                                    writeEngineLog("Issue5: whisper-engine card UI refs null, cannot auto-install")
                                                }
                                            } else {
                                                Log.w(TAG, "whisper-engine entry not found in engines list")
                                                writeEngineLog("Issue5: whisper-engine entry not found in engines list")
                                            }
                                        }
                                    }
                                }

                                withContext(Dispatchers.Main) {
                                    btn.isEnabled = true
                                    btn.text = "已安装(点击删除)"
                                    progressBar?.visibility = View.GONE
                                    // 绑定删除按钮
                                    btn.setOnClickListener {
                                        File(modelsDir, engine.modelDir).let { deleteRecursive(it) }
                                        btn.text = "安装"
                                        Toast.makeText(this@OfflineEngineActivity, "${engine.name} 已删除", Toast.LENGTH_SHORT).show()
                                        modelDir?.let { bindInstallAction(engine, btn, progressBar, it) }
                                    }
                                    Toast.makeText(this@OfflineEngineActivity, "${engine.name} 安装完成 (via $usedMirrorName)", Toast.LENGTH_SHORT).show()
                                }
                                Log.d(TAG, "downloadModel: installation complete for ${engine.name}")
                                writeEngineLog("downloadModel: installation complete for '${engine.name}' (modelDir='${engine.modelDir}')")
                                writeEngineLog("downloadModel: COMPLETE, engine=${engine.name}, modelDir=${engine.modelDir}, success=true")
                            } catch (e: Exception) {
                                if (downloadCancelled) return@withContext
                                lastError = "${e.message}"
                                Log.e(TAG, "Download failed from $mirrorName: ${e.message}", e)
                                writeEngineLog("Download failed from $mirrorName for '${engine.name}': ${e.message}")
                                // 关闭资源
                                try { raf?.close() } catch (_: Exception) {}
                                try { conn?.disconnect() } catch (_: Exception) {}
                                // 如果不是最后一个URL，继续尝试下一个镜像
                                if (urlIndex < allUrls.size - 1) {
                                    Log.d(TAG, "Will try next mirror...")
                                } else {
                                    throw e
                                }
                            } finally {
                                try { raf?.close() } catch (_: Exception) {}
                                if (conn != null && activeConnection === conn) {
                                    // don't disconnect here if we're moving to next mirror; will be GC'd
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (downloadCancelled) break
                        lastError = "${e.message}"
                    }
                }

                // 处理取消
                if (downloadCancelled) {
                    // 清理临时文件
                    withContext(Dispatchers.IO) {
                        try { if (tempFile.exists()) tempFile.delete() } catch (_: Exception) {}
                    }
                    return@launch
                }

                // 如果所有镜像都失败了
                if (!downloadSucceeded) {
                    // Issue 5/9: 记录下载最终失败
                    writeEngineLog("downloadModel: COMPLETE, engine=${engine.name}, modelDir=${engine.modelDir}, success=false, error=$lastError")
                    // 如果使用了备用镜像仍失败，删除临时文件避免跨镜像断点续传损坏
                    if (usedFallback) {
                        withContext(Dispatchers.IO) {
                            try { if (tempFile.exists()) tempFile.delete() } catch (_: Exception) {}
                        }
                    }
                    withContext(Dispatchers.Main) {
                        btn.isEnabled = true
                        btn.text = if (usedFallback) "安装" else "继续下载"
                        progressBar?.visibility = View.GONE
                        val triedMirrors = allUrls.size
                        Toast.makeText(this@OfflineEngineActivity,
                            "下载失败: $lastError\n已尝试 $triedMirrors 个镜像源，请稍后重试",
                            Toast.LENGTH_LONG).show()
                        // 重新绑定安装按钮
                        modelDir?.let { bindInstallAction(engine, btn, progressBar, it) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "downloadModel ERROR: ${engine.name}: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    btn.isEnabled = true
                    btn.text = "安装"
                    progressBar?.visibility = View.GONE
                    modelDir?.let { bindInstallAction(engine, btn, progressBar, it) }
                }
            } finally {
                // 仅当当前job仍是活动job时才清除，避免新启动的下载被旧job的finally覆盖
                if (downloadJob === coroutineContext[Job]) {
                    downloadJob = null
                }
                // Issue 12: 仅当 active 状态仍属于本次下载（该 engine）时才清除，避免清除
                // 自动安装触发的 Vosk 引擎下载所设置的 active 状态。
                if (activeEngine === engine) {
                    activeConnection = null
                    activeTempFile = null
                    activeBtn = null
                    activeProgressBar = null
                    activeEngine = null
                    activeModelDir = null
                }
            }
        }
        downloadJob = job
    }

    private fun unzipFile(zipFile: File, destDir: File) {
        Log.d(TAG, "unzipFile: extracting ${zipFile.absolutePath} to ${destDir.absolutePath}")
        // First pass: detect common prefix from ALL entries (not just directory entries)
        var commonPrefix: String? = null
        var zipInputStream: ZipInputStream? = null
        try {
            zipInputStream = ZipInputStream(java.io.FileInputStream(zipFile))
            var entry: java.util.zip.ZipEntry? = zipInputStream.nextEntry
            while (entry != null) {
                val name = entry.name
                if (entry.isDirectory && !name.contains('/')) {
                    // Explicit root directory entry found
                    commonPrefix = name.trimEnd('/')
                    break
                }
                if (!entry.isDirectory && name.contains('/')) {
                    val prefix = name.substringBefore('/')
                    if (commonPrefix == null) {
                        commonPrefix = prefix
                    } else if (commonPrefix != prefix) {
                        commonPrefix = null
                        break
                    }
                }
                entry = zipInputStream.nextEntry
            }
        } catch (e: Exception) {
            Log.w(TAG, "unzipFile: prefix detection failed: ${e.message}")
        }
        try { zipInputStream?.close() } catch (_: Exception) {}

        Log.d(TAG, "unzipFile: detected commonPrefix=$commonPrefix")

        // Second pass: extract files
        var count = 0
        ZipInputStream(java.io.BufferedInputStream(java.io.FileInputStream(zipFile))).use { zis ->
            val buffer = ByteArray(8192)
            var entry = zis.nextEntry
            while (entry != null) {
                Log.d(TAG, "unzipFile: extracting ${entry.name}, size=${entry.size}")
                count++
                // Strip the common top-level directory prefix
                var entryName = entry.name
                if (commonPrefix != null && entryName.startsWith("$commonPrefix/")) {
                    entryName = entryName.removePrefix("$commonPrefix/")
                }
                if (entryName.isBlank()) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                val outFile = File(destDir, entryName)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } != -1) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // After extraction, check for nested directory and move files up one level
        // This handles cases where the zip contains e.g. vosk-model-small-cn-0.22/vosk-model-small-cn-0.22/...
        val extractedDirs = destDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        if (extractedDirs.size == 1) {
            val nestedDir = extractedDirs[0]
            // If the only subdirectory matches the expected model dir name, flatten it
            val nestedFiles = nestedDir.listFiles() ?: emptyArray()
            val hasModelFiles = nestedFiles.any { f ->
                f.isFile && (f.name.endsWith(".mdl") || f.name.endsWith(".fst") || f.name.endsWith(".conf"))
            } || nestedFiles.any { f -> f.isDirectory && (f.name == "am" || f.name == "graph" || f.name == "conf" || f.name == "ivector") }
            if (hasModelFiles || nestedDir.name == zipFile.nameWithoutExtension) {
                Log.d(TAG, "unzipFile: detected nested directory '${nestedDir.name}', moving contents up")
                for (f in nestedFiles) {
                    val dest = File(destDir, f.name)
                    if (dest.exists()) {
                        if (f.isDirectory) {
                            // Merge directories
                            f.listFiles()?.forEach { child ->
                                val childDest = File(dest, child.name)
                                if (!childDest.exists()) {
                                    child.renameTo(childDest)
                                } else {
                                    // Move contents recursively
                                    moveRecursive(child, childDest)
                                }
                            }
                        } else {
                            f.copyTo(dest, overwrite = true)
                            f.delete()
                        }
                    } else {
                        f.renameTo(dest)
                    }
                }
                nestedDir.delete()
            }
        }
        Log.d(TAG, "unzipFile: complete, extracted $count entries")
    }

    private fun moveRecursive(src: File, dest: File) {
        if (src.isDirectory) {
            dest.mkdirs()
            src.listFiles()?.forEach { child ->
                val childDest = File(dest, child.name)
                moveRecursive(child, childDest)
            }
            src.delete()
        } else {
            if (dest.exists()) dest.delete()
            src.renameTo(dest)
        }
    }

    private fun deleteRecursive(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                deleteRecursive(child)
            }
        }
        file.delete()
    }

    private fun isEngineInstalled(engine: EngineInfo, modelDir: File?): Boolean {
        // v2.4.97: Don't check dir.exists() — it can return false on some devices
        if (modelDir == null) {
            writeEngineLog("isEngineInstalled: '${engine.name}' -> false (modelDir null)")
            return false
        }
        // v2.4.96: For audio-models, check specific files (no MIN_INSTALL_SIZE check)
        if (engine.modelDir.contains("audio-models")) {
            val result = when {
                engine.name.contains("运行库") -> File(modelDir, "libonnxruntime.so").exists() && File(modelDir, "libonnxruntime4j_jni.so").exists() && File(modelDir, "libtensorflowlite_jni.so").exists()
                engine.name.contains("Silero") -> File(modelDir, "silero_vad.onnx").exists() && File(modelDir, "silero_vad.onnx").length() > 50_000
                engine.name.contains("YAMNet") -> File(modelDir, "yamnet.tflite").exists() && File(modelDir, "yamnet.tflite").length() > 1_000_000
                else -> false
            }
            writeEngineLog("isEngineInstalled: '${engine.name}' -> $result (audio-models check)")
            return result
        }
        // v2.4.98: For MNN, dynamically search mnn-llm/ subdirectories
        if (engine.modelDir.contains("mnn", ignoreCase = true)) {
            val modelsDir = getExternalFilesDir("models")
            val mnnBaseDir = modelsDir?.let { File(it, "mnn-llm") }
            // Try configured path first
            val weightFile = File(modelDir, "llm.mnn.weight")
            var found = weightFile.exists() && weightFile.length() > 100_000_000
            
            // Search subdirectories if not found
            if (!found && mnnBaseDir != null && mnnBaseDir.exists()) {
                val subDirs = mnnBaseDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
                for (subDir in subDirs) {
                    val wf = File(subDir, "llm.mnn.weight")
                    if (wf.exists() && wf.length() > 100_000_000) {
                        found = true
                        break
                    }
                }
            }
            
            // Also check via MnnLlmBridge
            if (!found) {
                found = try { com.radio.app.whisper.MnnLlmBridge.isModelInstalled(modelDir) } catch (e: Exception) { false }
            }
            writeEngineLog("isEngineInstalled: '${engine.name}' -> $found (MNN dynamic check)")
            return found
        }
        if (getDirTotalSize(modelDir) < MIN_INSTALL_SIZE) {
            writeEngineLog("isEngineInstalled: '${engine.name}' -> false (size < MIN_INSTALL_SIZE, size=${getDirTotalSize(modelDir)})")
            return false
        }
        val result = when {
            engine.modelDir.contains("vosk", ignoreCase = true) -> isValidVoskModelDir(modelDir)
            engine.modelDir.contains("whisper", ignoreCase = true) -> isValidWhisperModelDir(modelDir)
            else -> true
        }
        writeEngineLog("isEngineInstalled: '${engine.name}' -> $result")
        return result
    }

    private fun isValidVoskModelDir(dir: File): Boolean {
        Log.d(TAG, "isValidVoskModelDir: checking ${dir.absolutePath}, exists=${dir.exists()}, files=${dir.listFiles()?.map { it.name }}")
        val result = run {
            if (!dir.isDirectory) return@run false

            // For vosk-engine directory, check if libvosk.so exists
            if (dir.name == "vosk-engine") {
                return@run File(dir, "libvosk.so").exists()
            }

            // Quick check: if directory name starts with "vosk-model", it's likely valid
            if (dir.name.startsWith("vosk-model", ignoreCase = true)) {
                // But still verify it has some content
                val files = dir.listFiles() ?: emptyArray()
                if (files.isNotEmpty()) {
                    return@run true
                }
            }

            // Check recursively for model files
            fun hasModelFiles(d: File, depth: Int = 0): Boolean {
                if (depth > 2) return false
                val files = d.listFiles() ?: return false
                for (f in files) {
                    if (f.isFile) {
                        if (f.name.endsWith(".mdl") || f.name.endsWith(".fst") ||
                            f.name == "model.conf" || f.name == "mfcc.conf" ||
                            f.name == "ivector.conf" || f.name == "HCLG.fst" ||
                            f.name == "Gr.fst" || f.name == "HCLr.fst") {
                            return true
                        }
                    } else if (f.isDirectory) {
                        if (f.name in listOf("am", "conf", "graph", "ivector", "rescore")) {
                            return true
                        }
                        if (hasModelFiles(f, depth + 1)) return true
                    }
                }
                return false
            }

            return@run hasModelFiles(dir)
        }
        Log.d(TAG, "isValidVoskModelDir: result=$result for ${dir.absolutePath}")
        return result
    }

    private fun isValidWhisperModelDir(dir: File): Boolean {
        Log.d(TAG, "isValidWhisperModelDir: checking ${dir.absolutePath}, exists=${dir.exists()}, files=${dir.listFiles()?.map { it.name }}")
        val result = run {
            if (!dir.isDirectory) return@run false
            // For whisper-engine directory, check that all 4 required .so files exist (anywhere in
            // the dir, to be robust against different zip internal layouts)
            if (dir.name == "whisper-engine") {
                val requiredFiles = listOf("libwhisper.so", "libggml-base-whisper.so", "libggml-cpu-whisper.so", "libggml-whisper.so")
                return@run requiredFiles.all { requiredFile ->
                    dir.walkTopDown().any { it.isFile && it.name == requiredFile }
                }
            }
            // Check for ggml*.bin files in this dir or subdirs
            fun findBinFile(d: File, depth: Int = 0): Boolean {
                if (depth > 2) return false
                val files = d.listFiles() ?: return false
                for (f in files) {
                    if (f.isFile && f.name.endsWith(".bin") &&
                        (f.name.startsWith("ggml") || f.name.contains("whisper", ignoreCase = true)) &&
                        f.length() > 100_000) { // At least 100KB
                        return true
                    }
                    if (f.isDirectory && findBinFile(f, depth + 1)) return true
                }
                return false
            }
            return@run findBinFile(dir)
        }
        Log.d(TAG, "isValidWhisperModelDir: result=$result for ${dir.absolutePath}")
        return result
    }

    // [v2.4.31] Check if MNN model directory is valid - all required files must exist
    // Also checks that .so files exist in the sibling mnn-libs directory
    private fun isValidMnnModelDir(dir: File): Boolean {
        Log.d(TAG, "isValidMnnModelDir: checking ${dir.absolutePath}, exists=${dir.exists()}, files=${dir.listFiles()?.map { "${it.name}(${it.length()})" }}")
        writeEngineLog("isValidMnnModelDir: checking ${dir.absolutePath}, files=${dir.listFiles()?.map { "${it.name}(${it.length()})" }}")
        val result = run {
            if (!dir.isDirectory) return@run false
            val hasLlmMnn = dir.walkTopDown().any { it.isFile && it.name == "llm.mnn" && it.length() > 100_000 }
            val hasWeight = dir.walkTopDown().any { it.isFile && it.name == "llm.mnn.weight" && it.length() > 100_000_000 }
            val hasConfig = dir.walkTopDown().any { it.isFile && (it.name == "config.json" || it.name == "llm.mnn.json") }
            // v2.4.31: Also check for .so files in mnn-libs directory
            val libsDir = File(dir.parentFile, "mnn-libs")
            val hasLibllm = File(libsDir, "libllm.so").exists()
            if (!hasLlmMnn) writeEngineLog("isValidMnnModelDir: MISSING llm.mnn (>100KB)")
            if (!hasWeight) writeEngineLog("isValidMnnModelDir: MISSING llm.mnn.weight (>100MB)")
            if (!hasConfig) writeEngineLog("isValidMnnModelDir: MISSING config.json or llm.mnn.json")
            if (!hasLibllm) writeEngineLog("isValidMnnModelDir: MISSING libllm.so in mnn-libs")
            return@run hasLlmMnn && hasWeight && hasConfig && hasLibllm
        }
        Log.d(TAG, "isValidMnnModelDir: result=$result")
        writeEngineLog("isValidMnnModelDir: result=$result")
        return result
    }

    // v2.4.34: Download MNN .so files using jsDelivr CDN (works in China)
    // Falls back to GitHub direct if jsDelivr fails
    private fun downloadAndExtractMnnLibs(libsDir: File): Boolean {
        try {
            if (!libsDir.exists()) libsDir.mkdirs()

            // v2.4.34: jsDelivr CDN has China CDN nodes - much faster than GitHub direct
            val baseUrl = "https://cdn.jsdelivr.net/gh/bingo17368222/RadioApp@main/mnn_libs"
            val fallbackUrl = "https://github.com/bingo17368222/RadioApp/releases/download/mnn-libs-v1"
            val requiredLibs = listOf(
                "libMNN.so", "libMNN_Express.so", "libMNN_Vulkan.so", "libMNN_CL.so",
                "libMNNOpenCV.so", "libMNNAudio.so", "libmnncore.so", "libllm.so"
            )

            // v2.4.38: Known-good file sizes for verification.
            // If existing file doesn't match, delete and re-download.
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

            var downloadedCount = 0
            for (libName in requiredLibs) {
                if (downloadCancelled) return false

                val outFile = File(libsDir, libName)
                // v2.4.38: Verify file size against known-good size.
                // The v2.4.36 truncated libMNN_CL.so (2120998 vs 2212056) was
                // being skipped because it "already exists". Now we verify.
                val expectedSize = knownGoodSizes[libName] ?: 1000L
                if (outFile.exists() && outFile.length() == expectedSize) {
                    writeEngineLog("downloadAndExtractMnnLibs: $libName verified OK (${outFile.length()} bytes)")
                    downloadedCount++
                    continue
                }
                if (outFile.exists() && outFile.length() != expectedSize) {
                    writeEngineLog("downloadAndExtractMnnLibs: $libName SIZE MISMATCH (expected=$expectedSize actual=${outFile.length()}), re-downloading")
                    outFile.delete()
                }

                // v2.4.34: Try jsDelivr CDN first, then GitHub release
                val urls = listOf("$baseUrl/$libName", "$fallbackUrl/$libName")
                var success = false
                for ((urlIdx, url) in urls.withIndex()) {
                    if (downloadCancelled) return false
                    val source = if (urlIdx == 0) "jsDelivr" else "GitHub"
                    writeEngineLog("downloadAndExtractMnnLibs: downloading $libName from $source")

                    val maxRetries = 3
                    for (attempt in 1..maxRetries) {
                        if (downloadCancelled) return false
                        try {
                            val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                                connectTimeout = 15000
                                // v2.4.37: Increased from 60s to 120s - 60s was too short for
                                // slow China connections, causing truncated downloads
                                readTimeout = 120000
                                instanceFollowRedirects = true
                            }
                            val responseCode = connection.responseCode
                            if (responseCode != 200) {
                                writeEngineLog("downloadAndExtractMnnLibs: $libName ($source) HTTP $responseCode (attempt $attempt)")
                                connection.disconnect()
                                continue
                            }

                            // v2.4.37: Get expected file size from Content-Length
                            val expectedSize = connection.contentLengthLong
                            writeEngineLog("downloadAndExtractMnnLibs: $libName ($source) Content-Length=$expectedSize")

                            val input = connection.inputStream
                            val output = java.io.FileOutputStream(outFile)
                            val buffer = ByteArray(16384)
                            var bytesRead: Int
                            var totalRead = 0L
                            while (true) {
                                if (downloadCancelled) {
                                    input.close(); output.close(); connection.disconnect()
                                    return false
                                }
                                bytesRead = input.read(buffer)
                                if (bytesRead == -1) break
                                output.write(buffer, 0, bytesRead)
                                totalRead += bytesRead
                            }
                            input.close(); output.close(); connection.disconnect()

                            // v2.4.37: Verify downloaded file size matches Content-Length
                            if (expectedSize > 0 && outFile.length() != expectedSize) {
                                writeEngineLog("downloadAndExtractMnnLibs: $libName ($source) SIZE MISMATCH: expected=$expectedSize actual=${outFile.length()}")
                                outFile.delete()
                                continue
                            }

                            if (outFile.length() < 1000) {
                                writeEngineLog("downloadAndExtractMnnLibs: $libName ($source) too small (${outFile.length()})")
                                outFile.delete()
                                continue
                            }

                            writeEngineLog("downloadAndExtractMnnLibs: $libName ($source) downloaded OK (${outFile.length()} bytes)")
                            success = true
                            downloadedCount++
                            break
                        } catch (e: Exception) {
                            writeEngineLog("downloadAndExtractMnnLibs: $libName ($source) attempt $attempt failed: ${e.message}")
                        }
                        if (attempt < maxRetries) Thread.sleep(2000L * attempt)
                    }
                    if (success) break
                }

                if (!success) {
                    writeEngineLog("downloadAndExtractMnnLibs: FAILED to download $libName from all sources")
                    return false
                }
            }

            writeEngineLog("downloadAndExtractMnnLibs: all $downloadedCount .so files downloaded")
            return downloadedCount == requiredLibs.size
        } catch (e: Exception) {
            writeEngineLog("downloadAndExtractMnnLibs: ERROR: ${e.message}")
            return false
        }
    }

    private fun getDirTotalSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var size = 0L
        dir.listFiles()?.forEach { f ->
            size += if (f.isDirectory) getDirTotalSize(f) else f.length()
        }
        return size
    }

    // Issue 12: 检查 libvosk.so 是否已安装（在 models 或 engines 目录下）
    private fun isVoskEngineInstalled(): Boolean {
        val modelsDir = getExternalFilesDir("models")
        val engineDir = File(filesDir, "engines")
        return (modelsDir?.walkTopDown()?.any { it.name == "libvosk.so" } == true) ||
               (engineDir.walkTopDown()?.any { it.name == "libvosk.so" } == true)
    }

    // Issue 12: 检查 Whisper 引擎4个.so文件是否已安装（在 models 或 engines 目录下）
    private fun isWhisperEngineInstalled(): Boolean {
        val modelsDir = getExternalFilesDir("models")
        val engineDir = File(filesDir, "engines")
        val requiredFiles = listOf("libwhisper.so", "libggml-base-whisper.so", "libggml-cpu-whisper.so", "libggml-whisper.so")
        fun hasAllSoFiles(root: File?): Boolean {
            if (root == null || !root.exists()) return false
            return requiredFiles.all { required ->
                root.walkTopDown().any { it.isFile && it.name == required }
            }
        }
        return hasAllSoFiles(modelsDir) || hasAllSoFiles(engineDir)
    }

    // Issue 13: 写入引擎管理日志到文件，便于排查下载/安装问题
    private fun writeEngineLog(message: String) {
        try {
            // v2.4.37: Use unified log directory
            val logDir = File(com.radio.app.RadioApplication.getLogDir(this), "engine")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = File(logDir, "engine.log")
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
            logFile.appendText("[$timestamp] $message\n")
        } catch (_: Exception) {}
    }
}
