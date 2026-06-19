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


    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? RadioPlaybackService.LocalBinder ?: return
            playbackService = binder.getService()
            serviceBound = true
            playbackService?.setCallback(playbackCallback)
            // 服务绑定成功后，开始播放
            if (currentStation != null) {
                // 直播电台：使用 playStation
                playbackService?.playStation(currentStation!!)
            } else {
                val audioUrl = currentEpisode?.audioUrl
                if (!audioUrl.isNullOrBlank()) {
                    val isLive = currentEpisode?.isLive ?: false
                    currentEpisode?.let { episode ->
                        playbackService?.playEpisode(episode, isLive)
                    }
                }
            }
            updateUI()
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
            }
        }

        override fun onPositionChanged(position: Long, duration: Long) {
            runOnUiThread {
                val pos = position.toInt()
                val dur = duration.toInt()
                if (dur > 0) {
                    binding.seekBar.max = dur
                    binding.seekBar.progress = pos
                    binding.tvCurrentTime.text = "${formatTime(pos)} / ${formatTime(dur)}"
                } else {
                    // Bug 2: 直播流 duration <= 0，显示已播放时间
                    binding.tvCurrentTime.text = "直播 ${formatTime(pos)}"
                }
            }
        }

        override fun onBufferUpdate(percent: Int) {
            runOnUiThread {
                if (hasError) return@runOnUiThread
                binding.tvAiProgress.text = "缓冲: ${percent}%"
                binding.tvAiProgress.visibility = if (percent >= 100) View.GONE else View.VISIBLE
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentEpisode = intent.getSerializableExtra("episode") as? Episode
        if (currentEpisode == null) {
            // 尝试从单独的字段中构建 Episode 对象（兼容旧调用方式）
            val audioUrl = intent.getStringExtra("audio_url") ?: intent.getStringExtra("stream_url")
            if (audioUrl.isNullOrBlank()) {
                Toast.makeText(this, "播放地址无效，无法播放", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            val isLive = intent.getBooleanExtra("is_live", false)
            if (isLive) {
                // 直播电台：构建 RadioStation 对象
                currentStation = RadioStation().apply {
                    id = intent.getStringExtra("station_id") ?: ""
                    name = intent.getStringExtra("station_name") ?: ""
                    streamUrl = audioUrl
                    this.isLive = true
                }
                // 同时构建一个 Episode 用于 UI 显示
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

        // 读取 voice_segments extra
        @Suppress("UNCHECKED_CAST")
        voiceSegments = (intent.getSerializableExtra("voice_segments") as? ArrayList<VoiceSegment>) ?: emptyList()

        // 读取 episode 列表和 index
        @Suppress("UNCHECKED_CAST")
        episodeList = (intent.getSerializableExtra("episode_list") as? ArrayList<Episode>) ?: ArrayList()
        currentEpisodeIndex = intent.getIntExtra("episode_index", -1)

        // 最终校验：确保 audioUrl 有效
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
        binding.tvCacheUrl.visibility = View.GONE
        binding.tvLiveIndicator.text = "准备播放..."
        binding.tvLiveIndicator.visibility = View.VISIBLE
        binding.tvCurrentTime.text = "00:00 / 00:00"
        binding.tvAiProgress.text = "缓冲: 0%"
        binding.tvAiProgress.visibility = View.VISIBLE

        // Bug 5: 设置片段列表 adapter
        segmentAdapter = VoiceSegmentAdapter()
        segmentAdapter?.setSegments(voiceSegments)
        segmentAdapter?.setOnSegmentClickListener(object : VoiceSegmentAdapter.OnSegmentClickListener {
            override fun onSegmentClick(position: Int, segment: VoiceSegment) {
                playbackService?.seekTo(segment.start)
            }
            override fun onSegmentLongClick(position: Int, segment: VoiceSegment) {
                // 长按标记为干货/水分
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

        // 节目导航：非直播且有列表时显示
        val isLive = currentEpisode?.isLive ?: false
        if (!isLive && episodeList.size > 1 && currentEpisodeIndex >= 0) {
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

        // ExoPlayer 自带缓存，不需要手动轮询缓存路径
        binding.tvCacheUrl.visibility = View.GONE
    }

    private fun setupListeners() {
        binding.btnPlayPause.setOnClickListener {
            playbackService?.let { service ->
                if (service.isPlaying()) {
                    service.pause()
                } else {
                    service.play()
                }
            }
        }

        // Bug 3: btnPrevSegment 应该跳转到上一片段，而不是停止播放
        binding.btnPrevSegment.setOnClickListener {
            playbackService?.jumpToPrevSegment()
        }

        // Bug 3: btnNextSegment 绑定跳转到下一片段
        binding.btnNextSegment.setOnClickListener {
            playbackService?.jumpToNextSegment()
        }

        // Bug 3: btnSkipForward 使用 Service 的 skipForward()（15秒）
        binding.btnSkipForward.setOnClickListener {
            playbackService?.skipForward()
        }

        // Bug 3: btnSkipBackward 使用 Service 的 skipBackward()（15秒）
        binding.btnSkipBackward.setOnClickListener {
            playbackService?.skipBackward()
        }

        // Bug 3: btnClose 关闭 Activity
        binding.btnClose.setOnClickListener {
            finish()
        }

        // Bug 6: btnGenerateSubtitle 生成字幕
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

        // btnSubtitleToggle: 切换字幕/片段列表显示
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
                    playbackService?.seekTo(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
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
                episode.id,
                episode.audioUrl,
                object : SubtitleGeneratorService.SubtitleCallback {
                    private val subtitleList = mutableListOf<com.radio.app.models.Transcript>()
                    override fun onSubtitleGenerated(transcript: com.radio.app.models.Transcript) {
                        subtitleList.add(transcript)
                        runOnUiThread {
                            binding.tvAiProgress.text = "字幕: ${transcript.text}"
                            // 同时更新 SubtitleView 显示
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
            // 已经绑定，直接生成
            subtitleService?.generateSubtitlesForEpisode(
                episode.id,
                episode.audioUrl,
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
        playbackService?.let {
            updatePlayPauseButton(it.isPlaying())
        }
    }

    private fun playEpisodeAtIndex(index: Int) {
        if (index < 0 || index >= episodeList.size) return
        currentEpisodeIndex = index
        val episode = episodeList[index]
        currentEpisode = episode
        binding.tvStationName.text = episode.title
        binding.tvNetworkUrl.text = "网络: ${episode.audioUrl}"
        binding.tvEpisodeNavHint.text = " ${index + 1}/${episodeList.size} "
        binding.tvLiveIndicator.text = "准备播放..."
        binding.tvCurrentTime.text = "00:00 / 00:00"
        binding.seekBar.progress = 0
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
