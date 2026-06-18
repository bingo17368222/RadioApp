package com.radio.app.services

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class RadioPlaybackService : Service(),
    MediaPlayer.OnPreparedListener,
    MediaPlayer.OnCompletionListener,
    MediaPlayer.OnBufferingUpdateListener,
    MediaPlayer.OnErrorListener,
    AudioManager.OnAudioFocusChangeListener {

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

    private var player: MediaPlayer? = null
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
    private var localCachePath = ""
    private var caching = false
    private var cacheProgress = 0
    private var errorRetryCount = 0
    private var isRetrying = false
    private var stablePlayStartTime = 0L  // 稳定播放开始时间，用于判断是否为新的错误周期
    private var hasStablePlayed = false    // 是否已经稳定播放过（超过5秒）

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var cacheJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setOnPreparedListener(this@RadioPlaybackService)
            setOnCompletionListener(this@RadioPlaybackService)
            setOnBufferingUpdateListener(this@RadioPlaybackService)
            setOnErrorListener(this@RadioPlaybackService)
        }
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        autoSkipHandler = Handler(Looper.getMainLooper())
        progressHandler = Handler(Looper.getMainLooper())
        loadSettings()
        startProgressPolling()
    }

    private fun startProgressPolling() {
        progressRunnable = Runnable {
            player?.let { p ->
                callback?.let { cb ->
                    val pos = p.currentPosition.toLong()
                    val dur = p.duration.toLong()
                    cb.onPositionChanged(pos, dur)
                    if (prepared) {
                        try {
                            var bp = 0
                            if (dur > 0) {
                                bp = ((pos * 100) / dur).toInt()
                                if (bp < 100) bp = minOf(100, bp + 10)
                            }
                            if (bp > 0 && bp != bufferPercent) {
                                bufferPercent = bp
                                cb.onBufferUpdate(bp)
                            }
                        } catch (_: Exception) { /* ignore */ }
                    }
                }
            }
            if (caching) {
                val ci = Intent(BROADCAST_CACHE_UPDATE).apply {
                    putExtra(EXTRA_CACHE_PERCENT, cacheProgress)
                    putExtra(EXTRA_CACHE_PATH, "")
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(ci)
            } else if (localCachePath.isNotEmpty()) {
                val ci = Intent(BROADCAST_CACHE_UPDATE).apply {
                    putExtra(EXTRA_CACHE_PERCENT, 100)
                    putExtra(EXTRA_CACHE_PATH, localCachePath)
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(ci)
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
                player?.takeIf { it.isPlaying }?.setVolume(0.3f, 0.3f)
            }
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                player?.setVolume(1.0f, 1.0f)
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
        hasStablePlayed = false
        stablePlayStartTime = 0L
        stopAutoSkipCheck()
        try {
            player?.reset()
            player?.setDataSource(station.streamUrl)
            player?.prepareAsync()
            requestAudioFocus()
            startForegroundNotification()
        } catch (e: Exception) {
            Log.e(TAG, "playStation failed", e)
            prepared = false
        }
    }

    fun playEpisode(episode: Episode, live: Boolean) {
        currentEpisode = episode
        currentStation = null
        isLive = live
        prepared = false
        currentStreamUrl = episode.audioUrl ?: ""
        localCachePath = ""
        caching = false
        errorRetryCount = 0
        isRetrying = false
        hasStablePlayed = false
        stablePlayStartTime = 0L
        stopAutoSkipCheck()
        try {
            player?.reset()
            player?.setDataSource(episode.audioUrl)
            player?.prepareAsync()
            requestAudioFocus()
            startForegroundNotification()
            startAutoSkipCheck()
            if (!live && episode.audioUrl != null) {
                startCaching(episode.audioUrl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "playEpisode failed", e)
            prepared = false
        }
    }

    private fun startCaching(url: String?) {
        if (url.isNullOrEmpty()) return
        caching = true
        cacheProgress = 0
        cacheJob?.cancel()
        cacheJob = serviceScope.launch {
            var conn: HttpURLConnection? = null
            var fos: FileOutputStream? = null
            try {
                val fileName = "${Math.abs(url.hashCode())}.mp3"
                val cacheDir = getExternalFilesDir("audio")?.apply {
                    if (!exists()) mkdirs()
                } ?: File(filesDir, "audio").apply {
                    if (!exists()) mkdirs()
                }
                val cacheFile = File(cacheDir, fileName)

                if (cacheFile.exists() && cacheFile.length() > 1024) {
                    localCachePath = cacheFile.absolutePath
                    caching = false
                    cacheProgress = 100
                    Log.d(TAG, "Cache already exists: $localCachePath size=${cacheFile.length()}")
                    return@launch
                }

                val downloadUrl = URL(url)
                conn = downloadUrl.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = true

                val rc = conn.responseCode
                if (rc != 200) {
                    Log.w(TAG, "Cache HTTP $rc for $url")
                    caching = false
                    return@launch
                }

                val totalSize = conn.contentLength
                val input = conn.inputStream
                fos = FileOutputStream(cacheFile)
                val buffer = ByteArray(8192)
                var len: Int
                var downloaded = 0
                while (input.read(buffer).also { len = it } != -1) {
                    fos.write(buffer, 0, len)
                    downloaded += len
                    if (totalSize > 0) {
                        cacheProgress = (downloaded * 100 / totalSize)
                    }
                }
                fos.flush()

                if (cacheFile.length() < 1024) {
                    Log.w(TAG, "Cache file too small: ${cacheFile.length()} bytes")
                    cacheFile.delete()
                    caching = false
                    cacheProgress = 0
                    return@launch
                }

                localCachePath = cacheFile.absolutePath
                caching = false
                cacheProgress = 100
                Log.d(TAG, "Cache complete: $localCachePath size=${cacheFile.length()}")
            } catch (e: Exception) {
                Log.e(TAG, "Caching failed: ${e.message}")
                caching = false
                cacheProgress = 0
            } finally {
                try { fos?.close() } catch (_: Exception) { /* ignored */ }
                try { conn?.inputStream?.close() } catch (_: Exception) { /* ignored */ }
                conn?.disconnect()
            }
        }
    }

    fun getLocalCachePath(): String = localCachePath
    fun isCaching(): Boolean = caching
    fun getCacheProgress(): Int = cacheProgress

    fun play() {
        player?.takeIf { prepared && !it.isPlaying }?.let {
            it.start()
            callback?.onStateChanged(true)
            sendStateBroadcast(true)
            startForegroundNotification()
        }
    }

    fun pause() {
        player?.takeIf { prepared && it.isPlaying }?.let {
            it.pause()
            callback?.onStateChanged(false)
            sendStateBroadcast(false)
            startForegroundNotification()
        }
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
        if (!isLive) {
            player?.seekTo(pos.toInt())
        }
    }

    fun skipForward() {
        if (isLive) return
        player?.let { p ->
            var pPos = p.currentPosition + skipSeconds * 1000
            val dur = p.duration
            if (dur > 0 && pPos > dur) pPos = dur
            p.seekTo(pPos)
        }
    }

    fun skipBackward() {
        if (isLive) return
        player?.seekTo(maxOf(0, (player?.currentPosition ?: 0) - skipSeconds * 1000))
    }

    fun isPlaying(): Boolean = player?.isPlaying ?: false
    fun isLive(): Boolean = isLive
    fun isPrepared(): Boolean = prepared
    fun getCurrentStreamUrl(): String = currentStreamUrl
    fun getCurrentPosition(): Long = player?.currentPosition?.toLong() ?: 0L
    fun getDuration(): Long {
        val dur = player?.duration?.toLong() ?: 0L
        return if (dur <= 0 && isLive) -1 else maxOf(0, dur)
    }
    fun getCurrentEpisode(): Episode? = currentEpisode
    fun getCurrentStation(): RadioStation? = currentStation
    fun getBufferPercent(): Int = bufferPercent
    fun setCallback(cb: Callback?) { callback = cb }

    fun jumpToNextSegment() {
        val segments = currentEpisode?.voiceSegments ?: return
        val currentPos = player?.currentPosition?.toLong() ?: 0L
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
        val currentPos = player?.currentPosition?.toLong() ?: 0L
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
                        val currentPos = p.currentPosition.toLong()
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

    override fun onCompletion(mp: MediaPlayer?) {
        Log.d(TAG, "onCompletion, isLive=$isLive")
        callback?.onStateChanged(false)
        sendStateBroadcast(false)
        stopAutoSkipCheck()
    }

    override fun onBufferingUpdate(mp: MediaPlayer?, percent: Int) {
        bufferPercent = percent
        callback?.onBufferUpdate(percent)
        val intent = Intent(BROADCAST_BUFFER_UPDATE).apply {
            putExtra(EXTRA_BUFFER_PERCENT, percent)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
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

        val rewindIntent = Intent(this, RadioPlaybackService::class.java).apply {
            action = ACTION_REWIND
        }
        val rewindPI = PendingIntent.getService(
            this, 1, rewindIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prevIntent = Intent(this, RadioPlaybackService::class.java).apply {
            action = ACTION_PREV_SEGMENT
        }
        val prevPI = PendingIntent.getService(
            this, 2, prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIntent = Intent(this, RadioPlaybackService::class.java).apply {
            action = if (playing) ACTION_PAUSE else ACTION_PLAY
        }
        val playPausePI = PendingIntent.getService(
            this, 3, playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = Intent(this, RadioPlaybackService::class.java).apply {
            action = ACTION_NEXT_SEGMENT
        }
        val nextPI = PendingIntent.getService(
            this, 4, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val forwardIntent = Intent(this, RadioPlaybackService::class.java).apply {
            action = ACTION_FORWARD
        }
        val forwardPI = PendingIntent.getService(
            this, 5, forwardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mediaStyle = MediaStyle().setShowActionsInCompactView(1, 2, 3)

        val notification: Notification = NotificationCompat.Builder(this, RadioApplication.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (playing) "正在播放" else "已暂停")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_rewind, "快退", rewindPI)
            .addAction(R.drawable.ic_prev, "上一片段", prevPI)
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "暂停" else "播放",
                playPausePI
            )
            .addAction(R.drawable.ic_next, "下一片段", nextPI)
            .addAction(R.drawable.ic_forward, "快进", forwardPI)
            .setStyle(mediaStyle)
            .build()
        startForeground(1, notification)
    }

    private fun sendStateBroadcast(playing: Boolean) {
        val intent = Intent(BROADCAST_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_PLAYING, playing)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onPrepared(mp: MediaPlayer?) {
        prepared = true
        isRetrying = false
        // 记录稳定播放开始时间，但不重置 errorRetryCount
        // 只有在稳定播放超过5秒后，才认为进入新的播放周期
        stablePlayStartTime = System.currentTimeMillis()
        hasStablePlayed = false
        mp?.start()
        callback?.onStateChanged(true)
        sendStateBroadcast(true)
        startForegroundNotification()
        // 延迟检查是否稳定播放
        Handler(Looper.getMainLooper()).postDelayed({
            if (prepared && player?.isPlaying == true) {
                val elapsed = System.currentTimeMillis() - stablePlayStartTime
                if (elapsed >= 5000) {
                    hasStablePlayed = true
                    errorRetryCount = 0  // 稳定播放5秒后，重置错误计数
                    Log.d(TAG, "Stable play detected, reset errorRetryCount")
                }
            }
        }, 5000)
    }

    override fun onError(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        Log.e(TAG, "MediaPlayer onError: what=$what extra=$extra retry=$errorRetryCount hasStable=$hasStablePlayed")
        prepared = false
        errorRetryCount++
        // 如果之前已经稳定播放过，重置计数器（新的错误周期）
        if (hasStablePlayed) {
            errorRetryCount = 1
            hasStablePlayed = false
            Log.d(TAG, "New error cycle after stable play, reset retry count to 1")
        }
        if (errorRetryCount <= MAX_ERROR_RETRY && currentStreamUrl.isNotEmpty()) {
            // 重试期间静默处理，不通知 UI
            isRetrying = true
            val retryDelay = errorRetryCount * 3000L
            Handler(Looper.getMainLooper()).postDelayed({
                if (isRetrying && currentStreamUrl.isNotEmpty()) {
                    try {
                        player?.reset()
                        player?.setDataSource(currentStreamUrl)
                        player?.prepareAsync()
                    } catch (e: Exception) {
                        Log.e(TAG, "Retry failed", e)
                        isRetrying = false
                        callback?.let { cb ->
                            cb.onStateChanged(false)
                            cb.onError("播放失败: 重试异常 (${e.message})")
                        }
                        sendStateBroadcast(false)
                    }
                }
            }, retryDelay)
        } else {
            // 最终失败，通知 UI
            isRetrying = false
            callback?.let { cb ->
                cb.onStateChanged(false)
                cb.onError("播放失败: 无效的播放内容 (错误 $what)，已重试 $MAX_ERROR_RETRY 次")
            }
            sendStateBroadcast(false)
        }
        return true
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        progressRunnable?.let { progressHandler?.removeCallbacks(it) }
        stopAutoSkipCheck()
        abandonAudioFocus()
        cacheJob?.cancel()
        serviceScope.cancel()
        player?.let {
            it.release()
            player = null
        }
    }
}
