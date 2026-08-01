package com.radio.app.activities

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.radio.app.R
import com.radio.app.adapters.AudioFingerprintAdapter
import com.radio.app.database.AudioFingerprint
import com.radio.app.database.RadioDatabaseHelper
import com.radio.app.models.AppSettings
import com.radio.app.services.AudioFingerprintService
import com.radio.app.utils.ChromaprintExtractor
import com.radio.app.utils.PcmSegmentExtractor
import com.radio.app.utils.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v3.0.2: 音频指纹管理页。
 * 展示用户通过“添加为水分指纹”保存的音频指纹素材，支持删除和修正（重新提取）。
 * v3.0.4: 支持播放水印指纹片段 PCM；PCM 缺失时可从缓存重新生成。
 * v3.0.5: 接收指纹服务广播，自动刷新列表并提示结果。
 */
class KeywordSettingsActivity : AppCompatActivity() {

    private lateinit var prefMgr: PreferenceManager
    private lateinit var settings: AppSettings
    private lateinit var tvTitle: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var recyclerFingerprints: RecyclerView
    private lateinit var fingerprintAdapter: AudioFingerprintAdapter
    private lateinit var tvFingerprintCount: TextView
    private lateinit var tvFingerprintsEmpty: TextView

    // 水货分段开头/结尾组合管理（保留，供字幕提取水货组合使用）
    private lateinit var etCombinationStart: EditText
    private lateinit var etCombinationEnd: EditText
    private lateinit var chipGroupCombinations: ChipGroup
    private lateinit var tvCombinationsEmpty: TextView
    private val waterCombinationList: MutableList<Pair<String, String>> = mutableListOf()

    private val uiHandler = Handler(Looper.getMainLooper())
    private var reloadRunnable: Runnable? = null

    // v3.0.4: PCM 播放
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null

    // v3.0.5: 接收指纹服务广播，自动刷新列表并提示结果
    private val fingerprintReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                AudioFingerprintService.ACTION_FINGERPRINT_ADDED -> {
                    loadFingerprints()
                    Toast.makeText(this@KeywordSettingsActivity, "水分指纹已添加", Toast.LENGTH_SHORT).show()
                }
                AudioFingerprintService.ACTION_FINGERPRINT_ERROR -> {
                    val msg = intent.getStringExtra(AudioFingerprintService.EXTRA_FINGERPRINT_MESSAGE) ?: "添加指纹失败"
                    loadFingerprints()
                    Toast.makeText(this@KeywordSettingsActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    companion object {
        private const val TAG = "KeywordSettingsActivity"
        private const val SAMPLE_RATE = 16000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyword_settings)

        tvTitle = findViewById(R.id.tv_title)
        btnBack = findViewById(R.id.btn_back)
        tvTitle.text = "音频指纹管理"
        btnBack.setOnClickListener { finish() }

        prefMgr = PreferenceManager(this)
        settings = prefMgr.loadSettings()

        initFingerprintList()
        initWaterCombinationManagement()

        // v3.0.5: 注册指纹服务广播
        try {
            LocalBroadcastManager.getInstance(this).registerReceiver(
                fingerprintReceiver,
                IntentFilter().apply {
                    addAction(AudioFingerprintService.ACTION_FINGERPRINT_ADDED)
                    addAction(AudioFingerprintService.ACTION_FINGERPRINT_ERROR)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "register fingerprintReceiver failed: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        loadFingerprints()
    }

    override fun onDestroy() {
        super.onDestroy()
        reloadRunnable?.let { uiHandler.removeCallbacks(it) }
        releaseAudioTrack()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(fingerprintReceiver)
        } catch (_: Exception) {}
    }

    // ==================== 音频指纹管理 ====================

    private fun initFingerprintList() {
        recyclerFingerprints = findViewById(R.id.recycler_audio_fingerprints)
        tvFingerprintCount = findViewById(R.id.tv_fingerprint_count)
        tvFingerprintsEmpty = findViewById(R.id.tv_fingerprints_empty)

        fingerprintAdapter = AudioFingerprintAdapter()
        recyclerFingerprints.layoutManager = LinearLayoutManager(this)
        recyclerFingerprints.adapter = fingerprintAdapter

        fingerprintAdapter.setOnDeleteListener { fp ->
            AlertDialog.Builder(this)
                .setTitle("删除音频指纹")
                .setMessage("确定删除该水分音频指纹素材吗？")
                .setPositiveButton("删除") { _, _ ->
                    try {
                        RadioDatabaseHelper.getInstance(this).deleteAudioFingerprint(fp.id)
                        // 同步删除对应的水印 PCM 文件
                        PcmSegmentExtractor.getWatermarkPcmFile(this, fp.episodeId, fp.startMs, fp.endMs)
                            .delete()
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                        loadFingerprints()
                    } catch (e: Exception) {
                        Toast.makeText(this, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        fingerprintAdapter.setOnRefreshListener { fp ->
            // 修正：以原起止时间重新截取片段、提取指纹并更新数据库
            AudioFingerprintService.startAddFingerprint(
                this,
                episodeId = fp.episodeId,
                startMs = fp.startMs,
                endMs = fp.endMs,
                episodeTitle = null
            )
            Toast.makeText(this, "已开始修正指纹，请查看通知栏进度", Toast.LENGTH_SHORT).show()
            // 延迟刷新列表，等待后台服务完成
            reloadRunnable?.let { uiHandler.removeCallbacks(it) }
            reloadRunnable = Runnable { loadFingerprints() }
            uiHandler.postDelayed(reloadRunnable!!, 3000)
        }

        fingerprintAdapter.setOnPlayListener { fp ->
            playFingerprintPcm(fp)
        }

        // v3.0.9: 绑定停止播放逻辑，播放按钮点击“停止”时真正停止
        fingerprintAdapter.setOnStopListener {
            releaseAudioTrack()
            fingerprintAdapter.stopPlaying()
        }

        // v3.0.9: 点击条目可高亮选中，方便用户确认当前操作对象
        fingerprintAdapter.setOnItemClickListener { fp, _ ->
            val fpSize = ChromaprintExtractor.parseFingerprint(fp.fingerprint).size
            Toast.makeText(this, "已选中：${fp.episodeId}\n指纹点数：$fpSize", Toast.LENGTH_SHORT).show()
        }

        fingerprintAdapter.setOnTestListener { fp ->
            showFingerprintTestDialog(fp)
        }

        // v3.1.3: 备注失焦自动保存
        fingerprintAdapter.setOnNoteUpdateListener { fp, note ->
            try {
                RadioDatabaseHelper.getInstance(this).updateFingerprintNote(fp.id, note)
                Toast.makeText(this, "备注已保存", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "备注保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // v3.1.2: 批量测试按钮
        findViewById<android.widget.Button>(R.id.btn_test_all_fingerprints)?.setOnClickListener {
            runAllFingerprintsVsAllPcmTest()
        }
    }

    private fun loadFingerprints() {
        try {
            val fingerprints = RadioDatabaseHelper.getInstance(this).getAllAudioFingerprints()
            fingerprintAdapter.setItems(fingerprints)
            tvFingerprintCount.text = "已保存 ${fingerprints.size} 条指纹"
            if (fingerprints.isEmpty()) {
                tvFingerprintsEmpty.visibility = TextView.VISIBLE
                recyclerFingerprints.visibility = RecyclerView.GONE
            } else {
                tvFingerprintsEmpty.visibility = TextView.GONE
                recyclerFingerprints.visibility = RecyclerView.VISIBLE
            }
        } catch (e: Exception) {
            Toast.makeText(this, "加载指纹失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 水印指纹 PCM 播放 ====================

    private fun playFingerprintPcm(fp: AudioFingerprint) {
        val pcmFile = PcmSegmentExtractor.getWatermarkPcmFile(this, fp.episodeId, fp.startMs, fp.endMs)
        if (pcmFile.exists() && pcmFile.length() > 0) {
            playPcmFile(pcmFile)
            return
        }

        // PCM 不存在，尝试从完整 PCM 缓存重新生成
        Toast.makeText(this, "PCM 已删除，正在重新生成...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val regenerated = withContext(Dispatchers.IO) {
                PcmSegmentExtractor.extractWatermarkPcm(this@KeywordSettingsActivity, fp.episodeId, fp.startMs, fp.endMs)
            }
            if (regenerated != null && regenerated.exists() && regenerated.length() > 0) {
                playPcmFile(regenerated)
            } else {
                Toast.makeText(this@KeywordSettingsActivity, "重新生成 PCM 失败（缺少原始缓存）", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playPcmFile(pcmFile: File) {
        releaseAudioTrack()
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = (minBufferSize * 2).coerceAtLeast(8192)
            audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            audioTrack?.play()

            playbackThread = Thread {
                try {
                    pcmFile.inputStream().use { fis ->
                        val buffer = ByteArray(8192)
                        while (!Thread.currentThread().isInterrupted) {
                            val read = fis.read(buffer)
                            if (read <= 0) break
                            audioTrack?.write(buffer, 0, read)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "PCM playback error: ${e.message}")
                } finally {
                    try {
                        audioTrack?.stop()
                    } catch (_: Exception) {
                    }
                    try {
                        audioTrack?.release()
                    } catch (_: Exception) {
                    }
                    audioTrack = null
                    // v3.0.9: 播放结束或被打断后，回到“播放”按钮状态
                    uiHandler.post { fingerprintAdapter.stopPlaying() }
                }
            }.apply { start() }
        } catch (e: Exception) {
            Log.e(TAG, "playPcmFile failed: ${e.message}", e)
            Toast.makeText(this, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            releaseAudioTrack()
        }
    }

    private fun releaseAudioTrack() {
        try {
            playbackThread?.interrupt()
        } catch (_: Exception) {
        }
        playbackThread = null
        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }
        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
    }

    // ==================== 水印指纹自匹配测试 ====================

    private fun showFingerprintTestDialog(fp: AudioFingerprint) {
        val options = arrayOf("指纹 vs 自身 PCM", "指纹 vs 完整节目 PCM", "指纹 vs 所有完整 PCM", "所有指纹 vs 所有完整 PCM", "跨节目指纹直接对比")
        AlertDialog.Builder(this)
            .setTitle("音频指纹匹配测试")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> runFingerprintSelfTest(fp)
                    1 -> runFingerprintFullEpisodeTest(fp)
                    2 -> runFingerprintVsAllPcmTest(fp)
                    3 -> runAllFingerprintsVsAllPcmTest()
                    4 -> runCrossEpisodeFingerprintTest(fp)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runFingerprintSelfTest(fp: AudioFingerprint) {
        val pcmFile = PcmSegmentExtractor.getWatermarkPcmFile(this, fp.episodeId, fp.startMs, fp.endMs)
        if (!pcmFile.exists() || pcmFile.length() <= 0) {
            Toast.makeText(this, "自身 PCM 不存在，请先播放或重新生成", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (!ChromaprintExtractor.ensureLibraryLoaded(this@KeywordSettingsActivity)) {
                        return@withContext "Chromaprint 库未加载"
                    }
                    val refingerprint = ChromaprintExtractor.extractFingerprintFromFile(pcmFile)
                        ?: return@withContext "从自身 PCM 提取指纹失败"
                    val detail = ChromaprintExtractor.compareFingerprintsDetailed(fp.fingerprint, refingerprint)
                    val match = detail.similarity >= 0.70f
                    "自身 PCM 重提指纹相似度: %.2f%%\n是否匹配: %s\n原始指纹长度: %d\n重提指纹长度: %d\n最佳偏移: %d\n原始相似度(不含长度惩罚): %.2f%%\n位误差: %d/%d bits\n长度惩罚: %.2f".format(
                        detail.similarity * 100, if (match) "是" else "否",
                        detail.len1, detail.len2, detail.bestOffset,
                        detail.rawSimilarity * 100, detail.minErrors, detail.totalBits, detail.lengthPenalty
                    )
                }.getOrElse { "测试异常: ${it.message}" }
            }
            AlertDialog.Builder(this@KeywordSettingsActivity)
                .setTitle("指纹 vs 自身 PCM")
                .setMessage(result)
                .setPositiveButton("确定", null)
                .show()
        }
    }

    private fun runFingerprintFullEpisodeTest(fp: AudioFingerprint) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (!ChromaprintExtractor.ensureLibraryLoaded(this@KeywordSettingsActivity)) {
                        return@withContext "Chromaprint 库未加载"
                    }
                    val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this@KeywordSettingsActivity)
                    val fullPcm = File(pcmCacheDir, "${fp.episodeId}_full.pcm")
                    val min5Pcm = File(pcmCacheDir, "${fp.episodeId}_5min.pcm")
                    val sourceFile = when {
                        fullPcm.exists() && fullPcm.length() > 0 -> fullPcm
                        min5Pcm.exists() && min5Pcm.length() > 0 -> min5Pcm
                        else -> return@withContext "未找到完整节目 PCM 缓存"
                    }
                    val segmentPcm = PcmSegmentExtractor.extractSegmentFromFile(sourceFile, fp.startMs, fp.endMs)
                        ?: return@withContext "从完整节目截取片段失败"
                    val refingerprint = ChromaprintExtractor.extractFingerprintFromFile(segmentPcm)
                        ?: return@withContext "从完整节目 PCM 提取指纹失败"
                    segmentPcm.delete()
                    val detail = ChromaprintExtractor.compareFingerprintsDetailed(fp.fingerprint, refingerprint)
                    val match = detail.similarity >= 0.70f
                    "完整节目 PCM 同片段相似度: %.2f%%\n是否匹配: %s\n原始指纹长度: %d\n重提指纹长度: %d\n最佳偏移: %d\n原始相似度: %.2f%%\n位误差: %d/%d\n长度惩罚: %.2f\n源文件: %s".format(
                        detail.similarity * 100, if (match) "是" else "否",
                        detail.len1, detail.len2, detail.bestOffset,
                        detail.rawSimilarity * 100, detail.minErrors, detail.totalBits,
                        detail.lengthPenalty, sourceFile.name
                    )
                }.getOrElse { "测试异常: ${it.message}" }
            }
            AlertDialog.Builder(this@KeywordSettingsActivity)
                .setTitle("指纹 vs 完整节目 PCM")
                .setMessage(result)
                .setPositiveButton("确定", null)
                .show()
        }
    }

    /**
     * v3.1.2: 单指纹 vs 所有完整 PCM。
     * 扫描 PCM 缓存目录中所有 *_full.pcm 文件，对每个文件截取指纹对应时间段并比对。
     */
    private fun runFingerprintVsAllPcmTest(fp: AudioFingerprint) {
        lifecycleScope.launch {
            val progressDialog = android.app.ProgressDialog(this@KeywordSettingsActivity).apply {
                setMessage("正在测试指纹 vs 所有完整 PCM...")
                setCancelable(false)
                show()
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (!ChromaprintExtractor.ensureLibraryLoaded(this@KeywordSettingsActivity)) {
                        return@withContext "Chromaprint 库未加载"
                    }
                    val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this@KeywordSettingsActivity)
                    val allPcmFiles = pcmCacheDir.listFiles { f -> f.name.endsWith("_full.pcm") && f.length() > 0 }
                        ?.sortedByDescending { it.lastModified() } ?: emptyList()
                    if (allPcmFiles.isEmpty()) return@withContext "未找到任何完整 PCM 缓存文件"

                    val sb = StringBuilder()
                    sb.append("指纹: ${fp.episodeId} [${fp.startMs}-${fp.endMs}]\n")
                    sb.append("共 ${allPcmFiles.size} 个完整 PCM 文件\n\n")

                    var matchCount = 0
                    for ((idx, pcmFile) in allPcmFiles.withIndex()) {
                        val episodeIdFromName = pcmFile.name.removeSuffix("_full.pcm")
                        try {
                            val segmentPcm = PcmSegmentExtractor.extractSegmentFromFile(pcmFile, fp.startMs, fp.endMs)
                            if (segmentPcm == null) {
                                sb.append("[${idx + 1}/${allPcmFiles.size}] $episodeIdFromName: 截取片段失败\n")
                                continue
                            }
                            val refingerprint = ChromaprintExtractor.extractFingerprintFromFile(segmentPcm)
                            segmentPcm.delete()
                            if (refingerprint == null) {
                                sb.append("[${idx + 1}/${allPcmFiles.size}] $episodeIdFromName: 提取指纹失败\n")
                                continue
                            }
                            val detail = ChromaprintExtractor.compareFingerprintsDetailed(fp.fingerprint, refingerprint)
                            val match = detail.similarity >= 0.70f
                            if (match) matchCount++
                            sb.append("[${idx + 1}/${allPcmFiles.size}] $episodeIdFromName: %.1f%% (偏移:%d, 原始:%.1f%%) %s\n".format(
                                detail.similarity * 100, detail.bestOffset, detail.rawSimilarity * 100,
                                if (match) "★匹配" else ""
                            ))
                        } catch (e: Exception) {
                            sb.append("[${idx + 1}/${allPcmFiles.size}] $episodeIdFromName: 异常 ${e.message}\n")
                        }
                    }
                    sb.append("\n匹配数: $matchCount / ${allPcmFiles.size}")
                    sb.toString()
                }.getOrElse { "测试异常: ${it.message}" }
            }
            progressDialog.dismiss()
            AlertDialog.Builder(this@KeywordSettingsActivity)
                .setTitle("指纹 vs 所有完整 PCM")
                .setMessage(result)
                .setPositiveButton("确定", null)
                .show()
        }
    }

    /**
     * v3.1.2: 所有指纹 vs 所有完整 PCM。
     * 对每条指纹，与所有完整 PCM 文件进行比对，输出匹配矩阵。
     */
    private fun runAllFingerprintsVsAllPcmTest() {
        val allFps = RadioDatabaseHelper.getInstance(this).getAllAudioFingerprints()
        if (allFps.isEmpty()) {
            Toast.makeText(this, "暂无指纹", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val progressDialog = android.app.ProgressDialog(this@KeywordSettingsActivity).apply {
                setMessage("正在测试所有指纹 vs 所有完整 PCM...")
                setCancelable(false)
                show()
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (!ChromaprintExtractor.ensureLibraryLoaded(this@KeywordSettingsActivity)) {
                        return@withContext "Chromaprint 库未加载"
                    }
                    val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this@KeywordSettingsActivity)
                    val allPcmFiles = pcmCacheDir.listFiles { f -> f.name.endsWith("_full.pcm") && f.length() > 0 }
                        ?.sortedByDescending { it.lastModified() } ?: emptyList()
                    if (allPcmFiles.isEmpty()) return@withContext "未找到任何完整 PCM 缓存文件"

                    val sb = StringBuilder()
                    sb.append("指纹数: ${allFps.size}, PCM 文件数: ${allPcmFiles.size}\n\n")

                    var totalMatch = 0
                    var totalTest = 0
                    for ((fpIdx, fp) in allFps.withIndex()) {
                        sb.append("--- 指纹 ${fpIdx + 1}: ${fp.episodeId} [${fp.startMs}-${fp.endMs}] ---\n")
                        for (pcmFile in allPcmFiles) {
                            val episodeIdFromName = pcmFile.name.removeSuffix("_full.pcm")
                            totalTest++
                            try {
                                val segmentPcm = PcmSegmentExtractor.extractSegmentFromFile(pcmFile, fp.startMs, fp.endMs)
                                if (segmentPcm == null) continue
                                val refingerprint = ChromaprintExtractor.extractFingerprintFromFile(segmentPcm)
                                segmentPcm.delete()
                                if (refingerprint == null) continue
                                val detail = ChromaprintExtractor.compareFingerprintsDetailed(fp.fingerprint, refingerprint)
                                val match = detail.similarity >= 0.70f
                                if (match) {
                                    totalMatch++
                                    sb.append("  ★ $episodeIdFromName: %.1f%% (偏移:%d)\n".format(detail.similarity * 100, detail.bestOffset))
                                } else if (detail.similarity > 0.5f) {
                                    sb.append("  ~ $episodeIdFromName: %.1f%% (偏移:%d)\n".format(detail.similarity * 100, detail.bestOffset))
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    sb.append("\n总测试: $totalTest, 匹配: $totalMatch")
                    sb.toString()
                }.getOrElse { "测试异常: ${it.message}" }
            }
            progressDialog.dismiss()
            AlertDialog.Builder(this@KeywordSettingsActivity)
                .setTitle("所有指纹 vs 所有完整 PCM")
                .setMessage(result)
                .setPositiveButton("确定", null)
                .show()
        }
    }

    /**
     * v3.1.3: 跨节目指纹直接对比。
     * 将选中指纹与数据库中所有其他指纹直接比较（不经过 PCM 重新提取），
     * 用于诊断"人耳听不出差别但无法跨节目匹配"的问题。
     * 如果直接对比也不匹配，说明指纹提取阶段就产生了差异（PCM 数据不同或提取参数不一致）。
     * 如果直接对比匹配但 PCM 重提不匹配，说明是 PCM 截取存在时间偏移。
     */
    private fun runCrossEpisodeFingerprintTest(fp: AudioFingerprint) {
        val allFps = RadioDatabaseHelper.getInstance(this).getAllAudioFingerprints()
        val others = allFps.filter { it.id != fp.id }
        if (others.isEmpty()) {
            Toast.makeText(this, "数据库中只有这一条指纹，无法跨节目对比", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val sb = StringBuilder()
                    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(fp.createdAt))
                    sb.append("基准指纹: ${fp.episodeId}\n")
                    sb.append("  时间: ${formatMs(fp.startMs)}-${formatMs(fp.endMs)} (${fp.durationMs / 1000}s)\n")
                    sb.append("  创建: $dateStr\n")
                    sb.append("  指纹点数: ${ChromaprintExtractor.parseFingerprint(fp.fingerprint).size}\n")
                    sb.append("  备注: ${fp.note.ifEmpty { "(无)" }}\n")
                    sb.append("\n对比 ${others.size} 条其他指纹:\n\n")

                    var matchCount = 0
                    // 同时长优先（durationMs 相近）
                    val sorted = others.sortedBy { kotlin.math.abs(it.durationMs - fp.durationMs) }

                    for (other in sorted) {
                        val otherDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(other.createdAt))
                        val detail = ChromaprintExtractor.compareFingerprintsDetailed(fp.fingerprint, other.fingerprint)
                        val match = detail.similarity >= 0.70f
                        val sameDuration = kotlin.math.abs(other.durationMs - fp.durationMs) < 1000
                        if (match) matchCount++

                        val marker = when {
                            match -> "★匹配"
                            detail.similarity > 0.5f -> "~接近"
                            else -> ""
                        }
                        val durMark = if (sameDuration) "[同时长]" else ""
                        sb.append("${other.episodeId} $durMark $marker\n")
                        sb.append("  时间: ${formatMs(other.startMs)}-${formatMs(other.endMs)} (${other.durationMs / 1000}s)\n")
                        sb.append("  创建: $otherDate\n")
                        sb.append("  相似度: %.2f%% (原始: %.2f%%)\n".format(detail.similarity * 100, detail.rawSimilarity * 100))
                        sb.append("  指纹长度: ${detail.len1} vs ${detail.len2}, 最佳偏移: ${detail.bestOffset}\n")
                        sb.append("  位误差: ${detail.minErrors}/${detail.totalBits} bits, 长度惩罚: ${detail.lengthPenalty}\n")
                        if (other.note.isNotEmpty()) {
                            sb.append("  备注: ${other.note}\n")
                        }
                        sb.append("\n")
                    }
                    sb.append("匹配数: $matchCount / ${others.size}")
                    sb.toString()
                }.getOrElse { "测试异常: ${it.message}" }
            }
            AlertDialog.Builder(this@KeywordSettingsActivity)
                .setTitle("跨节目指纹直接对比")
                .setMessage(result)
                .setPositiveButton("确定", null)
                .show()
        }
    }

    private fun formatMs(ms: Long): String {
        val s = (ms / 1000).toInt()
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    private fun initWaterCombinationManagement() {
        etCombinationStart = findViewById(R.id.et_combination_start)
        etCombinationEnd = findViewById(R.id.et_combination_end)
        chipGroupCombinations = findViewById(R.id.chip_group_combinations)
        tvCombinationsEmpty = findViewById(R.id.tv_combinations_empty)

        waterCombinationList.clear()
        waterCombinationList.addAll(settings.getWaterCombinations())

        findViewById<Button>(R.id.btn_add_combination).setOnClickListener { addWaterCombination() }
        etCombinationEnd.setOnEditorActionListener { _, _, _ -> addWaterCombination(); true }

        refreshCombinationChips()
    }

    private fun addWaterCombination() {
        val start = etCombinationStart.text.toString().trim()
        val end = etCombinationEnd.text.toString().trim()
        if (start.isEmpty() || end.isEmpty()) {
            Toast.makeText(this, "开头和结尾都不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        val combo = start to end
        if (waterCombinationList.contains(combo)) {
            Toast.makeText(this, "该组合已存在", Toast.LENGTH_SHORT).show()
            return
        }
        waterCombinationList.add(combo)
        settings.setWaterCombinations(this, waterCombinationList)
        etCombinationStart.text.clear()
        etCombinationEnd.text.clear()
        refreshCombinationChips()
        Toast.makeText(this, "已添加组合：$start ... $end", Toast.LENGTH_SHORT).show()
    }

    private fun removeWaterCombination(combo: Pair<String, String>) {
        waterCombinationList.remove(combo)
        settings.setWaterCombinations(this, waterCombinationList)
        refreshCombinationChips()
        Toast.makeText(this, "已删除组合：${combo.first} ... ${combo.second}", Toast.LENGTH_SHORT).show()
    }

    private fun refreshCombinationChips() {
        chipGroupCombinations.removeAllViews()
        for (combo in waterCombinationList) {
            val label = "${combo.first} ... ${combo.second}"
            chipGroupCombinations.addView(createKeywordChip(chipGroupCombinations, label) {
                removeWaterCombination(combo)
            })
        }
        tvCombinationsEmpty.visibility =
            if (waterCombinationList.isEmpty()) TextView.VISIBLE else TextView.GONE
    }

    private fun createKeywordChip(
        parent: android.view.ViewGroup,
        keyword: String,
        onClose: () -> Unit
    ): Chip {
        val chip = LayoutInflater.from(this)
            .inflate(R.layout.item_keyword_chip, parent, false) as? Chip
            ?: Chip(this).apply {
                setEnsureMinTouchTargetSize(false)
            }
        chip.text = keyword
        chip.isCloseIconVisible = true
        chip.setOnClickListener { /* 点击仅高亮，不删除 */ }
        chip.setOnCloseIconClickListener { onClose() }
        return chip
    }
}
