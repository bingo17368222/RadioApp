package com.radio.app.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? RadioPlaybackService.LocalBinder ?: return
            playbackService = binder.getService()
            serviceBound = true
            playbackService?.setCallback(playbackCallback)
            if (currentStation != null) {
                playbackService?.playStation(currentStation!!)
            } else {
                val audioUrl = currentEpisode?.audioUrl
                if (!audioUrl.isNullOrBlank()) {
                    currentEpisode?.let { episode ->
                        // 始终以回放模式播放，不使用 episode.isLive（API可能标记错误）
                        playbackService?.playEpisode(episode, false)
                    }
                }
            }
            updateUI()
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
                val pos = position.toInt()
                val dur = duration.toInt()
                if (isDragging) return@runOnUiThread
                if (dur > 0) {
                    binding.seekBar.max = dur
                    binding.seekBar.progress = pos
                    binding.tvCurrentTime.text = "${formatTime(pos)} / ${formatTime(dur)}"
                    binding.tvTotalTime.text = formatTime(dur)
                    binding.tvLiveIndicator.text = "播放中"
                } else if (playbackService?.isLive() == true) {
                    binding.tvCurrentTime.text = "直播 ${formatTime(pos)}"
                    binding.seekBarCache.visibility = View.GONE
                    binding.tvCacheProgress.visibility = View.GONE
                } else {
                    // 回放模式但时长尚未加载，显示缓冲中
                    binding.tvCurrentTime.text = "缓冲中 ${formatTime(pos)}"
                }
            }
        }

        override fun onBufferUpdate(percent: Int) {
            runOnUiThread {
                if (hasError) return@runOnUiThread
                binding.tvAiProgress.text = "缓冲: ${percent}%"
                binding.tvAiProgress.visibility = if (percent >= 100) View.GONE else View.VISIBLE
                binding.progressBuffer.progress = percent
                binding.progressBuffer.visibility = if (percent >= 100) View.GONE else View.VISIBLE
            }
        }

        override fun onError(errorMessage: String) {
            runOnUiThread {
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
            try {
                val svc = playbackService ?: return@Runnable
                val dur = svc.getDuration()
                if (dur <= 0) {
                    // 直播模式不显示缓存
                    runOnUiThread {
                        binding.seekBarCache.visibility = View.GONE
                        binding.tvCacheProgress.visibility = View.GONE
                    }
                    cacheProgressHandler?.postDelayed(cacheProgressRunnable!!, 2000)
                    return@Runnable
                }
                // 使用后台下载进度（非 ExoPlayer 缓冲进度，MP4 不适用）
                val cachePct = svc.getDownloadProgress()

                runOnUiThread {
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

        // 从EpisodesFragment进入时，is_live始终为false，以回放模式处理
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

        segmentAdapter = VoiceSegmentAdapter()
        segmentAdapter?.setSegments(voiceSegments)
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

        if (voiceSegments.isEmpty()) {
            binding.recyclerSegments.visibility = View.GONE
        } else {
            binding.recyclerSegments.visibility = View.VISIBLE
        }

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
            binding.tvAiProgress.text = "字幕生成中..."
            binding.tvAiProgress.visibility = View.VISIBLE
            bindSubtitleService(episode)
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
            subtitleService?.generateSubtitlesForEpisode(
                episode.id, episode.audioUrl,
                object : SubtitleGeneratorService.SubtitleCallback {
                    private val subtitleList = mutableListOf<com.radio.app.models.Transcript>()
                    override fun onSubtitleGenerated(transcript: com.radio.app.models.Transcript) {
                        subtitleList.add(transcript)
                        runOnUiThread {
                            binding.tvAiProgress.text = "字幕: ${transcript.text}"
                            binding.subtitleView.setSubtitles(subtitleList)
                            binding.subtitleView.visibility = View.VISIBLE
                            binding.recyclerSegments.visibility = View.GONE
                        }
                    }
                    override fun onProgressUpdate(progress: Int, total: Int) {
                        runOnUiThread {
                            binding.tvAiProgress.text = "字幕生成: $progress/$total"
                            binding.tvAiProgress.visibility = View.VISIBLE
                        }
                    }
                    override fun onError(error: String) {
                        runOnUiThread {
                            binding.tvAiProgress.text = "字幕生成失败: $error"
                            Toast.makeText(this@PlayerActivity, error, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            subtitleService = null
            subtitleServiceBound = false
        }
    }

    private fun bindSubtitleService(episode: Episode) {
        if (subtitleServiceBound && subtitleService != null) {
            subtitleService?.generateSubtitlesForEpisode(
                episode.id, episode.audioUrl,
                object : SubtitleGeneratorService.SubtitleCallback {
                    private val subtitleList = mutableListOf<com.radio.app.models.Transcript>()
                    override fun onSubtitleGenerated(transcript: com.radio.app.models.Transcript) {
                        subtitleList.add(transcript)
                        runOnUiThread {
                            binding.tvAiProgress.text = "字幕: ${transcript.text}"
                            binding.subtitleView.setSubtitles(subtitleList)
                            binding.subtitleView.visibility = View.VISIBLE
                            binding.recyclerSegments.visibility = View.GONE
                        }
                    }
                    override fun onProgressUpdate(progress: Int, total: Int) {
                        runOnUiThread {
                            binding.tvAiProgress.text = "字幕生成: $progress/$total"
                            binding.tvAiProgress.visibility = View.VISIBLE
                        }
                    }
                    override fun onError(error: String) {
                        runOnUiThread {
                            binding.tvAiProgress.text = "字幕生成失败: $error"
                            Toast.makeText(this@PlayerActivity, error, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
            return
        }
        val intent = Intent(this, SubtitleGeneratorService::class.java)
        bindService(intent, subtitleServiceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun updateUI() {
        playbackService?.let { updatePlayPauseButton(it.isPlaying()) }
    }

    private fun playEpisodeAtIndex(index: Int) {
        if (index < 0 || index >= episodeList.size) return
        currentEpisodeIndex = index
        val episode = episodeList[index]
        currentEpisode = episode
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