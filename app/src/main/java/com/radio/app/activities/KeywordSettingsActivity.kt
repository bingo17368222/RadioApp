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
import com.radio.app.utils.FingerprintTestNotificationHelper
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
 * v3.1.61: 恢复v3.1.41版本的音频指纹播放功能和测试功能组。
 * 移除后续版本引入的复杂播放/测试UI，恢复简洁方案。
 * v3.0.2: 音频指纹管理页。
 * 展示用户通过"添加为水分指纹"保存的音频指纹素材，支持删除和修正（重新提取）。
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

    // v3.1.8: 指纹测试取消广播接收器
    // v3.1.11: 改为内部接收器，可访问Activity状态，取消时关闭ProgressDialog
    private val fingerprintTestCancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action == FingerprintTestNotificationHelper.CANCEL_ACTION) {
                Log.d(TAG, "取消指纹测试")
                FingerprintTestNotificationHelper.setCancelled()
                FingerprintTestNotificationHelper.cancel(context)
                // 取消时关闭可能存在的ProgressDialog
                uiHandler.post { currentProgressDialog?.dismiss() }
            }
        }
    }
    // v3.1.11: 当前ProgressDialog引用，用于取消时关闭
    @Volatile
    private var currentProgressDialog: android.app.ProgressDialog? = null

    companion object {
        private const val TAG = "KeywordSettingsActivity"
        private const val SAMPLE_RATE = 16000

        // v3.1.9: 静态时间格式化方法，供 FingerprintGroupActivity 等外部调用
        fun formatMsStatic(ms: Long): String {
            val s = (ms / 1000).toInt()
            return String.format("%02d:%02d", s / 60, s % 60)
        }
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

        // v3.1.8: 注册指纹测试取消广播
        // v3.1.63: 改为系统广播接收器（取消按钮的PendingIntent发送系统广播，与LocalBroadcastManager不匹配）
        try {
            this.registerReceiver(
                fingerprintTestCancelReceiver,
                IntentFilter(FingerprintTestNotificationHelper.CANCEL_ACTION),
                Context.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            Log.e(TAG, "register fingerprintTestCancelReceiver failed: ${e.message}")
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
        try {
            this.unregisterReceiver(fingerprintTestCancelReceiver)
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

        // v3.1.6: 指纹分组管理入口
        findViewById<android.widget.Button>(R.id.btn_fingerprint_groups)?.setOnClickListener {
            startActivity(android.content.Intent(this, FingerprintGroupActivity::class.java))
        }

        // v3.2.3: 指纹分类管理入口（人工/候选/自动）
        findViewById<android.widget.Button>(R.id.btn_fingerprint_management)?.setOnClickListener {
            startActivity(android.content.Intent(this, FingerprintManagementActivity::class.java))
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
        playPcmFileWithSeekBar(pcmFile, pcmFile.length() / (16000 * 2) * 1000)
    }

    /**
     * v3.1.11: 带可拖动进度条的PCM播放。
     * 弹出对话框，包含SeekBar进度条、当前时间/总时间显示。
     * 支持拖动进度条跳转到任意位置播放。
     */
    @Volatile
    private var playbackSeekRequested: Long = -1L
    @Volatile
    private var playbackCurrentPositionMs: Long = 0L
    private var playbackTotalMs: Long = 0L
    private var playbackPcmFile: File? = null
    private val seekBarHandler = Handler(Looper.getMainLooper())
    private var seekBarUpdateRunnable: Runnable? = null

    private fun playPcmFileWithSeekBar(pcmFile: File, totalDurationMs: Long) {
        releaseAudioTrack()
        playbackPcmFile = pcmFile
        playbackTotalMs = totalDurationMs
        playbackCurrentPositionMs = 0L
        playbackSeekRequested = -1L

        // 构建SeekBar对话框
        val dialogBuilder = AlertDialog.Builder(this@KeywordSettingsActivity)
        val dialogLayout = android.widget.LinearLayout(this@KeywordSettingsActivity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val timeLayout = android.widget.LinearLayout(this@KeywordSettingsActivity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val tvCurrent = android.widget.TextView(this@KeywordSettingsActivity).apply {
            text = "00:00"
            textSize = 12f
        }
        val tvTotal = android.widget.TextView(this@KeywordSettingsActivity).apply {
            text = formatMs(totalDurationMs)
            textSize = 12f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.END }
        }
        // 使用weight让total靠右
        val spacer = android.widget.Space(this@KeywordSettingsActivity).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, 0, 1f)
        }
        timeLayout.addView(tvCurrent)
        timeLayout.addView(spacer)
        timeLayout.addView(tvTotal)
        dialogLayout.addView(timeLayout)

        val seekBar = android.widget.SeekBar(this@KeywordSettingsActivity).apply {
            max = totalDurationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            progress = 0
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // 用户拖动时暂停播放并跳转
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        tvCurrent.text = formatMs(progress.toLong())
                    }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    val seekPos = sb?.progress?.toLong() ?: return
                    playbackSeekRequested = seekPos
                }
            })
        }
        dialogLayout.addView(seekBar)

        val btnClose = android.widget.Button(this@KeywordSettingsActivity).apply {
            text = "停止"
            setOnClickListener {
                releaseAudioTrack()
                seekBarUpdateRunnable?.let { seekBarHandler.removeCallbacks(it) }
                seekBarUpdateRunnable = null
            }
        }
        dialogLayout.addView(btnClose)

        dialogBuilder.setTitle("PCM播放")
        dialogBuilder.setView(dialogLayout)
        dialogBuilder.setCancelable(false)
        val dialog = dialogBuilder.show()

        // 启动播放
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
                    var currentSeek = playbackSeekRequested
                    playbackSeekRequested = -1L
                    var fis = java.io.FileInputStream(pcmFile)
                    // 初始seek到指定位置
                    if (currentSeek > 0) {
                        val seekBytes = (currentSeek * 16000L * 2L / 1000L).coerceAtMost(pcmFile.length())
                        fis.skip(seekBytes)
                        playbackCurrentPositionMs = currentSeek
                    }
                    val buffer = ByteArray(8192)
                    // v3.1.18: 改用累计字节数计算位置，避免整数除法精度丢失
                    var totalBytesRead = 0L
                    while (!Thread.currentThread().isInterrupted) {
                        // 检查seek请求
                        val seekReq = playbackSeekRequested
                        if (seekReq >= 0) {
                            playbackSeekRequested = -1L
                            currentSeek = seekReq
                            // 重新打开文件并seek
                            fis.close()
                            fis = java.io.FileInputStream(pcmFile)
                            val seekBytes = (seekReq * 16000L * 2L / 1000L).coerceAtMost(pcmFile.length())
                            fis.skip(seekBytes)
                            playbackCurrentPositionMs = seekReq
                            totalBytesRead = 0L  // 重置累计字节数
                            // Flush AudioTrack
                            try { audioTrack?.pause() } catch (_: Exception) {}
                            try { audioTrack?.flush() } catch (_: Exception) {}
                            try { audioTrack?.play() } catch (_: Exception) {}
                        }
                        val read = fis.read(buffer)
                        if (read <= 0) break
                        audioTrack?.write(buffer, 0, read)
                        totalBytesRead += read
                        // v3.1.18: 用累计字节数计算位置，避免 read/32000=0 的问题
                        playbackCurrentPositionMs = currentSeek + totalBytesRead * 1000L / (16000L * 2L)
                    }
                    fis.close()
                } catch (e: Exception) {
                    Log.e(TAG, "PCM playback error: ${e.message}")
                } finally {
                    try {
                        audioTrack?.stop()
                    } catch (_: Exception) {}
                    try {
                        audioTrack?.release()
                    } catch (_: Exception) {}
                    audioTrack = null
                    uiHandler.post { fingerprintAdapter.stopPlaying() }
                    uiHandler.post { dialog.dismiss() }
                }
            }.apply { start() }

            // 定期更新SeekBar
            seekBarUpdateRunnable = object : Runnable {
                override fun run() {
                    // v3.1.18: 移除 audioTrack != null 检查，仅以 dialog 状态为准
                    if (dialog.isShowing) {
                        val pos = playbackCurrentPositionMs.coerceAtMost(totalDurationMs)
                        seekBar.progress = pos.toInt()
                        tvCurrent.text = formatMs(pos)
                        seekBarUpdateRunnable?.let { seekBarHandler.postDelayed(this, 500) }
                    }
                }
            }
            seekBarHandler.postDelayed(seekBarUpdateRunnable!!, 500)
        } catch (e: Exception) {
            Log.e(TAG, "playPcmFileWithSeekBar failed: ${e.message}", e)
            Toast.makeText(this, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            releaseAudioTrack()
            dialog.dismiss()
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

    /**
     * v3.1.10: 下载指定节目的MP4缓存文件。
     * 当PCM重新生成所需的MP4已被缓存清理时，从网络重新下载。
     */
    private suspend fun downloadMp4ForEpisode(episodeId: String): File? = withContext(Dispatchers.IO) {
        try {
            val episodesDir = com.radio.app.RadioApplication.getEpisodesCacheDir(this@KeywordSettingsActivity)
            // 先检查是否已有MP4缓存
            val existing = episodesDir.listFiles { f ->
                f.name.contains(episodeId) && f.name.endsWith(".mp4") && f.length() > 1024
            }?.firstOrNull()
            if (existing != null) return@withContext existing

            // 从数据库获取音频URL
            val episode = RadioDatabaseHelper.getInstance(this@KeywordSettingsActivity).getEpisodeInfo(episodeId)
            val audioUrl = episode?.audioUrl ?: return@withContext null
            if (audioUrl.isBlank() || !audioUrl.startsWith("http")) return@withContext null

            // 下载MP4
            val fileName = try {
                java.net.URL(audioUrl).path.substringAfterLast('/')
            } catch (e: Exception) {
                "${episodeId}.mp4"
            }
            val targetFile = File(episodesDir, fileName)
            if (targetFile.exists() && targetFile.length() > 1024) return@withContext targetFile

            val connection = java.net.URL(audioUrl).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
            connection.connect()
            if (connection.responseCode == 200) {
                connection.inputStream.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (targetFile.exists() && targetFile.length() > 1024) targetFile else null
            } else {
                Log.e(TAG, "downloadMp4ForEpisode: HTTP ${connection.responseCode} for $episodeId")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadMp4ForEpisode failed: ${e.message}", e)
            null
        }
    }

    private fun showFingerprintTestDialog(fp: AudioFingerprint) {
        val options = arrayOf("指纹 vs 自身 PCM", "指纹 vs 完整节目 PCM(滑动搜索)", "指纹 vs 所有完整 PCM(滑动搜索)", "所有指纹 vs 所有完整 PCM(滑动搜索)", "跨节目指纹直接对比", "指纹分组测试(≥95%)", "指纹分组管理(独立页面)")
        AlertDialog.Builder(this)
            .setTitle("音频指纹匹配测试")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> runFingerprintSelfTest(fp)
                    1 -> runFingerprintFullEpisodeTest(fp)
                    2 -> runFingerprintVsAllPcmTest(fp)
                    3 -> runAllFingerprintsVsAllPcmTest()
                    4 -> runCrossEpisodeFingerprintTest(fp)
                    5 -> runFingerprintGroupTest()
                    6 -> startActivity(android.content.Intent(this, FingerprintGroupActivity::class.java))
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
                .setNeutralButton("播放指纹片段") { _, _ -> playPcmFile(pcmFile) }
                .show()
        }
    }

    /**
     * v3.1.7: 升级为完整PCM滑动搜索。使用 searchFingerprintInPcm 扫描整个PCM文件，
     * 找到最佳匹配位置。同时保留原位置快速对比作为参考。
     * v3.1.7-fix: 增加播放匹配片段按钮，支持指纹片段和匹配片段双向播放验证。
     */
    private fun runFingerprintFullEpisodeTest(fp: AudioFingerprint) {
        lifecycleScope.launch {
            val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this@KeywordSettingsActivity)
            val fullPcm = File(pcmCacheDir, "${fp.episodeId}_full.pcm")
            val sourceFile = when {
                fullPcm.exists() && fullPcm.length() > 0 -> fullPcm
                else -> null
            }

            // v3.1.9: 如果PCM已被自动清理，尝试重新生成
            // v3.1.10: 如果MP4缓存也被清理，自动从网络重新下载
            // v3.1.11: PCM重新生成时通知栏显示进度百分比、已用时间和估计剩余时间
            var resolvedSource = sourceFile
            if (resolvedSource == null) {
                val fpLabel = "${fp.episodeId} ${formatMs(fp.startMs)}-${formatMs(fp.endMs)}"
                FingerprintTestNotificationHelper.resetCancel()
                FingerprintTestNotificationHelper.showProgress(this@KeywordSettingsActivity, fpLabel, "正在重新生成PCM...", 0, 0)
                val pcmGenStartTime = System.currentTimeMillis()
                // 尝试重新生成PCM
                withContext(Dispatchers.IO) {
                    var audioFile = com.radio.app.RadioApplication.getEpisodesCacheDir(this@KeywordSettingsActivity)
                        .listFiles { f -> f.name.contains(fp.episodeId) && f.name.endsWith(".mp4") }
                        ?.firstOrNull { it.length() > 1024 }
                    // v3.1.10: 如果MP4已被缓存清理，从网络重新下载
                    if (audioFile == null) {
                        Log.d(TAG, "PCM regeneration: MP4 not found in cache, downloading for ${fp.episodeId}")
                        FingerprintTestNotificationHelper.showProgress(this@KeywordSettingsActivity, fpLabel, "正在下载MP4...", 0, 0)
                        audioFile = downloadMp4ForEpisode(fp.episodeId)
                    }
                    if (audioFile != null && audioFile.length() > 1024) {
                        com.radio.app.utils.AudioSegmentAnalyzer.preGeneratePcmFiles(
                            this@KeywordSettingsActivity, fp.episodeId, audioFile.absolutePath,
                            progressCallback = { pct ->
                                val elapsed = System.currentTimeMillis() - pcmGenStartTime
                                FingerprintTestNotificationHelper.showProgress(this@KeywordSettingsActivity, fpLabel, "正在生成PCM...", pct, elapsed)
                            }
                        )
                    }
                }
                // 重新检查PCM文件
                val fullPcm2 = File(pcmCacheDir, "${fp.episodeId}_full.pcm")
                resolvedSource = when {
                    fullPcm2.exists() && fullPcm2.length() > 0 -> fullPcm2
                    else -> null
                }
                FingerprintTestNotificationHelper.cancel(this@KeywordSettingsActivity)
            }

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (!ChromaprintExtractor.ensureLibraryLoaded(this@KeywordSettingsActivity)) {
                        return@withContext "Chromaprint 库未加载"
                    }
                    if (resolvedSource == null) return@withContext "未找到完整节目 PCM 缓存，且无法自动重新生成"

                    // 使用resolvedSource替代sourceFile
                    val actualSource = resolvedSource!!

                    val sb = StringBuilder()
                    sb.append("指纹: ${fp.episodeId} [${formatMs(fp.startMs)}-${formatMs(fp.endMs)}] (${fp.durationMs / 1000}s)\n")
                    sb.append("源文件: ${actualSource.name}\n\n")

                    // v3.1.8: 通知栏进度
                    val fpLabel = "${fp.episodeId} ${formatMs(fp.startMs)}-${formatMs(fp.endMs)}"
                    val pcmLabel = actualSource.name
                    val testStartTime = System.currentTimeMillis()
                    FingerprintTestNotificationHelper.resetCancel()
                    FingerprintTestNotificationHelper.showProgress(this@KeywordSettingsActivity, fpLabel, pcmLabel, 1, 0)

                    // 1. 原位置快速对比（旧方案）
                    sb.append("--- 原位置快速对比（±3s偏移） ---\n")
                    val offsets = listOf(0L, 1000L, -1000L, 2000L, -2000L, 3000L, -3000L)
                    var bestDetail: ChromaprintExtractor.CompareResult? = null
                    var bestOffsetLabel = ""
                    for (offsetMs in offsets) {
                        val adjustedStart = (fp.startMs + offsetMs).coerceAtLeast(0L)
                        val segmentPcm = PcmSegmentExtractor.extractSegmentFromFile(actualSource, adjustedStart, fp.endMs)
                            ?: continue
                        val refingerprint = ChromaprintExtractor.extractFingerprintFromFile(segmentPcm)
                        segmentPcm.delete()
                        if (refingerprint == null) continue
                        val detail = ChromaprintExtractor.compareFingerprintsDetailed(fp.fingerprint, refingerprint)
                        if (bestDetail == null || detail.similarity > bestDetail.similarity) {
                            bestDetail = detail
                            bestOffsetLabel = "${offsetMs}ms"
                        }
                    }
                    if (bestDetail != null) {
                        val match = bestDetail.similarity >= 0.70f
                        sb.append("最佳相似度: %.2f%% %s\n".format(bestDetail.similarity * 100, if (match) "★匹配" else ""))
                        sb.append("最佳偏移: $bestOffsetLabel, 指纹偏移: ${bestDetail.bestOffset}\n")
                        sb.append("位误差: ${bestDetail.minErrors}/${bestDetail.totalBits}\n\n")
                    } else {
                        sb.append("所有偏移均提取失败\n\n")
                    }

                    // 2. 完整PCM滑动搜索
                    sb.append("--- 完整PCM滑动搜索 ---\n")
                    val pcmResult = ChromaprintExtractor.searchFingerprintInPcm(
                        fingerprint = fp.fingerprint,
                        pcmFile = actualSource,
                        searchDurationMs = fp.durationMs,
                        threshold = 0.70f,
                        originalStartMs = fp.startMs,
                        progressCallback = { pct, msg ->
                            val elapsed = System.currentTimeMillis() - testStartTime
                            FingerprintTestNotificationHelper.showProgress(this@KeywordSettingsActivity, fpLabel, pcmLabel, pct, elapsed)
                        }
                    )
                    if (pcmResult != null) {
                        val match = pcmResult.similarity >= 0.70f
                        sb.append("最佳相似度: %.2f%% %s\n".format(pcmResult.similarity * 100, if (match) "★匹配" else ""))
                        sb.append("最佳匹配位置: ${formatMs(pcmResult.bestMatchStartMs)}-${formatMs(pcmResult.bestMatchEndMs)}\n")
                        sb.append("PCM总时长: ${pcmResult.pcmDurationMs / 1000}s\n")
                        sb.append("扫描位置数: ${pcmResult.totalPositionsScanned}\n")
                        sb.append("超阈值位置数: ${pcmResult.positionsAboveThreshold}\n")
                        sb.append("搜索耗时: ${pcmResult.searchDurationMs}ms\n")
                    } else {
                        sb.append("滑动搜索失败（PCM文件无效或时长不足）\n")
                    }

                    // 完成通知
                    FingerprintTestNotificationHelper.showComplete(this@KeywordSettingsActivity, "测试完成: ${fpLabel} vs ${pcmLabel}")

                    sb.toString()
                }.getOrElse { "测试异常: ${it.message}" }
            }

            // 构建带播放按钮的对话框
            val builder = AlertDialog.Builder(this@KeywordSettingsActivity)
                .setTitle("指纹 vs 完整节目 PCM")
                .setMessage(result)
                .setPositiveButton("确定", null)
            // 添加播放指纹片段按钮和播放匹配片段按钮
            if (resolvedSource != null) {
                builder.setNeutralButton("播放指纹片段") { _, _ ->
                    playPcmSegment(resolvedSource, fp.startMs, fp.endMs)
                }
                // 从结果中提取匹配位置并添加播放按钮
                val matchStartLine = result.split("\n").find { it.contains("最佳匹配位置") }
                if (matchStartLine != null) {
                    val parts = matchStartLine.split(": ")
                    if (parts.size >= 2) {
                        val timeRange = parts[1].trim()
                        val timeParts = timeRange.split("-")
                        if (timeParts.size >= 2) {
                            val matchStart = parseFormatMs(timeParts[0].trim())
                            val matchEnd = parseFormatMs(timeParts[1].trim())
                            if (matchEnd > matchStart) {
                                builder.setNegativeButton("播放匹配片段") { _, _ ->
                                    playPcmSegment(resolvedSource, matchStart, matchEnd)
                                }
                            }
                        }
                    }
                }
            }
            builder.show()
        }
    }

    /**
     * v3.1.7-fix: 将格式化的时间字符串（mm:ss）解析为毫秒。
     */
    private fun parseFormatMs(formatted: String): Long {
        try {
            val parts = formatted.split(":")
            if (parts.size >= 2) {
                val minutes = parts[0].toLong()
                val seconds = parts[1].toLong()
                return (minutes * 60 + seconds) * 1000
            }
        } catch (_: Exception) {}
        return 0L
    }

    /**
     * v3.1.7: 单指纹 vs 所有完整 PCM。
     * 使用滑动窗口搜索，扫描整个PCM文件，找到最佳匹配位置。
     * 同时保留原位置快速对比作为参考。
     * v3.1.8: 通知栏进度、取消支持、播放按钮。
     * v3.1.10: 每个匹配结果旁增加独立的播放匹配片段按钮。
     */
    private fun runFingerprintVsAllPcmTest(fp: AudioFingerprint) {
        lifecycleScope.launch {
            currentProgressDialog = android.app.ProgressDialog(this@KeywordSettingsActivity).apply {
                setMessage("正在测试指纹 vs 所有完整 PCM...")
                setCancelable(false)
                show()
            }
            // v3.1.10: 匹配信息数据类，用于生成每项播放按钮
            data class MatchInfo(
                val episodeId: String, val sim: Float, val slideSim: Float,
                val matchStartMs: Long, val matchEndMs: Long, val pcmFile: File?
            )
            val resultTriple = withContext(Dispatchers.IO) {
                runCatching {
                    if (!ChromaprintExtractor.ensureLibraryLoaded(this@KeywordSettingsActivity)) {
                        return@withContext Triple("Chromaprint 库未加载", emptyList<String>(), emptyList<MatchInfo>())
                    }
                    val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this@KeywordSettingsActivity)
                    val allPcmFiles = pcmCacheDir.listFiles { f -> f.name.endsWith("_full.pcm") && f.length() > 0 }
                        ?.sortedByDescending { it.lastModified() } ?: emptyList()
                    if (allPcmFiles.isEmpty()) return@withContext Triple("未找到任何完整 PCM 缓存文件", emptyList<String>(), emptyList<MatchInfo>())

                    val headerSb = StringBuilder()
                    val fpLabel = "${fp.episodeId} ${formatMs(fp.startMs)}-${formatMs(fp.endMs)}"
                    headerSb.append("指纹: $fpLabel (${fp.durationMs / 1000}s)\n")
                    if (fp.note.isNotEmpty()) headerSb.append("备注: ${fp.note}\n")
                    headerSb.append("共 ${allPcmFiles.size} 个完整 PCM 文件\n\n")
                    val headerText = headerSb.toString()

                    FingerprintTestNotificationHelper.resetCancel()
                    val testStartTime = System.currentTimeMillis()
                    FingerprintTestNotificationHelper.showProgress(this@KeywordSettingsActivity, fpLabel, "准备中...", 0, 0)

                    var matchCount = 0
                    var slideMatchCount = 0
                    val matchInfos = mutableListOf<MatchInfo>()
                    val sectionTexts = mutableListOf<String>()

                    for ((idx, pcmFile) in allPcmFiles.withIndex()) {
                        val sectionSb = StringBuilder()
                        if (FingerprintTestNotificationHelper.isCancelled) {
                            sectionSb.append("\n测试已被用户取消\n")
                            sectionTexts.add(sectionSb.toString())
                            break
                        }
                        val episodeIdFromName = pcmFile.name.removeSuffix("_full.pcm")
                        val pcmLabel = pcmFile.name
                        val progress = ((idx * 100) / allPcmFiles.size).coerceIn(0, 99)
                        val elapsed = System.currentTimeMillis() - testStartTime
                        FingerprintTestNotificationHelper.showProgress(this@KeywordSettingsActivity, fpLabel, pcmLabel, progress, elapsed)

                        sectionSb.append("--- [${idx + 1}/${allPcmFiles.size}] $episodeIdFromName ---\n")
                        try {
                            // 1. 原位置快速对比（仅同节目保留，跨节目无意义）
                            var oldSim = 0f
                            if (episodeIdFromName == fp.episodeId) {
                                sectionSb.append("  原位置: ")
                                val segmentPcm = PcmSegmentExtractor.extractSegmentFromFile(pcmFile, fp.startMs, fp.endMs)
                                if (segmentPcm != null) {
                                    val refingerprint = ChromaprintExtractor.extractFingerprintFromFile(segmentPcm)
                                    segmentPcm.delete()
                                    if (refingerprint != null) {
                                        val detail = ChromaprintExtractor.compareFingerprintsDetailed(fp.fingerprint, refingerprint)
                                        oldSim = detail.similarity
                                        val match = detail.similarity >= 0.70f
                                        if (match) matchCount++
                                        sectionSb.append("%.1f%% %s\n".format(detail.similarity * 100, if (match) "★匹配" else ""))
                                    } else {
                                        sectionSb.append("提取指纹失败\n")
                                    }
                                } else {
                                    sectionSb.append("截取片段失败\n")
                                }
                            }

                            // 2. 滑动窗口搜索
                            sectionSb.append("  滑动搜索: ")
                            val isSameEpisode = episodeIdFromName == fp.episodeId
                            val pcmResult = ChromaprintExtractor.searchFingerprintInPcm(
                                fingerprint = fp.fingerprint,
                                pcmFile = pcmFile,
                                searchDurationMs = fp.durationMs,
                                threshold = 0.70f,
                                originalStartMs = if (isSameEpisode) fp.startMs else null
                            )
                            var slideSim = 0f
                            var matchStart = 0L
                            var matchEnd = 0L
                            if (pcmResult != null) {
                                slideSim = pcmResult.similarity
                                matchStart = pcmResult.bestMatchStartMs
                                matchEnd = pcmResult.bestMatchEndMs
                                val match = slideSim >= 0.70f
                                if (match) slideMatchCount++
                                sectionSb.append("%.1f%% %s".format(slideSim * 100, if (match) "★匹配" else ""))
                                sectionSb.append(" (位置:${formatMs(matchStart)}, 扫描${pcmResult.totalPositionsScanned}处, ${pcmResult.searchDurationMs}ms)\n")
                            } else {
                                sectionSb.append("PCM无效或时长不足\n")
                            }
                            matchInfos.add(MatchInfo(episodeIdFromName, oldSim, slideSim, matchStart, matchEnd, pcmFile))
                        } catch (e: Exception) {
                            sectionSb.append("  异常: ${e.message}\n")
                        }
                        sectionTexts.add(sectionSb.toString())
                    }

                    if (!FingerprintTestNotificationHelper.isCancelled) {
                        FingerprintTestNotificationHelper.showComplete(this@KeywordSettingsActivity, "测试完成: $fpLabel, 匹配 $slideMatchCount/$matchCount")
                    } else {
                        FingerprintTestNotificationHelper.cancel(this@KeywordSettingsActivity)
                    }

                    Triple(headerText, sectionTexts, matchInfos.toList())
                }.getOrElse { Triple("测试异常: ${it.message}", emptyList<String>(), emptyList<MatchInfo>()) }
            }
            currentProgressDialog?.dismiss()
            currentProgressDialog = null

            val (headerText, sectionTexts, matchInfos) = resultTriple

            // 构建带每项匹配播放按钮的自定义对话框
            val scrollView = android.widget.ScrollView(this@KeywordSettingsActivity)
            val container = android.widget.LinearLayout(this@KeywordSettingsActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(32, 16, 32, 16)
            }

            // 头部文本（指纹信息、文件总数）
            val tvHeader = android.widget.TextView(this@KeywordSettingsActivity).apply {
                text = headerText
                textSize = 12f
                setTextIsSelectable(true)
                setLineSpacing(0f, 1.2f)
                setPadding(0, 0, 0, 16)
            }
            container.addView(tvHeader)

            // 每个 PCM 文件的段落及对应的播放按钮
            for ((idx, sectionText) in sectionTexts.withIndex()) {
                val tvSection = android.widget.TextView(this@KeywordSettingsActivity).apply {
                    text = sectionText
                    textSize = 12f
                    setTextIsSelectable(true)
                    setLineSpacing(0f, 1.2f)
                    setPadding(0, 0, 0, 4)
                }
                container.addView(tvSection)

                if (idx < matchInfos.size && matchInfos[idx].slideSim >= 0.70f) {
                    val mi = matchInfos[idx]
                    val btnMatch = android.widget.Button(this@KeywordSettingsActivity).apply {
                        text = "▶ 播放匹配[${idx + 1}] ${mi.episodeId} @${formatMs(mi.matchStartMs)} (${"%.1f%%".format(mi.slideSim * 100)})"
                        setOnClickListener {
                            if (mi.pcmFile != null && mi.pcmFile.exists() && mi.matchEndMs > mi.matchStartMs) {
                                playPcmSegment(mi.pcmFile, mi.matchStartMs, mi.matchEndMs)
                            } else {
                                Toast.makeText(this@KeywordSettingsActivity, "匹配片段PCM不可用", Toast.LENGTH_SHORT).show()
                            }
                        }
                        setPadding(0, 4, 0, 8)
                    }
                    container.addView(btnMatch)
                }
            }

            // 播放指纹片段按钮
            val btnPlayFp = android.widget.Button(this@KeywordSettingsActivity).apply {
                text = "▶ 播放指纹片段"
                setOnClickListener {
                    val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this@KeywordSettingsActivity)
                    val sourceFile = File(pcmCacheDir, "${fp.episodeId}_full.pcm")
                    if (sourceFile.exists()) {
                        playPcmSegment(sourceFile, fp.startMs, fp.endMs)
                    } else {
                        Toast.makeText(this@KeywordSettingsActivity, "指纹PCM文件不存在", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            container.addView(btnPlayFp)

            // 底部统计摘要
            val matchCount = matchInfos.count { it.sim >= 0.70f }
            val slideMatchCount = matchInfos.count { it.slideSim >= 0.70f }
            val totalCount = matchInfos.size
            val tvFooter = android.widget.TextView(this@KeywordSettingsActivity).apply {
                text = "\n原位置匹配: $matchCount / $totalCount\n滑动搜索匹配: $slideMatchCount / $totalCount"
                textSize = 12f
                setTextIsSelectable(true)
                setLineSpacing(0f, 1.2f)
                setPadding(0, 8, 0, 0)
            }
            container.addView(tvFooter)

            scrollView.addView(container)

            AlertDialog.Builder(this@KeywordSettingsActivity)
                .setTitle("指纹 vs 所有完整 PCM")
                .setView(scrollView)
                .setPositiveButton("确定", null)
                .show()
        }
    }

    /**
     * v3.1.7: 所有指纹 vs 所有完整 PCM。
     * 对每条指纹，使用滑动窗口搜索所有完整 PCM 文件，输出匹配矩阵。
     * 每条指纹搜索一个PCM文件约需10-30秒，请耐心等待。
     * v3.1.8: 通知栏进度、取消支持、播放按钮。
     * v3.1.10: 每个匹配结果旁增加独立的播放匹配片段按钮。
     */
    private fun runAllFingerprintsVsAllPcmTest() {
        val allFps = RadioDatabaseHelper.getInstance(this).getAllAudioFingerprints()
        if (allFps.isEmpty()) {
            Toast.makeText(this, "暂无指纹", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            currentProgressDialog = android.app.ProgressDialog(this@KeywordSettingsActivity).apply {
                setMessage("正在测试所有指纹 vs 所有完整 PCM（滑动搜索）...")
                setCancelable(false)
                show()
            }
            data class FpMatchInfo(
                val fp: AudioFingerprint, val episodeId: String,
                val slideSim: Float, val matchStartMs: Long, val matchEndMs: Long,
                val pcmFile: File?
            )
            // v3.1.11: 每个指纹的匹配结果段落
            data class FingerprintSection(
                val headerText: String,
                val matchInfos: List<FpMatchInfo>,
                val footerText: String = ""
            )
            val resultSections = withContext(Dispatchers.IO) {
                runCatching {
                    if (!ChromaprintExtractor.ensureLibraryLoaded(this@KeywordSettingsActivity)) {
                        return@withContext listOf(FingerprintSection("Chromaprint 库未加载", emptyList()))
                    }
                    val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this@KeywordSettingsActivity)
                    val allPcmFiles = pcmCacheDir.listFiles { f -> f.name.endsWith("_full.pcm") && f.length() > 0 }
                        ?.sortedByDescending { it.lastModified() } ?: emptyList()
                    if (allPcmFiles.isEmpty()) return@withContext listOf(FingerprintSection("未找到任何完整 PCM 缓存文件", emptyList()))

                    val sections = mutableListOf<FingerprintSection>()
                    val headerSb = StringBuilder()
                    headerSb.append("指纹数: ${allFps.size}, PCM 文件数: ${allPcmFiles.size}\n\n")
                    sections.add(FingerprintSection(headerSb.toString(), emptyList()))

                    FingerprintTestNotificationHelper.resetCancel()
                    val testStartTime = System.currentTimeMillis()
                    FingerprintTestNotificationHelper.showProgress(this@KeywordSettingsActivity, "所有指纹", "准备中...", 0, 0)

                    var totalMatch = 0
                    var totalSlideMatch = 0
                    var totalTest = 0
                    for ((fpIdx, fp) in allFps.withIndex()) {
                        if (FingerprintTestNotificationHelper.isCancelled) {
                            sections.add(FingerprintSection("\n测试已被用户取消\n", emptyList()))
                            break
                        }
                        val progress = ((fpIdx * 100) / allFps.size).coerceIn(0, 99)
                        val elapsed = System.currentTimeMillis() - testStartTime
                        FingerprintTestNotificationHelper.showProgress(this@KeywordSettingsActivity, "指纹${fpIdx+1}/${allFps.size}", "共${allPcmFiles.size}个PCM", progress, elapsed)

                        val note = if (fp.note.isNotEmpty()) " [${fp.note}]" else ""
                        val fpHeader = "--- 指纹 ${fpIdx + 1}: ${fp.episodeId} ${formatMs(fp.startMs)}-${formatMs(fp.endMs)}${note} ---\n"
                        val fpMatchInfos = mutableListOf<FpMatchInfo>()
                        val fpResultSb = StringBuilder()

                        for (pcmFile in allPcmFiles) {
                            val episodeIdFromName = pcmFile.name.removeSuffix("_full.pcm")
                            totalTest++
                            try {
                                var oldMatch = false
                                var oldSim = 0f
                                if (episodeIdFromName == fp.episodeId) {
                                    val segmentPcm = PcmSegmentExtractor.extractSegmentFromFile(pcmFile, fp.startMs, fp.endMs)
                                    if (segmentPcm != null) {
                                        val refingerprint = ChromaprintExtractor.extractFingerprintFromFile(segmentPcm)
                                        segmentPcm.delete()
                                        if (refingerprint != null) {
                                            val detail = ChromaprintExtractor.compareFingerprintsDetailed(fp.fingerprint, refingerprint)
                                            oldMatch = detail.similarity >= 0.70f
                                            oldSim = detail.similarity
                                            if (oldMatch) totalMatch++
                                        }
                                    }
                                }

                                val isSameEpisode = episodeIdFromName == fp.episodeId
                                val pcmResult = ChromaprintExtractor.searchFingerprintInPcm(
                                    fingerprint = fp.fingerprint,
                                    pcmFile = pcmFile,
                                    searchDurationMs = fp.durationMs,
                                    threshold = 0.70f,
                                    originalStartMs = if (isSameEpisode) fp.startMs else null
                                )
                                val slideMatch = pcmResult != null && pcmResult.similarity >= 0.70f
                                if (slideMatch) {
                                    totalSlideMatch++
                                    fpMatchInfos.add(FpMatchInfo(fp, episodeIdFromName, pcmResult!!.similarity, pcmResult.bestMatchStartMs, pcmResult.bestMatchEndMs, pcmFile))
                                    fpResultSb.append("  ★ $episodeIdFromName: 原位置%.1f%% → 滑动%.1f%% @${formatMs(pcmResult.bestMatchStartMs)} (${pcmResult.searchDurationMs}ms)\n".format(
                                        oldSim * 100, pcmResult.similarity * 100))
                                } else if (oldMatch || (pcmResult != null && pcmResult.similarity > 0.5f)) {
                                    fpResultSb.append("  ~ $episodeIdFromName: 原位置%.1f%% → 滑动%.1f%% (${pcmResult?.searchDurationMs ?: 0}ms)\n".format(
                                        oldSim * 100, (pcmResult?.similarity ?: 0f) * 100))
                                }
                            } catch (_: Exception) {}
                        }

                        sections.add(FingerprintSection(fpHeader, fpMatchInfos.toList(), fpResultSb.toString()))
                    }

                    // 统计摘要段落
                    val summarySb = StringBuilder()
                    summarySb.append("\n总测试: $totalTest\n")
                    summarySb.append("原位置匹配: $totalMatch\n")
                    summarySb.append("滑动搜索匹配: $totalSlideMatch")
                    sections.add(FingerprintSection(summarySb.toString(), emptyList()))

                    if (!FingerprintTestNotificationHelper.isCancelled) {
                        FingerprintTestNotificationHelper.showComplete(this@KeywordSettingsActivity, "测试完成: 总测试$totalTest, 匹配$totalSlideMatch")
                    } else {
                        FingerprintTestNotificationHelper.cancel(this@KeywordSettingsActivity)
                    }

                    sections.toList()
                }.getOrElse { listOf(FingerprintSection("测试异常: ${it.message}", emptyList())) }
            }
            currentProgressDialog?.dismiss()
            currentProgressDialog = null

            // v3.1.11: 构建带每项匹配播放按钮的自定义对话框（按钮放在匹配片段下方）
            val scrollView = android.widget.ScrollView(this@KeywordSettingsActivity)
            val container = android.widget.LinearLayout(this@KeywordSettingsActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(32, 16, 32, 16)
            }

            for (section in resultSections) {
                if (section.headerText.isNotEmpty()) {
                    val tvHeader = android.widget.TextView(this@KeywordSettingsActivity).apply {
                        text = section.headerText
                        textSize = 12f
                        setTextIsSelectable(true)
                        setLineSpacing(0f, 1.2f)
                        setPadding(0, 0, 0, 4)
                    }
                    container.addView(tvHeader)
                }
                if (section.footerText.isNotEmpty()) {
                    val tvBody = android.widget.TextView(this@KeywordSettingsActivity).apply {
                        text = section.footerText
                        textSize = 12f
                        setTextIsSelectable(true)
                        setLineSpacing(0f, 1.2f)
                        setPadding(0, 0, 0, 4)
                    }
                    container.addView(tvBody)
                }
                // 匹配播放按钮放在匹配结果下方、下一条结果之前
                var matchIdx = 0
                for (mi in section.matchInfos) {
                    matchIdx++
                    val btnMatch = android.widget.Button(this@KeywordSettingsActivity).apply {
                        val fpLabel = "${mi.fp.episodeId} ${formatMs(mi.fp.startMs)}-${formatMs(mi.fp.endMs)}"
                        text = "▶ 播放匹配 #$matchIdx ${mi.episodeId} @${formatMs(mi.matchStartMs)} (${"%.1f%%".format(mi.slideSim * 100)})"
                        setOnClickListener {
                            if (mi.pcmFile != null && mi.pcmFile.exists() && mi.matchEndMs > mi.matchStartMs) {
                                playPcmSegment(mi.pcmFile, mi.matchStartMs, mi.matchEndMs)
                            } else {
                                Toast.makeText(this@KeywordSettingsActivity, "匹配片段PCM不可用", Toast.LENGTH_SHORT).show()
                            }
                        }
                        setPadding(0, 4, 0, 8)
                        textSize = 11f
                    }
                    container.addView(btnMatch)
                }
            }

            scrollView.addView(container)

            AlertDialog.Builder(this@KeywordSettingsActivity)
                .setTitle("所有指纹 vs 所有完整 PCM")
                .setView(scrollView)
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
                .setNeutralButton("播放基准指纹片段") { _, _ ->
                    val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this@KeywordSettingsActivity)
                    val sourceFile = File(pcmCacheDir, "${fp.episodeId}_full.pcm")
                    if (sourceFile.exists()) {
                        playPcmSegment(sourceFile, fp.startMs, fp.endMs)
                    } else {
                        Toast.makeText(this@KeywordSettingsActivity, "PCM文件不存在", Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
    }

    private fun formatMs(ms: Long): String {
        val s = (ms / 1000).toInt()
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    /**
     * v3.1.7: 播放PCM文件中的指定片段，用于人工验证测试结果。
     */
    private fun playPcmSegment(pcmFile: File, startMs: Long, endMs: Long) {
        if (!pcmFile.exists() || pcmFile.length() <= 0) {
            Toast.makeText(this, "PCM文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        if (endMs <= startMs) {
            Toast.makeText(this, "无效的时间范围", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val segmentFile = withContext(Dispatchers.IO) {
                PcmSegmentExtractor.extractSegmentFromFile(pcmFile, startMs, endMs)
            }
            if (segmentFile != null && segmentFile.exists() && segmentFile.length() > 0) {
                playPcmFile(segmentFile)
                Toast.makeText(this@KeywordSettingsActivity, "正在播放: ${formatMs(startMs)}-${formatMs(endMs)}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@KeywordSettingsActivity, "截取PCM片段失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * v3.1.4: 指纹分组测试。
     * 读取所有指纹，按相似度≥95%分组，展示分组结果和组内指纹信息。
     */
    private fun runFingerprintGroupTest() {
        val allFps = RadioDatabaseHelper.getInstance(this).getAllAudioFingerprints()
        if (allFps.size < 2) {
            Toast.makeText(this, "至少需要2条指纹才能测试分组", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val parsedFps = allFps.map { ChromaprintExtractor.parseFingerprint(it.fingerprint) }
                    val groups = ChromaprintExtractor.buildFingerprintGroups(parsedFps)
                    val sb = StringBuilder()
                    sb.append("共 ${allFps.size} 条指纹，${groups.size} 个分组\n\n")
                    var groupIdx = 0
                    for (group in groups) {
                        groupIdx++
                        val rep = allFps[group.representativeIndex]
                        sb.append("=== 分组 $groupIdx (${group.memberIndices.size} 个成员) ===\n")
                        sb.append("  代表指纹: ${rep.episodeId} [${formatMs(rep.startMs)}-${formatMs(rep.endMs)}] (${rep.durationMs / 1000}s)\n")
                        if (rep.note.isNotEmpty()) sb.append("  备注: ${rep.note}\n")
                        sb.append("\n")
                        for (memberIdx in group.memberIndices) {
                            val fp = allFps[memberIdx]
                            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(fp.createdAt))
                            val isRep = if (memberIdx == group.representativeIndex) " ★代表" else ""
                            sb.append("  [${memberIdx + 1}] ${fp.episodeId} $isRep\n")
                            sb.append("    时间: ${formatMs(fp.startMs)}-${formatMs(fp.endMs)} (${fp.durationMs / 1000}s)\n")
                            sb.append("    创建: $dateStr\n")
                            sb.append("    指纹长度: ${parsedFps[memberIdx].size}\n")
                            if (fp.note.isNotEmpty()) sb.append("    备注: ${fp.note}\n")
                            // 如果非代表，计算与代表的相似度
                            if (memberIdx != group.representativeIndex) {
                                val detail = ChromaprintExtractor.compareFingerprintArrays(
                                    parsedFps[memberIdx], parsedFps[group.representativeIndex]
                                )
                                sb.append("    与代表相似度: %.2f%%\n".format(detail.similarity * 100))
                            }
                            sb.append("\n")
                        }
                    }
                    // 统计孤立指纹（未分组）
                    val groupedIndices = groups.flatMap { it.memberIndices }.toSet()
                    val isolatedCount = allFps.size - groupedIndices.size
                    sb.append("孤立指纹（未与任何指纹聚组）: $isolatedCount 条\n")
                    sb.toString()
                }.getOrElse { "测试异常: ${it.message}" }
            }
            val builder = AlertDialog.Builder(this@KeywordSettingsActivity)
                .setTitle("指纹分组测试")
                .setMessage(result)
                .setPositiveButton("确定", null)
            // v3.1.9: 添加播放按钮
            if (allFps.isNotEmpty()) {
                val firstFp = allFps[0]
                builder.setNeutralButton("播放首个指纹片段") { _, _ ->
                    val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this@KeywordSettingsActivity)
                    val sourceFile = File(pcmCacheDir, "${firstFp.episodeId}_full.pcm")
                    if (sourceFile.exists()) {
                        playPcmSegment(sourceFile, firstFp.startMs, firstFp.endMs)
                    } else {
                        Toast.makeText(this@KeywordSettingsActivity, "PCM文件不存在", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            builder.show()
        }
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
