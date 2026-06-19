package com.radio.app.fragments

import android.content.Intent
import android.graphics.Color
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
import androidx.appcompat.app.AlertDialog
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
    private var adapter: EpisodeAdapter? = null
    private val episodes = mutableListOf<Episode>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dayFormat = SimpleDateFormat("M/d", Locale.getDefault())
    private val weekFormat = SimpleDateFormat("EEE", Locale.CHINA)
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

        recyclerView?.layoutManager = LinearLayoutManager(context)

        // 今天按钮
        v.findViewById<Button>(R.id.btn_today)?.setOnClickListener {
            selectedDate.timeInMillis = System.currentTimeMillis()
            buildDatePills()
            selectedStationId?.let {
                loadEpisodes(it, dateFormat.format(selectedDate.time))
            }
        }

        // 更新按钮
        v.findViewById<Button>(R.id.btn_refresh)?.setOnClickListener {
            selectedStationId?.let {
                loadEpisodes(it, dateFormat.format(selectedDate.time))
            }
        }

        buildDatePills()
        buildStationPills()
        return v
    }

    private fun buildDatePills() {
        dateContainer?.removeAllViews()
        dateButtons.clear()
        val today = Calendar.getInstance()
        for (i in 0 until 7) {
            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_MONTH, -i)
            }
            val isToday = cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
                    cal.get(Calendar.YEAR) == today.get(Calendar.YEAR)
            val isSelected = cal.get(Calendar.DAY_OF_YEAR) == selectedDate.get(Calendar.DAY_OF_YEAR) &&
                    cal.get(Calendar.YEAR) == selectedDate.get(Calendar.YEAR)

            val pill = TextView(context).apply {
                text = "${dayFormat.format(cal.time)}\n${weekFormat.format(cal.time)}"
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(16, 6, 16, 6)
                if (isSelected) {
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
                    marginStart = if (i > 0) 8 else 0
                    marginEnd = 4
                }
            }
            pill.setOnClickListener {
                selectedDate.timeInMillis = cal.timeInMillis
                buildDatePills()
                selectedStationId?.let {
                    loadEpisodes(it, dateFormat.format(selectedDate.time))
                }
            }
            dateContainer?.addView(pill)
            dateButtons.add(pill)
        }
    }

    private fun buildStationPills() {
        stationContainer?.removeAllViews()
        val stations = getBuiltinStations()
        stations.forEachIndexed { index, station ->
            val pill = TextView(context).apply {
                text = station.name
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(16, 6, 16, 6)
                if (index == 0) {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(getColorPrimary())
                    selectedStationId = station.id
                    selectedStationName = station.name
                } else {
                    setTextColor(Color.parseColor("#666666"))
                    setBackgroundColor(Color.parseColor("#F0F0F0"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    marginStart = if (index > 0) 8 else 0
                    marginEnd = 4
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
        val data = arrayOf(
            arrayOf("henan-1", "河南新闻广播"),
            arrayOf("henan-2", "河南音乐广播"),
            arrayOf("henan-3", "河南交通广播"),
            arrayOf("henan-4", "河南经济广播"),
            arrayOf("cnr-1", "中国之声"),
            arrayOf("cnr-2", "经济之声"),
            arrayOf("cnr-3", "音乐之声"),
            arrayOf("cnr-4", "经典音乐广播")
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
            Toast.makeText(context, "播放地址无效，该节目暂无音频资源", Toast.LENGTH_SHORT).show()
            return
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
        AlertDialog.Builder(requireContext())
            .setTitle(episode.title)
            .setItems(arrayOf("标记为不喜欢", "取消不喜欢")) { _, which ->
                if (which == 0) {
                    AppSettings.getInstance(requireContext()).addDislikedEpisode(requireContext(), episode.id)
                    Toast.makeText(context, "已标记为不喜欢", Toast.LENGTH_SHORT).show()
                } else {
                    AppSettings.getInstance(requireContext()).removeDislikedEpisode(requireContext(), episode.id)
                    Toast.makeText(context, "已取消不喜欢", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }
}
