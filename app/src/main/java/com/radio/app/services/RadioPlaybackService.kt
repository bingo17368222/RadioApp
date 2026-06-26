package com.radio.app.services

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
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
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaMetadataCompat
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Objects
import java.util.concurrent.atomic.AtomicBoolean

class RadioPlaybackService : Service(), AudioManager.OnAudioFocusChangeListener {

    companion object {
        private const val TAG = "RadioPlaybackService"
        private const val MAX_ERROR_RETRY = 3
        private const val NOTIFICATION_ID = 1
        private const val POSITION_SAVE_INTERVAL = 5000L

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
        const val BROADCAST_EPISODE_CHANGED = "com.radio.app.EPISODE_CHANGED"
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
        fun onEpisodeChanged(episode: Episode) {}
    }

    inner class LocalBinder : Binder() {
        fun getService(): RadioPlaybackService = this@RadioPlaybackService
    }

    private fun writeServiceLog(category: String, msg: String) {
        try {
            val logDir = java.io.File(com.radio.app.RadioApplication.getLogDir(this), category)
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = java.io.File(logDir, "${category}.log")
            // Add version header on first write
            if (!logFile.exists()) {
                logFile.appendText("=== RadioApp v2.0.18 ===\n")
            }
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            java.io.FileWriter(logFile, true).use { it.append("[$ts] $msg\n") }
            // Limit file size to 500KB
            if (logFile.length() > 500_000) {
                val lines = logFile.readLines()
                val keep = lines.takeLast(500)
                logFile.writeText(keep.joinToString("\n") + "\n")
            }
        } catch (_: Exception) {}
    }

    // Issue 3 & 4: Dedicated notification detail logging for diagnosing notification date/update issues
    private fun writeNotifDetailLog(message: String) {
        try {
            val logDir = java.io.File(getExternalFilesDir(null), "logs/notif_detail")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = java.io.File(logDir, "notif_detail.log")
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
            logFile.appendText("[$timestamp] $message\n")
        } catch (_: Exception) {}
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
    private var lastNotifiedPosition = -1L
    private var positionSaveHandler: Handler? = null
    private var positionSaveRunnable: Runnable? = null
    private var skipSeconds = 15
    private var errorRetryCount = 0
    private var isRetrying = false
    private var savePlaybackPosition = true
    private var notificationPlaying = false
    private var userPaused = false // Track whether USER paused (vs buffering pause)
    private var notificationTitle = "Radio App"
    private var notificationSubText = ""
    private var notificationDate = ""
    private var notificationTimeRange = ""
    private var downloadingJob: kotlinx.coroutines.Job? = null
    private var positionRestoreRequested = false
    private var pendingStartPosition: Long = -1L
    private var isSeekingToPosition = false
    private var lastPositionRestoreTime = 0L
    private var notificationStyle = "full"
    private var lastNotificationTime = 0L
    private var pendingNotificationRunnable: Runnable? = null
    // SharedPreferences监听器，用于热切换通知栏样式
    private var prefChangeListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    // 后台下载进度（供 UI 读取）
    @Volatile
    private var downloadProgressPct = 0
    @Volatile
    private var downloadTotalBytes = 0L
    @Volatile
    private var downloadDoneBytes = 0L
    private val downloadActive = AtomicBoolean(false)

    // 防抖标记：playback 正在初始化中，避免 Activity 重复启动播放
    @Volatile
    var playbackInitializing = false

    // 预缓存标记：预缓存下载期间跳过通知栏更新，避免频繁刷新通知
    @Volatile
    private var isPrecaching = false
    // 预缓存完成计数：记录本轮预缓存下载完成的数量，在结束时统一通知用户
    @Volatile
    private var precacheCompletedCount = 0
    // 预缓存完成的文件名列表：用于在通知中展示具体下载了哪些文件
    private val precacheCompletedFileNames = mutableListOf<String>()
    // 预缓存完成通知是否已展示：确保每轮预缓存只弹一次汇总通知
    private var precacheNotificationShown = false
    // 预缓存时间节流：记录上次预缓存检查的时间戳，无节目可下载时 30 秒内不重复触发，避免无限循环
    private var lastPreCacheCheckTime: Long = 0
    // 通知内容去重哈希：内容未变化时跳过 manager.notify()，避免 ExoPlayer 缓冲态频繁刷新导致的通知闪烁
    private var lastNotificationContentHash: Int = 0

    // MediaSession for Bluetooth/media button support
    private var mediaSession: MediaSessionCompat? = null
    // Issue 1: Cache the contentIntent PendingIntent - creating it on every notification update may cause Activity recreation
    private var cachedContentIntent: PendingIntent? = null
    // 防止切回app时重复启动播放
    @Volatile
    private var playbackStarted = false
    private var currentPlayingUrl = ""

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
        initMediaSession()
        startProgressPolling()
        startNotificationProgressUpdater()
        startPositionSaver()
        // 提升为前台服务，防止后台被杀
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(RadioApplication.NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(RadioApplication.NOTIFICATION_ID, buildNotification())
        }
        
        // 注册SharedPreferences监听器，实现通知栏样式热切换
        val prefs = getSharedPreferences("radio_app_settings", Context.MODE_PRIVATE)
        prefChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            Log.d(TAG, "Pref changed: key=$key")
            when (key) {
                "notification_style" -> {
                    reloadNotificationStyle()
                    Log.d(TAG, "Hot-switch notification style to: $notificationStyle")
                    val handler = Handler(Looper.getMainLooper())
                    handler.post { notifyNotification() }
                }
                "skip_seconds" -> {
                    skipSeconds = prefs.getInt("skip_seconds", 15)
                    Log.d(TAG, "Hot-switch skipSeconds to: $skipSeconds, rebuilding notification")
                    val handler = Handler(Looper.getMainLooper())
                    handler.post { notifyNotification() }
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
    }

    private fun initMediaSession() {
        val componentName = ComponentName(this, "RadioPlaybackService")
        mediaSession = MediaSessionCompat(this, "RadioApp", componentName, null)
        mediaSession?.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                play()
            }
            override fun onPause() {
                pause()
            }
            override fun onSkipToNext() {
                notifyNextEpisode()
            }
            override fun onSkipToPrevious() {
                notifyPrevEpisode()
            }
            override fun onStop() {
                pause()
            }
            override fun onSeekTo(pos: Long) {
                seekTo(pos)
            }
            override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                val keyEvent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                    when (keyEvent.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> play()
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> pause()
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            if (isPlaying()) pause() else play()
                        }
                        KeyEvent.KEYCODE_MEDIA_NEXT -> notifyNextEpisode()
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> notifyPrevEpisode()
                        KeyEvent.KEYCODE_MEDIA_STOP -> pause()
                    }
                }
                return super.onMediaButtonEvent(mediaButtonEvent)
            }
        })
        mediaSession?.isActive = true
        updateMediaSessionState()
    }

    private fun updateMediaSessionState() {
        val state = if (player?.isPlaying == true)
            PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val pos = player?.currentPosition ?: 0L
        val builder = PlaybackStateCompat.Builder()
            .setState(state, pos, 1.0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP
            )
        mediaSession?.setPlaybackState(builder.build())

        // 设置MediaMetadata，系统需要duration才能显示SeekBar
        if (!isLive && prepared) {
            val dur = player?.duration ?: 0L
            if (dur > 0) {
                val metadata = MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, notificationTitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, notificationSubText)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur)
                    .build()
                mediaSession?.setMetadata(metadata)
            }
        }
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
                        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                            super.onPositionDiscontinuity(oldPosition, newPosition, reason)
                            if (reason == Player.DISCONTINUITY_REASON_SEEK && isSeekingToPosition) {
                                val actualPos = player?.currentPosition ?: -1L
                                Log.d(TAG, "SEEK completed: actual position=$actualPos ms, starting playback now")
                                player?.playWhenReady = true
                                isSeekingToPosition = false
                                notifyNotification()
                                startPositionSaver()
                            }
                        }
                        override fun onPlaybackStateChanged(state: Int) {
                            when (state) {
                                Player.STATE_READY -> {
                                    prepared = true
                                    playbackInitializing = false
                                    isRetrying = false
                                    errorRetryCount = 0
                                    Log.d(TAG, "STATE_READY: isSeekingToPosition=$isSeekingToPosition, positionRestoreRequested=$positionRestoreRequested, pendingStartPosition=$pendingStartPosition")
                                    if (positionRestoreRequested) {
                                        positionRestoreRequested = false
                                        applySavedPosition()
                                    }
                                    callback?.onStateChanged(true)
                                    // 后台下载音频文件
                                    startBackgroundDownload()
                                    // 尝试触发PCM预解码（当前节目已缓存时）
                                    startPcmPreDecodeIfNeeded()
                                }
                                Player.STATE_ENDED -> {
                                    callback?.onStateChanged(false)
                                    clearSavedPosition()
                                    stopAutoSkipCheck()
                                    // 连续播放：播放完成后自动播放下一个节目
                                    if (continuousPlay && !isLive) {
                                        Log.d(TAG, "Playback ended, auto-playing next episode")
                                        autoPlayNextEpisode()
                                    }
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
                            updateMediaSessionState()
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
            if (!isPrecaching) {
                Handler(Looper.getMainLooper()).post { triggerPreCache() }
            }
            startPcmPreDecodeIfNeeded()
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
                // Save cache file -> episode mapping for dislike filtering
                try {
                    val ep = currentEpisode
                    if (ep != null) {
                        val cacheMappingPrefs = getSharedPreferences("cache_episode_mapping", MODE_PRIVATE)
                        cacheMappingPrefs.edit()
                            .putString(fileName, com.google.gson.Gson().toJson(ep))
                            .apply()
                        Log.d(TAG, "Download: saved cache_episode_mapping for $fileName")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Download: failed to save cache_episode_mapping: ${e.message}")
                }
                // 预缓存下一节目
                if (!isPrecaching) {
                    Handler(Looper.getMainLooper()).post { triggerPreCache() }
                }
                // 尝试触发当前节目的PCM预解码
                startPcmPreDecodeIfNeeded()
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

    /**
     * 写入预缓存日志到外部存储，方便调试
     */
    private fun writePreCacheLog(msg: String) {
        try {
            val logDir = getExternalFilesDir("logs")
            if (logDir != null) {
                if (!logDir.exists()) logDir.mkdirs()
                val logFile = File(logDir, "precache.log")
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                FileWriter(logFile, true).use { it.append("[$ts] $msg\n") }
                Log.d(TAG, "[PreCache] $msg")
            }
        } catch (_: Exception) {}
    }

    private fun triggerPreCache() {
        val now = System.currentTimeMillis()
        if (now - lastPreCacheCheckTime < 30_000) {
            // Throttle: don't re-check within 30 seconds
            return
        }
        lastPreCacheCheckTime = now
        val settings = AppSettings.getInstance(this)
        writeServiceLog("notification", "triggerPreCache: START, isPrecaching=$isPrecaching, targetCount=${settings.preloadCacheCount}, currentCount=$precacheCompletedCount")
        if (!settings.autoCache) {
            Log.d(TAG, "Pre-cache: disabled")
            return
        }
        if (settings.wifiOnlyPreCache && !NetworkUtils.isWifiConnected(this)) {
            Log.d(TAG, "Pre-cache: skipped (WiFi only)")
            return
        }

        val currentEp = currentEpisode ?: run {
            Log.d(TAG, "Pre-cache: no current episode, skipping")
            return
        }

        // Re-entrancy guard: prevent infinite recursion / concurrent pre-cache chains
        if (isPrecaching) {
            Log.d(TAG, "Pre-cache: already running, skipping duplicate trigger")
            return
        }

        // 标记预缓存开始，通知栏进度轮询将跳过更新
        precacheCompletedCount = 0
        isPrecaching = true
        writeServiceLog("notification", "triggerPreCache: starting pre-cache loop, isPrecaching=true")

        val episodesDir = File(cacheDir, "episodes")
        if (!episodesDir.exists()) episodesDir.mkdirs()
        val cachedFiles = episodesDir.listFiles()?.filter { it.isFile && it.length() > 1024 } ?: emptyList()
        val cachedNames = cachedFiles.map { it.name }.toSet()

        var preCacheList = loadPreCacheList()
        Log.d(TAG, "Pre-cache: list has ${preCacheList.size} episodes, current=${currentEp.title}")

        // Find current episode index in the list
        var currentIdx = preCacheList.indexOfFirst { it.id == currentEp.id }
        if (currentIdx < 0) {
            // Current episode not in list, try to find by URL
            currentIdx = preCacheList.indexOfFirst { it.audioUrl == currentEp.audioUrl }
        }
        if (currentIdx < 0) {
            Log.d(TAG, "Pre-cache: current episode not in list, adding to list")
            preCacheList = listOf(currentEp) + preCacheList
            currentIdx = 0
            savePreCacheList(preCacheList)
        }

        // Count future episodes that are already cached (after current index)
        var futureCachedCount = 0
        var nextToDownload: Episode? = null
        for (i in (currentIdx + 1) until preCacheList.size) {
            val ep = preCacheList[i]
            val fileName = extractCacheFileName(ep.audioUrl)
            val isDisliked = settings.isDisliked(ep.id) || settings.isDislikedByTitle(ep.stationId, ep.title)
            if (fileName in cachedNames) {
                futureCachedCount++
            } else if (!isDisliked && ep.audioUrl.isNotBlank() && nextToDownload == null) {
                nextToDownload = ep
                Log.d(TAG, "Pre-cache: next to download: ${ep.title} (index=$i)")
            }
        }

        val targetCount = settings.preloadCacheCount
        writeServiceLog("notification", "triggerPreCache: START isPrecaching=$isPrecaching targetCount=$targetCount")
        Log.d(TAG, "Pre-cache: futureCached=$futureCachedCount, target=$targetCount")

        if (futureCachedCount >= targetCount) {
            Log.d(TAG, "Pre-cache: target reached ($futureCachedCount >= $targetCount)")
            isPrecaching = false
            showPrecacheCompleteNotification()
            return
        }

        // If no next episode to download in current list, fetch more days (forward only)
        if (nextToDownload == null) {
            Log.d(TAG, "Pre-cache: no more future episodes in list, fetching more days (forward)")
            val expandedList = fetchMoreDaysForPreCache(preCacheList, cachedFiles)
            if (expandedList.size > preCacheList.size) {
                savePreCacheList(expandedList)
                preCacheList = expandedList
                // Re-find current index and look for next to download
                currentIdx = preCacheList.indexOfFirst { it.id == currentEp.id || it.audioUrl == currentEp.audioUrl }
                for (i in (currentIdx + 1) until preCacheList.size) {
                    val ep = preCacheList[i]
                    val fileName = extractCacheFileName(ep.audioUrl)
                    val isDisliked = settings.isDisliked(ep.id) || settings.isDislikedByTitle(ep.stationId, ep.title)
                    if (fileName !in cachedNames && !isDisliked && ep.audioUrl.isNotBlank()) {
                        nextToDownload = ep
                        break
                    }
                }
            }
        }

        if (nextToDownload != null) {
            Log.d(TAG, "Pre-cache: downloading: ${nextToDownload!!.title}")
            downloadPreCacheEpisode(nextToDownload!!)
            // isPrecaching stays true during the async download;
            // downloadPreCacheEpisode will set isPrecaching=false and post triggerPreCache()
            // on the main looper when done (success/failure/skip), which continues the chain
        } else {
            Log.d(TAG, "Pre-cache: no more future episodes available to download")
            writeServiceLog("notification", "triggerPreCache: END, no more episodes to download")
            isPrecaching = false
            showPrecacheCompleteNotification()
            // Check if all future episodes are disliked
            val allDisliked = ((currentIdx + 1) until preCacheList.size).all { i ->
                val ep = preCacheList[i]
                settings.isDisliked(ep.id) || settings.isDislikedByTitle(ep.stationId, ep.title) ||
                extractCacheFileName(ep.audioUrl) in cachedNames
            }
            if (allDisliked && (currentIdx + 1) < preCacheList.size) {
                Log.d(TAG, "Pre-cache: all future episodes disliked or cached")
                Handler(Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(this, "当前电台未来节目已被全部缓存或标记为不喜欢", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 预缓存完成后显示一次汇总通知（仅当有下载时）
     */
    private fun showPrecacheCompleteNotification() {
        if (precacheNotificationShown) {
            writeServiceLog("notification", "showPrecacheCompleteNotification: already shown for this session, skipping. count=$precacheCompletedCount, files=${synchronized(precacheCompletedFileNames) { precacheCompletedFileNames.toList() }}")
            return
        }
        if (precacheCompletedCount <= 0) {
            isPrecaching = false
            precacheNotificationShown = true  // Prevent repeated calls with count=0
            writeServiceLog("notification", "showPrecacheCompleteNotification: no new files pre-cached (count=0), setting precacheNotificationShown=true to prevent repeats")
            return
        }
        val count = precacheCompletedCount
        val fileNames = synchronized(precacheCompletedFileNames) {
            precacheCompletedFileNames.toList()
        }
        precacheCompletedCount = 0
        synchronized(precacheCompletedFileNames) {
            precacheCompletedFileNames.clear()
        }
        isPrecaching = false

        val msg = "预缓存完成：${count}个文件"
        writeServiceLog("notification", "showPrecacheCompleteNotification: count=$count, files=$fileNames, isPrecaching was=$isPrecaching, msg=$msg")

        val namesText = if (fileNames.isNotEmpty()) {
            fileNames.joinToString("、")
        } else {
            ""
        }
        val contentText = if (namesText.isNotBlank()) {
            "预缓存完成：${count}个文件（${namesText}）"
        } else {
            "预缓存完成：${count}个文件"
        }
        val notification = NotificationCompat.Builder(this, RadioApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("预缓存完成")
            .setContentText(msg)
            .setAutoCancel(true)
            .setOngoing(false)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$msg\n${fileNames.take(5).joinToString("、")}${if (fileNames.size > 5) "..." else ""}"))
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        precacheNotificationShown = true
        writeServiceLog("notification", "showPrecacheCompleteNotification: SHOWING notification, count=$count, content='$contentText', files=$fileNames, notificationId=1001")
        writeServiceLog("notification", "showPrecacheCompleteNotification: about to show notification with ID=1001, title='预缓存完成', text='$msg', files=$fileNames")
        writeServiceLog("notification", "showPrecacheCompleteNotification: manager.notify(1001) precache complete, count=$count")
        manager.notify(1001, notification)
    }

    /**
     * 跨天获取更多节目用于预缓存
     * 仅向未来方向获取（当前播放节目向未来数10个）
     * 最多获取20天
     */
    private fun fetchMoreDaysForPreCache(existingList: List<Episode>, cachedFiles: List<File>): List<Episode> {
        val prefs = getSharedPreferences("precache_list", MODE_PRIVATE)
        val stationId = prefs.getString("station_id", null) ?: currentEpisode?.stationId
        val startDate = prefs.getString("current_date", null) ?: currentEpisode?.broadcastAt?.take(10)
        val daysFetched = prefs.getInt("days_fetched", 0)

        if (stationId.isNullOrBlank() || startDate.isNullOrBlank()) {
            writePreCacheLog("fetchMoreDaysForPreCache: missing stationId or startDate")
            return existingList
        }

        val maxDays = 20
        if (daysFetched >= maxDays) {
            writePreCacheLog("fetchMoreDaysForPreCache: limit reached ($daysFetched days)")
            return existingList
        }

        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        dateFormat.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")

        val resultList = existingList.toMutableList()
        val existingUrls = resultList.map { it.audioUrl }.toSet()
        val cachedNames = cachedFiles.map { it.name }.toSet()

        // Only go forward (future dates)
        val dayOffset = daysFetched + 1
        try {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
            cal.time = dateFormat.parse(startDate) ?: return existingList
            cal.add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
            val targetDate = dateFormat.format(cal.time)

            writePreCacheLog("fetchMoreDaysForPreCache: fetching $stationId on $targetDate (offset=+$dayOffset)")

            val apiService = com.radio.app.network.EpisodeApiService.getInstance()
            val newEpisodes = apiService.fetchEpisodesByDateSync(stationId, targetDate)

            if (newEpisodes != null && newEpisodes.isNotEmpty()) {
                val validNewEpisodes = newEpisodes.filter { ep ->
                    ep.audioUrl.isNotBlank() &&
                    ep.audioUrl !in existingUrls &&
                    extractCacheFileName(ep.audioUrl) !in cachedNames &&
                    ep.audioUrl.startsWith("http")
                }
                writePreCacheLog("fetchMoreDaysForPreCache: got ${newEpisodes.size} episodes for $targetDate, ${validNewEpisodes.size} valid new")
                resultList.addAll(validNewEpisodes)
            } else {
                writePreCacheLog("fetchMoreDaysForPreCache: no episodes for $targetDate")
            }
        } catch (e: Exception) {
            writePreCacheLog("fetchMoreDaysForPreCache: error: ${e.message}")
        }

        prefs.edit().putInt("days_fetched", daysFetched + 1).apply()
        writePreCacheLog("fetchMoreDaysForPreCache: returning ${resultList.size} episodes (was ${existingList.size})")
        return resultList
    }

    private fun savePreCacheList(episodes: List<Episode>) {
        val arr = org.json.JSONArray()
        for (ep in episodes) {
            val obj = org.json.JSONObject()
            obj.put("id", ep.id ?: "")
            obj.put("title", ep.title ?: "")
            obj.put("audio_url", ep.audioUrl ?: "")
            obj.put("station_name", ep.stationName ?: "")
            obj.put("station_id", ep.stationId ?: "")
            obj.put("duration", ep.duration)
            obj.put("broadcast_at", ep.broadcastAt ?: "")
            arr.put(obj)
        }
        getSharedPreferences("precache_list", MODE_PRIVATE)
            .edit().putString("episodes", arr.toString()).apply()
        writePreCacheLog("savePreCacheList: saved ${episodes.size} episodes")
        Log.d(TAG, "Pre-cache list saved: ${episodes.size} episodes")
    }

    private fun loadPreCacheList(): List<Episode> {
        val prefs = getSharedPreferences("precache_list", MODE_PRIVATE)
        val json = prefs.getString("episodes", "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(json)
            val list = mutableListOf<Episode>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val ep = Episode().apply {
                    id = obj.optString("id", "")
                    title = obj.optString("title", "")
                    audioUrl = obj.optString("audio_url", "")
                    stationName = obj.optString("station_name", "")
                    stationId = obj.optString("station_id", "")
                    duration = obj.optLong("duration", 0)
                    broadcastAt = obj.optString("broadcast_at", "")
                }
                if (ep.audioUrl.isNotBlank()) list.add(ep)
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun loadEpisodeList(): List<Episode> {
        val prefs = getSharedPreferences("episode_list", MODE_PRIVATE)
        val json = prefs.getString("list", null) ?: return emptyList()
        return try {
            val gson = Gson()
            val type = object : TypeToken<List<Episode>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) { emptyList() }
    }

    private fun saveEpisodeList(episodes: List<Episode>) {
        val prefs = getSharedPreferences("episode_list", MODE_PRIVATE)
        val gson = Gson()
        prefs.edit().putString("list", gson.toJson(episodes)).apply()
    }

    private fun findPrevInList(list: List<Episode>, curId: String, settings: AppSettings): Episode? {
        var foundCurrent = false
        for (i in list.indices.reversed()) {
            val ep = list[i]
            if (ep.id == curId) { foundCurrent = true; continue }
            if (foundCurrent && !settings.isDisliked(ep.id) && !settings.isDislikedByTitle(ep.stationId, ep.title)) {
                return ep
            }
        }
        return null
    }

    private fun findNextInList(list: List<Episode>, curId: String, settings: AppSettings): Episode? {
        var foundCurrent = false
        for (ep in list) {
            if (!foundCurrent) {
                if (ep.id == curId) foundCurrent = true
                continue
            }
            if (!settings.isDisliked(ep.id) && !settings.isDislikedByTitle(ep.stationId, ep.title)) {
                return ep
            }
        }
        return null
    }

    fun setPreCacheEpisodeList(episodes: List<Episode>) {
        val arr = org.json.JSONArray()
        for (ep in episodes) {
            val obj = org.json.JSONObject()
            obj.put("id", ep.id ?: "")
            obj.put("title", ep.title ?: "")
            obj.put("audio_url", ep.audioUrl ?: "")
            obj.put("station_name", ep.stationName ?: "")
            obj.put("station_id", ep.stationId ?: "")
            obj.put("duration", ep.duration)
            obj.put("broadcast_at", ep.broadcastAt ?: "")
            arr.put(obj)
        }
        // 保存电台ID和当前日期用于跨天预缓存
        val stationId = episodes.firstOrNull()?.stationId ?: currentEpisode?.stationId ?: ""
        val broadcastAt = episodes.firstOrNull()?.broadcastAt ?: currentEpisode?.broadcastAt ?: ""
        val currentDate = if (broadcastAt.length >= 10) broadcastAt.substring(0, 10) else ""
        
        getSharedPreferences("precache_list", MODE_PRIVATE)
            .edit()
            .putString("episodes", arr.toString())
            .putString("station_id", stationId)
            .putString("current_date", currentDate)
            .putInt("days_fetched", 0)  // 重置跨天计数
            .apply()
        writePreCacheLog("setPreCacheEpisodeList: updated ${episodes.size} episodes, station=$stationId, date=$currentDate")
        Log.d(TAG, "Pre-cache list updated: ${episodes.size} episodes, station=$stationId, date=$currentDate")
        // 立即触发预缓存检查（独立于下载状态）
        writePreCacheLog("setPreCacheEpisodeList: triggering pre-cache check")
        triggerPreCache()
    }

    /**
     * 独立触发预缓存：不依赖当前下载状态，按预缓存个数设置缓存节目
     */
    fun triggerPreCacheIndependently() {
        Log.d(TAG, "Pre-cache: independent trigger")
        triggerPreCache()
    }

    private fun downloadPreCacheEpisode(episode: Episode) {
        val url = episode.audioUrl
        if (url == null || url.isBlank() || !url.startsWith("http")) {
            Log.w(TAG, "Pre-cache: invalid URL for ${episode.title}, skipping: $url")
            isPrecaching = false
            Handler(Looper.getMainLooper()).post { triggerPreCache() }
            return
        }
        val fileName = extractCacheFileName(url)
        val episodesDir = File(cacheDir, "episodes")
        if (!episodesDir.exists()) episodesDir.mkdirs()
        val targetFile = File(episodesDir, fileName)
        if (targetFile.exists() && targetFile.length() > 1024) {
            Log.d(TAG, "Pre-cache: already exists: ${episode.title} ($fileName)")
            // Already cached, release guard and schedule next pre-cache check
            isPrecaching = false
            Handler(Looper.getMainLooper()).post { triggerPreCache() }
            return
        }
        Log.d(TAG, "Pre-cache: downloading ${episode.title} from $url")
        serviceScope.launch {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 120000
                connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
                connection.setRequestProperty("Referer", "https://www.hndt.com/")
                connection.connect()
                if (connection.responseCode != 200) {
                    Log.e(TAG, "Pre-cache download failed: HTTP ${connection.responseCode}")
                    // Download failed, release guard and schedule next pre-cache check
                    isPrecaching = false
                    Handler(Looper.getMainLooper()).post { triggerPreCache() }
                    return@launch
                }
                val input = connection.inputStream
                val output = FileOutputStream(targetFile)
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
                output.close()
                input.close()
                Log.d(TAG, "Pre-cache: downloaded ${targetFile.length()} bytes to ${targetFile.name}")
                // 预缓存下载成功，递增计数，记录文件名
                precacheCompletedCount++
                synchronized(precacheCompletedFileNames) {
                    precacheCompletedFileNames.add(fileName)
                }
                writeServiceLog("notification", "downloadPreCacheEpisode: SUCCESS, file=$fileName, count=$precacheCompletedCount")
                // Save cache file -> episode mapping for dislike filtering
                try {
                    val cacheMappingPrefs = getSharedPreferences("cache_episode_mapping", MODE_PRIVATE)
                    cacheMappingPrefs.edit()
                        .putString(fileName, com.google.gson.Gson().toJson(episode))
                        .apply()
                    Log.d(TAG, "Pre-cache: saved cache_episode_mapping for $fileName")
                } catch (e: Exception) {
                    Log.e(TAG, "Pre-cache: failed to save cache_episode_mapping: ${e.message}")
                }
                // 下载成功后触发PCM预解码
                startPcmPreDecode(episode.id ?: "", targetFile, episode.title ?: "unknown")
                // 同时检查当前播放节目的PCM预解码
                startPcmPreDecodeIfNeeded()
                // Download complete, release guard and schedule next pre-cache check
                isPrecaching = false
                Handler(Looper.getMainLooper()).post { triggerPreCache() }
            } catch (e: Exception) {
                Log.e(TAG, "Pre-cache download error: ${e.message}")
                // 删除不完整的文件
                if (targetFile.exists()) targetFile.delete()
                // Download error, release guard and schedule next pre-cache check
                isPrecaching = false
                Handler(Looper.getMainLooper()).post { triggerPreCache() }
            }
        }
    }

    /**
     * 预缓存下载完成后，后台解码前30分钟音频为PCM并缓存
     * 后续字幕生成时可直接使用预解码的PCM文件，跳过下载和解码阶段
     */
    private fun startPcmPreDecode(episodeId: String, audioFile: File, episodeTitle: String) {
        if (episodeId.isBlank()) {
            writePreCacheLog("startPcmPreDecode: empty episodeId, skipping PCM pre-decode")
            return
        }
        writePreCacheLog("startPcmPreDecode: launching PCM pre-decode for $episodeTitle ($episodeId)")
        serviceScope.launch {
            try {
                val pcmCacheDir = getExternalFilesDir(null)?.let { File(it, "pcm_cache") }
                if (pcmCacheDir == null) {
                    writePreCacheLog("startPcmPreDecode: failed to create pcm_cache dir")
                    return@launch
                }
                if (!pcmCacheDir.exists()) pcmCacheDir.mkdirs()
                val pcmFile = File(pcmCacheDir, "${episodeId}_30min.pcm")
                if (pcmFile.exists() && pcmFile.length() > 1024) {
                    // Validate format - must have .info file with version=3 (original rate, no resampling)
                    val infoFile = File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".info")
                    if (infoFile.exists() && infoFile.readText().contains("version=3")) {
                        writePreCacheLog("startPcmPreDecode: PCM cache already exists for $episodeTitle, skipping")
                        return@launch
                    }
                    writePreCacheLog("startPcmPreDecode: PCM format outdated, regenerating")
                    pcmFile.delete()
                    infoFile.delete()
                    File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".wav").delete()
                } else if (pcmFile.exists()) {
                    pcmFile.delete()
                }
                writePreCacheLog("startPcmPreDecode: decoding ${audioFile.length()} bytes from ${audioFile.name} to PCM...")
                val startTime = System.currentTimeMillis()

                // 获取音频时长
                val durationExtractor = MediaExtractor()
                var audioDurationUs = 0L
                try {
                    durationExtractor.setDataSource(audioFile.absolutePath)
                    for (i in 0 until durationExtractor.trackCount) {
                        val fmt = durationExtractor.getTrackFormat(i)
                        if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                            audioDurationUs = fmt.getLong(MediaFormat.KEY_DURATION)
                            break
                        }
                    }
                } catch (e: Exception) {
                    writePreCacheLog("startPcmPreDecode: warning, failed to get duration: ${e.message}")
                } finally {
                    durationExtractor.release()
                }
                // 只解码前30分钟
                val maxDurationUs = 30 * 60 * 1000000L // 30 minutes
                val effectiveDurationUs = if (audioDurationUs > 0 && audioDurationUs > maxDurationUs) {
                    maxDurationUs
                } else {
                    audioDurationUs
                }
                writePreCacheLog("startPcmPreDecode: audio duration=${audioDurationUs / 1000000}s, effective=${effectiveDurationUs / 1000000}s")

                // 解码到PCM
                decodeToPcmForPreCache(audioFile, pcmFile, effectiveDurationUs)

                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                writePreCacheLog("startPcmPreDecode: PCM pre-decode complete for $episodeTitle, ${pcmFile.length()} bytes in ${elapsed}s")
            } catch (e: Exception) {
                writePreCacheLog("startPcmPreDecode: error for $episodeTitle: ${e.message}")
                Log.e(TAG, "PCM pre-decode failed for $episodeTitle", e)
            }
        }
    }

    /**
     * 检查当前播放节目的音频缓存，若已缓存且预处理开关开启，则触发PCM预解码
     * 从正常播放缓存（startBackgroundDownload）和预缓存下载（downloadPreCacheEpisode）都会调用
     */
    private fun startPcmPreDecodeIfNeeded() {
        val appSettings = AppSettings.getInstance(this)
        if (!appSettings.enablePreprocessing) {
            writePreCacheLog("startPcmPreDecodeIfNeeded: preprocessing disabled, skipping")
            return
        }
        val episode = currentEpisode ?: return
        val episodeId = episode.id
        if (episodeId.isNullOrBlank()) {
            writePreCacheLog("startPcmPreDecodeIfNeeded: no episode id, skipping")
            return
        }
        val url = currentStreamUrl
        if (url.isBlank()) {
            writePreCacheLog("startPcmPreDecodeIfNeeded: no stream URL, skipping")
            return
        }
        val fileName = extractCacheFileName(url)
        val episodesDir = File(cacheDir, "episodes")
        val audioFile = File(episodesDir, fileName)
        if (!audioFile.exists() || audioFile.length() <= 1024) {
            writePreCacheLog("startPcmPreDecodeIfNeeded: audio file not cached yet for ${episode.title} ($fileName)")
            return
        }
        // 检查PCM是否已解码
        val pcmCacheDir = getExternalFilesDir(null)?.let { File(it, "pcm_cache") }
        if (pcmCacheDir != null) {
            val pcmFile = File(pcmCacheDir, "${episodeId}_30min.pcm")
            if (pcmFile.exists() && pcmFile.length() > 1024) {
                // Check if existing PCM needs regeneration (format changed in v2.0.5: original rate, version=3)
                val infoFile = File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".info")
                if (infoFile.exists() && infoFile.readText().contains("version=3")) {
                    writePreCacheLog("startPcmPreDecodeIfNeeded: PCM already exists for ${episode.title}, skipping")
                    return
                }
                writePreCacheLog("startPcmPreDecodeIfNeeded: PCM format outdated, regenerating")
                pcmFile.delete()
                infoFile.delete()
                File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".wav").delete()
            }
        }
        writePreCacheLog("startPcmPreDecodeIfNeeded: triggering PCM pre-decode from normal cache for ${episode.title}")
        startPcmPreDecode(episodeId, audioFile, episode.title ?: "unknown")
    }

    /**
     * 为预缓存解码音频到PCM (16kHz mono 16bit)
     * 使用简单的线性插值重采样，不使用leftover机制，避免chunk边界处理误差导致"低沉缓慢"问题
     */
    private fun decodeToPcmForPreCache(audioFile: File, pcmFile: File, durationUs: Long) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var fos: FileOutputStream? = null
        try {
            extractor.setDataSource(audioFile.absolutePath)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrackIndex = i; audioFormat = format; break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) {
                writePreCacheLog("decodeToPcmForPreCache: no audio track found")
                return
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            // 保持原始采样率和声道数，避免重采样导致音质下降/速度异常
            // Vosk如需16kHz可在Vosk端自行重采样
            val outSampleRate = sampleRate
            val outChannels = channelCount
            writePreCacheLog("decodeToPcmForPreCache: sampleRate=$sampleRate, channels=$channelCount, keeping original (no resampling)")

            fos = FileOutputStream(pcmFile)
            val bufferInfo = MediaCodec.BufferInfo()

            var inputDone = false
            var outputDone = false
            var decodedBytes = 0L
            var resampledBytes = 0L
            val maxPcmBytes = 300_000_000L // ~30min @ 48kHz stereo 16bit
            val decodeStartTime = System.currentTimeMillis()
            val maxDecodeTimeMs = 5 * 60 * 1000L

            while (!outputDone) {
                val now = System.currentTimeMillis()
                if (resampledBytes >= maxPcmBytes) { outputDone = true; break }
                if (now - decodeStartTime > maxDecodeTimeMs) { outputDone = true; break }

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val buffer = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            if (durationUs > 0 && extractor.sampleTime >= durationUs) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 5000)
                when {
                    outIdx >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIdx)!!
                        if (bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val pcmBytes = ByteArray(bufferInfo.size)
                            buffer.position(bufferInfo.offset)
                            buffer.get(pcmBytes)
                            decodedBytes += pcmBytes.size

                            // Convert chunk to shorts
                            val chunkShorts = ShortArray(pcmBytes.size / 2)
                            java.nio.ByteBuffer.wrap(pcmBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(chunkShorts)

                            // Resample chunk (simple, no leftover)
                            val resampled = resampleChunkForPreCache(chunkShorts, sampleRate, channelCount, outSampleRate, outChannels)
                            val outBytes = resampled.first
                            fos.write(outBytes)
                            resampledBytes += outBytes.size
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    else -> {}
                }
            }

            writePreCacheLog("decodeToPcmForPreCache: decoded $decodedBytes raw bytes, resampled to $resampledBytes bytes")
            writePreCacheLog("decodeToPcmForPreCache: PCM file size=${pcmFile.length()} bytes, expected duration=${pcmFile.length() / (outSampleRate * 2 * outChannels)}s")

            // After decode loop, write sample rate info
            val infoFile = File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".info")
            infoFile.writeText("sampleRate=$outSampleRate\nchannels=$outChannels\nversion=3")
            writePreCacheLog("decodeToPcmForPreCache: wrote info file: $infoFile")

            // Issue 10: WAV file generation removed - PCM is consumed directly by Vosk/Whisper
            // Also generate 16kHz mono version for Whisper/Vosk
            try {
                val pcm16kFile = File(pcmFile.parentFile, pcmFile.nameWithoutExtension + "_16k.pcm")
                if (sampleRate != 16000 || channelCount != 1) {
                    writePreCacheLog("decodeToPcmForPreCache: generating 16kHz mono version for Whisper/Vosk")
                    // Stream resample to avoid OOM
                    val inBuf = ShortArray(65536)
                    val pcmInStream = pcmFile.inputStream()
                    val pcmOutStream = pcm16kFile.outputStream()
                    val byteBuf = ByteArray(131072)
                    var remaining = pcmFile.length()
                    try {
                        while (remaining > 0) {
                            val toRead = minOf(byteBuf.size, remaining.toInt())
                            val read = pcmInStream.read(byteBuf, 0, toRead)
                            if (read <= 0) break
                            val shorts = ShortArray(read / 2)
                            java.nio.ByteBuffer.wrap(byteBuf, 0, read).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                            val resampled = resampleChunkForPreCache(shorts, sampleRate, channelCount, 16000, 1)
                            pcmOutStream.write(resampled.first)
                            remaining -= read
                        }
                    } finally {
                        pcmInStream.close()
                        pcmOutStream.close()
                    }
                    val info16kFile = File(pcmFile.parentFile, pcmFile.nameWithoutExtension + "_16k.info")
                    info16kFile.writeText("sampleRate=16000\nchannels=1\nversion=3")
                    writePreCacheLog("decodeToPcmForPreCache: 16kHz mono PCM size=${pcm16kFile.length()}")
                } else {
                    // Already 16kHz mono, just copy
                    pcmFile.copyTo(pcm16kFile, overwrite = true)
                    val info16kFile = File(pcmFile.parentFile, pcmFile.nameWithoutExtension + "_16k.info")
                    info16kFile.writeText("sampleRate=16000\nchannels=1\nversion=3")
                }
            } catch (e: Exception) {
                writePreCacheLog("decodeToPcmForPreCache: failed to generate 16kHz version: ${e.message}")
            }
        } catch (e: Exception) {
            writePreCacheLog("decodeToPcmForPreCache: error: ${e.message}")
            throw e
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            try { fos?.close() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /**
     * 简单线性插值重采样函数（无leftover机制）
     * 输入: 任意采样率/声道的PCM shorts
     * 输出: 目标采样率/声道的PCM字节数组
     *
     * 不使用leftover跨chunk拼接，避免v2.0.0的leftover计算误差导致的"低沉缓慢"问题。
     * chunk边界处的微小不连续对语音内容几乎不可感知。
     */
    private fun resampleChunkForPreCache(input: ShortArray, inSampleRate: Int, inChannels: Int,
                                         outSampleRate: Int, outChannels: Int): Pair<ByteArray, ShortArray?> {
        // 采样率和声道都匹配时，直接转换为字节数组
        if (inSampleRate == outSampleRate && inChannels == outChannels) {
            val bytes = ByteArray(input.size * 2)
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(input)
            return Pair(bytes, null)
        }

        val ratio = inSampleRate.toDouble() / outSampleRate
        val inputFrames = input.size / inChannels

        // Step 1: 转换为单声道（多声道时取平均值，避免只取一个声道导致相位抵消/音量减半）
        val monoInput: ShortArray = if (inChannels > 1) {
            val arr = ShortArray(inputFrames)
            for (i in 0 until inputFrames) {
                var sum = 0
                for (c in 0 until inChannels) {
                    sum += input[i * inChannels + c].toInt()
                }
                arr[i] = (sum / inChannels).toShort()
            }
            arr
        } else {
            input
        }

        // Step 2: 线性插值重采样到目标采样率
        // 需要至少2个输入样本才能做插值
        if (monoInput.size < 2) {
            return Pair(ByteArray(0), null)
        }

        val outFrames = (inputFrames / ratio).toInt()
        if (outFrames <= 0) {
            return Pair(ByteArray(0), null)
        }

        val monoOut = ShortArray(outFrames)
        for (i in 0 until outFrames) {
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx
            if (srcIdx + 1 >= monoInput.size) {
                // 超出范围则使用最后一个样本
                monoOut[i] = monoInput[monoInput.size - 1]
            } else {
                monoOut[i] = ((monoInput[srcIdx] * (1.0 - frac) +
                        monoInput[srcIdx + 1] * frac).toInt()).toShort()
            }
        }

        // Step 3: 如果输出是多声道，复制到所有声道
        val finalShorts: ShortArray = if (outChannels > 1) {
            val arr = ShortArray(outFrames * outChannels)
            for (i in 0 until outFrames) {
                for (c in 0 until outChannels) {
                    arr[i * outChannels + c] = monoOut[i]
                }
            }
            arr
        } else {
            monoOut
        }

        val outBytes = ByteArray(finalShorts.size * 2)
        java.nio.ByteBuffer.wrap(outBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(finalShorts)
        return Pair(outBytes, null)  // 不使用leftover
    }

    private fun applySavedPosition() {
        if (!savePlaybackPosition && pendingStartPosition < 0) return
        val ep = currentEpisode ?: return
        val savedPos = if (pendingStartPosition >= 0) {
            val pos = pendingStartPosition
            pendingStartPosition = -1L
            pos
        } else {
            val episodeKey = "${ep.stationId}::${ep.title}"
            getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(episodeKey, -1L)
        }
        if (savedPos > 0 && !isLive) {
            isSeekingToPosition = true
            lastPositionRestoreTime = System.currentTimeMillis()
            player?.playWhenReady = false  // Keep paused until seek completes
            player?.seekTo(savedPos)
            Log.d(TAG, "Restored position: ${savedPos}ms, waiting for seek completion before play, will not save position for 30s")
            // Safety fallback: if onPositionDiscontinuity never fires, clear flag after 30 seconds
            positionSaveHandler?.postDelayed({
                isSeekingToPosition = false
                Log.d(TAG, "Position restore grace period ended, position saving enabled")
            }, 30000)
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
        if (isSeekingToPosition || pendingStartPosition >= 0) return
        if (System.currentTimeMillis() - lastPositionRestoreTime < 30000) return  // Don't save for 30s after restore
        val ep = currentEpisode ?: return
        val pos = player?.currentPosition ?: return
        if (pos <= 0) return
        val episodeKey = "${ep.stationId}::${ep.title}"
        getSharedPreferences("playback_positions", MODE_PRIVATE)
            .edit().putLong(episodeKey, pos)
            .putLong(ep.id ?: "", pos).apply()
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
            if (!isLive && prepared && player != null && !isPrecaching) {
                // 阈值判断与 lastNotifiedPosition 维护已下沉到 updateNotificationProgressOnly()，
                // 这里直接调用即可，避免在此处提前更新 lastNotifiedPosition 导致内部 2 秒阈值失效。
                updateNotificationProgressOnly()
            }
            // 进度条不需要每 2 秒更新，改为每 5 秒一次，降低通知刷新频率
            notificationHandler?.postDelayed(notificationRunnable!!, 5000)
        }
        notificationRunnable?.let { notificationHandler?.post(it) }
    }

    /**
     * 构建通知栏副文本：将 notificationSubText 与日期/时间段信息拼接。
     * 日期格式化为 "2024-06-04T07:00:00" -> "06-04"，统一供 updateNotification()、
     * updateNotificationProgressOnly()、buildMediaStyleNotification() 使用，避免重复代码。
     */
    private fun buildNotificationSubText(): String {
        val dateTimeInfo = buildString {
            if (notificationDate.isNotBlank()) {
                // Format date: "2024-06-04T07:00:00" -> "06-04"
                if (notificationDate.length >= 10) {
                    append(notificationDate.substring(5, 10))
                }
            }
            if (notificationTimeRange.isNotBlank()) {
                if (isNotEmpty()) append(" ")
                append(notificationTimeRange)
            }
        }
        return if (dateTimeInfo.isNotBlank()) "$notificationSubText  $dateTimeInfo" else notificationSubText
    }

    private fun updateNotification(): Notification {
        writeServiceLog("notification", "updateNotification: title=$notificationTitle, subText=$notificationSubText, date=$notificationDate, timeRange=$notificationTimeRange, playing=${player?.isPlaying}, userPaused=$userPaused, prepared=$prepared, contentHash=$lastNotificationContentHash")
        // 每次更新时重新加载通知栏样式，确保设置更改即时生效
        reloadNotificationStyle()
        // Use userPaused instead of player?.isPlaying to avoid notification flicker during buffering.
        // When ExoPlayer buffers/re-buffers, isPlaying briefly becomes false even though the user didn't pause.
        val playing = playbackStarted && !userPaused

        // Build informative subtitle with date and time
        val fullSubText = buildNotificationSubText()

        val deleteIntent = PendingIntent.getService(this, 99,
            Intent(this, RadioPlaybackService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // 紧凑模式：使用系统标准MediaStyle通知栏，支持seekbar和拖动手势
        if (notificationStyle == "compact") {
            return buildMediaStyleNotification(playing, deleteIntent)
        }

        // 其他模式：使用自定义RemoteViews布局
        val remoteViews = when (notificationStyle) {
            "minimal" -> RemoteViews(packageName, R.layout.notification_minimal)
            else -> RemoteViews(packageName, R.layout.notification_custom)
        }
        applyNotificationIntents(remoteViews)

        remoteViews.setImageViewResource(R.id.play_pause_icon,
            if (playing) R.drawable.notif_pause else R.drawable.notif_play)
        remoteViews.setTextViewText(R.id.play_pause_text, if (playing) "暂停" else "播放")

        // 动态更新快进快退按钮文字
        try { remoteViews.setTextViewText(R.id.rewind_text, "-${skipSeconds}s") } catch (_: Exception) {}
        try { remoteViews.setTextViewText(R.id.forward_text, "+${skipSeconds}s") } catch (_: Exception) {}

        remoteViews.setTextViewText(R.id.notification_title, notificationTitle)
        remoteViews.setTextViewText(R.id.notification_subtitle,
            if (playing) "正在播放 $fullSubText" else "已暂停 $fullSubText")

        if (!isLive && prepared) {
            remoteViews.setViewVisibility(R.id.notification_progress_area, android.view.View.VISIBLE)
            val p = player ?: return buildNotification()
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
        if (notificationStyle == "full") {
            applySeekIntents(remoteViews)
        }

        val dateStr = if (notificationDate.length >= 10) notificationDate.substring(5, 10) else ""
        val timeStr = notificationTimeRange
        val contentText = buildString {
            append(if (playing) "正在播放" else "已暂停")
            if (dateStr.isNotBlank()) {
                append(" · $dateStr")
            }
            if (timeStr.isNotBlank()) {
                append(" · $timeStr")
            }
        }
        // Issue 4: Set date/time on RemoteViews (contentText is hidden when custom layout is used)
        writeNotifDetailLog("updateNotification: BEFORE setTextViewText - notificationTitle='$notificationTitle', notificationDate='$notificationDate', notificationTimeRange='$notificationTimeRange', contentText='$contentText', notificationStyle='$notificationStyle'")
        remoteViews.setTextViewText(R.id.notification_subtitle, contentText)
        writeNotifDetailLog("updateNotification: AFTER setTextViewText - remoteViews.setTextViewText(notification_subtitle, '$contentText')")
        val builder = NotificationCompat.Builder(this, RadioApplication.CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(contentText)
            .setSubText(buildNotificationSubText())
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(createContentIntent())
            .setOngoing(true)
            .setAutoCancel(false)
            .setDeleteIntent(deleteIntent)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCustomContentView(remoteViews)

        if (notificationStyle == "full") {
            builder.setCustomBigContentView(remoteViews)
        }

        val notification: Notification = builder.build()
        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT

        return notification
    }

    /**
     * 通知栏更新：调用 manager.notify() 推送通知
     * 从 updateNotification() 分离出来，避免在 startForeground 前被进度轮询覆盖
     */
    private fun notifyNotification() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastNotificationTime

        // If handler is not available, just update directly（handler 为 null 时直接更新，避免防抖失效）
        if (notificationHandler == null) {
            lastNotificationTime = now
            doNotifyNotification()
            return
        }

        // Cancel any pending delayed notification
        pendingNotificationRunnable?.let { notificationHandler!!.removeCallbacks(it) }

        if (elapsed < 500) {
            // Debounce: schedule a delayed update
            pendingNotificationRunnable = Runnable {
                lastNotificationTime = System.currentTimeMillis()
                doNotifyNotification()
            }
            notificationHandler!!.postDelayed(pendingNotificationRunnable!!, 500 - elapsed)
            return
        }

        lastNotificationTime = now
        doNotifyNotification()
    }

    private fun doNotifyNotification() {
        val notification = updateNotification()

        // Content-based deduplication: skip if content hasn't changed
        val contentHash = Objects.hash(
            notificationTitle,
            notificationDate,
            notificationTimeRange,
            buildNotificationSubText(),
            playbackStarted && !userPaused,
            prepared,
            if (!isLive && prepared) player?.currentPosition?.div(2000) else 0L  // 2-second granularity
        )
        if (contentHash == lastNotificationContentHash) {
            return  // Skip identical notification update
        }
        lastNotificationContentHash = contentHash

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        try {
            writeServiceLog("notification", "doNotifyNotification: manager.notify(NOTIFICATION_ID=$NOTIFICATION_ID) contentHash=$contentHash")
            writeNotifDetailLog("doNotifyNotification: BEFORE manager.notify - notificationId=$NOTIFICATION_ID, contentHash=$contentHash, notificationTitle='$notificationTitle', notificationDate='$notificationDate', notificationTimeRange='$notificationTimeRange'")
            manager.notify(NOTIFICATION_ID, notification)
            writeNotifDetailLog("doNotifyNotification: AFTER manager.notify - success")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    /**
     * 切换节目失败时展示一次性反馈通知（3秒后自动取消）
     */
    private fun showEpisodeSwitchFailedNotification(reason: String) {
        try {
            val notification = NotificationCompat.Builder(this, RadioApplication.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("切换节目")
                .setContentText(reason)
                .setAutoCancel(true)
                .setOngoing(false)
                .build()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(1002, notification)
            // Auto-cancel after 3 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                manager.cancel(1002)
            }, 3000)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show switch failed notification", e)
        }
    }

    /**
     * 仅更新通知栏进度条和时间，不重建整个通知，避免每秒调用 updateNotification() 的开销。
     * 仅对 minimal 样式生效，其他样式跳过以避免闪烁。
     *
     * 重要修复：此函数此前直接调用 manager.notify()，绕过了 notifyNotification() 的防抖，
     * 导致通知每 200ms 刷新一次。现在：
     * 1. 仅在播放位置变化超过 2 秒时才更新；
     * 2. 仅 minimal 样式进行进度条独立更新，compact/full 样式完全跳过（避免重建通知闪烁）。
     */
    private fun updateNotificationProgressOnly() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration
        if (dur <= 0) return

        // 仅在播放位置变化超过 2 秒时才更新，避免高频调用 manager.notify() 造成通知刷屏
        if (kotlin.math.abs(pos - lastNotifiedPosition) < 2000) return
        lastNotifiedPosition = pos

        // 仅 minimal 样式进行进度条独立更新；其他样式跳过以避免重建通知带来的闪烁
        if (notificationStyle != "minimal") return

        try {
            val rv = RemoteViews(packageName, R.layout.notification_minimal)
            rv.setProgressBar(R.id.notification_progress, 1000, ((pos * 1000) / dur).toInt().coerceIn(0, 1000), false)
            rv.setTextViewText(R.id.notification_time_text, "${formatTimeNotif(pos.toInt() / 1000)}/${formatTimeNotif(dur.toInt() / 1000)}")
            applyNotificationIntents(rv)
            applyThemeToNotification(rv)
            val playing = playbackStarted && !userPaused
            rv.setImageViewResource(R.id.play_pause_icon,
                if (playing) R.drawable.notif_pause else R.drawable.notif_play)
            rv.setTextViewText(R.id.play_pause_text, if (playing) "暂停" else "播放")
            rv.setTextViewText(R.id.notification_title, notificationTitle)
            // Issue 4: Set date/time on RemoteViews (contentText is hidden when custom layout is used)
            val dateStr = if (notificationDate.length >= 10) notificationDate.substring(5, 10) else ""
            val timeStr = notificationTimeRange
            val contentText = buildString {
                append(if (playing) "正在播放" else "已暂停")
                if (dateStr.isNotBlank()) append(" · $dateStr")
                if (timeStr.isNotBlank()) append(" · $timeStr")
            }
            rv.setTextViewText(R.id.notification_subtitle, contentText)
            writeNotifDetailLog("updateNotificationProgressOnly: rv.setTextViewText(notification_subtitle, '$contentText'), notificationTitle='$notificationTitle', notificationDate='$notificationDate', notificationTimeRange='$notificationTimeRange'")
            val builder = NotificationCompat.Builder(this, RadioApplication.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setCustomContentView(rv)
                .setOngoing(true)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            writeServiceLog("notification", "updateNotificationProgressOnly: manager.notify (minimal), pos=$pos")
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (_: Exception) {}
    }

    /**
     * 构建系统标准MediaStyle通知栏（五按钮，支持seekbar和拖动手势）
     */
    private fun buildMediaStyleNotification(playing: Boolean, deleteIntent: PendingIntent): Notification {
        val dateStr = if (notificationDate.length >= 10) notificationDate.substring(5, 10) else ""
        val timeStr = notificationTimeRange
        val contentText = buildString {
            append(if (playing) "正在播放" else "已暂停")
            if (dateStr.isNotBlank()) {
                append(" · $dateStr")
            }
            if (timeStr.isNotBlank()) {
                append(" · $timeStr")
            }
        }

        // 创建展开视图（含进度条和50点seek）
        val expandedView = RemoteViews(packageName, R.layout.notification_media_expanded)
        expandedView.setTextViewText(R.id.notification_title, notificationTitle)
        expandedView.setTextViewText(R.id.notification_subtitle, contentText)

        if (!isLive && prepared) {
            expandedView.setViewVisibility(R.id.notification_progress_area, android.view.View.VISIBLE)
            val p = player
            if (p != null) {
                val pos = p.currentPosition
                val dur = p.duration
                if (dur > 0) {
                    val progress = ((pos * 1000) / dur).toInt().coerceIn(0, 1000)
                    expandedView.setProgressBar(R.id.notification_progress, 1000, progress, false)
                    val totalSec = dur.toInt() / 1000
                    val curSec = pos.toInt() / 1000
                    expandedView.setTextViewText(R.id.notification_time_text,
                        "${formatTimeNotif(curSec)}/${formatTimeNotif(totalSec)}")
                }
            }
            applySeekIntents(expandedView)
        } else {
            expandedView.setViewVisibility(R.id.notification_progress_area, android.view.View.GONE)
        }

        val builder = NotificationCompat.Builder(this, RadioApplication.CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(contentText)
            .setSubText(buildNotificationSubText())
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(createContentIntent())
            .setOngoing(true)
            .setAutoCancel(false)
            .setDeleteIntent(deleteIntent)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 2, 4)
                .setMediaSession(mediaSession?.sessionToken))
            // 5个按钮：后退 | 上一节目 | 播放/暂停 | 下一节目 | 前进
            .addAction(createNotificationAction(ACTION_REWIND, R.drawable.notif_rewind, "后退${skipSeconds}s", 10))
            .addAction(createNotificationAction(ACTION_PREV_EPISODE, R.drawable.notif_prev, "上一节目", 11))
            .addAction(createNotificationAction(
                if (playing) ACTION_PAUSE else ACTION_PLAY,
                if (playing) R.drawable.notif_pause else R.drawable.notif_play,
                if (playing) "暂停" else "播放", 12))
            .addAction(createNotificationAction(ACTION_NEXT_EPISODE, R.drawable.notif_next, "下一节目", 13))
            .addAction(createNotificationAction(ACTION_FORWARD, R.drawable.notif_forward, "前进${skipSeconds}s", 14))
            // 展开状态使用自定义布局（含进度条和seek区）
            .setCustomBigContentView(expandedView)

        val notification: Notification = builder.build()
        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT

        return notification
    }

    private fun createNotificationAction(action: String, iconRes: Int, title: String, requestCode: Int): NotificationCompat.Action {
        val intent = Intent(this, RadioPlaybackService::class.java).apply {
            this.action = action
        }
        val pi = PendingIntent.getService(this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(iconRes, title, pi).build()
    }

    private fun reloadNotificationStyle() {
        val prefs = getSharedPreferences("radio_app_settings", Context.MODE_PRIVATE)
        notificationStyle = prefs.getString("notification_style", "full") ?: "full"
    }

    private fun buildNotification(): Notification {
        // 紧凑模式使用MediaStyle
        if (notificationStyle == "compact") {
            return NotificationCompat.Builder(this, RadioApplication.CHANNEL_ID)
                .setContentTitle("Radio App")
                .setContentText("准备播放")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 2, 4)
                    .setMediaSession(mediaSession?.sessionToken))
                .addAction(createNotificationAction(ACTION_REWIND, R.drawable.notif_rewind, "后退${skipSeconds}s", 10))
                .addAction(createNotificationAction(ACTION_PREV_EPISODE, R.drawable.notif_prev, "上一节目", 11))
                .addAction(createNotificationAction(ACTION_PLAY, R.drawable.notif_play, "播放", 12))
                .addAction(createNotificationAction(ACTION_NEXT_EPISODE, R.drawable.notif_next, "下一节目", 13))
                .addAction(createNotificationAction(ACTION_FORWARD, R.drawable.notif_forward, "前进${skipSeconds}s", 14))
                .build()
        }
        val remoteViews = when (notificationStyle) {
            "minimal" -> RemoteViews(packageName, R.layout.notification_minimal)
            else -> RemoteViews(packageName, R.layout.notification_custom)
        }
        applyNotificationIntents(remoteViews)
        if (notificationStyle == "full") {
            applySeekIntents(remoteViews)
        }
        applyThemeToNotification(remoteViews)
        // Issue 4: Set title and date/time on RemoteViews (contentText is hidden when custom layout is used)
        remoteViews.setTextViewText(R.id.notification_title, notificationTitle)
        val dateStr = if (notificationDate.length >= 10) notificationDate.substring(5, 10) else ""
        val timeStr = notificationTimeRange
        val playing = playbackStarted && !userPaused
        val contentText = buildString {
            append(if (playing) "正在播放" else "已暂停")
            if (dateStr.isNotBlank()) append(" · $dateStr")
            if (timeStr.isNotBlank()) append(" · $timeStr")
        }
        remoteViews.setTextViewText(R.id.notification_subtitle, contentText)
        return NotificationCompat.Builder(this, RadioApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCustomContentView(remoteViews)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
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
        // 从 radio_app_settings 读取（与 AppSettings.save() 和热切换监听器使用同一文件）
        val appPrefs = getSharedPreferences("radio_app_settings", Context.MODE_PRIVATE)
        notificationStyle = appPrefs.getString("notification_style", "full") ?: "full"
        skipSeconds = appPrefs.getInt("skip_seconds", 15)
        Log.d(TAG, "loadSettings: raw skip_seconds from prefs = ${appPrefs.getInt("skip_seconds", -1)}")
        // 强制迁移：旧版本残留值5必须覆盖为15
        if (skipSeconds == 5) {
            skipSeconds = 15
            appPrefs.edit().putInt("skip_seconds", 15).apply()
            // 同时清理所有可能的旧pref文件
            try {
                getSharedPreferences("radio_app_prefs", Context.MODE_PRIVATE)
                    .edit().putInt("skip_seconds", 15).apply()
            } catch (_: Exception) {}
            Log.w(TAG, "loadSettings: FORCED migration skip_seconds 5→15")
        }
        Log.d(TAG, "loadSettings: final skipSeconds=$skipSeconds, notificationStyle=$notificationStyle")
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
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 保存当前播放位置，防止服务被意外杀死后位置丢失
        saveCurrentPosition()
        // 用户从最近任务划掉应用时，确保服务不被杀死
        // 如果正在播放，重新启动前台通知
        if (playbackStarted || player?.isPlaying == true) {
            Log.d(TAG, "onTaskRemoved: playback is active, keeping service alive")
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun seekToPercent(pct: Float) {
        if (isLive || !prepared) return
        val dur = player?.duration ?: 0L
        if (dur <= 0) return
        player?.seekTo((dur * pct).toLong())
        notifyNotification()
    }

    private fun seekToSec(sec: Int) {
        if (isLive || !prepared) return
        player?.seekTo((sec * 1000L).coerceAtLeast(0))
        notifyNotification()
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
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.volume = 1.0f
                play()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Pause for navigation announcements and other transient focus changes
                pause()
            }
        }
    }

    fun playStation(station: RadioStation) {
        Log.d(TAG, "playStation called: ${station.name}, playbackStarted before: $playbackStarted, url=${station.streamUrl}")
        currentStation = station; currentEpisode = null; isLive = true
        prepared = false; currentStreamUrl = station.streamUrl ?: ""
        currentPlayingUrl = currentStreamUrl
        playbackStarted = true
        userPaused = false // Starting new playback, not user-paused
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
            notificationDate = ""; notificationTimeRange = ""; notificationPlaying = true
            requestAudioFocus(); notifyNotification()
            updateMediaSessionState()
        } catch (e: Exception) { Log.e(TAG, "playStation failed", e) }
    }

    fun playEpisode(episode: Episode, live: Boolean, startPositionMs: Long = -1L) {
        Log.d(TAG, "playEpisode called: ${episode.title}, playbackStarted before: $playbackStarted, prepared=$prepared, url=${episode.audioUrl}, startPositionMs=$startPositionMs")
        currentEpisode = episode; currentStation = null; isLive = live
        prepared = false; errorRetryCount = 0; isRetrying = false
        userPaused = false // Starting new playback, not user-paused
        stopAutoSkipCheck()
        if (startPositionMs >= 0) {
            // 位置恢复通过 prepare 前 seek 完成
            positionRestoreRequested = true  // 标记，STATE_READY 时清除 pendingStartPosition
            pendingStartPosition = startPositionMs
        } else {
            pendingStartPosition = -1L
            positionRestoreRequested = false
        }
        downloadProgressPct = 0; downloadDoneBytes = 0; downloadTotalBytes = 0
        currentStreamUrl = episode.audioUrl ?: ""
        currentPlayingUrl = currentStreamUrl
        playbackStarted = true
        playbackInitializing = true
        notificationTitle = episode.title; notificationSubText = "[回放]"
        notificationPlaying = true
        // Parse broadcastAt for date and time range
        notificationDate = ""
        notificationTimeRange = ""
        lastNotificationContentHash = 0  // Force notification update on episode change
        precacheNotificationShown = false  // Reset for new episode
        lastPreCacheCheckTime = 0  // Allow immediate pre-cache check for new episode
        if (episode.broadcastAt != null && episode.broadcastAt.length >= 16) {
            notificationDate = episode.broadcastAt.substring(0, 10) // "2024-06-04"
            val timePart = episode.broadcastAt.substring(11, 16) // "07:00"
            val durationMin = episode.duration / 60
            if (durationMin > 0) {
                val startHour = timePart.substring(0, 2).toIntOrNull() ?: 0
                val startMin = timePart.substring(3, 5).toIntOrNull() ?: 0
                val totalMin = startHour * 60 + startMin + durationMin
                val endHour = (totalMin / 60) % 24
                val endMin = totalMin % 60
                notificationTimeRange = "${timePart}-${String.format("%02d:%02d", endHour, endMin)}"
            } else {
                notificationTimeRange = timePart
            }
        }
        // Fallback: parse date/time from URL (e.g., sijiache_20240604_0700_0900.mp4)
        if (notificationDate.isBlank() || notificationDate.length >= 10) {
            val url = episode.audioUrl ?: ""
            val dateMatch = Regex("(\\d{4})(\\d{2})(\\d{2})").find(url)
            if (dateMatch != null) {
                notificationDate = "${dateMatch.groupValues[1]}-${dateMatch.groupValues[2]}-${dateMatch.groupValues[3]}"
            }
        }
        if (notificationTimeRange.isBlank()) {
            val url = episode.audioUrl ?: ""
            val timeMatch = Regex("_(\\d{2})(\\d{2})_(\\d{2})(\\d{2})").find(url)
            if (timeMatch != null) {
                notificationTimeRange = "${timeMatch.groupValues[1]}:${timeMatch.groupValues[2]}-${timeMatch.groupValues[3]}:${timeMatch.groupValues[4]}"
            }
        }
        writeServiceLog("notification", "playEpisode: URL fallback date/time - date='$notificationDate', timeRange='$notificationTimeRange', url='${episode.audioUrl}'")
        writeServiceLog("notification", "playEpisode: final date/time - date='$notificationDate', timeRange='$notificationTimeRange'")
        writeNotifDetailLog("playEpisode: SET notificationTitle='$notificationTitle', notificationDate='$notificationDate', notificationTimeRange='$notificationTimeRange', episode=${episode.title}")
        val notification = updateNotification()
        // 立即推送前台通知，确保通知栏立即更新，不被进度轮询覆盖
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        writeServiceLog("notification", "playEpisode: title=${episode.title}, notificationTitle=$notificationTitle, notificationSubText=$notificationSubText, notificationDate=$notificationDate, updateNotification called")
        // Broadcast to notify UI that episode changed
        try {
            val broadcastIntent = Intent(BROADCAST_EPISODE_CHANGED)
            broadcastIntent.putExtra("episode_title", episode.title)
            broadcastIntent.putExtra("episode_id", episode.id)
            broadcastIntent.setPackage(packageName)
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
        } catch (e: Exception) { Log.w(TAG, "Failed to broadcast episode change", e) }
        saveLastEpisode(episode)
        ensurePlayerInitialized()
        try {
            player?.let {
                it.setMediaItem(MediaItem.fromUri(currentStreamUrl))
                if (startPositionMs >= 0) {
                    it.playWhenReady = false  // Don't start playing until seek completes
                } else {
                    it.playWhenReady = true
                }
                it.prepare()
            }
            requestAudioFocus()
            updateMediaSessionState()
            startAutoSkipCheck()
        } catch (e: Exception) { Log.e(TAG, "playEpisode failed", e) }
        // Force immediate notification update
        lastNotificationContentHash = 0
        notifyNotification()
    }

    fun play() { userPaused = false; player?.play() }
    fun pause() { userPaused = true; player?.pause() }
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
    fun getCurrentPlayingUrl(): String = currentPlayingUrl
    fun isPlaybackStarted(): Boolean = playbackStarted
    fun isPlaybackInitializing(): Boolean = playbackInitializing
    fun isSameEpisodePlaying(url: String): Boolean {
        return playbackStarted && currentPlayingUrl.isNotBlank() && currentPlayingUrl == url
    }
    fun getCurrentPosition(): Long = player?.currentPosition ?: 0L
    fun getDuration(): Long { val dur = player?.duration ?: 0L; return if (dur < 0) -1L else dur }
    fun getBufferedPercentage(): Int = player?.bufferedPercentage ?: 0
    fun getBufferedPosition(): Long = player?.bufferedPosition ?: 0L
    fun getCurrentEpisode(): Episode? = currentEpisode
    fun getCurrentStation(): RadioStation? = currentStation

    /**
     * 根据节目信息，从 SharedPreferences 中获取已保存的播放位置（毫秒）
     * 用于 Activity 重建时恢复播放进度，避免从 0 开始播放导致位置抖动
     */
    fun getSavedPositionForEpisode(episode: Episode): Long {
        val episodeKey = "${episode.stationId}::${episode.title}"
        return getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(episodeKey, -1L)
    }

    fun setCallback(cb: Callback?) { callback = cb }

    /** 获取后台下载进度（0-100），用于缓存进度显示 */
    fun getDownloadProgress(): Int = downloadProgressPct
    fun getDownloadTotalBytes(): Long = downloadTotalBytes
    fun getDownloadDoneBytes(): Long = downloadDoneBytes
    fun isDownloading(): Boolean = downloadActive.get()

    private fun getSegmentList(): List<VoiceSegment> {
        val segments = currentEpisode?.voiceSegments
        if (segments != null && segments.isNotEmpty()) return segments
        // Generate simulated 15-minute segments
        val dur = player?.duration ?: 0L
        if (dur <= 0) return emptyList()
        val segmentSize = 15 * 60 * 1000L  // 15 minutes
        val result = mutableListOf<VoiceSegment>()
        var start = 0L
        while (start < dur) {
            val end = minOf(start + segmentSize, dur)
            result.add(VoiceSegment(start = start, end = end, hasVoice = true, isSimulated = true))
            start = end
        }
        return result
    }

    fun jumpToNextSegment() {
        val segments = getSegmentList()
        if (segments.isNotEmpty()) {
            val currentPos = player?.currentPosition ?: 0L
            for (seg in segments) { if (seg.start > currentPos) { seekTo(seg.start); return } }
        }
        // Fallback: skip forward 30 seconds
        val pos = (player?.currentPosition ?: 0L) + 30000
        val dur = player?.duration ?: 0L
        if (dur > 0 && pos < dur) seekTo(pos)
    }

    private fun fetchCrossDayEpisode(nextDate: Boolean): Episode? {
        writeServiceLog("notification", "fetchCrossDayEpisode: START, nextDate=$nextDate, currentPlayingUrl=$currentPlayingUrl, currentEpisode=${currentEpisode?.title}")
        val curEp = currentEpisode
        val curUrl = currentPlayingUrl

        // Extract station and date from URL or episode
        val stationId: String
        val currentDate: String
        if (curEp != null) {
            stationId = curEp.stationId
            currentDate = curEp.broadcastAt?.take(10) ?: run {
                writeServiceLog("notification", "fetchCrossDayEpisode: RETURN null - currentEpisode.broadcastAt is null or too short")
                return null
            }
        } else if (curUrl.isNotBlank()) {
            // Extract from URL: e.g., sijiache_20240604_0700_0900.mp4
            val urlParts = curUrl.substringAfterLast("/").split("_")
            if (urlParts.size < 2) {
                writeServiceLog("notification", "fetchCrossDayEpisode: RETURN null - urlParts.size=${urlParts.size} < 2, curUrl=$curUrl")
                return null
            }
            stationId = urlParts[0] // "sijiache"
            val datePart = urlParts.getOrNull(1) ?: run {
                writeServiceLog("notification", "fetchCrossDayEpisode: RETURN null - datePart is null, urlParts=$urlParts")
                return null
            }
            if (datePart.length < 8) {
                writeServiceLog("notification", "fetchCrossDayEpisode: RETURN null - datePart.length=${datePart.length} < 8, datePart=$datePart")
                return null
            }
            currentDate = "${datePart.substring(0, 4)}-${datePart.substring(4, 6)}-${datePart.substring(6, 8)}"
        } else {
            writeServiceLog("notification", "fetchCrossDayEpisode: RETURN null - both currentEpisode and currentPlayingUrl are empty")
            return null
        }

        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        dateFormat.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")

        try {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
            cal.time = dateFormat.parse(currentDate) ?: run {
                writeServiceLog("notification", "fetchCrossDayEpisode: RETURN null - failed to parse currentDate=$currentDate")
                return null
            }
            cal.add(java.util.Calendar.DAY_OF_YEAR, if (nextDate) 1 else -1)
            val targetDate = dateFormat.format(cal.time)

            writeServiceLog("notification", "fetchCrossDayEpisode: fetching $stationId on $targetDate (nextDate=$nextDate), currentDate=$currentDate")
            Log.d(TAG, "fetchCrossDayEpisode: fetching $stationId on $targetDate (nextDate=$nextDate)")

            // Try network fetch first
            val apiService = com.radio.app.network.EpisodeApiService.getInstance()
            val episodes = apiService.fetchEpisodesByDateSync(stationId, targetDate)
            writeServiceLog("notification", "fetchCrossDayEpisode: network fetch returned episodes=${episodes?.size ?: "null"} for $stationId on $targetDate")

            if (episodes != null && episodes.isNotEmpty()) {
                val settings = AppSettings.getInstance(this)
                val result = if (nextDate) {
                    episodes.firstOrNull { !settings.isDisliked(it.id) && !settings.isDislikedByTitle(it.stationId, it.title) }
                } else {
                    episodes.lastOrNull { !settings.isDisliked(it.id) && !settings.isDislikedByTitle(it.stationId, it.title) }
                }
                if (result != null) {
                    writeServiceLog("notification", "fetchCrossDayEpisode: RETURN result from network - title=${result.title}, id=${result.id}, broadcastAt=${result.broadcastAt}")
                    Log.d(TAG, "fetchCrossDayEpisode: found ${result.title} from network")
                    // Save to episode list for future use
                    saveEpisodeList(episodes)
                    return result
                } else {
                    writeServiceLog("notification", "fetchCrossDayEpisode: network episodes found but all disliked/filtered (count=${episodes.size})")
                }
            }

            // Fallback: try saved episode list
            writeServiceLog("notification", "fetchCrossDayEpisode: network fetch failed or no result, trying saved list")
            Log.d(TAG, "fetchCrossDayEpisode: network fetch failed, trying saved list")
            val savedList = loadEpisodeList()
            val targetEpisodes = savedList.filter { it.broadcastAt?.startsWith(targetDate) == true && it.stationId == stationId }
            writeServiceLog("notification", "fetchCrossDayEpisode: saved list filtered - savedListSize=${savedList.size}, targetEpisodesSize=${targetEpisodes.size} for $stationId on $targetDate")
            if (targetEpisodes.isNotEmpty()) {
                val settings = AppSettings.getInstance(this)
                val result = if (nextDate) {
                    targetEpisodes.firstOrNull { !settings.isDisliked(it.id) && !settings.isDislikedByTitle(it.stationId, it.title) }
                } else {
                    targetEpisodes.lastOrNull { !settings.isDisliked(it.id) && !settings.isDislikedByTitle(it.stationId, it.title) }
                }
                if (result != null) {
                    writeServiceLog("notification", "fetchCrossDayEpisode: RETURN result from saved list - title=${result.title}, id=${result.id}, broadcastAt=${result.broadcastAt}")
                    Log.d(TAG, "fetchCrossDayEpisode: found ${result.title} from saved list")
                    return result
                } else {
                    writeServiceLog("notification", "fetchCrossDayEpisode: saved list target episodes found but all disliked/filtered (count=${targetEpisodes.size})")
                }
            }

            // Final fallback: construct URL directly from pattern
            writeServiceLog("notification", "fetchCrossDayEpisode: network and saved list both failed, trying URL construction")
            val curUrl = currentPlayingUrl ?: currentEpisode?.audioUrl ?: ""
            if (curUrl.isNotBlank()) {
                // Extract date from URL: sijiache_20240604_0700_0900.mp4
                val dateMatch = Regex("(\\d{4})(\\d{2})(\\d{2})").find(curUrl)
                if (dateMatch != null) {
                    val newDateStr = targetDate.replace("-", "") // "20240605"

                    // Issue 2: Cross-day just gets the next day's first/last episode - NO time slot filtering
                    val episodeList = loadEpisodeList()
                    val targetTimeSlot = if (nextDate) {
                        // Going forward: use first episode's time slot (whatever it is)
                        val firstEp = episodeList.firstOrNull()
                        firstEp?.audioUrl?.let { url ->
                            val parts = url.substringAfterLast("/").substringBefore(".").split("_")
                            if (parts.size >= 4) "${parts[2]}_${parts[3]}" else "0700_0900"
                        } ?: "0700_0900"
                    } else {
                        // Going backward: use last episode's time slot (whatever it is)
                        val lastEp = episodeList.lastOrNull()
                        lastEp?.audioUrl?.let { url ->
                            val parts = url.substringAfterLast("/").substringBefore(".").split("_")
                            if (parts.size >= 4) "${parts[2]}_${parts[3]}" else "1700_1900"
                        } ?: "1700_1900"
                    }
                    writeServiceLog("notification", "fetchCrossDayEpisode: targetTimeSlot=$targetTimeSlot (nextDate=$nextDate)")

                    // Construct new URL with target time slot
                    val stationPart = curUrl.substringAfterLast("/").substringBefore("_")
                    val pathPrefix = curUrl.substringBeforeLast("/").substringBeforeLast("/")
                    val newUrl = "$pathPrefix/jmd_$newDateStr/${stationPart}_${newDateStr}_$targetTimeSlot.mp4"

                    writeServiceLog("notification", "fetchCrossDayEpisode: constructed URL: $newUrl")

                    // Find matching title from episode list by time slot
                    val targetStart = targetTimeSlot.substringBefore("_")
                    val targetEnd = targetTimeSlot.substringAfter("_")
                    val matchEpisode = episodeList.firstOrNull { ep ->
                        val epUrl = ep.audioUrl ?: ""
                        epUrl.contains("_${targetStart}_") && epUrl.contains("_${targetEnd}.")
                    }

                    val dateDisplay = if (newDateStr.length >= 8) "${newDateStr.substring(4, 6)}-${newDateStr.substring(6, 8)}" else ""
                    val constructedTitle = if (matchEpisode != null) {
                        if (dateDisplay.isNotBlank()) "${matchEpisode.title} $dateDisplay" else matchEpisode.title ?: stationPart
                    } else {
                        buildString {
                            append(stationPart)
                            if (dateDisplay.isNotBlank()) append(" $dateDisplay")
                            if (targetStart.length >= 4 && targetEnd.length >= 4) {
                                append(" ${targetStart.substring(0, 2)}:${targetStart.substring(2, 4)}-${targetEnd.substring(0, 2)}:${targetEnd.substring(2, 4)}")
                            }
                        }
                    }

                    val newEpisode = Episode(
                        id = "${stationPart}-$newDateStr-cross",
                        title = constructedTitle,
                        stationId = currentEpisode?.stationId ?: stationPart,
                        audioUrl = newUrl,
                        broadcastAt = "${newDateStr.substring(0, 4)}-${newDateStr.substring(4, 6)}-${newDateStr.substring(6, 8)}",
                        duration = 0
                    )
                    writeServiceLog("notification", "fetchCrossDayEpisode: RETURN constructed episode: ${newEpisode.title}, url=${newEpisode.audioUrl}")
                    return newEpisode
                }
            }

            writeServiceLog("notification", "fetchCrossDayEpisode: RETURN null - all methods failed (network, saved list, URL construction)")
        } catch (e: Exception) {
            writeServiceLog("notification", "fetchCrossDayEpisode: RETURN null - exception: ${e.message}")
            Log.e(TAG, "fetchCrossDayEpisode error", e)
        }
        writeServiceLog("notification", "fetchCrossDayEpisode: END - returning null, nextDate=$nextDate")
        return null
    }

    private fun notifyPrevEpisode() {
        writeServiceLog("notification", "notifyPrevEpisode: called, currentEpisode=${currentEpisode?.title ?: "null"}")
        val settings = AppSettings.getInstance(this)
        val curId = currentEpisode?.id
        if (curId == null) {
            writeServiceLog("notification", "notifyPrevEpisode: currentEpisode is null, cannot switch")
            showEpisodeSwitchFailedNotification("无法切换到上一个节目：当前无播放节目")
            return
        }

        // Try preCacheList first
        var prevEpisode = findPrevInList(loadPreCacheList(), curId, settings)
        // Fallback to saved episode list
        if (prevEpisode == null) {
            prevEpisode = findPrevInList(loadEpisodeList(), curId, settings)
        }

        if (prevEpisode != null) {
            val episodeKey = "${prevEpisode.stationId}::${prevEpisode.title}"
            val savedPos = getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(episodeKey, -1L)
            val startPos = if (savedPos > 0) savedPos else -1L
            playEpisode(prevEpisode, false, startPos)
            callback?.onEpisodeChanged(prevEpisode)
            // Also send broadcast for UI update
            val intent = Intent(BROADCAST_STATE_CHANGED).apply {
                putExtra(EXTRA_IS_PLAYING, true)
                putExtra("episode_title", prevEpisode.title)
                putExtra("episode_id", prevEpisode.id)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        } else {
            // Try cross-day fetch before giving up
            writeServiceLog("notification", "notifyPrevEpisode: no more episodes in current list, trying cross-day fetch (previous day)")
            val crossDayEp = fetchCrossDayEpisode(nextDate = false)
            if (crossDayEp != null) {
                writeServiceLog("notification", "notifyPrevEpisode: cross-day episode found: ${crossDayEp.title}")
                val episodeKey = "${crossDayEp.stationId}::${crossDayEp.title}"
                val savedPos = getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(episodeKey, -1L)
                val startPos = if (savedPos > 0) savedPos else -1L
                playEpisode(crossDayEp, false, startPos)
                callback?.onEpisodeChanged(crossDayEp)
                val intent = Intent(BROADCAST_STATE_CHANGED).apply {
                    putExtra(EXTRA_IS_PLAYING, true)
                    putExtra("episode_title", crossDayEp.title)
                    putExtra("episode_id", crossDayEp.id)
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                return
            }
            writeServiceLog("notification", "notifyPrevEpisode: no more episodes available (cross-day also failed)")
            showEpisodeSwitchFailedNotification("已经是第一个节目了")
        }
    }
    private fun notifyNextEpisode() {
        writeServiceLog("notification", "notifyNextEpisode: called, currentEpisode=${currentEpisode?.title ?: "null"}")
        val settings = AppSettings.getInstance(this)
        val curId = currentEpisode?.id
        if (curId == null) {
            writeServiceLog("notification", "notifyNextEpisode: currentEpisode is null, cannot switch")
            showEpisodeSwitchFailedNotification("无法切换到下一个节目：当前无播放节目")
            return
        }

        // Try preCacheList first
        var nextEpisode = findNextInList(loadPreCacheList(), curId, settings)
        // Fallback to saved episode list
        if (nextEpisode == null) {
            nextEpisode = findNextInList(loadEpisodeList(), curId, settings)
        }

        if (nextEpisode != null) {
            val episodeKey = "${nextEpisode.stationId}::${nextEpisode.title}"
            val savedPos = getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(episodeKey, -1L)
            val startPos = if (savedPos > 0) savedPos else -1L
            playEpisode(nextEpisode, false, startPos)
            callback?.onEpisodeChanged(nextEpisode)
            // Also send broadcast for UI update
            val intent = Intent(BROADCAST_STATE_CHANGED).apply {
                putExtra(EXTRA_IS_PLAYING, true)
                putExtra("episode_title", nextEpisode.title)
                putExtra("episode_id", nextEpisode.id)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        } else {
            // Try cross-day fetch before giving up
            writeServiceLog("notification", "notifyNextEpisode: no more episodes in current list, trying cross-day fetch")
            val crossDayEp = fetchCrossDayEpisode(nextDate = true)
            if (crossDayEp != null) {
                writeServiceLog("notification", "notifyNextEpisode: cross-day episode found: ${crossDayEp.title}")
                val episodeKey = "${crossDayEp.stationId}::${crossDayEp.title}"
                val savedPos = getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(episodeKey, -1L)
                val startPos = if (savedPos > 0) savedPos else -1L
                playEpisode(crossDayEp, false, startPos)
                callback?.onEpisodeChanged(crossDayEp)
                val intent = Intent(BROADCAST_STATE_CHANGED).apply {
                    putExtra(EXTRA_IS_PLAYING, true)
                    putExtra("episode_title", crossDayEp.title)
                    putExtra("episode_id", crossDayEp.id)
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                return
            }
            writeServiceLog("notification", "notifyNextEpisode: no more episodes available (cross-day also failed)")
            showEpisodeSwitchFailedNotification("已经是最后一个节目了")
        }
    }

    /**
     * 自动播放下一个节目（服务端直接执行，跳过不喜欢的节目）
     * 然后通过回调通知 Activity 更新 UI
     */
    private fun autoPlayNextEpisode() {
        writeNotifDetailLog("autoPlayNextEpisode: START, currentEpisode=${currentEpisode?.title}, episodeList.size=${loadEpisodeList().size}")
        if (currentEpisode == null) return
        try {
            val preCacheList = loadPreCacheList()
            val settings = AppSettings.getInstance(this)
            val curId = currentEpisode!!.id
            var nextEpisode: Episode? = null
            var foundCurrent = false
            for (ep in preCacheList) {
                if (!foundCurrent) {
                    if (ep.id == curId) foundCurrent = true
                    continue
                }
                if (!settings.isDisliked(ep.id) && !settings.isDislikedByTitle(ep.stationId, ep.title)) {
                    nextEpisode = ep
                    break
                }
            }
            if (nextEpisode == null) {
                Log.d(TAG, "autoPlayNextEpisode: no more episodes in pre-cache list, trying cross-day")
                writeNotifDetailLog("autoPlayNextEpisode: nextEpisode is null after pre-cache scan, trying cross-day (curId=$curId, preCacheList.size=${preCacheList.size})")
                writeServiceLog("notification", "autoPlayNext: reached end of episode list, trying cross-day")
                val crossDayEp = fetchCrossDayEpisode(nextDate = true)
                if (crossDayEp != null) {
                    writeNotifDetailLog("autoPlayNextEpisode: cross-day episode found, switching - title=${crossDayEp.title}, id=${crossDayEp.id}")
                    writeServiceLog("notification", "autoPlayNext: cross-day episode found: ${crossDayEp.title}")
                    playEpisode(crossDayEp, false)
                    callback?.onEpisodeChanged(crossDayEp)
                    return
                }
                writeNotifDetailLog("autoPlayNextEpisode: no cross-day episode found, stopping playback")
                writeServiceLog("notification", "autoPlayNext: no cross-day episode found, stopping")
                return
            }
            writeNotifDetailLog("autoPlayNextEpisode: found next episode in pre-cache list, switching - title=${nextEpisode.title}, id=${nextEpisode.id}")
            Log.d(TAG, "autoPlayNextEpisode: switching to ${nextEpisode.title} (id=${nextEpisode.id})")
            val episodeKey = "${nextEpisode.stationId}::${nextEpisode.title}"
            val savedPos = getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(episodeKey, -1L)
            val startPos = if (savedPos > 0) savedPos else -1L
            playEpisode(nextEpisode, false, startPos)
            // 通过回调通知 Activity 更新界面
            callback?.onEpisodeChanged(nextEpisode)
        } catch (e: Exception) {
            Log.e(TAG, "autoPlayNextEpisode failed", e)
            notifyNextEpisode()
        }
    }
    fun jumpToPrevSegment() {
        val segments = getSegmentList()
        if (segments.isNotEmpty()) {
            val currentPos = player?.currentPosition ?: 0L
            var prev: VoiceSegment? = null
            for (i in segments.indices) {
                if (segments[i].end >= currentPos) { if (i > 0) prev = segments[i - 1]; break }
            }
            prev?.let { seekTo(it.start); return } ?: segments.firstOrNull()?.let { seekTo(it.start); return }
        }
        // Fallback: skip backward 30 seconds
        val pos = (player?.currentPosition ?: 0L) - 30000
        seekTo(maxOf(0L, pos))
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
        // Issue 1: Cache the PendingIntent - creating it on every notification update may cause Activity recreation
        cachedContentIntent?.let { return it }

        val intent = Intent(this, PlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("from_notification", true)
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        cachedContentIntent = pi
        return pi
    }

    private fun applyNotificationIntents(remoteViews: RemoteViews) {
        val playing = playbackStarted && !userPaused
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
        // 取消注册监听器
        prefChangeListener?.let {
            getSharedPreferences("radio_app_settings", Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(it)
        }
        progressRunnable?.let { progressHandler?.removeCallbacks(it) }
        notificationRunnable?.let { notificationHandler?.removeCallbacks(it) }
        positionSaveRunnable?.let { positionSaveHandler?.removeCallbacks(it) }
        stopAutoSkipCheck()
        downloadingJob?.cancel()
        downloadActive.set(false)
        saveCurrentPosition()
        abandonAudioFocus()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        serviceScope.cancel()
        player?.release()
        player = null
        playbackStarted = false
        (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .cancel(NOTIFICATION_ID)
    }
}