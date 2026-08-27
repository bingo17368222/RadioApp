package com.radio.app.fragments

import android.app.DatePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.radio.app.R
import com.radio.app.activities.PlayerActivity
import com.radio.app.adapters.EpisodeAdapter
import com.radio.app.models.AppSettings
import com.radio.app.models.Episode
import com.radio.app.models.RadioStation
import com.radio.app.network.EpisodeApiService
import com.radio.app.database.RadioDatabaseHelper
import com.radio.app.services.RadioPlaybackService
import com.radio.app.utils.PlayHistoryUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class EpisodesFragment : Fragment(), EpisodeAdapter.OnEpisodeClickListener {

    private var recyclerView: RecyclerView? = null
    private var progressBar: ProgressBar? = null
    private var dateContainer: LinearLayout? = null
    private var stationContainer: LinearLayout? = null
    private var tvSelectedDate: TextView? = null
    private var adapter: EpisodeAdapter? = null
    private val episodes = mutableListOf<Episode>()
    private val mainHandler = Handler(Looper.getMainLooper())
    // v2.4.176: Refresh the episode list when patrol pre-segmentation updates segments.
    private val segmentsUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            adapter?.notifyDataSetChanged()
        }
    }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dayFormat = SimpleDateFormat("M/d EEE", Locale.CHINA)
    // v3.1.58: 初始化时优先使用最后播放节目日期，失败则回退到当前系统日期
    private val selectedDate: Calendar by lazy {
        getLastPlayedEpisodeDate() ?: Calendar.getInstance()
    }
    private var selectedStationId: String? = null
    private var selectedStationName: String? = null
    private val dateButtons = mutableListOf<TextView>()
    private var initialLoadDone = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_episodes, container, false)
        recyclerView = v.findViewById(R.id.recycler_view)
        progressBar = v.findViewById(R.id.progress_bar)
        dateContainer = v.findViewById(R.id.date_container)
        stationContainer = v.findViewById(R.id.station_container)
        tvSelectedDate = v.findViewById(R.id.tv_selected_date)

        recyclerView?.layoutManager = LinearLayoutManager(context)

        v.findViewById<Button>(R.id.btn_today)?.setOnClickListener {
            selectedDate.timeInMillis = System.currentTimeMillis()
            buildDatePills()
            selectedStationId?.let { loadEpisodes(it, dateFormat.format(selectedDate.time)) }
        }

        v.findViewById<Button>(R.id.btn_date_picker)?.setOnClickListener {
            showDatePickerDialog()
        }

        v.findViewById<Button>(R.id.btn_refresh)?.setOnClickListener {
            // [v2.2.5] Force refresh from API and update DB
            if (selectedStationId == null) {
                Toast.makeText(context, "请先选择电台", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(context, "正在刷新节目单...", Toast.LENGTH_SHORT).show()
            loadEpisodes(selectedStationId!!, dateFormat.format(selectedDate.time), forceRefresh = true)
        }

        // v3.1.117: 播放历史按钮
        v.findViewById<Button>(R.id.btn_history)?.setOnClickListener {
            showPlayHistoryDialog()
        }

        // v3.1.136: 播放计划按钮
        v.findViewById<Button>(R.id.btn_schedule)?.setOnClickListener {
            showPlayScheduleDialog()
        }

        // 先恢复上次保存的日期和电台
        restoreLastSelection()

        buildDatePills()
        buildStationPills()
        return v
    }

    override fun onResume() {
        super.onResume()
        // 恢复上次选择的日期 - 但只在首次加载时恢复
        if (!initialLoadDone) {
            restoreLastSelection()
            initialLoadDone = true
        }
        // [v2.0.71] Issue 7: Sync date to currently playing episode's date
        try {
            val prefs = requireContext().getSharedPreferences("last_episode", android.content.Context.MODE_PRIVATE)
            val playingBroadcastAt = prefs.getString("broadcast_at", null)
            if (!playingBroadcastAt.isNullOrEmpty() && playingBroadcastAt.length >= 10) {
                val playingDateStr = playingBroadcastAt.substring(0, 10)  // yyyy-MM-dd
                val playingDate = dateFormat.parse(playingDateStr)
                if (playingDate != null) {
                    val playingCal = Calendar.getInstance()
                    playingCal.time = playingDate
                    // Only switch if different from current selected date
                    if (playingCal.get(Calendar.DAY_OF_YEAR) != selectedDate.get(Calendar.DAY_OF_YEAR) ||
                        playingCal.get(Calendar.YEAR) != selectedDate.get(Calendar.YEAR)) {
                        selectedDate.time = playingDate
                        buildDatePills()
                        selectedStationId?.let { loadEpisodes(it, dateFormat.format(selectedDate.time)) }
                    }
                }
            }
        } catch (_: Exception) {}
        // [v2.0.43] Issue 4: Highlight currently playing episode
        try {
            val prefs = requireContext().getSharedPreferences("last_episode", android.content.Context.MODE_PRIVATE)
            val playingId = prefs.getString("episode_id", null)
            val playingUrl = prefs.getString("audio_url", null)
            adapter?.currentlyPlayingId = playingId
            adapter?.currentlyPlayingUrl = playingUrl
        } catch (_: Exception) {}
        // 刷新列表以更新缓存状态标记（从播放页返回后缓存可能已变化）
        adapter?.notifyDataSetChanged()
        // v2.4.176: Listen for patrol pre-segmentation updates.
        try {
            LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
                segmentsUpdatedReceiver,
                IntentFilter(RadioPlaybackService.ACTION_SEGMENTS_UPDATED)
            )
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        // v2.4.176: Stop listening for segment updates.
        try {
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(segmentsUpdatedReceiver)
        } catch (_: Exception) {}
    }

    private fun restoreLastSelection() {
        try {
            val settings = AppSettings.getInstance(requireContext())
            if (settings.lastSelectedDate.isNotBlank()) {
                val savedDate = dateFormat.parse(settings.lastSelectedDate)
                if (savedDate != null) {
                    selectedDate.time = savedDate
                }
            }
            if (settings.lastSelectedStationId.isNotBlank()) {
                selectedStationId = settings.lastSelectedStationId
                selectedStationName = EpisodeApiService.getStationName(settings.lastSelectedStationId)
            }
        } catch (_: Exception) {}
    }

    // v3.1.59: 移除maxDate/minDate限制，允许选择任何日期（包括未来日期）
    private fun showDatePickerDialog() {
        val year = selectedDate.get(Calendar.YEAR)
        val month = selectedDate.get(Calendar.MONTH)
        val day = selectedDate.get(Calendar.DAY_OF_MONTH)

        val dialog = DatePickerDialog(requireContext(), { _, y, m, d ->
            selectedDate.set(y, m, d)
            buildDatePills()
            selectedStationId?.let { loadEpisodes(it, dateFormat.format(selectedDate.time)) }
        }, year, month, day)

        // 不设置maxDate/minDate，允许选择任何日期
        dialog.show()
    }

    // v3.1.58: 从SharedPreferences读取最后播放节目日期，作为"近期"基准点
    private fun getLastPlayedEpisodeDate(): Calendar? {
        try {
            val prefs = requireContext().getSharedPreferences("last_episode", android.content.Context.MODE_PRIVATE)
            val broadcastAt = prefs.getString("broadcast_at", null) ?: return null
            // 尝试解析 "yyyy-MM-dd HH:mm:ss" 格式
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val date = sdf.parse(broadcastAt)
                if (date != null) {
                    val cal = Calendar.getInstance()
                    cal.time = date
                    return cal
                }
            } catch (_: Exception) {}
            // 尝试解析 "yyyy-MM-dd" 格式
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val date = sdf.parse(broadcastAt)
                if (date != null) {
                    val cal = Calendar.getInstance()
                    cal.time = date
                    return cal
                }
            } catch (_: Exception) {}
            return null
        } catch (_: Exception) {
            return null
        }
    }

    private fun buildDatePills() {
        dateContainer?.removeAllViews()
        dateButtons.clear()

        // v3.1.59: 使用实际系统日期作为"今天"参考，不再基于最后播放节目日期
        val today = Calendar.getInstance()

        tvSelectedDate?.text = "${selectedDate.get(Calendar.YEAR)}年${selectedDate.get(Calendar.MONTH) + 1}月${selectedDate.get(Calendar.DAY_OF_MONTH)}日"

        for (i in -7..7) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = selectedDate.timeInMillis
                add(Calendar.DAY_OF_MONTH, i)
            }

            val isToday = cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
                    cal.get(Calendar.YEAR) == today.get(Calendar.YEAR)
            val isSelected = cal.get(Calendar.DAY_OF_YEAR) == selectedDate.get(Calendar.DAY_OF_YEAR) &&
                    cal.get(Calendar.YEAR) == selectedDate.get(Calendar.YEAR)
            val pill = TextView(context).apply {
                text = "${dayFormat.format(cal.time)}"
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(12, 6, 12, 6)
                if (isSelected) {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(getColorPrimary())
                    setTypeface(null, Typeface.BOLD)
                } else if (isToday) {
                    setTextColor(getColorPrimary())
                    setBackgroundColor(Color.parseColor("#E8F5E9"))
                } else {
                    setTextColor(Color.parseColor("#666666"))
                    setBackgroundColor(Color.parseColor("#F0F0F0"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    marginStart = if (i > -7) 4 else 0
                    marginEnd = 2
                }
            }
            // v3.1.59: 移除未来日期禁用，所有日期都可点击选择
            pill.setOnClickListener {
                selectedDate.timeInMillis = cal.timeInMillis
                buildDatePills()
                selectedStationId?.let { loadEpisodes(it, dateFormat.format(selectedDate.time)) }
            }
            dateContainer?.addView(pill)
            dateButtons.add(pill)
        }

        // [v2.0.70] Issue 7: Auto-center the highlighted (selected) date in the HorizontalScrollView
        val selectedIdx = 7  // i=0 in -7..7 → index 7
        dateContainer?.post {
            try {
                val selectedPill = dateButtons.getOrNull(selectedIdx)
                if (selectedPill != null) {
                    val sv = (dateContainer?.parent as? android.widget.HorizontalScrollView)
                    val targetScroll = selectedPill.left - (sv?.width ?: 0) / 2 + selectedPill.width / 2
                    sv?.smoothScrollTo(targetScroll.coerceAtLeast(0), 0)
                }
            } catch (_: Exception) {}
        }
    }

    private fun buildStationPills() {
        stationContainer?.removeAllViews()
        val settings = AppSettings.getInstance(requireContext())
        val stations = getBuiltinStations()

        val sortedStations = stations.sortedByDescending { settings.getStationPlayCount(it.id) }

        sortedStations.forEachIndexed { index, station ->
            val pill = TextView(context).apply {
                text = station.name
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(14, 6, 14, 6)
                if (station.id == selectedStationId) {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(getColorPrimary())
                } else {
                    setTextColor(Color.parseColor("#666666"))
                    setBackgroundColor(Color.parseColor("#F0F0F0"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    marginStart = if (index > 0) 4 else 0
                    marginEnd = 2
                }
            }
            pill.setOnClickListener {
                selectedStationId = station.id
                selectedStationName = station.name
                buildStationPills()
                loadEpisodes(station.id, dateFormat.format(selectedDate.time))
            }
            stationContainer?.addView(pill)
        }

        // 仅在首次加载时（没有选中电台）自动加载
        if (selectedStationId != null && episodes.isEmpty()) {
            loadEpisodes(selectedStationId!!, dateFormat.format(selectedDate.time))
        }
    }

    private fun getColorPrimary(): Int {
        return try {
            val typedValue = android.util.TypedValue()
            context?.theme?.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
            typedValue.data
        } catch (e: Exception) {
            Color.parseColor("#7ED321")
        }
    }

    private fun getBuiltinStations(): List<RadioStation> {
        val data = arrayOf(
            arrayOf("henan-news", "新闻广播"),
            arrayOf("henan-economy", "经济广播"),
            arrayOf("henan-traffic", "交通广播"),
            arrayOf("henan-opera", "戏曲广播"),
            arrayOf("henan-music", "音乐广播"),
            arrayOf("henan-rural", "大象资讯台"),
            arrayOf("henan-myradio", "My Radio"),
            arrayOf("henan-private-car", "私家车999"),
            arrayOf("henan-edu", "教育广播"),
            arrayOf("henan-info", "信息广播"),
            arrayOf("henan-bigradio", "Big Radio")
        )
        return data.map { d ->
            RadioStation().apply {
                id = d[0]
                name = d[1]
                currentProgram = d[1]
            }
        }
    }

    private fun loadEpisodes(stationId: String, dateStr: String, forceRefresh: Boolean = false) {
        // 保存用户选择
        val settings = AppSettings.getInstance(requireContext())
        settings.lastSelectedDate = dateStr
        settings.lastSelectedStationId = stationId
        settings.save(requireContext())
        initialLoadDone = true

        progressBar?.visibility = View.VISIBLE
        adapter = EpisodeAdapter(requireContext(), episodes, this)
        recyclerView?.adapter = adapter

        // [v2.2.4] DB first: show cached episodes immediately, then fetch from API if needed
        Thread {
            // 1) Try DB first
            if (!forceRefresh) {
                try {
                    val cached = RadioDatabaseHelper.getInstance(requireContext())
                        .getEpisodesByDateAndStation(stationId, dateStr)
                    if (cached.isNotEmpty()) {
                        mainHandler.post {
                            episodes.clear()
                            episodes.addAll(cached)
                            adapter?.notifyDataSetChanged()
                            progressBar?.visibility = View.GONE
                        }
                        // DB hit, no need to fetch from API
                        return@Thread
                    }
                } catch (_: Exception) {}
            }

            // 2) Fetch from API (always for forceRefresh, or when DB is empty)
            EpisodeApiService.getInstance().getEpisodesByDate(stationId, dateStr,
                object : EpisodeApiService.ApiCallback<List<Episode>> {
                    override fun onSuccess(result: List<Episode>) {
                            // v3.1.119: 不再过滤duration>0的节目。即使API返回duration=0，
                            // saveEpisodeInfos会保留DB中已有的时长（预缓存时已入库）。
                            // 这样已缓存的节目不会出现"未知时长"问题。
                            val unsortedList = result.toMutableList()
                            // 对duration=0的节目，尝试从DB补充时长
                            try {
                                val dbHelper = RadioDatabaseHelper.getInstance(requireContext())
                                for (i in unsortedList.indices) {
                                    if (unsortedList[i].duration <= 0) {
                                        val dbEp = dbHelper.getEpisodeInfo(unsortedList[i].id ?: "")
                                        if (dbEp != null && dbEp.duration > 0) {
                                            unsortedList[i] = unsortedList[i].copy(
                                                duration = dbEp.duration,
                                                startTime = if (unsortedList[i].startTime <= 0) dbEp.startTime else unsortedList[i].startTime,
                                                endTime = if (unsortedList[i].endTime <= 0) dbEp.endTime else unsortedList[i].endTime
                                            )
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                            // 保存到DB（saveEpisodeInfos已兼容duration=0时保留已有值）
                            try {
                                RadioDatabaseHelper.getInstance(requireContext()).saveEpisodeInfos(unsortedList)
                            } catch (_: Exception) {}
                            mainHandler.post {
                                progressBar?.visibility = View.GONE
                                episodes.clear()
                                // v3.1.119: 按开始时间排序，确保节目单按时间顺序展示
                                episodes.addAll(unsortedList.sortedBy { it.startTime })
                                adapter?.notifyDataSetChanged()
                            }
                        }

                    override fun onError(error: String) {
                        mainHandler.post {
                            progressBar?.visibility = View.GONE
                            // [v2.2.4] Try DB as fallback on API error
                            try {
                                val cached = RadioDatabaseHelper.getInstance(requireContext())
                                    .getEpisodesByDateAndStation(stationId, dateStr)
                                if (cached.isNotEmpty()) {
                                    episodes.clear()
                                    episodes.addAll(cached)
                                    adapter?.notifyDataSetChanged()
                                } else {
                                    Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                                }
                            } catch (_: Exception) {
                                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                })
        }.start()
    }

    override fun onEpisodeClick(episode: Episode) {
        val audioUrl = episode.audioUrl
        if (audioUrl.isNullOrBlank()) {
            Toast.makeText(context, "该节目直播尚未结束，暂无回放音频", Toast.LENGTH_SHORT).show()
            return
        }

        // [v2.0.43] Issue 5: Log click event for verification
        try {
            val logDir = java.io.File(com.radio.app.RadioApplication.getLogDir(requireContext()), "jitter")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = java.io.File(logDir, "jitter.log")
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            java.io.FileWriter(logFile, true).use { it.append("[$ts]${com.radio.app.RadioApplication.appVersionTag()} [EPISODE] EpisodesFragment.onEpisodeClick: BEFORE click - target='${episode.title}', id=${episode.id}, url=$audioUrl\n") }
        } catch (_: Exception) {}

        episode.stationId?.let { stationId ->
            AppSettings.getInstance(requireContext()).incrementStationPlayCount(requireContext(), stationId)
        }

        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("episode", episode)
            putExtra("episode_id", episode.id)
            putExtra("title", episode.title)
            putExtra("audio_url", audioUrl)
            putExtra("is_live", false)
            putExtra("station_name", selectedStationName ?: episode.stationName)
            putExtra("duration", episode.duration)
            putExtra("voice_segments", ArrayList(episode.voiceSegments))
            putExtra("transcripts", ArrayList(episode.transcripts))
            putExtra("episode_list", ArrayList(episodes))
            putExtra("episode_index", episodes.indexOf(episode))
            // 传递新鲜启动时间戳，用于PlayerActivity判断是否用户主动点击
            putExtra("fresh_launch_ts", System.currentTimeMillis())
        }
        startActivity(intent)
    }

    override fun onEpisodeLongClick(episode: Episode) {
        // [v2.4.14] Show a dialog with multiple options
        val settings = AppSettings.getInstance(requireContext())
        val dbHelper = RadioDatabaseHelper.getInstance(requireContext())
        val hasSubtitles = try { dbHelper.getTranscripts(episode.id).isNotEmpty() } catch (_: Exception) { false }
        val isNoPreprocess = settings.isNoPreprocess(episode.id)
        val isDisliked = settings.isDisliked(episode.id) || settings.isDislikedByTitle(episode.stationId, episode.title)

        // v2.4.85: Check if audio is cached
        val audioFileName = try {
            val url = java.net.URL(episode.audioUrl)
            url.path.substringAfterLast("/")
        } catch (e: Exception) {
            episode.audioUrl.substringAfterLast("/")
        }
        val audioCacheFile = java.io.File(com.radio.app.RadioApplication.getEpisodesCacheDir(requireContext()), audioFileName)
        val hasCachedAudio = audioCacheFile.exists() && audioCacheFile.length() > 1024

        val options = mutableListOf<String>()
        // Option 0: Toggle dislike
        options.add(if (isDisliked) "取消不喜欢" else "标记不喜欢")
        // Option 1: Delete subtitles (only if exists)
        if (hasSubtitles) options.add("删除字幕")
        // v2.4.85: Delete cached audio (only if cached)
        if (hasCachedAudio) options.add("删除缓存")
        // Option: Toggle no-preprocess
        options.add(if (isNoPreprocess) "取消无需预处理" else "标记无需预处理")

        val items = options.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(episode.title)
            .setItems(items) { _, which ->
                val selected = options[which]
                when (selected) {
                    "标记不喜欢", "取消不喜欢" -> {
                        val nowDisliked = settings.toggleDislikedEpisode(requireContext(), episode)
                        Toast.makeText(context, if (nowDisliked) "已标记为不喜欢" else "已取消不喜欢", Toast.LENGTH_SHORT).show()
                        adapter?.notifyDataSetChanged()
                    }
                    "删除字幕" -> {
                        try {
                            dbHelper.deleteTranscriptsByEpisode(episode.id)
                            // Also delete leftover full PCM if exists
                            val pcmDir = com.radio.app.RadioApplication.getPcmCacheDir(requireContext())
                            val fullPcm = java.io.File(pcmDir, "${episode.id}_full.pcm")
                            if (fullPcm.exists()) fullPcm.delete()
                            val fullInfo = java.io.File(pcmDir, "${episode.id}_full.info")
                            if (fullInfo.exists()) fullInfo.delete()
                            Toast.makeText(context, "已删除字幕", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "删除字幕失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                        adapter?.notifyDataSetChanged()
                    }
                    "删除缓存" -> {
                        // v2.4.85: Delete cached audio + subtitles + PCM
                        try {
                            // Delete audio cache
                            if (audioCacheFile.exists()) {
                                audioCacheFile.delete()
                            }
                            // Delete subtitles
                            dbHelper.deleteTranscriptsByEpisode(episode.id)
                            // Delete PCM files
                            val pcmDir = com.radio.app.RadioApplication.getPcmCacheDir(requireContext())
                            val fullPcm = java.io.File(pcmDir, "${episode.id}_full.pcm")
                            if (fullPcm.exists()) fullPcm.delete()
                            val fullInfo = java.io.File(pcmDir, "${episode.id}_full.info")
                            if (fullInfo.exists()) fullInfo.delete()
                            val chunkPcm = java.io.File(pcmDir, "${episode.id}_chunk.pcm")
                            if (chunkPcm.exists()) chunkPcm.delete()
                            // Reset subtitle complete status
                            dbHelper.resetSubtitlesComplete(episode.id)
                            Toast.makeText(context, "已删除缓存(${audioCacheFile.length() / 1024 / 1024}MB)", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "删除缓存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                        adapter?.notifyDataSetChanged()
                    }
                    "标记无需预处理", "取消无需预处理" -> {
                        val nowMarked = settings.toggleNoPreprocess(requireContext(), episode.id)
                        Toast.makeText(context, if (nowMarked) "已标记无需预处理" else "已取消无需预处理", Toast.LENGTH_SHORT).show()
                        adapter?.notifyDataSetChanged()
                    }
                }
            }
            .show()
    }

    // v3.1.117: 显示播放历史弹窗
    private fun showPlayHistoryDialog() {
        val ctx = context ?: return
        val historyList = PlayHistoryUtils.getHistory(ctx)
        if (historyList.isEmpty()) {
            Toast.makeText(ctx, "暂无播放历史", Toast.LENGTH_SHORT).show()
            return
        }

        val recyclerView = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            setHasFixedSize(true)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            val maxHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeight)
        }

        val adapter = HistoryListAdapter(historyList, null)
        adapter.onItemClicked = { position ->
            val item = historyList.getOrNull(position)
            if (item != null) {
                // 启动 PlayerActivity 播放该节目
                val intent = Intent(ctx, PlayerActivity::class.java).apply {
                    putExtra("episode_id", item.episodeId)
                    putExtra("title", item.title)
                    putExtra("audio_url", item.audioUrl)
                    putExtra("station_name", item.stationName)
                    putExtra("station_id", item.stationId)
                    putExtra("broadcast_at", item.broadcastAt)
                    putExtra("duration", item.duration)
                    putExtra("program_name", item.programName ?: "")
                    // v3.1.136: 修复extra名不匹配问题——PlayerActivity用seek_position_ms
                    putExtra("seek_position_ms", item.lastPosition)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
            }
        }
        recyclerView.adapter = adapter

        AlertDialog.Builder(ctx)
            .setTitle("播放历史")
            .setView(recyclerView)
            .setNegativeButton("关闭", null)
            .show()
    }

    // v3.1.136: 播放计划对话框
    // v3.1.168-fix: 预加载分段数并显示在播放计划列表中
    private fun showPlayScheduleDialog() {
        val ctx = context ?: return
        val app = requireActivity().application as? com.radio.app.RadioApplication ?: return
        val playbackService = app.playbackService
        if (playbackService == null) {
            Toast.makeText(ctx, "播放服务未连接", Toast.LENGTH_SHORT).show()
            return
        }
        val scheduleList = playbackService.getPlaybackSchedule()
        if (scheduleList.isEmpty()) {
            Toast.makeText(ctx, "暂无后续播放计划", Toast.LENGTH_SHORT).show()
            return
        }

        // v3.1.168-fix: 提前从数据库加载所有计划节目的真实分段数
        val dbHelper = RadioDatabaseHelper.getInstance(ctx)
        val segmentCountMap = HashMap<String, Int>()
        for (ep in scheduleList) {
            if (ep.id.isNotBlank()) {
                // 优先查segment_analysis_info表
                val analysis = dbHelper.getSegmentAnalysisInfo(ep.id)
                if (analysis != null && analysis.segmentCount > 0) {
                    segmentCountMap[ep.id] = analysis.segmentCount
                } else {
                    // 回退查voiceSegments
                    val segs = dbHelper.getVoiceSegments(ep.id)
                    val realSegs = segs.filter { !it.isSimulated }
                    if (realSegs.isNotEmpty()) {
                        segmentCountMap[ep.id] = realSegs.size
                    }
                }
            }
        }

        val recyclerView = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            setHasFixedSize(true)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            val maxHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeight)
        }

        val currentId = try {
            val prefs = ctx.getSharedPreferences("last_episode", android.content.Context.MODE_PRIVATE)
            prefs.getString("episode_id", null)
        } catch (_: Exception) { null }

        val adapter = ScheduleListAdapter(scheduleList, currentId, segmentCountMap)
        adapter.onItemClicked = { position ->
            val item = scheduleList.getOrNull(position)
            if (item != null) {
                Toast.makeText(ctx, "切换到: ${item.title}", Toast.LENGTH_SHORT).show()
                val intent = android.content.Intent(ctx, PlayerActivity::class.java)
                intent.putExtra("episode_title", item.title)
                intent.putExtra("episode_id", item.id)
                intent.putExtra("episode_url", item.audioUrl)
                intent.putExtra("episode_broadcast_at", item.broadcastAt)
                intent.putExtra("episode_station_id", item.stationId)
                intent.putExtra("episode_start_time", item.startTime)
                intent.putExtra("episode_end_time", item.endTime)
                intent.putExtra("episode_duration", item.duration)
                // v3.1.168-fix: 传递真实分段数到PlayerActivity
                val segCount = segmentCountMap[item.id] ?: 0
                if (segCount > 0) {
                    intent.putExtra("episode_segment_count", segCount)
                }
                ctx.startActivity(intent)
            }
        }
        recyclerView.adapter = adapter

        AlertDialog.Builder(ctx)
            .setTitle("播放计划")
            .setView(recyclerView)
            .setNegativeButton("关闭", null)
            .show()
    }

    // v3.1.136: 播放计划列表适配器
    // v3.1.168-fix: 添加segmentCountMap参数，显示分段数
    inner class ScheduleListAdapter(
        private val scheduleItems: List<Episode>,
        var currentlyPlayingId: String?,
        private val segmentCountMap: Map<String, Int> = emptyMap()
    ) : RecyclerView.Adapter<ScheduleListAdapter.ViewHolder>() {
        var onItemClicked: ((Int) -> Unit)? = null

        private val dateFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_schedule, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = scheduleItems[position]
            val isPlaying = item.id == currentlyPlayingId
            holder.tvIndex.text = if (isPlaying) "▶" else "${position + 1}"
            holder.tvTitle.text = item.title ?: "未知节目"
            val ctx = holder.itemView.context
            holder.tvIndex.setTextColor(if (isPlaying) android.graphics.Color.parseColor("#7ED321") else ctx.resources.getColor(android.R.color.black, ctx.theme))
            holder.tvTitle.setTextColor(if (isPlaying) android.graphics.Color.parseColor("#7ED321") else ctx.resources.getColor(android.R.color.black, ctx.theme))
            val timeStr = if (item.startTime > 0) {
                dateFormat.format(java.util.Date(item.startTime))
            } else {
                item.broadcastAt?.takeLast(5) ?: ""
            }
            holder.tvTime.text = timeStr

            // v3.1.168-fix: 显示分段数
            val segCount = segmentCountMap[item.id ?: ""] ?: 0
            if (segCount > 0) {
                holder.tvDesc.text = "${segCount}个片段"
                holder.tvDesc.visibility = View.VISIBLE
            } else {
                holder.tvDesc.visibility = View.GONE
            }

            holder.itemView.setOnClickListener { onItemClicked?.invoke(position) }
        }

        override fun getItemCount(): Int = scheduleItems.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvIndex: TextView = view.findViewById(R.id.tv_index)
            val tvTitle: TextView = view.findViewById(R.id.tv_title)
            val tvTime: TextView = view.findViewById(R.id.tv_time)
            // v3.1.168-fix: 分段数显示
            val tvDesc: TextView = view.findViewById(R.id.tv_desc)
        }
    }

    // 历史列表适配器（复用 PlayerActivity 中的逻辑）
    inner class HistoryListAdapter(
        private val historyItems: List<PlayHistoryUtils.HistoryItem>,
        var currentlyPlayingId: String?
    ) : RecyclerView.Adapter<HistoryListAdapter.ViewHolder>() {
        var onItemClicked: ((Int) -> Unit)? = null

        private val dateIn = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
        private val dateOut = java.text.SimpleDateFormat("MM/dd", java.util.Locale.getDefault())

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = historyItems[position]
            val isPlaying = item.episodeId == currentlyPlayingId

            // 日期
            holder.tvDate.text = try {
                dateIn.parse(item.broadcastAt)?.let { dateOut.format(it) } ?: item.broadcastAt
            } catch (_: Exception) {
                item.broadcastAt
            }

            // 标题
            holder.tvTitle.text = if (isPlaying) "▶ ${item.title}" else item.title

            // 电台名
            holder.tvStation.text = item.stationName

            // 最后播放位置
            // v3.1.118: Episode.duration 单位是秒（API中 (endTime-beginTime)/1000），需 /60 转为分钟
            // v3.1.119: 预缓存已将节目时长入库，若duration为0则从数据库获取
            var totalSec = item.duration
            if (totalSec <= 0) {
                try {
                    val dbEp = RadioDatabaseHelper.getInstance(holder.itemView.context).getEpisodeInfo(item.episodeId)
                    if (dbEp != null && dbEp.duration > 0) {
                        totalSec = dbEp.duration
                    }
                } catch (_: Exception) {}
            }
            if (totalSec <= 0) {
                holder.tvPosition.text = "未知时长"
            } else {
                val totalMin = totalSec / 60
                if (item.lastPosition <= 0) {
                    holder.tvPosition.text = "0:00 / ${totalMin}分钟"
                } else {
                    val posMin = item.lastPosition / 60000
                    val posSec = (item.lastPosition % 60000) / 1000
                    holder.tvPosition.text = "${posMin}:${String.format("%02d", posSec)} / ${totalMin}分钟"
                }
            }

            if (isPlaying) {
                holder.tvTitle.setTypeface(null, android.graphics.Typeface.BOLD)
                holder.btnPlay.setImageResource(android.R.drawable.ic_media_pause)
            } else {
                holder.tvTitle.setTypeface(null, android.graphics.Typeface.NORMAL)
                holder.btnPlay.setImageResource(com.radio.app.R.drawable.ic_play)
            }

            holder.itemView.setOnClickListener {
                val clickPos = holder.bindingAdapterPosition
                if (clickPos >= 0 && clickPos < historyItems.size) {
                    onItemClicked?.invoke(clickPos)
                }
                // 关闭 dialog
                var parent = holder.itemView.parent
                while (parent != null) {
                    if (parent is AlertDialog) {
                        parent.dismiss()
                        break
                    }
                    parent = (parent as? View)?.parent
                }
            }
        }

        override fun getItemCount(): Int = historyItems.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvDate: TextView = view.findViewById(R.id.tv_history_date)
            val tvTitle: TextView = view.findViewById(R.id.tv_history_title)
            val tvStation: TextView = view.findViewById(R.id.tv_history_station)
            val tvPosition: TextView = view.findViewById(R.id.tv_history_position)
            val btnPlay: ImageView = view.findViewById(R.id.btn_history_play)
        }
    }
}