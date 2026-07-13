package com.radio.app.fragments

import android.app.DatePickerDialog
import android.content.Intent
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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
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
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dayFormat = SimpleDateFormat("M/d EEE", Locale.CHINA)
    private val selectedDate: Calendar = Calendar.getInstance()
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

    private fun showDatePickerDialog() {
        val year = selectedDate.get(Calendar.YEAR)
        val month = selectedDate.get(Calendar.MONTH)
        val day = selectedDate.get(Calendar.DAY_OF_MONTH)

        val today = Calendar.getInstance()
        val maxDate = today.clone() as Calendar
        val minDate = today.clone() as Calendar
        minDate.add(Calendar.YEAR, -10)

        val dialog = DatePickerDialog(requireContext(), { _, y, m, d ->
            selectedDate.set(y, m, d)
            buildDatePills()
            selectedStationId?.let { loadEpisodes(it, dateFormat.format(selectedDate.time)) }
        }, year, month, day)

        dialog.datePicker.maxDate = maxDate.timeInMillis
        dialog.datePicker.minDate = minDate.timeInMillis
        dialog.show()
    }

    private fun buildDatePills() {
        dateContainer?.removeAllViews()
        dateButtons.clear()

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
            val isFuture = cal.timeInMillis > today.timeInMillis

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
                } else if (isFuture) {
                    setTextColor(Color.parseColor("#CCCCCC"))
                    setBackgroundColor(Color.parseColor("#F5F5F5"))
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
            pill.setOnClickListener {
                if (cal.timeInMillis > today.timeInMillis) {
                    Toast.makeText(context, "无法选择未来日期", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
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
                        // [v2.2.4] Save to DB for future lookups
                        try {
                            RadioDatabaseHelper.getInstance(requireContext()).saveEpisodeInfos(result)
                        } catch (_: Exception) {}
                        mainHandler.post {
                            progressBar?.visibility = View.GONE
                            episodes.clear()
                            episodes.addAll(result)
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
            java.io.FileWriter(logFile, true).use { it.append("[$ts][v2.0.43] [EPISODE] EpisodesFragment.onEpisodeClick: BEFORE click - target='${episode.title}', id=${episode.id}, url=$audioUrl\n") }
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
}