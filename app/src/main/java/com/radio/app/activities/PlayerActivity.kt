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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import com.radio.app.whisper.MnnLlmBridge
import java.io.File
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
    private var pendingSeekMs: Long = -1L  // [v2.1.5] For search-to-seek
    private var isFreshStart = false // true if user explicitly clicked an episode from the list
    private var pendingAiTaskType: String? = null
    private var isActivityRecreated = false // true if system is recreating this activity (config change, etc.)
    private var freshLaunchTs: Long = 0 // timestamp from intent, used to detect real fresh launches

    // Feature B: Real-time position tracking
    private var currentPlaybackPositionMs: Long = 0
    // [v2.0.62] Issue 1 Fix: Service is now the authoritative position source (PowerAmp approach).
    // The service never reports 0 during seeking/buffering, so UI just displays what service reports.
    // We keep a simple UI-level monotonic guard as defense-in-depth.
    private var lastDisplayedPositionMs = 0L
    // [v2.0.71] Issue 1 Fix: Track episode ID for jitter guard. When episode changes,
    // backward jumps are legitimate and should NOT be blocked.
    private var lastJitterEpisodeId: String? = null
    private var isUserSeeking = false
    // [v2.0.72] Issue 1 Fix: Post-sync stabilization. After onResume/onServiceConnected syncs to
    // service position, ExoPlayer may deliver a series of decreasing positions as it re-buffers.
    // [v2.0.76] Issue 1 Fix: Jitter guard stabilization.
    // During stabilization, ALL backward jumps are HELD regardless of size or consecutive count.
    // After stabilization, backward jumps >=5min (300s) are accepted (genuine seek/episode switch).
    // Position=0 is NEVER accepted as backward jump (player reset/buffering artifact).
    private var jitterSyncTimeMs = 0L
    private var jitterSyncBaseline = 0L
    private val JITTER_STABILIZE_MS_DEFAULT = 5000L  // 5s for cold start / activity recreate
    private var jitterStabilizeMs = JITTER_STABILIZE_MS_DEFAULT  // dynamic duration, set in onResume
    // Count consecutive backward jumps to detect player reset
    private var consecutiveBackwardJumps = 0
    // [v2.0.76] Track when we last intentionally paused to prevent isPlaying race condition
    private var lastPauseIntentTimeMs = 0L
    // [v2.4.16] Track onResume timestamp to skip stale onPositionChanged callbacks
    private var lastOnResumeTimestampMs = 0L
    private var subtitleTranscripts: List<Transcript> = emptyList()
    private var subtitleAdapter: SubtitleEntryAdapter? = null
    private var lastSubtitleHighlightIdx = -1
    // [v2.4.9] Subtitle scroll pause: when user manually scrolls, pause auto-jump for N seconds
    private var lastUserScrollTimeMs: Long = 0L
    private var isSubtitleScrollPaused: Boolean = false
    private var subtitleScrollPauseDurationMs: Long = 10000L  // default 10s, loaded from settings
    // [跨进程] 服务运行在 ":subtitle" 进程，SubtitleCallback 回调对象无法跨进程传递，
    // 改用广播回传结果。这里维护一份通过广播累积的字幕列表（与数据库互为校验，onResume 时从 DB 同步）。
    private val subtitleBroadcastList = mutableListOf<Transcript>()
    // [v2.0.67] Issue 6: Keep the model name reported by the subtitle service for display.
    private var lastReportedModelName: String = ""
    // [跨进程] 字幕广播接收器的注册状态（防止重复注册 / 重复注销导致崩溃）
    private var subtitleReceiverRegistered = false
    // [跨进程] 接收 SubtitleGeneratorService 发出的字幕广播：在字幕进程崩溃后，主进程（播放）仍可收到
    // 已生成的字幕结果。回调机制保留作为同进程回退。
    private val subtitleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val episodeId = intent.getStringExtra("episodeId") ?: return
            // 仅处理当前节目，避免切集后旧任务的广播污染当前界面
            val currentId = currentEpisode?.id
            if (currentId != null && episodeId != currentId) return
            when (intent.action) {
                "com.radio.app.SUBTITLE_GENERATED" -> {
                    val text = intent.getStringExtra("text") ?: ""
                    val startMs = intent.getLongExtra("startMs", 0)
                    val endMs = intent.getLongExtra("endMs", 0)
                    val modelName = intent.getStringExtra("modelName") ?: ""
                    if (modelName.isNotBlank()) lastReportedModelName = modelName
                    val transcript = Transcript(text = text, segmentStart = startMs, segmentEnd = endMs).apply {
                        this.episodeId = episodeId
                    }
                    // 去重：同进程回退时回调与广播可能同时触发同一字幕
                    if (subtitleBroadcastList.none { it.segmentStart == startMs && it.segmentEnd == endMs && it.text == text }) {
                        subtitleBroadcastList.add(transcript)
                    }
                    runOnUiThread {
                        if (_binding == null) return@runOnUiThread
                        subtitleTranscripts = subtitleBroadcastList.toList()
                        subtitleAdapter?.setTranscripts(subtitleTranscripts)
                        binding.subtitleView.visibility = View.GONE
                        binding.tvSubtitleTitle.visibility = View.VISIBLE
                        binding.recyclerSubtitles.visibility = View.VISIBLE
                        // [v2.0.67] Issue 6: Show the actual model name used by the service
                        val modelLabel = formatModelName(modelName)
                        binding.tvSubtitleStatus.text = "字幕生成中 (${subtitleTranscripts.size}条) · $modelLabel"
                    }
                }
                "com.radio.app.SUBTITLE_PROGRESS" -> {
                    val progress = intent.getIntExtra("progress", 0)
                    runOnUiThread {
                        if (_binding == null) return@runOnUiThread
                        binding.progressSubtitle.progress = progress
                        val modelLabel = formatModelName(lastReportedModelName)
                        binding.tvSubtitleStatus.text = "字幕生成: $progress% · $modelLabel"
                    }
                }
                "com.radio.app.SUBTITLE_ERROR" -> {
                    val message = intent.getStringExtra("message") ?: "未知错误"
                    runOnUiThread {
                        if (_binding == null) return@runOnUiThread
                        finishAiProcessing("subtitle")
                        binding.tvSubtitleStatus.text = "字幕生成失败: $message"
                        binding.tvSubtitleStatus.visibility = View.VISIBLE
                        Toast.makeText(this@PlayerActivity, message, Toast.LENGTH_SHORT).show()
                        android.util.Log.e("PlayerActivity", "Subtitle broadcast error: $message")
                    }
                }
                // [v2.0.69] Issue 6: Receive model info at the start of subtitle generation
                "com.radio.app.SUBTITLE_MODEL_INFO" -> {
                    val modelName = intent.getStringExtra("modelName") ?: ""
                    val engineType = intent.getStringExtra("engineType") ?: ""
                    if (modelName.isNotBlank()) lastReportedModelName = modelName
                    runOnUiThread {
                        if (_binding == null) return@runOnUiThread
                        val modelLabel = formatModelName(modelName)
                        binding.tvSubtitleStatus.text = "字幕生成中 · $modelLabel"
                        binding.tvSubtitleStatus.visibility = View.VISIBLE
                        android.util.Log.d("PlayerActivity", "[v2.0.69] Subtitle model info: $modelName ($engineType) -> $modelLabel")
                    }
                }
                "com.radio.app.SUBTITLE_COMPLETE" -> {
                    runOnUiThread {
                        if (_binding == null) return@runOnUiThread
                        finishAiProcessing("subtitle")
                        // [v2.4.3] Also check DB in case broadcast list is empty (cross-process timing)
                        val episode = currentEpisode
                        if (subtitleBroadcastList.isNotEmpty()) {
                            subtitleTranscripts = subtitleBroadcastList.toList()
                        } else if (episode != null) {
                            val dbHelper = RadioDatabaseHelper.getInstance(this@PlayerActivity)
                            subtitleTranscripts = dbHelper.getTranscripts(episode.id)
                            subtitleBroadcastList.clear()
                            subtitleBroadcastList.addAll(subtitleTranscripts)
                        }
                        subtitleAdapter?.setTranscripts(subtitleTranscripts)
                        binding.subtitleView.visibility = View.GONE
                        // [v2.4.12] Show subtitle title with engine info
                        val engineName = intent.getStringExtra("engineName") ?: ""
                        if (engineName.isNotBlank()) {
                            binding.tvSubtitleTitle.text = "字幕  生成所用引擎：$engineName"
                        } else if (episode != null) {
                            val dbEngine = RadioDatabaseHelper.getInstance(this@PlayerActivity).getTranscriptEngine(episode.id)
                            binding.tvSubtitleTitle.text = if (!dbEngine.isNullOrBlank())
                                "字幕  生成所用引擎：$dbEngine" else "字幕"
                        } else {
                            binding.tvSubtitleTitle.text = "字幕"
                        }
                        binding.tvSubtitleTitle.visibility = View.VISIBLE
                        binding.recyclerSubtitles.visibility = View.VISIBLE
                        Toast.makeText(this@PlayerActivity, "字幕生成完成，共${subtitleTranscripts.size}条", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    private var lastSegmentHighlightIdx = -1
    private val positionUpdateHandler = Handler(Looper.getMainLooper())
    private val positionUpdateRunnable = object : Runnable {
        override fun run() {
            // [v2.0.62] Service is authoritative for position; just update segment highlights
            val pos = playbackService?.getCurrentPosition() ?: 0L
            val dur = playbackService?.getDuration() ?: 0L
            if (pos > 0 && dur > 0) {
                updateCurrentPositionHighlight()
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
                // [v2.0.93] Fix: Update currentEpisode before calling updateUI(), otherwise
                // updateUI() overwrites tvStationName with old currentEpisode.title.
                val ep = playbackService?.getCurrentEpisode()
                if (ep != null) {
                    currentEpisode = ep
                    val newIdx = episodeList.indexOfFirst { it.id == ep.id }
                    if (newIdx >= 0) currentEpisodeIndex = newIdx
                    clearSubtitles()
                    voiceSegments = generateSimulatedSegments()
                    if (voiceSegments.isNotEmpty()) updateSegmentsUI()
                }
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
            // [v2.0.62] Issue 1 Fix: Service is now authoritative for position.
            // It never reports 0 during seeking/buffering, so we just sync UI to service position.
            val svcPos = playbackService?.getCurrentPosition() ?: 0L
            val svcDur = playbackService?.getDuration() ?: 0L
            val syncTime = System.currentTimeMillis()
            if (svcPos > 0) {
                lastDisplayedPositionMs = svcPos
                jitterSyncTimeMs = syncTime
                jitterStabilizeMs = JITTER_STABILIZE_MS_DEFAULT
                jitterSyncBaseline = svcPos
                consecutiveBackwardJumps = 0
                if (svcDur > 0) {
                    binding.tvCurrentTime.text = "${formatTime(svcPos.toInt())} / ${formatTime(svcDur.toInt())}"
                    binding.seekBar.max = svcDur.toInt()
                    binding.seekBar.progress = svcPos.toInt()
                } else {
                    binding.tvCurrentTime.text = "${formatTime(svcPos.toInt())} / --:--"
                    binding.seekBar.progress = svcPos.toInt()
                }
                writeJitterLog("[v2.0.62] onServiceConnected: synced UI to service position=$svcPos, dur=$svcDur")
            } else {
                // Service reports 0 - either fresh start or just killed. Pre-set UI to saved position.
                val savedPos = getSavedPositionForEpisode(this@PlayerActivity, currentEpisode?.id ?: "")
                if (savedPos > 0) {
                    lastDisplayedPositionMs = savedPos
                    binding.tvCurrentTime.text = "${formatTime(savedPos.toInt())} / --:--"
                    binding.seekBar.progress = savedPos.toInt()
                    binding.tvLiveIndicator.text = "恢复中..."
                    writeJitterLog("[v2.0.62] onServiceConnected: service pos=0, pre-set UI to savedPos=$savedPos")
                } else {
                    lastDisplayedPositionMs = 0
                }
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
            // [v2.0.70] Issue 1 Fix: Also check if the service's player is at a reasonable position.
            // If the service reports position near 0 but saved position is large, the player was
            // reset (e.g., after an error or system kill) and needs playEpisode to restore position.
            if (sameEpisode && svcStarted) {
                val svcPos = playbackService?.getCurrentPosition() ?: 0L
                val savedPosForCheck = getSavedPositionForEpisode(this@PlayerActivity, currentEpisode?.id ?: "")
                val playerReset = savedPosForCheck > 30000 && svcPos < 5000
                if (playerReset) {
                    writeJitterLog("JITTER-GUARD: same episode but svcPos=$svcPos << savedPos=$savedPosForCheck, player was reset, falling through to playEpisode for position restore")
                    // Don't return - fall through to playEpisode to restore position
                } else {
                    setPlaybackInProgress(this@PlayerActivity, null)
                    val msg = "JITTER-GUARD: same episode playing, only update UI (svcPos=$svcPos, savedPos=$savedPosForCheck)"
                    android.util.Log.d("PlayerActivity", msg)
                    writeJitterLog(msg)
                    updateUI()
                    startCacheProgressUpdater()
                    restoreBackgroundResults()
                    setupPreCacheList()
                    return@onServiceConnected
                }
            }

            // [v2.0.64] Issue 1 Fix: Also check by TITLE. Radio audio URLs may change between
            // sessions (different CDN, tokens, timestamps) but the episode is the same.
            // If the service is playing an episode with the same title, don't restart playback.
            // [v2.0.71] Issue 10 Fix: ALSO check episode ID. Different dates with the same time slot
            // have the same title but different IDs. Without checking ID, switching between
            // different dates with the same time slot would be blocked by this jitter guard.
            if (svcStarted && !sameEpisode && currentEpisode != null) {
                val svcEpisode = playbackService?.getCurrentEpisode()
                if (svcEpisode != null && svcEpisode.title == currentEpisode!!.title
                    && svcEpisode.id == currentEpisode!!.id) {
                    // [v2.0.71] Only skip restart if BOTH title AND ID match
                    setPlaybackInProgress(this@PlayerActivity, null)
                    val svcPos = playbackService?.getCurrentPosition() ?: 0L
                    val msg = "[v2.0.64] JITTER-GUARD: same episode title+id '${svcEpisode.title}'/'${svcEpisode.id}' (URL differs), syncing to service position=$svcPos without restart"
                    android.util.Log.d("PlayerActivity", msg)
                    writeJitterLog(msg)
                    lastDisplayedPositionMs = svcPos
                    updateUI()
                    startCacheProgressUpdater()
                    restoreBackgroundResults()
                    setupPreCacheList()
                    return@onServiceConnected
                }
            }

            // JITTER-GUARD: Service正在播放不同的episode（如自动切集后），同步Activity到Service的当前状态
            // Service是播放状态的唯一真相源，不要强制切回Activity中过时的episode
            // [v2.0.43] Issue 5 Fix: 当 isFreshStart=true（用户主动从节目列表选择了新节目）时，
            // 绝不同步到service的旧节目。用户的选择优先于service的当前状态。
            // 之前的bug：即使用户选择了"下班路上"，JITTER-GUARD也会强制同步回service正在播放的"旅行大玩家"，
            // 导致"无论点击哪个节目，当前播放的节目都不变"。
            // [v2.2.1] 当 pendingSeekMs > 0（来自搜索跳转）时，跳过JITTER-GUARD，
            // 强制切换到搜索结果中的节目。
            if (svcStarted && !sameEpisode && !isFreshStart && pendingSeekMs <= 0) {
                // Try to find the service's current episode by URL
                val svcMatch = episodeList.firstOrNull { it.audioUrl == svcUrl }
                if (svcMatch != null) {
                    val syncMsg = "JITTER-GUARD: service playing different episode (svc=${svcMatch.title}, url matched), syncing Activity to service instead of restarting (isFreshStart=$isFreshStart)"
                    android.util.Log.d("PlayerActivity", syncMsg)
                    writeJitterLog(syncMsg)
                    currentEpisode = svcMatch
                    clearSubtitles()
                    currentEpisodeIndex = episodeList.indexOf(svcMatch).coerceAtLeast(0)
                    updateUI()
                } else {
                    val syncMsg = "JITTER-GUARD: service playing unknown episode (svcUrl=$svcUrl), updating UI without restarting (isFreshStart=$isFreshStart)"
                    android.util.Log.d("PlayerActivity", syncMsg)
                    writeJitterLog(syncMsg)
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
            } else if (svcStarted && !sameEpisode && isFreshStart) {
                // [v2.0.43] Issue 5 Fix: 用户主动选择了新节目，service正在播放旧节目。
                // 必须播放用户选择的节目，而不是同步到service的旧节目。
                writeJitterLog("[v2.0.43] JITTER-GUARD BYPASSED: isFreshStart=true, user selected '${currentEpisode?.title}' but service playing '${playbackService?.getCurrentEpisode()?.title}'. Playing user's selection.")
                // 不return，继续执行下面的playEpisode逻辑
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
            // [v2.0.45] Issue 1 Fix: When maxDuration=0 (service killed, episode from memory has no duration),
            // treat savedPos as valid if > 0. Can't validate against unknown duration.
            // Previously: isValidSavedPos=false → UI not pre-set → progress bar shows 0 then jumps → FLICKER
            var isValidSavedPos = if (maxDuration > 0) {
                savedPos > 0 && savedPos <= maxDuration
            } else {
                savedPos > 0  // Can't validate, but position exists
            }

            // Issue 1 Fix [v2.0.42]: 当服务被杀死 (!svcStarted) 时，保留实际 savedPos 传给 playEpisode，
            // 让服务重新初始化时 seek 到正确位置。onResume 已经处理了 UI 防抖（只在服务无位置时显示缓存位置）。
            // 之前设为 0 会导致播放从头开始（进度回退），设为 -1 也一样。
            if (!svcStarted) {
                writeJitterLog("[v2.0.42] Service was killed (!svcStarted), keeping savedPos=${savedPos}ms (valid=$isValidSavedPos) to pass to playEpisode for position restore")
            }

            // [v2.0.62] Issue 1 Fix: Pre-set UI to savedPos when service is killed
            // Service will report authoritative position during buffering (never 0)
            if (!svcStarted && currentEpisode != null && isValidSavedPos) {
                binding.tvCurrentTime.text = "${formatTime(savedPos.toInt())} / --:--"
                binding.seekBar.progress = savedPos.toInt()
                binding.tvLiveIndicator.text = "恢复中..."
                lastDisplayedPositionMs = savedPos
                writeJitterLog("[v2.0.62] Pre-setting UI to saved position: ${savedPos}ms (svcStarted=$svcStarted, isFreshStart=$isFreshStart)")
            } else if (!isFreshStart && currentEpisode != null && savedPos > 0 && !isValidSavedPos) {
                writeJitterLog("Skipping invalid saved position: ${savedPos}ms exceeds maxDuration=$maxDuration, episode=${currentEpisode?.title}")
            }

            val msg = if (sameEpisode && !svcStarted) {
                "Same episode, service was killed, restoring from saved position: ${savedPos}ms (valid=$isValidSavedPos)"
            } else {
                "[v2.0.43] [EPISODE] Different episode, starting new playback: ${currentEpisode?.title} (was svc=${playbackService?.getCurrentEpisode()?.title}), isFreshStart=$isFreshStart"
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
                            // [v2.1.9] If pendingSeekMs from search, use it as start position
                            val startPos = if (pendingSeekMs > 0) pendingSeekMs else -1L
                            writeJitterLog("onServiceConnected: isFreshStart, playEpisode with startPos=$startPos (pendingSeekMs=$pendingSeekMs)")
                            playbackService?.playEpisode(episode, false, startPos)
                            pendingSeekMs = -1L  // [v2.1.9] Clear after use
                        } else {
                            // [v2.2.0] If pendingSeekMs from search, use it instead of savedPosition
                            var savedPosition = if (pendingSeekMs > 0) {
                                writeJitterLog("onServiceConnected: using pendingSeekMs=$pendingSeekMs instead of savedPosition")
                                pendingSeekMs
                            } else {
                                playbackService?.getSavedPositionForEpisode(episode) ?: -1L
                            }
                            // [v2.0.76] Issue 1 Fix: When restoring from killed service, prefer Activity's cached
                            // position if it's valid and significantly different from service's saved position.
                            val activityCachedPos = getSharedPreferences("player_position_cache", MODE_PRIVATE).getLong("cached_position", 0L)
                            val activityCachedEpId = getSharedPreferences("player_position_cache", MODE_PRIVATE).getString("cached_episode_id", "")
                            if (!svcStarted && activityCachedPos > 30000 && activityCachedEpId == episode.id) {
                                if (savedPosition <= 0 || kotlin.math.abs(savedPosition - activityCachedPos) > 60000) {
                                    writeJitterLog("[v2.0.76] Using Activity cached pos=$activityCachedPos instead of service savedPos=$savedPosition (svc was killed, cache is more reliable)")
                                    savedPosition = activityCachedPos
                                }
                            }
                            val epDurMs = episode.duration * if (episode.duration > 0 && episode.duration < 100000) 1000 else 1
                            if (savedPosition > 0 && epDurMs > 0 && savedPosition > epDurMs) {
                                writeJitterLog("Service killed restore: savedPosition=${savedPosition}ms exceeds episode duration=${epDurMs}ms, clamping to 0")
                                savedPosition = 0L
                            }
                            val msg = "[v2.0.76] restoring position: savedPos=${savedPosition}ms, epDur=${epDurMs}ms, svcStarted=$svcStarted, activityCached=$activityCachedPos"
                            android.util.Log.d("PlayerActivity", msg)
                            writeJitterLog(msg)
                            if (savedPosition > 0) {
                                lastDisplayedPositionMs = savedPosition
                                jitterSyncTimeMs = System.currentTimeMillis()
                                jitterSyncBaseline = savedPosition
                                consecutiveBackwardJumps = 0
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

    // Issue 6 & 8: episode list operations (highlight + click-to-switch) now written to
    // the jitter log so they are included in the user's log export.
    private fun writeEpisodeLog(message: String) {
        // Write to jitter log so it's included in user's log export
        writeJitterLog("[EPISODE] $message")
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
                val now = System.currentTimeMillis()
                // [v2.0.72] Issue 1 Fix: When episode changes, reset jitter guard to allow
                // legitimate backward jumps (e.g., new episode starts from 0, or player
                // was killed and restored to a different position).
                val currentEpId = currentEpisode?.id
                var displayPosition = position
                if (currentEpId != null && currentEpId != lastJitterEpisodeId) {
                    // [v2.0.73] Issue 1 Fix: Don't accept position=0 on episode change (player not ready yet).
                    // Wait for a valid position (>2s) before setting baseline, otherwise UI flashes 0.
                    if (position > 2000) {
                        writeJitterLog("[v2.0.73] onPositionChanged: episode changed from $lastJitterEpisodeId to $currentEpId, resetting jitter guard (pos=$position)")
                        lastJitterEpisodeId = currentEpId
                        lastDisplayedPositionMs = position
                        consecutiveBackwardJumps = 0
                        jitterSyncTimeMs = now
                        jitterSyncBaseline = position
                    } else {
                        // [v2.4.19] Episode changed with pos=0: reset to 0 instead of HOLDING old position
                        // Old code held lastDisplayedPositionMs which caused flicker between old/new episode positions
                        writeJitterLog("[v2.4.19] onPositionChanged: episode changed to $currentEpId, pos=$position (<2s), resetting to 0 (was lastPos=$lastDisplayedPositionMs)")
                        lastJitterEpisodeId = currentEpId
                        lastDisplayedPositionMs = 0
                        consecutiveBackwardJumps = 0
                        jitterSyncTimeMs = now
                        jitterSyncBaseline = 0
                        // Skip seekBar update — wait for valid position
                        return@runOnUiThread
                    }
                }

                // [v2.0.76] Issue 1 Fix: Jitter guard - multiple layers of protection against backward jumps:
                // 1. During stabilization (5s after onResume/episode change): NEVER accept ANY backward jump
                // 2. Position=0 or near-0 (<5s) is NEVER accepted as backward if last position >30s (player reset/buffering)
                // 3. After stabilization: only accept backward jumps >=300s (genuine episode switch/seek)
                // 4. After user presses pause, ignore isPlaying race conditions for 2s
                val inStabilization = (now - jitterSyncTimeMs) < jitterStabilizeMs
                val recentPause = (now - lastPauseIntentTimeMs) < 2000L
                val isPositionNearZero = position < 5000L && lastDisplayedPositionMs > 30000L

                // [v2.4.16] Fix: If this is a stale callback enqueued before onResume, skip it
                // to prevent progress from flickering back to old position
                if (lastOnResumeTimestampMs > 0 && now - lastOnResumeTimestampMs < 500L
                    && position < lastDisplayedPositionMs - 2000 && !isUserSeeking) {
                    writeJitterLog("[v2.4.16] onPositionChanged: skipping stale callback pos=$position < lastPos=$lastDisplayedPositionMs (within 500ms of onResume)")
                    return@runOnUiThread
                }

                if (position < lastDisplayedPositionMs - 2000 && !isUserSeeking) {
                    val backwardDelta = lastDisplayedPositionMs - position
                    consecutiveBackwardJumps++

                    // [v2.0.76] Never accept position=0 as legitimate backward jump (player reset artifact)
                    // Never accept any backward jump during stabilization regardless of size/consec count
                    if (isPositionNearZero) {
                        displayPosition = lastDisplayedPositionMs
                        if (consecutiveBackwardJumps == 1 || consecutiveBackwardJumps % 10 == 0) {
                            writeJitterLog("[v2.0.76] ZERO-BLOCK: keeping $lastDisplayedPositionMs (ignoring near-zero pos=$position, delta=${backwardDelta}ms, consec=$consecutiveBackwardJumps)")
                        }
                    // [v2.0.86] During stabilization: HOLD all backward jumps (v2.0.81 approach).
                    // v2.0.84 added STAB-ACCEPT for consec>3, but with the new skip protection
                    // (0 skips execute during blackout+breaker), no backward jumps should be
                    // reported during stabilization anyway. If any leak through, HOLD them.
                    } else if (inStabilization) {
                        displayPosition = lastDisplayedPositionMs
                        if (consecutiveBackwardJumps == 1 || consecutiveBackwardJumps % 5 == 0) {
                            writeJitterLog("[v2.0.86] STAB-HOLD: keeping $lastDisplayedPositionMs (ignoring backward to $position, delta=${backwardDelta}ms, consec=$consecutiveBackwardJumps, stabRemaining=${jitterStabilizeMs - (now - jitterSyncTimeMs)}ms)")
                        }
                    } else if (backwardDelta >= 300_000L && !recentPause) {
                        // Genuine large backward jump: episode switch or user seek to earlier position
                        writeJitterLog("[v2.0.76] LEGIT-BACK: accepting $lastDisplayedPositionMs->$position (delta=${backwardDelta}ms, consec=$consecutiveBackwardJumps)")
                        lastDisplayedPositionMs = position
                        displayPosition = position
                        jitterSyncTimeMs = now
                        jitterSyncBaseline = position
                        consecutiveBackwardJumps = 0
                    } else {
                        displayPosition = lastDisplayedPositionMs
                        if (consecutiveBackwardJumps == 1 || consecutiveBackwardJumps % 10 == 0) {
                            writeJitterLog("[v2.0.76] HOLD: keeping $lastDisplayedPositionMs (ignoring backward to $position, delta=${backwardDelta}ms, consec=$consecutiveBackwardJumps, recentPause=$recentPause)")
                        }
                    }
                } else if (position >= lastDisplayedPositionMs - 2000 || isUserSeeking) {
                    if (position > lastDisplayedPositionMs) {
                        if (consecutiveBackwardJumps > 0) {
                            writeJitterLog("[v2.0.76] FORWARD after $consecutiveBackwardJumps backward jumps: $lastDisplayedPositionMs->$position")
                        }
                        consecutiveBackwardJumps = 0
                    }
                    lastDisplayedPositionMs = position
                    displayPosition = position
                }

                val pos = displayPosition.toInt()
                val dur = duration.toInt()
                // Feature B: store current playback position
                currentPlaybackPositionMs = displayPosition
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
                    binding.tvCurrentTime.text = "播放: ${formatTime(pos)}"
                }
            }
        }

        override fun onBufferUpdate(percent: Int) {
            runOnUiThread {
                if (_binding == null) return@runOnUiThread
                if (hasError) return@runOnUiThread
                binding.tvAiProgress.text = "播放: ${percent}%"
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
                // [v2.0.62] Reset UI position tracking for new episode
                lastDisplayedPositionMs = 0
                writeJitterLog("[v2.0.62] onEpisodeChanged: reset lastDisplayedPositionMs=0 for new episode")
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

        // [v2.1.6] Check for seek_position_ms from search
        val seekMsOnCreate = intent.getLongExtra("seek_position_ms", -1L)
        if (seekMsOnCreate > 0) {
            pendingSeekMs = seekMsOnCreate
            writeJitterLog("onCreate: pending seek to $seekMsOnCreate ms from search")
        }

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
            binding.tvCacheUrl.text = "本地缓存: ${com.radio.app.RadioApplication.getEpisodesCacheDir(this).absolutePath}/$cacheFileName"
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

        // [v2.3.1] Fix: Always pre-fill saved position to avoid "00:00 → real position" flicker
        // when returning to app. The previous logic only pre-filled for freshLaunchTs>0, leaving
        // system-recreated activities showing "00:00" for hundreds of milliseconds until
        // onServiceConnected corrected it, causing visible progress bar jump.
        // If saved position is stale, onServiceConnected will immediately correct it.
        var preFilled = false
        if (currentEpisode != null) {
            val episodeKey = "${currentEpisode!!.stationId}::${currentEpisode!!.title}"
            val savedPos = getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(episodeKey, -1L)
            if (savedPos > 0) {
                binding.tvCurrentTime.text = "${formatTime(savedPos.toInt())} / --:--"
                binding.seekBar.progress = savedPos.toInt()
                binding.tvLiveIndicator.text = "连接中..."
                lastDisplayedPositionMs = savedPos
                preFilled = true
                android.util.Log.d("PlayerActivity", "initViews: [v2.3.1] pre-filled saved position ${savedPos}ms to avoid flicker")
            }
        }
        if (!preFilled) {
            binding.tvCurrentTime.text = "00:00 / 00:00"
            if (currentEpisode != null) {
                binding.tvLiveIndicator.text = "连接中..."
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

        // [v2.4.9] Detect manual scroll on subtitle RecyclerView to pause auto-jump
        // Load pause duration from settings
        subtitleScrollPauseDurationMs = AppSettings.getInstance(this).subtitleScrollPauseSeconds * 1000L
        binding.recyclerSubtitles.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                // Only treat as user scroll if there's actual movement
                if (dx != 0 || dy != 0) {
                    lastUserScrollTimeMs = System.currentTimeMillis()
                    isSubtitleScrollPaused = true
                }
            }
        })

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
                if (service.isPlaying()) {
                    lastPauseIntentTimeMs = System.currentTimeMillis()
                    service.pause()
                } else {
                    service.play()
                }
            }
        }
        binding.btnPrevSegment.setOnClickListener {
            if (playbackService == null) {
                writeEpisodeLog("btnPrevSegment: playbackService is null, cannot jump")
                Toast.makeText(this, "播放服务未连接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            writeEpisodeLog("btnPrevSegment: calling jumpToPrevSegment")
            playbackService?.jumpToPrevSegment()
        }
        binding.btnNextSegment.setOnClickListener {
            if (playbackService == null) {
                writeEpisodeLog("btnNextSegment: playbackService is null, cannot jump")
                Toast.makeText(this, "播放服务未连接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            writeEpisodeLog("btnNextSegment: calling jumpToNextSegment")
            playbackService?.jumpToNextSegment()
        }
        // v2.4.30: Add isUserSeeking protection around skip operations to prevent jitter guard from fighting
        binding.btnSkipForward.setOnClickListener {
            if (playbackService == null) {
                writeJitterLog("[v2.4.30] btnSkipForward: service not bound, ignoring")
                Toast.makeText(this, "播放服务未就绪", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isUserSeeking = true
            writeJitterLog("[v2.4.30] btnSkipForward: isUserSeeking=true, calling skipForward")
            playbackService?.skipForward()
            // Reset isUserSeeking after 1.5s to allow jitter guard to resume
            mainHandler.postDelayed({ isUserSeeking = false }, 1500L)
        }
        binding.btnSkipBackward.setOnClickListener {
            if (playbackService == null) {
                writeJitterLog("[v2.4.30] btnSkipBackward: service not bound, ignoring")
                Toast.makeText(this, "播放服务未就绪", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isUserSeeking = true
            writeJitterLog("[v2.4.30] btnSkipBackward: isUserSeeking=true, calling skipBackward")
            playbackService?.skipBackward()
            mainHandler.postDelayed({ isUserSeeking = false }, 1500L)
        }
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
            // [v2.2.6] DO NOT clear old subtitles before generating.
            // If generation fails or produces 0 subtitles, old subtitles are preserved.
            // Subtitles are replaced only when new ones are successfully generated (in onComplete).
            writeEpisodeLog("btnGenerateSubtitle: starting generation for ${episode.id} (old subtitles preserved until new ones complete)")
            startAiProcessing("subtitle")
            bindSubtitleService(episode, "subtitle")
        }

        binding.btnAiSegment.setOnClickListener {
            val episode = currentEpisode ?: run {
                Toast.makeText(this, "无法AI分段：无节目", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (episode.id.isBlank()) {
                Toast.makeText(this, "无法AI分段：缺少节目ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (segmentProcessing) {
                Toast.makeText(this, "正在处理中，请稍候", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // [v2.4.23] AI分段: use existing subtitles for content-based segmentation
            writeJitterLog("[v2.4.27] btnAiSegment CLICKED: episodeId=${episode.id}")
            startAiProcessing("segment")
            // [v2.4.24] Get duration on main thread BEFORE spawning background thread
            val dur = playbackService?.getDuration()?.toInt() ?: 0
            // [v2.4.27] Check which AI model to use for segmentation
            val settings = AppSettings.getInstance(this)
            val aiModel = settings.safeAiModel()
            writeJitterLog("[v2.4.27] btnAiSegment: aiModel=$aiModel")
            Thread {
                try {
                    val dbHelper = com.radio.app.database.RadioDatabaseHelper.getInstance(this)
                    val transcriptCount = dbHelper.getTranscriptCount(episode.id)
                    val isComplete = dbHelper.hasCompleteSubtitles(episode.id)
                    writeJitterLog("[v2.4.23] btnAiSegment: transcriptCount=$transcriptCount, isComplete=$isComplete")

                    if (transcriptCount < 3 || !isComplete) {
                        runOnUiThread {
                            finishAiProcessing("segment")
                            val msg = if (transcriptCount == 0) "无字幕，请先生成字幕" else "字幕不完整($transcriptCount 条)，请先完成字幕生成"
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }

                    // Generate content-based segments from subtitles
                    // [v2.4.24] Use dur captured on main thread, fallback to maxEnd from DB
                    val maxEnd = if (dur > 0) dur else dbHelper.getMaxTranscriptEndMs(episode.id).toInt()
                    writeJitterLog("[v2.4.27] btnAiSegment: dur=$dur, maxEnd=$maxEnd")

                    // [v2.4.28] When AI分段模型 = 阿里MNN-LLM, use OFFLINE MNN-LLM for intelligent classification
                    // If MNN fails, show error directly - NO fallback to keyword-based segmentation
                    var segments: List<VoiceSegment> = emptyList()
                    if (aiModel == AppSettings.AI_MODEL_MNN_LLM) {
                        val modelsDir = getExternalFilesDir("models")
                        val mnnModelDir = File(modelsDir, "mnn-llm/Qwen1.5-1.8B-Chat-MNN")
                        writeJitterLog("[v2.4.28] btnAiSegment: mnnModelDir=${mnnModelDir.absolutePath}, exists=${mnnModelDir.exists()}")

                        if (!MnnLlmBridge.isModelInstalled(mnnModelDir)) {
                            val err = MnnLlmBridge.lastError
                            writeJitterLog("[v2.4.28] btnAiSegment: MNN model not installed: $err")
                            runOnUiThread { Toast.makeText(this, "MNN模型未安装或文件不完整: $err\n请在离线引擎页面重新下载。", Toast.LENGTH_LONG).show() }
                        } else {
                            runOnUiThread { Toast.makeText(this, "正在加载MNN离线模型...", Toast.LENGTH_SHORT).show() }
                            try {
                                writeJitterLog("[v2.4.28] btnAiSegment: initializing MnnLlmBridge...")
                                val initOk = MnnLlmBridge.init(mnnModelDir)
                                writeJitterLog("[v2.4.28] btnAiSegment: MnnLlmBridge.init result=$initOk")
                                if (!initOk) {
                                    val err = MnnLlmBridge.lastError
                                    writeJitterLog("[v2.4.28] btnAiSegment: MNN init failed: $err")
                                    runOnUiThread { Toast.makeText(this, "MNN模型加载失败: $err", Toast.LENGTH_LONG).show() }
                                } else {
                                    val transcripts = dbHelper.getTranscripts(episode.id)
                                    val subtitleData = transcripts.map { Triple(it.segmentStart, it.segmentEnd, it.text ?: "") }
                                    writeJitterLog("[v2.4.30] btnAiSegment: feeding ${subtitleData.size} subtitles to MNN-LLM (batched)")

                                    runOnUiThread { Toast.makeText(this, "MNN分析中(分批处理)...", Toast.LENGTH_SHORT).show() }

                                    val results = MnnLlmBridge.classifySubtitles(subtitleData) { current, total ->
                                        val pct = if (total > 0) current * 100 / total else 0
                                        writeJitterLog("[v2.4.30] btnAiSegment: MNN progress $current/$total ($pct%)")
                                        runOnUiThread {
                                            if (_binding != null) {
                                                binding.tvAiStatus.text = "MNN分析中: $current/$total ($pct%)"
                                                binding.progressAi.progress = pct
                                            }
                                        }
                                    }
                                    writeJitterLog("[v2.4.30] btnAiSegment: MNN-LLM returned ${results?.size ?: 0} results")

                                    if (results != null && results.isNotEmpty()) {
                                        segments = results.map { r ->
                                            VoiceSegment().apply {
                                                this.start = r.start
                                                this.end = r.end
                                                this.hasVoice = r.isDry
                                                this.label = r.label
                                                this.isSimulated = false
                                            }
                                        }
                                    } else {
                                        writeJitterLog("[v2.4.28] btnAiSegment: MNN-LLM returned no results")
                                        runOnUiThread { Toast.makeText(this, "MNN分析无结果，请检查日志", Toast.LENGTH_LONG).show() }
                                    }
                                }
                                MnnLlmBridge.release()
                            } catch (e: Exception) {
                                writeJitterLog("[v2.4.28] btnAiSegment: MNN-LLM error: ${e.message}")
                                runOnUiThread { Toast.makeText(this, "MNN离线分析错误: ${e.message}", Toast.LENGTH_LONG).show() }
                                try { MnnLlmBridge.release() } catch (_: Exception) {}
                            }
                        }
                    } else {
                        // [v2.4.25] Keyword-based segmentation (default for non-MNN models)
                        if (maxEnd > 0) segments = generateContentBasedSegments(episode.id, maxEnd)
                    }
                    writeJitterLog("[v2.4.28] btnAiSegment: generated ${segments.size} segments")

                    runOnUiThread {
                        if (_binding == null) return@runOnUiThread
                        if (segments.isNotEmpty()) {
                            voiceSegments = segments
                            segmentAdapter?.setSegments(segments)
                            binding.recyclerSegments.visibility = View.VISIBLE
                            val dryCount = segments.count { it.hasVoice }
                            val waterCount = segments.size - dryCount
                            Toast.makeText(this,
                                "AI分段完成: ${segments.size}段 (干货${dryCount} 水货${waterCount})",
                                Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, "AI分段失败：无法生成分段", Toast.LENGTH_SHORT).show()
                        }
                        finishAiProcessing("segment")
                    }
                } catch (e: Exception) {
                    writeJitterLog("[v2.4.23] btnAiSegment ERROR: ${e.message}")
                    runOnUiThread {
                        finishAiProcessing("segment")
                        Toast.makeText(this, "AI分段错误: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
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
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isDragging = true; isUserSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isDragging = false
                isUserSeeking = false
                seekBar?.let {
                    val targetPos = it.progress.toLong()
                    // [v2.0.46] Update lastDisplayedPositionMs so monotonic guard accepts the seek position
                    lastDisplayedPositionMs = targetPos
                    playbackService?.seekTo(targetPos)
                }
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
                    episode,  // [v2.2.4] Pass full Episode to save metadata to DB
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
                                binding.tvSubtitleStatus.text = "字幕生成: $progress% (引擎: ${getDetailedAsrLabel()})"
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
            android.util.Log.w("PlayerActivity", "Subtitle service disconnected (process may have crashed)")
            writeJitterLog("[v2.0.59] Subtitle service disconnected (process crashed?)")
            subtitleService = null
            subtitleServiceBound = false
            // Issue 9 (partial): the subtitle service died while we may have been showing
            // progress (cancelled via notification or killed by the system). Hide any
            // progress UI immediately so it does not get stuck visible or race to 100%.
            // [v2.0.59] Don't show error here - the error broadcast will handle it.
            // But if no broadcast was received, the process was killed before sending one.
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
                    episode,  // [v2.2.4] Pass full Episode to save metadata to DB
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
                                binding.tvSubtitleStatus.text = "字幕生成: $progress% (引擎: ${getDetailedAsrLabel()})"
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
        // [v2.1.5] Execute pending seek from search result
        if (pendingSeekMs > 0 && playbackService?.isPrepared() == true) {
            writeJitterLog("updateUI: executing pending seek to $pendingSeekMs ms")
            playbackService?.seekTo(pendingSeekMs)
            pendingSeekMs = -1L
        }
        // 同步seekbar位置
        if (playbackService?.isPrepared() == true) {
            val svcPos = playbackService?.getCurrentPosition() ?: 0L
            val svcDur = playbackService?.getDuration() ?: -1L
            // [v2.3.1] Monotonic guard: never let updateUI() move position backward.
            // The service's getCurrentPosition() is already monotonic when prepared, but
            // during episode transitions there can be brief windows where stale values leak.
            if (svcPos > 0 && svcPos >= lastDisplayedPositionMs - 2000) {
                lastDisplayedPositionMs = svcPos
            }
            // Only update seekBar if we have valid position data (not 0 when playing)
            val isPlaying = playbackService?.isPlaying() ?: false
            val displayPos = lastDisplayedPositionMs
            if (displayPos > 0 || !isPlaying) {
                if (displayPos > 0) {
                    binding.seekBar.max = if (svcDur > 0) svcDur.toInt() else binding.seekBar.max
                    binding.seekBar.progress = displayPos.toInt()
                    binding.tvCurrentTime.text = "${formatTime(displayPos.toInt())} / ${if (svcDur > 0) formatTime(svcDur.toInt()) else "--:--"}"
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
            binding.tvCacheUrl.text = "本地缓存: ${com.radio.app.RadioApplication.getEpisodesCacheDir(this).absolutePath}/$cacheFileName"
            binding.tvCacheUrl.visibility = View.VISIBLE
        }
        // 更新缓存进度
        if (playbackService?.isLive() == true) {
            binding.tvCacheProgress.visibility = View.GONE
            binding.seekBarCache.visibility = View.GONE
        }
    }

    private fun playEpisodeAtIndex(index: Int) {
        if (index < 0 || index >= episodeList.size) {
            writeEpisodeLog("[v2.0.42] playEpisodeAtIndex: INVALID index=$index, episodeList.size=${episodeList.size}")
            return
        }
        writeJitterLog("[v2.0.42] playEpisodeAtIndex: START, index=$index, episodeList.size=${episodeList.size}")
        writeEpisodeLog("[v2.0.42] playEpisodeAtIndex: START, index=$index, episodeList.size=${episodeList.size}")
        if (playbackService == null) {
            writeEpisodeLog("[v2.0.42] playEpisodeAtIndex: ERROR - playbackService is null, cannot switch episode")
            Toast.makeText(this, "播放服务未连接，无法切换", Toast.LENGTH_SHORT).show()
            return
        }
        var targetIdx = index
        var targetEpisode = episodeList[targetIdx]
        writeEpisodeLog("[v2.0.42] playEpisodeAtIndex: targetEpisode title=${targetEpisode.title}, id=${targetEpisode.id}, audioUrl=${targetEpisode.audioUrl}")
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
        // Issue 5 Fix: Toast 即时反馈，让用户知道点击已生效
        Toast.makeText(this, "切换到: ${targetEpisode.title}", Toast.LENGTH_SHORT).show()
        currentEpisodeIndex = targetIdx
        currentEpisode = targetEpisode
        // Issue 10 Fix 2: clear old subtitles when switching episodes
        clearSubtitles()
        saveLastEpisode()
        writeEpisodeLog("[v2.0.42] playEpisodeAtIndex: BEFORE playEpisode - target=${targetEpisode.title}, id=${targetEpisode.id}, url=${targetEpisode.audioUrl}")
        val beforeEpisode = playbackService?.getCurrentEpisode()
        writeEpisodeLog("[v2.0.42] playEpisodeAtIndex: BEFORE - service current=${beforeEpisode?.title} (id=${beforeEpisode?.id})")
        playbackService?.playEpisode(targetEpisode, false)
        // Issue 5 Fix: 立即验证切换是否生效（不依赖延迟）
        val immediateAfter = playbackService?.getCurrentEpisode()
        writeEpisodeLog("[v2.0.42] playEpisodeAtIndex: IMMEDIATE AFTER - service=${immediateAfter?.title} (id=${immediateAfter?.id}), target=${targetEpisode.title}, match=${targetEpisode.id == immediateAfter?.id}")
        if (targetEpisode.id != immediateAfter?.id) {
            writeEpisodeLog("[v2.0.42] playEpisodeAtIndex: WARNING - service episode did NOT change! before=${beforeEpisode?.id}, after=${immediateAfter?.id}, target=${targetEpisode.id}")
        }
        // Issue 5 Fix: 延迟校验（500ms）确认切换是否真正生效
        Handler(Looper.getMainLooper()).postDelayed({
            val verifyEpisode = playbackService?.getCurrentEpisode()
            writeEpisodeLog("[v2.0.42] playEpisodeAtIndex: DELAYED VERIFY (500ms) - service=${verifyEpisode?.title} (id=${verifyEpisode?.id}), target=${targetEpisode.title} (id=${targetEpisode.id}), match=${targetEpisode.id == verifyEpisode?.id}")
        }, 500)
        // Issue 5 Fix: 再延迟2秒做最终校验
        Handler(Looper.getMainLooper()).postDelayed({
            val finalEpisode = playbackService?.getCurrentEpisode()
            writeEpisodeLog("[v2.0.42] playEpisodeAtIndex: FINAL VERIFY (2s) - service=${finalEpisode?.title} (id=${finalEpisode?.id}), target=${targetEpisode.title} (id=${targetEpisode.id}), match=${targetEpisode.id == finalEpisode?.id}")
        }, 2000)
        // Issue 4: 切歌后刷新节目列表适配器，使高亮跟随当前播放节目
        episodeListAdapter?.let { adapter ->
            adapter.currentlyPlayingId = targetEpisode.id
            adapter.notifyDataSetChanged()
            writeEpisodeLog("[v2.0.42] playEpisodeAtIndex: refreshed adapter, currentlyPlayingId=${adapter.currentlyPlayingId}")
        }
        voiceSegments = generateSimulatedSegments()
        if (voiceSegments.isNotEmpty()) updateSegmentsUI()
        updateUI()
        setupPreCacheList()
        writeEpisodeLog("[v2.0.42] playEpisodeAtIndex: DONE, switched to ${targetEpisode.title}, index=$currentEpisodeIndex")
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
            // Issue 4 Fix: 同时匹配 episode ID 和基于 title 的匹配（跨天同一节目也能高亮）
            val isPlaying = episode.id == currentlyPlayingId ||
                (currentlyPlayingId.isNullOrEmpty() && currentEpisode?.title == episode.title) ||
                (currentEpisode != null && episode.title != null &&
                 currentEpisode?.title == episode.title &&
                 currentEpisode?.stationId == episode.stationId)
            writeEpisodeLog("[v2.0.42] onBindViewHolder: pos=$position, title=${episode.title}, id=${episode.id}, isPlaying=$isPlaying, currentlyPlayingId=$currentlyPlayingId")

            holder.tvTitle.text = if (isPlaying) "▶ ${episode.title}" else episode.title
            holder.tvTime.text = try {
                dateIn.parse(episode.broadcastAt)?.let { dateOut.format(it) } ?: episode.broadcastAt
            } catch (_: Exception) {
                episode.broadcastAt
            }
            val durationMin = episode.duration / 60
            val segments = episode.voiceSegments?.size ?: 0
            holder.tvDescription.text = "${durationMin}分钟 · ${segments}片段"

            val ctx = holder.itemView.context
            val accentColor = resolveThemeColor(ctx, android.R.attr.colorPrimary)
            val titleColor = resolveThemeColor(ctx, com.radio.app.R.attr.appTextPrimary)

            if (isPlaying) {
                // Issue 4 Fix: 大幅提高高亮可见度——alpha从60提高到180，添加左侧色条
                val tint = Color.argb(180, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                holder.itemView.setBackgroundColor(tint)
                // 左侧添加3dp宽的强调色条（通过padding+background实现）
                val density = ctx.resources.displayMetrics.density
                holder.itemView.setPadding((3 * density).toInt(), holder.itemView.paddingTop,
                    holder.itemView.paddingEnd, holder.itemView.paddingBottom)
                holder.tvTitle.setTypeface(null, android.graphics.Typeface.BOLD)
                holder.tvTitle.setTextColor(accentColor)
                holder.tvPlayingIndicator.text = "正在播放"
                holder.tvPlayingIndicator.setTextColor(accentColor)
                holder.tvPlayingIndicator.visibility = View.VISIBLE
                holder.btnPlay.setImageResource(android.R.drawable.ic_media_pause)
                holder.btnPlay.setColorFilter(accentColor)
                writeEpisodeLog("[v2.0.42] onBindViewHolder: HIGHLIGHT pos=$position title=${episode.title}, alpha=180, accentColor=$accentColor")
            } else {
                holder.itemView.background = holder.originalBackground
                val density = ctx.resources.displayMetrics.density
                holder.itemView.setPadding((4 * density).toInt(), holder.itemView.paddingTop,
                    holder.itemView.paddingEnd, holder.itemView.paddingBottom)
                holder.tvTitle.setTypeface(null, android.graphics.Typeface.NORMAL)
                holder.tvTitle.setTextColor(titleColor)
                holder.tvPlayingIndicator.visibility = View.GONE
                holder.btnPlay.setImageResource(R.drawable.ic_play)
                holder.btnPlay.clearColorFilter()
            }

            // Issue 5 Fix: 使用 bindingAdapterPosition 替代 position，确保点击位置准确
            holder.itemView.setOnClickListener {
                val clickPos = holder.bindingAdapterPosition
                writeEpisodeLog("[v2.0.42] onItemClick: bindingAdapterPosition=$clickPos, position=$position, title=${episodes.getOrNull(clickPos)?.title}")
                if (clickPos >= 0 && clickPos < episodes.size) {
                    onItemClicked?.invoke(clickPos)
                }
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

    // [v2.4.6] Get detailed ASR label including specific model name (e.g., "Whisper Tiny" instead of "Whisper")
    private fun getDetailedAsrLabel(): String {
        val settings = AppSettings.getInstance(this)
        val provider = settings.safeAsrProvider()
        // [v2.4.6] If we already have a lastReportedModelName set by subtitle service, use it
        if (lastReportedModelName.isNotBlank()) {
            return lastReportedModelName
        }
        return when {
            provider == AppSettings.ASR_WHISPER || provider == "whisper-local" -> {
                val modelDir = settings.whisperModelDir
                if (modelDir.isNotBlank()) {
                    val friendlyName = getFriendlyModelName(modelDir)
                    if (friendlyName.startsWith("Whisper ")) friendlyName
                    else "Whisper $friendlyName"
                } else "Whisper"
            }
            provider == AppSettings.ASR_VOSK -> {
                val modelDir = settings.voskModelDir
                if (modelDir.isNotBlank()) getFriendlyModelName(modelDir)
                else "Vosk离线"
            }
            provider == AppSettings.ASR_BAIDU -> "百度ASR"
            provider == AppSettings.ASR_FUNASR -> "FunASR"
            else -> provider
        }
    }

    // [v2.4.6] Format model path/dir to friendly name
    private fun getFriendlyModelName(path: String): String {
        val name = File(path).name.lowercase()
        return when {
            name.contains("tiny") -> "Whisper Tiny"
            name.contains("base") -> "Whisper Base"
            name.contains("small") && !name.contains("vosk") -> "Whisper Small"
            name.contains("medium") -> "Whisper Medium"
            name.contains("large") -> "Whisper Large"
            name.contains("vosk") && name.contains("small") -> "Vosk 小模型"
            name.contains("vosk") && name.contains("large") -> "Vosk 大模型"
            name.contains("vosk") -> "Vosk 模型"
            else -> name.replace(".bin", "").replace("ggml-", "Whisper ").replace("vosk-model-", "Vosk ")
                .split("-").joinToString(" ") { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() } }
        }
    }

    // [v2.0.67] Issue 6: Format raw model directory name into a user-readable label.
    private fun formatModelName(rawName: String): String {
        if (rawName.isBlank()) return getDetailedAsrLabel()
        // [v2.4.3] If the name already looks like a friendly name, return as-is
        // (SubtitleGeneratorService now sends "Whisper Tiny", "Whisper Base", etc.)
        if (rawName.startsWith("Whisper ") || rawName.startsWith("Vosk ")) {
            return rawName
        }
        return when {
            // Vosk models: check specific patterns first
            rawName.contains("small-cn", ignoreCase = true) -> "Vosk中文小模型"
            rawName.contains("cn-0.22", ignoreCase = true) -> "Vosk中文大模型"
            rawName.contains("small-en", ignoreCase = true) -> "Vosk英文小模型"
            rawName.contains("en-us-0.22-lgraph", ignoreCase = true) -> "Vosk英文中模型"
            rawName.contains("en-us-0.22", ignoreCase = true) -> "Vosk英文大模型"
            // Whisper models by filename
            rawName.contains("tiny", ignoreCase = true) -> "Whisper Tiny"
            rawName.contains("base", ignoreCase = true) -> "Whisper Base"
            rawName.contains("medium", ignoreCase = true) -> "Whisper Medium"
            rawName.contains("large", ignoreCase = true) -> "Whisper Large"
            rawName.contains("small", ignoreCase = true) -> "Whisper Small"
            rawName.contains("vosk", ignoreCase = true) -> "Vosk模型"
            rawName.contains("whisper", ignoreCase = true) -> "Whisper"
            else -> rawName
        }
    }

    private fun startAiProcessing(taskType: String) {
        if (taskType == "subtitle") subtitleProcessing = true
        else if (taskType == "segment") segmentProcessing = true
        saveProcessingState()
        if (_binding == null) return
        if (taskType == "subtitle") {
            // [跨进程] 新任务开始时清空广播累积列表，避免残留上一轮字幕
            subtitleBroadcastList.clear()
            binding.progressSubtitle.progress = 0
            binding.progressSubtitle.visibility = View.VISIBLE
            binding.tvSubtitleStatus.visibility = View.VISIBLE
            binding.tvSubtitleStatus.text = "字幕生成: 0% (引擎: ${getDetailedAsrLabel()})"
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

        // [v2.4.21] If complete subtitles exist, use content-based AI segmentation
        // instead of fixed 5-minute segments
        val episode = currentEpisode
        if (episode != null && episode.id.isNotBlank()) {
            val contentSegments = generateContentBasedSegments(episode.id, dur)
            if (contentSegments.isNotEmpty()) return contentSegments
        }

        // Fallback: fixed 5-minute segments
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

    // [v2.4.25] Generate content-based segments from subtitles
    // Groups subtitles into meaningful segments and classifies as 干货(content) or 水货(filler)
    private fun generateContentBasedSegments(episodeId: String, durationMs: Int): List<VoiceSegment> {
        try {
            val dbHelper = com.radio.app.database.RadioDatabaseHelper.getInstance(this)
            val transcripts = dbHelper.getTranscripts(episodeId)
            if (transcripts.size < 3) return emptyList()

            // [v2.4.25] Refined keywords — removed common words that match everything
            // Dry keywords: specific content indicators (news, analysis, data, interview)
            val dryKeywords = listOf("新闻", "资讯", "报道", "访谈", "评论", "分析", "数据", "调查",
                "采访", "记者", "专家", "研究", "政策", "经济", "社会", "科技", "教育", "健康",
                "事故", "事件", "天气", "路况", "交通", "市场", "价格")
            // Water keywords: broadcast filler, ads, music, station ID, greetings
            val waterKeywords = listOf("广告", "音乐", "歌曲", "休息", "片头", "片尾", "赞助",
                "微信", "公众号", "下载", "关注", "扫码", "二维码", "推广",
                "欢迎收听", "感谢收听", "这里是", "您正在收听", "广播电台",
                "接下来", "稍后", "马上回来", "不要走开",
                "早安", "晚安", "再见", "拜拜",
                "片花", "预告", "下周", "明天同一时间")

            // Group transcripts into segments (every ~3-5 minutes of content)
            val segmentDurationMs = 3 * 60 * 1000L  // 3 minutes per segment
            val segments = mutableListOf<VoiceSegment>()
            var currentSegStart = 0L
            var currentSegEnd = segmentDurationMs
            var currentTexts = mutableListOf<String>()

            for (t in transcripts) {
                while (t.segmentStart >= currentSegEnd && currentTexts.isNotEmpty()) {
                    // Close current segment
                    val combinedText = currentTexts.joinToString("")
                    val isDry = classifySegment(combinedText, dryKeywords, waterKeywords, segmentDurationMs)
                    val seg = VoiceSegment().apply {
                        this.start = currentSegStart
                        this.end = currentSegEnd
                        this.hasVoice = isDry  // 干货 = hasVoice=true, 水货 = hasVoice=false
                        this.label = if (isDry) "干货" else "水货"
                        this.isSimulated = false
                    }
                    segments.add(seg)
                    currentSegStart = currentSegEnd
                    currentSegEnd = currentSegStart + segmentDurationMs
                    currentTexts = mutableListOf()
                }
                currentTexts.add(t.text ?: "")
            }
            // Close last segment
            if (currentTexts.isNotEmpty()) {
                val combinedText = currentTexts.joinToString("")
                val isDry = classifySegment(combinedText, dryKeywords, waterKeywords, segmentDurationMs)
                val seg = VoiceSegment().apply {
                    this.start = currentSegStart
                    this.end = durationMs.toLong()
                    this.hasVoice = isDry
                    this.label = if (isDry) "干货" else "水货"
                    this.isSimulated = false
                }
                segments.add(seg)
            }

            writeJitterLog("[v2.4.25] generateContentBasedSegments: ${segments.size} segments from ${transcripts.size} transcripts, dry=${segments.count{it.hasVoice}}, water=${segments.count{!it.hasVoice}}")
            return segments
        } catch (e: Exception) {
            writeJitterLog("[v2.4.25] generateContentBasedSegments failed: ${e.message}")
            return emptyList()
        }
    }

    // [v2.4.25] Classify a text segment as dry(content) or water(filler)
    // Uses keyword matching + text density (words per minute)
    private fun classifySegment(text: String, dryKeywords: List<String>, waterKeywords: List<String>, segmentDurationMs: Long): Boolean {
        val textLower = text.lowercase()
        var dryScore = 0
        var waterScore = 0
        for (kw in dryKeywords) {
            if (textLower.contains(kw.lowercase())) dryScore++
        }
        for (kw in waterKeywords) {
            if (textLower.contains(kw.lowercase())) waterScore++
        }

        // [v2.4.25] Text density: words per minute
        // Normal speech ~150-200 Chinese chars/min. If < 30 chars/min, likely silence/filler.
        val segmentMinutes = segmentDurationMs / 60000.0
        val charsPerMin = if (segmentMinutes > 0) text.length / segmentMinutes else 0.0

        // [v2.4.25] Classification rules:
        // 1. If text is very short (< 30 chars total), mark as water
        if (text.length < 30) return false
        // 2. If text density is very low (< 20 chars/min), mark as water
        if (charsPerMin < 20) return false
        // 3. If water keywords found and dryScore <= waterScore, mark as water
        if (waterScore > 0 && dryScore <= waterScore) return false
        // 4. If no keywords match at all, use text density:
        //    Dense text (>50 chars/min) = likely content, Sparse = likely filler
        if (dryScore == 0 && waterScore == 0) {
            return charsPerMin > 50
        }
        // 5. Default: dry only if dryScore > waterScore
        return dryScore > waterScore
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
        // [v2.4.9] Refresh subtitle scroll pause duration from settings (user may have changed it)
        subtitleScrollPauseDurationMs = AppSettings.getInstance(this).subtitleScrollPauseSeconds * 1000L
        // [v2.0.80] Issue 1 Fix: Notify service that Activity resumed to activate skip blackout window.
        // v2.0.79 bug: lastClientBindTime was only set in onBind (39s before onResume), so the
        // 3-second post-resume blackout never triggered. Skip storms started 159ms after onResume.
        // Now we update the timestamp on every onResume so the 3s blackout catches queued events.
        try { playbackService?.notifyActivityResumed() } catch (_: Exception) {}
        // [跨进程] 注册字幕广播接收器（服务运行在 ":subtitle" 进程，结果通过广播回传）
        if (!subtitleReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction("com.radio.app.SUBTITLE_GENERATED")
                addAction("com.radio.app.SUBTITLE_PROGRESS")
                addAction("com.radio.app.SUBTITLE_ERROR")
                addAction("com.radio.app.SUBTITLE_COMPLETE")
                addAction("com.radio.app.SUBTITLE_MODEL_INFO")
            }
            registerReceiver(subtitleReceiver, filter)
            subtitleReceiverRegistered = true
        }
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

        // [v2.0.76] Issue 1 Fix: ALWAYS start stabilization on EVERY onResume, including config changes.
        // v2.0.75 bug: stabilization only started when serviceBound && playbackService!=null && cachedPos>0,
        // which failed during Activity recreation (config change), causing inStab=false and pos=0 to be
        // accepted as LEGIT BACK (delta=3809322 >= 300000), triggering seekTo(0) storm.
        lastOnResumeTimestampMs = System.currentTimeMillis()  // [v2.4.16] Track for stale callback detection
        val cachedPos = getSharedPreferences("player_position_cache", MODE_PRIVATE).getLong("cached_position", 0L)
        val cachedDur = getSharedPreferences("player_position_cache", MODE_PRIVATE).getLong("cached_duration", 0L)
        val cachedEpId = getSharedPreferences("player_position_cache", MODE_PRIVATE).getString("cached_episode_id", "")
        val syncTime = System.currentTimeMillis()

        // [v2.3.1] Fix progress regression/flicker on resume:
        // Check if service was already connected and had valid position BEFORE this onResume.
        // If so (normal background→foreground, Activity not destroyed), the callback was updating
        // lastDisplayedPositionMs continuously in the background, so the position is already
        // accurate. Use a SHORT stabilization (1s) instead of 5s to avoid "frozen progress" feel.
        // If service is NOT ready or position is 0 (fresh activity/recreate), use full 5s stabilization.
        val svcPos = if (serviceBound && playbackService != null) playbackService?.getCurrentPosition() ?: 0L else 0L
        val svcDur = if (serviceBound && playbackService != null) playbackService?.getDuration() ?: 0L else 0L
        val wasAlreadyConnected = serviceBound && svcPos > 2000
        jitterStabilizeMs = if (wasAlreadyConnected) 1000L else JITTER_STABILIZE_MS_DEFAULT
        jitterSyncTimeMs = syncTime
        consecutiveBackwardJumps = 0
        writeJitterLog("[v2.3.1] onResume: stabilization STARTED (${jitterStabilizeMs}ms), svcPos=$svcPos, cachedPos=$cachedPos, cachedEpId=$cachedEpId, serviceBound=$serviceBound, wasAlreadyConnected=$wasAlreadyConnected")

        // [v2.0.85] Fix: When service is not bound (svcPos=0), do NOT display stale cachedPos.
        // v2.0.76 would show cachedPos then snap to svcPos when service connects = visible flicker.
        // Now: only set UI if svcPos is valid. If service not bound yet, wait for onServiceConnected.
        // [v2.4.19] Also check if cachedEpId matches current episode - don't show stale pos from different episode
        if (svcPos > 2000) {
            // Service is bound and has valid position - sync UI immediately
            lastDisplayedPositionMs = svcPos
            jitterSyncBaseline = svcPos
            if (svcDur > 0 && _binding != null) {
                binding.seekBar.max = svcDur.toInt()
            }
            if (_binding != null) {
                binding.tvCurrentTime.text = "${formatTime(svcPos.toInt())} / ${if (svcDur > 0) formatTime(svcDur.toInt()) else "--:--"}"
                binding.seekBar.progress = svcPos.toInt()
            }
            writeJitterLog("[v2.3.1] onResume: UI immediately synced to svcPos=$svcPos, dur=$svcDur")
        } else if (cachedPos > 0 && _binding != null && cachedEpId == currentEpisode?.id) {
            // [v2.3.1] Service not ready yet - show cached position (which we already pre-filled
            // in initViews) to prevent "00:00" gap. onServiceConnected will correct when ready.
            // [v2.4.19] Only show if cachedEpId matches current episode
            writeJitterLog("[v2.3.1] onResume: service not ready (svcPos=$svcPos), keeping pre-filled cached=$cachedPos, waiting for onServiceConnected")
        } else {
            // [v2.4.19] Service not bound AND cached position is from a different episode (or no cache)
            // Don't show stale position - reset to 0 and wait for valid callback
            lastDisplayedPositionMs = 0
            writeJitterLog("[v2.4.19] onResume: service not bound, cachedEpId=$cachedEpId != currentEpId=${currentEpisode?.id}, NOT showing stale cachedPos=$cachedPos")
        }

        // [v2.4.17] Sync title from service's currentEpisode to prevent stale title after background episode switch
        if (serviceBound && playbackService != null) {
            try {
                val svcEpisode = playbackService?.getCurrentEpisode()
                if (svcEpisode != null && svcEpisode.id != currentEpisode?.id) {
                    writeJitterLog("[v2.4.17] onResume: syncing episode from service: ${svcEpisode.title} (id=${svcEpisode.id}), was: ${currentEpisode?.title}")
                    currentEpisode = svcEpisode
                    lastDisplayedPositionMs = 0
                    lastJitterEpisodeId = svcEpisode.id
                    val newIdx = episodeList.indexOfFirst { it.id == svcEpisode.id }
                    if (newIdx >= 0) currentEpisodeIndex = newIdx
                    updateUI()
                }
            } catch (e: Exception) {
                writeJitterLog("[v2.4.17] onResume: failed to sync episode: ${e.message}")
            }
        }

        restoreBackgroundResults()

        // [v2.4.3] Delayed retry: if subtitles exist in DB but weren't loaded on first pass
        // (possible DB timing issue on cold start), retry after 1.5 seconds.
        positionUpdateHandler.postDelayed({
            if (_binding != null && subtitleTranscripts.isEmpty()) {
                val ep = currentEpisode
                if (ep != null) {
                    val dbHelper = RadioDatabaseHelper.getInstance(this)
                    val dbTranscripts = dbHelper.getTranscripts(ep.id)
                    if (dbTranscripts.isNotEmpty()) {
                        android.util.Log.d("PlayerActivity", "restoreSubtitles delayed retry: found ${dbTranscripts.size} transcripts")
                        subtitleTranscripts = dbTranscripts
                        subtitleBroadcastList.clear()
                        subtitleBroadcastList.addAll(dbTranscripts)
                        subtitleAdapter?.setTranscripts(subtitleTranscripts)
                        // [v2.4.12] Show engine info in subtitle title
                        val retryEngineName = dbHelper.getTranscriptEngine(ep.id)
                        binding.tvSubtitleTitle.text = if (!retryEngineName.isNullOrBlank())
                            "字幕  生成所用引擎：$retryEngineName" else "字幕"
                        binding.tvSubtitleTitle.visibility = View.VISIBLE
                        binding.recyclerSubtitles.visibility = View.VISIBLE
                    }
                }
            }
        }, 1500)
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
            // [跨进程] 将广播累积列表与数据库对齐：暂停期间漏收的广播结果已落盘到 DB，
            // 这里以 DB 为准重建累积列表，避免后续广播追加时把 DB 里的字幕丢掉。
            subtitleBroadcastList.clear()
            subtitleBroadcastList.addAll(dbTranscripts)
            subtitleAdapter?.setTranscripts(subtitleTranscripts)
            // [v2.4.12] Show engine info in subtitle title
            val engineName = dbHelper.getTranscriptEngine(episode.id)
            binding.tvSubtitleTitle.text = if (!engineName.isNullOrBlank())
                "字幕  生成所用引擎：$engineName" else "字幕"
            binding.tvSubtitleTitle.visibility = View.VISIBLE
            binding.recyclerSubtitles.visibility = View.VISIBLE
            // Keep segments visible too (do not touch recyclerSegments visibility).
        } else {
            // Issue 8: no subtitles for this episode — hide the subtitle RecyclerView and its
            // title so the subtitle area below the button is empty, while segments stay visible.
            // [跨进程] 无字幕时同步清空广播累积列表
            subtitleBroadcastList.clear()
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
        // [v2.4.9] Reset scroll pause state when switching episodes
        isSubtitleScrollPaused = false
        lastUserScrollTimeMs = 0L
        subtitleAdapter?.setTranscripts(emptyList())
        // [跨进程] 同步清空广播累积列表
        subtitleBroadcastList.clear()
        // [v2.3.1] Do NOT reset lastDisplayedPositionMs=0 here.
        // clearSubtitles() is called during episode switches and service reconnects.
        // Resetting the displayed position to 0 causes the progress bar to briefly
        // show 00:00 before the next onPositionTick updates it, creating a visible flicker.
        // The position is properly reset in updateUI()/onServiceConnected when needed.
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
        // [v2.1.8] Check if seek_position_ms was passed from search
        val seekMs = intent.getLongExtra("seek_position_ms", -1L)
        if (seekMs > 0) {
            pendingSeekMs = seekMs
            writeJitterLog("onNewIntent: will seek to $seekMs ms after playback starts")
        }
        // [v2.1.8] Compare by episode ID, not URL.
        // The old code used isSameEpisodePlaying(audioUrl) which compares URLs,
        // but the search-constructed URL might differ from the actual playing URL
        // even for the same episode, or match for different episodes.
        val targetEpisodeId = intent.getStringExtra("target_episode_id") ?: newEpisode.id
        val currentPlayingId = playbackService?.getCurrentEpisode()?.id ?: ""
        val sameEpisode = targetEpisodeId == currentPlayingId
        writeJitterLog("onNewIntent: targetEpisodeId=$targetEpisodeId, currentPlayingId=$currentPlayingId, sameEpisode=$sameEpisode")
        if (serviceBound && playbackService != null) {
            if (sameEpisode) {
                writeJitterLog("onNewIntent: same episode already playing, skip restart")
                restoreBackgroundResults()
                // [v2.1.8] If same episode, seek immediately
                if (pendingSeekMs > 0) {
                    playbackService?.seekTo(pendingSeekMs)
                    writeJitterLog("onNewIntent: same episode, executing seek to $pendingSeekMs ms")
                    pendingSeekMs = -1L
                }
            } else {
                // [v2.1.8] Different episode - MUST call playEpisode to switch
                // Pass seekMs as startPositionMs so player seeks immediately after prepare
                val startPos = if (seekMs > 0) seekMs else getSavedPositionForEpisode(this, newEpisode.id)
                writeJitterLog("onNewIntent: DIFFERENT episode, calling playEpisode with startPos=$startPos")
                playbackService?.playEpisode(newEpisode, false, startPos)
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
        // [跨进程] 注销字幕广播接收器（下次 onResume 会重新注册）
        if (subtitleReceiverRegistered) {
            try {
                unregisterReceiver(subtitleReceiver)
            } catch (_: Exception) {}
            subtitleReceiverRegistered = false
        }
        // Feature B: stop position update when activity is not visible
        positionUpdateHandler.removeCallbacks(positionUpdateRunnable)
        // [v2.0.73] Issue 1 Fix: Only cache position when player is prepared and position is valid.
        // During episode switches, player reports position=1197ms or 0ms which corrupts the cache,
        // causing resume to seek to beginning instead of last valid position.
        // [v2.0.84] Fix: Reverted v2.0.83's change to use lastDisplayedPositionMs.
        // v2.0.83 cached uiPos, but STAB-HOLD could pin uiPos at a stale forward position
        // (e.g., 44s ahead of svcPos after skipBackward), corrupting the cache.
        // Now cache svcPos (actual playback position), which is correct even after skipBackward.
        if (serviceBound && playbackService != null) {
            val isPrepared = playbackService?.isPrepared() ?: false
            val pos = playbackService?.getCurrentPosition() ?: 0L
            val dur = playbackService?.getDuration() ?: 0L
            val epId = currentEpisode?.id ?: ""
            // Only cache if: player is prepared, position > 5s (not at beginning), duration valid, episode known
            if (isPrepared && pos > 5000 && dur > 30000 && epId.isNotBlank()) {
                lastDisplayedPositionMs = pos
                getSharedPreferences("player_position_cache", MODE_PRIVATE).edit()
                    .putLong("cached_position", pos)
                    .putLong("cached_duration", dur)
                    .putString("cached_episode_id", epId)
                    .apply()
                writeJitterLog("onPause: cached position=$pos (uiPos=$lastDisplayedPositionMs), duration=$dur, episodeId=$epId (prepared=true)")
            } else {
                writeJitterLog("onPause: SKIPPED position cache (prepared=$isPrepared, pos=$pos, dur=$dur, epId=$epId) - keeping previous cache")
            }
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

        // [v2.4.9] Check if subtitle scroll pause has expired
        if (isSubtitleScrollPaused) {
            val elapsed = System.currentTimeMillis() - lastUserScrollTimeMs
            if (elapsed >= subtitleScrollPauseDurationMs) {
                // Pause expired, resume auto-jump
                isSubtitleScrollPaused = false
            }
        }

        // Update subtitle highlight - only if index changed
        // [v2.4.9] Skip auto-scroll if user is manually scrolling (pause active)
        if (subtitleTranscripts.isNotEmpty()) {
            val subtitleIdx = findClosestTranscriptIndex(pos)
            if (subtitleIdx != lastSubtitleHighlightIdx) {
                lastSubtitleHighlightIdx = subtitleIdx
                subtitleAdapter?.setCurrentHighlightIndex(subtitleIdx)
                // Only auto-scroll to position if user is NOT in scroll-pause period
                if (subtitleIdx >= 0 && !isSubtitleScrollPaused) {
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