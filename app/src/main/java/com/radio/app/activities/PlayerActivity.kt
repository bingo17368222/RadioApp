package com.radio.app.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.radio.app.databinding.ActivityPlayerBinding
import com.radio.app.models.Episode
import com.radio.app.services.RadioPlaybackService

class PlayerActivity : AppCompatActivity() {

    private var _binding: ActivityPlayerBinding? = null
    private val binding: ActivityPlayerBinding
        get() = _binding ?: throw IllegalStateException("Binding is only valid between onCreate and onDestroy")

    private var playbackService: RadioPlaybackService? = null
    private var serviceBound = false
    private var currentEpisode: Episode? = null
    private var hasError = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? RadioPlaybackService.LocalBinder ?: return
            playbackService = binder.getService()
            serviceBound = true
            playbackService?.setCallback(playbackCallback)
            // 服务绑定成功后，开始播放当前 episode
            val audioUrl = currentEpisode?.audioUrl
            if (!audioUrl.isNullOrBlank()) {
                val isLive = currentEpisode?.isLive ?: false
                currentEpisode?.let { episode ->
                    playbackService?.playEpisode(episode, isLive)
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
                    binding.tvCurrentTime.text = formatTime(pos)
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
                Toast.makeText(this@PlayerActivity, errorMessage, Toast.LENGTH_LONG).show()
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
            val audioUrl = intent.getStringExtra("audio_url")
            if (audioUrl.isNullOrBlank()) {
                Toast.makeText(this, "播放地址无效，无法播放", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            currentEpisode = Episode().apply {
                id = intent.getStringExtra("episode_id") ?: ""
                title = intent.getStringExtra("title") ?: ""
                this.audioUrl = audioUrl
                stationName = intent.getStringExtra("station_name") ?: ""
                duration = intent.getLongExtra("duration", 0)
                isLive = intent.getBooleanExtra("is_live", false)
            }
        }

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

        binding.btnPrevSegment.setOnClickListener {
            playbackService?.let { service ->
                service.stop()
                updatePlayPauseButton(false)
                binding.tvLiveIndicator.text = "已停止"
                binding.tvCurrentTime.text = "00:00 / 00:00"
                binding.seekBar.progress = 0
            }
        }

        binding.btnSkipForward.setOnClickListener {
            playbackService?.seekTo(playbackService?.getCurrentPosition().plus(30000))
        }

        binding.btnSkipBackward.setOnClickListener {
            playbackService?.seekTo(playbackService?.getCurrentPosition().minus(30000).coerceAtLeast(0))
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

    private fun updateUI() {
        playbackService?.let {
            updatePlayPauseButton(it.isPlaying())
        }
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
        _binding = null
    }
}
