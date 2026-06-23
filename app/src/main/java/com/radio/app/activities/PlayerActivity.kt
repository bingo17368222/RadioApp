package com.radio.app.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.radio.app.databinding.ActivityPlayerBinding
import com.radio.app.models.Episode
import com.radio.app.models.RadioStation
import com.radio.app.models.VoiceSegment
import com.radio.app.services.RadioPlaybackService
import com.radio.app.adapters.VoiceSegmentAdapter
import com.radio.app.services.SubtitleGeneratorService
import com.radio.app.models.AppSettings
import com.radio.app.database.RadioDatabaseHelper
import com.radio.app.utils.PreferenceManager

class PlayerActivity : AppCompatActivity() {

    private var _binding: ActivityPlayerBinding? = null
    private val binding: ActivityPlayerBinding
        get() = _binding ?: throw IllegalStateException("Binding is only valid between onCreate and onDestroy")

    private var playbackService: RadioPlaybackService? = null
    private var serviceBound = false
    private var currentEpisode: Episode? = null
    private var currentStation: RadioStation? = null
    private var voiceSegments: List<VoiceSegment> = emptyList()
    private var segmentAdapter: VoiceSegmentAdapter? = null
    private var subtitleService: SubtitleGeneratorService? = null
    private var subtitleServiceBound = false
    private var hasError = false
    private var hasErrorToastShown = false
    private var episodeList: ArrayList<Episode> = ArrayList()
    private var currentEpisodeIndex = -1
    private var isDragging = false
    private var cacheProgressHandler: Handler? = null
    private var cacheProgressRunnable: Runnable? = null
    private var subtitleProcessing = false
    private var segmentProcessing = false
    private var pendingAiTaskType: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? RadioPlaybackService.LocalBinder ?: return
            playbackService = binder.getService()
            serviceBound = true
            playbackService?.setCallback(playbackCallback)
            
            // 多层抖动防护
            val svcPlaying = playbackService?.isPlaying() ?: false
            val svcPrepared = playbackService?.isPrepared() ?: false
            val svcEpisode = playbackService?.getCurrentEpisode()
            val newUrl = currentEpisode?.audioUrl
            val svcUrl = playbackService?.getCurrentStreamUrl()
            
            // 1. 服务正在播放且URL完全匹配 → 跳过，只更新UI
            if (svcPlaying && svcUrl != null && newUrl != null && svcUrl == newUrl) {
                updateUI()
                startCacheProgressUpdater()
                restoreBackgroundResults()
                return@onServiceConnected
            }
            // 2. 服务正在播放且节目相同（通过stationId+title比较） → 跳过
            if (svcPlaying && svcEpisode != null && currentEpisode != null) {
                val sameEpisode = svcEpisode.stationId == currentEpisode!!.stationId && 
                                 svcEpisode.title == currentEpisode!!.title
                if (sameEpisode) {
                    updateUI()
                    startCacheProgressUpdater()
                    restoreBackgroundResults()
                    return@onServiceConnected
                }
            }
            // 3. 服务已准备就绪且URL匹配（即使暂停中） → 跳过
            if (svcPrepared && svcUrl != null && newUrl != null && svcUrl == newUrl) {
                updateUI()
                startCacheProgressUpdater()
                restoreBackgroundResults()
                return@onServiceConnected
            }
            // 4. 服务正在播放（任何节目） → 跳过，只更新UI
            if (svcPlaying) {
                updateUI()
                startCacheProgressUpdater()
                return@onServiceConnected
            }
            
            // 只有服务未播放时才启动新播放
            if (currentStation != null) {
                playbackService?.playStation(currentStation!!)
            } else {
                val audioUrl = currentEpisode?.audioUrl
                if (!audioUrl.isNullOrBlank()) {
                    currentEpisode?.let { episode ->
                        playbackService?.playEpisode(episode, false)
                    }
                }
            }
            updateUI()
            // Auto-generate simulated segments if none exist
            if (voiceSegments.isEmpty() && currentEpisode != null) {
                voiceSegments = generateSimulatedSegments()
                if (voiceSegments.isNotEmpty()) {
                    updateSegmentsUI()
                }
            }
            startCacheProgressUpdater()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            serviceBound = false
        }
    }

    private val playbackCallback = object : RadioPlaybackService.Callback {
        override fun onStateChanged(playing: Boolean) {
            runOnUiThread {
                if (_binding == null) return@runOnUiThread
                updatePlayPauseButton(playing)
                if (playing) {
                    hasError = false
                    hasErrorToastShown = false
                    binding.tvAiProgress.visibility = View.GONE
                }
                binding.tvLiveIndicator.text = if (hasError) "播放失败" else if (playing) "播放中" else "已暂停"
                binding.tvLiveIndicator.visibility = if (playing || hasError) View.VISIBLE else View.GONE
            }
        }

        override fun onPositionChanged(position: Long, duration: Long) {
            runOnUiThread {
                if (_binding == null) return@runOnUiThread
                val pos = position.toInt()
                val dur = duration.toInt()
                if (isDragging) return@runOnUiThread
                if (dur > 0) {
                    binding.seekBar.max = dur
                    binding.seekBar.progress = pos
                    binding.tvCurrentTime.text = "${formatTime(pos)} / ${formatTime(dur)}"
                    binding.tvTotalTime.text = formatTime(dur)
                    binding.tvLiveIndicator.text = "播放中"
                    // 同步字幕显示
                    binding.subtitleView.setCurrentPosition(position)
                } else if (playbackService?.isLive() == true) {
                    binding.tvCurrentTime.text = "直播 ${formatTime(pos)}"
                    binding.seekBarCache.visibility = View.GONE
                    binding.tvCacheProgress.visibility = View.GONE
                } else {
                    binding.tvCurrentTime.text = "缓冲中 ${formatTime(pos)}"
                }
            }
        }

        override fun onBufferUpdate(percent: Int) {
            runOnUiThread {
                if (_binding == null) return@runOnUiThread
                if (hasError) return@runOnUiThread
                binding.tvAiProgress.text = "缓冲: ${percent}%"
                binding.tvAiProgress.visibility = if (percent >= 100) View.GONE else View.VISIBLE
                binding.progressBuffer.progress = percent
                binding.progressBuffer.visibility = if (percent >= 100) View.GONE else View.VISIBLE
            }
        }

        override fun onError(errorMessage: String) {
            runOnUiThread {
                if (_binding == null) return@runOnUiThread
                hasError = true
                binding.tvLiveIndicator.text = "播放失败"
                binding.tvAiProgress.text = errorMessage
                binding.tvAiProgress.visibility = View.VISIBLE
                if (!hasErrorToastShown) {
                    hasErrorToastShown = true
                    Toast.makeText(this@PlayerActivity, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startCacheProgressUpdater() {
        cacheProgressHandler?.removeCallbacksAndMessages(null)
        cacheProgressHandler = Handler(Looper.getMainLooper())
        cacheProgressRunnable = Runnable {
            if (_binding == null) return@Runnable
            try {
                val svc = playbackService ?: return@Runnable
                val dur = svc.getDuration()
                if (dur <= 0) {
                    runOnUiThread {
                        if (_binding == null) return@runOnUiThread
                        binding.seekBarCache.visibility = View.GONE
                        binding.tvCacheProgress.visibility = View.GONE
                    }
                    cacheProgressHandler?.postDelayed(cacheProgressRunnable!!, 2000)
                    return@Runnable
                }
                val cachePct = svc.getDownloadProgress()

                runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    binding.seekBarCache.max = dur.toInt()
                    binding.seekBarCache.progress = ((dur * cachePct) / 100).toInt()
                    binding.tvCacheProgress.text = "缓存: $cachePct%"
                    binding.tvCacheProgress.visibility = View.VISIBLE
                    binding.seekBarCache.visibility = View.VISIBLE
                }
            } catch (_: Exception) {}
            cacheProgressHandler?.postDelayed(cacheProgressRunnable!!, 2000)
        }
        cacheProgressRunnable?.let { cacheProgressHandler?.post(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentEpisode = intent.getSerializableExtra("episode") as? Episode
        if (currentEpisode == null) {
            val audioUrl = intent.getStringExtra("audio_url") ?: intent.getStringExtra("stream_url")
            if (audioUrl.isNullOrBlank()) {
                Toast.makeText(this, "播放地址无效，无法播放", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            val isLive = intent.getBooleanExtra("is_live", false)
            if (isLive) {
                currentStation = RadioStation().apply {
                    id = intent.getStringExtra("station_id") ?: ""
                    name = intent.getStringExtra("station_name") ?: ""
                    streamUrl = audioUrl
                    this.isLive = true
                }
                currentEpisode = Episode().apply {
                    id = intent.getStringExtra("station_id") ?: ""
                    title = intent.getStringExtra("station_name") ?: ""
                    this.audioUrl = audioUrl
                    stationName = intent.getStringExtra("station_name") ?: ""
                    this.isLive = true
                }
            } else {
                currentEpisode = Episode().apply {
                    id = intent.getStringExtra("episode_id") ?: ""
                    title = intent.getStringExtra("title") ?: ""
                    this.audioUrl = audioUrl
                    stationName = intent.getStringExtra("station_name") ?: ""
                    duration = intent.getLongExtra("duration", 0)
                    this.isLive = false
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        voiceSegments = (intent.getSerializableExtra("voice_segments") as? ArrayList<VoiceSegment>) ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        episodeList = (intent.getSerializableExtra("episode_list") as? ArrayList<Episode>) ?: ArrayList()
        currentEpisodeIndex = intent.getIntExtra("episode_index", -1)

        if (currentEpisode?.audioUrl.isNullOrBlank()) {
            Toast.makeText(this, "播放地址无效，无法播放", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        setupListeners()
        bindPlaybackService()
    }

    private fun initViews() {
        binding.tvStationName.text = currentEpisode?.title
        binding.tvNetworkUrl.text = "网络: ${currentEpisode?.audioUrl}"
        binding.tvNetworkUrl.visibility = View.VISIBLE

        val isLive = currentEpisode?.isLive ?: false
        if (!isLive) {
            val audioUrl = currentEpisode?.audioUrl ?: ""
            val cacheFileName = extractCacheFileName(audioUrl)
            binding.tvCacheUrl.text = "本地缓存: ${cacheDir.absolutePath}/episodes/$cacheFileName"
            binding.tvCacheUrl.visibility = View.VISIBLE
            binding.tvCacheProgress.visibility = View.VISIBLE
            binding.seekBarCache.visibility = View.VISIBLE
        } else {
            binding.tvCacheUrl.visibility = View.GONE
            binding.tvCacheProgress.visibility = View.GONE
            binding.seekBarCache.visibility = View.GONE
        }
        binding.tvLiveIndicator.text = "准备播放..."
        binding.tvLiveIndicator.visibility = View.VISIBLE
        binding.tvCurrentTime.text = "00:00 / 00:00"
        binding.tvTotalTime.text = "00:00"
        binding.tvCacheProgress.text = "缓存: 0%"
        binding.progressBuffer.visibility = View.GONE
        binding.tvAiProgress.visibility = View.GONE
        binding.progressAi.visibility = View.GONE
        binding.tvAiStatus.visibility = View.GONE

        segmentAdapter = VoiceSegmentAdapter()
        segmentAdapter?.setOnSegmentClickListener(object : VoiceSegmentAdapter.OnSegmentClickListener {
            override fun onSegmentClick(position: Int, segment: VoiceSegment) {
                playbackService?.seekTo(segment.start)
            }
            override fun onSegmentLongClick(position: Int, segment: VoiceSegment) {
                val isDry = !segment.isEffectiveDry()
                playbackService?.markSegment(position, isDry)
                segmentAdapter?.notifyItemChanged(position)
            }
        })
        binding.recyclerSegments.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.recyclerSegments.adapter = segmentAdapter
        updateSegmentsUI()

        val isLiveNav = currentEpisode?.isLive ?: false
        if (!isLiveNav && episodeList.size > 1 && currentEpisodeIndex >= 0) {
            binding.layoutEpisodeNav.visibility = View.VISIBLE
            binding.tvEpisodeNavHint.text = " ${currentEpisodeIndex + 1}/${episodeList.size} "
            binding.btnPrevEpisode.setOnClickListener {
                if (currentEpisodeIndex > 0) {
                    playEpisodeAtIndex(currentEpisodeIndex - 1)
                }
            }
            binding.btnNextEpisode.setOnClickListener {
                if (currentEpisodeIndex < episodeList.size - 1) {
                    playEpisodeAtIndex(currentEpisodeIndex + 1)
                }
            }
        } else {
            binding.layoutEpisodeNav.visibility = View.GONE
        }
    }

    private fun extractCacheFileName(url: String): String {
        return try {
            val path = java.net.URL(url).path
            val name = path.substringAfterLast("/")
            if (name.isBlank()) "unknown.mp4" else name
        } catch (e: Exception) {
            val name = url.substringAfterLast("/")
            if (name.isBlank()) "unknown.mp4" else name
        }
    }

    private fun setupListeners() {
        binding.btnPlayPause.setOnClickListener {
            playbackService?.let { service ->
                if (service.isPlaying()) service.pause() else service.play()
            }
        }
        binding.btnPrevSegment.setOnClickListener { playbackService?.jumpToPrevSegment() }
        binding.btnNextSegment.setOnClickListener { playbackService?.jumpToNextSegment() }
        binding.btnSkipForward.setOnClickListener { playbackService?.skipForward() }
        binding.btnSkipBackward.setOnClickListener { playbackService?.skipBackward() }
        binding.btnClose.setOnClickListener { finish() }

        binding.btnGenerateSubtitle.setOnClickListener {
            val episode = currentEpisode ?: return@setOnClickListener
            if (episode.id.isBlank()) {
                Toast.makeText(this, "无法生成字幕：缺少节目ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (subtitleProcessing) return@setOnClickListener
            startAiProcessing("subtitle")
            bindSubtitleService(episode, "subtitle")
        }

        binding.btnAiSegment.setOnClickListener {
            val episode = currentEpisode ?: return@setOnClickListener
            if (episode.id.isBlank()) {
                Toast.makeText(this, "无法AI分段：缺少节目ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (segmentProcessing) return@setOnClickListener
            startAiProcessing("segment")
            bindSubtitleService(episode, "segment")
        }

        binding.btnSubtitleToggle.setOnClickListener {
            if (binding.subtitleView.visibility == View.VISIBLE) {
                binding.subtitleView.visibility = View.GONE
                binding.recyclerSegments.visibility = View.VISIBLE
            } else {
                binding.recyclerSegments.visibility = View.GONE
                binding.subtitleView.visibility = View.VISIBLE
            }
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = seekBar?.max ?: 0
                    binding.tvCurrentTime.text = "${formatTime(progress)} / ${formatTime(dur)}"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isDragging = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isDragging = false
                seekBar?.let { playbackService?.seekTo(it.progress.toLong()) }
            }
        })
    }

    private fun bindPlaybackService() {
        val intent = Intent(this, RadioPlaybackService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private val subtitleServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? SubtitleGeneratorService.LocalBinder ?: return
            subtitleService = binder.getService()
            subtitleServiceBound = true
            val episode = currentEpisode ?: return
            val taskType = pendingAiTaskType
            pendingAiTaskType = null
            if (taskType == "segment") {
                subtitleService?.generateSegmentsForEpisode(
                    episode.id, episode.audioUrl,
                    object : SubtitleGeneratorService.SegmentCallback {
                        override fun onSegmentGenerated(segment: VoiceSegment) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                val updated = voiceSegments.toMutableList()
                                updated.add(segment)
                                voiceSegments = updated
                                segmentAdapter?.setSegments(voiceSegments)
                                binding.recyclerSegments.visibility = View.VISIBLE
                            }
                        }
                        override fun onProgressUpdate(progress: Int, total: Int) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                binding.progressAi.progress = progress
                                binding.tvAiStatus.text = buildStatusText("segment", progress)
                            }
                        }
                        override fun onComplete(segments: List<VoiceSegment>) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                voiceSegments = voiceSegments.filter { !it.isSimulated }
                                voiceSegments = segments
                                segmentAdapter?.setSegments(segments)
                                binding.recyclerSegments.visibility = View.VISIBLE
                                finishAiProcessing("segment")
                                Toast.makeText(this@PlayerActivity, "AI分段完成，共${segments.size}个片段", Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun onError(error: String) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                finishAiProcessing("segment")
                                binding.tvAiStatus.text = "AI分段失败: $error"
                                binding.tvAiStatus.visibility = View.VISIBLE
                                Toast.makeText(this@PlayerActivity, error, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            } else {
                subtitleService?.generateSubtitlesForEpisode(
                    episode.id, episode.audioUrl,
                    object : SubtitleGeneratorService.SubtitleCallback {
                        private val subtitleList = mutableListOf<com.radio.app.models.Transcript>()
                        override fun onSubtitleGenerated(transcript: com.radio.app.models.Transcript) {
                            subtitleList.add(transcript)
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                binding.subtitleView.setSubtitles(subtitleList)
                                binding.subtitleView.visibility = View.VISIBLE
                                binding.recyclerSegments.visibility = View.GONE
                            }
                        }
                        override fun onProgressUpdate(progress: Int, total: Int) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                binding.progressSubtitle.progress = progress
                                binding.tvSubtitleStatus.text = "字幕生成: $progress% (引擎: ${getCurrentAsrLabel()})"
                            }
                        }
                        override fun onComplete(transcripts: List<com.radio.app.models.Transcript>) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                finishAiProcessing("subtitle")
                                Toast.makeText(this@PlayerActivity, "字幕生成完成，共${transcripts.size}条", Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun onError(error: String) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                finishAiProcessing("subtitle")
                                binding.tvSubtitleStatus.text = "字幕生成失败: $error"
                                binding.tvSubtitleStatus.visibility = View.VISIBLE
                                Toast.makeText(this@PlayerActivity, error, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            subtitleService = null
            subtitleServiceBound = false
        }
    }

    private fun bindSubtitleService(episode: Episode, taskType: String) {
        if (subtitleServiceBound && subtitleService != null) {
            if (taskType == "segment") {
                subtitleService?.generateSegmentsForEpisode(
                    episode.id, episode.audioUrl,
                    object : SubtitleGeneratorService.SegmentCallback {
                        override fun onSegmentGenerated(segment: VoiceSegment) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                val updated = voiceSegments.toMutableList()
                                updated.add(segment)
                                voiceSegments = updated
                                segmentAdapter?.setSegments(voiceSegments)
                                binding.recyclerSegments.visibility = View.VISIBLE
                            }
                        }
                        override fun onProgressUpdate(progress: Int, total: Int) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                binding.progressAi.progress = progress
                                binding.tvAiStatus.text = buildStatusText("segment", progress)
                            }
                        }
                        override fun onComplete(segments: List<VoiceSegment>) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                voiceSegments = voiceSegments.filter { !it.isSimulated }
                                voiceSegments = segments
                                segmentAdapter?.setSegments(segments)
                                binding.recyclerSegments.visibility = View.VISIBLE
                                finishAiProcessing("segment")
                                Toast.makeText(this@PlayerActivity, "AI分段完成，共${segments.size}个片段", Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun onError(error: String) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                finishAiProcessing("segment")
                                binding.tvAiStatus.text = "AI分段失败: $error"
                                binding.tvAiStatus.visibility = View.VISIBLE
                                Toast.makeText(this@PlayerActivity, error, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            } else {
                subtitleService?.generateSubtitlesForEpisode(
                    episode.id, episode.audioUrl,
                    object : SubtitleGeneratorService.SubtitleCallback {
                        private val subtitleList = mutableListOf<com.radio.app.models.Transcript>()
                        override fun onSubtitleGenerated(transcript: com.radio.app.models.Transcript) {
                            subtitleList.add(transcript)
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                binding.subtitleView.setSubtitles(subtitleList)
                                binding.subtitleView.visibility = View.VISIBLE
                                binding.recyclerSegments.visibility = View.GONE
                            }
                        }
                        override fun onProgressUpdate(progress: Int, total: Int) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                binding.progressSubtitle.progress = progress
                                binding.tvSubtitleStatus.text = "字幕生成: $progress% (引擎: ${getCurrentAsrLabel()})"
                            }
                        }
                        override fun onComplete(transcripts: List<com.radio.app.models.Transcript>) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                finishAiProcessing("subtitle")
                                Toast.makeText(this@PlayerActivity, "字幕生成完成，共${transcripts.size}条", Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun onError(error: String) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                finishAiProcessing("subtitle")
                                binding.tvSubtitleStatus.text = "字幕生成失败: $error"
                                binding.tvSubtitleStatus.visibility = View.VISIBLE
                                Toast.makeText(this@PlayerActivity, error, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
            return
        }
        pendingAiTaskType = taskType
        val ep = currentEpisode ?: return
        // Start foreground service to keep running in background
        val intent = Intent(this, SubtitleGeneratorService::class.java).apply {
            putExtra("episode_id", ep.id)
            putExtra("audio_url", ep.audioUrl)
            putExtra("task_type", taskType)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, subtitleServiceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun updateUI() {
        if (_binding == null) return
        playbackService?.let { updatePlayPauseButton(it.isPlaying()) }
    }

    private fun playEpisodeAtIndex(index: Int) {
        if (index < 0 || index >= episodeList.size) return
        currentEpisodeIndex = index
        val episode = episodeList[index]
        currentEpisode = episode
        if (_binding == null) return
        binding.tvStationName.text = episode.title
        binding.tvNetworkUrl.text = "网络: ${episode.audioUrl}"
        binding.tvNetworkUrl.visibility = View.VISIBLE
        val cacheFileName = extractCacheFileName(episode.audioUrl ?: "")
        binding.tvCacheUrl.text = "本地缓存: ${cacheDir.absolutePath}/episodes/$cacheFileName"
        binding.tvCacheUrl.visibility = View.VISIBLE
        binding.tvCacheProgress.text = "缓存: 0%"
        binding.tvCacheProgress.visibility = View.VISIBLE
        binding.seekBarCache.visibility = View.VISIBLE
        binding.tvEpisodeNavHint.text = " ${index + 1}/${episodeList.size} "
        binding.tvLiveIndicator.text = "准备播放..."
        binding.tvLiveIndicator.visibility = View.VISIBLE
        binding.tvCurrentTime.text = "00:00 / 00:00"
        binding.tvTotalTime.text = "00:00"
        binding.seekBar.progress = 0
        binding.seekBarCache.progress = 0
        binding.progressBuffer.progress = 0
        hasError = false
        hasErrorToastShown = false
        playbackService?.playEpisode(episode, false)
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        if (_binding == null) return
        binding.btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    private fun formatTime(millis: Int): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", minutes, secs)
    }

    private fun getCurrentAiModelLabel(): String {
        val settings = AppSettings.getInstance(this)
        return when (settings.safeAiModel()) {
            AppSettings.AI_MODEL_WENXIN -> "文心一言"
            AppSettings.AI_MODEL_DEEPSEEK -> "DeepSeek"
            AppSettings.AI_MODEL_QWEN -> "通义千问"
            AppSettings.AI_MODEL_FUNASR -> "FunASR"
            AppSettings.AI_MODEL_WHISPER -> "Whisper"
            AppSettings.AI_MODEL_JIU_AI_TING -> "就AI听"
            AppSettings.AI_MODEL_MNN_LLM -> "阿里MNN-LLM"
            else -> settings.safeAiModel()
        }
    }

    private fun getCurrentAsrLabel(): String {
        val settings = AppSettings.getInstance(this)
        return when (settings.safeAsrProvider()) {
            AppSettings.ASR_BAIDU -> "百度ASR"
            AppSettings.ASR_FUNASR -> "FunASR"
            AppSettings.ASR_WHISPER -> "Whisper"
            AppSettings.ASR_VOSK -> "Vosk离线"
            else -> settings.safeAsrProvider()
        }
    }

    private fun startAiProcessing(taskType: String) {
        if (taskType == "subtitle") subtitleProcessing = true
        else if (taskType == "segment") segmentProcessing = true
        if (_binding == null) return
        if (taskType == "subtitle") {
            binding.progressSubtitle.progress = 0
            binding.progressSubtitle.visibility = View.VISIBLE
            binding.tvSubtitleStatus.visibility = View.VISIBLE
            binding.tvSubtitleStatus.text = "字幕生成: 0% (引擎: ${getCurrentAsrLabel()})"
            binding.btnGenerateSubtitle.isEnabled = false
        } else {
            binding.progressAi.progress = 0
            binding.progressAi.visibility = View.VISIBLE
            binding.tvAiStatus.visibility = View.VISIBLE
            binding.tvAiStatus.text = buildStatusText(taskType, 0)
            binding.btnAiSegment.isEnabled = false
        }
    }

    private fun finishAiProcessing(taskType: String) {
        if (taskType == "subtitle") {
            subtitleProcessing = false
            if (_binding != null) {
                binding.progressSubtitle.visibility = View.GONE
                binding.tvSubtitleStatus.visibility = View.GONE
                binding.btnGenerateSubtitle.isEnabled = true
            }
        } else {
            segmentProcessing = false
            if (_binding != null) {
                binding.progressAi.visibility = View.GONE
                binding.tvAiStatus.visibility = View.GONE
                binding.btnAiSegment.isEnabled = true
            }
        }
    }

    private fun buildStatusText(taskType: String, progress: Int): String {
        val modelLabel = if (taskType == "segment") getCurrentAiModelLabel() else getCurrentAsrLabel()
        return if (taskType == "segment") "AI分段: $progress% (模型: $modelLabel)" else "字幕生成: $progress% (引擎: $modelLabel)"
    }

    private fun generateSimulatedSegments(): List<VoiceSegment> {
        val dur = playbackService?.getDuration()?.toInt() ?: 0
        if (dur <= 0) return emptyList()
        val segmentDuration = 300 // 5分钟一段
        val count = minOf((dur / 1000 / segmentDuration).coerceAtLeast(3), 20)
        val segments = mutableListOf<VoiceSegment>()
        for (i in 0 until count) {
            val startMs = (i * dur) / count
            val endMs = if (i == count - 1) dur else ((i + 1) * dur) / count
            val seg = VoiceSegment().apply {
                this.start = startMs.toLong()
                this.end = endMs.toLong()
                this.label = "${formatTime(startMs.toLong())} - ${formatTime(endMs.toLong())}"
                this.isSimulated = true
            }
            segments.add(seg)
        }
        return segments
    }

    private fun updateSegmentsUI() {
        if (_binding == null) return
        segmentAdapter?.setSegments(voiceSegments)
        if (voiceSegments.isEmpty()) {
            binding.recyclerSegments.visibility = View.GONE
        } else {
            binding.recyclerSegments.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        // 恢复字幕生成和AI分段进度：如果服务仍在运行，重新绑定以获取进度更新
        if (!subtitleServiceBound) {
            val episode = currentEpisode
            if (episode != null && episode.id.isNotBlank()) {
                if (subtitleProcessing) {
                    bindSubtitleService(episode, "subtitle")
                }
                if (segmentProcessing) {
                    bindSubtitleService(episode, "segment")
                }
            }
        }

        restoreBackgroundResults()
    }

    private fun restoreBackgroundResults() {
        val episode = currentEpisode ?: return
        if (episode.id.isBlank()) return

        val dbHelper = RadioDatabaseHelper.getInstance(this)

        // 检查AI分段结果
        val dbSegments = dbHelper.getVoiceSegments(episode.id)
        if (dbSegments.isNotEmpty()) {
            val realSegments = dbSegments.filter { !it.isSimulated }
            if (realSegments.isNotEmpty() && (voiceSegments.isEmpty() || voiceSegments.all { it.isSimulated })) {
                voiceSegments = realSegments
                updateSegmentsUI()
            }
        }

        // 检查字幕结果
        val dbTranscripts = dbHelper.getTranscripts(episode.id)
        if (dbTranscripts.isNotEmpty()) {
            binding.subtitleView.setSubtitles(dbTranscripts)
            binding.subtitleView.visibility = View.VISIBLE
            binding.recyclerSegments.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cacheProgressRunnable?.let { cacheProgressHandler?.removeCallbacks(it) }
        if (serviceBound) {
            playbackService?.setCallback(null)
            unbindService(serviceConnection)
            serviceBound = false
        }
        if (subtitleServiceBound) {
            unbindService(subtitleServiceConnection)
            subtitleServiceBound = false
        }
        _binding = null
    }
}