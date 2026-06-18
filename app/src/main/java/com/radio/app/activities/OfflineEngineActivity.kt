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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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

    private data class EngineInfo(
        val name: String,
        val desc: String,
        val size: String,
        val downloadUrl: String?,
        val modelDir: String
    )

    private val engines = arrayOf(
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

        // ===== Vosk 离线语音识别（APK内置 + 可下载） =====
        EngineInfo(
            "Vosk 小模型 (中文)",
            "Vosk small-cn 中文模型\n大小: 约42MB | 识别率: ~88% | 速度: 快(实时)\n状态: APK内置，无需下载\n适用: 中文语音识别，低资源消耗",
            "约42MB (内置)",
            null,
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
            "Vosk medium-en 英文模型\n大小: 约280MB | 识别率: ~93% | 速度: 中等\n适用: 高精度英文语音识别",
            "约280MB",
            "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22.zip",
            "vosk-model-en-us-0.22"
        ),
        EngineInfo(
            "Vosk 大模型 (英文)",
            "Vosk large-en 英文大模型\n大小: 约1.8GB | 识别率: ~96% | 速度: 较慢\n适用: 最高精度英文语音识别",
            "约1.8GB",
            "https://alphacephei.com/vosk/models/vosk-model-en-us-daanzu-0.22.zip",
            "vosk-model-en-us-daanzu-0.22"
        ),

        // ===== 阿里 MNN-LLM (APK内置) =====
        EngineInfo(
            "阿里 MNN-LLM (设备端)",
            "阿里巴巴 MNN 推理引擎\n大小: APK内置 | 推理速度: 快(Tensor加速)\n状态: 已集成，无需下载\n适用: 本地AI内容分析、干货/水分分类",
            "内置",
            null,
            "mnn-llm"
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

        for (engine in engines) {
            setupEngineCard(engine)
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

        val isBuiltin = engine.downloadUrl == null
        if (isBuiltin) {
            btnAction.text = "已内置"
            btnAction.isEnabled = false
            btnAction.alpha = 0.6f
        } else {
            val modelsDir = getExternalFilesDir("models")
            val modelDir = modelsDir?.let { File(it, engine.modelDir) }
            val installed = modelDir?.exists() == true && getDirTotalSize(modelDir) >= MIN_INSTALL_SIZE
            if (installed) {
                btnAction.text = "已安装(删除)"
                btnAction.setOnClickListener {
                    modelDir?.let { deleteRecursive(it) }
                    btnAction.text = "安装"
                    Toast.makeText(this, "${engine.name} 已删除", Toast.LENGTH_SHORT).show()
                }
            } else {
                // 如果目录存在但文件不完整，清理残留
                if (modelDir?.exists() == true) {
                    deleteRecursive(modelDir)
                }
                btnAction.text = "安装"
                btnAction.setOnClickListener {
                    btnAction.isEnabled = false
                    btnAction.text = "准备下载..."
                    progressBar?.visibility = View.VISIBLE
                    progressBar?.progress = 0
                    downloadModel(engine, btnAction, progressBar)
                }
            }
        }

        val container = findViewById<LinearLayout>(R.id.engine_container)
        container?.addView(card)
    }

    private fun downloadModel(engine: EngineInfo, btn: Button, progressBar: ProgressBar?) {
        lifecycleScope.launch {
            try {
                val modelsDir = withContext(Dispatchers.IO) {
                    getExternalFilesDir("models")
                }
                if (modelsDir == null) {
                    btn.isEnabled = true
                    btn.text = "安装"
                    return@launch
                }
                if (!modelsDir.exists()) {
                    modelsDir.mkdirs()
                }

                val downloadUrl = engine.downloadUrl ?: return@launch
                val fileName = downloadUrl.substring(downloadUrl.lastIndexOf('/') + 1)
                val outFile = File(modelsDir, "${engine.modelDir}/$fileName")
                outFile.parentFile?.mkdirs()

                btn.text = "连接中..."

                val result = withContext(Dispatchers.IO) {
                    var conn: HttpURLConnection? = null
                    var fos: FileOutputStream? = null
                    try {
                        var downloadUrlStr = downloadUrl
                        // Bug 9: 手动处理 3xx 重定向（HuggingFace 使用 307/308）
                        var redirectCount = 0
                        while (redirectCount < 5) {
                            val url = URL(downloadUrlStr)
                            conn = url.openConnection() as HttpURLConnection
                            conn.requestMethod = "GET"
                            conn.connectTimeout = 60000
                            conn.readTimeout = 300000
                            conn.instanceFollowRedirects = false  // 手动处理重定向
                            // 设置 User-Agent，模拟浏览器以避免服务器拒绝
                            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")

                            val rc = conn.responseCode
                            Log.d(TAG, "Download response code: $rc for ${engine.name} (redirect=$redirectCount)")
                            if (rc in 300..399) {
                                val location = conn.getHeaderField("Location")
                                conn.disconnect()
                                conn = null
                                if (location.isNullOrBlank()) {
                                    throw Exception("重定向但无 Location 头 (HTTP $rc)")
                                }
                                // 处理相对 URL
                                downloadUrlStr = if (location.startsWith("http")) {
                                    location
                                } else {
                                    URL(URL(downloadUrlStr), location).toString()
                                }
                                redirectCount++
                                continue
                            }
                            if (rc != 200) {
                                throw Exception("HTTP $rc")
                            }
                            break
                        }

                        val totalSize = conn?.contentLength ?: -1
                        Log.d(TAG, "Download total size: $totalSize for ${engine.name}")

                        val input = conn?.inputStream ?: throw Exception("连接失败")
                        fos = FileOutputStream(outFile)
                        val buffer = ByteArray(8192)
                        var len: Int
                        var downloaded = 0
                        var lastUpdate = System.currentTimeMillis()
                        var lastDownloaded = 0L
                        var startTime = System.currentTimeMillis()

                        while (input.read(buffer).also { len = it } != -1) {
                            fos.write(buffer, 0, len)
                            downloaded += len
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 500) {
                                lastUpdate = now
                                val progress: Int
                                val progressText: String
                                if (totalSize > 0) {
                                    progress = (downloaded * 100 / totalSize)
                                    // 计算下载速度和剩余时间
                                    val elapsed = (now - startTime) / 1000.0
                                    val speed = if (elapsed > 0) downloaded / elapsed else 0.0 // bytes/sec
                                    val remaining = if (speed > 0) (totalSize - downloaded) / speed else 0.0 // seconds
                                    val speedStr = if (speed >= 1024 * 1024) {
                                        String.format("%.1f MB/s", speed / (1024.0 * 1024))
                                    } else {
                                        String.format("%.0f KB/s", speed / 1024.0)
                                    }
                                    val remainStr = if (remaining >= 3600) {
                                        String.format("%.0f小时%.0f分", remaining / 3600, (remaining % 3600) / 60)
                                    } else if (remaining >= 60) {
                                        String.format("%.0f分%.0f秒", remaining / 60, remaining % 60)
                                    } else {
                                        String.format("%.0f秒", remaining)
                                    }
                                    // 大文件(>500MB)显示速度和剩余时间
                                    if (totalSize > 500 * 1024 * 1024) {
                                        progressText = "下载: $progress% | $speedStr | 剩余$remainStr"
                                    } else {
                                        progressText = "下载: $progress%"
                                    }
                                } else {
                                    progress = downloaded / 1024
                                    progressText = "已下载: $progress KB"
                                }
                                withContext(Dispatchers.Main) {
                                    btn.text = progressText
                                    progressBar?.progress = progress
                                }
                            }
                        }

                        if (outFile.length() < 1024) {
                            throw Exception("下载文件过小: ${outFile.length()} bytes")
                        }

                        withContext(Dispatchers.Main) {
                            btn.text = "下载完成"
                        }

                        if (fileName.endsWith(".zip")) {
                            withContext(Dispatchers.Main) {
                                btn.text = "解压中..."
                            }
                            unzipFile(outFile, File(modelsDir, engine.modelDir))
                            outFile.delete()
                        }

                        withContext(Dispatchers.Main) {
                            btn.isEnabled = true
                            btn.text = "已安装(删除)"
                            progressBar?.visibility = View.GONE
                            Toast.makeText(this@OfflineEngineActivity, "${engine.name} 安装完成", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Download failed: ${e.message}", e)
                        // 下载失败，删除残留文件
                        try {
                            val modelDir = File(modelsDir, engine.modelDir)
                            if (modelDir.exists()) {
                                deleteRecursive(modelDir)
                            }
                        } catch (_: Exception) {
                            // ignored
                        }
                        withContext(Dispatchers.Main) {
                            btn.isEnabled = true
                            btn.text = "安装(重试)"
                            progressBar?.visibility = View.GONE
                            Toast.makeText(this@OfflineEngineActivity, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    } finally {
                        try {
                            fos?.close()
                        } catch (_: Exception) {
                            // ignored
                        }
                        conn?.disconnect()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download coroutine failed: ${e.message}", e)
            }
        }
    }

    private fun unzipFile(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            val buffer = ByteArray(8192)
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
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
    }

    private fun deleteRecursive(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                deleteRecursive(child)
            }
        }
        file.delete()
    }

    private fun getDirTotalSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var size = 0L
        dir.listFiles()?.forEach { f ->
            size += if (f.isDirectory) getDirTotalSize(f) else f.length()
        }
        return size
    }
}
