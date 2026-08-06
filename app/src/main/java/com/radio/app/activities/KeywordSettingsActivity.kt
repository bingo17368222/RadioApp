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
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.radio.app.R
import com.radio.app.database.AudioFingerprint
import com.radio.app.database.RadioDatabaseHelper
import com.radio.app.adapters.AudioFingerprintAdapter
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
 * v3.0.2: 音频指纹管理页。
 * 展示用户通过"添加为水分指纹"保存的音频指纹素材，支持删除和修正（重新提取）。
 * v3.0.4: 支持播放水印指纹片段 PCM；PCM 缺失时可从缓存重新生成。
 * v3.0.5: 接收指纹服务广播，自动刷新列表并提示结果。
 * v3.1.44: 恢复原始水分指纹音频素材列表UI（包含播放/测试/批量测试等完整功能）。
 */
class KeywordSettingsActivity : AppCompatActivity() {

    private lateinit var prefMgr: PreferenceManager
    private lateinit var settings: AppSettings
    private lateinit var tvTitle: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var tvFingerprintCount: TextView
    private lateinit var btnTestAll: Button
    private lateinit var btnFingerprintGroups: Button
    private lateinit var btnFingerprintManagement: Button

    // v3.1.44: 指纹列表
    private lateinit var recyclerFingerprints: RecyclerView
    private lateinit var tvFingerprintsEmpty: TextView
    private lateinit var fingerprintAdapter: AudioFingerprintAdapter
    private var currentFingerprints: List<AudioFingerprint> = emptyList()
    private var selectedFingerprint: AudioFingerprint? = null

    // v3.1.55: 带进度条的播放控件
    private lateinit var sbPlaybackProgress: SeekBar
    private lateinit var tvPlaybackStatus: TextView
    private lateinit var btnPlayback: Button
    private var playingEpisodeId: String? = null
    private var isPlaying = false
    private var playbackFileSize: Long = 0L

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
    private var progressUpdater: Runnable? = null

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
    private val fingerprintTestCancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action == FingerprintTestNotificationHelper.CANCEL_ACTION) {
                Log.d(TAG, "取消指纹测试")
                FingerprintTestNotificationHelper.setCancelled()
                FingerprintTestNotificationHelper.cancel(context)
                uiHandler.post { currentProgressDialog?.dismiss() }
            }
        }
    }
    @Volatile
    private var currentProgressDialog: android.app.ProgressDialog? = null

    companion object {
        private const val TAG = "KeywordSettingsActivity"
        private const val SAMPLE_RATE = 16000

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

        initPlaybackAndTest()
        initFingerprintList()
        initWaterCombinationManagement()

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

        try {
            LocalBroadcastManager.getInstance(this).registerReceiver(
                fingerprintTestCancelReceiver,
                IntentFilter(FingerprintTestNotificationHelper.CANCEL_ACTION)
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
            LocalBroadcastManager.getInstance(this).unregisterReceiver(fingerprintTestCancelReceiver)
        } catch (_: Exception) {}
    }

    // ==================== 音频指纹播放与测试（v3.1.55：移除列表，保留播放/测试功能） ====================

    private fun initPlaybackAndTest() {
        tvFingerprintCount = findViewById(R.id.tv_fingerprint_count)
        btnTestAll = findViewById(R.id.btn_test_all_fingerprints)
        btnFingerprintGroups = findViewById(R.id.btn_fingerprint_groups)
        btnFingerprintManagement = findViewById(R.id.btn_fingerprint_management)
        sbPlaybackProgress = findViewById(R.id.sb_playback_progress)
        tvPlaybackStatus = findViewById(R.id.tv_playback_status)
        btnPlayback = findViewById(R.id.btn_playback_fingerprint)

        // 播放按钮：播放选中/第一条指纹的PCM，或停止播放
        btnPlayback.setOnClickListener {
            if (isPlaying) {
                releaseAudioTrack()
            } else {
                if (selectedFingerprint != null) {
                    playFingerprintPcm(selectedFingerprint!!)
                } else {
                    playFirstFingerprint()
                }
            }
        }

        // v3.1.2: 批量测试按钮
        btnTestAll.setOnClickListener {
            runBatchFingerprintTest()
        }

        // v3.1.6: 指纹分组管理
        btnFingerprintGroups.setOnClickListener {
            startActivity(Intent(this, FingerprintGroupActivity::class.java))
        }

        // v3.2.3: 指纹分类管理
        btnFingerprintManagement.setOnClickListener {
            startActivity(Intent(this, FingerprintManagementActivity::class.java))
        }
    }

    /**
     * v3.1.55: 播放第一条指纹的水印PCM。
     */
    private fun playFirstFingerprint() {
        try {
            val db = RadioDatabaseHelper.getInstance(this)
            val allFps = db.getAllAudioFingerprints()
            if (allFps.isEmpty()) {
                Toast.makeText(this, "没有指纹数据，请先在播放器中添加指纹", Toast.LENGTH_SHORT).show()
                return
            }
            val fp = allFps.first()
            val pcmFile = PcmSegmentExtractor.getWatermarkPcmFile(this, fp.episodeId, fp.startMs, fp.endMs)
            if (pcmFile.exists() && pcmFile.length() > 0) {
                playingEpisodeId = fp.episodeId
                tvPlaybackStatus.text = "正在播放: ${fp.episodeId} [${formatMs(fp.startMs)}-${formatMs(fp.endMs)}]"
                btnPlayback.text = "停止"
                playPcmFile(pcmFile)
            } else {
                Toast.makeText(this, "水印PCM文件不存在，尝试从缓存重新生成...", Toast.LENGTH_SHORT).show()
                regenerateWatermarkPcmAndPlay(fp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "playFirstFingerprint failed: ${e.message}")
            Toast.makeText(this, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * v3.1.42: 重新生成水印PCM并播放。
     */
    private fun regenerateWatermarkPcmAndPlay(fp: AudioFingerprint) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this@KeywordSettingsActivity)
                    val sourceFile = File(pcmCacheDir, "${fp.episodeId}_full.pcm")
                    if (!sourceFile.exists()) {
                        return@runCatching "完整PCM文件不存在，请在播放器中先播放该节目"
                    }
                    val segmentFile = PcmSegmentExtractor.extractSegmentFromFile(sourceFile, fp.startMs, fp.endMs)
                    if (segmentFile == null || !segmentFile.exists()) {
                        return@runCatching "从缓存截取PCM片段失败"
                    }
                    // 复制到水印PCM目录
                    val targetFile = PcmSegmentExtractor.getWatermarkPcmFile(this@KeywordSettingsActivity, fp.episodeId, fp.startMs, fp.endMs)
                    segmentFile.copyTo(targetFile, overwrite = true)
                    segmentFile.delete()
                    "ok:$targetFile"
                }.getOrElse { "error:${it.message}" }
            }
            if (result.startsWith("ok:")) {
                val pcmFile = File(result.removePrefix("ok:"))
                playPcmFile(pcmFile)
            } else {
                Toast.makeText(this@KeywordSettingsActivity, result, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * v3.0.4: 播放 PCM 文件（使用 AudioTrack）。
     * v3.1.55: 添加 SeekBar 进度条跟踪。
     */
    private fun playPcmFile(pcmFile: File) {
        releaseAudioTrack()
        playbackFileSize = pcmFile.length()
        isPlaying = true
        try {
            val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                bufferSize.coerceAtLeast(4096),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            audioTrack = track
            track.play()

            // 启动进度条更新
            progressUpdater = Runnable {
                updatePlaybackProgress()
            }

            playbackThread = Thread {
                try {
                    val buffer = ByteArray(bufferSize.coerceAtLeast(4096))
                    val fis = java.io.FileInputStream(pcmFile)
                    var totalRead = 0L
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } > 0 && isPlaying) {
                        track.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        // 更新进度条（每读取一块更新一次）
                        val progress = ((totalRead.toFloat() / playbackFileSize) * 1000).toInt()
                        uiHandler.post { sbPlaybackProgress.progress = progress.coerceIn(0, 1000) }
                    }
                    fis.close()
                    if (isPlaying) {
                        track.stop()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "playback error: ${e.message}")
                } finally {
                    track.release()
                    if (audioTrack == track) audioTrack = null
                    uiHandler.post {
                        isPlaying = false
                        sbPlaybackProgress.progress = 0
                        btnPlayback.text = "播放选中指纹"
                        tvPlaybackStatus.text = "播放完成"
                        progressUpdater?.let { uiHandler.removeCallbacks(it) }
                        progressUpdater = null
                    }
                }
            }.apply {
                name = "fingerprint-playback"
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "playPcmFile failed: ${e.message}")
            isPlaying = false
            audioTrack = null
            btnPlayback.text = "播放选中指纹"
            tvPlaybackStatus.text = "播放失败"
            sbPlaybackProgress.progress = 0
        }
    }

    /**
     * v3.1.55: 更新播放进度条。
     */
    private fun updatePlaybackProgress() {
        if (!isPlaying) return
        // 重新调度下次更新（每200ms刷新一次）
        uiHandler.postDelayed({ updatePlaybackProgress() }, 200L)
    }

    /**
     * 释放 AudioTrack 资源。
     * v3.1.55: 重置播放UI状态。
     */
    private fun releaseAudioTrack() {
        try {
            playbackThread?.interrupt()
            playbackThread = null
            audioTrack?.let { track ->
                try { track.stop() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
            }
            audioTrack = null
        } catch (_: Exception) {}
        isPlaying = false
        playingEpisodeId = null
        sbPlaybackProgress.progress = 0
        btnPlayback.text = "播放选中指纹"
        tvPlaybackStatus.text = "未播放"
        progressUpdater?.let { uiHandler.removeCallbacks(it) }
        progressUpdater = null
    }

    /**
     * v3.1.42: 测试指纹匹配（从水印PCM重新提取指纹，与数据库对比相似度）。
     */
    private fun testFingerprint(fp: AudioFingerprint) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pcmFile = PcmSegmentExtractor.getWatermarkPcmFile(
                        this@KeywordSettingsActivity, fp.episodeId, fp.startMs, fp.endMs
                    )
                    if (!pcmFile.exists() || pcmFile.length() <= 0) {
                        return@runCatching Pair(-1f, "水印PCM文件不存在")
                    }
                    val extractedFp = ChromaprintExtractor.extractFingerprintFromFile(pcmFile)
                    if (extractedFp.isNullOrBlank()) {
                        return@runCatching Pair(-1f, "从PCM提取指纹失败")
                    }
                    val similarity = ChromaprintExtractor.compareFingerprints(fp.fingerprint, extractedFp)
                    Pair(similarity, "相似度: ${String.format(Locale.US, "%.1f", similarity * 100)}%")
                }.getOrElse { Pair(-1f, "测试异常: ${it.message}") }
            }
            val msg = "${result.second} (${fp.episodeId} [${formatMs(fp.startMs)}-${formatMs(fp.endMs)}])"
            Toast.makeText(this@KeywordSettingsActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * v3.0.2: 修正指纹（重新提取）。
     */
    private fun refreshFingerprintAsync(fp: AudioFingerprint) {
        AudioFingerprintService.startAddFingerprint(this, fp.episodeId, fp.startMs, fp.endMs, fp.episodeId)
        Toast.makeText(this, "正在重新提取指纹...", Toast.LENGTH_SHORT).show()
    }

    /**
     * v3.0.2: 加载指纹列表。
     */
    private fun loadFingerprints() {
        try {
            val db = RadioDatabaseHelper.getInstance(this)
            val allFingerprints = db.getAllAudioFingerprints()
            currentFingerprints = allFingerprints
            tvFingerprintCount.text = "已保存 ${allFingerprints.size} 条指纹"
            fingerprintAdapter.setItems(allFingerprints)
            tvFingerprintsEmpty.visibility = if (allFingerprints.isEmpty()) TextView.VISIBLE else TextView.GONE
            recyclerFingerprints.visibility = if (allFingerprints.isEmpty()) TextView.GONE else TextView.VISIBLE
        } catch (e: Exception) {
            Log.e(TAG, "loadFingerprints failed: ${e.message}")
        }
    }

    // ==================== 指纹列表（v3.1.44 恢复） ====================

    private fun initFingerprintList() {
        recyclerFingerprints = findViewById(R.id.recycler_audio_fingerprints)
        tvFingerprintsEmpty = findViewById(R.id.tv_fingerprints_empty)

        fingerprintAdapter = AudioFingerprintAdapter()
        recyclerFingerprints.layoutManager = LinearLayoutManager(this)
        recyclerFingerprints.adapter = fingerprintAdapter

        // 播放按钮：播放选中指纹的PCM
        fingerprintAdapter.setOnPlayListener { fp ->
            if (isPlaying) {
                releaseAudioTrack()
            }
            selectedFingerprint = fp
            playFingerprintPcm(fp)
        }
        fingerprintAdapter.setOnStopListener {
            releaseAudioTrack()
        }

        // 测试按钮：弹出测试选择对话框
        fingerprintAdapter.setOnTestListener { fp ->
            showFingerprintTestDialog(fp)
        }

        // 删除、修正
        fingerprintAdapter.setOnDeleteListener { fp ->
            try {
                RadioDatabaseHelper.getInstance(this).deleteAudioFingerprint(fp.id)
                loadFingerprints()
                Toast.makeText(this, "已删除指纹", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        fingerprintAdapter.setOnRefreshListener { fp ->
            AudioFingerprintService.startAddFingerprint(this, fp.episodeId, fp.startMs, fp.endMs, fp.episodeId)
            Toast.makeText(this, "正在重新提取指纹...", Toast.LENGTH_SHORT).show()
        }

        // 点击选中
        fingerprintAdapter.setOnItemClickListener { fp, _ ->
            selectedFingerprint = fp
            tvPlaybackStatus.text = "已选中: ${fp.episodeId} [${formatMs(fp.startMs)}-${formatMs(fp.endMs)}]"
        }
    }

    /**
     * 播放选中指纹的PCM片段。
     */
    private fun playFingerprintPcm(fp: AudioFingerprint) {
        try {
            val pcmFile = PcmSegmentExtractor.getWatermarkPcmFile(this, fp.episodeId, fp.startMs, fp.endMs)
            if (pcmFile.exists() && pcmFile.length() > 0) {
                playingEpisodeId = fp.episodeId
                tvPlaybackStatus.text = "正在播放: ${fp.episodeId} [${formatMs(fp.startMs)}-${formatMs(fp.endMs)}]"
                btnPlayback.text = "停止"
                playPcmFile(pcmFile)
            } else {
                Toast.makeText(this, "水印PCM文件不存在，尝试从缓存重新生成...", Toast.LENGTH_SHORT).show()
                regenerateWatermarkPcmAndPlay(fp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "playFingerprintPcm failed: ${e.message}")
            Toast.makeText(this, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 显示指纹测试选择对话框：指纹vs自身 / 指纹vs完整PCM / 指纹vs所有完整PCM
     */
    private fun showFingerprintTestDialog(fp: AudioFingerprint) {
        val options = arrayOf(
            "指纹 vs 自身（提取→对比）",
            "指纹 vs 本节目完整PCM",
            "指纹 vs 所有完整PCM（滑动搜索）"
        )
        AlertDialog.Builder(this)
            .setTitle("测试指纹 - ${fp.episodeId} [${formatMs(fp.startMs)}-${formatMs(fp.endMs)}]")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> testFingerprintVsSelf(fp)
                    1 -> testFingerprintVsEpisodePcm(fp)
                    2 -> testFingerprintVsAllPcm(fp)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 测试1：指纹 vs 自身（重新提取指纹并对比相似度）
     */
    private fun testFingerprintVsSelf(fp: AudioFingerprint) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pcmFile = PcmSegmentExtractor.getWatermarkPcmFile(
                        this@KeywordSettingsActivity, fp.episodeId, fp.startMs, fp.endMs
                    )
                    if (!pcmFile.exists() || pcmFile.length() <= 0) {
                        return@runCatching "水印PCM文件不存在"
                    }
                    val extractedFp = ChromaprintExtractor.extractFingerprintFromFile(pcmFile)
                    if (extractedFp.isNullOrBlank()) {
                        return@runCatching "从PCM提取指纹失败"
                    }
                    val similarity = ChromaprintExtractor.compareFingerprints(fp.fingerprint, extractedFp)
                    "相似度: ${String.format(Locale.US, "%.1f", similarity * 100)}%"
                }.getOrElse { "测试异常: ${it.message}" }
            }
            showTestResultDialog("指纹 vs 自身（${fp.episodeId} [${formatMs(fp.startMs)}-${formatMs(fp.endMs)}]）", result, null, null, null)
        }
    }

    /**
     * 测试2：指纹 vs 本节目完整PCM（滑动窗口搜索匹配位置）
     */
    private fun testFingerprintVsEpisodePcm(fp: AudioFingerprint) {
        lifecycleScope.launch {
            val result: Triple<String, File?, Long?> = withContext(Dispatchers.IO) {
                runCatching {
                    val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this@KeywordSettingsActivity)
                    val fullPcm = File(pcmCacheDir, "${fp.episodeId}_full.pcm")
                    if (!fullPcm.exists() || fullPcm.length() <= 0) {
                        return@runCatching Triple("本节目完整PCM文件不存在", null as File?, null)
                    }
                    val pcmResult = ChromaprintExtractor.searchFingerprintInPcm(
                        fingerprint = fp.fingerprint,
                        pcmFile = fullPcm,
                        searchDurationMs = fp.durationMs,
                        threshold = 0.70f,
                        originalStartMs = fp.startMs
                    )
                    if (pcmResult != null && pcmResult.similarity >= 0.70f) {
                        val msg = "匹配成功! 相似度: ${String.format(Locale.US, "%.1f", pcmResult.similarity * 100)}% @ ${formatMs(pcmResult.bestMatchStartMs)}-${formatMs(pcmResult.bestMatchEndMs)}"
                        Triple(msg, fullPcm, pcmResult.bestMatchStartMs)
                    } else {
                        val simStr = if (pcmResult != null) String.format(Locale.US, "%.1f", pcmResult.similarity * 100) else "N/A"
                        Triple("匹配失败（相似度: $simStr%）", null, null)
                    }
                }.getOrElse { Triple("测试异常: ${it.message}", null, null) }
            }
            val (msg, pcmFile, matchStartMs) = result
            showTestResultDialog("指纹 vs 本节目完整PCM（${fp.episodeId}）", msg, fp, matchStartMs, pcmFile)
        }
    }

    /**
     * 测试3：指纹 vs 所有完整PCM（滑动窗口搜索所有节目）
     */
    private fun testFingerprintVsAllPcm(fp: AudioFingerprint) {
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                runCatching {
                    val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this@KeywordSettingsActivity)
                    val pcmFiles = pcmCacheDir.listFiles()?.filter { it.name.endsWith("_full.pcm") && it.length() > 16000 } ?: emptyList()
                    if (pcmFiles.isEmpty()) return@runCatching "没有完整PCM文件"

                    val matchResults = mutableListOf<Pair<String, File?>>()
                    for (pcmFile in pcmFiles) {
                        val epId = pcmFile.name.removeSuffix("_full.pcm")
                        val pcmResult = ChromaprintExtractor.searchFingerprintInPcm(
                            fingerprint = fp.fingerprint,
                            pcmFile = pcmFile,
                            searchDurationMs = fp.durationMs,
                            threshold = 0.70f,
                            originalStartMs = if (epId == fp.episodeId) fp.startMs else null
                        )
                        if (pcmResult != null && pcmResult.similarity >= 0.70f) {
                            matchResults.add("★ $epId: ${String.format(Locale.US, "%.1f", pcmResult.similarity * 100)}% @ ${formatMs(pcmResult.bestMatchStartMs)}" to pcmFile)
                        } else if (pcmResult != null && pcmResult.similarity > 0.5f) {
                            matchResults.add("~ $epId: ${String.format(Locale.US, "%.1f", pcmResult.similarity * 100)}%" to null)
                        }
                    }
                    if (matchResults.isEmpty()) return@runCatching "所有PCM中均未找到匹配（相似度>50%也无）"
                    matchResults.joinToString("\n") { it.first }
                }.getOrElse { "测试异常: ${it.message}" }
            }
            showTestResultDialog("指纹 vs 所有完整PCM（${fp.episodeId}）", results, null, null, null)
        }
    }

    /**
     * 显示测试结果对话框，匹配项下方附带播放按钮。
     */
    private fun showTestResultDialog(
        title: String,
        message: String,
        matchFp: AudioFingerprint?,
        matchStartMs: Long?,
        matchPcmFile: File?
    ) {
        val scrollView = android.widget.ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }

        val tvMsg = android.widget.TextView(this).apply {
            text = message
            textSize = 13f
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.2f)
        }
        container.addView(tvMsg)

        if (matchPcmFile != null && matchPcmFile.exists()) {
            val playStart = matchStartMs ?: (matchFp?.startMs ?: 0L)
            val playEnd = if (matchStartMs != null && matchFp != null) {
                matchStartMs + (matchFp.endMs - matchFp.startMs)
            } else {
                matchFp?.endMs ?: 0L
            }
            if (playEnd > playStart) {
                val btnPlay = Button(this).apply {
                    text = "▶ 播放匹配位置 PCM（${formatMs(playStart)}-${formatMs(playEnd)}）"
                    setOnClickListener {
                        playPcmSegment(matchPcmFile, playStart, playEnd)
                    }
                    textSize = 12f
                }
                container.addView(btnPlay)
            }
        }

        scrollView.addView(container)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("确定", null)
            .show()
    }

    // ==================== 批量测试 ====================

    /**
     * v3.1.2: 批量测试：所有指纹 vs 所有完整PCM。
     * 对每条指纹，在所有完整PCM中搜索匹配片段，通过滑动窗口指纹搜索找到最佳匹配位置。
     * v3.1.8: 使用通知栏进度显示，支持取消。
     * v3.1.11: 对话框展示匹配结果，匹配项下方带播放按钮。
     */
    private data class FpMatchInfo(
        val fp: AudioFingerprint,
        val episodeId: String,
        val slideSim: Float,
        val matchStartMs: Long,
        val matchEndMs: Long,
        val pcmFile: File?
    )

    private data class FingerprintSection(
        val headerText: String,
        val matchInfos: List<FpMatchInfo>,
        val footerText: String = ""
    )

    private fun runBatchFingerprintTest() {
        if (FingerprintTestNotificationHelper.isCancelled) {
            FingerprintTestNotificationHelper.resetCancel()
        }
        val allFps = RadioDatabaseHelper.getInstance(this).getAllAudioFingerprints()
        if (allFps.isEmpty()) {
            Toast.makeText(this, "没有指纹数据", Toast.LENGTH_SHORT).show()
            return
        }
        val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this)
        val pcmFiles = pcmCacheDir.listFiles()?.filter { it.name.endsWith("_full.pcm") && it.length() > 16000 } ?: emptyList()
        if (pcmFiles.isEmpty()) {
            Toast.makeText(this, "没有完整PCM文件用于测试", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "开始批量测试 ${allFps.size} 条指纹 × ${pcmFiles.size} 个PCM文件...", Toast.LENGTH_SHORT).show()
        FingerprintTestNotificationHelper.showProgress(this, "", "", 0, 0L)
        currentProgressDialog = android.app.ProgressDialog(this).apply {
            setTitle("批量指纹测试")
            setMessage("初始化...")
            setCancelable(false)
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max = allFps.size
            show()
        }

        lifecycleScope.launch {
            val resultSections = withContext(Dispatchers.IO) {
                runCatching {
                    val sections = mutableListOf<FingerprintSection>()
                    var totalTest = 0
                    var totalMatch = 0
                    var totalSlideMatch = 0

                    for ((fpIdx, fp) in allFps.withIndex()) {
                        if (FingerprintTestNotificationHelper.isCancelled) break
                        val current = fpIdx + 1
                        uiHandler.post { currentProgressDialog?.let { it.progress = current; it.setMessage("测试 ${fp.episodeId} [$current/${allFps.size}]") } }
                        FingerprintTestNotificationHelper.showProgress(this@KeywordSettingsActivity, "", "", current, 0L)

                        val fpHeader = "指纹 ${current}: ${fp.episodeId} [${formatMs(fp.startMs)}-${formatMs(fp.endMs)}] (${fp.durationMs / 1000}s)"
                        val fpMatchInfos = mutableListOf<FpMatchInfo>()
                        val fpResultSb = StringBuilder()

                        for (pcmFile in pcmFiles) {
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

    override fun onStop() {
        super.onStop()
        releaseAudioTrack()
    }

    private fun formatMs(ms: Long): String {
        val s = (ms / 1000).toInt()
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    // ==================== 水货分段组合管理 ====================

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
        parent: ViewGroup,
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