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
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.radio.app.R
import com.radio.app.RadioApplication
import com.radio.app.activities.PlayerActivity
import com.radio.app.database.RadioDatabaseHelper
import com.radio.app.models.AppSettings
import com.radio.app.models.Episode
import com.radio.app.models.RadioStation
import com.radio.app.models.VoiceSegment
import com.radio.app.utils.NetworkUtils
import com.radio.app.utils.PreferenceManager
import com.radio.app.utils.ThemeManager
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
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class RadioPlaybackService : Service(), AudioManager.OnAudioFocusChangeListener {

    companion object {
        private const val TAG = "RadioPlaybackService"
        private const val MAX_ERROR_RETRY = 3
        private const val NOTIFICATION_ID = 1
        private const val POSITION_SAVE_INTERVAL = 15000L

        const val ACTION_PLAY = "com.radio.app.PLAY"
        const val ACTION_PAUSE = "com.radio.app.PAUSE"
        const val ACTION_STOP = "com.radio.app.STOP"
        const val ACTION_PREV_SEGMENT = "com.radio.app.PREV_SEGMENT"
        const val ACTION_NEXT_SEGMENT = "com.radio.app.NEXT_SEGMENT"
        const val ACTION_REWIND = "com.radio.app.REWIND"
        const val ACTION_FORWARD = "com.radio.app.FORWARD"
        const val ACTION_PREV_EPISODE = "com.radio.app.PREV_EPISODE"
        const val ACTION_NEXT_EPISODE = "com.radio.app.NEXT_EPISODE"
        const val ACTION_SEEK_PCT = "com.radio.app.SEEK_PCT"
        const val ACTION_SEEK_SEC = "com.radio.app.SEEK_SEC"

        const val BROADCAST_BUFFER_UPDATE = "com.radio.app.BUFFER_UPDATE"
        const val BROADCAST_STATE_CHANGED = "com.radio.app.STATE_CHANGED"
        const val BROADCAST_CACHE_UPDATE = "com.radio.app.CACHE_UPDATE"
        const val EXTRA_BUFFER_PERCENT = "buffer_percent"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_CACHE_PERCENT = "cache_percent"
        const val EXTRA_CACHE_PATH = "cache_path"
        const val EXTRA_SEEK_PCT = "seek_pct"
        const val EXTRA_SEEK_SEC = "seek_sec"

        fun getLastEpisode(context: Context): Episode? {
            val prefs = context.getSharedPreferences("last_episode", Context.MODE_PRIVATE)
            val id = prefs.getString("episode_id", null) ?: return null
            val title = prefs.getString("title", null) ?: return null
            val audioUrl = prefs.getString("audio_url", null) ?: return null
            return Episode().apply {
                this.id = id
                this.title = title
                this.audioUrl = audioUrl
                this.stationName = prefs.getString("station_name", "") ?: ""
                this.stationId = prefs.getString("station_id", "") ?: ""
                this.duration = prefs.getLong("duration", 0)
                this.broadcastAt = prefs.getString("broadcast_at", null) ?: ""
                this.programName = prefs.getString("program_name", null)
            }
        }
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
    private var notificationHandler: Handler? = null
    private var notificationRunnable: Runnable? = null
    private var positionSaveHandler: Handler? = null
    private var positionSaveRunnable: Runnable? = null
    private var skipSeconds = 5
    private var errorRetryCount = 0
    private var isRetrying = false
    private var savePlaybackPosition = true
    private var notificationPlaying = false
    private var notificationTitle = "Radio App"
    private var notificationSubText = ""
    private var notificationDate = ""
    private var downloadingJob: kotlinx.coroutines.Job? = null
    private var positionRestoreRequested = false
    private var isSeekingToPosition = false
    private var useCompactNotification = false

    // 后台下载进度（供 UI 读取）
    @Volatile
    private var downloadProgressPct = 0
    @Volatile
    private var downloadTotalBytes = 0L
    @Volatile
    private var downloadDoneBytes = 0L
    private val downloadActive = AtomicBoolean(false)

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        autoSkipHandler = Handler(Looper.getMainLooper())
        progressHandler = Handler(Looper.getMainLooper())
        notificationHandler = Handler(Looper.getMainLooper())
        positionSaveHandler = Handler(Looper.getMainLooper())
        loadSettings()
        startProgressPolling()
        startNotificationProgressUpdater()
        startPositionSaver()
    }

    private fun ensurePlayerInitialized() {
        if (player != null) return
        try {
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36",
                    "Referer" to "https://www.hndt.com/"
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
                                    errorRetryCount = 0
                                    // 仅在首次就绪时恢复位置，避免 seek 引发状态循环
                                    if (positionRestoreRequested) {
                                        positionRestoreRequested = false
                                        applySavedPosition()
                                    }
                                    // seek 期间的 STATE_READY 不通知 UI，避免播放/暂停循环抖动
                                    if (!isSeekingToPosition) {
                                        callback?.onStateChanged(true)
                                    }
                                    isSeekingToPosition = false
                                    updateNotification()
                                    // 后台下载音频文件
                                    startBackgroundDownload()
                                }
                                Player.STATE_ENDED -> {
                                    callback?.onStateChanged(false)
                                    clearSavedPosition()
                                    stopAutoSkipCheck()
                                }
                            }
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            Log.e(TAG, "ExoPlayer error: ${error.message}")
                            prepared = false
                            errorRetryCount++
                            if (errorRetryCount <= MAX_ERROR_RETRY && currentStreamUrl.isNotEmpty()) {
                                isRetrying = true
                                Handler(Looper.getMainLooper()).postDelayed({
                                    try {
                                        player?.let {
                                            it.setMediaItem(MediaItem.fromUri(currentStreamUrl))
                                            it.prepare()
                                            it.play()
                                        }
                                    } catch (e: Exception) { Log.e(TAG, "Retry failed", e) }
                                }, errorRetryCount * 3000L)
                            } else {
                                callback?.onError("播放失败: ${error.message ?: "未知错误"}")
                            }
                        }
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            // seek 到记忆位置期间不通知 UI，避免播放/暂停循环抖动
                            if (isSeekingToPosition) return
                            callback?.onStateChanged(isPlaying)
                            notificationPlaying = isPlaying
                            updateNotification()
                        }
                    })
                }
        } catch (e: Exception) { Log.e(TAG, "ExoPlayer init failed", e) }
    }

    private fun startBackgroundDownload() {
        if (isLive) return
        val url = currentStreamUrl
        if (url.isBlank() || !url.startsWith("http")) return
        if (downloadActive.get()) return  // 已有下载任务进行中
        val fileName = extractCacheFileName(url)
        val episodesDir = File(cacheDir, "episodes")
        if (!episodesDir.exists()) episodesDir.mkdirs()
        val targetFile = File(episodesDir, fileName)
        // 已存在则直接标记完成
        if (targetFile.exists() && targetFile.length() > 0) {
            downloadProgressPct = 100
            downloadDoneBytes = targetFile.length()
            downloadTotalBytes = targetFile.length()
            sendCacheUpdateBroadcast(targetFile.length(), targetFile.absolutePath)
            return
        }
        downloadActive.set(true)
        downloadProgressPct = 0
        downloadDoneBytes = 0
        downloadTotalBytes = 0
        downloadingJob?.cancel()
        downloadingJob = serviceScope.launch {
            try {
                Log.d(TAG, "Downloading audio to: ${targetFile.absolutePath}")
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 60000
                connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
                connection.setRequestProperty("Referer", "https://www.hndt.com/")
                connection.connect()

                if (connection.responseCode != 200) {
                    Log.e(TAG, "Download failed: HTTP ${connection.responseCode}")
                    downloadActive.set(false)
                    return@launch
                }

                downloadTotalBytes = connection.contentLength.toLong()
                val input = connection.inputStream
                val output = FileOutputStream(targetFile)
                val buffer = ByteArray(8192)
                var totalRead = 0L
                var bytesRead: Int
                var lastProgress = 0

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    downloadDoneBytes = totalRead
                    if (downloadTotalBytes > 0) {
                        val pct = ((totalRead * 100) / downloadTotalBytes).toInt()
                        if (pct - lastProgress >= 5) {
                            lastProgress = pct
                            downloadProgressPct = pct
                            sendCacheUpdateBroadcast(totalRead, targetFile.absolutePath)
                        }
                    }
                }
                output.close()
                input.close()
                connection.disconnect()
                downloadProgressPct = 100
                downloadDoneBytes = downloadTotalBytes.coerceAtLeast(totalRead)
                sendCacheUpdateBroadcast(totalRead, targetFile.absolutePath)
                Log.d(TAG, "Download complete: ${targetFile.absolutePath} (${totalRead} bytes)")
                // 预缓存下一节目
                try {
                    val settings = AppSettings.getInstance(this@RadioPlaybackService)
                    if (settings.preloadCache && settings.autoCache) {
                        val wifiOk = !settings.wifiOnlyPreCache || NetworkUtils.isWifiConnected(this@RadioPlaybackService)
                        if (wifiOk) {
                            sendBroadcast(Intent("com.radio.app.PRECACHE_TRIGGER"))
                        }
                    }
                } catch (e: Exception) { Log.w(TAG, "Precache trigger failed", e) }
            } catch (e: Exception) {
                Log.e(TAG, "Download error: ${e.message}")
                try { targetFile.delete() } catch (_: Exception) {}
            } finally {
                downloadActive.set(false)
            }
        }
    }

    private fun extractCacheFileName(url: String): String {
        return try {
            val path = URL(url).path
            val name = path.substringAfterLast("/")
            if (name.isBlank()) "unknown.mp4" else name
        } catch (e: Exception) {
            val name = url.substringAfterLast("/")
            if (name.isBlank()) "unknown.mp4" else name
        }
    }

    private fun sendCacheUpdateBroadcast(size: Long, path: String) {
        val intent = Intent(BROADCAST_CACHE_UPDATE).apply {
            putExtra(EXTRA_CACHE_PATH, path)
            putExtra("cache_size", size)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun applySavedPosition() {
        if (!savePlaybackPosition) return
        val ep = currentEpisode ?: return
        val episodeKey = "${ep.stationId}::${ep.title}"
        val savedPos = getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(episodeKey, -1L)
        if (savedPos > 0 && !isLive) {
            isSeekingToPosition = true  // 抑制 seek 期间的 UI 回调
            player?.seekTo(savedPos)
            Log.d(TAG, "Restored position: ${savedPos}ms for $episodeKey")
        }
    }

    private fun startPositionSaver() {
        positionSaveRunnable = Runnable {
            if (savePlaybackPosition && !isLive && prepared && player?.isPlaying == true) {
                saveCurrentPosition()
            }
            positionSaveHandler?.postDelayed(positionSaveRunnable!!, POSITION_SAVE_INTERVAL)
        }
        positionSaveRunnable?.let { positionSaveHandler?.post(it) }
    }

    private fun saveCurrentPosition() {
        val ep = currentEpisode ?: return
        val pos = player?.currentPosition ?: return
        if (pos <= 0) return
        val episodeKey = "${ep.stationId}::${ep.title}"
        getSharedPreferences("playback_positions", MODE_PRIVATE)
            .edit().putLong(episodeKey, pos).apply()
    }

    private fun clearSavedPosition() {
        val ep = currentEpisode ?: return
        val episodeKey = "${ep.stationId}::${ep.title}"
        getSharedPreferences("playback_positions", MODE_PRIVATE)
            .edit().remove(episodeKey).apply()
    }

    private fun saveLastEpisode(episode: Episode) {
        val prefs = getSharedPreferences("last_episode", MODE_PRIVATE)
        prefs.edit().apply {
            putString("episode_id", episode.id)
            putString("title", episode.title)
            putString("audio_url", episode.audioUrl)
            putString("station_name", episode.stationName)
            putString("station_id", episode.stationId)
            putLong("duration", episode.duration)
            putString("broadcast_at", episode.broadcastAt)
            putString("program_name", episode.programName)
            putLong("saved_at", System.currentTimeMillis())
        }.apply()
    }

    fun getSavedPosition(episode: Episode?): Long {
        val ep = episode ?: return -1L
        val episodeKey = "${ep.stationId}::${ep.title}"
        return getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(episodeKey, -1L)
    }

    private fun startProgressPolling() {
        progressRunnable = Runnable {
            player?.let { p ->
                callback?.let { cb ->
                    try {
                        val pos = p.currentPosition
                        val dur = p.duration
                        val effectiveDur = if (dur > 0) dur else if (isLive) -1L else 0L
                        cb.onPositionChanged(pos, effectiveDur)
                        if (prepared && !isLive && dur > 0) {
                            val bp = ((pos * 100) / dur).toInt().coerceIn(0, 100)
                            if (bp != bufferPercent) {
                                bufferPercent = bp
                                cb.onBufferUpdate(bp)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            progressHandler?.postDelayed(progressRunnable!!, 500)
        }
        progressRunnable?.let { progressHandler?.post(it) }
    }

    private fun startNotificationProgressUpdater() {
        notificationRunnable = Runnable {
            if (!isLive && prepared && player != null) {
                updateNotification()
            }
            notificationHandler?.postDelayed(notificationRunnable!!, 1000)
        }
        notificationRunnable?.let { notificationHandler?.post(it) }
    }

    private fun updateNotification() {
        val playing = player?.isPlaying ?: false
        val remoteViews = if (useCompactNotification) {
            RemoteViews(packageName, R.layout.notification_compact)
        } else {
            RemoteViews(packageName, R.layout.notification_custom)
        }
        applyNotificationIntents(remoteViews)

        remoteViews.setImageViewResource(R.id.play_pause_icon,
            if (playing) R.drawable.notif_pause else R.drawable.notif_play)
        remoteViews.setTextViewText(R.id.play_pause_text, if (playing) "暂停" else "播放")

        remoteViews.setTextViewText(R.id.notification_title, notificationTitle)
        remoteViews.setTextViewText(R.id.notification_subtitle,
            if (playing) "正在播放 $notificationSubText" else "已暂停 $notificationSubText")

        if (!isLive && prepared) {
            remoteViews.setViewVisibility(R.id.notification_progress_area, android.view.View.VISIBLE)
            val p = player ?: return
            val pos = p.currentPosition
            val dur = p.duration
            if (dur > 0) {
                val progress = ((pos * 1000) / dur).toInt().coerceIn(0, 1000)
                remoteViews.setProgressBar(R.id.notification_progress, 1000, progress, false)
                val totalSec = dur.toInt() / 1000
                val curSec = pos.toInt() / 1000
                remoteViews.setTextViewText(R.id.notification_time_text,
                    "${formatTimeNotif(curSec)}/${formatTimeNotif(totalSec)}")
            }
        } else {
            remoteViews.setViewVisibility(R.id.notification_progress_area, android.view.View.GONE)
        }

        applyThemeToNotification(remoteViews)
        applySeekIntents(remoteViews)

        val deleteIntent = PendingIntent.getService(this, 99,
            Intent(this, RadioPlaybackService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification: Notification = NotificationCompat.Builder(this, RadioApplication.CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(if (playing) "正在播放 $notificationSubText" else "已暂停 $notificationSubText")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(createContentIntent())
            .setOngoing(true)
            .setAutoCancel(false)
            .setDeleteIntent(deleteIntent)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCustomContentView(remoteViews)
            .setCustomBigContentView(remoteViews)
            .build()

        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun applySeekIntents(remoteViews: RemoteViews) {
        try {
        // 50个跳转点，每2%一个，精度更高
        val pcts = FloatArray(51) { it * 0.02f }
        val ids = intArrayOf(
            R.id.btn_seek_0, R.id.btn_seek_2, R.id.btn_seek_4, R.id.btn_seek_6, R.id.btn_seek_8,
            R.id.btn_seek_10, R.id.btn_seek_12, R.id.btn_seek_14, R.id.btn_seek_16, R.id.btn_seek_18,
            R.id.btn_seek_20, R.id.btn_seek_22, R.id.btn_seek_24, R.id.btn_seek_26, R.id.btn_seek_28,
            R.id.btn_seek_30, R.id.btn_seek_32, R.id.btn_seek_34, R.id.btn_seek_36, R.id.btn_seek_38,
            R.id.btn_seek_40, R.id.btn_seek_42, R.id.btn_seek_44, R.id.btn_seek_46, R.id.btn_seek_48,
            R.id.btn_seek_50, R.id.btn_seek_52, R.id.btn_seek_54, R.id.btn_seek_56, R.id.btn_seek_58,
            R.id.btn_seek_60, R.id.btn_seek_62, R.id.btn_seek_64, R.id.btn_seek_66, R.id.btn_seek_68,
            R.id.btn_seek_70, R.id.btn_seek_72, R.id.btn_seek_74, R.id.btn_seek_76, R.id.btn_seek_78,
            R.id.btn_seek_80, R.id.btn_seek_82, R.id.btn_seek_84, R.id.btn_seek_86, R.id.btn_seek_88,
            R.id.btn_seek_90, R.id.btn_seek_92, R.id.btn_seek_94, R.id.btn_seek_96, R.id.btn_seek_98,
            R.id.btn_seek_100
        )
        for (i in pcts.indices) {
            val pi = PendingIntent.getService(this, 20 + i,
                Intent(this, RadioPlaybackService::class.java).apply {
                    action = ACTION_SEEK_PCT; putExtra(EXTRA_SEEK_PCT, pcts[i])
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            remoteViews.setOnClickPendingIntent(ids[i], pi)
        }
        } catch (_: Exception) {}
    }

    private fun formatTimeNotif(seconds: Int): String {
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", minutes, secs)
    }

    private fun loadSettings() {
        val prefMgr = PreferenceManager(this)
        val settings: AppSettings = prefMgr.loadSettings()
        continuousPlay = settings.continuousPlay
        savePlaybackPosition = settings.savePlaybackPosition
        useCompactNotification = settings.useCompactNotification
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
                ACTION_SEEK_PCT -> {
                    val pct = intent.getFloatExtra(EXTRA_SEEK_PCT, 0f)
                    seekToPercent(pct)
                }
                ACTION_SEEK_SEC -> {
                    val sec = intent.getIntExtra(EXTRA_SEEK_SEC, 0)
                    seekToSec(sec)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun seekToPercent(pct: Float) {
        if (isLive || !prepared) return
        val dur = player?.duration ?: 0L
        if (dur <= 0) return
        player?.seekTo((dur * pct).toLong())
        updateNotification()
    }

    private fun seekToSec(sec: Int) {
        if (isLive || !prepared) return
        player?.seekTo((sec * 1000L).coerceAtLeast(0))
        updateNotification()
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setWillPauseWhenDucked(true)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(this).build()
            audioFocusRequest = request
            am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            am.abandonAudioFocusRequest(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION") am.abandonAudioFocus(this)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> play()
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.takeIf { it.isPlaying }?.volume = 0.3f
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                player?.volume = 1.0f; play()
            }
        }
    }

    fun playStation(station: RadioStation) {
        currentStation = station; currentEpisode = null; isLive = true
        prepared = false; currentStreamUrl = station.streamUrl ?: ""
        errorRetryCount = 0; isRetrying = false; stopAutoSkipCheck()
        positionRestoreRequested = false
        downloadProgressPct = 0; downloadDoneBytes = 0; downloadTotalBytes = 0
        ensurePlayerInitialized()
        try {
            player?.let {
                it.setMediaItem(MediaItem.fromUri(currentStreamUrl))
                it.prepare(); it.playWhenReady = true
            }
            notificationTitle = station.name; notificationSubText = "[直播]"
            notificationDate = ""; notificationPlaying = true
            requestAudioFocus(); updateNotification()
        } catch (e: Exception) { Log.e(TAG, "playStation failed", e) }
    }

    fun playEpisode(episode: Episode, live: Boolean) {
        currentEpisode = episode; currentStation = null; isLive = live
        prepared = false; errorRetryCount = 0; isRetrying = false
        stopAutoSkipCheck()
        positionRestoreRequested = true  // 下次 STATE_READY 时恢复位置
        downloadProgressPct = 0; downloadDoneBytes = 0; downloadTotalBytes = 0
        currentStreamUrl = episode.audioUrl ?: ""
        notificationTitle = episode.title ?: "节目回放"
        notificationDate = episode.broadcastAt?.take(10) ?: ""
        notificationSubText = if (notificationDate.isNotEmpty()) "[回放] $notificationDate" else "[回放]"
        notificationPlaying = true
        saveLastEpisode(episode)
        ensurePlayerInitialized()
        try {
            player?.let {
                it.setMediaItem(MediaItem.fromUri(currentStreamUrl))
                it.prepare(); it.playWhenReady = true
            }
            requestAudioFocus(); updateNotification()
            startAutoSkipCheck()
        } catch (e: Exception) { Log.e(TAG, "playEpisode failed", e) }
    }

    fun play() { player?.play() }
    fun pause() { player?.pause() }
    fun stop() {
        stopAutoSkipCheck(); saveCurrentPosition()
        downloadingJob?.cancel()
        downloadActive.set(false)
        player?.let { it.stop(); abandonAudioFocus() }
        (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .cancel(NOTIFICATION_ID)
        stopSelf()
    }
    fun seekTo(pos: Long) { if (!isLive && prepared) player?.seekTo(pos) }
    fun skipForward() {
        if (isLive || !prepared) return
        val pPos = (player?.currentPosition ?: 0) + skipSeconds * 1000
        val dur = player?.duration ?: 0
        player?.seekTo(if (dur > 0 && pPos > dur) dur else pPos)
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
    fun getDuration(): Long { val dur = player?.duration ?: 0L; return if (dur < 0) -1L else dur }
    fun getBufferedPercentage(): Int = player?.bufferedPercentage ?: 0
    fun getBufferedPosition(): Long = player?.bufferedPosition ?: 0L
    fun getCurrentEpisode(): Episode? = currentEpisode
    fun getCurrentStation(): RadioStation? = currentStation
    fun setCallback(cb: Callback?) { callback = cb }

    /** 获取后台下载进度（0-100），用于缓存进度显示 */
    fun getDownloadProgress(): Int = downloadProgressPct
    fun getDownloadTotalBytes(): Long = downloadTotalBytes
    fun getDownloadDoneBytes(): Long = downloadDoneBytes
    fun isDownloading(): Boolean = downloadActive.get()

    fun jumpToNextSegment() {
        val segments = currentEpisode?.voiceSegments ?: return
        val currentPos = player?.currentPosition ?: 0L
        for (seg in segments) { if (seg.start > currentPos) { seekTo(seg.start); return } }
    }

    private fun notifyPrevEpisode() {
        val intent = Intent(BROADCAST_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_PLAYING, false); putExtra("action", "prev_episode")
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
    private fun notifyNextEpisode() {
        val intent = Intent(BROADCAST_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_PLAYING, false); putExtra("action", "next_episode")
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
    fun jumpToPrevSegment() {
        val segments = currentEpisode?.voiceSegments ?: return
        val currentPos = player?.currentPosition ?: 0L
        var prev: VoiceSegment? = null
        for (i in segments.indices) {
            if (segments[i].end >= currentPos) { if (i > 0) prev = segments[i - 1]; break }
        }
        prev?.let { seekTo(it.start) } ?: segments.firstOrNull()?.let { seekTo(it.start) }
    }

    fun markSegment(index: Int, isDry: Boolean) {
        val segments = currentEpisode?.voiceSegments ?: return
        if (index < 0 || index >= segments.size) return
        val seg = segments[index]
        seg.isManuallyMarked = true; seg.hasVoice = isDry
        seg.label = if (isDry) "手动标记:干货" else "手动标记:水分"
        currentEpisode?.id?.let { episodeId ->
            RadioDatabaseHelper.getInstance(this).saveManualSegmentMark(episodeId, seg.start, seg.end, isDry)
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
                    val segments = currentEpisode?.voiceSegments ?: return@Runnable
                    val currentPos = p.currentPosition
                    for (seg in segments) {
                        if (currentPos >= seg.start && currentPos < seg.end) {
                            if (seg.shouldAutoSkip()) jumpToNextDrySegment(seg)
                            break
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
            if (seg.start > currentSeg.end && seg.isEffectiveDry()) { seekTo(seg.start); return }
        }
    }
    private fun stopAutoSkipCheck() {
        autoSkipRunnable?.let { autoSkipHandler?.removeCallbacks(it) }; autoSkipRunnable = null
    }

    private fun createContentIntent(): PendingIntent {
        return PendingIntent.getActivity(this, 0,
            Intent(this, PlayerActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun applyNotificationIntents(remoteViews: RemoteViews) {
        val playing = player?.isPlaying ?: false
        try { remoteViews.setOnClickPendingIntent(R.id.btn_rewind,
            PendingIntent.getService(this, 1, Intent(this, RadioPlaybackService::class.java).apply { action = ACTION_REWIND },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) } catch (_: Exception) {}
        try { remoteViews.setOnClickPendingIntent(R.id.btn_prev_segment,
            PendingIntent.getService(this, 2, Intent(this, RadioPlaybackService::class.java).apply { action = ACTION_PREV_SEGMENT },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) } catch (_: Exception) {}
        try { remoteViews.setOnClickPendingIntent(R.id.btn_prev_episode,
            PendingIntent.getService(this, 3, Intent(this, RadioPlaybackService::class.java).apply { action = ACTION_PREV_EPISODE },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) } catch (_: Exception) {}
        try { remoteViews.setOnClickPendingIntent(R.id.btn_play_pause,
            PendingIntent.getService(this, 4, Intent(this, RadioPlaybackService::class.java).apply { action = if (playing) ACTION_PAUSE else ACTION_PLAY },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) } catch (_: Exception) {}
        try { remoteViews.setOnClickPendingIntent(R.id.btn_next_episode,
            PendingIntent.getService(this, 5, Intent(this, RadioPlaybackService::class.java).apply { action = ACTION_NEXT_EPISODE },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) } catch (_: Exception) {}
        try { remoteViews.setOnClickPendingIntent(R.id.btn_next_segment,
            PendingIntent.getService(this, 6, Intent(this, RadioPlaybackService::class.java).apply { action = ACTION_NEXT_SEGMENT },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) } catch (_: Exception) {}
        try { remoteViews.setOnClickPendingIntent(R.id.btn_forward,
            PendingIntent.getService(this, 7, Intent(this, RadioPlaybackService::class.java).apply { action = ACTION_FORWARD },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) } catch (_: Exception) {}
    }

    private fun applyThemeToNotification(remoteViews: RemoteViews) {
        try {
            val theme = ThemeManager.getCurrentTheme(this)
            val bgColor: Int; val textColor: Int; val subTextColor: Int; val timeTextColor: Int
            when (theme) {
                "dark" -> {
                    bgColor = android.graphics.Color.parseColor("#CC16213e")
                    textColor = android.graphics.Color.parseColor("#e0e0e0")
                    subTextColor = android.graphics.Color.parseColor("#b0b0b0")
                    timeTextColor = android.graphics.Color.parseColor("#90caf9")
                }
                "fresh" -> {
                    bgColor = android.graphics.Color.parseColor("#CCFFFFFF")
                    textColor = android.graphics.Color.parseColor("#1A1A1A")
                    subTextColor = android.graphics.Color.parseColor("#555555")
                    timeTextColor = android.graphics.Color.parseColor("#1976D2")
                }
                "classic" -> {
                    bgColor = android.graphics.Color.parseColor("#CCFFFFFF")
                    textColor = android.graphics.Color.parseColor("#2C1810")
                    subTextColor = android.graphics.Color.parseColor("#7B6B5A")
                    timeTextColor = android.graphics.Color.parseColor("#8B4513")
                }
                "minimal" -> {
                    bgColor = android.graphics.Color.parseColor("#CCFFFFFF")
                    textColor = android.graphics.Color.parseColor("#2D1B3D")
                    subTextColor = android.graphics.Color.parseColor("#6B5A7B")
                    timeTextColor = android.graphics.Color.parseColor("#673AB7")
                }
                else -> {
                    bgColor = android.graphics.Color.parseColor("#CC16213e")
                    textColor = android.graphics.Color.parseColor("#e0e0e0")
                    subTextColor = android.graphics.Color.parseColor("#b0b0b0")
                    timeTextColor = android.graphics.Color.parseColor("#90caf9")
                }
            }
            try { remoteViews.setInt(R.id.notification_root, "setBackgroundColor", bgColor) } catch (_: Exception) {}
            try { remoteViews.setTextColor(R.id.notification_title, textColor) } catch (_: Exception) {}
            try { remoteViews.setTextColor(R.id.notification_subtitle, subTextColor) } catch (_: Exception) {}
            try { remoteViews.setTextColor(R.id.notification_time_text, timeTextColor) } catch (_: Exception) {}
        } catch (e: Exception) { Log.e(TAG, "Failed to apply theme", e) }
    }

    private fun sendStateBroadcast(playing: Boolean) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(BROADCAST_STATE_CHANGED).apply { putExtra(EXTRA_IS_PLAYING, playing) })
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        progressRunnable?.let { progressHandler?.removeCallbacks(it) }
        notificationRunnable?.let { notificationHandler?.removeCallbacks(it) }
        positionSaveRunnable?.let { positionSaveHandler?.removeCallbacks(it) }
        stopAutoSkipCheck()
        downloadingJob?.cancel()
        downloadActive.set(false)
        saveCurrentPosition()
        abandonAudioFocus()
        serviceScope.cancel()
        player?.release()
        player = null
        (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .cancel(NOTIFICATION_ID)
    }
}