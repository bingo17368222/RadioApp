package com.radio.app.services

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.app.NotificationCompat.MediaStyle
import com.radio.app.R
import com.radio.app.RadioApplication
import com.radio.app.activities.PlayerActivity
import com.radio.app.database.RadioDatabaseHelper
import com.radio.app.models.AppSettings
import com.radio.app.models.Episode
import com.radio.app.models.RadioStation
import com.radio.app.models.VoiceSegment
import com.radio.app.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource

class RadioPlaybackService : Service(), AudioManager.OnAudioFocusChangeListener {

    companion object {
        private const val TAG = "RadioPlaybackService"
        private const val MAX_ERROR_RETRY = 3

        const val ACTION_PLAY = "com.radio.app.PLAY"
        const val ACTION_PAUSE = "com.radio.app.PAUSE"
        const val ACTION_STOP = "com.radio.app.STOP"
        const val ACTION_PREV_SEGMENT = "com.radio.app.PREV_SEGMENT"
        const val ACTION_NEXT_SEGMENT = "com.radio.app.NEXT_SEGMENT"
        const val ACTION_REWIND = "com.radio.app.REWIND"
        const val ACTION_FORWARD = "com.radio.app.FORWARD"
        const val ACTION_PREV_EPISODE = "com.radio.app.PREV_EPISODE"
        const val ACTION_NEXT_EPISODE = "com.radio.app.NEXT_EPISODE"

        const val BROADCAST_BUFFER_UPDATE = "com.radio.app.BUFFER_UPDATE"
        const val BROADCAST_STATE_CHANGED = "com.radio.app.STATE_CHANGED"
        const val BROADCAST_CACHE_UPDATE = "com.radio.app.CACHE_UPDATE"
        const val EXTRA_BUFFER_PERCENT = "buffer_percent"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_CACHE_PERCENT = "cache_percent"
        const val EXTRA_CACHE_PATH = "cache_path"
    }

    interface Callback {
        fun onStateChanged(playing: Boolean)
        fun onPositionChanged(position: Long, duration: Long)
        fun onBufferUpdate(percent: Int)
        fun onError(errorMessage: String)
    }

    inner class LocalBinder : Binder() {
        fun getService(): RadioPlaybackService = this@RadioPlaybackService
    }

    private var player: ExoPlayer? = null
    private val binder = LocalBinder()
    private var currentEpisode: Episode? = null
    private var currentStation: RadioStation? = null
    private var isLive = false
    private var callback: Callback? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var autoSkipHandler: Handler? = null
    private var autoSkipRunnable: Runnable? = null
    private var continuousPlay = true
    private var bufferPercent = 0
    private var prepared = false
    private var currentStreamUrl = ""
    private var progressHandler: Handler? = null
    private var progressRunnable: Runnable? = null
    private var skipSeconds = 15
    private var errorRetryCount = 0
    private var isRetrying = false
    private var stablePlayStartTime = 0L
    private var hasStablePlayed = false

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onCreate() {
        super.onCreate()
        // 延迟初始化ExoPlayer，避免启动时闪退
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        autoSkipHandler = Handler(Looper.getMainLooper())
        progressHandler = Handler(Looper.getMainLooper())
        loadSettings()
        startProgressPolling()
    }

    private fun ensurePlayerInitialized() {
        if (player != null) return
        try {
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36",
                    "Referer" to "https://www.qingting.fm/"
                ))
            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
                .build()
                .apply {
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            when (state) {
                                Player.STATE_READY -> {
                                    prepared = true
                                    isRetrying = false
                                    callback?.onStateChanged(true)
                                    sendStateBroadcast(true)
                                    startForegroundNotification()
                                }
                                Player.STATE_ENDED -> {
                                    callback?.onStateChanged(false)
                                    sendStateBroadcast(false)
                                    stopAutoSkipCheck()
                                }
                            }
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            Log.e(TAG, "ExoPlayer error: ${error.message}")
                            prepared = false
                            errorRetryCount++
                            if (errorRetryCount <= MAX_ERROR_RETRY && currentStreamUrl.isNotEmpty()) {
                                val retryDelay = errorRetryCount * 3000L
                                Handler(Looper.getMainLooper()).postDelayed({
                                    try {
                                        player?.let {
                                            it.setMediaItem(MediaItem.fromUri(currentStreamUrl))
                                            it.prepare()
                                            it.play()
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Retry failed", e)
                                    }
                                }, retryDelay)
                            }
                        }
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            callback?.onStateChanged(isPlaying)
                            sendStateBroadcast(isPlaying)
                            startForegroundNotification()
                        }
                    })
                }
        } catch (e: Exception) {
            Log.e(TAG, "ExoPlayer init failed", e)
        }
    }

    private fun startProgressPolling() {
        progressRunnable = Runnable {
            player?.let { p ->
                callback?.let { cb ->
                    try {
                        val pos = p.currentPosition
                        val dur = p.duration
                        // 直播流duration为TIME_UNSET(-9223372036854775807)，不计算进度
                        val effectiveDur = if (dur > 0) dur else if (isLive) -1L else 0L
                        cb.onPositionChanged(pos, effectiveDur)
                        // 只给回放节目计算缓冲进度
                        if (prepared && !isLive && dur > 0) {
                            val bp = ((pos * 100) / dur).toInt().coerceIn(0, 100)
                            if (bp != bufferPercent) {
                                bufferPercent = bp
                                cb.onBufferUpdate(bp)
                            }
                        }
                    } catch (_: Exception) { /* ignore */ }
                }
            }
            progressHandler?.postDelayed(progressRunnable!!, 500)
        }
        progressRunnable?.let { progressHandler?.post(it) }
    }

    private fun loadSettings() {
        val prefMgr = PreferenceManager(this)
        val settings: AppSettings = prefMgr.loadSettings()
        continuousPlay = settings.continuousPlay
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_PLAY -> play()
                ACTION_PAUSE -> pause()
                ACTION_STOP -> stop()
                ACTION_PREV_SEGMENT -> jumpToPrevSegment()
                ACTION_NEXT_SEGMENT -> jumpToNextSegment()
                ACTION_REWIND -> skipBackward()
                ACTION_FORWARD -> skipForward()
                ACTION_PREV_EPISODE -> notifyPrevEpisode()
                ACTION_NEXT_EPISODE -> notifyNextEpisode()
            }
        }
        return START_STICKY
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setWillPauseWhenDucked(true)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(this)
                .build()
            audioFocusRequest = request
            am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            am.abandonAudioFocusRequest(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(this)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> play()
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                player?.takeIf { it.isPlaying }?.volume = 0.3f
            }
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                player?.volume = 1.0f
                play()
            }
        }
    }

    fun playStation(station: RadioStation) {
        currentStation = station
        currentEpisode = null
        isLive = true
        prepared = false
        currentStreamUrl = station.streamUrl ?: ""
        errorRetryCount = 0
        isRetrying = false
        stopAutoSkipCheck()
        ensurePlayerInitialized()
        try {
            player?.let {
                it.setMediaItem(MediaItem.fromUri(currentStreamUrl))
                it.prepare()
                it.playWhenReady = true
            }
            requestAudioFocus()
            startForegroundNotification()
        } catch (e: Exception) {
            Log.e(TAG, "playStation failed", e)
        }
    }

    fun playEpisode(episode: Episode, live: Boolean) {
        currentEpisode = episode
        currentStation = null
        isLive = live
        prepared = false
        errorRetryCount = 0
        isRetrying = false
        stopAutoSkipCheck()
        val audioUrl = episode.audioUrl ?: ""
        ensurePlayerInitialized()
        try {
            player?.let {
                it.setMediaItem(MediaItem.fromUri(audioUrl))
                it.prepare()
                it.playWhenReady = true
            }
            requestAudioFocus()
            startForegroundNotification()
            startAutoSkipCheck()
        } catch (e: Exception) {
            Log.e(TAG, "playEpisode failed", e)
        }
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun stop() {
        stopAutoSkipCheck()
        player?.let {
            it.stop()
            abandonAudioFocus()
            stopForeground(true)
            stopSelf()
        }
    }

    fun seekTo(pos: Long) {
        if (!isLive && prepared) {
            player?.seekTo(pos)
        }
    }

    fun skipForward() {
        if (isLive || !prepared) return
        val pPos = (player?.currentPosition ?: 0) + skipSeconds * 1000
        val dur = player?.duration ?: 0
        val finalPos = if (dur > 0 && pPos > dur) dur else pPos
        player?.seekTo(finalPos)
    }

    fun skipBackward() {
        if (isLive || !prepared) return
        player?.seekTo(maxOf(0, (player?.currentPosition ?: 0) - skipSeconds * 1000))
    }

    fun isPlaying(): Boolean = player?.isPlaying ?: false
    fun isLive(): Boolean = isLive
    fun isPrepared(): Boolean = prepared
    fun getCurrentStreamUrl(): String = currentStreamUrl
    fun getCurrentPosition(): Long = player?.currentPosition ?: 0L
    fun getDuration(): Long {
        val dur = player?.duration ?: 0L
        // 直播流返回TIME_UNSET，返回-1表示未知
        return if (dur < 0) -1L else dur
    }
    fun getCurrentEpisode(): Episode? = currentEpisode
    fun getCurrentStation(): RadioStation? = currentStation
    fun getBufferPercent(): Int = player?.bufferedPercentage ?: 0
    fun setCallback(cb: Callback?) { callback = cb }

    fun jumpToNextSegment() {
        val segments = currentEpisode?.voiceSegments ?: return
        val currentPos = player?.currentPosition ?: 0L
        for (seg in segments) {
            if (seg.start > currentPos) {
                seekTo(seg.start)
                return
            }
        }
    }

    private fun notifyPrevEpisode() {
        val intent = Intent(BROADCAST_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_PLAYING, false)
            putExtra("action", "prev_episode")
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun notifyNextEpisode() {
        val intent = Intent(BROADCAST_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_PLAYING, false)
            putExtra("action", "next_episode")
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    fun jumpToPrevSegment() {
        val segments = currentEpisode?.voiceSegments ?: return
        val currentPos = player?.currentPosition ?: 0L
        var prev: VoiceSegment? = null
        for (i in segments.indices) {
            if (segments[i].end >= currentPos) {
                if (i > 0) prev = segments[i - 1]
                break
            }
        }
        prev?.let { seekTo(it.start) } ?: segments.firstOrNull()?.let { seekTo(it.start) }
    }

    fun markSegment(index: Int, isDry: Boolean) {
        val segments = currentEpisode?.voiceSegments ?: return
        if (index < 0 || index >= segments.size) return
        val seg = segments[index]
        seg.isManuallyMarked = true
        seg.hasVoice = isDry
        seg.label = if (isDry) "手动标记:干货" else "手动标记:水分"
        currentEpisode?.id?.let { episodeId ->
            RadioDatabaseHelper.getInstance(this).saveManualSegmentMark(
                episodeId, seg.start, seg.end, isDry
            )
        }
    }

    fun setSkipThisTime(index: Int, skip: Boolean) {
        val segments = currentEpisode?.voiceSegments ?: return
        if (index < 0 || index >= segments.size) return
        segments[index].isSkipThisTime = skip
    }

    private fun startAutoSkipCheck() {
        stopAutoSkipCheck()
        autoSkipRunnable = Runnable {
            player?.let { p ->
                if (p.isPlaying && !isLive) {
                    val segments = currentEpisode?.voiceSegments
                    if (segments != null) {
                        val currentPos = p.currentPosition
                        for (seg in segments) {
                            if (currentPos >= seg.start && currentPos < seg.end) {
                                if (seg.shouldAutoSkip()) jumpToNextDrySegment(seg)
                                break
                            }
                        }
                    }
                }
            }
            autoSkipHandler?.postDelayed(autoSkipRunnable!!, 1000)
        }
        autoSkipRunnable?.let { autoSkipHandler?.postDelayed(it, 1000) }
    }

    private fun jumpToNextDrySegment(currentSeg: VoiceSegment) {
        val segments = currentEpisode?.voiceSegments ?: return
        for (seg in segments) {
            if (seg.start > currentSeg.end && seg.isEffectiveDry()) {
                seekTo(seg.start)
                return
            }
        }
    }

    private fun stopAutoSkipCheck() {
        autoSkipRunnable?.let { autoSkipHandler?.removeCallbacks(it) }
        autoSkipRunnable = null
    }

    private fun startForegroundNotification() {
        val openIntent = Intent(this, PlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = currentEpisode?.title
            ?: currentStation?.name
            ?: "Radio App"
        val playing = player?.isPlaying ?: false

        // 快退15秒
        val rewindIntent = Intent(this, RadioPlaybackService::class.java).apply {
            action = ACTION_REWIND
        }
        val rewindPI = PendingIntent.getService(
            this, 1, rewindIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 上一片段
        val prevSegIntent = Intent(this, RadioPlaybackService::class.java).apply {
            action = ACTION_PREV_SEGMENT
        }
        val prevSegPI = PendingIntent.getService(
            this, 2, prevSegIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 上一节目
        val prevEpIntent = Intent(this, RadioPlaybackService::class.java).apply {
            action = ACTION_PREV_EPISODE
        }
        val prevEpPI = PendingIntent.getService(
            this, 3, prevEpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 播放/暂停
        val playPauseIntent = Intent(this, RadioPlaybackService::class.java).apply {
            action = if (playing) ACTION_PAUSE else ACTION_PLAY
        }
        val playPausePI = PendingIntent.getService(
            this, 4, playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 下一节目
        val nextEpIntent = Intent(this, RadioPlaybackService::class.java).apply {
            action = ACTION_NEXT_EPISODE
        }
        val nextEpPI = PendingIntent.getService(
            this, 5, nextEpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 下一片段
        val nextSegIntent = Intent(this, RadioPlaybackService::class.java).apply {
            action = ACTION_NEXT_SEGMENT
        }
        val nextSegPI = PendingIntent.getService(
            this, 6, nextSegIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 快进15秒
        val forwardIntent = Intent(this, RadioPlaybackService::class.java).apply {
            action = ACTION_FORWARD
        }
        val forwardPI = PendingIntent.getService(
            this, 7, forwardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val subText = if (isLive) "[直播]" else "[回放]"

        val notification: Notification = NotificationCompat.Builder(this, RadioApplication.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (playing) "正在播放 $subText" else "已暂停 $subText")
            .setSubText(subText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // compact view 最多显示3个按钮: 上节目(2), 播放/暂停(3), 下节目(4)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(2, 3, 4))
            .addAction(R.drawable.ic_rewind, "-15s", rewindPI)      // 0: 快退
            .addAction(R.drawable.ic_skip_backward, "上片段", prevSegPI)  // 1: 上片段
            .addAction(R.drawable.ic_prev, "上节目", prevEpPI)       // 2: 上节目
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "暂停" else "播放",
                playPausePI
            )                                                      // 3: 播放/暂停
            .addAction(R.drawable.ic_next, "下节目", nextEpPI)       // 4: 下节目
            .addAction(R.drawable.ic_skip_forward, "下片段", nextSegPI)   // 5: 下片段
            .addAction(R.drawable.ic_forward, "+15s", forwardPI)    // 6: 快进
            .build()
        startForeground(1, notification)
    }

    private fun sendStateBroadcast(playing: Boolean) {
        val intent = Intent(BROADCAST_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_PLAYING, playing)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        progressRunnable?.let { progressHandler?.removeCallbacks(it) }
        stopAutoSkipCheck()
        abandonAudioFocus()
        serviceScope.cancel()
        player?.let {
            it.release()
            player = null
        }
    }
}
