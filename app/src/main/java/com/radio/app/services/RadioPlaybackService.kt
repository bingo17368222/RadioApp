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
import android.os.PowerManager
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
        fun onEpisodeChanged(episode: Episode) {}
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
    private var skipSeconds = 15
    private var errorRetryCount = 0
    private var isRetrying = false
    private var savePlaybackPosition = true
    private var notificationPlaying = false
    private var notificationTitle = "Radio App"
    private var notificationSubText = ""
    private var notificationDate = ""
    private var downloadingJob: kotlinx.coroutines.Job? = null
    private var positionRestoreRequested = false
    private var pendingStartPosition: Long = -1L
    private var isSeekingToPosition = false
    private var notificationStyle = "full"
    // SharedPreferences监听器，用于热切换通知栏样式
    private var prefChangeListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    // WakeLock保持CPU运行
    private var wakeLock: PowerManager.WakeLock? = null

    // 后台下载进度（供 UI 读取）
    @Volatile
    private var downloadProgressPct = 0
    @Volatile
    private var downloadTotalBytes = 0L
    @Volatile
    private var downloadDoneBytes = 0L
    private val downloadActive = AtomicBoolean(false)

    // MediaSession for Bluetooth/media button support
    private var mediaSession: MediaSessionCompat? = null
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
        // 获取WakeLock保持CPU运行
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadioApp::PlaybackWakeLock")
        wakeLock?.acquire(24 * 60 * 60 * 1000L)
        
        // 注册SharedPreferences监听器，实现通知栏样式热切换
        val prefs = getSharedPreferences("radio_app_settings", Context.MODE_PRIVATE)
        prefChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            Log.d(TAG, "Pref changed: key=$key")
            when (key) {
                "notification_style" -> {
                    reloadNotificationStyle()
                    Log.d(TAG, "Hot-switch notification style to: $notificationStyle")
                    val handler = Handler(Looper.getMainLooper())
                    handler.post { updateNotification() }
                }
                "skip_seconds" -> {
                    skipSeconds = prefs.getInt("skip_seconds", 15)
                    Log.d(TAG, "Hot-switch skipSeconds to: $skipSeconds, rebuilding notification")
                    val handler = Handler(Looper.getMainLooper())
                    handler.post { updateNotification() }
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
                        override fun onPlaybackStateChanged(state: Int) {
                            when (state) {
                                Player.STATE_READY -> {
                                    prepared = true
                                    isRetrying = false
                                    errorRetryCount = 0
                                    // Apply saved position via seek in STATE_READY (when player is prepared)
                                    if (positionRestoreRequested) {
                                        positionRestoreRequested = false
                                        applySavedPosition()
                                    }
                                    // If seeking, resume playback; isSeekingToPosition cleared with delay
                                    if (isSeekingToPosition && player?.playWhenReady == false) {
                                        player?.playWhenReady = true
                                    }
                                    callback?.onStateChanged(true)
                                    // Delay clearing isSeekingToPosition to allow seek to complete
                                    positionSaveHandler?.postDelayed({
                                        isSeekingToPosition = false
                                    }, 3000)
                                    updateNotification()
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
                            updateNotification()
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
            triggerPreCache()
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
                // 预缓存下一节目
                triggerPreCache()
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
        val settings = AppSettings.getInstance(this)
        writePreCacheLog("Pre-cache check: autoCache=${settings.autoCache}, wifiOnly=${settings.wifiOnlyPreCache}, targetCount=${settings.preloadCacheCount}")
        if (!settings.autoCache) {
            writePreCacheLog("Pre-cache: disabled")
            return
        }
        if (settings.wifiOnlyPreCache && !NetworkUtils.isWifiConnected(this)) {
            writePreCacheLog("Pre-cache: skipped (WiFi only)")
            return
        }

        val currentEp = currentEpisode ?: run {
            writePreCacheLog("Pre-cache: no current episode, skipping")
            return
        }

        val episodesDir = File(cacheDir, "episodes")
        if (!episodesDir.exists()) episodesDir.mkdirs()
        val cachedFiles = episodesDir.listFiles()?.filter { it.isFile && it.length() > 1024 } ?: emptyList()
        val cachedNames = cachedFiles.map { it.name }.toSet()

        var preCacheList = loadPreCacheList()
        writePreCacheLog("Pre-cache: list has ${preCacheList.size} episodes, current=${currentEp.title}")

        // Find current episode index in the list
        var currentIdx = preCacheList.indexOfFirst { it.id == currentEp.id }
        if (currentIdx < 0) {
            // Current episode not in list, try to find by URL
            currentIdx = preCacheList.indexOfFirst { it.audioUrl == currentEp.audioUrl }
        }
        if (currentIdx < 0) {
            writePreCacheLog("Pre-cache: current episode not in list, adding to list")
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
                writePreCacheLog("Pre-cache: future episode #$i already cached: ${ep.title}")
            } else if (!isDisliked && ep.audioUrl.isNotBlank() && nextToDownload == null) {
                nextToDownload = ep
                writePreCacheLog("Pre-cache: next to download: ${ep.title} (index=$i)")
            }
        }

        val targetCount = settings.preloadCacheCount
        writePreCacheLog("Pre-cache: futureCached=$futureCachedCount, target=$targetCount")

        if (futureCachedCount >= targetCount) {
            writePreCacheLog("Pre-cache: target reached ($futureCachedCount >= $targetCount)")
            return
        }

        // If no next episode to download in current list, fetch more days (forward only)
        if (nextToDownload == null) {
            writePreCacheLog("Pre-cache: no more future episodes in list, fetching more days (forward)")
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
            writePreCacheLog("Pre-cache: downloading: ${nextToDownload!!.title}")
            downloadPreCacheEpisode(nextToDownload!!)
        } else {
            writePreCacheLog("Pre-cache: no more future episodes available to download")
            // Check if all future episodes are disliked
            val allDisliked = ((currentIdx + 1) until preCacheList.size).all { i ->
                val ep = preCacheList[i]
                settings.isDisliked(ep.id) || settings.isDislikedByTitle(ep.stationId, ep.title) ||
                extractCacheFileName(ep.audioUrl) in cachedNames
            }
            if (allDisliked && (currentIdx + 1) < preCacheList.size) {
                writePreCacheLog("Pre-cache: all future episodes disliked or cached")
                Handler(Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(this, "当前电台未来节目已被全部缓存或标记为不喜欢", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
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
        val url = episode.audioUrl ?: return
        if (url.isBlank() || !url.startsWith("http")) return
        val fileName = extractCacheFileName(url)
        val episodesDir = File(cacheDir, "episodes")
        if (!episodesDir.exists()) episodesDir.mkdirs()
        val targetFile = File(episodesDir, fileName)
        if (targetFile.exists() && targetFile.length() > 1024) {
            writePreCacheLog("downloadPreCacheEpisode: already exists: ${episode.title} ($fileName)")
            triggerPreCache() // 已存在，继续下一个
            return
        }
        writePreCacheLog("downloadPreCacheEpisode: starting download: ${episode.title} from $url")
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
                    writePreCacheLog("downloadPreCacheEpisode: HTTP ${connection.responseCode} for ${episode.title}")
                    Log.e(TAG, "Pre-cache download failed: HTTP ${connection.responseCode}")
                    triggerPreCache() // 继续下一个
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
                writePreCacheLog("downloadPreCacheEpisode: downloaded ${targetFile.length()} bytes to ${targetFile.name} for ${episode.title}")
                Log.d(TAG, "Pre-cache: downloaded ${targetFile.length()} bytes to ${targetFile.name}")
                // 下载成功后触发PCM预解码
                startPcmPreDecode(episode.id ?: "", targetFile, episode.title ?: "unknown")
                // 同时检查当前播放节目的PCM预解码
                startPcmPreDecodeIfNeeded()
                // 继续预缓存下一个
                triggerPreCache()
            } catch (e: Exception) {
                writePreCacheLog("downloadPreCacheEpisode: error downloading ${episode.title}: ${e.message}")
                Log.e(TAG, "Pre-cache download error: ${e.message}")
                // 删除不完整的文件
                if (targetFile.exists()) targetFile.delete()
                triggerPreCache() // 继续下一个
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
                val pcmFile = File(pcmCacheDir, "${episodeId}_30min_16000.pcm")
                if (pcmFile.exists() && pcmFile.length() > 1024) {
                    writePreCacheLog("startPcmPreDecode: PCM cache already exists for $episodeTitle, skipping")
                    return@launch
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
            val pcmFile = File(pcmCacheDir, "${episodeId}_30min_16000.pcm")
            if (pcmFile.exists() && pcmFile.length() > 1024) {
                writePreCacheLog("startPcmPreDecodeIfNeeded: PCM already exists for ${episode.title}, skipping")
                return
            }
        }
        writePreCacheLog("startPcmPreDecodeIfNeeded: triggering PCM pre-decode from normal cache for ${episode.title}")
        startPcmPreDecode(episodeId, audioFile, episode.title ?: "unknown")
    }

    /**
     * 为预缓存解码音频到PCM (16kHz mono 16bit)
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

            val bufferInfo = MediaCodec.BufferInfo()
            fos = FileOutputStream(pcmFile)
            val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val outSampleRate = 16000
            val outChannels = 1
            writePreCacheLog("decodeToPcmForPreCache: sampleRate=$sampleRate, channels=$channelCount, outSampleRate=$outSampleRate, ratio=${sampleRate.toDouble()/outSampleRate}")

            var inputDone = false
            var outputDone = false
            var decodedBytes = 0L
            val maxPcmBytes = 60_000_000L // 30min @ 16kHz mono 16bit
            val decodeStartTime = System.currentTimeMillis()
            val maxDecodeTimeMs = 5 * 60 * 1000L

            while (!outputDone) {
                val now = System.currentTimeMillis()
                if (decodedBytes >= maxPcmBytes) { outputDone = true; break }
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
                            val resampled = resamplePcmForPreCache(pcmBytes, sampleRate, channelCount, outSampleRate, outChannels)
                            fos.write(resampled)
                            decodedBytes += resampled.size
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
            writePreCacheLog("decodeToPcmForPreCache: decoded $decodedBytes PCM bytes")
            writePreCacheLog("decodeToPcmForPreCache: PCM file size=${pcmFile.length()} bytes, expected duration=${pcmFile.length() / (outSampleRate * 2)}s")

            // After decode loop, write sample rate info
            val infoFile = File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".info")
            infoFile.writeText("sampleRate=$outSampleRate\nchannels=$outChannels")
            writePreCacheLog("decodeToPcmForPreCache: wrote info file: $infoFile")
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
     * 为预缓存重采样PCM (复用SubtitleGeneratorService的算法)
     */
    private fun resamplePcmForPreCache(input: ByteArray, inSampleRate: Int, inChannels: Int,
                                       outSampleRate: Int, outChannels: Int): ByteArray {
        // Fast path: no resampling needed
        if (inSampleRate == outSampleRate && inChannels == outChannels) {
            return input
        }

        val shorts = ShortArray(input.size / 2)
        ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        val ratio = inSampleRate.toDouble() / outSampleRate
        val outLength = (shorts.size / inChannels / ratio).toInt() * outChannels
        val output = ShortArray(outLength.coerceAtLeast(1))
        var outIdx = 0; var inIdx = 0.0
        while (outIdx < outLength) {
            val srcIdx = inIdx.toInt() * inChannels
            val nextIdx = ((inIdx.toInt() + 1) * inChannels).coerceAtMost(shorts.size - 1)
            val frac = inIdx - inIdx.toInt()
            val interpolated = (shorts[srcIdx] * (1.0 - frac) + shorts[nextIdx] * frac).toInt().toShort()
            output[outIdx] = interpolated
            outIdx++; inIdx += ratio
        }
        val result = ByteArray(output.size * 2)
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(output)
        return result
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
            player?.playWhenReady = false
            player?.seekTo(savedPos)
            Log.d(TAG, "Restored position: ${savedPos}ms (from ${if (pendingStartPosition >= 0) "override" else "prefs"})")
        }
    }

    private fun startPositionSaver() {
        positionSaveRunnable = Runnable {
            if (savePlaybackPosition && !isLive && prepared && player?.isPlaying == true
                && !isSeekingToPosition && pendingStartPosition < 0) {
                saveCurrentPosition()
            }
            positionSaveHandler?.postDelayed(positionSaveRunnable!!, POSITION_SAVE_INTERVAL)
        }
        positionSaveRunnable?.let { positionSaveHandler?.post(it) }
    }

    private fun saveCurrentPosition() {
        if (isSeekingToPosition || pendingStartPosition >= 0) return  // Don't save during seek/restore
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
        // 每次更新时重新加载通知栏样式，确保设置更改即时生效
        reloadNotificationStyle()
        val playing = player?.isPlaying ?: false

        val deleteIntent = PendingIntent.getService(this, 99,
            Intent(this, RadioPlaybackService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // 紧凑模式：使用系统标准MediaStyle通知栏，支持seekbar和拖动手势
        if (notificationStyle == "compact") {
            buildMediaStyleNotification(playing, deleteIntent)
            return
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
        if (notificationStyle == "full") {
            applySeekIntents(remoteViews)
        }

        val builder = NotificationCompat.Builder(this, RadioApplication.CHANNEL_ID)
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

        if (notificationStyle == "full") {
            builder.setCustomBigContentView(remoteViews)
        }

        val notification: Notification = builder.build()
        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 构建系统标准MediaStyle通知栏（五按钮，支持seekbar和拖动手势）
     */
    private fun buildMediaStyleNotification(playing: Boolean, deleteIntent: PendingIntent) {
        val contentText = if (playing) "正在播放 $notificationSubText" else "已暂停 $notificationSubText"

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

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
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
            updateMediaSessionState()
        } catch (e: Exception) { Log.e(TAG, "playStation failed", e) }
    }

    fun playEpisode(episode: Episode, live: Boolean, startPositionMs: Long = -1L) {
        Log.d(TAG, "playEpisode called: ${episode.title}, playbackStarted before: $playbackStarted, prepared=$prepared, url=${episode.audioUrl}, startPositionMs=$startPositionMs")
        currentEpisode = episode; currentStation = null; isLive = live
        prepared = false; errorRetryCount = 0; isRetrying = false
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
        notificationTitle = episode.title ?: "节目回放"
        notificationDate = episode.broadcastAt?.take(10) ?: ""
        notificationSubText = if (notificationDate.isNotEmpty()) "[回放] $notificationDate" else "[回放]"
        notificationPlaying = true
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
            requestAudioFocus(); updateNotification()
            updateMediaSessionState()
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
    fun getCurrentPlayingUrl(): String = currentPlayingUrl
    fun isPlaybackStarted(): Boolean = playbackStarted
    fun isSameEpisodePlaying(url: String): Boolean {
        return playbackStarted && currentPlayingUrl.isNotBlank() && currentPlayingUrl == url &&
                (player?.isPlaying == true || prepared)
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

    fun jumpToNextSegment() {
        val segments = currentEpisode?.voiceSegments ?: return
        val currentPos = player?.currentPosition ?: 0L
        for (seg in segments) { if (seg.start > currentPos) { seekTo(seg.start); return } }
    }

    private fun notifyPrevEpisode() {
        val settings = AppSettings.getInstance(this)
        val curId = currentEpisode?.id ?: return

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
        } else {
            val intent = Intent(BROADCAST_STATE_CHANGED).apply {
                putExtra(EXTRA_IS_PLAYING, false); putExtra("action", "prev_episode")
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
    }
    private fun notifyNextEpisode() {
        val settings = AppSettings.getInstance(this)
        val curId = currentEpisode?.id ?: return

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
        } else {
            val intent = Intent(BROADCAST_STATE_CHANGED).apply {
                putExtra(EXTRA_IS_PLAYING, false); putExtra("action", "next_episode")
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
    }

    /**
     * 自动播放下一个节目（服务端直接执行，跳过不喜欢的节目）
     * 然后通过回调通知 Activity 更新 UI
     */
    private fun autoPlayNextEpisode() {
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
                Log.d(TAG, "autoPlayNextEpisode: no more episodes in pre-cache list, falling back to broadcast")
                notifyNextEpisode()
                return
            }
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
        // 取消注册监听器
        prefChangeListener?.let {
            getSharedPreferences("radio_app_settings", Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(it)
        }
        wakeLock?.let { if (it.isHeld) it.release() }
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