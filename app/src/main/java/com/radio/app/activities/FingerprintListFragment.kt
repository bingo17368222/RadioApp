package com.radio.app.activities

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.radio.app.R
import com.radio.app.activities.FingerprintGroupActivity
import com.radio.app.adapters.AudioFingerprintAdapter
import com.radio.app.adapters.AutomaticFingerprintAdapter
import com.radio.app.adapters.CandidateFingerprintAdapter
import com.radio.app.database.AudioFingerprint
import com.radio.app.database.RadioDatabaseHelper
import com.radio.app.database.RadioDatabaseHelper.ObservationPoolCandidate
import com.radio.app.RadioApplication
import com.radio.app.utils.AudioSegmentAnalyzer
import com.radio.app.utils.ChromaprintExtractor
import com.radio.app.utils.PcmSegmentExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * v3.2.3: 指纹列表 Fragment。
 * 根据 type 参数展示三类指纹列表：
 * - "manual"：人工指纹（金标准，isGoldStandard=true）
 * - "candidate"：候选指纹（观察池）
 * - "automatic"：自动指纹（自动晋升，isGoldStandard=false）
 *
 * v3.1.62: 修复手动/候选指纹播放进度条，支持带SeekBar的PCM播放对话框。
 * 支持删除操作，在 onResume 中自动刷新数据。
 */
class FingerprintListFragment : Fragment() {

    companion object {
        private const val ARG_TYPE = "fingerprint_type"
        private const val TAG = "FingerprintListFragment"
        private const val SAMPLE_RATE = 16000

        fun newInstance(type: String): FingerprintListFragment {
            return FingerprintListFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TYPE, type)
                }
            }
        }
    }

    private var fingerprintType: String = "manual"
    private lateinit var dbHelper: RadioDatabaseHelper
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvCount: TextView

    // 三种适配器，按需初始化
    private var audioFingerprintAdapter: AudioFingerprintAdapter? = null
    private var candidateAdapter: CandidateFingerprintAdapter? = null
    private var automaticAdapter: AutomaticFingerprintAdapter? = null

    // ===== PCM 播放状态 =====
    private var playbackAudioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    @Volatile
    private var playbackSeekRequested: Long = -1L
    @Volatile
    private var playbackCurrentPositionMs: Long = 0L
    private var playbackTotalMs: Long = 0L
    private val seekBarHandler = Handler(Looper.getMainLooper())
    private var seekBarUpdateRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fingerprintType = arguments?.getString(ARG_TYPE, "manual") ?: "manual"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_fingerprint_list, container, false)
        recyclerView = view.findViewById(R.id.recycler_fingerprints)
        tvEmpty = view.findViewById(R.id.tv_fingerprints_empty)
        tvCount = view.findViewById(R.id.tv_fingerprint_count)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dbHelper = RadioDatabaseHelper.getInstance(requireContext().applicationContext)
        setupRecyclerView()
        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        releaseAudioTrack()
        seekBarUpdateRunnable?.let { seekBarHandler.removeCallbacks(it) }
        seekBarUpdateRunnable = null
    }

    /**
     * 根据指纹类型初始化 RecyclerView 和适配器。
     */
    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        when (fingerprintType) {
            "manual" -> {
                audioFingerprintAdapter = AudioFingerprintAdapter().apply {
                    setOnDeleteListener { fp -> confirmDeleteFingerprint(fp) }
                    // v3.1.62: 带进度条的PCM播放
                    setOnPlayListener { fp ->
                        playFingerprintPcm(fp.episodeId, fp.startMs, fp.endMs, fp.durationMs)
                    }
                    setOnStopListener {
                        releaseAudioTrack()
                        audioFingerprintAdapter?.stopPlaying()
                    }
                    // v3.1.65: 改为音频指纹素材列表的测试组菜单
                    setOnTestListener { fp ->
                        showFingerprintTestDialog(fp)
                    }
                }
                recyclerView.adapter = audioFingerprintAdapter
            }
            "candidate" -> {
                candidateAdapter = CandidateFingerprintAdapter().apply {
                    setOnDeleteListener { candidate -> confirmDeleteCandidate(candidate) }
                    // v3.1.62: 带进度条的候选指纹播放（候选无startMs/endMs，按episodeId搜索PCM）
                    setOnPlayListener { candidate ->
                        playCandidateFingerprintPcm(candidate.episodeId, candidate.durationMs)
                    }
                    setOnStopListener {
                        releaseAudioTrack()
                        candidateAdapter?.stopPlaying()
                    }
                }
                recyclerView.adapter = candidateAdapter
            }
            "automatic" -> {
                automaticAdapter = AutomaticFingerprintAdapter().apply {
                    setOnDeleteListener { fp -> confirmDeleteFingerprint(fp) }
                }
                recyclerView.adapter = automaticAdapter
            }
        }
    }

    // ===== PCM 播放 =====

    /**
     * v3.1.63: 播放候选指纹PCM（候选无startMs/endMs，按episodeId搜索PCM文件）。
     * 如果PCM不存在，自动从PCM缓存重新生成；如果MP4不存在，自动下载并重新生成PCM。
     */
    private fun playCandidateFingerprintPcm(episodeId: String, durationMs: Long) {
        val watermarkDir = RadioApplication.getWatermarkPcmDir(requireContext())
        if (!watermarkDir.exists()) watermarkDir.mkdirs()

        // 1. 搜索以episodeId开头的PCM文件
        val pcmFiles = watermarkDir.listFiles { file ->
            file.name.startsWith(episodeId) && file.name.endsWith(".pcm")
        }
        if (!pcmFiles.isNullOrEmpty()) {
            // 找到已有PCM，直接播放
            val pcmFile = pcmFiles[0]
            val totalMs = if (durationMs > 0) durationMs else pcmFile.length() / (SAMPLE_RATE * 2) * 1000
            playPcmFileWithSeekBar(pcmFile, totalMs)
            return
        }

        // 2. PCM不存在，尝试自动生成
        Toast.makeText(requireContext(), "PCM不存在，正在自动生成...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                generatePcmAndPlay(episodeId, durationMs)
            }
            if (result != null) {
                activity?.runOnUiThread {
                    playPcmFileWithSeekBar(result, if (durationMs > 0) durationMs else result.length() / (SAMPLE_RATE * 2) * 1000)
                }
            }
        }
    }

    /**
     * v3.1.63: 生成候选指纹PCM：先尝试从现有PCM缓存提取，再尝试下载MP4生成。
     * v3.1.65: 增加进度通知，使用FingerprintTestNotificationHelper显示PCM生成进度。
     * @return PCM文件，失败返回null
     */
    private fun generatePcmAndPlay(episodeId: String, durationMs: Long): File? {
        val fpLabel = episodeId.take(30)
        val pcmGenStartTime = System.currentTimeMillis()
        // v3.1.65: 显示PCM生成进度通知栏
        com.radio.app.utils.FingerprintTestNotificationHelper.resetCancel()
        com.radio.app.utils.FingerprintTestNotificationHelper.showProgress(
            requireContext(), fpLabel, "正在生成PCM...", 0, 0
        )
        return try {
            // 2a. 检查是否有完整PCM缓存
            val pcmCacheDir = RadioApplication.getPcmCacheDir(requireContext())
            val fullPcm = File(pcmCacheDir, "${episodeId}_full.pcm")
            val min5Pcm = File(pcmCacheDir, "${episodeId}_5min.pcm")
            val sourceFile = when {
                fullPcm.exists() && fullPcm.length() > 0 -> fullPcm
                min5Pcm.exists() && min5Pcm.length() > 0 -> min5Pcm
                else -> null
            }

            if (sourceFile != null) {
                // 有PCM缓存，直接播放完整PCM
                Log.d(TAG, "generatePcmAndPlay: using existing PCM cache: ${sourceFile.name}")
                com.radio.app.utils.FingerprintTestNotificationHelper.cancel(requireContext())
                return sourceFile
            }

            // 2b. 检查是否有MP4缓存
            val episodesDir = RadioApplication.getEpisodesCacheDir(requireContext())
            var mp4File = episodesDir.listFiles { f ->
                f.name.contains(episodeId) && f.name.endsWith(".mp4") && f.length() > 1024
            }?.firstOrNull()

            // 2c. 如果MP4不存在，从网络下载
            if (mp4File == null) {
                Log.d(TAG, "generatePcmAndPlay: MP4 not found, downloading for $episodeId")
                com.radio.app.utils.FingerprintTestNotificationHelper.showProgress(
                    requireContext(), fpLabel, "正在下载MP4...", 0, 0
                )
                mp4File = downloadMp4ForEpisode(episodeId)
            }

            // 2d. 用MP4生成PCM
            if (mp4File != null && mp4File.length() > 1024) {
                AudioSegmentAnalyzer.preGeneratePcmFiles(
                    requireContext(), episodeId, mp4File.absolutePath,
                    progressCallback = { pct ->
                        val elapsed = System.currentTimeMillis() - pcmGenStartTime
                        com.radio.app.utils.FingerprintTestNotificationHelper.showProgress(
                            requireContext(), fpLabel, "正在生成PCM...", pct, elapsed
                        )
                    }
                )
                // 检查PCM是否生成成功
                val fullPcm2 = File(pcmCacheDir, "${episodeId}_full.pcm")
                if (fullPcm2.exists() && fullPcm2.length() > 0) {
                    com.radio.app.utils.FingerprintTestNotificationHelper.cancel(requireContext())
                    return fullPcm2
                }
                val min5Pcm2 = File(pcmCacheDir, "${episodeId}_5min.pcm")
                if (min5Pcm2.exists() && min5Pcm2.length() > 0) {
                    com.radio.app.utils.FingerprintTestNotificationHelper.cancel(requireContext())
                    return min5Pcm2
                }
            }

            Log.w(TAG, "generatePcmAndPlay: failed to generate PCM for $episodeId")
            com.radio.app.utils.FingerprintTestNotificationHelper.cancel(requireContext())
            null
        } catch (e: Exception) {
            Log.e(TAG, "generatePcmAndPlay failed: ${e.message}", e)
            com.radio.app.utils.FingerprintTestNotificationHelper.cancel(requireContext())
            null
        }
    }

    /**
     * v3.1.63: 下载MP4用于PCM生成。
     */
    private fun downloadMp4ForEpisode(episodeId: String): File? {
        return try {
            val episodesDir = RadioApplication.getEpisodesCacheDir(requireContext())
            // 从数据库获取音频URL
            val episode = dbHelper.getEpisodeInfo(episodeId)
            val audioUrl = episode?.audioUrl ?: return null
            if (audioUrl.isBlank() || !audioUrl.startsWith("http")) return null

            val fileName = try {
                URL(audioUrl).path.substringAfterLast('/')
            } catch (e: Exception) {
                "${episodeId}.mp4"
            }
            val targetFile = File(episodesDir, fileName)
            if (targetFile.exists() && targetFile.length() > 1024) return targetFile

            val connection = URL(audioUrl).openConnection() as HttpURLConnection
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

    /**
     * v3.1.62: 播放指纹PCM，带进度条对话框。
     */
    private fun playFingerprintPcm(episodeId: String, startMs: Long, endMs: Long, durationMs: Long) {
        val pcmFile = PcmSegmentExtractor.getWatermarkPcmFile(requireContext(), episodeId, startMs, endMs)
        if (pcmFile.exists() && pcmFile.length() > 0) {
            val totalMs = if (durationMs > 0) durationMs else pcmFile.length() / (SAMPLE_RATE * 2) * 1000
            playPcmFileWithSeekBar(pcmFile, totalMs)
            return
        }

        // PCM 不存在，尝试重新生成
        Toast.makeText(requireContext(), "PCM 已删除，正在重新生成...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val regenerated = withContext(Dispatchers.IO) {
                PcmSegmentExtractor.extractWatermarkPcm(requireContext(), episodeId, startMs, endMs)
            }
            if (regenerated != null && regenerated.exists() && regenerated.length() > 0) {
                val totalMs = if (durationMs > 0) durationMs else regenerated.length() / (SAMPLE_RATE * 2) * 1000
                playPcmFileWithSeekBar(regenerated, totalMs)
            } else {
                Toast.makeText(requireContext(), "重新生成 PCM 失败（缺少原始缓存）", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * v3.1.62: 带可拖动进度条的PCM播放对话框。
     */
    private fun playPcmFileWithSeekBar(pcmFile: File, totalDurationMs: Long) {
        releaseAudioTrack()
        playbackTotalMs = totalDurationMs
        playbackCurrentPositionMs = 0L
        playbackSeekRequested = -1L

        val ctx = requireContext()

        // 构建SeekBar对话框
        val dialogBuilder = AlertDialog.Builder(ctx)
        val dialogLayout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val timeLayout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val tvCurrent = android.widget.TextView(ctx).apply {
            text = "00:00"
            textSize = 12f
        }
        val tvTotal = android.widget.TextView(ctx).apply {
            text = formatMs(totalDurationMs)
            textSize = 12f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.END }
        }
        val spacer = android.widget.Space(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, 0, 1f)
        }
        timeLayout.addView(tvCurrent)
        timeLayout.addView(spacer)
        timeLayout.addView(tvTotal)
        dialogLayout.addView(timeLayout)

        val seekBar = android.widget.SeekBar(ctx).apply {
            max = totalDurationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            progress = 0
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
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

        val btnClose = android.widget.Button(ctx).apply {
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
            playbackAudioTrack = AudioTrack(
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
            playbackAudioTrack?.play()

            playbackThread = Thread {
                try {
                    var currentSeek = playbackSeekRequested
                    playbackSeekRequested = -1L
                    var fis = java.io.FileInputStream(pcmFile)
                    if (currentSeek > 0) {
                        val seekBytes = (currentSeek * SAMPLE_RATE.toLong() * 2L / 1000L).coerceAtMost(pcmFile.length())
                        fis.skip(seekBytes)
                        playbackCurrentPositionMs = currentSeek
                    }
                    val buffer = ByteArray(8192)
                    var totalBytesRead = 0L
                    while (!Thread.currentThread().isInterrupted) {
                        val seekReq = playbackSeekRequested
                        if (seekReq >= 0) {
                            playbackSeekRequested = -1L
                            currentSeek = seekReq
                            fis.close()
                            fis = java.io.FileInputStream(pcmFile)
                            val seekBytes = (seekReq * SAMPLE_RATE.toLong() * 2L / 1000L).coerceAtMost(pcmFile.length())
                            fis.skip(seekBytes)
                            playbackCurrentPositionMs = seekReq
                            totalBytesRead = 0L
                            try { playbackAudioTrack?.pause() } catch (_: Exception) {}
                            try { playbackAudioTrack?.flush() } catch (_: Exception) {}
                            try { playbackAudioTrack?.play() } catch (_: Exception) {}
                        }
                        val read = fis.read(buffer)
                        if (read <= 0) break
                        playbackAudioTrack?.write(buffer, 0, read)
                        totalBytesRead += read
                        playbackCurrentPositionMs = currentSeek + totalBytesRead * 1000L / (SAMPLE_RATE.toLong() * 2L)
                    }
                    fis.close()
                } catch (e: Exception) {
                    Log.e(TAG, "PCM playback error: ${e.message}")
                } finally {
                    try { playbackAudioTrack?.stop() } catch (_: Exception) {}
                    try { playbackAudioTrack?.release() } catch (_: Exception) {}
                    playbackAudioTrack = null
                    // 更新播放状态
                    activity?.runOnUiThread {
                        when (fingerprintType) {
                            "manual" -> audioFingerprintAdapter?.stopPlaying()
                            "candidate" -> candidateAdapter?.stopPlaying()
                        }
                        if (dialog.isShowing) dialog.dismiss()
                    }
                }
            }.apply { start() }

            // 定期更新SeekBar
            seekBarUpdateRunnable = object : Runnable {
                override fun run() {
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
            Toast.makeText(ctx, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            releaseAudioTrack()
            if (dialog.isShowing) dialog.dismiss()
        }
    }

    /**
     * v3.1.62: 释放AudioTrack资源。
     */
    private fun releaseAudioTrack() {
        playbackThread?.interrupt()
        playbackThread = null
        try { playbackAudioTrack?.stop() } catch (_: Exception) {}
        try { playbackAudioTrack?.release() } catch (_: Exception) {}
        playbackAudioTrack = null
    }

    /**
     * 从数据库加载数据并刷新列表。
     */
    private fun loadData() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    loadDataSync()
                } catch (e: Exception) {
                    Log.e(TAG, "loadData failed for type=$fingerprintType: ${e.message}")
                    null
                }
            }

            if (result == null) {
                tvEmpty.visibility = TextView.VISIBLE
                tvEmpty.text = "加载失败"
                recyclerView.visibility = RecyclerView.GONE
                return@launch
            }

            when (result) {
                is ManualResult -> {
                    audioFingerprintAdapter?.setItems(result.fingerprints)
                    updateEmptyState(result.fingerprints.isEmpty())
                    tvCount.text = "共 ${result.fingerprints.size} 条人工指纹"
                }
                is CandidateResult -> {
                    candidateAdapter?.setItems(result.candidates)
                    updateEmptyState(result.candidates.isEmpty())
                    tvCount.text = "共 ${result.candidates.size} 条候选指纹"
                }
                is AutomaticResult -> {
                    automaticAdapter?.setItems(result.fingerprints)
                    updateEmptyState(result.fingerprints.isEmpty())
                    tvCount.text = "共 ${result.fingerprints.size} 条自动指纹"
                }
            }
        }
    }

    /**
     * 在 IO 线程同步加载数据。
     */
    private fun loadDataSync(): Any {
        return when (fingerprintType) {
            "manual" -> {
                val all = dbHelper.getAllAudioFingerprints()
                val gold = all.filter { it.isGoldStandard }
                ManualResult(gold)
            }
            "candidate" -> {
                val candidates = dbHelper.getAllObservationPoolCandidates()
                CandidateResult(candidates)
            }
            "automatic" -> {
                val all = dbHelper.getAllAudioFingerprints()
                val auto = all.filter { !it.isGoldStandard }
                AutomaticResult(auto)
            }
            else -> ManualResult(emptyList())
        }
    }

    /**
     * 更新空状态显示。
     */
    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            tvEmpty.visibility = TextView.VISIBLE
            tvEmpty.text = when (fingerprintType) {
                "manual" -> "暂无人工指纹"
                "candidate" -> "暂无候选指纹"
                "automatic" -> "暂无自动指纹"
                else -> "暂无数据"
            }
            recyclerView.visibility = RecyclerView.GONE
        } else {
            tvEmpty.visibility = RecyclerView.GONE
            recyclerView.visibility = RecyclerView.VISIBLE
        }
    }

    // ===== 删除操作 =====

    /**
     * 确认删除音频指纹（人工 / 自动）。
     */
    private fun confirmDeleteFingerprint(fp: AudioFingerprint) {
        val label = when (fingerprintType) {
            "manual" -> "人工指纹"
            "automatic" -> "自动指纹"
            else -> "指纹"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("删除$label")
            .setMessage("确定删除该指纹吗？\n${fp.episodeId} [${formatMs(fp.startMs)}-${formatMs(fp.endMs)}]")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        try {
                            dbHelper.deleteAudioFingerprint(fp.id)
                        } catch (e: Exception) {
                            Log.e(TAG, "deleteAudioFingerprint failed: ${e.message}")
                            0
                        }
                    }
                    if (deleted > 0) {
                        Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                        loadData()
                    } else {
                        Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 确认删除候选指纹。
     */
    private fun confirmDeleteCandidate(candidate: ObservationPoolCandidate) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除候选指纹")
            .setMessage("确定删除该候选指纹吗？\n${candidate.episodeId} (${candidate.fingerprintHash.take(8)}...)")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        try {
                            dbHelper.deleteObservationPoolCandidate(candidate.id)
                            1
                        } catch (e: Exception) {
                            Log.e(TAG, "deleteObservationPoolCandidate failed: ${e.message}")
                            0
                        }
                    }
                    if (deleted > 0) {
                        Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                        loadData()
                    } else {
                        Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun formatMs(ms: Long): String {
        val s = (ms / 1000).toInt()
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    // ===== 音频指纹素材列表测试组菜单 =====

    /**
     * v3.1.65: 显示音频指纹素材列表测试组菜单，与 KeywordSettingsActivity 保持一致。
     */
    private fun showFingerprintTestDialog(fp: AudioFingerprint) {
        val options = arrayOf("指纹 vs 自身 PCM", "指纹 vs 完整节目 PCM(滑动搜索)", "指纹 vs 所有完整 PCM(滑动搜索)", "所有指纹 vs 所有完整 PCM(滑动搜索)", "跨节目指纹直接对比", "指纹分组测试(≥95%)", "指纹分组管理(独立页面)")
        AlertDialog.Builder(requireContext())
            .setTitle("音频指纹匹配测试")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> runFingerprintSelfTest(fp)
                    1 -> runFingerprintFullEpisodeTest(fp)
                    2 -> runFingerprintVsAllPcmTest(fp)
                    3 -> runAllFingerprintsVsAllPcmTest()
                    4 -> runCrossEpisodeFingerprintTest(fp)
                    5 -> runFingerprintGroupTest()
                    6 -> startActivity(Intent(requireContext(), FingerprintGroupActivity::class.java))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runFingerprintSelfTest(fp: AudioFingerprint) {
        val pcmFile = PcmSegmentExtractor.getWatermarkPcmFile(requireContext(), fp.episodeId, fp.startMs, fp.endMs)
        if (!pcmFile.exists() || pcmFile.length() <= 0) {
            Toast.makeText(requireContext(), "自身 PCM 不存在，请先播放或重新生成", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (!ChromaprintExtractor.ensureLibraryLoaded(requireContext())) {
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
            AlertDialog.Builder(requireContext())
                .setTitle("指纹 vs 自身 PCM")
                .setMessage(result)
                .setPositiveButton("确定", null)
                .setNeutralButton("播放指纹片段") { _, _ -> playPcmFileWithSeekBar(pcmFile, fp.durationMs) }
                .show()
        }
    }

    /**
     * v3.1.7: 升级为完整PCM滑动搜索。
     */
    private fun runFingerprintFullEpisodeTest(fp: AudioFingerprint) {
        lifecycleScope.launch {
            val pcmCacheDir = RadioApplication.getPcmCacheDir(requireContext())
            val fullPcm = File(pcmCacheDir, "${fp.episodeId}_full.pcm")
            val min5Pcm = File(pcmCacheDir, "${fp.episodeId}_5min.pcm")
            val pcmSource = if (fullPcm.exists() && fullPcm.length() > 1024 * 100) fullPcm
            else if (min5Pcm.exists() && min5Pcm.length() > 16000) min5Pcm
            else null
            if (pcmSource == null) {
                Toast.makeText(requireContext(), "完整节目 PCM 不存在", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (!ChromaprintExtractor.ensureLibraryLoaded(requireContext())) {
                        return@withContext "Chromaprint 库未加载"
                    }
                    val detail = ChromaprintExtractor.searchFingerprintInPcm(pcmSource, fp.fingerprint)
                    val match = detail.similarity >= 0.70f
                    "节目 PCM 滑动搜索匹配结果:\n相似度: %.2f%%\n是否匹配: %s\n原始指纹长度: %d\nPCM 指纹长度: %d\n最佳偏移: ${detail.bestOffset} samples (约 %.1f 秒)\n原始相似度(不含长度惩罚): %.2f%%\n位误差: %d/%d bits\n长度惩罚: %.2f".format(
                        detail.similarity * 100, if (match) "是" else "否",
                        detail.len1, detail.len2, detail.bestOffset * 1000.0 / 16000.0,
                        detail.rawSimilarity * 100, detail.minErrors, detail.totalBits, detail.lengthPenalty
                    )
                }.getOrElse { "测试异常: ${it.message}" }
            }
            AlertDialog.Builder(requireContext())
                .setTitle("指纹 vs 完整节目 PCM")
                .setMessage(result)
                .setPositiveButton("确定", null)
                .show()
        }
    }

    private fun runFingerprintVsAllPcmTest(fp: AudioFingerprint) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (!ChromaprintExtractor.ensureLibraryLoaded(requireContext())) {
                        return@withContext "Chromaprint 库未加载"
                    }
                    val pcmCacheDir = RadioApplication.getPcmCacheDir(requireContext())
                    val allPcmFiles = pcmCacheDir.listFiles { f -> f.name.endsWith("_full.pcm") && f.length() > 1024 * 100 }?.toList() ?: emptyList()
                    if (allPcmFiles.isEmpty()) return@withContext "没有找到任何完整 PCM 文件"
                    val sb = StringBuilder("共扫描 ${allPcmFiles.size} 个完整 PCM 文件:\n\n")
                    var bestMatch = ""
                    var bestSimilarity = 0f
                    for (pcmFile in allPcmFiles) {
                        val detail = ChromaprintExtractor.searchFingerprintInPcm(pcmFile, fp.fingerprint)
                        if (detail.similarity > bestSimilarity) {
                            bestSimilarity = detail.similarity
                            bestMatch = pcmFile.name
                        }
                        sb.append("${pcmFile.name}: ${"%.1f".format(detail.similarity * 100)}% (${if (detail.similarity >= 0.70f) "匹配" else "不匹配"})\n")
                    }
                    sb.append("\n最佳匹配: $bestMatch (${"%.1f".format(bestSimilarity * 100)}%)")
                    sb.toString()
                }.getOrElse { "测试异常: ${it.message}" }
            }
            AlertDialog.Builder(requireContext())
                .setTitle("指纹 vs 所有完整 PCM")
                .setMessage(result)
                .setPositiveButton("确定", null)
                .show()
        }
    }

    private fun runAllFingerprintsVsAllPcmTest() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (!ChromaprintExtractor.ensureLibraryLoaded(requireContext())) {
                        return@withContext "Chromaprint 库未加载"
                    }
                    val allFps = dbHelper.getAllAudioFingerprints().filter { it.isGoldStandard }
                    if (allFps.isEmpty()) return@withContext "没有金标准指纹"
                    val pcmCacheDir = RadioApplication.getPcmCacheDir(requireContext())
                    val allPcmFiles = pcmCacheDir.listFiles { f -> f.name.endsWith("_full.pcm") && f.length() > 1024 * 100 }?.toList() ?: emptyList()
                    if (allPcmFiles.isEmpty()) return@withContext "没有找到任何完整 PCM 文件"
                    val sb = StringBuilder("指纹库: ${allFps.size} 条, PCM 文件: ${allPcmFiles.size} 个\n\n")
                    var matchCount = 0
                    for (fp in allFps) {
                        for (pcmFile in allPcmFiles) {
                            val detail = ChromaprintExtractor.searchFingerprintInPcm(pcmFile, fp.fingerprint)
                            if (detail.similarity >= 0.70f) {
                                matchCount++
                                sb.append("匹配: ${fp.episodeId} [${formatMs(fp.startMs)}-${formatMs(fp.endMs)}] → ${pcmFile.name} (${"%.1f".format(detail.similarity * 100)}%)\n")
                            }
                        }
                    }
                    sb.append("\n共 $matchCount 个匹配")
                    sb.toString()
                }.getOrElse { "测试异常: ${it.message}" }
            }
            AlertDialog.Builder(requireContext())
                .setTitle("所有指纹 vs 所有完整 PCM")
                .setMessage(result)
                .setPositiveButton("确定", null)
                .show()
        }
    }

    private fun runCrossEpisodeFingerprintTest(fp: AudioFingerprint) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (!ChromaprintExtractor.ensureLibraryLoaded(requireContext())) {
                        return@withContext "Chromaprint 库未加载"
                    }
                    val allFps = dbHelper.getAllAudioFingerprints().filter { it.isGoldStandard && it.id != fp.id }
                    if (allFps.isEmpty()) return@withContext "没有其他指纹可对比"
                    val sb = StringBuilder("当前指纹: ${fp.episodeId} [${formatMs(fp.startMs)}-${formatMs(fp.endMs)}]\n\n对比结果:\n")
                    var bestMatch = ""
                    var bestSimilarity = 0f
                    for (other in allFps) {
                        val detail = ChromaprintExtractor.compareFingerprintsDetailed(fp.fingerprint, other.fingerprint)
                        if (detail.similarity > bestSimilarity) {
                            bestSimilarity = detail.similarity
                            bestMatch = "${other.episodeId} [${formatMs(other.startMs)}-${formatMs(other.endMs)}]"
                        }
                        sb.append("${other.episodeId} [${formatMs(other.startMs)}-${formatMs(other.endMs)}]: ${"%.1f".format(detail.similarity * 100)}% (${if (detail.similarity >= 0.70f) "匹配" else "不匹配"})\n")
                    }
                    sb.append("\n最佳匹配: $bestMatch (${"%.1f".format(bestSimilarity * 100)}%)")
                    sb.toString()
                }.getOrElse { "测试异常: ${it.message}" }
            }
            AlertDialog.Builder(requireContext())
                .setTitle("跨节目指纹直接对比")
                .setMessage(result)
                .setPositiveButton("确定", null)
                .show()
        }
    }

    private fun runFingerprintGroupTest() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (!ChromaprintExtractor.ensureLibraryLoaded(requireContext())) {
                        return@withContext "Chromaprint 库未加载"
                    }
                    val allFps = dbHelper.getAllAudioFingerprints().filter { it.isGoldStandard }
                    if (allFps.isEmpty()) return@withContext "没有金标准指纹"
                    val groups = mutableListOf<MutableList<AudioFingerprint>>()
                    for (fp in allFps) {
                        var added = false
                        for (group in groups) {
                            val ref = group[0]
                            val detail = ChromaprintExtractor.compareFingerprintsDetailed(ref.fingerprint, fp.fingerprint)
                            if (detail.similarity >= 0.95f) {
                                group.add(fp)
                                added = true
                                break
                            }
                        }
                        if (!added) {
                            groups.add(mutableListOf(fp))
                        }
                    }
                    val sb = StringBuilder("指纹分组测试结果 (≥95% 相似度分组):\n\n")
                    for ((i, group) in groups.withIndex()) {
                        sb.append("组${i + 1} (${group.size}条):\n")
                        for (fp in group) {
                            sb.append("  ${fp.episodeId} [${formatMs(fp.startMs)}-${formatMs(fp.endMs)}]\n")
                        }
                        sb.append("\n")
                    }
                    sb.append("共 ${groups.size} 组")
                    sb.toString()
                }.getOrElse { "测试异常: ${it.message}" }
            }
            AlertDialog.Builder(requireContext())
                .setTitle("指纹分组测试")
                .setMessage(result)
                .setPositiveButton("确定", null)
                .show()
        }
    }

    // ===== 内部数据封装 =====

    private class ManualResult(val fingerprints: List<AudioFingerprint>)
    private class CandidateResult(val candidates: List<ObservationPoolCandidate>)
    private class AutomaticResult(val fingerprints: List<AudioFingerprint>)
}