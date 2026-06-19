package com.radio.app.fragments

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import com.radio.app.adapters.StationAdapter
import com.radio.app.models.AppSettings
import com.radio.app.models.Episode
import com.radio.app.models.RadioStation
import com.radio.app.network.EpisodeApiService
import com.radio.app.network.RadioApiService
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class EpisodesFragment : Fragment(), EpisodeAdapter.OnEpisodeClickListener {

    private var recyclerView: RecyclerView? = null
    private var progressBar: ProgressBar? = null
    private var tvSelectedDate: TextView? = null
    private var btnSelectDate: Button? = null
    private var adapter: EpisodeAdapter? = null
    private val episodes = mutableListOf<Episode>()
    private val stations = mutableListOf<RadioStation>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val selectedDate: Calendar = Calendar.getInstance()
    private var selectedStationId: String? = null
    private var selectedStationName: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_episodes, container, false)
        recyclerView = v.findViewById(R.id.recycler_view)
        progressBar = v.findViewById(R.id.progress_bar)
        tvSelectedDate = v.findViewById(R.id.tv_selected_date)
        btnSelectDate = v.findViewById(R.id.btn_select_date)

        recyclerView?.layoutManager = LinearLayoutManager(context)

        tvSelectedDate?.text = dateFormat.format(selectedDate.time)
        btnSelectDate?.setOnClickListener { showDatePicker() }

        loadStations()
        return v
    }

    private fun showDatePicker() {
        val now = Calendar.getInstance()
        val tenYearsAgo = Calendar.getInstance().apply { add(Calendar.YEAR, -10) }

        val dialog = DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                selectedDate.set(year, month, dayOfMonth)
                tvSelectedDate?.text = dateFormat.format(selectedDate.time)
                selectedStationId?.let {
                    loadEpisodes(it, dateFormat.format(selectedDate.time))
                }
            },
            selectedDate.get(Calendar.YEAR),
            selectedDate.get(Calendar.MONTH),
            selectedDate.get(Calendar.DAY_OF_MONTH)
        )

        dialog.datePicker.minDate = tenYearsAgo.timeInMillis
        dialog.datePicker.maxDate = now.timeInMillis

        try {
            dialog.datePicker.calendarViewShown = false
            dialog.datePicker.spinnersShown = true
        } catch (e: Exception) {
            e.printStackTrace()
        }

        dialog.show()
    }

    private fun loadStations() {
        progressBar?.visibility = View.VISIBLE
        tvSelectedDate?.text = "请选择电台"
        btnSelectDate?.isEnabled = false

        stations.clear()
        stations.addAll(getBuiltinStations())

        val stationAdapter = StationAdapter(requireContext(), stations, object : StationAdapter.OnStationClickListener {
            override fun onStationClick(s: RadioStation) {
                selectedStationId = s.id
                selectedStationName = s.name
                tvSelectedDate?.text = "${s.name} | ${dateFormat.format(selectedDate.time)}"
                btnSelectDate?.isEnabled = true
                loadEpisodes(s.id, dateFormat.format(selectedDate.time))
            }

            override fun onStationLongClick(s: RadioStation) {}
        })

        recyclerView?.adapter = stationAdapter
        progressBar?.visibility = View.GONE

        RadioApiService.getInstance().getAllStations(object : RadioApiService.ApiCallback<List<RadioStation>> {
            override fun onSuccess(result: List<RadioStation>) {
                mainHandler.post {
                    stations.clear()
                    stations.addAll(result)
                    stationAdapter.notifyDataSetChanged()
                }
            }

            override fun onError(error: String) {
                // 忽略网络错误，使用内置电台
            }
        })
    }

    private fun getBuiltinStations(): List<RadioStation> {
        // 只显示支持节目回放的电台
        val data = arrayOf(
            arrayOf("henan-5", "郑州新闻广播"),
            arrayOf("henan-6", "洛阳交通广播"),
            arrayOf("other-2", "上海新闻广播"),
            arrayOf("other-3", "广东新闻广播")
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
