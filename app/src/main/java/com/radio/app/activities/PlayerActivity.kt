package com.radio.app.activities

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.radio.app.R
import com.radio.app.databinding.ActivityPlayerBinding
import com.radio.app.models.Episode
import com.radio.app.models.RadioStation
import com.radio.app.models.VoiceSegment
import com.radio.app.models.Transcript
import com.radio.app.services.RadioPlaybackService
import com.radio.app.adapters.VoiceSegmentAdapter
import com.radio.app.services.SubtitleGeneratorService
import com.radio.app.models.AppSettings
import com.radio.app.database.RadioDatabaseHelper
import com.radio.app.utils.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.graphics.Color

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
    // Issue 4: 持有节目列表对话框的适配器引用，便于切歌后刷新高亮
    private var episodeListAdapter: EpisodeListAdapter? = null
    private var isDragging = false
    private var cacheProgressHandler: Handler? = null
    private var cacheProgressRunnable: Runnable? = null
    private var subtitleProcessing = false
    private var segmentProcessing = false
    private var isFreshStart = false // true if user explicitly clicked an episode from the list
    private var pendingAiTaskType: String? = null
    private var isActivityRecreated = false // true if system is recreating this activity (config change, etc.)
    private var freshLaunchTs: Long = 0 // timestamp from intent, used to detect real fresh launches

    // Feature B: Real-time position tracking
    private var currentPlaybackPositionMs: Long = 0
    // Issue 1 Fix 4: true while waiting for the playback service to report a valid position.
    // While true, positionUpdateRunnable skips UI updates so the cached position restored in
    // onResume/onCreate is not overridden before the service is ready.
    private var awaitingServicePosition = true
    private var subtitleTranscripts: List<Transcript> = emptyList()
    private var subtitleAdapter: SubtitleEntryAdapter? = null
    private var lastSubtitleHighlightIdx = -1
    private var lastSegmentHighlightIdx = -1
    private val positionUpdateHandler = Handler(Looper.getMainLooper())
    private val positionUpdateRunnable = object : Runnable {
        override fun run() {
            if (!awaitingServicePosition) {
                // Issue 1: Only update if service reports a valid position
                val pos = playbackService?.getCurrentPosition() ?: 0L
                val dur = playbackService?.getDuration() ?: 0L
                if (pos > 0 && dur > 0) {
                    updateCurrentPositionHighlight()
                }
            }
            positionUpdateHandler.postDelayed(this, 500)
        }
    }

    companion object {
        private const val PREF_NAME = "jitter_guard"
        private const val KEY_LAST_STARTED_URL = "last_started_url"
        private const val KEY_LAST_STARTED_TS = "last_started_ts"
        private const val KEY_PLAYBACK_IN_PROGRESS = "playback_in_progress"

        fun markFreshLaunchHandled(ctx: android.content.Context, ts: Long) {
            val prefs = ctx.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
            prefs.edit().putLong("lastHandledTs", ts).apply()
        }

        fun getLastHandledTs(ctx: android.content.Context): Long {
            return ctx.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE).getLong("lastHandledTs", 0L)
        }

        fun isPlaybackInProgress(ctx: android.content.Context): Boolean {
            val prefs = ctx.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_PLAYBACK_IN_PROGRESS, false)) return false
            val ts = prefs.getLong(KEY_LAST_STARTED_TS, 0L)
            return System.currentTimeMillis() - ts < 1800000  // 30 minutes (covers typical background switch duration)
        }

        fun setPlaybackInProgress(ctx: android.content.Context, url: String?) {
            val prefs = ctx.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
            val editor = prefs.edit()
            if (url == null) {
                editor.putBoolean(KEY_PLAYBACK_IN_PROGRESS, false)
                editor.remove(KEY_LAST_STARTED_URL)
            } else {
                editor.putBoolean(KEY_PLAYBACK_IN_PROGRESS, true)
                editor.putString(KEY_LAST_STARTED_URL, url)
                editor.putLong(KEY_LAST_STARTED_TS, System.currentTimeMillis())
            }
            editor.apply()
        }

        fun getLastStartedUrl(ctx: android.content.Context): String? {
            return ctx.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE).getString(KEY_LAST_STARTED_URL, null)
        }

        fun getSavedPositionForEpisode(ctx: android.content.Context, episodeId: String): Long {
            return ctx.getSharedPreferences("playback_positions", android.content.Context.MODE_PRIVATE)
                .getLong(episodeId, -1L)
        }
    }

    // 广播接收器：处理连续播放、下一集等事件
    private val episodeActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RadioPlaybackService.BROADCAST_STATE_CHANGED) {
                val action = intent.getStringExtra("action")
                when (action) {
                    "next_episode" -> {
                        android.util.Log.d("PlayerActivity", "Received next_episode broadcast")
                        playNextEpisode()
                    }
                    "prev_episode" -> {
                        android.util.Log.d("PlayerActivity", "Received prev_episode broadcast")
                        playPrevEpisode()
                    }
                }
            }
        }
    }

    private val episodeChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RadioPlaybackService.BROADCAST_EPISODE_CHANGED) {
                val newTitle = intent.getStringExtra("episode_title") ?: return
                val newId = intent.getStringExtra("episode_id") ?: return
                writeNotificationLog("episodeChanged broadcast: title=$newTitle, id=$newId")
                // Update notification title in UI
                binding.tvStationName.text = newTitle
                updateUI()
            }
        }
    }

    private fun saveProcessingState() {
        getSharedPreferences("player_processing_state", MODE_PRIVATE).edit().apply {
            putBoolean("subtitle_processing", subtitleProcessing)
            putBoolean("segment_processing", segmentProcessing)
            putString("processing_episode_id", currentEpisode?.id ?: "")
        }.apply()
    }

    private fun restoreProcessingState() {
        val prefs = getSharedPreferences("player_processing_state", MODE_PRIVATE)
        val savedEpisodeId = prefs.getString("processing_episode_id", "")
        val currentId = currentEpisode?.id ?: ""
        // 只有同一个节目才恢复状态
        if (savedEpisodeId != null && savedEpisodeId.isNotBlank() && savedEpisodeId == currentId) {
            subtitleProcessing = prefs.getBoolean("subtitle_processing", false)
            segmentProcessing = prefs.getBoolean("segment_processing", false)
            android.util.Log.d("PlayerActivity", "restoreProcessingState: restored subtitle=$subtitleProcessing segment=$segmentProcessing for $currentId")
        } else {
            subtitleProcessing = false
            segmentProcessing = false
            // 清除残留状态，防止错误恢复
            if (savedEpisodeId != null && savedEpisodeId.isNotBlank()) {
                prefs.edit().clear().apply()
                android.util.Log.d("PlayerActivity", "restoreProcessingState: cleared stale state (saved=$savedEpisodeId != current=$currentId)")
            }
        }
        // 安全兜底：如果Activity是新鲜启动（不是从后台恢复），清除所有处理状态
        if (isFreshStart) {
            subtitleProcessing = false
            segmentProcessing = false
            prefs.edit().clear().apply()
            android.util.Log.d("PlayerActivity", "restoreProcessingState: fresh start, cleared all processing state")
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? RadioPlaybackService.LocalBinder ?: return
            playbackService = binder.getService()
            serviceBound = true
            playbackService?.setCallback(playbackCallback)
            // Issue 1: Only clear awaitingServicePosition if service reports a valid position
            val svcPos = playbackService?.getCurrentPosition() ?: 0L
            val svcDur = playbackService?.getDuration() ?: 0L
            if (svcPos > 0 && svcDur > 0) {
                awaitingServicePosition = false
                writeJitterLog("onServiceConnected: service has valid position=$svcPos, clearing awaitingServicePosition")
            } else {
                writeJitterLog("onServiceConnected: service has no valid position (pos=$svcPos, dur=$svcDur), keeping awaitingServicePosition=true")
            }

            // Issue 1: If currentEpisode is null (recreated from notification without episode data), get from service
            if (currentEpisode == null) {
                val svcEpisode = playbackService?.getCurrentEpisode()
                if (svcEpisode != null) {
                    currentEpisode = svcEpisode
                    val svcList = loadEpisodeListFromPrefs()
                    if (svcList.isNotEmpty()) {
                        episodeList = svcList
                        currentEpisodeIndex = episodeList.indexOfFirst { it.id == svcEpisode.id }.coerceAtLeast(0)
                    }
                    clearSubtitles()
                    updateUI()
                    restoreBackgroundResults()
                    writeJitterLog("onServiceConnected: restored episode from service: ${svcEpisode.title}")
                    return
                }
            }

            val newUrl = currentEpisode?.audioUrl
            val svcStarted = playbackService?.isPlaybackStarted() ?: false
            val svcPlaying = playbackService?.isPlaying() ?: false
            val svcPrepared = playbackService?.isPrepared() ?: false
            val svcUrl = playbackService?.getCurrentPlayingUrl()
            val sameEpisode = playbackService?.isSameEpisodePlaying(newUrl ?: "") ?: false

            val logMsg = "=== onServiceConnected DEBUG ===\n" +
                "  isFreshStart=$isFreshStart, svcStarted=$svcStarted, svcPlaying=$svcPlaying, svcPrepared=$svcPrepared\n" +
                "  sameEpisode=$sameEpisode, newUrl=$newUrl, svcUrl=$svcUrl\n" +
                "  episodeList.size=${episodeList.size}, currentIndex=$currentEpisodeIndex\n" +
                "  playbackInProgress=${isPlaybackInProgress(this@PlayerActivity)}, lastStartedUrl=${getLastStartedUrl(this@PlayerActivity)}"
            android.util.Log.d("PlayerActivity", logMsg)
            writeJitterLog(logMsg)
            writeJitterLog("onServiceConnected: sameEpisode=$sameEpisode, svcStarted=$svcStarted, isFreshStart=$isFreshStart, playbackInProgress=${isPlaybackInProgress(this@PlayerActivity)}")

            // Issue 1 Fix: if the Activity's episode list is stale/empty, try to restore it
            // BEFORE the JITTER-GUARD logic below, so the service's current episode can be
            // matched by URL. The playback service does not expose its own episode list, so
            // we restore from the persisted "episode_list" prefs and the launch intent.
            if (episodeList.isEmpty()) {
                try {
                    val prefs = getSharedPreferences("episode_list", MODE_PRIVATE)
                    val json = prefs.getString("list", null)
                    if (json != null) {
                        val gson = com.google.gson.Gson()
                        val type = object : com.google.gson.reflect.TypeToken<List<com.radio.app.models.Episode>>() {}.type
                        episodeList = ArrayList(gson.fromJson<java.util.ArrayList<com.radio.app.models.Episode>>(json, type) ?: java.util.ArrayList())
                        android.util.Log.d("PlayerActivity", "Restored episode list from service prefs before jitter-guard: ${episodeList.size} episodes")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PlayerActivity", "Failed to restore episode list before jitter-guard", e)
                }
                if (episodeList.isEmpty()) {
                    val intentEpisodes = intent?.getSerializableExtra("episode_list") as? ArrayList<com.radio.app.models.Episode>
                    if (!intentEpisodes.isNullOrEmpty()) {
                        episodeList = intentEpisodes
                        android.util.Log.d("PlayerActivity", "Restored episode list from intent before jitter-guard: ${episodeList.size} episodes")
                    }
                }
            }

            // 核心防抖：如果服务已经播放同一URL，只更新UI（防止抖动）
            if (sameEpisode && svcStarted) {
                setPlaybackInProgress(this@PlayerActivity, null)
                val msg = "JITTER-GUARD: same episode playing, only update UI"
                android.util.Log.d("PlayerActivity", msg)
                writeJitterLog(msg)
                updateUI()
                startCacheProgressUpdater()
                restoreBackgroundResults()
                setupPreCacheList()
                return@onServiceConnected
            }

            // JITTER-GUARD: Service正在播放不同的episode（如自动切集后），同步Activity到Service的当前状态
            // Service是播放状态的唯一真相源，不要强制切回Activity中过时的episode
            if (svcStarted && !sameEpisode) {
                // Try to find the service's current episode by URL
                val svcMatch = episodeList.firstOrNull { it.audioUrl == svcUrl }
                if (svcMatch != null) {
                    val syncMsg = "JITTER-GUARD: service playing different episode (svc=${svcMatch.title}, url matched), syncing Activity to service instead of restarting"
                    android.util.Log.d("PlayerActivity", syncMsg)
                    writeJitterLog(syncMsg)
                    currentEpisode = svcMatch
                    // Issue 10 Fix 2: clear old subtitles when syncing to the service's episode
                    clearSubtitles()
                    currentEpisodeIndex = episodeList.indexOf(svcMatch).coerceAtLeast(0)
                    // Issue 1 Fix: update the UI immediately so the stale episode is never
                    // rendered before the rest of the sync work runs.
                    updateUI()
                } else {
                    // Can't find episode by URL, but service is playing - just update UI, don't restart
                    val syncMsg = "JITTER-GUARD: service playing unknown episode (svcUrl=$svcUrl), updating UI without restarting"
                    android.util.Log.d("PlayerActivity", syncMsg)
                    writeJitterLog(syncMsg)
                    // Issue 1 Fix: try to extract the episode info directly from the service so
                    // the UI reflects what is actually playing instead of the stale episode.
                    val svcEpisode = playbackService?.getCurrentEpisode()
                    if (svcEpisode != null) {
                        currentEpisode = svcEpisode
                        clearSubtitles()
                        currentEpisodeIndex = episodeList.indexOfFirst { it.id == svcEpisode.id }.coerceAtLeast(0)
                        updateUI()
                        restoreBackgroundResults()
                    }
                }
                saveLastEpisode()
                setPlaybackInProgress(this@PlayerActivity, null)
                updateUI()
                startCacheProgressUpdater()
                restoreBackgroundResults()
                setupPreCacheList()
                return@onServiceConnected
            }

            // JITTER-PREVENT: skip if we already started this exact URL
            if (getLastStartedUrl(this@PlayerActivity) == newUrl && isPlaybackInProgress(this@PlayerActivity)) {
                val msg = "Already starting playback for same URL, skipping duplicate: $newUrl"
                android.util.Log.d("PlayerActivity", msg)
                writeJitterLog(msg)
                return@onServiceConnected
            }
            
            // 需要开始/重新开始播放
            // 如果服务正在初始化播放，跳过重复启动
            if (playbackService?.playbackInitializing == true) {
                val msg = "Service is initializing playback, skipping duplicate start"
                android.util.Log.d("PlayerActivity", msg)
                writeJitterLog(msg)
                updateUI()
                startCacheProgressUpdater()
                restoreBackgroundResults()
                setupPreCacheList()
                return@onServiceConnected
            }

            setPlaybackInProgress(this@PlayerActivity, newUrl)

            // Issue 1 Fix: 显示保存位置前，先验证其合理性（不超过 episode duration）
            var savedPos = getSavedPositionForEpisode(this@PlayerActivity, currentEpisode?.id ?: "")
            val svcDuration = playbackService?.getDuration() ?: 0L
            val epDuration = currentEpisode?.duration ?: 0L
            // 取服务和 episode 中较大的 duration 作为校验基准（单位可能不同：ms vs s）
            val maxDuration = maxOf(svcDuration, epDuration * if (epDuration > 0 && epDuration < 100000) 1000 else 1)
            var isValidSavedPos = savedPos > 0 && maxDuration > 0 && savedPos <= maxDuration

            // Issue 1 Fix: 当服务被杀死 (!svcStarted) 时，完全不恢复任何保存位置。
            // 即使 savedPos 通过了校验（在 episode duration 范围内），在服务尚未就绪时
            // 恢复该位置也会导致抖动。将 savedPos 清零并跳过 "Pre-setting UI" 块。
            if (!svcStarted) {
                writeJitterLog("Service was killed (!svcStarted), NOT restoring savedPos=${savedPos}ms (valid=$isValidSavedPos), setting to 0 to avoid jitter")
                savedPos = 0L
                isValidSavedPos = false
            }

            if (!isFreshStart && currentEpisode != null && isValidSavedPos && svcStarted) {
                binding.tvCurrentTime.text = "${formatTime(savedPos.toInt())} / --:--"
                binding.seekBar.progress = savedPos.toInt()
                binding.tvLiveIndicator.text = "恢复中..."
                writeJitterLog("Pre-setting UI to saved position: ${savedPos}ms before playEpisode (maxDur=$maxDuration)")
            } else if (!isFreshStart && currentEpisode != null && savedPos > 0 && !isValidSavedPos) {
                writeJitterLog("Skipping invalid saved position: ${savedPos}ms exceeds maxDuration=$maxDuration, episode=${currentEpisode?.title}")
            }

            val msg = if (sameEpisode && !svcStarted) {
                "Same episode, service was killed, restoring from saved position: ${savedPos}ms (valid=$isValidSavedPos)"
            } else {
                "Different episode, starting new playback: ${currentEpisode?.title} (was svc=${playbackService?.getCurrentEpisode()?.title})"
            }
            android.util.Log.d("PlayerActivity", msg)
            writeJitterLog(msg)

            if (!svcStarted) {
                writeJitterLog("Service was killed, will restore playback. savedPos=${savedPos}ms, valid=$isValidSavedPos, episode=${currentEpisode?.title}")
            }

            // If episode list is empty (activity recreated without intent), try to restore from prefs
            if (episodeList.isEmpty()) {
                try {
                    val prefs = getSharedPreferences("episode_list", MODE_PRIVATE)
                    val json = prefs.getString("list", null)
                    if (json != null) {
                        val gson = com.google.gson.Gson()
                        val type = object : com.google.gson.reflect.TypeToken<List<com.radio.app.models.Episode>>() {}.type
                        episodeList = ArrayList(gson.fromJson<java.util.ArrayList<com.radio.app.models.Episode>>(json, type) ?: java.util.ArrayList())
                        android.util.Log.d("PlayerActivity", "Restored episode list from prefs: ${episodeList.size} episodes")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PlayerActivity", "Failed to restore episode list", e)
                }
            }

            if (episodeList.isEmpty()) {
                // Try to get from intent
                val intentEpisodes = intent?.getSerializableExtra("episode_list") as? ArrayList<com.radio.app.models.Episode>
                if (!intentEpisodes.isNullOrEmpty()) {
                    episodeList = intentEpisodes
                    android.util.Log.d("PlayerActivity", "Restored episode list from intent: ${episodeList.size} episodes")
                }
            }

            if (currentStation != null) {
                playbackService?.playStation(currentStation!!)
            } else {
                val audioUrl = currentEpisode?.audioUrl
                if (!audioUrl.isNullOrBlank()) {
                    currentEpisode?.let { episode ->
                        if (isFreshStart) {
                            playbackService?.playEpisode(episode, false)
                        } else {
                            var savedPosition = playbackService?.getSavedPositionForEpisode(episode) ?: -1L
                            // Issue 1 Fix: 验证保存位置的合理性，不超过 episode duration
                            val epDurMs = episode.duration * if (episode.duration > 0 && episode.duration < 100000) 1000 else 1
                            if (savedPosition > 0 && epDurMs > 0 && savedPosition > epDurMs) {
                                writeJitterLog("Service killed restore: savedPosition=${savedPosition}ms exceeds episode duration=${epDurMs}ms, clamping to 0")
                                savedPosition = 0L
                            }
                            // Issue 1 Fix: 当服务被杀死 (!svcStarted) 时，不向 playEpisode 传递保存的位置，
                            // 改为传 -1（不 seek），让服务从自身保存的状态或节目开头恢复，
                            // 避免系统重建 Activity 时恢复一个过大的位置（如 2329771ms）导致抖动。
                            if (!svcStarted) {
                                writeJitterLog("Service was killed (!svcStarted), NOT passing savedPos=${savedPosition}ms to playEpisode, using -1 (no seek) to avoid jitter")
                                savedPosition = -1L
                            }
                            val msg = "Service was killed, restoring saved position: ${savedPosition}ms (epDur=${epDurMs}ms, svcStarted=$svcStarted)"
                            android.util.Log.d("PlayerActivity", msg)
                            writeJitterLog(msg)
                            if (savedPosition > 0) {
                                // Show saved position immediately to prevent 0:00 flash
                                binding.tvCurrentTime.text = "${formatTime(savedPosition.toInt())} / --:--"
                                binding.seekBar.progress = savedPosition.toInt()
                                binding.tvLiveIndicator.text = "恢复中..."
                            }
                            playbackService?.playEpisode(episode, false, savedPosition)
                        }
                    }
                }
            }
            setupPreCacheList()
            updateUI()
            if (voiceSegments.isEmpty() && currentEpisode != null) {
                voiceSegments = generateSimulatedSegments()
                if (voiceSegments.isNotEmpty()) {
                    updateSegmentsUI()
                }
            }
            restoreBackgroundResults()
            startCacheProgressUpdater()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            setPlaybackInProgress(this@PlayerActivity, null)
            android.util.Log.d("PlayerActivity", "onServiceDisconnected")
            writeJitterLog("onServiceDisconnected")
            playbackService = null
            serviceBound = false
        }
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

    private fun writeLog(category: String, msg: String) {
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
    private fun writeJitterLog(msg: String) = writeLog("jitter", msg)
    private fun writeDislikeLog(msg: String) = writeLog("dislike", msg)
    private fun writeNotificationLog(msg: String) = writeLog("notification", msg)

    // Issue 6 & 8: dedicated log file for episode list operations (highlight + click-to-switch)
    private fun writeEpisodeLog(message: String) {
        try {
            val logDir = java.io.File(getExternalFilesDir(null), "logs/episode")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = java.io.File(logDir, "episode.log")
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
            logFile.appendText("[$timestamp][$appVersion] $message\n")
        } catch (_: Exception) {}
    }

    /**
     * 设置预缓存列表：传递当前节目之后的所有节目（跨天支持）
     * 服务端会根据 preloadCacheCount 限制实际下载数量
     */
    private fun setupPreCacheList() {
        val settings = AppSettings.getInstance(this)
        if (!settings.autoCache) {
            android.util.Log.d("PlayerActivity", "setupPreCacheList: pre-cache disabled (autoCache=false)")
            return
        }
        if (episodeList.isEmpty() || currentEpisodeIndex < 0) {
            android.util.Log.d("PlayerActivity", "setupPreCacheList: no episode list available (size=${episodeList.size}, index=$currentEpisodeIndex)")
            return
        }
        val startIdx = currentEpisodeIndex + 1
        if (startIdx >= episodeList.size) {
            android.util.Log.d("PlayerActivity", "setupPreCacheList: no more episodes after current (index=$currentEpisodeIndex, total=${episodeList.size})")
            return
        }
        // 传递所有后续节目（不限数量），服务端会根据preloadCacheCount控制下载数
        val upcomingEpisodes = episodeList.subList(startIdx, episodeList.size)
        android.util.Log.d("PlayerActivity", "setupPreCacheList: setting ${upcomingEpisodes.size} upcoming episodes: ${upcomingEpisodes.joinToString(", ") { it.title ?: "?" }}")
        playbackService?.setPreCacheEpisodeList(upcomingEpisodes)
        playbackService?.triggerPreCacheIndependently()
    }

    private val playbackCallback = object : RadioPlaybackService.Callback {
        override fun onStateChanged(playing: Boolean) {
            runOnUiThread {
                if (_binding == null) return@runOnUiThread
                updatePlayPauseButton(playing)
                if (playing) {
                    hasError = false
                    hasErrorToastShown = false
                    binding.tvAiProgress.visibility = View.GONE
                    // Clear playback-in-progress flag once playback is confirmed started
                    setPlaybackInProgress(this@PlayerActivity, null)
                }
                binding.tvLiveIndicator.text = if (hasError) "播放失败" else if (playing) "播放中" else "已暂停"
                binding.tvLiveIndicator.visibility = if (playing || hasError) View.VISIBLE else View.GONE
            }
        }

        override fun onPositionChanged(position: Long, duration: Long) {
            runOnUiThread {
                if (_binding == null) return@runOnUiThread
                val pos = position.toInt()
                val dur = duration.toInt()
                // Feature B: store current playback position
                currentPlaybackPositionMs = position
                if (isDragging) return@runOnUiThread
                if (dur > 0) {
                    binding.seekBar.max = dur
                    binding.seekBar.progress = pos
                    binding.tvCurrentTime.text = "${formatTime(pos)} / ${formatTime(dur)}"
                    binding.tvTotalTime.text = formatTime(dur)
                    binding.tvLiveIndicator.text = "播放中"
                    // 同步字幕显示
                    binding.subtitleView.setCurrentPosition(position)
                } else if (playbackService?.isLive() == true) {
                    binding.tvCurrentTime.text = "直播 ${formatTime(pos)}"
                    binding.seekBarCache.visibility = View.GONE
                    binding.tvCacheProgress.visibility = View.GONE
                } else {
                    binding.tvCurrentTime.text = "缓冲中 ${formatTime(pos)}"
                }
            }
        }

        override fun onBufferUpdate(percent: Int) {
            runOnUiThread {
                if (_binding == null) return@runOnUiThread
                if (hasError) return@runOnUiThread
                binding.tvAiProgress.text = "缓冲: ${percent}%"
                binding.tvAiProgress.visibility = if (percent >= 100) View.GONE else View.VISIBLE
                binding.progressBuffer.progress = percent
                binding.progressBuffer.visibility = if (percent >= 100) View.GONE else View.VISIBLE
            }
        }

        override fun onError(errorMessage: String) {
            runOnUiThread {
                if (_binding == null) return@runOnUiThread
                hasError = true
                binding.tvLiveIndicator.text = "播放失败"
                binding.tvAiProgress.text = errorMessage
                binding.tvAiProgress.visibility = View.VISIBLE
                if (!hasErrorToastShown) {
                    hasErrorToastShown = true
                    Toast.makeText(this@PlayerActivity, errorMessage, Toast.LENGTH_LONG).show()
                }
                android.util.Log.e("PlayerActivity", "Playback error: $errorMessage")
            }
        }

        override fun onEpisodeChanged(episode: Episode) {
            // 服务端自动切换节目时的回调（连续播放）
            runOnUiThread {
                if (_binding == null) return@runOnUiThread
                android.util.Log.d("PlayerActivity", "onEpisodeChanged: ${episode.title}")
                writeJitterLog("onEpisodeChanged: ${episode.title} (id=${episode.id})")
                currentEpisode = episode
                // Issue 10 Fix 2: clear old subtitles so the new episode only shows its own
                clearSubtitles()
                val newIdx = episodeList.indexOfFirst { it.id == episode.id }
                if (newIdx >= 0) currentEpisodeIndex = newIdx
                saveLastEpisode()
                voiceSegments = generateSimulatedSegments()
                if (voiceSegments.isNotEmpty()) updateSegmentsUI()
                updateUI()
                setupPreCacheList()
            }
        }
    }

    private fun startCacheProgressUpdater() {
        cacheProgressHandler?.removeCallbacksAndMessages(null)
        cacheProgressHandler = Handler(Looper.getMainLooper())
        cacheProgressRunnable = Runnable {
            if (_binding == null) return@Runnable
            try {
                val svc = playbackService ?: return@Runnable
                val dur = svc.getDuration()
                if (dur <= 0) {
                    runOnUiThread {
                        if (_binding == null) return@runOnUiThread
                        binding.seekBarCache.visibility = View.GONE
                        binding.tvCacheProgress.visibility = View.GONE
                    }
                    cacheProgressHandler?.postDelayed(cacheProgressRunnable!!, 2000)
                    return@Runnable
                }
                val cachePct = svc.getDownloadProgress()

                runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    binding.seekBarCache.max = dur.toInt()
                    binding.seekBarCache.progress = ((dur * cachePct) / 100).toInt()
                    binding.tvCacheProgress.text = "缓存: ${cachePct}%"
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

        // 使用时间戳判断是否为真正的新鲜启动
        // 系统重建Activity时，intent会保留旧数据，但fresh_launch_ts仍然为旧值
        // 通过对比lastHandledTs来判断是否已经处理过这个启动
        freshLaunchTs = intent.getLongExtra("fresh_launch_ts", 0)
        isActivityRecreated = savedInstanceState != null
        // Issue 1 (partial) Fix 5: log WHY the Activity is being (re)created to help diagnose
        // system-killed recreation jitter. savedInstanceState != null => system killed & restored.
        writeJitterLog("onCreate: savedInstanceState=${savedInstanceState != null}, isActivityRecreated=$isActivityRecreated, reason=${if (savedInstanceState != null) "system_killed" else "fresh_launch"}")

        val lastHandled = getLastHandledTs(this)

        currentEpisode = intent.getSerializableExtra("episode") as? Episode
        if (currentEpisode == null) {
            val audioUrl = intent.getStringExtra("audio_url") ?: intent.getStringExtra("stream_url")
            if (audioUrl.isNullOrBlank()) {
                // Issue 1: Don't finish() - this happens when Activity is recreated from notification.
                // Wait for service to connect, then get episode from service.
                writeJitterLog("onCreate: no episode/audioUrl in intent (from_notification=${intent.getBooleanExtra("from_notification", false)}), will sync from service")
                currentEpisode = null
                // Don't return - continue with initialization, service will provide episode
                initViews()
                // Issue 1 Fix 3: 系统重建时不立即恢复缓存位置，等服务连接后获取实际位置。
                // 只在 freshLaunchTs > 0（用户主动操作）时才恢复缓存位置。
                if (freshLaunchTs > 0) {
                    val cachedPos = getSharedPreferences("player_position_cache", MODE_PRIVATE).getLong("cached_position", 0L)
                    val cachedDur = getSharedPreferences("player_position_cache", MODE_PRIVATE).getLong("cached_duration", 0L)
                    if (cachedPos > 0 && cachedDur > 0 && cachedPos <= cachedDur) {
                        try {
                            binding.seekBar.max = cachedDur.toInt()
                            binding.seekBar.progress = cachedPos.toInt()
                            binding.tvCurrentTime.text = "${formatTime(cachedPos.toInt())} / ${formatTime(cachedDur.toInt())}"
                            writeJitterLog("onCreate: immediately restored cached position=$cachedPos, duration=$cachedDur")
                        } catch (_: Exception) {}
                    }
                } else {
                    writeJitterLog("onCreate: freshLaunchTs=0, skipping cached position restore, waiting for service")
                }
                setupListeners()
                restoreProcessingState()
                bindPlaybackService()
                LocalBroadcastManager.getInstance(this).registerReceiver(
                    episodeActionReceiver,
                    IntentFilter(RadioPlaybackService.BROADCAST_STATE_CHANGED)
                )
                LocalBroadcastManager.getInstance(this).registerReceiver(
                    episodeChangedReceiver,
                    IntentFilter(RadioPlaybackService.BROADCAST_EPISODE_CHANGED)
                )
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
        saveEpisodeListToPrefs()
        currentEpisodeIndex = intent.getIntExtra("episode_index", -1)

        // 真正的新鲜启动：有有效的时间戳，且该时间戳尚未被处理过，
        // 且intent中有实际节目数据
        // 注意：不再依赖 intent.action != null，因为从通知栏/最近任务进入时action可能为null
        isFreshStart = freshLaunchTs > 0 && freshLaunchTs > lastHandled && currentEpisode != null
        if (isFreshStart) {
            markFreshLaunchHandled(this, freshLaunchTs)
        }

        android.util.Log.d("PlayerActivity", "onCreate: freshLaunchTs=$freshLaunchTs, lastHandled=$lastHandled, isFreshStart=$isFreshStart, isActivityRecreated=$isActivityRecreated")
        writeJitterLog("onCreate: freshLaunchTs=$freshLaunchTs, lastHandled=$lastHandled, isFreshStart=$isFreshStart, isActivityRecreated=$isActivityRecreated, action=${intent.action}, episode=${currentEpisode?.title}, hasIntentAction=${intent.action != null}, currentEpisodeNotNull=${currentEpisode != null}")

        // 缓存节目列表用于连续播放
        if (episodeList.isNotEmpty()) {
            val arr = org.json.JSONArray()
            for (ep in episodeList) {
                val obj = org.json.JSONObject()
                obj.put("id", ep.id); obj.put("title", ep.title)
                obj.put("audio_url", ep.audioUrl); obj.put("station_id", ep.stationId)
                obj.put("station_name", ep.stationName); obj.put("duration", ep.duration)
                obj.put("broadcast_at", ep.broadcastAt)
                arr.put(obj)
            }
            getSharedPreferences("episode_list_cache", MODE_PRIVATE).edit()
                .putString("episodes", arr.toString()).apply()
        }

        if (currentEpisode?.audioUrl.isNullOrBlank()) {
            // Issue 1: Don't finish() - wait for service to provide episode
            writeJitterLog("onCreate: currentEpisode has no audioUrl, will sync from service")
            initViews()
            setupListeners()
            restoreProcessingState()
            bindPlaybackService()
            LocalBroadcastManager.getInstance(this).registerReceiver(
                episodeActionReceiver,
                IntentFilter(RadioPlaybackService.BROADCAST_STATE_CHANGED)
            )
            LocalBroadcastManager.getInstance(this).registerReceiver(
                episodeChangedReceiver,
                IntentFilter(RadioPlaybackService.BROADCAST_EPISODE_CHANGED)
            )
            return
        }

        initViews()
        setupListeners()
        restoreProcessingState()
        bindPlaybackService()
        // 注册广播接收器处理连续播放等事件
        LocalBroadcastManager.getInstance(this).registerReceiver(
            episodeActionReceiver,
            IntentFilter(RadioPlaybackService.BROADCAST_STATE_CHANGED)
        )
        LocalBroadcastManager.getInstance(this).registerReceiver(
            episodeChangedReceiver,
            IntentFilter(RadioPlaybackService.BROADCAST_EPISODE_CHANGED)
        )
    }

    private fun initViews() {
        binding.tvStationName.text = currentEpisode?.title
        binding.tvNetworkUrl.text = "网络: ${currentEpisode?.audioUrl}"
        binding.tvNetworkUrl.visibility = View.VISIBLE

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

        // Issue 1 Fix: 只在真正的新鲜启动（用户主动操作）时预填充保存的位置。
        // 当 freshLaunchTs=0（系统重建 Activity）时，不立即显示保存的位置，
        // 因为保存的位置可能不准确（如 2329771ms），会导致进度条先跳到错误位置，
        // 等服务连接后再跳回实际位置，造成"抖动"。
        // 改为显示 "00:00 / 00:00"，等服务连接后由服务提供实际位置。
        if (!isFreshStart && currentEpisode != null && freshLaunchTs > 0) {
            // freshLaunchTs > 0 说明是用户主动操作（非系统重建），可以安全预填充
            val episodeKey = "${currentEpisode!!.stationId}::${currentEpisode!!.title}"
            val savedPos = getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(episodeKey, -1L)
            if (savedPos > 0) {
                binding.tvCurrentTime.text = "${formatTime(savedPos.toInt())} / --:--"
                binding.seekBar.progress = savedPos.toInt()
                binding.tvLiveIndicator.text = "恢复中..."
                android.util.Log.d("PlayerActivity", "initViews: pre-filled saved position ${savedPos}ms to avoid UI flicker")
            } else {
                binding.tvCurrentTime.text = "00:00 / 00:00"
            }
        } else {
            // freshLaunchTs=0（系统重建）或新鲜启动，不预填充位置
            binding.tvCurrentTime.text = "00:00 / 00:00"
            if (!isFreshStart && currentEpisode != null) {
                binding.tvLiveIndicator.text = "连接中..."
                writeJitterLog("initViews: freshLaunchTs=0, skipping saved position pre-fill, waiting for service")
            }
        }
        binding.tvTotalTime.text = "00:00"
        binding.tvCacheProgress.text = "缓存: 0%"
        binding.progressBuffer.visibility = View.GONE
        binding.tvAiProgress.visibility = View.GONE
        binding.progressAi.visibility = View.GONE
        binding.tvAiStatus.visibility = View.GONE

        segmentAdapter = VoiceSegmentAdapter()
        segmentAdapter?.setOnSegmentClickListener(object : VoiceSegmentAdapter.OnSegmentClickListener {
            override fun onSegmentClick(position: Int, segment: VoiceSegment) {
                // Feature C: click to seek
                playbackService?.seekTo(segment.start)
            }
            override fun onSegmentLongClick(position: Int, segment: VoiceSegment) {
                val isDry = !segment.isEffectiveDry()
                playbackService?.markSegment(position, isDry)
                segmentAdapter?.notifyItemChanged(position)
            }
        })
        binding.recyclerSegments.layoutManager = LinearLayoutManager(this)
        binding.recyclerSegments.adapter = segmentAdapter
        updateSegmentsUI()

        // Feature A: subtitle RecyclerView setup
        subtitleAdapter = SubtitleEntryAdapter()
        binding.recyclerSubtitles.layoutManager = LinearLayoutManager(this)
        binding.recyclerSubtitles.adapter = subtitleAdapter

        val isLiveNav = currentEpisode?.isLive ?: false
        if (!isLiveNav && episodeList.size > 1 && currentEpisodeIndex >= 0) {
            binding.layoutEpisodeNav.visibility = View.VISIBLE
            binding.tvEpisodeNavHint.text = " ▼ ${currentEpisodeIndex + 1}/${episodeList.size} "
            binding.btnPrevEpisode.setOnClickListener {
                playPrevEpisode()
            }
            binding.btnNextEpisode.setOnClickListener {
                playNextEpisode()
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

    private fun saveEpisodeListToPrefs() {
        try {
            val gson = com.google.gson.Gson()
            val json = gson.toJson(episodeList)
            getSharedPreferences("episode_list", MODE_PRIVATE).edit().putString("list", json).apply()

            // Also save to persistent all-episodes store for cross-day dislike filtering
            saveEpisodesToPersistentStore(episodeList)
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivity", "Failed to save episode list", e)
        }
    }

    // Issue 1: restore the cached episode list (written in onCreate) so that an Activity
    // recreated from the notification (which carries no episode data) can still rebuild its
    // episode list and locate the currently-playing episode returned by the service.
    private fun loadEpisodeListFromPrefs(): ArrayList<Episode> {
        val json = getSharedPreferences("episode_list_cache", MODE_PRIVATE).getString("episodes", "") ?: ""
        if (json.isBlank()) return ArrayList()
        return try {
            val arr = org.json.JSONArray(json)
            ArrayList<Episode>().apply {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(Episode().apply {
                        id = obj.getString("id")
                        title = obj.getString("title")
                        audioUrl = obj.getString("audio_url")
                        stationId = obj.optString("station_id", "")
                        stationName = obj.optString("station_name", "")
                        duration = obj.optLong("duration", 0)
                        broadcastAt = obj.optString("broadcast_at", "")
                    })
                }
            }
        } catch (_: Exception) { ArrayList() }
    }

    private fun saveEpisodesToPersistentStore(episodes: List<Episode>) {
        try {
            val prefs = getSharedPreferences("all_episodes", MODE_PRIVATE)
            val editor = prefs.edit()
            for (ep in episodes) {
                if (ep.audioUrl.isNullOrBlank()) continue
                val key = ep.audioUrl  // Use audio URL as unique key
                val json = com.google.gson.Gson().toJson(ep)
                editor.putString(key, json)
            }
            editor.apply()
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivity", "Failed to save to persistent episode store", e)
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
        binding.btnClose.setOnClickListener {
            writeJitterLog("btnClose: calling finish() to exit to MainActivity")
            finish()
        }
        // Issue 6 & 11: 点击节目导航提示（如 "1/10"）弹出当前节目列表，可高亮当前播放项并点击切换
        binding.tvEpisodeNavHint.setOnClickListener {
            writeEpisodeLog("tvEpisodeNavHint clicked, showing episode list dialog")
            writeJitterLog("tvEpisodeNavHint clicked, showing episode list dialog")
            showEpisodeListDialog()
        }

        binding.btnGenerateSubtitle.setOnClickListener {
            val episode = currentEpisode ?: return@setOnClickListener
            if (episode.id.isBlank()) {
                Toast.makeText(this, "无法生成字幕：缺少节目ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (subtitleProcessing) return@setOnClickListener
            startAiProcessing("subtitle")
            bindSubtitleService(episode, "subtitle")
        }

        binding.btnAiSegment.setOnClickListener {
            val episode = currentEpisode ?: return@setOnClickListener
            if (episode.id.isBlank()) {
                Toast.makeText(this, "无法AI分段：缺少节目ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (segmentProcessing) return@setOnClickListener
            startAiProcessing("segment")
            bindSubtitleService(episode, "segment")
        }

        binding.btnSubtitleToggle.setOnClickListener {
            // Toggle subtitle RecyclerView visibility instead of overlay
            if (binding.recyclerSubtitles.visibility == View.VISIBLE) {
                binding.recyclerSubtitles.visibility = View.GONE
            } else {
                binding.recyclerSubtitles.visibility = View.VISIBLE
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
        // 必须先 startService 再 bindService，确保服务作为"启动服务"运行
        // 仅 bindService 的服务在 Activity 解绑时会被系统杀死，导致后台播放被杀
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private val subtitleServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? SubtitleGeneratorService.LocalBinder ?: return
            subtitleService = binder.getService()
            subtitleServiceBound = true
            val episode = currentEpisode ?: return
            val taskType = pendingAiTaskType
            pendingAiTaskType = null
            if (taskType == "segment") {
                subtitleService?.generateSegmentsForEpisode(
                    episode.id, episode.audioUrl,
                    object : SubtitleGeneratorService.SegmentCallback {
                        override fun onSegmentGenerated(segment: VoiceSegment) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                val updated = voiceSegments.toMutableList()
                                updated.add(segment)
                                voiceSegments = updated
                                segmentAdapter?.setSegments(voiceSegments)
                                binding.recyclerSegments.visibility = View.VISIBLE
                            }
                        }
                        override fun onProgressUpdate(progress: Int, total: Int) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                binding.progressAi.progress = progress
                                binding.tvAiStatus.text = buildStatusText("segment", progress)
                            }
                        }
                        override fun onComplete(segments: List<VoiceSegment>) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                voiceSegments = voiceSegments.filter { !it.isSimulated }
                                voiceSegments = segments
                                segmentAdapter?.setSegments(segments)
                                binding.recyclerSegments.visibility = View.VISIBLE
                                finishAiProcessing("segment")
                                Toast.makeText(this@PlayerActivity, "AI分段完成，共${segments.size}个片段", Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun onError(error: String) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                finishAiProcessing("segment")
                                binding.tvAiStatus.text = "AI分段失败: $error"
                                binding.tvAiStatus.visibility = View.VISIBLE
                                Toast.makeText(this@PlayerActivity, error, Toast.LENGTH_SHORT).show()
                                android.util.Log.e("PlayerActivity", "AI Segment error: $error")
                            }
                        }
                    }
                )
            } else {
                subtitleService?.generateSubtitlesForEpisode(
                    episode.id, episode.audioUrl,
                    object : SubtitleGeneratorService.SubtitleCallback {
                        private val subtitleList = mutableListOf<com.radio.app.models.Transcript>()
                        override fun onSubtitleGenerated(transcript: com.radio.app.models.Transcript) {
                            subtitleList.add(transcript)
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                // Only show subtitle RecyclerView, hide overlay to avoid covering segments.
                                // Issue 8: keep recyclerSegments visible; subtitles live in their own area
                                // below the button and must not cover the segment list.
                                binding.subtitleView.visibility = View.GONE
                                // Feature A: update subtitle RecyclerView
                                subtitleTranscripts = subtitleList.toList()
                                subtitleAdapter?.setTranscripts(subtitleTranscripts)
                                binding.tvSubtitleTitle.visibility = View.VISIBLE
                                binding.recyclerSubtitles.visibility = View.VISIBLE
                            }
                        }
                        override fun onProgressUpdate(progress: Int, total: Int) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                binding.progressSubtitle.progress = progress
                                binding.tvSubtitleStatus.text = "字幕生成: $progress% (引擎: ${getCurrentAsrLabel()})"
                            }
                        }
                        override fun onComplete(transcripts: List<com.radio.app.models.Transcript>) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                finishAiProcessing("subtitle")
                                // Feature A: update subtitle RecyclerView with final list
                                subtitleTranscripts = transcripts
                                subtitleAdapter?.setTranscripts(subtitleTranscripts)
                                binding.subtitleView.visibility = View.GONE  // Hide overlay, use RecyclerView only
                                binding.tvSubtitleTitle.visibility = View.VISIBLE
                                binding.recyclerSubtitles.visibility = View.VISIBLE
                                Toast.makeText(this@PlayerActivity, "字幕生成完成，共${transcripts.size}条", Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun onError(error: String) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                finishAiProcessing("subtitle")
                                binding.tvSubtitleStatus.text = "字幕生成失败: $error"
                                binding.tvSubtitleStatus.visibility = View.VISIBLE
                                Toast.makeText(this@PlayerActivity, error, Toast.LENGTH_SHORT).show()
                                android.util.Log.e("PlayerActivity", "Subtitle error: $error")
                            }
                        }
                    }
                )
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            subtitleService = null
            subtitleServiceBound = false
            // Issue 9 (partial): the subtitle service died while we may have been showing
            // progress (cancelled via notification or killed by the system). Hide any
            // progress UI immediately so it does not get stuck visible or race to 100%.
            if (subtitleProcessing || segmentProcessing) {
                cancelAiProcessing()
            }
        }
    }

    private fun bindSubtitleService(episode: Episode, taskType: String) {
        if (subtitleServiceBound && subtitleService != null) {
            if (taskType == "segment") {
                subtitleService?.generateSegmentsForEpisode(
                    episode.id, episode.audioUrl,
                    object : SubtitleGeneratorService.SegmentCallback {
                        override fun onSegmentGenerated(segment: VoiceSegment) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                val updated = voiceSegments.toMutableList()
                                updated.add(segment)
                                voiceSegments = updated
                                segmentAdapter?.setSegments(voiceSegments)
                                binding.recyclerSegments.visibility = View.VISIBLE
                            }
                        }
                        override fun onProgressUpdate(progress: Int, total: Int) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                binding.progressAi.progress = progress
                                binding.tvAiStatus.text = buildStatusText("segment", progress)
                            }
                        }
                        override fun onComplete(segments: List<VoiceSegment>) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                voiceSegments = voiceSegments.filter { !it.isSimulated }
                                voiceSegments = segments
                                segmentAdapter?.setSegments(segments)
                                binding.recyclerSegments.visibility = View.VISIBLE
                                finishAiProcessing("segment")
                                Toast.makeText(this@PlayerActivity, "AI分段完成，共${segments.size}个片段", Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun onError(error: String) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                finishAiProcessing("segment")
                                binding.tvAiStatus.text = "AI分段失败: $error"
                                binding.tvAiStatus.visibility = View.VISIBLE
                                Toast.makeText(this@PlayerActivity, error, Toast.LENGTH_SHORT).show()
                                android.util.Log.e("PlayerActivity", "AI Segment error (bound): $error")
                            }
                        }
                    }
                )
            } else {
                subtitleService?.generateSubtitlesForEpisode(
                    episode.id, episode.audioUrl,
                    object : SubtitleGeneratorService.SubtitleCallback {
                        private val subtitleList = mutableListOf<com.radio.app.models.Transcript>()
                        override fun onSubtitleGenerated(transcript: com.radio.app.models.Transcript) {
                            subtitleList.add(transcript)
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                // Only show subtitle RecyclerView, hide overlay to avoid covering segments.
                                // Issue 8: keep recyclerSegments visible; subtitles live in their own area
                                // below the button and must not cover the segment list.
                                binding.subtitleView.visibility = View.GONE
                                // Feature A: update subtitle RecyclerView
                                subtitleTranscripts = subtitleList.toList()
                                subtitleAdapter?.setTranscripts(subtitleTranscripts)
                                binding.tvSubtitleTitle.visibility = View.VISIBLE
                                binding.recyclerSubtitles.visibility = View.VISIBLE
                            }
                        }
                        override fun onProgressUpdate(progress: Int, total: Int) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                binding.progressSubtitle.progress = progress
                                binding.tvSubtitleStatus.text = "字幕生成: $progress% (引擎: ${getCurrentAsrLabel()})"
                            }
                        }
                        override fun onComplete(transcripts: List<com.radio.app.models.Transcript>) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                finishAiProcessing("subtitle")
                                // Feature A: update subtitle RecyclerView with final list
                                subtitleTranscripts = transcripts
                                subtitleAdapter?.setTranscripts(subtitleTranscripts)
                                binding.subtitleView.visibility = View.GONE  // Hide overlay, use RecyclerView only
                                binding.tvSubtitleTitle.visibility = View.VISIBLE
                                binding.recyclerSubtitles.visibility = View.VISIBLE
                                Toast.makeText(this@PlayerActivity, "字幕生成完成，共${transcripts.size}条", Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun onError(error: String) {
                            runOnUiThread {
                                if (_binding == null) return@runOnUiThread
                                finishAiProcessing("subtitle")
                                binding.tvSubtitleStatus.text = "字幕生成失败: $error"
                                binding.tvSubtitleStatus.visibility = View.VISIBLE
                                Toast.makeText(this@PlayerActivity, error, Toast.LENGTH_SHORT).show()
                                android.util.Log.e("PlayerActivity", "Subtitle error (bound): $error")
                            }
                        }
                    }
                )
            }
            return
        }
        pendingAiTaskType = taskType
        val ep = currentEpisode ?: return
        // Start foreground service to keep running in background
        val intent = Intent(this, SubtitleGeneratorService::class.java).apply {
            putExtra("episode_id", ep.id)
            putExtra("audio_url", ep.audioUrl)
            putExtra("task_type", taskType)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, subtitleServiceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun updateUI() {
        if (_binding == null) return
        val ep = currentEpisode ?: playbackService?.getCurrentEpisode()
        if (ep != null && currentEpisode == null) {
            currentEpisode = ep
            val idx = episodeList.indexOfFirst { it.id == ep.id }
            if (idx >= 0) currentEpisodeIndex = idx
        }
        // 更新标题
        if (currentEpisode != null) {
            binding.tvStationName.text = currentEpisode!!.title ?: "节目回放"
            binding.tvEpisodeNavHint.text = " ▼ ${currentEpisodeIndex + 1}/${episodeList.size} "
            // Show broadcast date, duration
            val infoParts = mutableListOf<String>()
            if (!currentEpisode!!.broadcastAt.isNullOrBlank()) {
                infoParts.add("日期: ${currentEpisode!!.broadcastAt.take(10)}")
            }
            val dur = playbackService?.getDuration() ?: currentEpisode!!.duration
            if (dur > 0) {
                val totalMin = dur / 60000
                infoParts.add("时长: ${totalMin}分钟")
            }
            binding.tvEpisodeInfo.text = infoParts.joinToString("  |  ")
            binding.tvEpisodeInfo.visibility = if (infoParts.isNotEmpty()) View.VISIBLE else View.GONE
        } else if (currentStation != null) {
            binding.tvStationName.text = currentStation!!.name
            binding.tvEpisodeNavHint.text = " [直播] "
        }
        // 更新播放/暂停按钮
        playbackService?.let { updatePlayPauseButton(it.isPlaying()) }
        // 同步seekbar位置
        if (playbackService?.isPrepared() == true) {
            val svcPos = playbackService?.getCurrentPosition() ?: 0L
            val svcDur = playbackService?.getDuration() ?: -1L
            // Only update seekBar if we have valid position data (not 0 when playing)
            val isPlaying = playbackService?.isPlaying() ?: false
            if (svcPos > 0 || !isPlaying) {
                if (svcPos > 0) {
                    binding.seekBar.max = if (svcDur > 0) svcDur.toInt() else binding.seekBar.max
                    binding.seekBar.progress = svcPos.toInt()
                    binding.tvCurrentTime.text = "${formatTime(svcPos.toInt())} / ${if (svcDur > 0) formatTime(svcDur.toInt()) else "--:--"}"
                }
            }
            binding.tvLiveIndicator.text = if (playbackService?.isPlaying() == true) "播放中" else "已暂停"
            binding.tvLiveIndicator.visibility = View.VISIBLE
        }
        // Show playback progress percentage in the middle (tv_ai_progress)
        if (playbackService?.isPrepared() == true) {
            val svcPos = playbackService?.getCurrentPosition() ?: 0L
            val svcDur = playbackService?.getDuration() ?: -1L
            if (svcDur > 0) {
                val pct = (svcPos * 100 / svcDur).toInt()
                binding.tvAiProgress.text = "${pct}%"
                binding.tvAiProgress.visibility = View.VISIBLE
            }
        }
        // 更新缓存URL显示
        if (currentEpisode != null) {
            val cacheFileName = extractCacheFileName(currentEpisode!!.audioUrl ?: "")
            binding.tvNetworkUrl.text = "网络: ${currentEpisode!!.audioUrl}"
            binding.tvNetworkUrl.visibility = View.VISIBLE
            binding.tvCacheUrl.text = "本地缓存: ${cacheDir.absolutePath}/episodes/$cacheFileName"
            binding.tvCacheUrl.visibility = View.VISIBLE
        }
        // 更新缓存进度
        if (playbackService?.isLive() == true) {
            binding.tvCacheProgress.visibility = View.GONE
            binding.seekBarCache.visibility = View.GONE
        }
    }

    private fun playEpisodeAtIndex(index: Int) {
        if (index < 0 || index >= episodeList.size) return
        writeJitterLog("playEpisodeAtIndex: START, index=$index, episodeList.size=${episodeList.size}")
        writeEpisodeLog("playEpisodeAtIndex: START, index=$index, episodeList.size=${episodeList.size}")
        if (playbackService == null) {
            writeEpisodeLog("playEpisodeAtIndex: ERROR - playbackService is null, cannot switch episode")
            return
        }
        var targetIdx = index
        var targetEpisode = episodeList[targetIdx]
        val settings = AppSettings.getInstance(this)
        // 跳过不喜欢的节目（最多跳过10个，避免死循环）
        var skipCount = 0
        while (skipCount < 10) {
            if (!settings.isDisliked(targetEpisode.id) && !settings.isDislikedByTitle(targetEpisode.stationId, targetEpisode.title)) {
                break
            }
            skipCount++
            // 向前或向后继续找
            targetIdx = if (index > currentEpisodeIndex) targetIdx + 1 else targetIdx - 1
            if (targetIdx < 0 || targetIdx >= episodeList.size) {
                Toast.makeText(this, "附近没有非不喜欢的节目了", Toast.LENGTH_SHORT).show()
                return
            }
            targetEpisode = episodeList[targetIdx]
        }
        currentEpisodeIndex = targetIdx
        currentEpisode = targetEpisode
        // Issue 10 Fix 2: clear old subtitles when switching episodes
        clearSubtitles()
        saveLastEpisode()
        writeJitterLog("playEpisodeAtIndex: calling playEpisode with ${targetEpisode.title}")
        writeEpisodeLog("playEpisodeAtIndex: calling playEpisode with ${targetEpisode.title}")
        writeEpisodeLog("playEpisodeAtIndex: BEFORE switch - clicked index=$index, targetIdx=$targetIdx, targetEpisode.title=${targetEpisode.title}, targetEpisode.id=${targetEpisode.id}")
        val beforeEpisode = playbackService?.getCurrentEpisode()
        writeEpisodeLog("playEpisodeAtIndex: BEFORE switch - service current episode=${beforeEpisode?.title} (id=${beforeEpisode?.id})")
        playbackService?.playEpisode(targetEpisode, false)
        val afterEpisode = playbackService?.getCurrentEpisode()
        writeEpisodeLog("playEpisodeAtIndex: AFTER switch - service current episode=${afterEpisode?.title} (id=${afterEpisode?.id})")
        writeEpisodeLog("playEpisodeAtIndex: AFTER switch - target was ${targetEpisode.title}, service reports ${afterEpisode?.title}, match=${targetEpisode.id == afterEpisode?.id}")
        // Issue 5 Fix: 延迟校验（500ms）确认切换是否真正生效
        Handler(Looper.getMainLooper()).postDelayed({
            val verifyEpisode = playbackService?.getCurrentEpisode()
            writeEpisodeLog("playEpisodeAtIndex: DELAYED VERIFY (500ms) - service episode=${verifyEpisode?.title} (id=${verifyEpisode?.id}), target was ${targetEpisode.title} (id=${targetEpisode.id}), match=${targetEpisode.id == verifyEpisode?.id}")
        }, 500)
        // Issue 4: 切歌后刷新节目列表适配器，使高亮跟随当前播放节目
        episodeListAdapter?.let { adapter ->
            adapter.currentlyPlayingId = targetEpisode.id
            adapter.notifyDataSetChanged()
            writeEpisodeLog("playEpisodeAtIndex: refreshed adapter, currentlyPlayingId=${adapter.currentlyPlayingId}")
        }
        voiceSegments = generateSimulatedSegments()
        if (voiceSegments.isNotEmpty()) updateSegmentsUI()
        updateUI()
        setupPreCacheList()
        android.util.Log.d("PlayerActivity", "playEpisodeAtIndex: switched to ${targetEpisode.title}, index=$currentEpisodeIndex")
    }

    // Issue 6 & 11: 弹出当前节目列表对话框。高亮正在播放的节目（Issue 6），点击任意节目通过
    // playEpisodeAtIndex 切换播放并关闭对话框（Issue 11，修复点击切换失效的回归）。
    private fun showEpisodeListDialog() {
        if (episodeList.isEmpty()) {
            Toast.makeText(this, "没有可显示的节目列表", Toast.LENGTH_SHORT).show()
            return
        }
        writeJitterLog("showEpisodeListDialog: START, episodeList.size=${episodeList.size}, currentEpisodeIndex=$currentEpisodeIndex, currentEpisodeId=${currentEpisode?.id}")
        writeEpisodeLog("showEpisodeListDialog: START, episodeList.size=${episodeList.size}, currentEpisodeIndex=$currentEpisodeIndex, currentEpisodeId=${currentEpisode?.id}")
        val currentId = currentEpisode?.id ?: playbackService?.getCurrentEpisode()?.id
        // 确保当前索引与正在播放的节目一致
        val actualIdx = episodeList.indexOfFirst { it.id == currentId }
        if (actualIdx >= 0) currentEpisodeIndex = actualIdx

        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity)
            setHasFixedSize(true)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            // 限制最大高度，长列表时内部滚动，避免对话框超出屏幕
            val maxHeight = (resources.displayMetrics.heightPixels * 0.7).toInt()
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeight)
        }
        val listAdapter = EpisodeListAdapter(episodeList, currentId)
        // Issue 4: 持有适配器引用，切歌后可刷新高亮
        episodeListAdapter = listAdapter
        writeEpisodeLog("showEpisodeListDialog: creating adapter with currentId=$currentId, episodeList.size=${episodeList.size}")
        writeJitterLog("showEpisodeListDialog: adapter created, currentlyPlayingId=${currentEpisode?.id}")
        writeEpisodeLog("showEpisodeListDialog: adapter created, currentlyPlayingId=${currentEpisode?.id}, listAdapter.currentlyPlayingId=$currentId")
        listAdapter.onItemClicked = { position ->
            writeEpisodeLog("showEpisodeListDialog: onItemClicked position=$position, episode=${episodeList.getOrNull(position)?.title}")
            if (position in episodeList.indices) {
                val episode = episodeList[position]
                writeJitterLog("showEpisodeListDialog: episode clicked at position=$position, episode=${episode.title}, id=${episode.id}")
                writeEpisodeLog("showEpisodeListDialog: episode clicked at position=$position, episode=${episode.title}, id=${episode.id}")
                writeJitterLog("showEpisodeListDialog: calling playEpisodeAtIndex($position)")
                writeEpisodeLog("showEpisodeListDialog: calling playEpisodeAtIndex($position)")
                playEpisodeAtIndex(position)
            }
        }
        recyclerView.adapter = listAdapter

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("节目列表 (${episodeList.size})")
            .setView(recyclerView)
            .setNegativeButton("关闭", null)
            .create()
        listAdapter.onDismiss = { dialog.dismiss() }

        // 滚动到当前播放项，便于用户定位
        if (currentEpisodeIndex in episodeList.indices) {
            recyclerView.post {
                (recyclerView.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(currentEpisodeIndex, 0)
            }
        }
        writeEpisodeLog("showEpisodeListDialog: showing dialog with ${episodeList.size} episodes, currentEpisodeIndex=$currentEpisodeIndex, currentId=$currentId")
        dialog.show()
    }

    // 解析主题属性对应的颜色（兼容直接颜色值与颜色资源引用）
    private fun resolveThemeColor(context: Context, attrId: Int): Int {
        val tv = android.util.TypedValue()
        if (!context.theme.resolveAttribute(attrId, tv, true)) return Color.BLACK
        return if (tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT &&
            tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
            tv.data
        } else if (tv.resourceId != 0) {
            ContextCompat.getColor(context, tv.resourceId)
        } else {
            Color.BLACK
        }
    }

    // Issue 6: 节目列表适配器，高亮当前正在播放的节目
    // 高亮方式：背景色 + "正在播放" 标签 + 加粗标题 + 播放按钮变为暂停图标
    inner class EpisodeListAdapter(
        private val episodes: List<Episode>,
        // Issue 4: 改为 var 以便切歌后更新高亮的当前播放项
        var currentlyPlayingId: String?
    ) : RecyclerView.Adapter<EpisodeListAdapter.ViewHolder>() {
        var onItemClicked: ((Int) -> Unit)? = null
        var onDismiss: (() -> Unit)? = null

        private val dateIn = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
        private val dateOut = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val episode = episodes[position]
            writeEpisodeLog("onBindViewHolder: position=$position, episode.title=${episode.title}, episode.id=${episode.id}, currentlyPlayingId=$currentlyPlayingId, isPlaying=${episode.id == currentlyPlayingId}")
            // Issue 6 Fix: 同时匹配 episode ID 和基于 title 的匹配（跨天同一节目也能高亮）
            // Issue 4 Fix: 当 currentlyPlayingId 为空时，回退到按 title 匹配当前播放节目
            val isPlaying = episode.id == currentlyPlayingId ||
                (currentlyPlayingId.isNullOrEmpty() && currentEpisode?.title == episode.title) ||
                (currentEpisode != null && episode.title != null &&
                 currentEpisode?.title == episode.title &&
                 currentEpisode?.stationId == episode.stationId)
            writeEpisodeLog("onBindViewHolder: position=$position, title=${episode.title}, id=${episode.id}, isCurrentlyPlaying=$isPlaying, currentlyPlayingId=$currentlyPlayingId")

            holder.tvTitle.text = if (isPlaying) "▶ ${episode.title}" else episode.title
            holder.tvTime.text = try {
                dateIn.parse(episode.broadcastAt)?.let { dateOut.format(it) } ?: episode.broadcastAt
            } catch (_: Exception) {
                episode.broadcastAt
            }
            val durationMin = episode.duration / 60
            val segments = episode.voiceSegments?.size ?: 0
            holder.tvDescription.text = "${durationMin}分钟 · ${segments}片段"

            // 解析主题色用于高亮/还原（适配深色/浅色主题）
            val ctx = holder.itemView.context
            val accentColor = resolveThemeColor(ctx, android.R.attr.colorPrimary)
            val titleColor = resolveThemeColor(ctx, com.radio.app.R.attr.appTextPrimary)

            if (isPlaying) {
                // Issue 6: 高亮当前播放节目 - 使用更明显的背景色
                val tint = Color.argb(60, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                holder.itemView.setBackgroundColor(tint)
                holder.tvTitle.setTypeface(null, android.graphics.Typeface.BOLD)
                holder.tvTitle.setTextColor(accentColor)
                holder.tvPlayingIndicator.text = "正在播放"
                holder.tvPlayingIndicator.setTextColor(accentColor)
                holder.tvPlayingIndicator.visibility = View.VISIBLE
                // Issue 6 Fix: 播放按钮变为暂停图标，并用主题色着色
                holder.btnPlay.setImageResource(android.R.drawable.ic_media_pause)
                holder.btnPlay.setColorFilter(accentColor)
                writeEpisodeLog("onBindViewHolder: HIGHLIGHT set for position=$position title=${episode.title}, bgColor=$tint, textColor=$accentColor, indicator=VISIBLE, btnPlay=pause")
            } else {
                // Issue 6 Fix: 恢复原始背景（selectableItemBackground），而非简单设置透明
                holder.itemView.background = holder.originalBackground
                holder.tvTitle.setTypeface(null, android.graphics.Typeface.NORMAL)
                holder.tvTitle.setTextColor(titleColor)
                holder.tvPlayingIndicator.visibility = View.GONE
                // Issue 6 Fix: 恢复播放按钮为默认播放图标
                holder.btnPlay.setImageResource(R.drawable.ic_play)
                holder.btnPlay.clearColorFilter()
                writeEpisodeLog("onBindViewHolder: normal style for position=$position title=${episode.title}, bgColor=original, textColor=$titleColor, indicator=GONE, btnPlay=play")
            }

            holder.itemView.setOnClickListener {
                onItemClicked?.invoke(position)
                onDismiss?.invoke()
            }
        }

        override fun getItemCount(): Int = episodes.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTime: TextView = view.findViewById(R.id.tv_time)
            val tvTitle: TextView = view.findViewById(R.id.tv_title)
            val tvDescription: TextView = view.findViewById(R.id.tv_description)
            // 复用缓存指示位作为 "正在播放" 标签展示位
            val tvPlayingIndicator: TextView = view.findViewById(R.id.tv_cached_indicator)
            // Issue 6 Fix: 引用播放按钮，高亮时切换为暂停图标
            val btnPlay: ImageView = view.findViewById(R.id.btn_play)
            // Issue 6 Fix: 保存原始背景，用于在非播放项上恢复 selectableItemBackground
            val originalBackground: android.graphics.drawable.Drawable? = view.background
        }
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        if (_binding == null) return
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

    private fun getCurrentAiModelLabel(): String {
        val settings = AppSettings.getInstance(this)
        return when (settings.safeAiModel()) {
            AppSettings.AI_MODEL_WENXIN -> "文心一言"
            AppSettings.AI_MODEL_DEEPSEEK -> "DeepSeek"
            AppSettings.AI_MODEL_QWEN -> "通义千问"
            AppSettings.AI_MODEL_FUNASR -> "FunASR"
            AppSettings.AI_MODEL_WHISPER -> "Whisper"
            AppSettings.AI_MODEL_JIU_AI_TING -> "就AI听"
            AppSettings.AI_MODEL_MNN_LLM -> "阿里MNN-LLM"
            else -> settings.safeAiModel()
        }
    }

    private fun getCurrentAsrLabel(): String {
        val settings = AppSettings.getInstance(this)
        return when (settings.safeAsrProvider()) {
            AppSettings.ASR_BAIDU -> "百度ASR"
            AppSettings.ASR_FUNASR -> "FunASR"
            AppSettings.ASR_WHISPER -> "Whisper"
            AppSettings.ASR_VOSK -> "Vosk离线"
            else -> settings.safeAsrProvider()
        }
    }

    private fun startAiProcessing(taskType: String) {
        if (taskType == "subtitle") subtitleProcessing = true
        else if (taskType == "segment") segmentProcessing = true
        saveProcessingState()
        if (_binding == null) return
        if (taskType == "subtitle") {
            binding.progressSubtitle.progress = 0
            binding.progressSubtitle.visibility = View.VISIBLE
            binding.tvSubtitleStatus.visibility = View.VISIBLE
            binding.tvSubtitleStatus.text = "字幕生成: 0% (引擎: ${getCurrentAsrLabel()})"
            binding.btnGenerateSubtitle.isEnabled = false
        } else {
            binding.progressAi.progress = 0
            binding.progressAi.visibility = View.VISIBLE
            binding.tvAiStatus.visibility = View.VISIBLE
            binding.tvAiStatus.text = buildStatusText(taskType, 0)
            binding.btnAiSegment.isEnabled = false
        }
    }

    private fun finishAiProcessing(taskType: String) {
        if (taskType == "subtitle") {
            subtitleProcessing = false
            if (_binding != null) {
                binding.progressSubtitle.visibility = View.GONE
                binding.tvSubtitleStatus.visibility = View.GONE
                binding.btnGenerateSubtitle.isEnabled = true
            }
        } else {
            segmentProcessing = false
            if (_binding != null) {
                binding.progressAi.visibility = View.GONE
                binding.tvAiStatus.visibility = View.GONE
                binding.btnAiSegment.isEnabled = true
            }
        }
        saveProcessingState()
    }

    /**
     * Issue 9 (partial): Hide ALL AI/subtitle progress UI immediately when generation is
     * cancelled (or when a stale processing state is detected on resume).
     *
     * finishAiProcessing(taskType) only clears a single task type, so a cancelled subtitle
     * task could leave the segment progress bar visible (and vice-versa). This helper clears
     * BOTH the subtitle and segment progress UI/flags so a cancelled task can never leave a
     * dangling progress bar that flashes or races to 100%.
     */
    private fun cancelAiProcessing() {
        subtitleProcessing = false
        segmentProcessing = false
        if (_binding != null) {
            binding.progressSubtitle.visibility = View.GONE
            binding.progressAi.visibility = View.GONE
            binding.tvSubtitleStatus.visibility = View.GONE
            binding.tvAiStatus.visibility = View.GONE
            binding.btnGenerateSubtitle.isEnabled = true
            binding.btnAiSegment.isEnabled = true
        }
        saveProcessingState()
    }

    private fun buildStatusText(taskType: String, progress: Int): String {
        val modelLabel = if (taskType == "segment") getCurrentAiModelLabel() else getCurrentAsrLabel()
        return if (taskType == "segment") "AI分段: $progress% (模型: $modelLabel)" else "字幕生成: $progress% (引擎: $modelLabel)"
    }

    private fun playNextEpisode() {
        getSharedPreferences("player_processing_state", MODE_PRIVATE).edit().apply {
            putBoolean("subtitle_processing", false)
            putBoolean("segment_processing", false)
        }.apply()

        val settings = AppSettings.getInstance(this)
        val episodes = episodeList
        if (episodes.isEmpty()) return

        // Ensure currentEpisodeIndex is correct
        val actualIdx = episodes.indexOfFirst { it.id == currentEpisode?.id }
        if (actualIdx >= 0) currentEpisodeIndex = actualIdx
        android.util.Log.d("PlayerActivity", "playNextEpisode: currentIdx=$currentEpisodeIndex, listSize=${episodes.size}")

        var targetIdx = currentEpisodeIndex + 1
        var skipCount = 0
        while (targetIdx < episodes.size && skipCount < 20) {
            val ep = episodes[targetIdx]
            if (!settings.isDisliked(ep.id) && !settings.isDislikedByTitle(ep.stationId, ep.title)) {
                // Found a non-disliked episode
                currentEpisode = ep
                // Issue 10 Fix 2: clear old subtitles when switching to next episode
                clearSubtitles()
                currentEpisodeIndex = targetIdx
                saveLastEpisode()
                val episodeKey = "${ep.stationId}::${ep.title}"
                val savedPos = getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(episodeKey, -1L)
                val startPos = if (savedPos > 0) savedPos else -1L
                playbackService?.playEpisode(ep, false, startPos)
                voiceSegments = generateSimulatedSegments()
                if (voiceSegments.isNotEmpty()) updateSegmentsUI()
                updateUI()
                setupPreCacheList()
                android.util.Log.d("PlayerActivity", "playNextEpisode: switched to ${ep.title}, index=$targetIdx (skipped $skipCount disliked)")
                return
            }
            skipCount++
            targetIdx++
        }

        // No more episodes in current list, try cross-day
        fetchAndPlayCrossDayEpisode(1)
    }

    private fun playPrevEpisode() {
        getSharedPreferences("player_processing_state", MODE_PRIVATE).edit().apply {
            putBoolean("subtitle_processing", false)
            putBoolean("segment_processing", false)
        }.apply()

        val settings = AppSettings.getInstance(this)
        val episodes = episodeList
        if (episodes.isEmpty()) return

        // Ensure currentEpisodeIndex is correct
        val actualIdx = episodes.indexOfFirst { it.id == currentEpisode?.id }
        if (actualIdx >= 0) currentEpisodeIndex = actualIdx
        android.util.Log.d("PlayerActivity", "playPrevEpisode: currentIdx=$currentEpisodeIndex, listSize=${episodes.size}")

        var targetIdx = currentEpisodeIndex - 1
        var skipCount = 0
        while (targetIdx >= 0 && skipCount < 20) {
            val ep = episodes[targetIdx]
            if (!settings.isDisliked(ep.id) && !settings.isDislikedByTitle(ep.stationId, ep.title)) {
                currentEpisode = ep
                // Issue 10 Fix 2: clear old subtitles when switching to prev episode
                clearSubtitles()
                currentEpisodeIndex = targetIdx
                saveLastEpisode()
                val episodeKey = "${ep.stationId}::${ep.title}"
                val savedPos = getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(episodeKey, -1L)
                val startPos = if (savedPos > 0) savedPos else -1L
                playbackService?.playEpisode(ep, false, startPos)
                voiceSegments = generateSimulatedSegments()
                if (voiceSegments.isNotEmpty()) updateSegmentsUI()
                updateUI()
                setupPreCacheList()
                android.util.Log.d("PlayerActivity", "playPrevEpisode: switched to ${ep.title}, index=$targetIdx (skipped $skipCount disliked)")
                return
            }
            skipCount++
            targetIdx--
        }

        // No more episodes in current list, try cross-day
        fetchAndPlayCrossDayEpisode(-1)
    }

    private fun findAdjacentEpisode(current: Episode?, direction: Int): Episode? {
        if (current == null) return null
        val episodes = getEpisodeList()
        val idx = episodes.indexOfFirst { it.id == current.id }
        if (idx < 0) return null
        val targetIdx = idx + direction
        return if (targetIdx in episodes.indices) episodes[targetIdx] else null
    }

    /**
     * 跨天获取相邻日期的节目列表并播放
     * @param direction 1=下一天, -1=前一天
     */
    private fun fetchAndPlayCrossDayEpisode(direction: Int) {
        val episode = currentEpisode ?: return
        val stationId = episode.stationId
        if (stationId.isBlank()) {
            Toast.makeText(this, if (direction > 0) "没有更多节目了" else "没有更早的节目了", Toast.LENGTH_SHORT).show()
            return
        }
        val broadcastAt = episode.broadcastAt ?: ""
        val currentDateStr = if (broadcastAt.length >= 10) broadcastAt.substring(0, 10) else ""
        if (currentDateStr.isBlank()) {
            Toast.makeText(this, if (direction > 0) "没有更多节目了" else "没有更早的节目了", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, if (direction > 0) "正在获取下一天节目..." else "正在获取前一天节目...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                dateFormat.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
                cal.time = dateFormat.parse(currentDateStr) ?: return@Thread
                cal.add(java.util.Calendar.DAY_OF_YEAR, direction)
                val targetDate = dateFormat.format(cal.time)

                android.util.Log.d("PlayerActivity", "fetchAndPlayCrossDayEpisode: fetching $stationId on $targetDate (direction=$direction)")
                val apiService = com.radio.app.network.EpisodeApiService.getInstance()
                val newEpisodes = apiService.fetchEpisodesByDateSync(stationId, targetDate)

                if (newEpisodes.isNullOrEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this, if (direction > 0) "没有更多节目了" else "没有更早的节目了", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                // 过滤掉没有有效音频URL的节目
                val validEpisodes = newEpisodes.filter { it.audioUrl.isNotBlank() && it.audioUrl.startsWith("http") }
                if (validEpisodes.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this, if (direction > 0) "没有更多节目了" else "没有更早的节目了", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                // 过滤掉已不喜欢的节目
                val settings = AppSettings.getInstance(this)
                val nonDisliked = validEpisodes.filter {
                    !settings.isDisliked(it.id) && !settings.isDislikedByTitle(it.stationId, it.title)
                }
                if (nonDisliked.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this, if (direction > 0) "没有更多节目了" else "没有更早的节目了", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                runOnUiThread {
                    // 更新节目列表为新一天的节目
                    episodeList = ArrayList(nonDisliked)
                    saveEpisodeListToPrefs()
                    // direction > 0 (next): 播放第一天第一个节目
                    // direction < 0 (prev): 播放最后一天最后一个节目
                    val targetIndex = if (direction > 0) 0 else nonDisliked.size - 1
                    currentEpisodeIndex = targetIndex
                    val targetEpisode = nonDisliked[targetIndex]
                    currentEpisode = targetEpisode
                    // Issue 10 Fix 2: clear old subtitles when crossing to another day's episode
                    clearSubtitles()
                    saveLastEpisode()
                    val episodeKey = "${targetEpisode.stationId}::${targetEpisode.title}"
                    val savedPos = getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(episodeKey, -1L)
                    val startPos = if (savedPos > 0) savedPos else -1L
                    playbackService?.playEpisode(targetEpisode, false, startPos)
                    voiceSegments = generateSimulatedSegments()
                    if (voiceSegments.isNotEmpty()) updateSegmentsUI()
                    updateUI()
                    setupPreCacheList()
                    android.util.Log.d("PlayerActivity", "fetchAndPlayCrossDayEpisode: crossed to $targetDate, playing ${targetEpisode.title}, index=$currentEpisodeIndex")
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "fetchAndPlayCrossDayEpisode failed", e)
                runOnUiThread {
                    Toast.makeText(this, "获取跨天节目失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun getEpisodeList(): List<Episode> {
        // 优先使用内存中的列表
        if (episodeList.isNotEmpty()) return episodeList
        // 从缓存获取
        val prefs = getSharedPreferences("episode_list_cache", MODE_PRIVATE)
        val json = prefs.getString("episodes", null) ?: return emptyList()
        try {
            val arr = org.json.JSONArray(json)
            val list = mutableListOf<Episode>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(Episode().apply {
                    id = obj.optString("id", "")
                    title = obj.optString("title", "")
                    audioUrl = obj.optString("audio_url", "")
                    stationId = obj.optString("station_id", "")
                    stationName = obj.optString("station_name", "")
                    duration = obj.optLong("duration", 0)
                    broadcastAt = obj.optString("broadcast_at", "")
                })
            }
            return list
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private fun saveLastEpisode() {
        currentEpisode?.let { ep ->
            getSharedPreferences("last_episode", MODE_PRIVATE).edit().apply {
                putString("episode_id", ep.id)
                putString("title", ep.title)
                putString("audio_url", ep.audioUrl)
                putString("station_name", ep.stationName)
                putString("station_id", ep.stationId)
                putLong("duration", ep.duration)
                putString("broadcast_at", ep.broadcastAt)
                putString("program_name", ep.programName)
            }.apply()
        }
    }

    private fun generateSimulatedSegments(): List<VoiceSegment> {
        val dur = playbackService?.getDuration()?.toInt() ?: 0
        if (dur <= 0) return emptyList()
        val segmentDuration = 300 // 5分钟一段
        val count = minOf((dur / 1000 / segmentDuration).coerceAtLeast(3), 20)
        val segments = mutableListOf<VoiceSegment>()
        for (i in 0 until count) {
            val startMs = (i * dur) / count
            val endMs = if (i == count - 1) dur else ((i + 1) * dur) / count
            val seg = VoiceSegment().apply {
                this.start = startMs.toLong()
                this.end = endMs.toLong()
                this.label = "${formatTime(startMs)} - ${formatTime(endMs)}"
                this.isSimulated = true
            }
            segments.add(seg)
        }
        return segments
    }

    private fun updateSegmentsUI() {
        if (_binding == null) return
        segmentAdapter?.setSegments(voiceSegments)
        if (voiceSegments.isEmpty()) {
            binding.recyclerSegments.visibility = View.GONE
        } else {
            binding.recyclerSegments.visibility = View.VISIBLE
        }
    }

    override fun onBackPressed() {
        // Issue 7: Restore single-tap back to exit (pre-v2.0.32 behavior).
        // User explicitly requested: one back press exits to MainActivity playlist.
        // DO NOT use moveTaskToBack or double-tap - these were rejected by the user.
        writeJitterLog("onBackPressed: calling super.onBackPressed() to exit to MainActivity")
        super.onBackPressed()
    }

    override fun finish() {
        writeJitterLog("finish() called! stacktrace:\n${android.util.Log.getStackTraceString(Exception())}")
        super.finish()
    }

    override fun onResume() {
        super.onResume()
        writeJitterLog("onResume: subtitleProcessing=$subtitleProcessing, segmentProcessing=$segmentProcessing, serviceBound=$serviceBound")
        // 恢复处理状态持久化
        restoreProcessingState()
        
        // Feature B: start position update for highlighting
        positionUpdateHandler.post(positionUpdateRunnable)
        
        // Issue 9 (partial): If a processing flag is still set but the subtitle service is
        // NOT bound, the saved state is stale — the task was cancelled (e.g. via the
        // notification "取消" action) or the Activity was recreated without the running task.
        // Re-binding would either restart a cancelled task (spurious progress bar that races
        // to 100%) or attach to a running task whose callbacks point to the old, destroyed
        // Activity (stuck progress bar). Clear the stale state BEFORE showing any progress UI
        // so the cancelled progress bar never appears. Partial DB results are loaded below.
        if (!subtitleServiceBound && (subtitleProcessing || segmentProcessing)) {
            android.util.Log.d("PlayerActivity", "onResume: subtitle service not bound but processing flag set (subtitle=$subtitleProcessing, segment=$segmentProcessing) — treating as cancelled, hiding progress UI")
            writeJitterLog("onResume: stale processing state (subtitle=$subtitleProcessing, segment=$segmentProcessing), hiding progress UI on cancel")
            cancelAiProcessing()
        }

        // 根据处理状态恢复按钮和进度条
        if (_binding != null) {
            if (subtitleProcessing) {
                binding.btnGenerateSubtitle.isEnabled = false
                binding.progressSubtitle.visibility = View.VISIBLE
                binding.tvSubtitleStatus.visibility = View.VISIBLE
            } else {
                binding.btnGenerateSubtitle.isEnabled = true
            }
            if (segmentProcessing) {
                binding.btnAiSegment.isEnabled = false
                binding.progressAi.visibility = View.VISIBLE
                binding.tvAiStatus.visibility = View.VISIBLE
            } else {
                binding.btnAiSegment.isEnabled = true
            }
        }
        
        // Issue 9 (partial): the previous block re-bound the subtitle service here to resume
        // progress updates. That re-bind either restarted a cancelled task (spurious progress
        // bar racing to 100%) or attached to a running task whose callbacks were lost, leaving
        // a stuck progress bar. Stale processing state is now cleared above (cancelAiProcessing)
        // before any progress UI is shown, so no re-bind is needed here.

        // Issue 1: Restore cached playback position to prevent seekbar rewind
        val cachedPos = getSharedPreferences("player_position_cache", MODE_PRIVATE).getLong("cached_position", 0L)
        val cachedDur = getSharedPreferences("player_position_cache", MODE_PRIVATE).getLong("cached_duration", 0L)
        val cachedEpId = getSharedPreferences("player_position_cache", MODE_PRIVATE).getString("cached_episode_id", "")
        if (cachedPos > 0 && cachedEpId == currentEpisode?.id && _binding != null) {
            binding.seekBar.max = cachedDur.toInt()
            binding.seekBar.progress = cachedPos.toInt()
            binding.tvCurrentTime.text = "${formatTime(cachedPos.toInt())} / ${if (cachedDur > 0) formatTime(cachedDur.toInt()) else "--:--"}"
            writeJitterLog("onResume: restored cached position=$cachedPos, duration=$cachedDur")
        }
        // Issue 1 Fix 4: await a valid position from the service before letting the
        // position-update runnable touch the UI, so the restored cached position holds.
        awaitingServicePosition = true

        restoreBackgroundResults()
    }

    private fun restoreBackgroundResults() {
        val episode = currentEpisode ?: return
        if (episode.id.isBlank()) return

        val dbHelper = RadioDatabaseHelper.getInstance(this)

        // 检查AI分段结果
        val dbSegments = dbHelper.getVoiceSegments(episode.id)
        if (dbSegments.isNotEmpty()) {
            val realSegments = dbSegments.filter { !it.isSimulated }
            if (realSegments.isNotEmpty() && (voiceSegments.isEmpty() || voiceSegments.all { it.isSimulated })) {
                voiceSegments = realSegments
                updateSegmentsUI()
            }
        }

        // 检查字幕结果
        val dbTranscripts = dbHelper.getTranscripts(episode.id)
        android.util.Log.d("PlayerActivity", "restoreSubtitles: episode=${episode.id}, found=${dbTranscripts.size} transcripts")
        if (dbTranscripts.isNotEmpty()) {
            binding.subtitleView.visibility = View.GONE  // Hide overlay
            // Issue 10 Fix 1/4: Do NOT hide recyclerSegments here. Both segments and
            // subtitles should remain visible at the same time. Hiding segments when
            // subtitles exist previously caused segments to be permanently hidden.
            // Feature A: restore subtitle RecyclerView
            subtitleTranscripts = dbTranscripts
            subtitleAdapter?.setTranscripts(subtitleTranscripts)
            binding.tvSubtitleTitle.visibility = View.VISIBLE
            binding.recyclerSubtitles.visibility = View.VISIBLE
            // Keep segments visible too (do not touch recyclerSegments visibility).
        } else {
            // Issue 8: no subtitles for this episode — hide the subtitle RecyclerView and its
            // title so the subtitle area below the button is empty, while segments stay visible.
            binding.recyclerSubtitles.visibility = View.GONE
            binding.tvSubtitleTitle.visibility = View.GONE
        }
    }

    // Issue 10 Fix 2: Clear old subtitles when switching episodes so each episode
    // only shows its own subtitles. Call this after every currentEpisode assignment
    // that changes the episode to prevent stale subtitles from the previous episode.
    private fun clearSubtitles() {
        subtitleTranscripts = emptyList()
        lastSubtitleHighlightIdx = -1
        subtitleAdapter?.setTranscripts(emptyList())
        if (_binding != null) {
            binding.subtitleView.visibility = View.GONE
            binding.tvSubtitleTitle.visibility = View.GONE
            binding.recyclerSubtitles.visibility = View.GONE
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        writeJitterLog("onNewIntent: action=${intent.action}, fromNotification=${intent.getBooleanExtra("from_notification", false)}, hasEpisode=${intent.hasExtra("episode")}")
        // Update the intent so that onCreate/getIntent can use the new intent
        setIntent(intent)

        val newEpisode = intent.getSerializableExtra("episode") as? Episode
        if (newEpisode == null) {
            // Issue 1: 通知点击带来的 intent 不含 episode（v2.0.30 起 createContentIntent 不再附带
            // episode）。此时只需与服务的当前播放状态同步，不要重启播放，避免通知更新引发的抖动循环。
            writeJitterLog("onNewIntent: no episode in intent, syncing to service state")
            if (serviceBound && playbackService != null) {
                val svcEpisode = playbackService?.getCurrentEpisode()
                if (svcEpisode != null && svcEpisode.id != currentEpisode?.id) {
                    currentEpisode = svcEpisode
                    clearSubtitles()
                    val newIdx = episodeList.indexOfFirst { it.id == svcEpisode.id }
                    if (newIdx >= 0) currentEpisodeIndex = newIdx
                    updateUI()
                    restoreBackgroundResults()
                    writeJitterLog("onNewIntent: synced to service episode: ${svcEpisode.title}")
                } else {
                    writeJitterLog("onNewIntent: service playing same episode or no episode, just update UI")
                    updateUI()
                }
            } else {
                writeJitterLog("onNewIntent: service not bound, just update UI")
                updateUI()
            }
            return
        }

        // Episode provided in intent (from program list click, etc.)
        currentEpisode = newEpisode
        // Issue 10 Fix 2: clear old subtitles when switching to a new episode via intent
        clearSubtitles()
        // Update episode list from intent
        val intentEpisodes = intent.getSerializableExtra("episode_list") as? ArrayList<com.radio.app.models.Episode>
        if (!intentEpisodes.isNullOrEmpty()) {
            episodeList = intentEpisodes
            android.util.Log.d("PlayerActivity", "onNewIntent: updated episodeList from intent, size=${episodeList.size}")
        }
        val intentIndex = intent.getIntExtra("episode_index", -1)
        if (intentIndex >= 0 && intentIndex < episodeList.size) {
            currentEpisodeIndex = intentIndex
        } else {
            val idx = episodeList.indexOfFirst { it.id == newEpisode.id }
            if (idx >= 0) currentEpisodeIndex = idx
        }
        writeJitterLog("onNewIntent: updated currentEpisode to ${newEpisode.title}")
        // Check if same episode is already playing
        if (serviceBound && playbackService != null) {
            val sameEpisode = playbackService?.isSameEpisodePlaying(newEpisode.audioUrl) ?: false
            if (sameEpisode) {
                writeJitterLog("onNewIntent: same episode already playing, skip restart")
                // Issue 10 Fix 2: subtitles were cleared above; since this is the SAME
                // episode (not a switch), reload this episode's own subtitles/segments
                // so they are not lost.
                restoreBackgroundResults()
            } else {
                val savedPos = getSavedPositionForEpisode(this, newEpisode.id)
                playbackService?.playEpisode(newEpisode, false, savedPos)
                writeJitterLog("onNewIntent: starting playback for ${newEpisode.title}")
            }
        } else {
            // Service not bound yet - episode will be played when service connects
            writeJitterLog("onNewIntent: service not bound, will play when connected: ${newEpisode.title}")
        }
        updateUI()
    }

    override fun onPause() {
        super.onPause()
        writeJitterLog("onPause")
        // Feature B: stop position update when activity is not visible
        positionUpdateHandler.removeCallbacks(positionUpdateRunnable)
        // Issue 1: Cache playback position to prevent seekbar rewind on resume
        if (serviceBound && playbackService != null) {
            val pos = playbackService?.getCurrentPosition() ?: 0L
            val dur = playbackService?.getDuration() ?: 0L
            getSharedPreferences("player_position_cache", MODE_PRIVATE).edit()
                .putLong("cached_position", pos)
                .putLong("cached_duration", dur)
                .putString("cached_episode_id", currentEpisode?.id ?: "")
                .apply()
            writeJitterLog("onPause: cached position=$pos, duration=$dur, episodeId=${currentEpisode?.id}")
        }
    }

    override fun onStop() {
        super.onStop()
        writeJitterLog("onStop: isFinishing=$isFinishing")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        writeJitterLog("onSaveInstanceState: called")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Issue 1 (partial) Fix 5: isFinishing=false means the system killed the Activity
        // (memory pressure / config change); isFinishing=true means the app/user finished it.
        writeJitterLog("onDestroy: isFinishing=$isFinishing")
        setPlaybackInProgress(this, null)
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(episodeActionReceiver)
        } catch (_: Exception) {}
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(episodeChangedReceiver)
        } catch (_: Exception) {}
        cacheProgressRunnable?.let { cacheProgressHandler?.removeCallbacks(it) }
        // Feature B: stop position update
        positionUpdateHandler.removeCallbacks(positionUpdateRunnable)
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

    // Feature B: update highlight for both subtitle and segment lists based on current playback position
    private fun updateCurrentPositionHighlight() {
        if (_binding == null) return
        val pos = currentPlaybackPositionMs

        // Update subtitle highlight - only if index changed
        if (subtitleTranscripts.isNotEmpty()) {
            val subtitleIdx = findClosestTranscriptIndex(pos)
            if (subtitleIdx != lastSubtitleHighlightIdx) {
                lastSubtitleHighlightIdx = subtitleIdx
                subtitleAdapter?.setCurrentHighlightIndex(subtitleIdx)
                if (subtitleIdx >= 0) {
                    binding.recyclerSubtitles.post {
                        (binding.recyclerSubtitles.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(subtitleIdx, 0)
                    }
                }
            }
        }

        // Update segment highlight - only if index changed
        if (voiceSegments.isNotEmpty()) {
            val segIdx = findClosestSegmentIndex(pos)
            if (segIdx != lastSegmentHighlightIdx) {
                lastSegmentHighlightIdx = segIdx
                segmentAdapter?.setCurrentSegmentIndex(segIdx)
                if (segIdx >= 0) {
                    binding.recyclerSegments.post {
                        (binding.recyclerSegments.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(segIdx, 0)
                    }
                }
            }
        }
    }

    private fun findClosestTranscriptIndex(positionMs: Long): Int {
        // First, find transcript that contains the position
        for (i in subtitleTranscripts.indices) {
            val t = subtitleTranscripts[i]
            if (t.startTime <= positionMs && (t.endTime > positionMs || t.endTime == 0L)) {
                return i
            }
        }
        // Fallback: find closest start time
        var closestIdx = -1
        var closestDiff = Long.MAX_VALUE
        for (i in subtitleTranscripts.indices) {
            val t = subtitleTranscripts[i]
            val diff = kotlin.math.abs(t.startTime - positionMs)
            if (diff < closestDiff) {
                closestDiff = diff
                closestIdx = i
            }
        }
        return closestIdx
    }

    private fun findClosestSegmentIndex(positionMs: Long): Int {
        // First, find segment that contains the position
        for (i in voiceSegments.indices) {
            val s = voiceSegments[i]
            if (s.start <= positionMs && (s.end > positionMs || s.end == 0L)) {
                return i
            }
        }
        // Fallback: find closest start time
        var closestIdx = -1
        var closestDiff = Long.MAX_VALUE
        for (i in voiceSegments.indices) {
            val s = voiceSegments[i]
            val diff = kotlin.math.abs(s.start - positionMs)
            if (diff < closestDiff) {
                closestDiff = diff
                closestIdx = i
            }
        }
        return closestIdx
    }

    // Feature A: Subtitle entry adapter for RecyclerView
    inner class SubtitleEntryAdapter : RecyclerView.Adapter<SubtitleEntryAdapter.ViewHolder>() {

        private var transcripts: List<Transcript> = emptyList()
        private var highlightedIndex: Int = -1

        fun setTranscripts(transcripts: List<Transcript>) {
            // Issue 10 Fix 3: replace the list (do not append) and keep a defensive
            // copy so external mutations cannot affect the adapter's data set.
            this.transcripts = transcripts.toList()
            highlightedIndex = -1
            lastSubtitleHighlightIdx = -1
            notifyDataSetChanged()
        }

        fun setCurrentHighlightIndex(index: Int) {
            val old = this.highlightedIndex
            this.highlightedIndex = index
            if (old >= 0 && old < transcripts.size) notifyItemChanged(old)
            if (index >= 0 && index < transcripts.size) notifyItemChanged(index)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(
                android.R.layout.simple_list_item_2, parent, false
            )
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val transcript = transcripts[position]
            holder.tvTimestamp.text = formatTimeMs(transcript.startTime)
            holder.tvText.text = transcript.text ?: ""

            val ctx = holder.itemView.context
            if (position == highlightedIndex) {
                holder.itemView.setBackgroundColor(ContextCompat.getColor(ctx, android.R.color.holo_blue_light))
            } else {
                holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            }

            // Feature C: click to seek
            holder.itemView.setOnClickListener {
                playbackService?.seekTo(transcript.startTime)
            }
        }

        override fun getItemCount(): Int = transcripts.size

        private fun formatTimeMs(ms: Long): String {
            val totalSeconds = (ms / 1000).toInt()
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return String.format("%02d:%02d:%02d", hours, minutes, seconds)
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTimestamp: TextView = view.findViewById(android.R.id.text1)
            val tvText: TextView = view.findViewById(android.R.id.text2)
        }
    }
}