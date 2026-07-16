package com.radio.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import kotlin.math.abs
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
        private const val MAX_ERROR_RETRY = 1  // [v2.3.6] Reduced from 3 to 1: faster recovery (2s instead of 18s)
        private const val NOTIFICATION_ID = 1
        private const val POSITION_SAVE_INTERVAL = 5000L
        // [v2.4.13] Subtitle patrol interval: 3 minutes
        private const val SUBTITLE_PATROL_INTERVAL_MS = 3L * 60 * 1000

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

    // [v2.0.91] Called by PlayerActivity.onResume() to activate post-resume skip protection.
    // v2.0.91 fixes:
    // - Don't clear an already-active circuit breaker (prevents protection reset race)
    // - Add consecutive backward skip tracking to prevent "frog-boiling" step-down to 0
    // - Strengthen zeroStorm to use maxKnownPosition (monotonic) instead of authoritativePosition
    fun notifyActivityResumed() {
        val now = System.currentTimeMillis()
        lastClientBindTime = now
        skipRequestCount = 0
        skipRequestWindowStart = now
        breakerWasTripped = false  // [v2.2.7] Reset on new resume
        // [v2.0.91] Don't reset breaker if it's still active — preserve protection
        if (now >= skipCircuitBreakerUntil) {
            skipCircuitBreakerUntil = 0L
        }
        inPostResumeProtection = true
        // [v2.0.91] Reset consecutive backward skip counter
        consecutiveBackwardSkips = 0
        firstBackwardSkipWindowStart = now
        writeServiceLog("playback", "[v2.0.91] notifyActivityResumed: set blackout for ${POST_RESUME_BLACKOUT_MS}ms")
        // After blackout+breaker window, exit protection mode
        audioFocusHandler.postDelayed({
            inPostResumeProtection = false
            skipRequestCount = 0
        }, POST_RESUME_BLACKOUT_MS + SKIP_CIRCUIT_BREAKER_MS + 1000L)
    }

    /** Issue 9: app version tag included in every log line */
    private val appVersion: String by lazy {
        try {
            @Suppress("DEPRECATION")
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            "v${pInfo.versionName}"
        } catch (e: Exception) {
            "v?"
        }
    }

    private fun writeServiceLog(category: String, msg: String) {
        try {
            val logDir = java.io.File(com.radio.app.RadioApplication.getLogDir(this), category)
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = java.io.File(logDir, "${category}.log")
            // Add version header on first write
            if (!logFile.exists()) {
                logFile.appendText("=== RadioApp $appVersion ===\n")
            }
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            java.io.FileWriter(logFile, true).use { it.append("[$ts][$appVersion] $msg\n") }
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
            // v2.4.37: Use unified log directory
            val logDir = java.io.File(com.radio.app.RadioApplication.getLogDir(this), "notif_detail")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = java.io.File(logDir, "notif_detail.log")
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
            logFile.appendText("[$timestamp][$appVersion] $message\n")
        } catch (_: Exception) {}
    }

    private var player: ExoPlayer? = null
    private val binder = LocalBinder()
    private var currentEpisode: Episode? = null
    // [v2.1.8] getCurrentEpisode() already exists at line ~3703
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
    // [v2.0.94] Flag to prevent getSafeDuration() from using stale player.duration during episode switch.
    // Set to true in playEpisode() when switching to a different episode, cleared in STATE_READY.
    private var episodeSwitching = false
    private var currentStreamUrl = ""
    private var progressHandler: Handler? = null
    private var progressRunnable: Runnable? = null
    private var notificationHandler: Handler? = null
    private var notificationRunnable: Runnable? = null
    private var lastNotifiedPosition = -1L
    // v2.4.112: Track last RESET-triggered full rebuild to prevent notification burst.
    // When episode switch sets lastNotifiedPosition=-1, the progress poll detects RESET
    // and forces a full rebuild. Without this guard, multiple polls within a few seconds
    // each trigger a separate rebuild (2-5 manager.notify calls in 1.5-3 seconds).
    private var lastResetRebuildTime = 0L
    // [v2.4.6] Track when playback gets stuck near the end of an episode.
    // ExoPlayer sometimes doesn't fire STATE_ENDED when audio reaches the end,
    // leaving the player in STATE_READY with isPlaying=true but position frozen
    // near dur. This causes the notification progress bar to stay at 100% forever.
    private var stuckAtEndSince = 0L
    private var stuckAtEndPos = 0L
    private var positionSaveHandler: Handler? = null
    private var positionSaveRunnable: Runnable? = null
    private var skipSeconds = 15
    private var errorRetryCount = 0
    private var isRetrying = false
    // [v2.3.0-fix] Cancel pending retry when switching episodes to prevent old retry from interrupting new playback
    private var retryHandler: Handler? = null
    private var retryRunnable: Runnable? = null
    private var savePlaybackPosition = true
    private var notificationPlaying = false
    private var userPaused = false // Track whether USER paused (vs buffering pause)
    // [v2.0.71] Issue 8 Fix: Track whether playback was paused by audio focus loss (transient).
    // When AUDIOFOCUS_GAIN returns, we resume ONLY if pausedByAudioFocus is true,
    // regardless of userPaused. This fixes the issue where user pauses, then opens
    // Pinduoduo video, then Pinduoduo ends - playback should NOT auto-resume because
    // user had already paused. But if user was playing and Pinduoduo interrupted,
    // playback SHOULD auto-resume after Pinduoduo ends.
    private var pausedByAudioFocus = false
    // [v2.0.74] Issue 6/7 Fix: Track the TYPE of audio focus loss separately.
    // - FOCUS_LOSS_NONE: no loss
    // - FOCUS_LOSS_TRANSIENT: temporary loss (navigation, phone call) -> auto-resume on GAIN
    // - FOCUS_LOSS_PERMANENT: permanent loss (Pinduoduo/Douyin video) -> do NOT auto-resume
    // - FOCUS_LOSS_DUCK: can duck (short navigation chime) -> just lower volume, no pause
    private val FOCUS_LOSS_NONE = 0
    private val FOCUS_LOSS_TRANSIENT = 1
    private val FOCUS_LOSS_PERMANENT = 2
    private val FOCUS_LOSS_DUCK = 3
    private var audioFocusLossType = FOCUS_LOSS_NONE
    // [v2.0.77] Issue 5 Fix: Active polling recovery for permanent audio focus loss.
    // Many Chinese apps (Pinduoduo, some video players) NEVER call abandonAudioFocus() after
    // they finish playing video, so we NEVER receive AUDIOFOCUS_GAIN. The 60-second passive
    // wait is insufficient. Solution: after a permanent loss, actively poll every 5 seconds
    // by trying to re-request audio focus. If request is GRANTED, it means the other app
    // has released focus (or been killed by LMKD), so we can resume.
    // [v2.0.78] Issue 5 Fix: Shorter passive wait + faster probing.
    // v2.0.77 waited 60s passively which was too long; Pinduoduo videos are usually 15-30s.
    // Start probing after 10s passive wait, probe every 3s (was 5s), for up to 3 minutes.
    // Also add a pause-confirmed flag to prevent notification flicker after pause().
    private var focusRecoveryAttempts = 0
    private var pauseConfirmedUntil = 0L  // [v2.0.78] force notification to show PAUSED until this time
    // [v2.0.83] DISABLED focusProbe active probing. requestAudioFocus() always returns GRANTED
    // because AudioFocus is a soft resource - the system grants it to whoever asks. This caused
    // "不管是否退出拼多多，几秒后都会自动恢复播放" - app would steal focus from Pinduoduo and
    // resume playback even while user was still watching Pinduoduo videos.
    // Now: PERMANENT loss → pause and wait for passive GAIN callback (user returns to app).
    private val focusProbeRunnable = object : Runnable {
        override fun run() {
            // [v2.0.83] Disabled - do not actively request audio focus
            writeServiceLog("audiofocus", "[v2.0.83] focusProbe: DISABLED, not actively requesting focus")
            return
        }
    }
    // [v2.0.83] Disabled permanent loss recovery - no active probing
    private val permanentLossRecoveryRunnable = Runnable {
        // [v2.0.83] Disabled - do not start focus probing after PERMANENT loss
        // Only passive AUDIOFOCUS_GAIN callback will resume playback
        writeServiceLog("audiofocus", "[v2.0.83] permanentLossRecovery: DISABLED, waiting for passive GAIN only")
        return@Runnable
    }
    // [v2.0.88] Issue 3 Fix: Smart resume after PERMANENT audio focus loss.
    // When Pinduoduo/Douyin video takes permanent focus, start polling.
    // Check if any other app has an active PLAYING MediaSession. If none found,
    // try to re-request audio focus. If granted, resume playback.
    // [v2.1.2] Changed from hardcoded 15s to configurable setting (default 5s).
    private var smartResumePollMs: Long = 5_000L  // Updated from settings when focus loss occurs
    private var smartResumeRunning = false
    private val smartResumeRunnable = Runnable { smartResumePoll() }
    // [v2.0.89] Issue 3 Fix: Smart resume after PERMANENT audio focus loss.
    // v2.0.88 used MediaSessionManager.getActiveSessions() which requires
    // MEDIA_CONTENT_CONTROL permission (not available to regular apps).
    // v2.0.89 uses AudioManager.isMusicActive() instead — no special permission needed.
    private fun smartResumePoll() {
        if (!smartResumeRunning) return
        if (!playbackStarted || userPaused) {
            writeServiceLog("audiofocus", "[v2.0.89] smartResume: stopping (playbackStarted=$playbackStarted, userPaused=$userPaused)")
            smartResumeRunning = false
            return
        }
        try {
            // [v2.0.89] Use AudioManager.isMusicActive() instead of MediaSessionManager.
            // isMusicActive() returns true if any app is actively playing music/audio.
            // No special permission required.
            val am = audioManager ?: getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val musicActive = am?.isMusicActive ?: false
            if (musicActive) {
                writeServiceLog("audiofocus", "[v2.0.89] smartResume: other app still playing music (isMusicActive=true), waiting...")
            } else {
                writeServiceLog("audiofocus", "[v2.0.89] smartResume: no other app playing (isMusicActive=false), attempting to re-request focus")
                val granted = requestAudioFocus()
                if (granted) {
                    writeServiceLog("audiofocus", "[v2.0.89] smartResume: FOCUS GRANTED! Resuming playback")
                    audioFocusLossType = FOCUS_LOSS_NONE
                    pausedByAudioFocus = false
                    smartResumeRunning = false
                    // [v2.3.5] Only resume if player exists; if null, let play() handle recovery
                    if (player != null) {
                        player?.play()
                        prepared = true
                    } else {
                        writeServiceLog("audiofocus", "[v2.3.5] smartResume: player is null, calling play() for recovery")
                        audioFocusHandler.post { play() }
                    }
                    forceNotificationUpdate = true
                    // v2.4.117: Don't reset lastNotificationContentHash — force flag is sufficient
                    updateNotification()
                    return
                } else {
                    writeServiceLog("audiofocus", "[v2.0.89] smartResume: focus request FAILED, will retry in ${smartResumePollMs/1000}s")
                }
            }
        } catch (e: Exception) {
            writeServiceLog("audiofocus", "[v2.0.89] smartResume: exception: ${e.message}")
        }
        // Schedule next poll
        audioFocusHandler.postDelayed(smartResumeRunnable, smartResumePollMs)
    }
    private val audioFocusPermanentLossTimeoutMs = 10_000L
    // [v2.0.73] Issue 5 Fix: Cache last valid duration to avoid Long.MIN_VALUE from ExoPlayer
    // during episode switches (player.getDuration() returns C.TIME_UNSET = -9223372036854775807
    // when player is reset, causing notification progress bar to disappear)
    private var lastValidDurationMs = 7200000L  // Default 2 hours for radio episodes
    private var notificationTitle = "Radio App"
    private var notificationSubText = ""
    private var notificationDate = ""
    private var notificationTimeRange = ""
    private var downloadingJob: kotlinx.coroutines.Job? = null
    private var positionRestoreRequested = false
    private var pendingStartPosition: Long = -1L
    private var isSeekingToPosition = false
    // [v2.0.62] Issue 1 Fix (PowerAmp approach): Authoritative position tracking
    // The service is the SINGLE SOURCE OF TRUTH for playback position.
    // During preparation/seeking/buffering, ExoPlayer reports 0 or intermediate positions.
    // We maintain authoritativePosition which:
    //   - Is set to seekTargetPosition when restoring to a saved position
    //   - Only moves FORWARD from actual player position updates
    //   - Never reports 0 when we know the player should be at a saved position
    private var authoritativePosition = 0L
    private var seekTargetPosition = 0L
    // [v2.0.48] Issue 1 Fix: Track max known position to prevent backward position reporting/saving
    // ExoPlayer reports backward positions during re-buffering; this ensures we never go backward
    private var maxKnownPosition = 0L
    private var lastPositionRestoreTime = 0L
    // v2.4.116: Track the start position for the current episode. Used to validate rawPos
    // from ExoPlayer. Unlike the 10s switch window, this check is ALWAYS active because
    // ExoPlayer can report stale old-episode positions even 12+ seconds after setMediaItem.
    @Volatile
    private var episodeStartPos = 0L
    // v2.4.116: Counter for stale position rejections (reset on episode switch)
    @Volatile
    private var stalePosRejectCount = 0

    // v2.4.117: Centralized position update with stale-value rejection.
    // ALL code paths that update authoritativePosition from player position must use this.
    // Prevents old episode's position from leaking into the new episode.
    private fun safeUpdatePosition(rawPos: Long): Boolean {
        if (rawPos <= authoritativePosition) return false
        val delta = rawPos - authoritativePosition
        if (delta > 60000 && episodeStartPos > 0) {
            // Position jumped >60s in a single update — definitely stale from old episode.
            if (stalePosRejectCount < 5) {
                stalePosRejectCount++
                writeServiceLog("playback", "[v2.4.117] safeUpdatePosition: REJECTING stale rawPos=$rawPos (authPos=$authoritativePosition, episodeStartPos=$episodeStartPos, delta=${delta}ms, count=$stalePosRejectCount)")
            }
            return false
        }
        authoritativePosition = rawPos
        if (rawPos > maxKnownPosition) maxKnownPosition = rawPos
        return true
    }
    // [v2.0.77] Issue 1 Fix: seekTo debounce/duplicate protection to prevent seekTo(0) storms.
    // Logs show repeated skipBackward/seekTo(0) calls at ~300ms intervals (likely misfiring
    // notification PendingIntents or headset button repeats). Rate-limit seeks and ignore
    // repeated seeks to the same position within a short window.
    private var lastSeekCallTime = 0L
    private var lastSeekTargetPos = -1L
    private var lastSkipDirectionTime = 0L  // for skipForward/skipBackward debounce
    // [v2.0.86] Skip storm protection redesigned per user feedback:
    // - Post-resume blackout: blocks ALL skips for 3s after app resumes
    // - During blackout: count ALL requests (including blocked ones). If >=5 requests in blackout,
    //   trip circuit breaker. This is the v2.0.81 approach that prevented flicker because
    //   0 skips actually executed seekTo during the storm.
    // - [v2.0.88] Circuit breaker is SELF-EXTENDING: each new request during breaker extends it
    //   by SKIP_CIRCUIT_BREAKER_MS. This prevents the "breaker expires → storm resumes" bug.
    // - [v2.0.88] After breaker expires: POST_BREAKER_COOLDOWN (30s) enforces min 2s between
    //   skip executions. This catches skip storms that continue past the breaker window.
    // - After cooldown: NO LIMITS on user clicks (per user requirement #3).
    // [v2.2.6] Relaxed skip protection parameters to prevent false-positive blocking of legitimate skips.
    // Previous values were too aggressive: 3s blackout + 5 requests + 30s cooldown blocked normal usage.
    private val POST_RESUME_BLACKOUT_MS = 5000L        // [v2.3.3] Extended to 5s: headsets/bluetooth can replay MEDIA_PREVIOUS key events at ~220ms intervals for many seconds after resume
    private val SKIP_CIRCUIT_BREAKER_MS = 5_000L       // [v2.3.3] Extended breaker to 5s to stop sustained key-repeat storms
    private val SKIP_DEBOUNCE_MS = 200L                 // [v2.2.8] 200ms debounce (was 300ms)
    private val EPISODE_CHANGE_SKIP_COOLDOWN_MS = 2_000L // [v2.2.6] 2s (was 3s)
    private val LOW_POSITION_SKIP_DEDUP_MS = 3_000L
    private val STORM_REQUEST_THRESHOLD = 10            // [v2.2.6] 10 requests to trip (was 5)
    private val POST_BREAKER_COOLDOWN_MS = 10_000L      // [v2.2.6] 10s cooldown (was 30s)
    private val POST_BREAKER_MIN_INTERVAL_MS = 1_000L   // [v2.2.6] 1s min interval (was 2s)
    // [v2.0.91] Consecutive backward skip protection: prevent "frog-boiling" step-down to 0
    private val MAX_CONSECUTIVE_BACKWARD_SKIPS = 10     // [v2.2.6] 10 (was 6)
    private val CONSECUTIVE_BACKWARD_WINDOW_MS = 10_000L
    private var consecutiveBackwardSkips = 0
    private var firstBackwardSkipWindowStart = 0L
    private var skipRequestCount = 0
    private var skipRequestWindowStart = 0L
    private var skipCircuitBreakerUntil = 0L
    private var inPostResumeProtection = false  // true during blackout+breaker window
    private var breakerWasTripped = false  // [v2.2.7] true only when breaker actually triggered (not on every resume)
    private var lastBackwardSkipTime = 0L
    private var lastClientBindTime = 0L
    private var lastEpisodeStartTime = 0L  // set when playEpisode starts new episode
    private var lastSeekToZeroTime = 0L  // dedup seekTo(0) when position is low
    private var notificationStyle = "compact"
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
    // [v2.0.73] Issue 1 Fix: Track last playEpisode call to debounce rapid episode switches.
    // When user quickly switches episodes (cross-day), multiple playEpisode calls cause player
    // reset/prepare cycles that produce position=0 and seekTo oscillation between different positions.
    @Volatile
    private var lastPlayEpisodeEpisodeId: String? = null
    @Volatile
    private var lastPlayEpisodeTime = 0L

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
    // Issue 3: 切换节目后强制更新通知的标志位，绕过 contentHash 去重检查
    @Volatile
    private var forceNotificationUpdate: Boolean = false

    // MediaSession for Bluetooth/media button support
    private var mediaSession: MediaSessionCompat? = null
    // Issue 1: Cache the contentIntent PendingIntent - creating it on every notification update may cause Activity recreation
    private var cachedContentIntent: PendingIntent? = null
    // 防止切回app时重复启动播放
    @Volatile
    private var playbackStarted = false
    private var currentPlayingUrl = ""
    // [v2.0.77] Issue 6 Fix: Track whether we've already called startForeground() to avoid
    // repeated startForeground() calls which cause notification flicker/disappear on many OEM devices.
    private var isForegroundService = false

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
        // [v2.0.77] Issue 6 Fix: Mark foreground started so subsequent updates use notify() not startForeground()
        isForegroundService = true
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

        // [v2.4.61] 不再自动生成5分钟PCM文件，禁用PCM巡逻修复
        // Handler(Looper.getMainLooper()).postDelayed({
        //     writePreCacheLog("patrolPcm: triggering startup PCM patrol")
        //     patrolAndRepairPcmFiles()
        // }, 10_000)

        // [v2.4.13] Subtitle patrol: check every 3 minutes for cached episodes without subtitles
        // Only generates when subtitle service is idle (checked inside patrolSubtitleGeneration)
        subtitlePatrolHandler = Handler(Looper.getMainLooper())
        subtitlePatrolRunnable = object : Runnable {
            override fun run() {
                patrolSubtitleGeneration()
                subtitlePatrolHandler.postDelayed(this, SUBTITLE_PATROL_INTERVAL_MS)
            }
        }
        subtitlePatrolHandler.postDelayed(subtitlePatrolRunnable, 30_000)  // First check after 30s
    }

    // [v2.4.13] Subtitle patrol handler and runnable
    private lateinit var subtitlePatrolHandler: Handler
    private lateinit var subtitlePatrolRunnable: Runnable

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
        // [v2.0.76] Issue 6 Fix: NEVER trust player.isPlaying alone after pause/stop intent.
        // ExoPlayer has a race condition where isPlaying briefly returns true ~400ms-1.5s after pause(),
        // which would overwrite STATE_PAUSED with STATE_PLAYING, causing notification button to show wrong state.
        // If userPaused or pausedByAudioFocus is true, state MUST be PAUSED regardless of player.isPlaying.
        val playerIsPlaying = player?.isPlaying == true
        val state = when {
            userPaused -> PlaybackStateCompat.STATE_PAUSED
            pausedByAudioFocus -> PlaybackStateCompat.STATE_PAUSED
            playerIsPlaying -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val effectivePlaying = (state == PlaybackStateCompat.STATE_PLAYING)
        // [v2.0.62] Issue 5 Fix: Use authoritative getCurrentPosition() instead of raw player position
        val pos = getCurrentPosition()
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
        writeServiceLog("notification", "[v2.0.76] updateMediaSessionState: state=${if (effectivePlaying) "PLAYING" else "PAUSED"}, playerIsPlaying=$playerIsPlaying, userPaused=$userPaused, pausedByAF=$pausedByAudioFocus, pos=$pos")

        // [v2.0.44] Issue 5 Fix: 同步 MediaSession 元数据
        updateMediaSessionMetadata()
    }

    /**
     * [Issue 5 Fix] 更新 MediaSession 元数据（节目标题/日期/时间段/时长）。
     * MediaStyle 通知栏直接读取 MediaSession 的当前元数据进行渲染，因此必须在构建/刷新
     * 通知栏之前调用。切换节目后即使播放器尚未准备好（duration 未知），也用节目元数据
     * 时长回退，确保覆盖上一集的陈旧元数据，避免通知栏显示旧节目信息。
     */
    private fun updateMediaSessionMetadata() {
        val episode = currentEpisode ?: return  // 直播时 currentEpisode 为 null，直接返回
        // 优先使用播放器实际时长，未准备好时回退到节目元数据时长（毫秒）
        val dur = player?.duration ?: 0L
        val effectiveDur = if (dur > 0) dur else (episode.duration.times(1000))
        // v2.4.102: Include date+time in title for MIUI MediaStyle notification
        val dateStr = if (notificationDate.length >= 10) notificationDate.substring(5, 10) else ""
        // v2.4.107: Title = title + time range (no date). Date stays in subtitle only.
        val displayTitle = buildString {
            append(episode.title)
            if (notificationTimeRange.isNotBlank()) append(" $notificationTimeRange")
        }
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, notificationDate)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, notificationTimeRange)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, effectiveDur)
            .build()
        mediaSession?.setMetadata(metadata)
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
                            val actualPos = player?.currentPosition ?: -1L
                            Log.d(TAG, "[v2.0.91] onPositionDiscontinuity: reason=$reason, actualPos=$actualPos, authPos=$authoritativePosition, isSeeking=$isSeekingToPosition, target=$seekTargetPosition")
                            if (isSeekingToPosition) {
                                // [v2.0.91] Only clear seeking state when position is near target.
                                // ExoPlayer may fire discontinuity multiple times during a seek, reporting
                                // intermediate positions (including 0). Prematurely clearing isSeekingToPosition
                                // causes getCurrentPosition() to report the intermediate/0 position instead of
                                // the seek target, leading to UI flicker and apparent backtracking.
                                if (actualPos >= seekTargetPosition - 2000) {
                                    isSeekingToPosition = false
                                    safeUpdatePosition(actualPos)
                                } else {
                                    Log.d(TAG, "[v2.0.91] onPositionDiscontinuity: still seeking, actualPos=$actualPos < target-2000=${seekTargetPosition-2000}, keeping isSeekingToPosition=true")
                                }
                                player?.playWhenReady = true
                                notifyNotification()
                                startPositionSaver()
                            } else {
                                safeUpdatePosition(actualPos)
                            }
                        }
                        override fun onPlaybackStateChanged(state: Int) {
                            when (state) {
                                Player.STATE_READY -> {
                                    prepared = true
                                    // [v2.0.94] Clear episode switching flag — player is now ready with new media
                                    episodeSwitching = false
                                    val curPos = player?.currentPosition ?: 0L
                                    if (isSeekingToPosition && seekTargetPosition > 0) {
                                        // [v2.0.65] Issue 1 Fix: Only clear seeking state if position is NEAR target.
                                        // Previously, curPos <= 0 cleared seeking state, causing position to drop to 0.
                                        if (curPos >= seekTargetPosition - 2000) {
                                            Log.d(TAG, "[v2.0.65] STATE_READY: pos=$curPos near target=$seekTargetPosition, clearing seeking state")
                                            isSeekingToPosition = false
                                            safeUpdatePosition(curPos)
                                        } else {
                                            Log.d(TAG, "[v2.0.65] STATE_READY: pos=$curPos NOT near target=$seekTargetPosition, keeping seeking state, authPos=$authoritativePosition")
                                        }
                                    }
                                    // [v2.0.53] Issue 1 Fix: Seek BEFORE first STATE_READY using ExoPlayer's setSeekParameters
                                    // Previous approach: seek in STATE_READY → STATE_BUFFERING → STATE_READY again
                                    // Problem: during STATE_BUFFERING, positions 0→savedPos are all "backward" = UI frozen
                                    // Fix: seek immediately after prepare() using player.setMediaItem+seekTo
                                    // The position restore is handled in playEpisode() directly
                                    if (positionRestoreRequested && pendingStartPosition > 0) {
                                        writeServiceLog("playback", "[v2.0.62] STATE_READY: positionRestoreRequested still true (seek before prepare failed), seeking now")
                                        isSeekingToPosition = true
                                        seekTargetPosition = pendingStartPosition
                                        authoritativePosition = pendingStartPosition
                                        maxKnownPosition = pendingStartPosition
                                        player?.seekTo(pendingStartPosition)
                                        positionRestoreRequested = false
                                        // v2.4.43: CRITICAL FIX - Clear pendingStartPosition!
                                        // Without this, saveCurrentPosition() was BLOCKED forever
                                        // because it checks `pendingStartPosition >= 0`.
                                        // This was the root cause of issue 5: position never saved.
                                        writeServiceLog("playback", "[v2.4.43] STATE_READY: clearing pendingStartPosition (was $pendingStartPosition)")
                                        pendingStartPosition = -1L
                                    }
                                    player?.playWhenReady = true
                                    playbackInitializing = false
                                    isRetrying = false
                                    errorRetryCount = 0
                                    Log.d(TAG, "[v2.0.62] STATE_READY: isSeekingToPosition=$isSeekingToPosition, pos=$curPos, seekTarget=$seekTargetPosition, authPos=$authoritativePosition")
                                    callback?.onStateChanged(true)
                                    // 后台下载音频文件
                                    startBackgroundDownload()
                                    // [v2.4.61] 不再自动生成5分钟PCM文件，手动生成字幕时再生成
                                    // startPcmPreDecodeIfNeeded()
                                }
                                Player.STATE_ENDED -> {
                                    callback?.onStateChanged(false)
                                    clearSavedPosition()
                                    stopAutoSkipCheck()
                                    // [v2.0.71] Issue 8 Fix: Don't set userPaused=true here.
                                    // This flag is for USER-initiated pause. Setting it here
                                    // prevents audio focus from resuming playback after Pinduoduo
                                    // video ends. Instead, use a separate flag for playback ended state.
                                    prepared = false   // No longer prepared for progress updates
                                    // [v2.0.87] Fix: Reset authoritativePosition so notification doesn't
                                    // stay stuck at 100% (pos=dur) after playback ends. Without this,
                                    // getCurrentPosition() returns the old end position forever, causing
                                    // the notification and main UI to show "full progress" that never updates.
                                    authoritativePosition = 0L
                                    maxKnownPosition = 0L
                                    lastNotifiedPosition = -1L
                                    writeServiceLog("notification", "[v2.0.87] STATE_ENDED: prepared=false, reset authoritativePosition=0 (userPaused NOT set)")
                                    // [v2.0.92] Fix: For continuous play, skip updateNotification() here.
                                    // Calling updateNotification() before autoPlayNextEpisode() causes
                                    // the notification to show progress=100% (old pos=dur / old dur).
                                    // autoPlayNextEpisode() will call playEpisode() which resets position
                                    // and calls updateNotification() with the correct new episode state.
                                    if (continuousPlay && !isLive) {
                                        Log.d(TAG, "Playback ended, auto-playing next episode")
                                        autoPlayNextEpisode()
                                    } else {
                                        forceNotificationUpdate = true
                                        // v2.4.117: Don't reset lastNotificationContentHash — force flag is sufficient
                                        updateNotification()
                                    }
                                }
                            }
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            try {
                                val errMsg = try { error.message ?: "unknown error" } catch (_: Exception) { "unknown error" }
                                Log.e(TAG, "ExoPlayer error: $errMsg")
                                writeServiceLog("playback", "onPlayerError: $errMsg, retryCount=$errorRetryCount")
                                prepared = false
                                // v2.4.111: Clear episodeSwitching on player error so that
                                // saveCurrentPosition() resumes working during error recovery.
                                // Previously, episodeSwitching stayed true for 28+ seconds during
                                // Source error retries, blocking 54 consecutive position saves.
                                if (episodeSwitching) {
                                    episodeSwitching = false
                                    writeServiceLog("playback", "[v2.4.111] onPlayerError: cleared episodeSwitching (was true, blocking saves)")
                                }
                                errorRetryCount++

                                // Cancel any previous pending retry (fully null-safe)
                                try {
                                    retryRunnable?.let { r ->
                                        retryHandler?.removeCallbacks(r)
                                    }
                                } catch (_: Exception) {}
                                retryRunnable = null

                                if (errorRetryCount <= MAX_ERROR_RETRY && currentStreamUrl.isNotEmpty()) {
                                    isRetrying = true
                                    val retryDelay = errorRetryCount * 2000L  // [v2.3.6] Reduced from 3s to 2s
                                    writeServiceLog("playback", "onPlayerError: scheduling retry #$errorRetryCount in ${retryDelay}ms")
                                    try {
                                        retryHandler = Handler(Looper.getMainLooper())
                                    } catch (_: Exception) {}
                                    val runnable = Runnable {
                                        retryRunnable = null
                                        if (isRetrying) {
                                            try {
                                                player?.let {
                                                    it.stop()
                                                    it.clearMediaItems()
                                                    it.setMediaItem(MediaItem.fromUri(currentStreamUrl))
                                                    it.playWhenReady = true
                                                    it.prepare()
                                                    it.play()
                                                }
                                                writeServiceLog("playback", "onPlayerError: retry #${errorRetryCount} executed")
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Retry failed", e)
                                                writeServiceLog("playback", "onPlayerError: retry exception: ${e.message}")
                                                isRetrying = false
                                                playbackInitializing = false
                                                episodeSwitching = false
                                                prepared = false  // [v2.3.4] Reset prepared flag
                                                // [v2.3.3] Release broken player on retry exception too, so next playEpisode recreates it
                                                val brokenPlayer2 = player
                                                player = null
                                                try { brokenPlayer2?.stop(); brokenPlayer2?.clearMediaItems() } catch (_: Exception) {}
                                                try { brokenPlayer2?.release() } catch (_: Exception) {}
                                                try { callback?.onError("播放重试失败: ${e.message ?: "未知错误"}") } catch (_: Exception) {}
                                            }
                                        }
                                    }
                                    retryRunnable = runnable
                                    try {
                                        retryHandler?.postDelayed(runnable, retryDelay)
                                    } catch (_: Exception) {}
                                } else {
                                    writeServiceLog("playback", "onPlayerError: max retries reached, releasing player for clean recovery")
                                    isRetrying = false
                                    playbackInitializing = false
                                    episodeSwitching = false
                                    prepared = false  // [v2.3.4] Reset prepared flag when player is released
                                    // Release player safely - post to avoid doing dangerous ops inside error callback
                                    val brokenPlayer = player
                                    player = null
                                    try {
                                        brokenPlayer?.stop()
                                        brokenPlayer?.clearMediaItems()
                                    } catch (_: Exception) {}
                                    try {
                                        brokenPlayer?.release()
                                    } catch (_: Exception) {}
                                    try { callback?.onError("播放失败: $errMsg") } catch (_: Exception) {}
                                }
                            } catch (e: Exception) {
                                // Ultimate safety: never let onPlayerError crash the app
                                Log.e(TAG, "onPlayerError internal error", e)
                                try { player = null } catch (_: Exception) {}
                            }
                        }
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            // seek 到记忆位置期间不通知 UI，避免播放/暂停循环抖动
                            if (isSeekingToPosition) return
                            // [v2.0.76] Issue 6 Fix: Ignore spurious isPlaying=true after pause intent.
                            // ExoPlayer briefly reports isPlaying=true ~400ms-1.5s after pause(), which would
                            // overwrite our authoritative PAUSED state. If userPaused or pausedByAudioFocus,
                            // ignore isPlaying=true and keep state as PAUSED.
                            val effectivePlaying = isPlaying && !userPaused && !pausedByAudioFocus
                            callback?.onStateChanged(effectivePlaying)
                            notificationPlaying = effectivePlaying
                            updateMediaSessionState()
                            // [v2.0.76] If effective playing state changed, force notification update to sync button state
                            if (effectivePlaying != (playbackStarted && !userPaused && !pausedByAudioFocus)) {
                                forceNotificationUpdate = true
                                // v2.4.117: Don't reset lastNotificationContentHash — force flag is sufficient
                                notifyNotification()
                            }
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
        // [v2.1.0] Use centralized cache dir
        val episodesDir = com.radio.app.RadioApplication.getEpisodesCacheDir(this@RadioPlaybackService)
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
            // [v2.4.61] 不再自动生成5分钟PCM文件
            // startPcmPreDecodeIfNeeded()
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
                // [v2.4.61] 不再自动生成5分钟PCM文件
                // startPcmPreDecodeIfNeeded()
            } catch (e: Exception) {
                Log.e(TAG, "Download error: ${e.message}")
                writeServiceLog("notification", "DELETING file: ${targetFile.absolutePath}, size=${targetFile.length()} (download error)")
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
     * v2.4.80: Fixed to use getLogDir() so logs are included in the log zip
     */
    private fun writePreCacheLog(msg: String) {
        try {
            // v2.4.80: Use getLogDir() instead of getExternalFilesDir("logs")
            // so precache logs are included in the collected log zip
            val logDir = java.io.File(com.radio.app.RadioApplication.getLogDir(this), "precache")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = File(logDir, "precache.log")
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            FileWriter(logFile, true).use { it.append("[$ts][v2.4.80] $msg\n") }
            Log.d(TAG, "[PreCache] $msg")
        } catch (_: Exception) {}
    }

    private fun triggerPreCache() {
        val now = System.currentTimeMillis()
        if (now - lastPreCacheCheckTime < 120_000) {
            // [v2.2.6] Throttle: don't re-check within 2 minutes (was 30s, too frequent)
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

        // [v2.2.6] Re-entrancy guard: prevent infinite recursion / concurrent pre-cache chains
        // Also prevent resetting precacheCompletedCount when already running
        if (isPrecaching) {
            Log.d(TAG, "Pre-cache: already running, skipping duplicate trigger")
            writeServiceLog("notification", "triggerPreCache: SKIP (already running, currentCount=$precacheCompletedCount)")
            return
        }

        // 标记预缓存开始，通知栏进度轮询将跳过更新
        precacheCompletedCount = 0
        isPrecaching = true
        // Reset days_fetched counter for new pre-cache cycle so fetchMoreDaysForPreCache
        // can fetch up to 20 fresh days each cycle
        getSharedPreferences("precache_list", MODE_PRIVATE).edit().putInt("days_fetched", 0).apply()
        writeServiceLog("notification", "triggerPreCache: starting pre-cache loop, isPrecaching=true")

        // [v2.1.0] Use centralized cache dir
        val episodesDir = com.radio.app.RadioApplication.getEpisodesCacheDir(this@RadioPlaybackService)
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

        writeServiceLog("notification", "triggerPreCache: preCacheList.size=${preCacheList.size}, currentIdx=$currentIdx, cachedFiles.size=${cachedFiles.size}")
        writeServiceLog("notification", "triggerPreCache: preCacheList episodes: ${preCacheList.map { "${it.id}:${it.title}" }.take(10)}")

        // Count future episodes that are already cached (after current index)
        var futureCachedCount = 0
        var nextToDownload: Episode? = null
        for (i in (currentIdx + 1) until preCacheList.size) {
            val ep = preCacheList[i]
            val fileName = extractCacheFileName(ep.audioUrl)
            val isDisliked = settings.isDisliked(ep.id) || settings.isDislikedByTitle(ep.stationId, ep.title)
            // v2.4.90: Skip episodes marked as "no preprocessing needed" for pre-cache
            val isNoPreprocess = settings.isNoPreprocess(ep.id ?: "")
            if (fileName in cachedNames) {
                futureCachedCount++
            } else if (!isDisliked && !isNoPreprocess && ep.audioUrl.isNotBlank() && nextToDownload == null) {
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

        // Issue 14: If no next episode to download in current list, keep fetching more days
        // until a downloadable episode is found or the max days limit is reached.
        // This prevents marking pre-cache as "complete" when only 7 out of 10 target files are cached.
        if (nextToDownload == null) {
            Log.d(TAG, "Pre-cache: no more future episodes in list, fetching more days (forward)")
            var fetchAttempts = 0
            val maxFetchAttempts = 20  // Safety limit to avoid infinite loops
            while (nextToDownload == null && fetchAttempts < maxFetchAttempts) {
                val listSizeBefore = preCacheList.size
                val expandedList = fetchMoreDaysForPreCache(preCacheList, cachedFiles)
                if (expandedList.size <= listSizeBefore) {
                    // fetchMoreDaysForPreCache returned no new episodes (max days reached or no episodes available)
                    Log.d(TAG, "Pre-cache: fetchMoreDaysForPreCache returned no new episodes, stopping fetch loop")
                    break
                }
                savePreCacheList(expandedList)
                preCacheList = expandedList
                // Update cachedNames to include any newly cached files
                val updatedCachedFiles = episodesDir.listFiles()?.filter { it.isFile && it.length() > 1024 } ?: emptyList()
                val updatedCachedNames = updatedCachedFiles.map { it.name }.toSet()
                // Re-find current index and look for next to download
                currentIdx = preCacheList.indexOfFirst { it.id == currentEp.id || it.audioUrl == currentEp.audioUrl }
                for (i in (currentIdx + 1) until preCacheList.size) {
                    val ep = preCacheList[i]
                    val fileName = extractCacheFileName(ep.audioUrl)
                    val isDisliked = settings.isDisliked(ep.id) || settings.isDislikedByTitle(ep.stationId, ep.title)
                    // v2.4.90: Skip episodes marked as "no preprocessing needed" for pre-cache
                    val isNoPreprocess = settings.isNoPreprocess(ep.id ?: "")
                    if (fileName !in updatedCachedNames && !isDisliked && !isNoPreprocess && ep.audioUrl.isNotBlank()) {
                        nextToDownload = ep
                        break
                    }
                }
                fetchAttempts++
            }
            // After fetching more days, re-count future cached episodes against targetCount
            if (nextToDownload == null) {
                futureCachedCount = 0
                for (i in (currentIdx + 1) until preCacheList.size) {
                    val ep = preCacheList[i]
                    val fileName = extractCacheFileName(ep.audioUrl)
                    if (fileName in cachedNames) {
                        futureCachedCount++
                    }
                }
                if (futureCachedCount >= targetCount) {
                    Log.d(TAG, "Pre-cache: target reached after fetching more days ($futureCachedCount >= $targetCount)")
                    writeServiceLog("notification", "triggerPreCache: target reached after expanding list, futureCached=$futureCachedCount, target=$targetCount")
                    isPrecaching = false
                    showPrecacheCompleteNotification()
                    return
                }
                // Issue 8 Fix: Not enough episodes AND can't find more to download.
                // Don't mark as complete and DON'T bother the user - just reset isPrecaching
                // and return silently. The pre-cache will retry on the next episode change.
                writeServiceLog("notification", "triggerPreCache: INSUFFICIENT episodes, futureCached=$futureCachedCount < target=$targetCount, will retry silently on next episode change")
                isPrecaching = false
                // Do NOT call showPrecacheCompleteNotification() - this is NOT complete!
                return
            }
        }

        if (nextToDownload != null) {
            Log.d(TAG, "Pre-cache: downloading: ${nextToDownload!!.title}")
            downloadPreCacheEpisode(nextToDownload!!)
            // isPrecaching stays true during the async download;
            // downloadPreCacheEpisode will set isPrecaching=false and post triggerPreCache()
            // on the main looper when done (success/failure/skip), which continues the chain
        } else {
            Log.d(TAG, "Pre-cache: no more future episodes available to download (futureCached=$futureCachedCount, target=$targetCount)")
            writeServiceLog("notification", "triggerPreCache: END, no more episodes to download, futureCached=$futureCachedCount, target=$targetCount")
            isPrecaching = false
            // Issue 8 Fix: Only show complete notification if we actually have enough
            // FUTURE cached files. When futureCachedCount < targetCount, return silently
            // (no Toast) - the pre-cache will retry on the next episode change.
            if (futureCachedCount >= targetCount) {
                showPrecacheCompleteNotification()
            } else {
                writeServiceLog("notification", "triggerPreCache: NOT showing complete notification, futureCached=$futureCachedCount < target=$targetCount, will retry silently")
            }
        }
    }

    /**
     * 预缓存完成后显示一次汇总通知（仅当有下载时）
     */
    private fun showPrecacheCompleteNotification() {
        val settings = AppSettings.getInstance(this)
        val targetCount = settings.preloadCacheCount
        // Issue 8 Fix: count only FUTURE cached episodes (after the current one), not ALL
        // files. Counting all files is wrong because it includes old episodes and the
        // currently playing one, so the guard passed even when no future episodes were
        // actually cached (causing this function to be reached with precacheCompletedCount=0).
        // [v2.1.0] Use centralized cache dir
        val episodesDir = com.radio.app.RadioApplication.getEpisodesCacheDir(this@RadioPlaybackService)
        val cachedFiles = episodesDir.listFiles()?.filter { it.isFile && it.length() > 1024 } ?: emptyList()
        val cachedNames = cachedFiles.map { it.name }.toSet()
        val preCacheList = loadPreCacheList()
        val currentEp = currentEpisode
        val currentIdx = if (currentEp != null)
            preCacheList.indexOfFirst { it.id == currentEp.id || it.audioUrl == currentEp.audioUrl } else -1
        val cachedCount = if (currentIdx >= 0) {
            var count = 0
            for (i in (currentIdx + 1) until preCacheList.size) {
                val ep = preCacheList[i]
                val fileName = extractCacheFileName(ep.audioUrl)
                if (fileName in cachedNames) count++
            }
            count
        } else {
            // Fallback: current episode not in list, count all files
            cachedFiles.size
        }
        writeServiceLog("notification", "showPrecacheCompleteNotification: called, futureCachedCount=$cachedCount, targetCount=$targetCount, precacheNotificationShown=$precacheNotificationShown")
        if (cachedCount < targetCount) {
            writeServiceLog("notification", "showPrecacheCompleteNotification: SKIPPED - futureCachedCount=$cachedCount < targetCount=$targetCount, NOT showing complete notification")
            return
        }
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
                val settings = AppSettings.getInstance(this)
                // v2.4.92: Do NOT exclude episodes whose audio is already cached.
                // Previously, `extractCacheFileName(ep.audioUrl) !in cachedNames` filtered them out,
                // which meant cached episodes were never added to the preCacheList, so the subtitle
                // patrol could never find them for subtitle generation. Now we only filter by URL
                // duplicates and disliked status — the download logic already skips cached files.
                val validNewEpisodes = newEpisodes.filter { ep ->
                    ep.audioUrl.isNotBlank() &&
                    ep.audioUrl !in existingUrls &&
                    ep.audioUrl.startsWith("http") &&
                    !settings.isDisliked(ep.id) && !settings.isDislikedByTitle(ep.stationId, ep.title)
                }
                writePreCacheLog("fetchMoreDaysForPreCache: got ${newEpisodes.size} episodes for $targetDate, ${validNewEpisodes.size} valid new")
                resultList.addAll(validNewEpisodes)
            } else {
                writePreCacheLog("fetchMoreDaysForPreCache: no episodes for $targetDate, trying URL construction")
                // Issue 7 Fix: 网络抓取失败时，根据已保存节目的时间段和 URL 模式构造节目，
                // 让 preCacheList 能持续增长，避免永远停留在 6 个节目。
                val savedList = loadEpisodeList()
                if (savedList.isNotEmpty()) {
                    // 从已保存节目中提取时间段（如 0700_0900）
                    val timeSlots = savedList.mapNotNull { ep ->
                        val url = ep.audioUrl ?: ""
                        val parts = url.substringAfterLast("/").substringBefore(".").split("_")
                        if (parts.size >= 4) "${parts[2]}_${parts[3]}" else null
                    }.distinct()
                    val newDateStr = targetDate.replace("-", "")
                    // 与 fetchCrossDayEpisode 保持一致：从已保存节目的 URL 推导 pathPrefix，
                    // 避免硬编码 base 路径在不同电台/路径下出错。
                    val sampleUrl = savedList.firstOrNull { !it.audioUrl.isNullOrBlank() }?.audioUrl ?: ""
                    val pathPrefix = if (sampleUrl.isNotBlank()) sampleUrl.substringBeforeLast("/").substringBeforeLast("/") else "https://new-file.hntv.tv/bdmz/data/new_record"
                    val stationPart = savedList.firstOrNull { !it.audioUrl.isNullOrBlank() }?.audioUrl?.substringAfterLast("/")?.substringBefore("_") ?: stationId
                    for ((slotIdx, slot) in timeSlots.withIndex()) {
                        val constructedUrl = "$pathPrefix/jmd_$newDateStr/${stationPart}_${newDateStr}_$slot.mp4"
                        if (constructedUrl !in existingUrls) {
                            // [v2.1.6] Use stationId (not stationPart) in episode.id to match API format
                            // This prevents duplicate PCM files (e.g., sijiache-20240712-0700 vs henan-private-car-2024-07-12-0)
                            val constructedEp = Episode(
                                id = "$stationId-$targetDate-$slotIdx",
                                title = savedList.firstOrNull {
                                    val parts = it.audioUrl?.substringAfterLast("/")?.substringBefore(".")?.split("_") ?: emptyList()
                                    parts.size >= 4 && "${parts[2]}_${parts[3]}" == slot
                                }?.title ?: "节目",
                                audioUrl = constructedUrl,
                                stationId = stationId,
                                broadcastAt = targetDate
                            )
                            resultList.add(constructedEp)
                            writePreCacheLog("fetchMoreDaysForPreCache: constructed episode: ${constructedEp.id}, url=$constructedUrl")
                        }
                    }
                }
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
        // [v2.0.43] Issue 2 Fix: 按 ID 或 audioUrl 找当前位置，找不到时返回 null 触发跨天获取
        // 之前的bug: curId不在列表时返回首/尾节目导致循环（跨天节目ID不在列表→回到第一天→再跨天→再回到第一天）
        var foundCurrent = false
        for (i in list.indices.reversed()) {
            val ep = list[i]
            if (ep.id == curId || ep.audioUrl == currentPlayingUrl) { foundCurrent = true; continue }
            if (foundCurrent && !settings.isDisliked(ep.id) && !settings.isDislikedByTitle(ep.stationId, ep.title)) {
                return ep
            }
        }
        if (!foundCurrent) {
            writeServiceLog("notification", "findPrevInList: curId=$curId not in list (size=${list.size}), returning null to trigger cross-day fetch")
        }
        return null
    }

    private fun findNextInList(list: List<Episode>, curId: String, settings: AppSettings): Episode? {
        // [v2.0.43] Issue 2 Fix: 按 ID 或 audioUrl 找当前位置，找不到时返回 null 触发跨天获取
        var foundCurrent = false
        for (ep in list) {
            if (!foundCurrent) {
                if (ep.id == curId || ep.audioUrl == currentPlayingUrl) foundCurrent = true
                continue
            }
            // v2.4.91: Skip no-preprocess episodes in continuous play
            if (!settings.isDisliked(ep.id) && !settings.isDislikedByTitle(ep.stationId, ep.title)
                && !settings.isNoPreprocess(ep.id ?: "")) {
                return ep
            }
        }
        if (!foundCurrent) {
            writeServiceLog("notification", "findNextInList: curId=$curId not in list (size=${list.size}), returning null to trigger cross-day fetch")
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
        // [v2.4.13] Also trigger subtitle patrol when episode changes (with delay to allow pre-cache list update)
        Handler(Looper.getMainLooper()).postDelayed({
            patrolSubtitleGeneration()
        }, 5_000)
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
        // [v2.1.0] Use centralized cache dir
        val episodesDir = com.radio.app.RadioApplication.getEpisodesCacheDir(this@RadioPlaybackService)
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
                // [v2.4.61] 不再自动生成5分钟PCM文件，手动生成字幕时再生成
                // startPcmPreDecode(episode.id ?: "", targetFile, episode.title ?: "unknown")
                // startPcmPreDecodeIfNeeded()
                // [Fix] Persist the episode's metadata (date/title) to the episode_info table
                // now that the background recording has been saved. RadioPlaybackService never
                // wrote episode_info before, so auto-started subtitle tasks found no date/title.
                // saveEpisodeInfo fills the current date and a default title if the pre-cache
                // episode came in without them.
                try {
                    com.radio.app.database.RadioDatabaseHelper.getInstance(this@RadioPlaybackService).saveEpisodeInfo(episode)
                } catch (e: Exception) {
                    Log.w(TAG, "Pre-cache: failed to save episode_info for ${episode.id}: ${e.message}")
                }
                // [v2.4.10] 预缓存完成后，自动生成字幕（使用Whisper base模型）
                startPreCacheSubtitleGeneration(episode)
                // Download complete, release guard and schedule next pre-cache check
                isPrecaching = false
                Handler(Looper.getMainLooper()).post { triggerPreCache() }
            } catch (e: Exception) {
                Log.e(TAG, "Pre-cache download error: ${e.message}")
                // 删除不完整的文件
                if (targetFile.exists()) {
                    writeServiceLog("notification", "DELETING file: ${targetFile.absolutePath}, size=${targetFile.length()} (pre-cache download error)")
                    targetFile.delete()
                }
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
                // [v2.1.0] Use centralized cache dir from RadioApplication
                val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this@RadioPlaybackService)
                if (!pcmCacheDir.exists()) pcmCacheDir.mkdirs()
                val pcmFile = File(pcmCacheDir, "${episodeId}_5min.pcm")
                if (pcmFile.exists() && pcmFile.length() > 1024) {
                    // Validate format - must have .info file with version=3 (original rate, no resampling)
                    val infoFile = File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".info")
                    if (infoFile.exists() && infoFile.readText().contains("version=3")) {
                        writePreCacheLog("startPcmPreDecode: PCM cache already exists for $episodeTitle, skipping")
                        return@launch
                    }
                    writePreCacheLog("startPcmPreDecode: PCM format outdated, regenerating")
                    writeServiceLog("notification", "DELETING file: ${pcmFile.absolutePath}, size=${pcmFile.length()} (PCM format outdated)")
                    pcmFile.delete()
                    writeServiceLog("notification", "DELETING file: ${infoFile.absolutePath}, size=${infoFile.length()} (PCM info outdated)")
                    infoFile.delete()
                } else if (pcmFile.exists()) {
                    writeServiceLog("notification", "DELETING file: ${pcmFile.absolutePath}, size=${pcmFile.length()} (PCM too small/invalid)")
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
                // [v2.0.72] PCM2 Fix: Pass ACTUAL audio duration, not capped at 5min.
                // The 5-minute window is handled internally by decodeToPcmForPreCache via stopAtUs.
                // Capping here caused bug: after seek to 15min, sampleTime(900M) >= durationUs(300M) → immediate EOS.
                writePreCacheLog("startPcmPreDecode: audio duration=${audioDurationUs / 1000000}s")

                // 解码到PCM
                decodeToPcmForPreCache(audioFile, pcmFile, audioDurationUs)

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
        // [v2.1.0] Use centralized cache dir
        val episodesDir = com.radio.app.RadioApplication.getEpisodesCacheDir(this@RadioPlaybackService)
        val audioFile = File(episodesDir, fileName)
        if (!audioFile.exists() || audioFile.length() <= 1024) {
            writePreCacheLog("startPcmPreDecodeIfNeeded: audio file not cached yet for ${episode.title} ($fileName)")
            return
        }
        // 检查PCM是否已解码
        // [v2.1.0] Use centralized cache dir from RadioApplication
        val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this@RadioPlaybackService)
        val pcmFile = File(pcmCacheDir, "${episodeId}_5min.pcm")
        if (pcmFile.exists() && pcmFile.length() > 1024) {
            // Check if existing PCM needs regeneration (format changed in v2.0.5: original rate, version=3)
            val infoFile = File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".info")
            if (infoFile.exists() && infoFile.readText().contains("version=3")) {
                writePreCacheLog("startPcmPreDecodeIfNeeded: PCM already exists for ${episode.title}, skipping")
                return
            }
            writePreCacheLog("startPcmPreDecodeIfNeeded: PCM format outdated, regenerating")
            writeServiceLog("notification", "DELETING file: ${pcmFile.absolutePath}, size=${pcmFile.length()} (PCM format outdated, pre-decode-if-needed)")
            pcmFile.delete()
            writeServiceLog("notification", "DELETING file: ${infoFile.absolutePath}, size=${infoFile.length()} (PCM info outdated, pre-decode-if-needed)")
            infoFile.delete()
        }
        writePreCacheLog("startPcmPreDecodeIfNeeded: triggering PCM pre-decode from normal cache for ${episode.title}")
        startPcmPreDecode(episodeId, audioFile, episode.title ?: "unknown")
    }

    /**
     * v2.4.61: 手动触发当前节目的5分钟PCM预解码（手动生成字幕时调用）。
     * 与 startPcmPreDecodeIfNeeded 不同，此方法忽略 enablePreprocessing 开关，
     * 因为用户明确请求了字幕生成，需要准备PCM数据。
     */
    fun requestManualPcmPreDecode() {
        val episode = currentEpisode ?: run {
            writePreCacheLog("requestManualPcmPreDecode: no current episode, skipping")
            return
        }
        val episodeId = episode.id
        if (episodeId.isNullOrBlank()) {
            writePreCacheLog("requestManualPcmPreDecode: no episode id, skipping")
            return
        }
        val url = currentStreamUrl
        if (url.isBlank()) {
            writePreCacheLog("requestManualPcmPreDecode: no stream URL, skipping")
            return
        }
        val fileName = extractCacheFileName(url)
        val episodesDir = com.radio.app.RadioApplication.getEpisodesCacheDir(this@RadioPlaybackService)
        val audioFile = File(episodesDir, fileName)
        if (!audioFile.exists() || audioFile.length() <= 1024) {
            writePreCacheLog("requestManualPcmPreDecode: audio file not cached yet for ${episode.title} ($fileName), PCM will be generated during subtitle processing")
            return
        }
        writePreCacheLog("requestManualPcmPreDecode: triggering manual PCM pre-decode for ${episode.title}")
        startPcmPreDecode(episodeId, audioFile, episode.title ?: "unknown")
    }

    /**
     * v2.4.10: 预缓存完成后自动生成字幕
     * 使用Whisper base模型，预生成字幕的节目数取自设置中的预缓存节目个数
     */
    private fun startPreCacheSubtitleGeneration(episode: Episode) {
        val appSettings = AppSettings.getInstance(this)
        // v2.4.96: Check independent pre-generate subtitles toggle
        if (!appSettings.enablePreGenerateSubtitles) {
            writePreCacheLog("startPreCacheSubtitleGeneration: pre-generate subtitles disabled, skipping")
            return
        }
        val episodeId = episode.id
        if (episodeId.isNullOrBlank()) {
            writePreCacheLog("startPreCacheSubtitleGeneration: empty episodeId, skipping")
            return
        }
        // [v2.4.14] Skip episodes marked as "no preprocessing needed"
        if (appSettings.isNoPreprocess(episodeId)) {
            writePreCacheLog("startPreCacheSubtitleGeneration: [v2.4.14] episode $episodeId marked as no-preprocess, skipping")
            return
        }
        // v2.4.83: Skip episodes that are disliked
        if (appSettings.isDisliked(episodeId)) {
            writePreCacheLog("startPreCacheSubtitleGeneration: [v2.4.83] episode $episodeId is disliked, skipping")
            return
        }
        if (!episode.title.isNullOrBlank() && appSettings.isDislikedByTitle(episode.stationId, episode.title)) {
            writePreCacheLog("startPreCacheSubtitleGeneration: [v2.4.83] episode $episodeId title='${episode.title}' is disliked, skipping")
            return
        }
        val audioUrl = episode.audioUrl
        if (audioUrl.isNullOrBlank()) {
            writePreCacheLog("startPreCacheSubtitleGeneration: empty audioUrl, skipping")
            return
        }

        // [v2.4.18] Check if subtitles are COMPLETE (not just existing) — incomplete subtitles need regeneration
        try {
            val dbHelper = com.radio.app.database.RadioDatabaseHelper.getInstance(this)
            if (dbHelper.hasCompleteSubtitles(episodeId)) {
                // v2.4.83: Log detailed info about why it's being skipped
                val segmentCount = try { dbHelper.getSubtitleSegmentCount(episodeId) } catch (_: Exception) { -1 }
                val audioFile = File(getExternalFilesDir("episodes"), extractCacheFileName(audioUrl))
                writePreCacheLog("startPreCacheSubtitleGeneration: [v2.4.83] SKIP $episodeId: complete subtitles exist (segments=$segmentCount, audioCached=${audioFile.exists()}, audioSize=${if (audioFile.exists()) audioFile.length() else 0})")
                return
            }
        } catch (e: Exception) {
            writePreCacheLog("startPreCacheSubtitleGeneration: error checking subtitle status: ${e.message}")
        }

        writePreCacheLog("startPreCacheSubtitleGeneration: triggering subtitle generation for ${episode.title} ($episodeId)")

        // v2.4.73: Write episode_info to database BEFORE starting subtitle generation.
        // Previously, episode_info was only created in ensureEpisodeInfo() during subtitle
        // generation, which was too late — the notification showed "广播节目录音" placeholder.
        // Now we save it here, using the title from the Episode object (from radio schedule API).
        try {
            val dbHelper = com.radio.app.database.RadioDatabaseHelper.getInstance(this)
            val existing = dbHelper.getEpisodeInfo(episodeId)
            if (existing == null) {
                // No episode_info row — create one with the title from the Episode object
                val ep = com.radio.app.models.Episode().apply {
                    this.id = episodeId
                    this.title = episode.title.ifBlank { episodeId }
                    this.broadcastAt = episode.broadcastAt
                    this.audioUrl = audioUrl
                }
                dbHelper.saveEpisodeInfo(ep)
                writePreCacheLog("startPreCacheSubtitleGeneration: [v2.4.73] saved episode_info (title=${episode.title})")
            } else if (existing.title.isNullOrBlank()) {
                // Row exists but title is empty — fill it in
                existing.title = episode.title.ifBlank { episodeId }
                dbHelper.saveEpisodeInfo(existing)
                writePreCacheLog("startPreCacheSubtitleGeneration: [v2.4.73] filled missing title=${episode.title}")
            }
        } catch (e: Exception) {
            writePreCacheLog("startPreCacheSubtitleGeneration: [v2.4.73] error saving episode_info: ${e.message}")
        }

        // v2.4.91: Pre-segment with fixed 15-minute segments before subtitle generation
        // This gives immediate segment count in the episode list
        try {
            val epDuration = episode.duration?.let { if (it in 60..100000) it * 1000 else 0 } ?: 0
            val durationMs = if (epDuration > 60000) epDuration.toLong() else 7200_000L // default 2h
            com.radio.app.utils.SegmentGenerator.preSegmentFixed(this, episodeId, durationMs)
        } catch (e: Exception) {
            writePreCacheLog("startPreCacheSubtitleGeneration: pre-segment failed: ${e.message}")
        }

        // 发送Intent启动SubtitleGeneratorService，使用"precache_subtitle"作为任务类型
        // 添加extra标记force_whisper_base=true，让SubtitleGeneratorService使用Whisper base模型
        val subtitleIntent = android.content.Intent(this, com.radio.app.services.SubtitleGeneratorService::class.java).apply {
            putExtra("episode_id", episodeId)
            putExtra("audio_url", audioUrl)
            putExtra("task_type", "subtitle")
            putExtra("precache_subtitle", true)
            putExtra("force_whisper_base", true)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(subtitleIntent)
            } else {
                startService(subtitleIntent)
            }
            writePreCacheLog("startPreCacheSubtitleGeneration: subtitle service started for $episodeId")
        } catch (e: Exception) {
            writePreCacheLog("startPreCacheSubtitleGeneration: failed to start subtitle service: ${e.message}")
            Log.e(TAG, "Pre-cache subtitle generation failed to start: ${e.message}")
        }
    }

    /**
     * v2.4.13: Subtitle Patrol - scan cached episodes after current one and auto-generate
     * subtitles for episodes that are cached but don't have subtitles yet.
     * Only runs when subtitle generation service is idle (not busy with other tasks).
     */
    private fun patrolSubtitleGeneration() {
        val appSettings = AppSettings.getInstance(this)
        // v2.4.96: Check independent pre-generate subtitles toggle
        if (!appSettings.enablePreGenerateSubtitles) {
            writePreCacheLog("patrolSubtitle: [v2.4.96] pre-generate subtitles disabled, skipping patrol")
            return
        }
        // [v2.4.13] Check if subtitle service is busy (cross-process via flag file)
        val busyFlag = java.io.File(
            android.os.Environment.getExternalStorageDirectory(),
            "RadioApp/subtitle_service_busy.flag"
        )
        if (busyFlag.exists()) {
            // v2.4.65: Reduced stale timeout from 15min to 2min.
            // With heartbeat (flag timestamp updated every chunk), a live task updates the flag
            // every ~15-30 seconds. If no update for 2 minutes, the service is definitely dead.
            val flagAge = System.currentTimeMillis() - busyFlag.lastModified()
            if (flagAge > 2 * 60 * 1000L) {
                writePreCacheLog("patrolSubtitle: [v2.4.65] stale busy flag detected (age=${flagAge/1000}s > 2min), deleting and continuing patrol")
                busyFlag.delete()
            } else {
                writePreCacheLog("patrolSubtitle: [v2.4.59] subtitle service is busy (flag age=${flagAge/1000}s), skipping patrol")
                return
            }
        }
        // v2.4.59: Check if subtitle service is already running (component check)
        val subtitleRunning = try {
            val mgr = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            mgr.getRunningServices(100)?.any { it.service.className == "com.radio.app.services.SubtitleGeneratorService" } ?: false
        } catch (_: Exception) { false }
        if (subtitleRunning) {
            writePreCacheLog("patrolSubtitle: [v2.4.59] SubtitleGeneratorService is running, skipping patrol")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentEp = currentEpisode ?: return@launch
                if (currentEp.id.isNullOrBlank()) return@launch

                writePreCacheLog("patrolSubtitle: [v2.4.59] patrol started, currentEp=${currentEp.title}")

                // Load pre-cache list and find episodes after current one
                val preCacheList = loadPreCacheList()
                var currentIdx = preCacheList.indexOfFirst { it.id == currentEp.id }
                if (currentIdx < 0) {
                    currentIdx = preCacheList.indexOfFirst { it.audioUrl == currentEp.audioUrl }
                }
                if (currentIdx < 0) {
                    writePreCacheLog("patrolSubtitle: [v2.4.19] current episode not in preCacheList, scanning all episodes")
                    currentIdx = -1  // [v2.4.19] Scan all episodes if current not found
                }

                val episodesDir = com.radio.app.RadioApplication.getEpisodesCacheDir(this@RadioPlaybackService)
                val dbHelper = com.radio.app.database.RadioDatabaseHelper.getInstance(this@RadioPlaybackService)
                val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this@RadioPlaybackService)
                val cachedNames = episodesDir.listFiles()
                    ?.filter { it.isFile && it.length() > 1024 }
                    ?.map { it.name }?.toSet() ?: emptySet()
                val settings = AppSettings.getInstance(this@RadioPlaybackService)

                // v2.4.93: Only scan episodes AFTER the current one.
                // Previously also scanned episodes before current — this wasted resources
                // processing past episodes that the user has already moved past.
                val scanOrder = if (currentIdx >= 0) {
                    ((currentIdx + 1) until preCacheList.size).toList()
                } else {
                    writePreCacheLog("patrolSubtitle: [v2.4.93] current episode not in preCacheList, cannot determine position — skipping patrol")
                    emptyList()
                }

                // [v2.4.81] Better patrol logging: count what was scanned
                var totalScanned = 0
                var withAudio = 0
                var withSubtitles = 0
                var withoutAudio = 0

                // [v2.4.19] Also check for leftover _full.pcm (interrupted generation) - prioritize these
                for (i in scanOrder) {
                    val ep = preCacheList[i]
                    if (ep.id.isNullOrBlank() || ep.audioUrl.isBlank()) continue
                    totalScanned++

                    // Check if audio is cached
                    val fileName = extractCacheFileName(ep.audioUrl)
                    if (fileName !in cachedNames) {
                        withoutAudio++
                        writePreCacheLog("patrolSubtitle: [v2.4.82] SKIP ep=${ep.id}, audio NOT cached (expected: $fileName)")
                        continue
                    }
                    withAudio++
                    writePreCacheLog("patrolSubtitle: [v2.4.82] ep=${ep.id}, audio cached ($fileName), checking subtitles...")

                    // [v2.4.14] Skip episodes marked as "no preprocessing needed"
                    if (settings.isNoPreprocess(ep.id)) continue

                    // v2.4.83: Skip episodes that are disliked
                    if (settings.isDisliked(ep.id)) {
                        writePreCacheLog("patrolSubtitle: [v2.4.83] SKIP ep=${ep.id}, disliked by ID")
                        continue
                    }
                    // v2.4.83: Also check by title (stationId + title)
                    if (!ep.title.isNullOrBlank() && settings.isDislikedByTitle(ep.stationId, ep.title)) {
                        writePreCacheLog("patrolSubtitle: [v2.4.83] SKIP ep=${ep.id}, disliked by title: ${ep.title}")
                        continue
                    }

                    // [v2.4.18] Check if subtitles are COMPLETE (not just existing)
                    // [v2.4.19] Wrap in try-catch to prevent patrol abort on DB errors
                    var isComplete = false
                    try {
                        isComplete = dbHelper.hasCompleteSubtitles(ep.id)
                    } catch (e: Exception) {
                        writePreCacheLog("patrolSubtitle: [v2.4.19] hasCompleteSubtitles failed for ${ep.id}: ${e.message}, treating as incomplete")
                    }
                    if (isComplete) {
                        withSubtitles++
                        // v2.4.91: Auto-generate keyword-based segments after subtitles complete
                        try {
                            val existingSegs = dbHelper.getVoiceSegments(ep.id)
                            val hasRealSegs = existingSegs.any { !it.isSimulated }
                            if (!hasRealSegs) {
                                val epDuration = ep.duration?.let { if (it in 60..100000) it * 1000 else 0 } ?: 0
                                val durMs = if (epDuration > 60000) epDuration.toLong() else 7200_000L
                                writePreCacheLog("patrolSubtitle: [v2.4.91] subtitles complete but no real segments, auto-segmenting ${ep.id}")
                                com.radio.app.utils.SegmentGenerator.postSegmentKeyword(this@RadioPlaybackService, ep.id, durMs)
                            }
                        } catch (e: Exception) {
                            writePreCacheLog("patrolSubtitle: [v2.4.91] auto-segment failed for ${ep.id}: ${e.message}")
                        }
                        continue
                    }

                    // [v2.4.14] Check if there's a leftover _full.pcm (interrupted generation)
                    // If so, this episode needs resume — prioritize it
                    val fullPcmFile = java.io.File(pcmCacheDir, "${ep.id}_full.pcm")
                    if (fullPcmFile.exists() && fullPcmFile.length() > 1024 * 100) {
                        writePreCacheLog("patrolSubtitle: [v2.4.14] found leftover full PCM for ${ep.id}, resuming subtitle generation")
                        startPreCacheSubtitleGeneration(ep)
                        return@launch
                    }

                    // Found a cached episode without subtitles — trigger subtitle generation
                    writePreCacheLog("patrolSubtitle: [v2.4.13] found cached episode without subtitles: ${ep.title} (${ep.id}), triggering generation")
                    startPreCacheSubtitleGeneration(ep)
                    // v2.4.80: Show notification when patrol finds episode to process
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                            if (nm.getNotificationChannel("subtitle_patrol_channel") == null) {
                                nm.createNotificationChannel(NotificationChannel("subtitle_patrol_channel", "预生成字幕", NotificationManager.IMPORTANCE_LOW))
                            }
                        }
                        val notif = NotificationCompat.Builder(this@RadioPlaybackService, "subtitle_patrol_channel")
                            .setSmallIcon(android.R.drawable.ic_media_ff)
                            .setContentTitle("预生成字幕")
                            .setContentText("正在为 ${ep.title ?: ep.id} 生成字幕...")
                            .setAutoCancel(true)
                            .build()
                        val notifManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        notifManager.notify(2001, notif)
                    } catch (_: Exception) {}
                    return@launch  // Only generate one at a time; next patrol will pick up the next one
                }

                writePreCacheLog("patrolSubtitle: [v2.4.93] patrol complete (scanned=$totalScanned, withAudio=$withAudio, withSubtitles=$withSubtitles, withoutAudio=$withoutAudio)")

                // v2.4.93: Only process episodes AFTER the current one.
                // Parse current episode's date for comparison.
                val currentDateStr = currentEp.broadcastAt?.take(10) ?: run {
                    // Fallback: parse from URL (e.g., sijiache_20240806_0700_0900.mp4)
                    val urlMatch = Regex("(\\d{4})(\\d{2})(\\d{2})").find(currentEp.audioUrl ?: "")
                    if (urlMatch != null) "${urlMatch.groupValues[1]}-${urlMatch.groupValues[2]}-${urlMatch.groupValues[3]}"
                    else ""
                }
                writePreCacheLog("patrolSubtitle: [v2.4.93] current episode date=$currentDateStr, only processing episodes after this date")

                // v2.4.92: Fallback — scan episodes directory for cached files NOT in preCacheList.
                // v2.4.93: Restricted to only files dated >= current episode's date.
                val preCacheFileNames = mutableSetOf<String>()
                for (ep in preCacheList) {
                    if (ep.audioUrl.isNotBlank()) {
                        preCacheFileNames.add(extractCacheFileName(ep.audioUrl))
                    }
                }
                var orphanFound = false
                for (cachedName in cachedNames) {
                    if (cachedName in preCacheFileNames) continue
                    // v2.4.93: Parse date from filename, skip if before current episode's date
                    val fileDateMatch = Regex("(\\d{4})(\\d{2})(\\d{2})").find(cachedName)
                    val fileDateStr = if (fileDateMatch != null) {
                        "${fileDateMatch.groupValues[1]}-${fileDateMatch.groupValues[2]}-${fileDateMatch.groupValues[3]}"
                    } else ""
                    if (currentDateStr.isNotBlank() && fileDateStr.isNotBlank() && fileDateStr < currentDateStr) {
                        writePreCacheLog("patrolSubtitle: [v2.4.93] ORPHAN SKIP $cachedName: date $fileDateStr < current $currentDateStr (past episode)")
                        continue
                    }
                    // Try to find episode in DB by audio filename
                    val dbEp = try { dbHelper.getEpisodeByAudioFileName(cachedName) } catch (_: Exception) { null }
                    val episodeId = dbEp?.id ?: cachedName.substringBeforeLast(".")
                    // Skip if already has complete subtitles
                    val isComplete = try { dbHelper.hasCompleteSubtitles(episodeId) } catch (_: Exception) { false }
                    if (isComplete) {
                        writePreCacheLog("patrolSubtitle: [v2.4.92] ORPHAN SKIP $cachedName: subtitles already complete (id=$episodeId)")
                        continue
                    }
                    // Skip if noPreprocess or disliked
                    if (settings.isNoPreprocess(episodeId)) {
                        writePreCacheLog("patrolSubtitle: [v2.4.92] ORPHAN SKIP $cachedName: noPreprocess (id=$episodeId)")
                        continue
                    }
                    if (settings.isDisliked(episodeId)) {
                        writePreCacheLog("patrolSubtitle: [v2.4.92] ORPHAN SKIP $cachedName: disliked (id=$episodeId)")
                        continue
                    }
                    // Construct Episode and trigger subtitle generation
                    val stationId = dbEp?.stationId ?: cachedName.substringBefore("_")
                    val episodeTitle = dbEp?.title ?: cachedName.substringBeforeLast(".")
                    val audioUrl = dbEp?.audioUrl ?: "https://placeholder/$cachedName"
                    writePreCacheLog("patrolSubtitle: [v2.4.92] ORPHAN FOUND: $cachedName (date=$fileDateStr) has no subtitles (id=$episodeId), triggering generation")
                    val orphanEp = Episode(
                        id = episodeId,
                        title = episodeTitle,
                        audioUrl = audioUrl,
                        stationId = stationId,
                        stationName = dbEp?.stationName ?: "",
                        duration = dbEp?.duration ?: 0,
                        broadcastAt = dbEp?.broadcastAt ?: ""
                    )
                    startPreCacheSubtitleGeneration(orphanEp)
                    orphanFound = true
                    break  // Only generate one at a time
                }
                if (orphanFound) return@launch

                writePreCacheLog("patrolSubtitle: [v2.4.93] no future orphaned files found, all done")

                // v2.4.82: If there are episodes without audio, trigger pre-cache download
                if (withoutAudio > 0 && !isPrecaching) {
                    writePreCacheLog("patrolSubtitle: [v2.4.82] found $withoutAudio episodes without audio, triggering pre-cache download")
                    try {
                        serviceScope.launch { triggerPreCache() }
                    } catch (_: Exception) {}
                }
                // v2.4.92: Clear notification text — explain what was done and what will happen next
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        if (nm.getNotificationChannel("subtitle_patrol_channel") == null) {
                            nm.createNotificationChannel(NotificationChannel("subtitle_patrol_channel", "预生成字幕", NotificationManager.IMPORTANCE_LOW))
                        }
                    }
                    val notifText = when {
                        withoutAudio > 0 && withSubtitles > 0 ->
                            "字幕已完成${withSubtitles}集，剩余${withoutAudio}集将自动下载音频并生成字幕"
                        withoutAudio > 0 && withSubtitles == 0 ->
                            "${withoutAudio}集待下载音频后自动生成字幕"
                        withoutAudio == 0 && withSubtitles > 0 ->
                            "字幕已全部完成（${withSubtitles}集）"
                        else -> "暂无待处理节目"
                    }
                    val notif = NotificationCompat.Builder(this@RadioPlaybackService, "subtitle_patrol_channel")
                        .setSmallIcon(android.R.drawable.ic_media_ff)
                        .setContentTitle("预生成字幕")
                        .setContentText(notifText)
                        .setAutoCancel(true)
                        .build()
                    val notifManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    notifManager.notify(2002, notif)
                } catch (_: Exception) {}
            } catch (e: Exception) {
                writePreCacheLog("patrolSubtitle: [v2.4.13] patrol failed: ${e.message}")
            }
        }
    }

    /**
     * v2.0.72 Issue 6 Fix: PCM Patrol - scan cached audio files and auto-generate
     * missing/invalid PCM files. This addresses cases where episodes were cached
     * (downloaded) but PCM preparation failed (e.g., bug PCM1 in v2.0.67-71 produced
     * 0-byte PCM files). Runs on episode change and service start.
     */
    private fun patrolAndRepairPcmFiles() {
        val appSettings = AppSettings.getInstance(this)
        if (!appSettings.enablePreprocessing) {
            writePreCacheLog("patrolPcm: preprocessing disabled, skipping patrol")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // [v2.1.0] Use centralized cache dirs from RadioApplication
                val episodesDir = com.radio.app.RadioApplication.getEpisodesCacheDir(this@RadioPlaybackService)
                val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(this@RadioPlaybackService)
                if (!episodesDir.exists() || !pcmCacheDir.exists()) {
                    writePreCacheLog("patrolPcm: directories missing (episodes=${episodesDir.exists()}, pcm=${pcmCacheDir.exists()})")
                    return@launch
                }

                // Use preCacheList + current episode to find episodes with cached audio but missing PCM
                val episodesToCheck = mutableListOf<Episode>()
                currentEpisode?.let { episodesToCheck.add(it) }
                episodesToCheck.addAll(loadPreCacheList())

                val minValidPcmBytes = 1024 * 500  // At least 500KB (about 15s @16kHz mono)
                var repaired = 0
                var alreadyOk = 0
                var skipped = 0

                // Deduplicate by ID
                val checkedIds = mutableSetOf<String>()
                for (ep in episodesToCheck) {
                    if (ep.id.isNullOrBlank() || ep.id in checkedIds) continue
                    checkedIds.add(ep.id)
                    try {
                        val fileName = extractCacheFileName(ep.audioUrl)
                        val audioFile = File(episodesDir, fileName)
                        if (!audioFile.exists() || audioFile.length() <= 1024 * 100) {
                            skipped++
                            continue
                        }

                        val pcmFile = File(pcmCacheDir, "${ep.id}_5min.pcm")
                        val infoFile = File(pcmCacheDir, "${ep.id}_5min.info")

                        val pcmValid = pcmFile.exists() && pcmFile.length() > minValidPcmBytes
                        val infoValid = infoFile.exists() && infoFile.readText().contains("version=3")

                        if (pcmValid && infoValid) {
                            alreadyOk++
                            continue
                        }

                        // Delete invalid files
                        if (pcmFile.exists() && pcmFile.length() <= minValidPcmBytes) {
                            writePreCacheLog("patrolPcm: deleting invalid PCM: ${pcmFile.name} (${pcmFile.length()} bytes)")
                            pcmFile.delete()
                        }
                        if (infoFile.exists() && !infoFile.readText().contains("version=3")) {
                            infoFile.delete()
                        }

                        // Regenerate PCM
                        writePreCacheLog("patrolPcm: REPAIRING PCM for episode=${ep.id}, audio=${audioFile.name} (${audioFile.length()/1024/1024}MB)")
                        // Get actual audio duration
                        val audioDuration = try {
                            val de = MediaExtractor()
                            de.setDataSource(audioFile.absolutePath)
                            var dur = 0L
                            for (i in 0 until de.trackCount) {
                                val fmt = de.getTrackFormat(i)
                                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                                    dur = fmt.getLong(MediaFormat.KEY_DURATION)
                                    break
                                }
                            }
                            de.release()
                            dur
                        } catch (e: Exception) { 0L }
                        decodeToPcmForPreCache(audioFile, pcmFile, audioDuration)
                        if (pcmFile.exists() && pcmFile.length() > minValidPcmBytes) {
                            repaired++
                            writePreCacheLog("patrolPcm: successfully repaired PCM for ${ep.id} (${pcmFile.length()/1024}KB)")
                        } else {
                            writePreCacheLog("patrolPcm: FAILED to repair PCM for ${ep.id}")
                        }
                    } catch (e: Exception) {
                        writePreCacheLog("patrolPcm: error processing ${ep.id}: ${e.message}")
                    }
                }
                writePreCacheLog("patrolPcm: complete - $repaired repaired, $alreadyOk OK, $skipped skipped (no cached audio), total checked=${checkedIds.size}")
            } catch (e: Exception) {
                writePreCacheLog("patrolPcm: patrol failed: ${e.message}")
            }
        }
    }

    /**
     * v2.1.1: Continuous resampling with cross-chunk phase preservation.
     * Solves the "low-pitched and slow" audio bug by:
     * 1. Maintaining resample phase across MediaCodec chunk boundaries (no periodic clicks)
     * 2. Using fractional position accumulation (no floor-truncation timing drift)
     * 3. Carrying last sample from previous chunk for boundary interpolation
     *
     * Returns: Triple(outputBytes, newPhase, lastSample)
     */
    private fun resampleChunkContinuous(
        input: ShortArray, inSampleRate: Int, inChannels: Int,
        outSampleRate: Int, outChannels: Int,
        prevPhase: Double, prevLastSample: Short
    ): Triple<ByteArray, Double, Short> {
        // No resampling needed
        if (inSampleRate == outSampleRate && inChannels == outChannels) {
            val bytes = ByteArray(input.size * 2)
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(input)
            return Triple(bytes, 0.0, if (input.isNotEmpty()) input[input.size - 1] else prevLastSample)
        }

        val ratio = inSampleRate.toDouble() / outSampleRate
        val inputFrames = input.size / inChannels

        // Step 1: Downmix to mono (average all channels per frame)
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

        if (monoInput.size < 1) {
            return Triple(ByteArray(0), prevPhase, prevLastSample)
        }

        // Step 2: Continuous linear interpolation with phase carry-over
        // prevPhase is the fractional position within the PREVIOUS chunk's last frame.
        // We prepend prevLastSample to the input to allow interpolation at chunk start.
        val extendedInput = ShortArray(monoInput.size + 1)
        extendedInput[0] = prevLastSample
        System.arraycopy(monoInput, 0, extendedInput, 1, monoInput.size)

        // Calculate how many output samples we can produce
        // Available input range: [0, extendedInput.size - 1] for interpolation
        val availableInputRange = extendedInput.size - 1  // need srcIdx+1 < size
        // Start from prevPhase (carried from previous chunk)
        var currentPhase = prevPhase
        val outputSamples = ArrayList<Short>(512)

        while (currentPhase < availableInputRange) {
            val srcIdx = currentPhase.toInt()
            val frac = currentPhase - srcIdx
            if (srcIdx + 1 < extendedInput.size) {
                val sample = (extendedInput[srcIdx] * (1.0 - frac) + extendedInput[srcIdx + 1] * frac).toInt().toShort()
                outputSamples.add(sample)
            }
            currentPhase += ratio
        }

        // Carry over the phase (subtract consumed input range)
        val newPhase = currentPhase - availableInputRange
        val newLastSample = monoInput[monoInput.size - 1]

        // Step 3: Output is always mono (outChannels=1), no channel duplication needed
        val outShorts = outputSamples.toShortArray()
        val outBytes = ByteArray(outShorts.size * 2)
        java.nio.ByteBuffer.wrap(outBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outShorts)
        return Triple(outBytes, newPhase, newLastSample)
    }

    /**
     * v2.1.0: Generate WAV file from PCM raw data.
     * WAV header: 44 bytes RIFF header + PCM data.
     */
    private fun generateWavFromPcm(pcmFile: File, sampleRate: Int, channels: Int) {
        try {
            val wavFile = File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".wav")
            val pcmData = pcmFile.readBytes()
            if (pcmData.isEmpty()) {
                writePreCacheLog("generateWavFromPcm: PCM empty, skipping WAV generation")
                return
            }
            val dataLen = pcmData.size
            val wavHeader = java.io.ByteArrayOutputStream(44)
            val bb = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray())                         // ChunkID
            bb.putInt(36 + dataLen)                              // ChunkSize
            bb.put("WAVE".toByteArray())                         // Format
            bb.put("fmt ".toByteArray())                         // Subchunk1ID
            bb.putInt(16)                                       // Subchunk1Size (PCM)
            bb.putShort(1)                                      // AudioFormat (PCM=1)
            bb.putShort(channels.toShort())                     // NumChannels
            bb.putInt(sampleRate)                                // SampleRate
            bb.putInt(sampleRate * channels * 2)                 // ByteRate
            bb.putShort((channels * 2).toShort())               // BlockAlign
            bb.putShort(16)                                     // BitsPerSample
            bb.put("data".toByteArray())                        // Subchunk2ID
            bb.putInt(dataLen)                                  // Subchunk2Size
            wavFile.outputStream().use { out ->
                out.write(bb.array())
                out.write(pcmData)
            }
            writePreCacheLog("generateWavFromPcm: created ${wavFile.name} (${wavFile.length()} bytes, ${sampleRate}Hz, ${channels}ch)")
        } catch (e: Exception) {
            writePreCacheLog("generateWavFromPcm: error: ${e.message}")
        }
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
            // [v2.0.97] Force 16kHz mono output to match SubtitleGeneratorService.decodeToPcm.
            val outSampleRate = 16000
            val outChannels = 1
            // [v2.1.1] Mutable: will be updated on INFO_OUTPUT_FORMAT_CHANGED
            var actualInSampleRate = sampleRate
            var actualInChannels = channelCount
            writePreCacheLog("decodeToPcmForPreCache: [v2.0.97] sampleRate=$sampleRate→${outSampleRate}, channels=$channelCount→${outChannels} (resampling to 16kHz mono)")

            // [v2.0.70] Issue 6 Fix: Seek to 15-min offset to match subtitle service's expected range (15-20 min).
            // Previously decoded from 0 min, but subtitle service adds 15-min offset to timestamps,
            // causing mismatch between audio content and displayed timestamps.
            val seekTargetUs = 15L * 60 * 1000 * 1000  // 15 minutes in microseconds
            val didSeek = if (durationUs <= 0 || durationUs > seekTargetUs) {
                // [v2.0.72] PCM2 Fix: Seek when duration unknown OR longer than 15min.
                // Previously didn't seek when durationUs=0 (unknown), causing 0-5min decode instead of 15-20min.
                extractor.seekTo(seekTargetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                writePreCacheLog("decodeToPcmForPreCache: [v2.0.72] seeked to 15min offset, duration=${durationUs / 1000000}s")
                true
            } else {
                writePreCacheLog("decodeToPcmForPreCache: [v2.0.72] audio shorter than 15min (${durationUs/1000000}s), decoding from start")
                false
            }

            fos = FileOutputStream(pcmFile)
            val bufferInfo = MediaCodec.BufferInfo()

            var inputDone = false
            var outputDone = false
            var decodedBytes = 0L
            var resampledBytes = 0L
            val maxPcmBytes = 5L * 60 * outSampleRate * outChannels * 2  // 5min at actual rate
            val decodeStartTime = System.currentTimeMillis()
            val maxDecodeTimeMs = 5 * 60 * 1000L

            // [v2.1.1] Global continuous resampler state - maintains phase across chunks
            // Previous per-chunk resampling caused periodic clicks (~43Hz buzz) and
            // floor-truncation timing errors, making audio sound "low-pitched and slow".
            var resamplePhase = 0.0  // accumulated output position in input-sample units
            var lastSample: Short = 0  // last sample from previous chunk for interpolation

            // [v2.0.70] Track the seek offset for stopping at 20 min
            val stopAtUs = seekTargetUs + 5L * 60 * 1000 * 1000  // 20 min

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
                            val currentSampleTime = extractor.sampleTime
                            if (currentSampleTime >= stopAtUs) {
                                // [v2.0.72] PCM2 Fix: Use stopAtUs (absolute 20min) as primary stop condition.
                                // Previously also checked `sampleTime >= durationUs` which was buggy when
                                // durationUs was capped at 5min but we seeked to 15min (900M >= 300M = true immediately).
                                codec.queueInputBuffer(inIdx, 0, sampleSize, currentSampleTime, 0)
                                extractor.advance()
                                val eosIdx = codec.dequeueInputBuffer(10000)
                                if (eosIdx >= 0) {
                                    codec.queueInputBuffer(eosIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                }
                                inputDone = true
                                writePreCacheLog("decodeToPcmForPreCache: [v2.0.72] reached stop time at ${currentSampleTime / 1000000}s (stopAt=${stopAtUs / 1000000}s)")
                            } else if (durationUs > 0 && currentSampleTime >= durationUs) {
                                // End of actual audio (for short files or when not seeked)
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                                writePreCacheLog("decodeToPcmForPreCache: [v2.0.72] reached end of audio at ${currentSampleTime / 1000000}s (duration=${durationUs / 1000000}s)")
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, currentSampleTime, 0)
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

                            // [v2.1.1] Global continuous resampling with leftover state
                            // This prevents periodic clicks at chunk boundaries and timing drift
                            val resampled = resampleChunkContinuous(
                                chunkShorts, actualInSampleRate, actualInChannels,
                                outSampleRate, outChannels, resamplePhase, lastSample
                            )
                            resamplePhase = resampled.second  // save phase for next chunk
                            lastSample = resampled.third      // save last sample for next chunk
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
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // [v2.1.1] Re-read actual output format from codec
                        val newFormat = codec.outputFormat
                        try {
                            actualInSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            actualInChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            writePreCacheLog("decodeToPcmForPreCache: [v2.1.1] FORMAT_CHANGED: sampleRate=$actualInSampleRate, channels=$actualInChannels")
                        } catch (e: Exception) {
                            writePreCacheLog("decodeToPcmForPreCache: [v2.1.1] FORMAT_CHANGED but failed to read format: ${e.message}")
                        }
                    }
                    else -> {}
                }
            }

            writePreCacheLog("decodeToPcmForPreCache: decoded $decodedBytes raw bytes, resampled to $resampledBytes bytes")
            writePreCacheLog("decodeToPcmForPreCache: PCM file size=${pcmFile.length()} bytes, expected duration=${pcmFile.length() / (outSampleRate * 2 * outChannels)}s")

            // After decode loop, write sample rate info
            // [v2.0.99] _5min.pcm is now always 16kHz mono (outSampleRate=16000, outChannels=1).
            // No need to generate a separate _16k file - the main file IS the 16kHz file.
            val infoFile = File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".info")
            infoFile.writeText("sampleRate=$outSampleRate\nchannels=$outChannels\nversion=3")
            writePreCacheLog("decodeToPcmForPreCache: wrote info file: $infoFile (sampleRate=$outSampleRate, channels=$outChannels)")

            // [v2.1.2] WAV generation removed per user request. PCM file is sufficient.
            // generateWavFromPcm(pcmFile, outSampleRate, outChannels)

            // [v2.0.99] Removed: duplicate _16k.pcm generation (lines 1833-1871).
            // Previously this code created a second PCM file with _16k suffix by re-reading
            // and re-resampling the already-16kHz _5min.pcm. This was redundant (v2.0.97
            // already forces 16kHz output) and the resample used wrong source rate (44100
            // instead of 16000), producing a corrupt 6MB file alongside the correct 9MB file.
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
            val episodeKey = ep.id ?: ""
            if (episodeKey.isBlank()) -1L else getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(episodeKey, -1L)
        }
        if (savedPos > 0 && !isLive) {
            isSeekingToPosition = true
            lastPositionRestoreTime = System.currentTimeMillis()
            player?.playWhenReady = false  // Keep paused until seek completes
            player?.seekTo(savedPos)
            Log.d(TAG, "Restored position: ${savedPos}ms, waiting for seek completion before play, will not save position for 5s")
            // v2.4.37: Reduced from 30s to 5s - 30s was blocking ALL position saves for
            // 30 seconds after starting playback, even for the 5-second auto-save.
            // The seek should complete within 2-3 seconds; 5s is enough safety margin.
            positionSaveHandler?.postDelayed({
                isSeekingToPosition = false
                Log.d(TAG, "Position restore grace period ended, position saving enabled")
            }, 5000)
        }
    }

    private fun startPositionSaver() {
        positionSaveRunnable = Runnable {
            // [v2.4.6] Save position when prepared (not just when playing).
            // Previously only saved when isPlaying==true, which meant if the user
            // paused and the app crashed, the position was lost.
            if (savePlaybackPosition && !isLive && prepared) {
                saveCurrentPosition()
            }
            positionSaveHandler?.postDelayed(positionSaveRunnable!!, POSITION_SAVE_INTERVAL)
        }
        positionSaveRunnable?.let { positionSaveHandler?.post(it) }
    }

    private fun saveCurrentPosition() {
        if (isSeekingToPosition || pendingStartPosition >= 0) {
            // v2.4.37: Log why save is blocked for debugging
            writeServiceLog("playback", "[v2.4.37] saveCurrentPosition: BLOCKED (isSeekingToPosition=$isSeekingToPosition, pendingStartPosition=$pendingStartPosition)")
            return
        }
        // v2.4.108: Block saves during episode switching to prevent old position being
        // saved under new episode's ID. episodeSwitching is set in playEpisode() and
        // cleared in STATE_READY. If the player hasn't reached STATE_READY for the new
        // episode yet, the position is still from the old episode.
        if (episodeSwitching) {
            writeServiceLog("playback", "[v2.4.108] saveCurrentPosition: BLOCKED (episodeSwitching=true)")
            return
        }
        // v2.4.36: Reduced from 30000ms to 5000ms - 30s block was too long, causing
        // positions to not be saved for 30 seconds after starting playback.
        // v2.4.114: Increased from 5000ms to 10000ms. Logs show stale positions being
        // saved 8.9 seconds after episode switch (5s window expired at 5s, stale save
        // happened at 8.9s). 10s window covers the full stale position period.
        if (System.currentTimeMillis() - lastPositionRestoreTime < 10000) {
            writeServiceLog("playback", "[v2.4.114] saveCurrentPosition: BLOCKED (within 10s of restore)")
            return
        }
        val ep = currentEpisode ?: return
        val pos = getCurrentPosition()  // [v2.0.62] Use authoritative position
        // v2.4.36: Allow saving even at pos=0 - previously pos<=0 was skipped, but
        // this meant if user started playing from beginning, position was never saved.
        if (pos < 0) return
        // v2.4.116: Reject saves where the position is far from the episode's start position.
        // v2.4.114 compared pos with authoritativePosition, but these were the SAME variable
        // (getCurrentPosition returns authoritativePosition), so delta was always 0.
        // v2.4.116 compares pos with episodeStartPos (a separate fixed reference point).
        // If pos is more than 120s ahead of episodeStartPos, it's likely stale.
        val expectedStart = episodeStartPos
        if (expectedStart > 0 && pos > 0) {
            val delta = pos - expectedStart
            // Allow up to 120s ahead of start position (accounts for elapsed playback time).
            // Beyond that, the position is definitely stale from the old episode.
            // Also check if pos is BEHIND episodeStartPos by more than 5s (shouldn't go backward).
            if (delta > 120000 || delta < -5000) {
                writeServiceLog("playback", "[v2.4.116] saveCurrentPosition: REJECTED (pos=$pos vs episodeStartPos=$expectedStart, delta=${delta}ms, likely stale)")
                return
            }
        }
        // v2.4.63: Use episode ID as the primary key (unique per episode).
        // Previously used stationId::title which is shared across different dates of the
        // same program, causing position to be overwritten when switching between dates.
        val episodeKey = ep.id ?: ""
        if (episodeKey.isBlank()) return
        // [v2.4.31] Fix: use commit() (synchronous) instead of apply() (asynchronous).
        getSharedPreferences("playback_positions", MODE_PRIVATE)
            .edit().putLong(episodeKey, pos).commit()
        writeServiceLog("playback", "[v2.4.63] saveCurrentPosition: SAVED pos=$pos for episodeId=$episodeKey")
    }

    // v2.4.86: Clear saved position from BOTH SharedPreferences AND database when episode completes.
    // Previously only cleared SharedPreferences, leaving stale DB/Activity cache progress which
    // caused "cannot replay" issue when PlayerActivity restored from cache and skipped to near-end.
    private fun clearSavedPosition() {
        val ep = currentEpisode ?: return
        val episodeKey = ep.id ?: ""
        if (episodeKey.isBlank()) return
        // 1. Clear service-side SharedPreferences
        getSharedPreferences("playback_positions", MODE_PRIVATE)
            .edit().remove(episodeKey).apply()
        // 2. Clear SQLite database progress
        try {
            val dbHelper = com.radio.app.database.RadioDatabaseHelper.getInstance(this)
            dbHelper.deletePlayProgress(episodeKey)
        } catch (e: Exception) {
            writeServiceLog("playback", "[v2.4.86] clearSavedPosition: DB delete failed: ${e.message}")
        }
        // 3. Clear Activity's player_position_cache so replay doesn't jump to stale near-end position
        try {
            getSharedPreferences("player_position_cache", MODE_PRIVATE)
                .edit().remove("cached_position").remove("cached_episode_id").apply()
        } catch (_: Exception) {}
        writeServiceLog("playback", "[v2.4.86] clearSavedPosition: cleared all progress for episode=$episodeKey")
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
        // v2.4.63: Use episode ID as key (unique per episode, not shared across dates)
        val episodeKey = ep.id ?: ""
        if (episodeKey.isBlank()) return -1L
        return getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(episodeKey, -1L)
    }

    /**
     * v2.4.63: Force save current position immediately (bypasses the 5s restore guard).
     * Called when user manually pauses playback.
     */
    fun forceSaveCurrentPosition() {
        val ep = currentEpisode ?: return
        val pos = getCurrentPosition()
        if (pos < 0) return
        // v2.4.118: Check for stale position before force-saving.
        // forceSaveCurrentPosition is called during episode switch (playEpisode) to save
        // the OLD episode's position. But if authoritativePosition was corrupted by stale
        // rawPos from STATE_READY/onPositionDiscontinuity, this would save the wrong position.
        if (episodeStartPos > 0 && pos > 0) {
            val delta = pos - episodeStartPos
            if (delta > 120000 || delta < -5000) {
                writeServiceLog("playback", "[v2.4.118] forceSaveCurrentPosition: REJECTED (pos=$pos vs episodeStartPos=$episodeStartPos, delta=${delta}ms, likely stale)")
                return
            }
        }
        val episodeKey = ep.id ?: ""
        if (episodeKey.isBlank()) return
        getSharedPreferences("playback_positions", MODE_PRIVATE)
            .edit().putLong(episodeKey, pos).commit()
        writeServiceLog("playback", "[v2.4.63] forceSaveCurrentPosition: SAVED pos=$pos for episodeId=$episodeKey")
    }

    /**
     * v2.4.63: Force save last episode info (bypasses normal saveLastEpisode which may not be called on pause).
     * Called when user manually pauses playback to ensure last played episode is remembered.
     */
    fun forceSaveLastEpisode(episode: Episode) {
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
        }.commit()  // v2.4.63: Use commit() for synchronous save
        writeServiceLog("playback", "[v2.4.63] forceSaveLastEpisode: SAVED episode=${episode.title}, id=${episode.id}")
    }

    private fun startProgressPolling() {
        progressRunnable = Runnable {
            player?.let { p ->
                callback?.let { cb ->
                    try {
                        // [v2.0.62] Issue 1 Fix: Use authoritative position from getCurrentPosition()
                        val pos = getCurrentPosition()
                        // [v2.4.16] Fix: Use getSafeDuration() instead of raw p.duration
                        // This prevents old episode's duration leaking to UI during episode switch
                        var dur = getSafeDuration()
                        // [v2.0.62] Issue 1 Fix: When player duration is 0 (not prepared) but we have episode duration, use it
                        // This prevents seekBar max from being 0 during buffering
                        if (dur <= 0 && !isLive) {
                            val epDur = currentEpisode?.duration ?: 0L
                            if (epDur > 0) {
                                // episode.duration might be in seconds; convert to ms if needed
                                dur = if (epDur < 100000) epDur * 1000 else epDur
                            }
                        }
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
            // [v2.0.62] Issue 5 Fix: Update notification progress even when not fully prepared.
            // During episode switches (prepared=false), getCurrentPosition() returns authoritative
            // position (never 0), and updateNotificationProgressOnly uses episode duration fallback.
            // This prevents the notification progress bar from disappearing during cross-day switches.
            // [v2.0.87] Also update MediaSession state during polling so the system MediaStyle
            // progress bar (used by compact notifications) stays in sync. Without this, the
            // system progress bar freezes because setPlaybackState is only called on play/pause.
            // [v2.0.94] Removed !isPrecaching guard — notification must update during episode switch
            if (!isLive && player != null) {
                updateNotificationProgressOnly()
                // [v2.0.87] Update MediaSession PlaybackState with current position for system progress bar
                updateMediaSessionState()
            }
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
                // [v2.0.45] Issue 2 Fix: Include year in date display
                // Format date: "2024-06-04T07:00:00" -> "2024-06-04" (was "06-04")
                if (notificationDate.length >= 10) {
                    append(notificationDate.substring(0, 10))
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
        // [Issue 5 Fix] 构建通知栏前先刷新 MediaSession 元数据，避免显示上一集陈旧信息
        updateMediaSessionMetadata()
        // 每次更新时重新加载通知栏样式，确保设置更改即时生效
        reloadNotificationStyle()
        writeServiceLog("notification", "updateNotification: BEFORE build - title='$notificationTitle', fullSubText='${buildNotificationSubText()}', date='$notificationDate', timeRange='$notificationTimeRange', style='$notificationStyle'")
        // [v2.0.78] Issue 5/6 Fix: Also check pauseConfirmedUntil to prevent post-pause flicker.
        // For 2 seconds after pause(), force playing=false regardless of ExoPlayer transient state.
        val inPauseConfirmWindow = System.currentTimeMillis() < pauseConfirmedUntil
        val playing = playbackStarted && !userPaused && !pausedByAudioFocus && !inPauseConfirmWindow

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

        // v2.4.107: Title = title + time range (no date)
        val notifTimeStr = notificationTimeRange
        val displayTitle = buildString {
            append(notificationTitle)
            if (notifTimeStr.isNotBlank()) append(" $notifTimeStr")
        }

        remoteViews.setTextViewText(R.id.notification_title, displayTitle)
        remoteViews.setTextViewText(R.id.notification_subtitle,
            if (playing) "正在播放 $fullSubText" else "已暂停 $fullSubText")

        // [v2.0.67] Issue 5 Fix: Always show progress bar for non-live content, even when not prepared.
        // Cross-day episode switches set prepared=false, which previously hid the progress area.
        // Use authoritative position and episode duration fallback to keep progress visible.
        // [v2.0.73] Issue 5 Fix: Use getSafeDuration() for all notification progress calculations.
        // Also log detailed progress state per user request.
        if (!isLive) {
            remoteViews.setViewVisibility(R.id.notification_progress_area, android.view.View.VISIBLE)
            val pos = getCurrentPosition()
            val rawPlayerDur = player?.duration ?: 0L
            val dur = getSafeDuration()
            val epId = currentEpisode?.id ?: "null"
            val epTitle = currentEpisode?.title ?: "null"
            val isPlaying = player?.isPlaying ?: false
            writeServiceLog("notification", "[v2.0.77-PROGRESS] progress_area=VISIBLE, pos=$pos, rawPlayerDur=$rawPlayerDur, safeDur=$dur, prepared=$prepared, isPlaying=$isPlaying, playbackStarted=$playbackStarted, userPaused=$userPaused, pausedByAF=$pausedByAudioFocus, epId=$epId, epTitle=$epTitle, progressBar will show=${dur > 0}")
            if (dur > 0) {
                // [v2.0.77] Issue 6 Fix: Cap pos to dur to prevent overflow/full-progress bug.
                // pos can temporarily exceed dur during seek storms or after episode switches.
                val safePos = pos.coerceIn(0L, dur)
                val progress = ((safePos * 1000) / dur).toInt().coerceIn(0, 1000)
                remoteViews.setProgressBar(R.id.notification_progress, 1000, progress, false)
                val totalSec = dur.toInt() / 1000
                val curSec = pos.toInt() / 1000
                remoteViews.setTextViewText(R.id.notification_time_text,
                    "${formatTimeNotif(curSec)}/${formatTimeNotif(totalSec)}")
                writeServiceLog("notification", "[v2.0.73-PROGRESS] progress set: progress=$progress/1000, curTime=${formatTimeNotif(curSec)}, totalTime=${formatTimeNotif(totalSec)}")
            } else {
                // [v2.0.93] Fix: When dur=0 (new episode not yet prepared), explicitly set progress=0
                // instead of leaving the old progress bar value (which could be 100% from previous episode).
                remoteViews.setProgressBar(R.id.notification_progress, 1000, 0, false)
                remoteViews.setTextViewText(R.id.notification_time_text, "00:00/--:--")
                writeServiceLog("notification", "[v2.0.93-PROGRESS] dur=0, set progress=0 (was keeping old value causing 100% bug)")
            }
        } else {
            remoteViews.setViewVisibility(R.id.notification_progress_area, android.view.View.GONE)
            writeServiceLog("notification", "[v2.0.73-PROGRESS] live content, progress hidden")
        }

        applyThemeToNotification(remoteViews)
        if (notificationStyle == "full") {
            applySeekIntents(remoteViews)
        }

        val dateStr = if (notificationDate.length >= 10) notificationDate.substring(5, 10) else ""
        val timeStr = notificationTimeRange
        val contentText = buildString {
            append(if (playing) "正在播放" else "已暂停")
            // v2.4.98: Combine date and start-end time as one unit
            if (dateStr.isNotBlank()) {
                append(" · $dateStr")
                if (timeStr.isNotBlank()) append(" $timeStr")
            } else if (timeStr.isNotBlank()) {
                append(" · $timeStr")
            }
        }
        // Issue 4: Set date/time on RemoteViews (contentText is hidden when custom layout is used)
        writeNotifDetailLog("updateNotification: BEFORE setTextViewText - notificationTitle='$notificationTitle', notificationDate='$notificationDate', notificationTimeRange='$notificationTimeRange', contentText='$contentText', notificationStyle='$notificationStyle', lastNotificationContentHash=$lastNotificationContentHash")
        remoteViews.setTextViewText(R.id.notification_subtitle, contentText)
        writeNotifDetailLog("updateNotification: AFTER setTextViewText - remoteViews.setTextViewText(notification_subtitle, '$contentText') called=true, lastNotificationContentHash=$lastNotificationContentHash")
        val builder = NotificationCompat.Builder(this, RadioApplication.CHANNEL_ID)
            .setContentTitle(displayTitle)
            .setContentText(fullSubText)
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
        writeServiceLog("notification", "updateNotification: AFTER notify - title='$notificationTitle', fullSubText='${buildNotificationSubText()}', date='$notificationDate', timeRange='$notificationTimeRange'")
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

        // v2.4.109: Increased debounce from 500ms to 1500ms to prevent notification burst
        // during state transitions (PAUSED→PLAYING + PROGRESS-POLL within 1 second).
        // Multiple notifyNotification() calls within 1.5s are coalesced into one update.
        if (elapsed < 1500) {
            // Debounce: schedule a delayed update
            pendingNotificationRunnable = Runnable {
                lastNotificationTime = System.currentTimeMillis()
                doNotifyNotification()
            }
            notificationHandler!!.postDelayed(pendingNotificationRunnable!!, 1500 - elapsed)
            return
        }

        lastNotificationTime = now
        doNotifyNotification()
    }

    private fun doNotifyNotification() {
        val notification = updateNotification()

        // [v2.0.75] Issue 5 Fix: Content hash must ALWAYS include position (using getCurrentPosition()
        // which returns authoritativePosition), not just when prepared=true.
        // v2.0.74 bug: when prepared=false (during cross-day episode switch), position was 0 in hash,
        // causing deduplication to skip all updates since title/date/playing state didn't change.
        // [v2.0.87] Fix: Include pausedByAudioFocus and inPauseConfirmWindow in hash to detect
        // all playing state changes. Previous hash only used playbackStarted && !userPaused,
        // which missed audio focus changes (e.g., Pinduoduo video pausing playback).
        val inPauseConfirmWindow = System.currentTimeMillis() < pauseConfirmedUntil
        val effectivePlaying = playbackStarted && !userPaused && !pausedByAudioFocus && !inPauseConfirmWindow
        // v2.4.118: Removed position from contentHash. Position changes every 5s poll (~5000ms),
        // so including it (even at 2s granularity) means the hash is ALWAYS different, making
        // dedup impossible. Position-based dedup is already handled by the posChanged check
        // in the progress poll (v2.4.108 "pos unchanged SKIPPING").
        // ContentHash now only tracks metadata changes (title/date/playing state/prepared).
        val contentHash = Objects.hash(
            notificationTitle,
            notificationDate,
            notificationTimeRange,
            buildNotificationSubText(),
            effectivePlaying,
            prepared
        )
        // Issue 3: forceNotificationUpdate bypasses the hash check after episode switch
        val shouldForce = forceNotificationUpdate
        if (shouldForce) {
            forceNotificationUpdate = false
        }
        if (!shouldForce && contentHash == lastNotificationContentHash) {
            return  // Skip identical notification update
        }
        lastNotificationContentHash = contentHash

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        try {
            val pos = getCurrentPosition()
            val dur = if (!isLive) getSafeDuration() else 0L
            writeServiceLog("notification", "[v2.0.75-PROGRESS-NOTIFY] manager.notify(ID=$NOTIFICATION_ID), pos=$pos, dur=$dur, prepared=$prepared, playing=${playbackStarted && !userPaused}, hash=$contentHash")
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
     * v2.0.75 Issue 5 Fix: 更新通知栏进度条。
     *
     * v2.0.74及之前的关键bug：
     * 1. non-minimal样式调用updateNotification()但不调用manager.notify()，导致compact/full样式
     *    的进度条在5秒轮询中从不更新，只在play/pause/episode switch时更新；
     * 2. lastNotifiedPosition=-1重置后，首次轮询pos=0或很小值时abs(pos-(-1))<2000导致跳过更新；
     * 3. doNotifyNotification()内容hash在prepared=false时位置字段固定为0，导致去重跳过更新；
     * 4. minimal布局没有progress_area，进度条无法显示。
     *
     * 修复：所有样式都通过notifyNotification()正确推送通知；首次重置后强制更新；
     * minimal布局已添加progress_area并正确设置可见性。
     */
    private fun updateNotificationProgressOnly() {
        val pos = getCurrentPosition()
        val dur = if (!isLive) getSafeDuration() else 0L
        val rawPlayerDur = player?.duration ?: 0L
        // [v2.0.78] Include pauseConfirmedUntil to prevent flicker
        val inPauseConfirmWindow = System.currentTimeMillis() < pauseConfirmedUntil
        val playing = playbackStarted && !userPaused && !pausedByAudioFocus && !inPauseConfirmWindow
        val epId = currentEpisode?.id ?: "null"
        val epTitle = currentEpisode?.title ?: "null"
        // [v2.0.75] Issue 5: 详细日志 - 进度条状态、总时长、播放进度、关键标志
        writeServiceLog("notification", "[v2.0.75-PROGRESS-POLL] pos=$pos, dur=$dur, rawPlayerDur=$rawPlayerDur, isLive=$isLive, style=$notificationStyle, prepared=$prepared, isPlaying=$playing, userPaused=$userPaused, lastNotifiedPos=$lastNotifiedPosition, epId=$epId, epTitle=$epTitle")
        if (isLive) {
            writeServiceLog("notification", "[v2.0.75-PROGRESS-POLL] live content, hiding progress")
            return
        }
        if (dur <= 0) {
            // v2.4.110: During episode switching, getSafeDuration() returns 0 because
            // player.duration is ignored (episodeSwitching=true) and episode duration
            // may not be available yet. Previously this forced a full notification rebuild
            // every 5 seconds, causing the notification progress bar to reset/loop.
            // Now: skip the rebuild during episode switching; the notification will be
            // properly updated when STATE_READY fires (clears episodeSwitching).
            if (episodeSwitching || !prepared) {
                writeServiceLog("notification", "[v2.4.110-PROGRESS-POLL] dur<=0 but episodeSwitching=$episodeSwitching, prepared=$prepared, SKIPPING rebuild (will update on STATE_READY)")
                return
            }
            writeServiceLog("notification", "[v2.0.75-PROGRESS-POLL] dur<=0, forcing full notification rebuild to recover")
            // [v2.0.75] Issue 5: dur<=0时不要直接返回，强制重建通知恢复进度条
            forceNotificationUpdate = true
            // v2.4.117: removed — forceNotificationUpdate=true is sufficient
            notifyNotification()
            return
        }

        // [v2.4.6] Detect playback stuck near the end of an episode.
        // ExoPlayer sometimes doesn't fire STATE_ENDED when audio reaches the end,
        // leaving position frozen near dur with isPlaying=true. The notification
        // progress bar then stays at 100% (999/1000) forever, and continuous play
        // (autoPlayNextEpisode) is never triggered.
        // Fix: when pos is within 3s of dur AND position hasn't changed for 15s
        // (3 consecutive 5s polls), manually trigger episode end handling.
        // [v2.4.81] FIX: Also handle pos >= dur (position exceeds duration).
        // When pos > dur, distToEnd is negative, and the old check `distToEnd in 0..3000`
        // fails. This causes the progress bar to stay at 100% forever.
        if (prepared && !isLive && !userPaused && continuousPlay && dur > 10000) {
            val distToEnd = dur - pos
            // v2.4.81: Handle both near-end (0..3000) AND past-end (negative distToEnd)
            val isNearOrPastEnd = distToEnd in -10000..3000
            // v2.4.84: If pos > dur (position EXCEEDS duration), trigger immediately!
            // No need to wait 15s - the audio is clearly done.
            val isPastEnd = pos > dur
            // v2.4.113: Skip isPastEnd/isNearOrPastEnd if within 10 seconds of episode switch.
             // v2.4.114: Increased from 5s to 10s to match saveCurrentPosition block window.
             // Root cause: after setMediaItem(newItem, startPos), getCurrentPosition() may still
             // return old episode's position which is > new episode's duration. This falsely
             // triggers isPastEnd → clearSavedPosition → autoPlayNextEpisode with wrong startPos.
             val withinEpisodeSwitchWindow = System.currentTimeMillis() - lastPositionRestoreTime < 10000
            if (isPastEnd && !withinEpisodeSwitchWindow) {
                writeServiceLog("notification", "[v2.4.86] PAST-END: pos=$pos > dur=$dur, clearing progress and triggering autoPlayNextEpisode")
                // v2.4.86: Clear saved progress BEFORE switching to next episode
                // so the completed episode can be replayed from the beginning
                clearSavedPosition()
                stuckAtEndSince = 0L
                stuckAtEndPos = 0L
                prepared = false
                authoritativePosition = 0L
                maxKnownPosition = 0L
                lastNotifiedPosition = -1L
                if (continuousPlay && !isLive) {
                    autoPlayNextEpisode()
                } else {
                    forceNotificationUpdate = true
                    // v2.4.117: removed — forceNotificationUpdate=true is sufficient
                    updateNotification()
                }
                return
            } else if (isNearOrPastEnd && !withinEpisodeSwitchWindow) {
                // Position is near the end (within 3s before dur)
                if (stuckAtEndSince == 0L) {
                    stuckAtEndSince = System.currentTimeMillis()
                    stuckAtEndPos = pos
                    writeServiceLog("notification", "[v2.4.6] STUCK-AT-END detected: pos=$pos, dur=$dur, distToEnd=${distToEnd}ms, starting 5s timer")
                } else if (pos == stuckAtEndPos) {
                    // Position hasn't changed since we first detected near-end
                    val stuckDuration = System.currentTimeMillis() - stuckAtEndSince
                    // v2.4.84: Reduced from 15s to 5s - user sees progress bar at 100% for too long
                    if (stuckDuration >= 5000) {
                        writeServiceLog("notification", "[v2.4.86] STUCK-AT-END CONFIRMED: pos=$pos unchanged for ${stuckDuration}ms, clearing progress and triggering autoPlayNextEpisode")
                        // v2.4.86: Clear saved progress BEFORE switching to next episode
                        clearSavedPosition()
                        stuckAtEndSince = 0L
                        stuckAtEndPos = 0L
                        // Simulate STATE_ENDED handling to trigger continuous play
                        prepared = false
                        authoritativePosition = 0L
                        maxKnownPosition = 0L
                        lastNotifiedPosition = -1L
                        if (continuousPlay && !isLive) {
                            autoPlayNextEpisode()
                        } else {
                            forceNotificationUpdate = true
                            // v2.4.117: removed — forceNotificationUpdate=true is sufficient
                            updateNotification()
                        }
                        return
                    } else {
                        writeServiceLog("notification", "[v2.4.6] STUCK-AT-END waiting: pos=$pos unchanged for ${stuckDuration}ms (need 5000ms)")
                    }
                } else {
                    // Position changed (advanced slightly), reset timer
                    stuckAtEndSince = System.currentTimeMillis()
                    stuckAtEndPos = pos
                }
            } else {
                // Not near end, reset
                if (stuckAtEndSince != 0L) {
                    writeServiceLog("notification", "[v2.4.6] STUCK-AT-END cancelled: pos=$pos moved away from end (distToEnd=${distToEnd}ms)")
                    stuckAtEndSince = 0L
                    stuckAtEndPos = 0L
                }
            }
        } else {
            // Not in a state where we should check, reset
            stuckAtEndSince = 0L
            stuckAtEndPos = 0L
        }

        // [v2.0.87] Fix: Removed posDelta < 2000 early return. This was causing the notification
        // to freeze when position didn't change (e.g., during buffering, at end of audio, or when
        // player was in a bad state). The content hash dedup in doNotifyNotification() already
        // prevents redundant updates when nothing has changed, so the posDelta check was redundant
        // and harmful — it prevented legitimate updates (e.g., play/pause state changes).
        val isReset = lastNotifiedPosition < 0
        // v2.4.91: Save previous position before overwriting, to detect if position actually changed
        val prevNotifiedPos = lastNotifiedPosition
        val posChanged = !isReset && abs(pos - prevNotifiedPos) > 500
        lastNotifiedPosition = pos

        if (isReset) {
            // v2.4.112: Prevent notification burst during episode switch.
            // Multiple RESET detections (from playEpisode + state transitions + progress poll)
            // each force a full rebuild, causing 2-5 manager.notify calls in 1.5-3 seconds.
            // Now: only allow one RESET-triggered rebuild per 5 seconds.
            val now = System.currentTimeMillis()
            if (now - lastResetRebuildTime < 5000) {
                writeServiceLog("notification", "[v2.4.112-PROGRESS-POLL] RESET detected but throttled (last rebuild ${now - lastResetRebuildTime}ms ago), skipping. pos=$pos, dur=$dur")
                // Still update position tracking, just don't force rebuild
                return
            }
            lastResetRebuildTime = now
            writeServiceLog("notification", "[v2.0.87-PROGRESS-POLL] RESET detected (lastNotifiedPosition was <0), forcing full notification rebuild. pos=$pos, dur=$dur")
        } else if (posChanged) {
            writeServiceLog("notification", "[v2.0.87-PROGRESS-POLL] updating notification. pos=$pos, dur=$dur")
        } else {
            // v2.4.91: Position unchanged (frozen/buffering) — skip forced rebuild to prevent "几秒循环"
            writeServiceLog("notification", "[v2.4.91-PROGRESS-POLL] pos unchanged ($pos), skipping forced rebuild")
        }

        // [v2.0.75] Issue 5 Fix: non-minimal样式必须正确推送通知。
        // v2.4.91: Only force update when position changed or on reset, NOT every poll.
        // v2.4.108: Also SKIP notifyNotification() entirely when position is unchanged
        // to prevent progress bar animation restart (the "几秒循环" bug).
        if (notificationStyle != "minimal") {
            if (isReset || posChanged) {
                forceNotificationUpdate = true
                // v2.4.116: DON'T reset lastNotificationContentHash to 0 here.
                // forceNotificationUpdate=true already bypasses the hash check in
                // doNotifyNotification(). Resetting the hash destroys the dedup
                // state, causing the next non-forced poll to always rebuild even
                // when content hasn't changed.
                writeServiceLog("notification", "[v2.0.75-PROGRESS-POLL] style=$notificationStyle, calling notifyNotification() for progress refresh")
                notifyNotification()
            } else {
                writeServiceLog("notification", "[v2.4.108-PROGRESS-POLL] pos unchanged ($pos), SKIPPING notifyNotification() to prevent progress bar loop")
            }
            return
        }

        // [v2.0.75] Issue 5 Fix: minimal样式更新 - minimal布局现已包含progress_area
        try {
            val rv = RemoteViews(packageName, R.layout.notification_minimal)
            // Set progress area VISIBLE for non-live
            rv.setViewVisibility(R.id.notification_progress_area, android.view.View.VISIBLE)
            // [v2.0.77] Issue 6 Fix: Cap pos to dur
            val safePos = pos.coerceIn(0L, dur)
            val progress = ((safePos * 1000) / dur).toInt().coerceIn(0, 1000)
            rv.setProgressBar(R.id.notification_progress, 1000, progress, false)
            rv.setTextViewText(R.id.notification_time_text, "${formatTimeNotif(safePos.toInt() / 1000)}/${formatTimeNotif(dur.toInt() / 1000)}")
            applyNotificationIntents(rv)
            applyThemeToNotification(rv)
            rv.setImageViewResource(R.id.play_pause_icon,
                if (playing) R.drawable.notif_pause else R.drawable.notif_play)
            rv.setTextViewText(R.id.play_pause_text, if (playing) "暂停" else "播放")
            val dateStr = if (notificationDate.length >= 10) notificationDate.substring(5, 10) else ""
            val timeStr = notificationTimeRange
            // v2.4.107: Title = title + time range (no date)
            val displayTitleProgress = buildString {
                append(notificationTitle)
                if (timeStr.isNotBlank()) append(" $timeStr")
            }
            rv.setTextViewText(R.id.notification_title, displayTitleProgress)
            val contentText = buildString {
                append(if (playing) "正在播放" else "已暂停")
                if (dateStr.isNotBlank()) {
                    append(" · $dateStr")
                    if (timeStr.isNotBlank()) append(" $timeStr")
                } else if (timeStr.isNotBlank()) {
                    append(" · $timeStr")
                }
            }
            rv.setTextViewText(R.id.notification_subtitle, contentText)
            val fullSubText = buildNotificationSubText()
            val builder = NotificationCompat.Builder(this, RadioApplication.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(displayTitleProgress)
                .setContentText(fullSubText)
                .setSubText(fullSubText)
                .setCustomContentView(rv)
                .setOngoing(true)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            writeServiceLog("notification", "[v2.0.75-PROGRESS-POLL] manager.notify (minimal), pos=$pos/$dur, progress=$progress")
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            writeServiceLog("notification", "[v2.0.75-PROGRESS-POLL] minimal update FAILED: ${e.message}, falling back to notifyNotification()")
            forceNotificationUpdate = true
            // v2.4.117: removed — forceNotificationUpdate=true is sufficient
            notifyNotification()
        }
    }

    /**
     * 构建系统标准MediaStyle通知栏（五按钮，支持seekbar和拖动手势）
     */
    private fun buildMediaStyleNotification(playing: Boolean, deleteIntent: PendingIntent): Notification {
        writeServiceLog("notification", "buildMediaStyleNotification: BEFORE build - title='$notificationTitle', fullSubText='${buildNotificationSubText()}', date='$notificationDate', timeRange='$notificationTimeRange'")
        val dateStr = if (notificationDate.length >= 10) notificationDate.substring(5, 10) else ""
        val timeStr = notificationTimeRange

        // v2.4.107: Title = title + time range (no date). Date stays in subtitle only.
        val displayTitle = buildString {
            append(notificationTitle)
            if (timeStr.isNotBlank()) append(" $timeStr")
        }

        val contentText = buildString {
            append(if (playing) "正在播放" else "已暂停")
            // v2.4.98: Combine date and start-end time as one unit
            if (dateStr.isNotBlank()) {
                append(" · $dateStr")
                if (timeStr.isNotBlank()) append(" $timeStr")
            } else if (timeStr.isNotBlank()) {
                append(" · $timeStr")
            }
        }

        // 创建展开视图（含进度条和50点seek）
        val expandedView = RemoteViews(packageName, R.layout.notification_media_expanded)
        expandedView.setTextViewText(R.id.notification_title, displayTitle)
        writeNotifDetailLog("buildMediaStyleNotification: BEFORE setTextViewText - displayTitle='$displayTitle', notificationTitle='$notificationTitle', notificationDate='$notificationDate', notificationTimeRange='$notificationTimeRange', contentText='$contentText', notificationStyle='$notificationStyle', lastNotificationContentHash=$lastNotificationContentHash")
        expandedView.setTextViewText(R.id.notification_subtitle, contentText)
        writeNotifDetailLog("buildMediaStyleNotification: AFTER setTextViewText - remoteViews.setTextViewText(notification_subtitle, '$contentText') called=true, lastNotificationContentHash=$lastNotificationContentHash")

        // [v2.0.64] Issue 5 Fix: Always show progress bar for non-live content, even when not prepared.
        // During episode switches, prepared=false causes the progress bar to disappear.
        // Use getCurrentPosition() (authoritative, never 0) and episode duration as fallback.
        if (!isLive) {
            expandedView.setViewVisibility(R.id.notification_progress_area, android.view.View.VISIBLE)
            val pos = getCurrentPosition()
            // [v2.0.73] Issue 5 Fix: Use getSafeDuration() which filters out Long.MIN_VALUE
            // (C.TIME_UNSET from ExoPlayer during reset) and falls back to cached/episode/default duration
            val rawPlayerDur = player?.duration ?: 0L
            val dur = getSafeDuration()
            val epId = currentEpisode?.id ?: "null"
            val epTitle = currentEpisode?.title ?: "null"
            val isPlaying = player?.isPlaying ?: false
            writeServiceLog("notification", "[v2.0.77-PROGRESS-MEDIA] progress_area=VISIBLE, pos=$pos, rawPlayerDur=$rawPlayerDur, safeDur=$dur, prepared=$prepared, isPlaying=$isPlaying, playbackStarted=$playbackStarted, userPaused=$userPaused, pausedByAF=$pausedByAudioFocus, epId=$epId, epTitle=$epTitle, progressBar will show=${dur > 0}")
            if (dur > 0) {
                // [v2.0.77] Issue 6 Fix: Cap pos to dur
                val safePos = pos.coerceIn(0L, dur)
                val progress = ((safePos * 1000) / dur).toInt().coerceIn(0, 1000)
                expandedView.setProgressBar(R.id.notification_progress, 1000, progress, false)
                val totalSec = dur.toInt() / 1000
                val curSec = safePos.toInt() / 1000
                expandedView.setTextViewText(R.id.notification_time_text,
                    "${formatTimeNotif(curSec)}/${formatTimeNotif(totalSec)}")
                writeServiceLog("notification", "[v2.0.77-PROGRESS-MEDIA] progress set: progress=$progress/1000, curTime=${formatTimeNotif(curSec)}, totalTime=${formatTimeNotif(totalSec)}")
            } else {
                // [v2.0.93] Fix: dur=0 — set progress=0 instead of keeping old value (100% bug)
                expandedView.setProgressBar(R.id.notification_progress, 1000, 0, false)
                expandedView.setTextViewText(R.id.notification_time_text, "00:00/--:--")
                writeServiceLog("notification", "[v2.0.93-PROGRESS-MEDIA] dur=0, set progress=0")
            }
            applySeekIntents(expandedView)
        } else {
            expandedView.setViewVisibility(R.id.notification_progress_area, android.view.View.GONE)
            writeServiceLog("notification", "[v2.0.73-PROGRESS-MEDIA] live content, progress hidden")
        }

        // Issue 4: Use fullSubText (with date/time) for setContentText so compact view shows date
        val fullSubText = buildNotificationSubText()

        // v2.4.101: displayTitle already computed above with date+time

        val builder = NotificationCompat.Builder(this, RadioApplication.CHANNEL_ID)
            .setContentTitle(displayTitle)
            .setContentText(fullSubText)
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
        writeServiceLog("notification", "buildMediaStyleNotification: AFTER notify - title='$notificationTitle', fullSubText='${buildNotificationSubText()}', date='$notificationDate', timeRange='$notificationTimeRange'")
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
        notificationStyle = prefs.getString("notification_style", "compact") ?: "compact"
    }

    private fun buildNotification(): Notification {
        // [v2.0.78] Issue 5/6 Fix: playing state includes pauseConfirmedUntil to prevent flicker.
        val inPauseConfirmWindow = System.currentTimeMillis() < pauseConfirmedUntil
        val playing = playbackStarted && !userPaused && !pausedByAudioFocus && !inPauseConfirmWindow
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
                .addAction(createNotificationAction(
                    if (playing) ACTION_PAUSE else ACTION_PLAY,
                    if (playing) R.drawable.notif_pause else R.drawable.notif_play,
                    if (playing) "暂停" else "播放", 12))
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
        // v2.4.101: Include date+time in title
        val dateStr = if (notificationDate.length >= 10) notificationDate.substring(5, 10) else ""
        val timeStr = notificationTimeRange
        // v2.4.107: Title = title + time range (no date)
        val displayTitleBuild = buildString {
            append(notificationTitle)
            if (timeStr.isNotBlank()) append(" $timeStr")
        }
        remoteViews.setTextViewText(R.id.notification_title, displayTitleBuild)
        val contentText = buildString {
            append(if (playing) "正在播放" else "已暂停")
            // v2.4.98: Combine date and start-end time as one unit
            if (dateStr.isNotBlank()) {
                append(" · $dateStr")
                if (timeStr.isNotBlank()) append(" $timeStr")
            } else if (timeStr.isNotBlank()) {
                append(" · $timeStr")
            }
        }
        remoteViews.setTextViewText(R.id.notification_subtitle, contentText)

        // [v2.0.74] Issue 5 Fix: buildNotification() (called from onCreate/onTaskRemoved) must
        // also set progress area visibility. Previously it didn't, so the default "gone" in XML
        // persisted when service restarted or task was removed, making progress bar disappear
        // until updateNotification() was next called.
        try {
            if (!isLive) {
                remoteViews.setViewVisibility(R.id.notification_progress_area, android.view.View.VISIBLE)
                val pos = getCurrentPosition()
                val dur = getSafeDuration()
                if (dur > 0) {
                    // [v2.0.77] Issue 6 Fix: Cap pos to dur
                    val safePos = pos.coerceIn(0L, dur)
                    val progress = ((safePos * 1000) / dur).toInt().coerceIn(0, 1000)
                    remoteViews.setProgressBar(R.id.notification_progress, 1000, progress, false)
                    val totalSec = dur.toInt() / 1000
                    val curSec = safePos.toInt() / 1000
                    remoteViews.setTextViewText(R.id.notification_time_text,
                        "${formatTimeNotif(curSec)}/${formatTimeNotif(totalSec)}")
                } else {
                    remoteViews.setProgressBar(R.id.notification_progress, 1000, 0, false)
                    remoteViews.setTextViewText(R.id.notification_time_text, "00:00/00:00")
                }
                writeServiceLog("notification", "[v2.0.74-PROGRESS] buildNotification: progress_area=VISIBLE, pos=$pos, dur=$dur")
            } else {
                remoteViews.setViewVisibility(R.id.notification_progress_area, android.view.View.GONE)
            }
        } catch (e: Exception) {
            // Minimal layout doesn't have progress_area - that's expected
            writeServiceLog("notification", "[v2.0.74-PROGRESS] buildNotification: layout doesn't support progress (minimal style): ${e.message}")
        }

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
        // v2.4.91: Support hours for content longer than 60 minutes
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%02d:%02d", minutes, secs)
        }
    }

    private fun loadSettings() {
        val prefMgr = PreferenceManager(this)
        val settings: AppSettings = prefMgr.loadSettings()
        continuousPlay = settings.continuousPlay
        savePlaybackPosition = settings.savePlaybackPosition
        // 从 radio_app_settings 读取（与 AppSettings.save() 和热切换监听器使用同一文件）
        val appPrefs = getSharedPreferences("radio_app_settings", Context.MODE_PRIVATE)
        notificationStyle = appPrefs.getString("notification_style", "compact") ?: "compact"
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
            isForegroundService = true  // [v2.0.77] mark as foreground
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

    // [v2.0.72] Issue 7 Fix: Audio focus debounce to prevent rapid pause/resume cycles
    // when other apps (notifications, short videos) repeatedly grab/release focus.
    // [v2.0.74] Initialize to currentTimeMillis to avoid timeSinceLast overflow (was 0, causing ~56 year values)
    private var lastAudioFocusChangeTime = System.currentTimeMillis()
    private var pendingAudioFocusResume = false
    private val audioFocusHandler = Handler(Looper.getMainLooper())
    private val audioFocusResumeRunnable = Runnable {
        // [v2.0.74] Issue 6/7 Fix: Only resume for TRANSIENT loss, not PERMANENT.
        // Permanent loss (Pinduoduo video) requires user to manually press play.
        if (pendingAudioFocusResume && pausedByAudioFocus && !userPaused && audioFocusLossType == FOCUS_LOSS_TRANSIENT) {
            writeServiceLog("audiofocus", "[v2.0.74] debounce: executing delayed resume after TRANSIENT loss")
            pendingAudioFocusResume = false
            pausedByAudioFocus = false
            audioFocusLossType = FOCUS_LOSS_NONE
            player?.volume = 1.0f
            play()
        } else {
            writeServiceLog("audiofocus", "[v2.0.74] debounce: NOT resuming (pending=$pendingAudioFocusResume, pausedByAF=$pausedByAudioFocus, userPaused=$userPaused, lossType=$audioFocusLossType)")
            pendingAudioFocusResume = false
            // If ducked, restore volume
            if (audioFocusLossType == FOCUS_LOSS_DUCK) {
                player?.volume = 1.0f
                audioFocusLossType = FOCUS_LOSS_NONE
                pausedByAudioFocus = false
            }
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        val now = System.currentTimeMillis()
        val timeSinceLastChange = now - lastAudioFocusChangeTime
        lastAudioFocusChangeTime = now
        writeServiceLog("audiofocus", "[v2.0.74] onAudioFocusChange: $focusChange (${focusChangeToString(focusChange)}), timeSinceLast=${timeSinceLastChange}ms, userPaused=$userPaused, pausedByAudioFocus=$pausedByAudioFocus, lossType=$audioFocusLossType, isPlaying=${player?.isPlaying}, playbackStarted=$playbackStarted")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Cancel any pending debounced resume and probing
                audioFocusHandler.removeCallbacks(audioFocusResumeRunnable)
                audioFocusHandler.removeCallbacks(permanentLossRecoveryRunnable)
                smartResumeRunning = false; audioFocusHandler.removeCallbacks(smartResumeRunnable)
                audioFocusHandler.removeCallbacks(focusProbeRunnable)  // [v2.0.77] stop probing
                focusRecoveryAttempts = 0

                // [v2.0.74] Issue 6/7 Fix: Handle based on loss type
                when (audioFocusLossType) {
                    FOCUS_LOSS_DUCK -> {
                        // Ducking: just restore volume, no resume needed (we never paused)
                        player?.volume = 1.0f
                        writeServiceLog("audiofocus", "[v2.0.74] AUDIOFOCUS_GAIN: restoring volume from duck, NOT resuming (was just ducked)")
                        pausedByAudioFocus = false
                        audioFocusLossType = FOCUS_LOSS_NONE
                        pendingAudioFocusResume = false
                        return
                    }
                    FOCUS_LOSS_PERMANENT -> {
                        // Permanent loss (Pinduoduo/Douyin video): try auto-resume on GAIN for 60s.
                        // Many Chinese apps incorrectly use AUDIOFOCUS_GAIN instead of GAIN_TRANSIENT.
                        // If GAIN arrives within 60s, resume. After 60s, give up and require manual play.
                        player?.volume = 1.0f
                        if (pausedByAudioFocus && !userPaused) {
                            writeServiceLog("audiofocus", "[v2.0.75] AUDIOFOCUS_GAIN: PERMANENT loss ended within 60s, auto-resuming (app like Pinduoduo used permanent focus incorrectly)")
                            pausedByAudioFocus = false
                            audioFocusLossType = FOCUS_LOSS_NONE
                            audioFocusHandler.removeCallbacks(permanentLossRecoveryRunnable)
                            smartResumeRunning = false; audioFocusHandler.removeCallbacks(smartResumeRunnable)
                            play()
                        } else {
                            writeServiceLog("audiofocus", "[v2.0.75] AUDIOFOCUS_GAIN: PERMANENT loss ended but not resuming (pausedByAF=$pausedByAudioFocus, userPaused=$userPaused)")
                            audioFocusLossType = FOCUS_LOSS_NONE
                            pausedByAudioFocus = false
                        }
                        return
                    }
                    FOCUS_LOSS_TRANSIENT -> {
                        // Transient loss (navigation, call): debounced resume
                        if (pausedByAudioFocus && !userPaused) {
                            pendingAudioFocusResume = true
                            audioFocusHandler.postDelayed(audioFocusResumeRunnable, 300)
                            writeServiceLog("audiofocus", "[v2.0.74] AUDIOFOCUS_GAIN: scheduling debounced resume in 300ms (TRANSIENT loss)")
                        } else {
                            writeServiceLog("audiofocus", "[v2.0.74] AUDIOFOCUS_GAIN: TRANSIENT loss ended but not resuming (pausedByAF=$pausedByAudioFocus, userPaused=$userPaused)")
                            audioFocusLossType = FOCUS_LOSS_NONE
                        }
                        return
                    }
                    else -> {
                        // No recorded loss: check if we're playing and restore volume
                        player?.volume = 1.0f
                        if (player?.isPlaying == true && !userPaused) {
                            writeServiceLog("audiofocus", "[v2.0.74] AUDIOFOCUS_GAIN: already playing, restoring volume")
                        } else if (!userPaused && !playbackInitializing && playbackStarted) {
                            writeServiceLog("audiofocus", "[v2.0.74] AUDIOFOCUS_GAIN: no loss recorded but not playing, attempting resume")
                            play()
                        } else {
                            writeServiceLog("audiofocus", "[v2.0.74] AUDIOFOCUS_GAIN: no action needed (userPaused=$userPaused, initializing=$playbackInitializing)")
                        }
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent focus loss (Pinduoduo/Douyin video playing long-form content)
                audioFocusHandler.removeCallbacks(audioFocusResumeRunnable)
                audioFocusHandler.removeCallbacks(permanentLossRecoveryRunnable)
                smartResumeRunning = false; audioFocusHandler.removeCallbacks(smartResumeRunnable)
                audioFocusHandler.removeCallbacks(focusProbeRunnable)  // [v2.0.77] cancel any prior probe
                focusRecoveryAttempts = 0
                pendingAudioFocusResume = false
                audioFocusLossType = FOCUS_LOSS_PERMANENT
                if (!userPaused && playbackStarted) {
                    pausedByAudioFocus = true
                    // [v2.1.2] Read detection interval from settings (default 5s, was hardcoded 15s)
                    try {
                        val settings = AppSettings.getInstance(this@RadioPlaybackService)
                        smartResumePollMs = settings.pinduoduoDetectionInterval.toLong() * 1000
                    } catch (_: Exception) { smartResumePollMs = 5000L }
                    writeServiceLog("audiofocus", "[v2.1.2] AUDIOFOCUS_LOSS (PERMANENT): pausing, starting smart resume polling every ${smartResumePollMs/1000}s")
                    pause(userInitiated = false)
                    // [v2.0.88] Issue 3 Fix: Start smart resume polling.
                    // Instead of waiting passively for GAIN, actively check if other
                    // apps stopped playing, then re-request focus.
                    smartResumeRunning = true
                    audioFocusHandler.postDelayed(smartResumeRunnable, smartResumePollMs)
                } else {
                    writeServiceLog("audiofocus", "[v2.0.77] AUDIOFOCUS_LOSS (PERMANENT): not pausing (userPaused=$userPaused, playbackStarted=$playbackStarted)")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Temporary loss (navigation voice, phone call, notification sound with audio)
                audioFocusHandler.removeCallbacks(audioFocusResumeRunnable)
                audioFocusHandler.removeCallbacks(focusProbeRunnable)  // [v2.0.77] cancel probe
                focusRecoveryAttempts = 0
                pendingAudioFocusResume = false
                audioFocusLossType = FOCUS_LOSS_TRANSIENT
                if (!userPaused && playbackStarted && player?.isPlaying == true) {
                    pausedByAudioFocus = true
                    writeServiceLog("audiofocus", "[v2.0.74] AUDIOFOCUS_LOSS_TRANSIENT: pausing for transient interruption (navigation/call), will auto-resume")
                    pause(userInitiated = false)
                } else if (!userPaused && playbackStarted) {
                    // Player is buffering or preparing, mark flag but don't force pause
                    pausedByAudioFocus = true
                    writeServiceLog("audiofocus", "[v2.0.74] AUDIOFOCUS_LOSS_TRANSIENT: player not playing (buffering?), marking flag for resume")
                } else {
                    writeServiceLog("audiofocus", "[v2.0.74] AUDIOFOCUS_LOSS_TRANSIENT: not pausing (userPaused=$userPaused, playbackStarted=$playbackStarted)")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Short audio chime (navigation turn indicator, notification beep)
                // [v2.0.74] Issue 7 Fix: ONLY duck, do NOT pause or set pausedByAudioFocus.
                // Previous bug: this was grouped with LOSS_TRANSIENT and incorrectly set
                // pausedByAudioFocus=true even though we only ducked, causing state mismatch.
                audioFocusHandler.removeCallbacks(audioFocusResumeRunnable)
                pendingAudioFocusResume = false
                audioFocusLossType = FOCUS_LOSS_DUCK
                player?.volume = 0.2f
                writeServiceLog("audiofocus", "[v2.0.74] AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: ducking volume to 20% (no pause, will restore on GAIN)")
            }
        }
    }

    private fun focusChangeToString(fc: Int): String = when (fc) {
        AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
        AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
        else -> "UNKNOWN($fc)"
    }

    fun playStation(station: RadioStation) {
        Log.d(TAG, "playStation called: ${station.name}, playbackStarted before: $playbackStarted, url=${station.streamUrl}")
        currentStation = station; currentEpisode = null; isLive = true
        prepared = false; currentStreamUrl = station.streamUrl ?: ""
        currentPlayingUrl = currentStreamUrl
        playbackStarted = true
        userPaused = false // Starting new playback, not user-paused
        errorRetryCount = 0; isRetrying = false; stopAutoSkipCheck()
        // [v2.3.1] Cancel pending retry from previous failed playback (null-safe)
        retryRunnable?.let { retryHandler?.removeCallbacks(it) }
        retryRunnable = null
        playbackInitializing = false
        episodeSwitching = false
        positionRestoreRequested = false
        downloadProgressPct = 0; downloadDoneBytes = 0; downloadTotalBytes = 0
        ensurePlayerInitialized()
        try {
            player?.let {
                // [v2.3.0-fix] Stop and clear previous media before setting new one.
                // This ensures ExoPlayer exits any error state from a previous failed episode,
                // preventing cascading playback failures.
                try { it.stop() } catch (_: Exception) {}
                it.clearMediaItems()
                it.setMediaItem(MediaItem.fromUri(currentStreamUrl))
                it.prepare(); it.playWhenReady = true
            }
            notificationTitle = station.name; notificationSubText = "[直播]"
            notificationDate = ""; notificationTimeRange = ""; notificationPlaying = true
            requestAudioFocus(); notifyNotification()
            updateMediaSessionState()
        } catch (e: Exception) { Log.e(TAG, "playStation failed", e) }
    }

    fun playEpisode(episode: Episode?, live: Boolean, startPositionMs: Long = -1L) {
        // [v2.3.2] Null-safety: episode can be null in rare race conditions
        if (episode == null) {
            Log.e(TAG, "playEpisode: episode is NULL, ignoring")
            writeServiceLog("playback", "playEpisode: episode is NULL, ignoring")
            return
        }

        // v2.4.106: Force-save current episode's position BEFORE switching.
        // Previously, position was only saved by periodic timer (every 5s) and
        // on pause. If user switches episodes quickly, the current episode's
        // position might not be saved, causing progress to be lost.
        if (currentEpisode != null && currentEpisode?.id != episode.id) {
            val oldEp = currentEpisode!!
            val oldPos = getCurrentPosition()
            if (oldPos > 1000 && oldEp.id?.isNotBlank() == true) {
                // v2.4.118: Check for stale position before force-saving in playEpisode.
                // This is the main path where stale positions get saved — getCurrentPosition()
                // returns authoritativePosition which may have been corrupted by STATE_READY
                // reporting the old episode's position after setMediaItem.
                val staleDelta = if (episodeStartPos > 0) oldPos - episodeStartPos else 0
                if (staleDelta > 120000 || staleDelta < -5000) {
                    writeServiceLog("playback", "[v2.4.118] playEpisode: force-saved REJECTED (oldPos=$oldPos vs episodeStartPos=$episodeStartPos, delta=${staleDelta}ms, likely stale) for oldEpId=${oldEp.id}")
                } else {
                    getSharedPreferences("playback_positions", MODE_PRIVATE)
                        .edit().putLong(oldEp.id!!, oldPos).commit()
                    writeServiceLog("playback", "[v2.4.106] playEpisode: force-saved pos=$oldPos for oldEpId=${oldEp.id} before switching to ${episode.id}")
                }
            }
            // v2.4.108: episodeSwitching flag (set above) now blocks saveCurrentPosition
            // until STATE_READY fires for the new episode. No need for lastPositionRestoreTime.
        }
        val epTitle = try { episode.title ?: "unknown" } catch (_: Exception) { "unknown" }
        val epAudioUrl = try { episode.audioUrl ?: "" } catch (_: Exception) { "" }
        Log.d(TAG, "playEpisode called: $epTitle, playbackStarted before: $playbackStarted, prepared=$prepared, url=$epAudioUrl, startPositionMs=$startPositionMs")
        if (epAudioUrl.isEmpty()) {
            Log.e(TAG, "playEpisode: episode audioUrl is empty, ignoring")
            writeServiceLog("playback", "playEpisode: audioUrl is empty for $epTitle")
            try { callback?.onError("节目音频URL为空") } catch (_: Exception) {}
            return
        }

        // [v2.0.73] Issue 1 Fix: Debounce rapid playEpisode calls for the SAME episode within 500ms.
        // When PlayerActivity reconnects or user taps quickly, duplicate calls cause player reset/prepare
        // cycles that produce position=0 and seekTo oscillation.
        val now = System.currentTimeMillis()
        // [v2.0.91] Reset skip protection state on new episode (but don't clear active breaker)
        lastEpisodeStartTime = now
        skipRequestCount = 0
        if (now >= skipCircuitBreakerUntil) {
            skipCircuitBreakerUntil = 0L
        }
        consecutiveBackwardSkips = 0
        firstBackwardSkipWindowStart = now
        val epId = episode.id ?: ""
        if (epId == lastPlayEpisodeEpisodeId && now - lastPlayEpisodeTime < 500) {
            Log.d(TAG, "playEpisode: [v2.0.73] debounced duplicate call for $epTitle (${now - lastPlayEpisodeTime}ms ago), skipping")
            writeServiceLog("playback", "playEpisode: [v2.0.73] DEBOUNCED duplicate for $epId")
            return
        }
        lastPlayEpisodeEpisodeId = epId
        lastPlayEpisodeTime = now

        // [v2.0.69] Issue 1 Fix: Only preserve position when the service was actually running
        // (playbackStarted=true). When the service was killed (playbackStarted=false),
        // authoritativePosition is stale from the old service instance and must NOT be used.
        // The player will be recreated and seek to startPositionMs instead.
        val isSameEpisode = currentEpisode != null && currentEpisode?.id == episode.id
        val wasServiceRunning = playbackStarted
        val preservedPosition = if (isSameEpisode && wasServiceRunning && authoritativePosition > 0) {
            Log.d(TAG, "playEpisode: same episode, service running, preserving authoritativePosition=$authoritativePosition")
            authoritativePosition
        } else -1L
        if (isSameEpisode && !wasServiceRunning) {
            Log.d(TAG, "playEpisode: same episode but service was killed, NOT preserving stale authoritativePosition=$authoritativePosition, will use startPositionMs=$startPositionMs")
        }

        currentEpisode = episode; currentStation = null; isLive = live
        prepared = false; errorRetryCount = 0; isRetrying = false
        // [v2.3.1] Cancel any pending retry from previous failed episode (null-safe)
        retryRunnable?.let { retryHandler?.removeCallbacks(it) }
        retryRunnable = null
        // [v2.3.1] Reset all state flags that could block new playback
        playbackInitializing = false
        episodeSwitching = !isSameEpisode
        // [v2.0.75] Issue 5 Fix: When switching to a DIFFERENT episode (cross-day), reset
        // authoritativePosition to startPositionMs (or 0) so notification doesn't show old episode's
        // position (1028s) on the new episode. v2.0.74 logs showed pos=1028118 from old episode
        // appearing on new episode immediately after cross-day switch.
        if (!isSameEpisode) {
            authoritativePosition = if (startPositionMs >= 0) startPositionMs else 0L
            maxKnownPosition = authoritativePosition
            // v2.4.116: Save the episode start position for continuous stale-position validation.
            // Unlike lastPositionRestoreTime (10s window), episodeStartPos is ALWAYS checked in
            // getCurrentPosition() to reject rawPos values that are far behind the start position
            // (indicating they come from the old episode leaking through ExoPlayer).
            episodeStartPos = if (startPositionMs >= 0) startPositionMs else 0L
            // v2.4.116: Reset stale position rejection counter for new episode
            stalePosRejectCount = 0
            // [v2.0.92] Reset lastValidDurationMs to prevent getSafeDuration() from returning
            // the old episode's duration during the transition window, which causes the
            // notification progress bar to show 100% (old pos / old dur).
            lastValidDurationMs = 0L
            writeServiceLog("notification", "[v2.0.75-PROGRESS] episode switch: reset pos to $authoritativePosition, lastValidDur=0 for new ep=${episode.id}")
        }
        // [v2.0.72] Issue 5 Fix: Reset notification state on new episode to prevent
        // stale position/title from previous episode showing in notification bar.
        lastNotifiedPosition = -1L
        // v2.4.117: Don't reset lastNotificationContentHash — forceNotificationUpdate=true is sufficient.
        // Resetting the hash destroys dedup state, causing duplicate notifications during episode switch.
        forceNotificationUpdate = true
        // [v2.4.6] Reset stuck-at-end detection for new episode
        stuckAtEndSince = 0L
        stuckAtEndPos = 0L
        // [v2.0.69] Issue 1 Fix: When service was killed (preservedPosition=-1), use startPositionMs
        // as authoritative position. This is the actual position the player will seek to.
        // Old v2.0.68 code always used preservedPosition for same episode, but when service was killed,
        // preservedPosition is stale and the player actually seeks to startPositionMs.
        // [v2.0.62] Issue 1 Fix (PowerAmp approach): Initialize authoritative position
        if (preservedPosition >= 0) {
            // Same episode, service still running: preserve current position (just resume)
            Log.d(TAG, "playEpisode: same episode, service running, using preservedPosition=$preservedPosition")
            authoritativePosition = preservedPosition
            maxKnownPosition = preservedPosition
            seekTargetPosition = preservedPosition
            isSeekingToPosition = true
        } else if (startPositionMs >= 0) {
            // Service was killed or different episode with saved position: use saved position
            Log.d(TAG, "playEpisode: using startPositionMs=$startPositionMs as authoritativePosition")
            authoritativePosition = startPositionMs
            maxKnownPosition = startPositionMs
            seekTargetPosition = startPositionMs
            isSeekingToPosition = true
        } else {
            authoritativePosition = 0L
            maxKnownPosition = 0L
            seekTargetPosition = 0L
            isSeekingToPosition = false
        }
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
        // v2.4.117: removed lastNotificationContentHash=0 — forceNotificationUpdate=true is sufficient
        forceNotificationUpdate = true  // Issue 3: bypass content hash check for next notification update
        precacheNotificationShown = false  // Reset for new episode
        // [v2.2.7] Don't reset lastPreCacheCheckTime to 0 on episode change.
        // This caused pre-cache to be re-triggered every time user switches episodes,
        // even within the 2-minute throttle window. The throttle will naturally allow
        // the next check after the window expires.
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
        // [v2.0.72] Issue 5 Fix: Fallback: parse date/time from URL (e.g., sijiache_20240604_0700_0900.mp4)
        // ONLY use URL fallback if date was NOT parsed from broadcastAt (fixed logic: was `|| length>=10`
        // which ALWAYS overwrote the properly parsed date).
        if (notificationDate.isBlank()) {
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
        // [Issue 5 Fix] 节目切换后立即刷新 MediaSession 元数据，确保通知栏显示新一集信息
        updateMediaSessionMetadata()
        val notification = updateNotification()
        // 立即推送前台通知，确保通知栏立即更新，不被进度轮询覆盖
        // [v2.0.77] Issue 6 Fix: Use notify() if already foreground
        if (!isForegroundService) {
            isForegroundService = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            try {
                (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                    .notify(NOTIFICATION_ID, notification)
            } catch (_: Exception) {}
        }
        writeServiceLog("notification", "playEpisode: title=${episode.title}, notificationTitle=$notificationTitle, notificationSubText=$notificationSubText, notificationDate=$notificationDate, updateNotification called")
        writeServiceLog("notification", "playEpisode: FULL subText='${buildNotificationSubText()}', notificationTitle='$notificationTitle', notificationDate='$notificationDate', notificationTimeRange='$notificationTimeRange'")
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
                // v2.4.108: Remove stop() + clearMediaItems() which caused player stall.
                // stop() puts player in STATE_IDLE, and subsequent seekTo() doesn't
                // take effect. Instead, use setMediaItem(item, startPos) which sets
                // the start position BEFORE loading, so ExoPlayer seeks during
                // initial buffering rather than after.
                // setMediaItem() already replaces all previous items (no need for clearMediaItems).
                if (startPositionMs >= 0) {
                    // [v2.2.2] Set playWhenReady=true so playback starts after seek
                    it.playWhenReady = true
                    // v2.4.108: Use setMediaItem(item, startPos) instead of setMediaItem(item) + seekTo()
                    // This sets the start position before the media is loaded, which is more reliable.
                    it.setMediaItem(MediaItem.fromUri(currentStreamUrl), startPositionMs)
                    it.prepare()
                    // [v2.0.54] Clear positionRestoreRequested so STATE_READY doesn't seek again
                    positionRestoreRequested = false
                    // v2.4.46: CRITICAL FIX - Also clear pendingStartPosition!
                    pendingStartPosition = -1L
                    // v2.4.113: Set lastPositionRestoreTime to block saveCurrentPosition for 5 seconds
                    // after episode switch. Root cause of progress interference: episodeSwitching
                    // is cleared on STATE_READY, but getCurrentPosition() still returns old episode's
                    // position for a brief window. Periodic saveCurrentPosition fires during this
                    // window, saving old episode's position to new episode's SharedPreferences.
                    // Additionally, isPastEnd check fires (old pos > new dur) → clearSavedPosition
                    // → autoPlayNextEpisode with wrong startPos. The 5-second block prevents both.
                    lastPositionRestoreTime = System.currentTimeMillis()
                    writeServiceLog("playback", "[v2.4.108] playEpisode: setMediaItem+startPos($startPositionMs), prepared")
                } else {
                    it.playWhenReady = true
                    it.setMediaItem(MediaItem.fromUri(currentStreamUrl))
                    it.prepare()
                }
            }
            requestAudioFocus()
            updateMediaSessionState()
            startAutoSkipCheck()
            // [v2.1.4] If this was a cross-episode segment jump, seek to target segment
            onCrossEpisodeSwitchComplete()
        } catch (e: Exception) { Log.e(TAG, "playEpisode failed", e) }
        // Force immediate notification update
        // v2.4.117: removed lastNotificationContentHash=0 — forceNotificationUpdate=true is sufficient
        forceNotificationUpdate = true  // Issue 3: bypass content hash check
        notifyNotification()
    }

    fun play() {
        userPaused = false
        // [v2.0.74] Issue 6 Fix: When user manually presses play, clear audio focus loss state.
        pausedByAudioFocus = false
        audioFocusLossType = FOCUS_LOSS_NONE
        pendingAudioFocusResume = false
        audioFocusHandler.removeCallbacks(audioFocusResumeRunnable)
        // [v2.0.76] Also cancel permanent loss recovery timer
        audioFocusHandler.removeCallbacks(permanentLossRecoveryRunnable)
        smartResumeRunning = false; audioFocusHandler.removeCallbacks(smartResumeRunnable)
        // [v2.0.77] Issue 5 Fix: Also cancel active focus probing
        audioFocusHandler.removeCallbacks(focusProbeRunnable)
        focusRecoveryAttempts = 0
        // [v2.0.91] User-initiated play resets skip storm state (but preserve active breaker)
        skipRequestCount = 0
        val nowPlay = System.currentTimeMillis()
        if (nowPlay >= skipCircuitBreakerUntil) {
            skipCircuitBreakerUntil = 0L
        }
        consecutiveBackwardSkips = 0
        firstBackwardSkipWindowStart = nowPlay
        // [v2.0.78] Issue 5 Fix: Clear pause confirmation window on play
        pauseConfirmedUntil = 0L

        // [v2.3.5] If player is null (was released after error), recreate it and resume current episode
        if (player == null) {
            writeServiceLog("playback", "[v2.3.5] play(): player is null, attempting recovery")
            // Reset error state so we can retry
            errorRetryCount = 0
            isRetrying = false
            if (currentEpisode != null && currentStreamUrl.isNotEmpty()) {
                writeServiceLog("playback", "[v2.3.5] play(): recovering with current episode at pos=${authoritativePosition}ms")
                playEpisode(currentEpisode!!, isLive, authoritativePosition.coerceAtLeast(0L))
                return
            } else {
                writeServiceLog("playback", "[v2.3.5] play(): no current episode to recover, callback onError")
                try { callback?.onError("播放器已重置，请重新选择节目") } catch (_: Exception) {}
                return
            }
        }

        // [v2.0.87] Fix: If player has ended (STATE_ENDED), seek to 0 before playing.
        // ExoPlayer does NOT restart from beginning when play() is called in STATE_ENDED.
        // This was the root cause of "notification stuck at 100% progress, pause/play doesn't fix it".
        val playerState = player?.playbackState ?: Player.STATE_IDLE
        if (playerState == Player.STATE_ENDED) {
            writeServiceLog("playback", "[v2.0.87] play(): player in STATE_ENDED, seeking to 0 before play")
            player?.seekTo(0)
            authoritativePosition = 0L
            maxKnownPosition = 0L
            lastNotifiedPosition = -1L
            isSeekingToPosition = false
        }

        requestAudioFocus()
        player?.play()
        // [v2.3.5] Only set prepared=true if player actually exists
        if (player != null) {
            prepared = true
        }
        // [v2.0.76] Issue 6 Fix: Immediately update MediaSession to STATE_PLAYING
        writeServiceLog("notification", "[v2.0.87] play() called, userPaused=false, updating MediaSession to STATE_PLAYING")
        forceNotificationUpdate = true
        // v2.4.117: removed lastNotificationContentHash=0 — force flag is sufficient
        mediaSession?.setPlaybackState(PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_PLAYING, getCurrentPosition(), 1.0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP)
            .build())
        val notification = updateNotification()
        // [v2.0.77] Issue 6 Fix: Use notify() instead of startForeground() after initial startup
        // to prevent notification flicker/disappear on OEM devices.
        if (!isForegroundService) {
            isForegroundService = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            try {
                (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                    .notify(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                Log.w(TAG, "notify failed, falling back to startForeground", e)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }
    }
    fun pause(userInitiated: Boolean = true) {
        if (userInitiated) {
            userPaused = true
            // [v2.0.77] When user manually pauses, clear audio focus recovery state
            pausedByAudioFocus = false
            audioFocusLossType = FOCUS_LOSS_NONE
        }
        // [v2.0.78] Issue 5 Fix: Set pause-confirmed window (2 seconds) to prevent notification
        // flicker. ExoPlayer's isPlaying may briefly return true after pause() due to state
        // propagation delay; buildNotification() checks this flag to force playing=false.
        pauseConfirmedUntil = System.currentTimeMillis() + 2000L
        // [v2.0.91] Reset skip protection state on pause - user is actively controlling playback
        skipRequestCount = 0
        val nowPause = System.currentTimeMillis()
        if (nowPause >= skipCircuitBreakerUntil) {
            skipCircuitBreakerUntil = 0L
        }
        inPostResumeProtection = false
        consecutiveBackwardSkips = 0
        firstBackwardSkipWindowStart = nowPause
        // [v2.0.76] Cancel any pending audio focus resume timers when pausing
        audioFocusHandler.removeCallbacks(audioFocusResumeRunnable)
        audioFocusHandler.removeCallbacks(permanentLossRecoveryRunnable)
        smartResumeRunning = false; audioFocusHandler.removeCallbacks(smartResumeRunnable)
        audioFocusHandler.removeCallbacks(focusProbeRunnable)  // [v2.0.77] cancel probe
        focusRecoveryAttempts = 0
        player?.pause()
        // [v2.0.78] Issue 5/6 Fix: Immediately update MediaSession PlaybackState to STATE_PAUSED
        writeServiceLog("notification", "[v2.0.78] pause(userInitiated=$userInitiated) called, userPaused=$userPaused, pausedByAF=$pausedByAudioFocus, updating MediaSession to STATE_PAUSED")
        forceNotificationUpdate = true
        // v2.4.117: removed lastNotificationContentHash=0 — force flag is sufficient
        mediaSession?.setPlaybackState(PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_PAUSED, getCurrentPosition(), 1.0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP)
            .build())
        val notification = updateNotification()
        // [v2.0.77] Issue 6 Fix: Use notify() instead of startForeground() after initial startup
        if (!isForegroundService) {
            isForegroundService = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            try {
                (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                    .notify(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                Log.w(TAG, "notify failed in pause(), falling back to startForeground", e)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }
    }
    fun stop() {
        stopAutoSkipCheck(); saveCurrentPosition()
        downloadingJob?.cancel()
        downloadActive.set(false)
        player?.let { it.stop(); abandonAudioFocus() }
        isForegroundService = false  // [v2.0.77] reset foreground flag
        stopForeground(STOP_FOREGROUND_REMOVE)  // [v2.0.77] properly exit foreground before cancel
        (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .cancel(NOTIFICATION_ID)
        stopSelf()
    }
    fun seekTo(pos: Long) {
        if (isLive) return
        // v2.4.110: Block ALL seekTo calls during episode switching.
        // During episode switch, playEpisode() sets the start position via
        // setMediaItem(item, startPos). External callers (MediaSession onSeekTo,
        // Activity reconnection, etc.) may call seekTo with OLD saved positions
        // that override the correct start position. Since the start position is
        // already set, any seekTo during this window is unnecessary and harmful.
        if (episodeSwitching) {
            writeServiceLog("playback", "[v2.4.110] seekTo($pos) BLOCKED (episodeSwitching=true, start pos already set via setMediaItem)")
            return
        }
        val now = System.currentTimeMillis()
        // [v2.1.7] Removed largeBackward and zeroStorm anti-seek-storm protections.
        // These protections blocked ALL legitimate backward seeks >45s, including:
        // - Previous segment button (segment jumps are typically 15min = 900s apart)
        // - Progress bar drag to earlier position
        // - Search result jump to a specific timestamp
        // Only keep rate-limiting (200ms) and duplicate detection (500ms same pos).
        val dupSamePos = (pos == lastSeekTargetPos) && (now - lastSeekCallTime < 500L)
        val rateLimited = (now - lastSeekCallTime < 200L) && lastSeekCallTime > 0
        if (dupSamePos) {
            writeServiceLog("playback", "[v2.1.7] seekTo: DROPPED duplicate seek to $pos (lastSeek was ${now - lastSeekCallTime}ms ago)")
            return
        }
        if (rateLimited) {
            writeServiceLog("playback", "[v2.1.7] seekTo: DROPPED rate-limited seek to $pos (lastSeek was ${now - lastSeekCallTime}ms ago)")
            return
        }
        lastSeekCallTime = now
        lastSeekTargetPos = pos
        // [v2.0.72] Issue 1/10 Fix: Queue seek when player is not prepared instead of
        // silently dropping it. Previously, seeking during episode switch/buffering
        // (prepared=false) would return immediately with no effect.
        isSeekingToPosition = true
        seekTargetPosition = pos
        authoritativePosition = pos
        // v2.4.116: Update episodeStartPos on user seek so the REJECTED check in
        // saveCurrentPosition uses the seeked position as the new reference point.
        episodeStartPos = pos
        stalePosRejectCount = 0
        // [v2.0.91] Only update maxKnownPosition when seeking FORWARD — never lower it.
        // maxKnownPosition is used for anti-storm detection (zeroStorm, largeBackward) and must
        // remain monotonic. When user skips backward or a misfire seeks to 0, maxKnownPosition
        // must keep the highest position we've ever reached.
        if (pos > maxKnownPosition) {
            maxKnownPosition = pos
        }
        if (prepared && player != null) {
            player?.seekTo(pos)
            writeServiceLog("playback", "[v2.0.77] seekTo: immediate seek to $pos (prepared=true)")
            // [v2.4.6] Save position immediately after seek to prevent loss on crash.
            // Previously position was only saved periodically while playing.
            // If the app crashes (e.g., Whisper OOM), the last seek position would be lost.
            // v2.4.63: Use episode ID as key (unique per episode, not shared across dates)
            val ep = currentEpisode
            if (ep != null && pos > 0 && !isLive) {
                val episodeKey = ep.id ?: ""
                if (episodeKey.isNotBlank()) {
                    getSharedPreferences("playback_positions", MODE_PRIVATE)
                        .edit().putLong(episodeKey, pos).commit()  // commit() = synchronous write
                    writeServiceLog("playback", "[v2.4.63] seekTo: saved position=$pos for episodeId=$episodeKey")
                }
            }
        } else {
            // Player not ready - store seek and apply when STATE_READY fires
            pendingStartPosition = pos
            positionRestoreRequested = true
            writeServiceLog("playback", "[v2.0.77] seekTo: queued seek to $pos (prepared=$prepared, will apply on STATE_READY)")
        }
    }
    fun skipForward() {
        if (isLive) return
        val now = System.currentTimeMillis()

        // [v2.0.88] Self-extending circuit breaker + post-breaker cooldown (same as skipBackward).
        val inBlackout = lastClientBindTime > 0 && now - lastClientBindTime < POST_RESUME_BLACKOUT_MS
        val inBreaker = now < skipCircuitBreakerUntil

        if (inBlackout || inBreaker) {
            // v2.4.35: Log but still allow - the blackout was blocking ALL user skips
            writeServiceLog("playback", "[v2.4.35] skipForward: would be blocked by ${if (inBlackout) "blackout" else "breaker"}, but ALLOWING (user-initiated)")
        }

        // [v2.4.44] REMOVED post-breaker cooldown for skipForward.
        // With storm detection removed, breaker will never trip, so cooldown is dead code.

        // [v2.0.86] After protection window: only basic checks, no rate limiting
        // Episode-change cooldown
        if (lastEpisodeStartTime > 0 && now - lastEpisodeStartTime < EPISODE_CHANGE_SKIP_COOLDOWN_MS) {
            writeServiceLog("playback", "[v2.0.86] skipForward: BLOCKED by episode-change cooldown (${now - lastEpisodeStartTime}ms)")
            return
        }
        // Debounce: 500ms to prevent double-tap
        if (now - lastSkipDirectionTime < SKIP_DEBOUNCE_MS && lastSkipDirectionTime > 0) {
            writeServiceLog("playback", "[v2.0.86] skipForward: DROPPED (debounced, ${now - lastSkipDirectionTime}ms)")
            return
        }
        lastSkipDirectionTime = now

        val curPos = getCurrentPosition()
        val pPos = curPos + skipSeconds * 1000
        val dur = if (prepared) player?.duration ?: 0 else currentEpisode?.let {
            val d = it.duration; if (d < 100000) d * 1000 else d
        } ?: 0
        val targetPos = if (dur > 0 && pPos > dur) dur else pPos
        writeServiceLog("playback", "[v2.0.91] skipForward: curPos=$curPos -> targetPos=$targetPos")
        // [v2.0.91] Reset backward skip counter when user skips forward (active user control)
        consecutiveBackwardSkips = 0
        firstBackwardSkipWindowStart = now
        seekTo(targetPos)
    }
    fun skipBackward() {
        if (isLive) return
        val now = System.currentTimeMillis()

        // [v2.0.91] Strengthened protection: blackout + breaker + post-breaker cooldown
        // + consecutive backward skip limit (prevents frog-boiling to 0).
        val inBlackout = lastClientBindTime > 0 && now - lastClientBindTime < POST_RESUME_BLACKOUT_MS
        val inBreaker = now < skipCircuitBreakerUntil

        if (inBlackout || inBreaker) {
            // v2.4.35: Log but still allow - the blackout was blocking ALL user skips
            // for 5 seconds after switching back to app, making buttons appear "unavailable"
            writeServiceLog("playback", "[v2.4.35] skipBackward: would be blocked by ${if (inBlackout) "blackout" else "breaker"}, but ALLOWING (user-initiated)")
        }

        // [v2.4.44] REMOVED post-breaker cooldown for skipBackward.
        // With storm detection removed, breaker will never trip, so cooldown is dead code.

        // Episode-change cooldown
        if (lastEpisodeStartTime > 0 && now - lastEpisodeStartTime < EPISODE_CHANGE_SKIP_COOLDOWN_MS) {
            writeServiceLog("playback", "[v2.0.91] skipBackward: BLOCKED by episode-change cooldown (${now - lastEpisodeStartTime}ms)")
            return
        }

        // [v2.4.44] REMOVED consecutive backward skip storm detection.
        // This was blocking legitimate user skips after 4 rapid presses (MAX_CONSECUTIVE_BACKWARD_SKIPS=3).
        // User reported "skip buttons sometimes unavailable" - this was the cause.
        // SEEK-LOCK in PlayerActivity now handles position correctly during rapid skips.
        // The storm detection was originally for misbehaving notification/headset buttons,
        // but it also blocked intentional rapid seeking by the user.

        // [v2.0.91] Strengthened debounce: 1000ms (was 500ms) to prevent rapid repeats
        // [v2.4.36] Reduced debounce from 1000ms to 200ms - 1000ms was dropping too many
        // legitimate user skip requests. UI already has 500ms debounce.
        if (now - lastSkipDirectionTime < 200L && lastSkipDirectionTime > 0) {
            writeServiceLog("playback", "[v2.0.91] skipBackward: DROPPED (debounced, ${now - lastSkipDirectionTime}ms)")
            return
        }

        val curPos = getCurrentPosition()
        val targetPos = maxOf(0, curPos - skipSeconds * 1000)

        // Low-position dedup
        if (targetPos == 0L) {
            if (now - lastSeekToZeroTime < LOW_POSITION_SKIP_DEDUP_MS && lastSeekToZeroTime > 0) {
                writeServiceLog("playback", "[v2.0.91] skipBackward: DROPPED low-pos dedup (targetPos=0, ${now - lastSeekToZeroTime}ms since last seekTo(0))")
                return
            }
            lastSeekToZeroTime = now
        }

        lastSkipDirectionTime = now
        writeServiceLog("playback", "[v2.0.91] skipBackward: curPos=$curPos -> targetPos=$targetPos (consecutive=$consecutiveBackwardSkips)")
        seekTo(targetPos)
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
    fun getCurrentPosition(): Long {
        val rawPos = player?.currentPosition ?: 0L
        // [v2.0.62] Issue 1 Fix (PowerAmp approach): Authoritative position
        // The service NEVER reports 0 when there's a known target position.
        // During preparation/seeking/buffering, ExoPlayer reports 0 or intermediate positions.
        // We return authoritativePosition which tracks where playback SHOULD be.
        // [v2.0.72] Issue 5 Fix: During seek (isSeekingToPosition), report target position.
        if (isSeekingToPosition && seekTargetPosition > 0) {
            // During seek: if player reports 0 or very small position (still buffering to target),
            // return the seek target. If player reports position near target, accept it and clear seek state.
            if (rawPos < seekTargetPosition - 2000) {
                // Still buffering/seeking - report target position, NOT 0
                return maxOf(authoritativePosition, seekTargetPosition)
            } else {
                // Close enough to target - clear seeking state and use player position
                isSeekingToPosition = false
                if (rawPos > authoritativePosition) {
                    authoritativePosition = rawPos
                }
                if (rawPos > maxKnownPosition) {
                    maxKnownPosition = rawPos
                }
            }
        }
        // [v2.0.72] Issue 5 Fix: When not prepared and starting a new episode without
        // a saved position, authoritativePosition was set to 0 in playEpisode().
        // When not prepared AND not seeking, don't let stale rawPos leak through.
        // If authoritativePosition is 0 (fresh episode start) and player reports a non-zero
        // position from previous media item, ignore it.
        if (!prepared) {
            // [v2.4.17] During episode switch, return authoritativePosition only.
            // This prevents old episode's seekTargetPosition from leaking through isSeekingToPosition.
            if (episodeSwitching) {
                return authoritativePosition
            }
            // When not prepared, only return authoritativePosition (set by playEpisode/seekTo).
            // Do NOT update from rawPos during preparation - rawPos may be from old media source.
            if (isSeekingToPosition) {
                return maxOf(authoritativePosition, seekTargetPosition)
            }
            return authoritativePosition
        }
        // Normal playback: maintain monotonic position (never go backward)
        // Only update from rawPos if player is prepared and position moves forward
        // v2.4.117: Use centralized safeUpdatePosition() which rejects stale positions >60s ahead.
        // This replaces the inline check from v2.4.116 and ensures ALL update paths use the same logic.
        if (prepared && rawPos > authoritativePosition) {
            safeUpdatePosition(rawPos)
        }
        // Also maintain maxKnownPosition for backward compatibility
        if (rawPos > maxKnownPosition && prepared) {
            maxKnownPosition = rawPos
        } else if (!prepared && maxKnownPosition < authoritativePosition) {
            maxKnownPosition = authoritativePosition
        }
        return authoritativePosition
    }

    // v2.0.73: Safely get player duration, filtering out invalid values (0, negative, TIME_UNSET).
    // Returns last known valid duration or default 2 hours when player duration is unavailable.
    // [v2.0.77] Issue 6 Fix: Also reject absurdly small durations (< 60s) to prevent "full progress" bug.
    // When player briefly reports dur=1000ms during reset, we must NOT cache that as lastValidDurationMs
    // or subsequent pos=22min/dur=1s shows as 100% progress.
    private fun getSafeDuration(): Long {
        // [v2.0.94] During episode switch, player.duration may return the OLD episode's duration.
        // Ignore player.duration until STATE_READY clears episodeSwitching flag.
        val rawDur = if (episodeSwitching) 0L else (player?.duration ?: 0L)
        // [v2.0.77] Valid: positive, >=60s, <24h. Reject tiny durations (player reset artifacts).
        if (rawDur >= 60_000L && rawDur < 24L * 60 * 60 * 1000) {
            lastValidDurationMs = rawDur
            return rawDur
        }
        // Invalid duration - try episode duration
        val epDur = currentEpisode?.duration ?: 0L
        val epDurMs = if (epDur in 60..100000) epDur * 1000 else if (epDur > 100000 && epDur < 24L*60*60*1000) epDur else 0L
        if (epDurMs >= 60_000L) {
            lastValidDurationMs = epDurMs
            return epDurMs
        }
        // Fallback to last known valid duration
        return lastValidDurationMs
    }

    fun getDuration(): Long { return getSafeDuration() }
    fun getBufferedPercentage(): Int = player?.bufferedPercentage ?: 0
    fun getBufferedPosition(): Long = player?.bufferedPosition ?: 0L
    fun getCurrentEpisode(): Episode? = currentEpisode
    fun getCurrentStation(): RadioStation? = currentStation

    /**
     * 根据节目信息，从 SharedPreferences 中获取已保存的播放位置（毫秒）
     * 用于 Activity 重建时恢复播放进度，避免从 0 开始播放导致位置抖动
     */
    fun getSavedPositionForEpisode(episode: Episode): Long {
        // v2.4.63: Use episode ID as key (unique per episode)
        val episodeKey = episode.id ?: ""
        if (episodeKey.isBlank()) return -1L
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
        writeServiceLog("notification", "jumpToNextSegment: segments=${segments.size}, currentPos=${getCurrentPosition()}")
        if (segments.isNotEmpty()) {
            val currentPos = getCurrentPosition()
            // [v2.1.4] Find the next segment after current position
            for (i in segments.indices) {
                if (segments[i].start > currentPos) {
                    writeServiceLog("notification", "jumpToNextSegment: seeking to ${segments[i].start} (next segment)")
                    seekTo(segments[i].start)
                    return
                }
            }
            // [v2.1.4] No more segments in current episode, cross to next episode
            writeServiceLog("notification", "jumpToNextSegment: at last segment, crossing to next episode")
            crossToNextEpisodeFirstSegment()
            return
        }
        // Fallback: skip forward 30 seconds
        val pos = getCurrentPosition() + 30000
        val dur = player?.duration ?: 0L
        if (dur > 0 && pos < dur) seekTo(pos)
    }

    // [v2.1.4] Cross to previous episode and jump to its last segment
    private fun crossToPrevEpisodeLastSegment() {
        writeServiceLog("notification", "crossToPrevEpisodeLastSegment: START")
        // Use the existing notifyPrevEpisode logic but with a flag to seek to last segment
        crossEpisodeTargetSegment = Pair(false, true)  // (isNext, seekLast)
        notifyPrevEpisode()
    }

    // [v2.1.4] Cross to next episode and jump to its first segment
    private fun crossToNextEpisodeFirstSegment() {
        writeServiceLog("notification", "crossToNextEpisodeFirstSegment: START")
        crossEpisodeTargetSegment = Pair(true, false)  // (isNext, seekLast)
        notifyNextEpisode()
    }

    // [v2.1.4] Called after cross-episode switch to seek to the target segment
    private var crossEpisodeTargetSegment: Pair<Boolean, Boolean>? = null  // (isNext, seekLastSegment)
    fun onCrossEpisodeSwitchComplete() {
        crossEpisodeTargetSegment?.let { (_, seekLast) ->
            val segments = getSegmentList()
            if (segments.isNotEmpty()) {
                val targetIdx = if (seekLast) segments.size - 1 else 0
                val targetPos = segments[targetIdx].start
                writeServiceLog("notification", "onCrossEpisodeSwitchComplete: seeking to segment $targetIdx pos=$targetPos (seekLast=$seekLast)")
                seekTo(targetPos)
            }
            crossEpisodeTargetSegment = null
        }
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
            // Issue 2: If the current episode is a cross-day episode (ID ends with "-cross")
            // the URL may encode a stale date. Fall back to currentEpisode?.broadcastAt?.take(10)
            // when URL parsing fails so targetDate is computed from the real broadcast date.
            val fallbackDate = currentEpisode?.broadcastAt?.take(10)
            if (urlParts.size < 2) {
                if (fallbackDate != null) {
                    stationId = currentEpisode?.stationId ?: urlParts.getOrNull(0) ?: ""
                    currentDate = fallbackDate
                    writeServiceLog("notification", "fetchCrossDayEpisode: URL parse failed (urlParts.size=${urlParts.size}), using broadcastAt fallback=$fallbackDate")
                } else {
                    writeServiceLog("notification", "fetchCrossDayEpisode: RETURN null - urlParts.size=${urlParts.size} < 2, curUrl=$curUrl")
                    return null
                }
            } else {
                stationId = urlParts[0] // "sijiache"
                val datePart = urlParts.getOrNull(1)
                if (datePart == null || datePart.length < 8) {
                    if (fallbackDate != null) {
                        currentDate = fallbackDate
                        writeServiceLog("notification", "fetchCrossDayEpisode: URL date parse failed (datePart=$datePart), using broadcastAt fallback=$fallbackDate")
                    } else {
                        writeServiceLog("notification", "fetchCrossDayEpisode: RETURN null - datePart invalid, datePart=$datePart")
                        return null
                    }
                } else {
                    currentDate = "${datePart.substring(0, 4)}-${datePart.substring(4, 6)}-${datePart.substring(6, 8)}"
                }
            }
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

            // [v2.4.81] FIX: Removed the 3-day retry loop that skipped dates.
            // Old behavior: if API returned 0 episodes for 07-31, it advanced to 08-01
            // and returned 08-01 episodes, SKIPPING 07-31 entirely.
            // New behavior: try API once for target date. If 0 episodes, fall through
            // to saved list and URL construction, which correctly handles dates
            // that the API doesn't have but episodes exist for.
            val apiService = com.radio.app.network.EpisodeApiService.getInstance()
            var episodes: List<Episode>? = null
            val fetched = apiService.fetchEpisodesByDateSync(stationId, targetDate)
            writeServiceLog("notification", "fetchCrossDayEpisode: network fetch returned episodes=${fetched?.size ?: "null"} for $stationId on $targetDate")
            if (fetched != null && fetched.isNotEmpty()) {
                episodes = fetched
            }

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

                    val episodeList = loadEpisodeList()
                    writeServiceLog("notification", "fetchCrossDayEpisode: URL construction - episodeList.size=${episodeList.size}, stationId=$stationId, targetDate=$targetDate")
                    if (episodeList.isEmpty()) {
                        writeServiceLog("notification", "fetchCrossDayEpisode: WARNING - episodeList is EMPTY, cannot find matching title")
                    } else {
                        writeServiceLog("notification", "fetchCrossDayEpisode: episodeList titles=${episodeList.map { "${it.id}:${it.title}:${it.broadcastAt?.take(10)}" }.take(5)}")
                    }
                    // Issue 2: Find first/last NON-DISLIKED episode's time slot for cross-day.
                    // 跨天切换节目的逻辑与时间段无关，仅仅是跳过不喜欢的而已。
                    // 如果当天剩下的节目都是不喜欢的，那就取第2天的节目；上一个节目的逻辑也是这样。
                    val settings = AppSettings.getInstance(this)
                    val targetTimeSlot = if (nextDate) {
                        // Going forward: find first non-disliked episode's time slot
                        val firstNonDisliked = episodeList.firstOrNull { ep ->
                            !settings.isDisliked(ep.id) && !settings.isDislikedByTitle(ep.stationId, ep.title)
                        }
                        firstNonDisliked?.audioUrl?.let { url ->
                            val parts = url.substringAfterLast("/").substringBefore(".").split("_")
                            if (parts.size >= 4) "${parts[2]}_${parts[3]}" else "0700_0900"
                        } ?: "0700_0900"
                    } else {
                        // Going backward: find last non-disliked episode's time slot
                        val lastNonDisliked = episodeList.lastOrNull { ep ->
                            !settings.isDisliked(ep.id) && !settings.isDislikedByTitle(ep.stationId, ep.title)
                        }
                        lastNonDisliked?.audioUrl?.let { url ->
                            val parts = url.substringAfterLast("/").substringBefore(".").split("_")
                            if (parts.size >= 4) "${parts[2]}_${parts[3]}" else "1700_1900"
                        } ?: "1700_1900"
                    }
                    writeServiceLog("notification", "fetchCrossDayEpisode: targetTimeSlot=$targetTimeSlot (nextDate=$nextDate, filtered by dislike)")

                    // Construct new URL with target time slot
                    val stationPart = curUrl.substringAfterLast("/").substringBefore("_")
                    val pathPrefix = curUrl.substringBeforeLast("/").substringBeforeLast("/")
                    val newUrl = "$pathPrefix/jmd_$newDateStr/${stationPart}_${newDateStr}_$targetTimeSlot.mp4"

                    writeServiceLog("notification", "fetchCrossDayEpisode: constructed URL: $newUrl")

                    // Issue 2: Find matching title from episode list for the SAME time slot on the TARGET date.
                    val targetStart = targetTimeSlot.substringBefore("_")
                    val targetEnd = targetTimeSlot.substringAfter("_")
                    val targetDateFormatted = "${newDateStr.substring(0, 4)}-${newDateStr.substring(4, 6)}-${newDateStr.substring(6, 8)}"
                    // First, look for an episode on the target date with the same time slot
                    val matchEpisode = episodeList.firstOrNull { ep ->
                        val epDate = ep.broadcastAt?.take(10) ?: ""
                        val epUrl = ep.audioUrl ?: ""
                        epDate == targetDateFormatted && ep.stationId == stationId &&
                            epUrl.contains("_${targetStart}_") && epUrl.contains("_${targetEnd}.") &&
                            !settings.isDisliked(ep.id) && !settings.isDislikedByTitle(ep.stationId, ep.title)
                    }
                    // Fallback: look for any episode with the same time slot (any date)
                    val matchEpisodeFallback = matchEpisode ?: episodeList.firstOrNull { ep ->
                        val epUrl = ep.audioUrl ?: ""
                        ep.stationId == stationId &&
                            epUrl.contains("_${targetStart}_") && epUrl.contains("_${targetEnd}.") &&
                            !settings.isDisliked(ep.id) && !settings.isDislikedByTitle(ep.stationId, ep.title)
                    }

                    val dateDisplay = if (newDateStr.length >= 8) "${newDateStr.substring(4, 6)}月${newDateStr.substring(6, 8)}日" else ""
                    // [v2.0.98] Fix: Ensure stationName is never empty
                    val stationName = currentEpisode?.stationName?.takeIf { it.isNotBlank() } ?: stationPart
                    val timeDisplay = if (targetStart.length >= 4 && targetEnd.length >= 4) {
                        "${targetStart.substring(0, 2)}:${targetStart.substring(2, 4)}-${targetEnd.substring(0, 2)}:${targetEnd.substring(2, 4)}"
                    } else ""
                    // [v2.0.98] Fix: Use real episode title when available (from matchEpisodeFallback).
                    // v2.4.94: Do NOT construct title from stationName + date + time — that causes
                    // notification title confusion (title becomes "电台名 日期 时间段"). Date and time
                    // are already shown in the notification subtext. Use real title only, or a simple
                    // fallback like station name alone.
                    val realTitle = matchEpisodeFallback?.title ?: matchEpisode?.title
                    val constructedTitle: String = if (!realTitle.isNullOrBlank()) {
                        realTitle!!
                    } else {
                        // v2.4.94: Try to find title from episode_info DB table
                        val dbEpisodeId = "${stationId}-$targetDate"
                        val dbEp = try {
                            com.radio.app.database.RadioDatabaseHelper.getInstance(this@RadioPlaybackService)
                                .getEpisodeInfo(dbEpisodeId)
                        } catch (_: Exception) { null }
                        val dbTitle = dbEp?.title?.takeIf { it.isNotBlank() && !it.startsWith("广播节目录音") }
                        if (!dbTitle.isNullOrBlank()) {
                            dbTitle!!
                        } else {
                            // v2.4.94: Use station name only, NOT date+time (they're in subtext)
                            stationName.takeIf { it.isNotBlank() } ?: "广播节目"
                        }
                    }

                    // [v2.0.43] Issue 1 Fix: Calculate duration from time slot to avoid duration=0
                    // duration=0 causes savedPos validation to fail in PlayerActivity, leading to progress regression
                    val calculatedDuration = try {
                        val timeParts = targetTimeSlot.split("_")
                        if (timeParts.size >= 2) {
                            val startMin = timeParts[0].substring(0, 2).toInt() * 60 + timeParts[0].substring(2, 4).toInt()
                            val endMin = timeParts[1].substring(0, 2).toInt() * 60 + timeParts[1].substring(2, 4).toInt()
                            ((endMin - startMin).coerceAtLeast(0) * 60 * 1000).toLong()  // milliseconds
                        } else {
                            7200_000L  // Default 2 hours
                        }
                    } catch (_: Exception) {
                        7200_000L  // Default 2 hours
                    }

                    val newEpisode = Episode(
                        id = "${stationId}-$targetDate-cross",  // [v2.1.6] Use stationId not stationPart
                        title = constructedTitle,
                        stationId = currentEpisode?.stationId ?: stationPart,
                        // [v2.0.93] Fix: Set stationName to prevent empty string in Episode object.
                        // Previously stationName was not passed, defaulting to "" in Episode data class.
                        stationName = stationName,  // [v2.0.98] Use non-empty stationName from above
                        audioUrl = newUrl,
                        // [v2.0.92] Fix: Include time part in broadcastAt (length >= 16) so that
                        // playEpisode() can parse notificationDate and notificationTimeRange correctly.
                        // Previously only "2024-07-11" (10 chars) caused fallback to URL regex parsing.
                        broadcastAt = if (targetStart.length >= 4) {
                            "${newDateStr.substring(0, 4)}-${newDateStr.substring(4, 6)}-${newDateStr.substring(6, 8)}T${targetStart.substring(0, 2)}:${targetStart.substring(2, 4)}"
                        } else {
                            "${newDateStr.substring(0, 4)}-${newDateStr.substring(4, 6)}-${newDateStr.substring(6, 8)}"
                        },
                        duration = calculatedDuration
                    )
                    // Check if the constructed episode's title is disliked
                    if (settings.isDislikedByTitle(newEpisode.stationId, newEpisode.title)) {
                        writeServiceLog("notification", "fetchCrossDayEpisode: constructed episode title '${newEpisode.title}' is disliked, returning null")
                        return null
                    }
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
        writeServiceLog("notification", "notifyPrevEpisode: BEFORE switch - current title='${currentEpisode?.title}', date='$notificationDate', timeRange='$notificationTimeRange'")
        writeServiceLog("notification", "notifyPrevEpisode: called, currentEpisode=${currentEpisode?.title ?: "null"}")
        val settings = AppSettings.getInstance(this)
        val curId = currentEpisode?.id
        if (curId == null) {
            writeServiceLog("notification", "notifyPrevEpisode: currentEpisode is null, cannot switch")
            showEpisodeSwitchFailedNotification("无法切换到上一个节目：当前无播放节目")
            return
        }

        // Try preCacheList first
        val preCacheList = loadPreCacheList()
        val savedList = loadEpisodeList()
        writeServiceLog("notification", "notifyPrevEpisode: searching for prev of curId=$curId, preCacheList.size=${preCacheList.size}, savedList.size=${savedList.size}")
        writeServiceLog("notification", "notifyPrevEpisode: preCacheList episodes: ${preCacheList.map { "${it.id}:${it.title}" }.take(5)}")
        writeServiceLog("notification", "notifyPrevEpisode: curId in preCacheList: ${preCacheList.any { it.id == curId }}, curId in savedList: ${savedList.any { it.id == curId }}")
        var prevEpisode = findPrevInList(preCacheList, curId, settings)
        // Fallback to saved episode list
        if (prevEpisode == null) {
            prevEpisode = findPrevInList(savedList, curId, settings)
        }
        // [v2.1.5] Anti-loop: if prevEpisode is the same as current, don't switch
        if (prevEpisode != null && prevEpisode.id == curId) {
            writeServiceLog("notification", "notifyPrevEpisode: [ANTI-LOOP] prevEpisode.id == curId ($curId), skipping to cross-day")
            prevEpisode = null
        }

        if (prevEpisode != null) {
            val episodeKey = prevEpisode.id ?: ""
            val savedPos = if (episodeKey.isNotBlank()) getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(episodeKey, -1L) else -1L
            val startPos = if (savedPos > 0) savedPos else -1L
            playEpisode(prevEpisode, false, startPos)
            writeServiceLog("notification", "notifyPrevEpisode: AFTER switch - new title='${prevEpisode.title}', new date='$notificationDate', new timeRange='$notificationTimeRange', fullSubText='${buildNotificationSubText()}'")
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
                // Issue 2 Fix: 将跨天节目加入 preCacheList，以便后续搜索能找到它，
                // 避免只在 2 天节目（2024-06-19 / 2024-06-20）之间反复循环。
                val preCacheListForCross = loadPreCacheList().toMutableList()
                if (preCacheListForCross.none { it.id == crossDayEp.id }) {
                    preCacheListForCross.add(crossDayEp)
                    savePreCacheList(preCacheListForCross)
                    writeServiceLog("notification", "notifyPrevEpisode: added cross-day episode to preCacheList: ${crossDayEp.id}")
                }
                val episodeKey = crossDayEp.id ?: ""
                val savedPos = if (episodeKey.isNotBlank()) getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(episodeKey, -1L) else -1L
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
        writeServiceLog("notification", "notifyNextEpisode: BEFORE switch - current title='${currentEpisode?.title}', date='$notificationDate', timeRange='$notificationTimeRange'")
        writeServiceLog("notification", "notifyNextEpisode: called, currentEpisode=${currentEpisode?.title ?: "null"}")
        val settings = AppSettings.getInstance(this)
        val curId = currentEpisode?.id
        if (curId == null) {
            writeServiceLog("notification", "notifyNextEpisode: currentEpisode is null, cannot switch")
            showEpisodeSwitchFailedNotification("无法切换到下一个节目：当前无播放节目")
            return
        }

        // Try preCacheList first
        val preCacheList = loadPreCacheList()
        val savedList = loadEpisodeList()
        writeServiceLog("notification", "notifyNextEpisode: searching for next of curId=$curId, preCacheList.size=${preCacheList.size}, savedList.size=${savedList.size}")
        writeServiceLog("notification", "notifyNextEpisode: preCacheList episodes: ${preCacheList.map { "${it.id}:${it.title}" }.take(5)}")
        writeServiceLog("notification", "notifyNextEpisode: curId in preCacheList: ${preCacheList.any { it.id == curId }}, curId in savedList: ${savedList.any { it.id == curId }}")
        var nextEpisode = findNextInList(preCacheList, curId, settings)
        // Fallback to saved episode list
        if (nextEpisode == null) {
            nextEpisode = findNextInList(savedList, curId, settings)
        }
        // [v2.1.5] Anti-loop: if nextEpisode is the same as current, don't switch
        if (nextEpisode != null && nextEpisode.id == curId) {
            writeServiceLog("notification", "notifyNextEpisode: [ANTI-LOOP] nextEpisode.id == curId ($curId), skipping to cross-day")
            nextEpisode = null
        }

        if (nextEpisode != null) {
            val episodeKey = nextEpisode.id ?: ""
            val savedPos = if (episodeKey.isNotBlank()) getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(episodeKey, -1L) else -1L
            val startPos = if (savedPos > 0) savedPos else -1L
            playEpisode(nextEpisode, false, startPos)
            writeServiceLog("notification", "notifyNextEpisode: AFTER switch - new title='${nextEpisode.title}', new date='$notificationDate', new timeRange='$notificationTimeRange', fullSubText='${buildNotificationSubText()}'")
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
                // Issue 2 Fix: 将跨天节目加入 preCacheList，以便后续搜索能找到它，
                // 避免只在 2 天节目（2024-06-19 / 2024-06-20）之间反复循环。
                val preCacheListForCross = loadPreCacheList().toMutableList()
                if (preCacheListForCross.none { it.id == crossDayEp.id }) {
                    preCacheListForCross.add(crossDayEp)
                    savePreCacheList(preCacheListForCross)
                    writeServiceLog("notification", "notifyNextEpisode: added cross-day episode to preCacheList: ${crossDayEp.id}")
                }
                val episodeKey = crossDayEp.id ?: ""
                val savedPos = if (episodeKey.isNotBlank()) getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(episodeKey, -1L) else -1L
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
            val savedList = loadEpisodeList()
            val settings = AppSettings.getInstance(this)
            val curId = currentEpisode!!.id
            var nextEpisode: Episode? = null
            var foundCurrent = false
            // v2.4.62: Search preCacheList first (contains future episodes + cross-day episodes)
            for (ep in preCacheList) {
                if (!foundCurrent) {
                    if (ep.id == curId) foundCurrent = true
                    continue
                }
                if (!settings.isDisliked(ep.id) && !settings.isDislikedByTitle(ep.stationId, ep.title)
                    && !settings.isNoPreprocess(ep.id ?: "")) {
                    nextEpisode = ep
                    break
                }
            }
            // v2.4.62: Fallback to saved episode list (contains ALL episodes for current day, including current).
            // The preCacheList is built starting from currentEpisodeIndex+1, so the current episode's ID
            // is NOT in it. When the current episode is not found in preCacheList, we fall back to the
            // full saved list which DOES contain the current episode, allowing findNextInList to correctly
            // find the next episode on the same day instead of jumping to cross-day.
            if (nextEpisode == null) {
                writeNotifDetailLog("autoPlayNextEpisode: curId=$curId not found in preCacheList (size=${preCacheList.size}), falling back to savedList (size=${savedList.size})")
                nextEpisode = findNextInList(savedList, curId, settings)
            }
            // v2.4.62: Anti-loop check
            if (nextEpisode != null && nextEpisode.id == curId) {
                writeNotifDetailLog("autoPlayNextEpisode: [ANTI-LOOP] nextEpisode.id == curId ($curId), skipping to cross-day")
                nextEpisode = null
            }
            if (nextEpisode == null) {
                Log.d(TAG, "autoPlayNextEpisode: no more episodes in pre-cache list or saved list, trying cross-day")
                writeNotifDetailLog("autoPlayNextEpisode: nextEpisode is null after all list scans, trying cross-day (curId=$curId, preCacheList.size=${preCacheList.size}, savedList.size=${savedList.size})")
                writeServiceLog("notification", "autoPlayNext: reached end of episode list, trying cross-day")
                val crossDayEp = fetchCrossDayEpisode(nextDate = true)
                if (crossDayEp != null) {
                    writeNotifDetailLog("autoPlayNextEpisode: cross-day episode found, switching - title=${crossDayEp.title}, id=${crossDayEp.id}")
                    writeServiceLog("notification", "autoPlayNext: cross-day episode found: ${crossDayEp.title}")
                    playEpisode(crossDayEp, false)
                    updateMediaSessionMetadata()  // [Issue 5 Fix] 先更新元数据再刷新通知栏
                    forceNotificationUpdate = true
                    // v2.4.117: removed — forceNotificationUpdate=true is sufficient
                    updateNotification()  // [v2.0.55] Issue 7 Fix: 切换节目后立即更新通知栏，避免延迟
                    callback?.onEpisodeChanged(crossDayEp)
                    // [v2.0.93] Fix: Send broadcast for cross-day episode change so main UI updates
                    // even when callback is null (App in background, Activity not bound)
                    try {
                        val broadcastIntent = Intent(BROADCAST_EPISODE_CHANGED)
                        broadcastIntent.putExtra("episode_title", crossDayEp.title)
                        broadcastIntent.putExtra("episode_id", crossDayEp.id)
                        broadcastIntent.setPackage(packageName)
                        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
                    } catch (e: Exception) { Log.w(TAG, "Failed to broadcast cross-day episode change", e) }
                    return
                }
                writeNotifDetailLog("autoPlayNextEpisode: no cross-day episode found, stopping playback")
                writeServiceLog("notification", "autoPlayNext: no cross-day episode found, stopping")
                return
            }
            writeNotifDetailLog("autoPlayNextEpisode: found next episode in pre-cache list, switching - title=${nextEpisode.title}, id=${nextEpisode.id}")
            Log.d(TAG, "autoPlayNextEpisode: switching to ${nextEpisode.title} (id=${nextEpisode.id})")
            val episodeKey = nextEpisode.id ?: ""
            val savedPos = if (episodeKey.isNotBlank()) getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(episodeKey, -1L) else -1L
            val startPos = if (savedPos > 0) savedPos else -1L
            playEpisode(nextEpisode, false, startPos)
            updateMediaSessionMetadata()  // [Issue 5 Fix] 先更新元数据再刷新通知栏
            forceNotificationUpdate = true
            // v2.4.117: removed — forceNotificationUpdate=true is sufficient
            updateNotification()  // [v2.0.55] Issue 7 Fix: 切换节目后立即更新通知栏，避免延迟
            // 通过回调通知 Activity 更新界面
            callback?.onEpisodeChanged(nextEpisode)
            // [v2.0.93] Fix: Send broadcast so main UI updates even when callback is null
            try {
                val broadcastIntent = Intent(BROADCAST_EPISODE_CHANGED)
                broadcastIntent.putExtra("episode_title", nextEpisode.title)
                broadcastIntent.putExtra("episode_id", nextEpisode.id)
                broadcastIntent.setPackage(packageName)
                LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
            } catch (e: Exception) { Log.w(TAG, "Failed to broadcast episode change", e) }
        } catch (e: Exception) {
            Log.e(TAG, "autoPlayNextEpisode failed", e)
            notifyNextEpisode()
        }
    }
    fun jumpToPrevSegment() {
        val segments = getSegmentList()
        writeServiceLog("notification", "jumpToPrevSegment: segments=${segments.size}, currentPos=${getCurrentPosition()}")
        if (segments.isNotEmpty()) {
            val currentPos = getCurrentPosition()
            // [v2.1.6] Find the current segment
            var currentSegmentIdx = -1
            for (i in segments.indices) {
                if (currentPos >= segments[i].start && currentPos < segments[i].end) {
                    currentSegmentIdx = i
                    break
                }
                if (segments[i].start > currentPos) {
                    currentSegmentIdx = i
                    break
                }
            }
            writeServiceLog("notification", "jumpToPrevSegment: currentSegmentIdx=$currentSegmentIdx, currentPos=$currentPos")
            // [v2.1.6] If we found a previous segment in current episode, jump to it
            if (currentSegmentIdx > 0) {
                val targetPos = segments[currentSegmentIdx - 1].start
                writeServiceLog("notification", "jumpToPrevSegment: seeking to $targetPos (prev segment start)")
                seekTo(targetPos)
                return
            }
            // [v2.1.6] If in first segment or before first segment, cross to previous episode
            if (currentSegmentIdx <= 0) {
                writeServiceLog("notification", "jumpToPrevSegment: at first segment, crossing to previous episode")
                crossToPrevEpisodeLastSegment()
                return
            }
        }
        // Fallback: skip backward 30 seconds
        val pos = getCurrentPosition() - 30000
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
        // [v2.0.78] Fix: include pausedByAudioFocus and pauseConfirmedUntil
        val inPauseConfirmWindow = System.currentTimeMillis() < pauseConfirmedUntil
        val playing = playbackStarted && !userPaused && !pausedByAudioFocus && !inPauseConfirmWindow
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

    override fun onBind(intent: Intent?): IBinder {
        // [v2.0.91] Track when client (Activity) binds to service.
        // Don't reset an already-active circuit breaker.
        val now = System.currentTimeMillis()
        lastClientBindTime = now
        skipRequestCount = 0
        breakerWasTripped = false  // [v2.2.7] Reset on new bind
        if (now >= skipCircuitBreakerUntil) {
            skipCircuitBreakerUntil = 0L
        }
        inPostResumeProtection = true
        consecutiveBackwardSkips = 0
        firstBackwardSkipWindowStart = now
        writeServiceLog("playback", "[v2.0.91] onBind: client connected, starting post-resume blackout for ${POST_RESUME_BLACKOUT_MS}ms")
        audioFocusHandler.postDelayed({
            inPostResumeProtection = false
            skipRequestCount = 0
        }, POST_RESUME_BLACKOUT_MS + SKIP_CIRCUIT_BREAKER_MS + 1000L)
        return binder
    }

    override fun onRebind(intent: Intent?) {
        val now = System.currentTimeMillis()
        lastClientBindTime = now
        skipRequestCount = 0
        breakerWasTripped = false  // [v2.2.7] Reset on rebind
        if (now >= skipCircuitBreakerUntil) {
            skipCircuitBreakerUntil = 0L
        }
        inPostResumeProtection = true
        consecutiveBackwardSkips = 0
        firstBackwardSkipWindowStart = now
        writeServiceLog("playback", "[v2.0.91] onRebind: client reconnected, starting post-resume blackout for ${POST_RESUME_BLACKOUT_MS}ms")
        audioFocusHandler.postDelayed({
            inPostResumeProtection = false
            skipRequestCount = 0
        }, POST_RESUME_BLACKOUT_MS + SKIP_CIRCUIT_BREAKER_MS + 1000L)
        super.onRebind(intent)
    }

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
        // [v2.4.13] Stop subtitle patrol
        if (::subtitlePatrolHandler.isInitialized) {
            subtitlePatrolHandler.removeCallbacks(subtitlePatrolRunnable)
        }
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