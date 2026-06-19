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
import android.net.Uri
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
                    try {
                        val pos = p.currentPosition.toLong()
                        val dur = p.duration.toLong()
                        // duration可能为负值(流式)或0(缓冲中)，使用getDuration()方法处理
                        val effectiveDur = if (dur > 0) dur else if (isLive) -1L else 0L
                        cb.onPositionChanged(pos, effectiveDur)
                        if (prepared) {
                            var bp = 0
                            if (dur > 0) {
                                bp = ((pos * 100) / dur).toInt()
                                if (bp < 100) bp = minOf(100, bp + 10)
                            }
                            if (bp > 0 && bp != bufferPercent) {
                                bufferPercent = bp
                                cb.onBufferUpdate(bp)
                            }
                        }
                    } catch (_: Exception) { /* ignore */ }
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
            setDataSourceWithHeaders(currentStreamUrl)  // 内部调用 prepareAsync()
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
        errorRetryCount = 0
        isRetrying = false
        hasStablePlayed = false
        stablePlayStartTime = 0L
        stopAutoSkipCheck()

        // 节目回放：优先使用本地缓存文件
        val audioUrl = episode.audioUrl ?: ""
        val cacheDir = getExternalFilesDir("audio") ?: File(filesDir, "audio")
        val cachedFile = File(cacheDir, "${Math.abs(audioUrl.hashCode())}.mp3")

        if (!live && cachedFile.exists() && cachedFile.length() > 1024) {
            // 使用本地缓存文件播放
            currentStreamUrl = audioUrl  // 保留原始URL用于显示
            localCachePath = cachedFile.absolutePath
            caching = false
            cacheProgress = 100
            Log.d(TAG, "Playing from cache: $localCachePath size=${cachedFile.length()}")
            try {
                player?.reset()
                player?.setDataSource(cachedFile.absolutePath)
                player?.prepareAsync()
                requestAudioFocus()
                startForegroundNotification()
                startAutoSkipCheck()
            } catch (e: Exception) {
                Log.e(TAG, "playEpisode from cache failed", e)
                // 缓存文件损坏，删除并回退到网络播放
                cachedFile.delete()
                localCachePath = ""
                playFromNetwork(audioUrl, live)
            }
        } else {
            // 无缓存，从网络播放
            currentStreamUrl = audioUrl
            localCachePath = ""
            caching = false
            playFromNetwork(audioUrl, live)
        }
    }

    private fun playFromNetwork(audioUrl: String, live: Boolean) {
        try {
            player?.reset()
            setDataSourceWithHeaders(audioUrl)  // 内部调用 prepareAsync()
            requestAudioFocus()
            startForegroundNotification()
            startAutoSkipCheck()
            if (!live && audioUrl.isNotEmpty()) {
                startCaching(audioUrl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "playFromNetwork failed", e)
            prepared = false
        }
    }

    /**
     * 设置数据源。MediaPlayer原生支持M3U8（Android 3.0+），
     * 直接设置URL即可，不需要手动解析重定向。
     */
    private fun setDataSourceWithHeaders(url: String) {
        try {
            player?.setDataSource(url)
            player?.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "setDataSource failed for $url", e)
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

                // 手动处理重定向 + 设置User-Agent + Referer + Cookie
                var downloadUrlStr = url
                var redirectCount = 0
                var cookieValue = ""
                while (redirectCount < 5) {
                    val downloadUrl = URL(downloadUrlStr)
                    conn = downloadUrl.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 60000
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    conn.setRequestProperty("Accept", "*/*")
                    conn.setRequestProperty("Referer", "https://www.qingting.fm/")
                    if (cookieValue.isNotEmpty()) {
                        conn.setRequestProperty("Cookie", cookieValue)
                    }
                    conn.setRequestProperty("Connection", "keep-alive")
                    conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")

                    val rc = conn.responseCode
                    Log.d(TAG, "Cache HTTP $rc for $url (redirect=$redirectCount)")
                    if (rc in 300..399) {
                        val location = conn.getHeaderField("Location")
                        val setCookie = conn.getHeaderField("Set-Cookie")
                        if (!setCookie.isNullOrBlank()) {
                            cookieValue = setCookie.split(";")[0].trim()
                        }
                        conn.disconnect()
                        conn = null
                        if (location.isNullOrBlank()) {
                            Log.w(TAG, "Cache redirect without Location")
                            caching = false
                            return@launch
                        }
                        downloadUrlStr = if (location.startsWith("http")) location
                        else URL(URL(downloadUrlStr), location).toString()
                        redirectCount++
                        continue
                    }
                    if (rc != 200) {
                        Log.w(TAG, "Cache HTTP $rc for $url")
                        caching = false
                        return@launch
                    }
                    break
                }

                val totalSize = conn?.contentLength ?: -1
                val contentType = conn?.contentType ?: ""
                Log.d(TAG, "Cache download totalSize=$totalSize contentType=$contentType for $url")

                // 检查Content-Type，拒绝HTML错误页面
                if (contentType.contains("text/html", ignoreCase = true) ||
                    contentType.contains("application/json", ignoreCase = true)) {
                    Log.w(TAG, "Cache received non-audio content: $contentType")
                    caching = false
                    cacheProgress = 0
                    return@launch
                }

                val input = conn?.inputStream ?: throw Exception("连接失败")
                fos = FileOutputStream(cacheFile)
                val buffer = ByteArray(8192)
                var len: Int
                var downloaded = 0
                while (input.read(buffer).also { len = it } != -1) {
                    fos.write(buffer, 0, len)
                    downloaded += len
                    if (totalSize > 0) {
                        cacheProgress = (downloaded * 100 / totalSize).toInt()
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

                // 再次检查文件头，确保不是HTML错误页面
                val header = ByteArray(20)
                cacheFile.inputStream().use { it.read(header) }
                val headerStr = String(header, Charsets.UTF_8)
                if (headerStr.contains("<!DOCTYPE", ignoreCase = true) ||
                    headerStr.contains("<html", ignoreCase = true)) {
                    Log.w(TAG, "Cache file is HTML, not audio")
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
        if (!isLive && prepared) {
            player?.seekTo(pos.toInt())
        }
    }

    fun skipForward() {
        if (isLive || !prepared) return
        player?.let { p ->
            var pPos = p.currentPosition + skipSeconds * 1000
            val dur = p.duration
            if (dur > 0 && pPos > dur) pPos = dur
            p.seekTo(pPos)
        }
    }

    fun skipBackward() {
        if (isLive || !prepared) return
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

        // 快退15秒
        val rewindIntent = Intent(this, RadioPlaybackService::class.java).apply {
            action = ACTION_REWIND
        }
        val rewindPI = PendingIntent.getService(
            this, 1, rewindIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 上一节目
        val prevEpIntent = Intent(this, RadioPlaybackService::class.java).apply {
            action = ACTION_PREV_EPISODE
        }
        val prevEpPI = PendingIntent.getService(
            this, 2, prevEpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 上一片段
        val prevSegIntent = Intent(this, RadioPlaybackService::class.java).apply {
            action = ACTION_PREV_SEGMENT
        }
        val prevSegPI = PendingIntent.getService(
            this, 3, prevSegIntent,
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
        // 下一片段
        val nextSegIntent = Intent(this, RadioPlaybackService::class.java).apply {
            action = ACTION_NEXT_SEGMENT
        }
        val nextSegPI = PendingIntent.getService(
            this, 5, nextSegIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 下一节目
        val nextEpIntent = Intent(this, RadioPlaybackService::class.java).apply {
            action = ACTION_NEXT_EPISODE
        }
        val nextEpPI = PendingIntent.getService(
            this, 6, nextEpIntent,
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
            // compact view 最多显示3个按钮: 上节目(1)、播放/暂停(3)、下节目(5)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(1, 3, 5))
            .addAction(R.drawable.ic_rewind, "-15s", rewindPI)
            .addAction(R.drawable.ic_prev, "上节目", prevEpPI)
            .addAction(R.drawable.ic_prev, "上片段", prevSegPI)
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "暂停" else "播放",
                playPausePI
            )
            .addAction(R.drawable.ic_next, "下片段", nextSegPI)
            .addAction(R.drawable.ic_next, "下节目", nextEpPI)
            .addAction(R.drawable.ic_forward, "+15s", forwardPI)
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
                        setDataSourceWithHeaders(currentStreamUrl)  // 内部调用 prepareAsync()
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
