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

        // 今天按钮
        v.findViewById<Button>(R.id.btn_today)?.setOnClickListener {
            selectedDate.timeInMillis = System.currentTimeMillis()
            buildDatePills()
            selectedStationId?.let { loadEpisodes(it, dateFormat.format(selectedDate.time)) }
        }

        // 日期选择器按钮
        v.findViewById<Button>(R.id.btn_date_picker)?.setOnClickListener {
            showDatePickerDialog()
        }

        // 更新按钮
        v.findViewById<Button>(R.id.btn_refresh)?.setOnClickListener {
            selectedStationId?.let { loadEpisodes(it, dateFormat.format(selectedDate.time)) }
        }

        buildDatePills()
        buildStationPills()
        return v
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

        // 更新顶部日期显示
        tvSelectedDate?.text = "${selectedDate.get(Calendar.YEAR)}年${selectedDate.get(Calendar.MONTH) + 1}月${selectedDate.get(Calendar.DAY_OF_MONTH)}日"

        // 以用户选择的日期为中心，显示前后各7天（共15天）
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
    }

    private fun buildStationPills() {
        stationContainer?.removeAllViews()
        val settings = AppSettings.getInstance(requireContext())
        val stations = getBuiltinStations()

        // 按播放次数排序（经常播放的在前）
        val sortedStations = stations.sortedByDescending { settings.getStationPlayCount(it.id) }

        sortedStations.forEachIndexed { index, station ->
            val pill = TextView(context).apply {
                text = station.name
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(14, 6, 14, 6)
                if (index == 0 && selectedStationId == null) {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(getColorPrimary())
                    selectedStationId = station.id
                    selectedStationName = station.name
                } else if (station.id == selectedStationId) {
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
        // 默认加载第一个电台的节目
        if (selectedStationId != null) {
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
        // 仅包含河南广播网11个电台（支持回放）
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

    private fun loadEpisodes(stationId: String, dateStr: String) {
        progressBar?.visibility = View.VISIBLE
        adapter = EpisodeAdapter(requireContext(), episodes, this)
        recyclerView?.adapter = adapter

        EpisodeApiService.getInstance().getEpisodesByDate(stationId, dateStr,
            object : EpisodeApiService.ApiCallback<List<Episode>> {
                override fun onSuccess(result: List<Episode>) {
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
                        Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                    }
                }
            })
    }

    override fun onEpisodeClick(episode: Episode) {
        val audioUrl = episode.audioUrl
        if (audioUrl.isNullOrBlank()) {
            Toast.makeText(context, "今日节目暂无回放音频，请选择过往日期", Toast.LENGTH_SHORT).show()
            return
        }

        // 记录播放习惯
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
        }
        startActivity(intent)
    }

    override fun onEpisodeLongClick(episode: Episode) {
        val settings = AppSettings.getInstance(requireContext())
        val isNowDisliked = settings.toggleDislikedEpisode(requireContext(), episode.id)
        if (isNowDisliked) {
            Toast.makeText(context, "已标记为不喜欢: ${episode.title}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "已取消不喜欢: ${episode.title}", Toast.LENGTH_SHORT).show()
        }
        // 刷新列表以更新视觉状态
        adapter?.notifyDataSetChanged()
    }
}