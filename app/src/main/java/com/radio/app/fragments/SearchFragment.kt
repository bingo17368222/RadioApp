package com.radio.app.fragments

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.radio.app.R
import com.radio.app.activities.PlayerActivity
import com.radio.app.adapters.SearchResultAdapter
import com.radio.app.database.RadioDatabaseHelper
import com.radio.app.models.Episode
import com.radio.app.models.SearchResult
import com.radio.app.models.Transcript
import com.radio.app.network.EpisodeApiService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SearchFragment : Fragment(), SearchResultAdapter.OnSearchResultClickListener {

    private var etSearch: EditText? = null
    private var recyclerView: RecyclerView? = null
    private var adapter: SearchResultAdapter? = null
    private val results = mutableListOf<SearchResult>()
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null

    // [v2.2.1] Cache episode list from SharedPreferences for title lookup
    private var episodeCache: List<Episode> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_search, container, false)
        etSearch = view.findViewById(R.id.et_search)
        recyclerView = view.findViewById(R.id.recycler_view)
        adapter = SearchResultAdapter(requireContext(), results, this)
        recyclerView?.layoutManager = LinearLayoutManager(requireContext())
        recyclerView?.adapter = adapter
        etSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                debounceSearch(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        loadEpisodeCache()
        return view
    }

    override fun onResume() {
        super.onResume()
        loadEpisodeCache()
    }

    // [v2.2.1] Load episode list from cache for title lookup
    private fun loadEpisodeCache() {
        try {
            val json = requireContext().getSharedPreferences("episode_list_cache", android.content.Context.MODE_PRIVATE)
                .getString("episodes", "") ?: ""
            if (json.isNotEmpty()) {
                val gson = Gson()
                val type = object : TypeToken<List<Episode>>() {}.type
                episodeCache = gson.fromJson(json, type) ?: emptyList()
            }
        } catch (_: Exception) {}
    }

    private fun debounceSearch(query: String) {
        debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
        debounceRunnable = Runnable { search(query) }
        debounceHandler.postDelayed(debounceRunnable!!, 300)
    }

    // [v2.1.8] Parse episodeId: henan-private-car-2024-07-12-2
    private data class EpisodeIdInfo(
        val stationId: String, val date: String, val index: Int, val timeSlot: String
    )

    private fun parseEpisodeId(epId: String): EpisodeIdInfo? {
        val dateRegex = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
        val dateMatch = dateRegex.find(epId) ?: return null
        val date = dateMatch.value
        val stationId = epId.substring(0, dateMatch.range.first).trimEnd('-')
        val afterDate = epId.substring(dateMatch.range.last + 1).trimStart('-')
        val index = afterDate.toIntOrNull() ?: -1
        val timeSlots = listOf("0700_0900", "0900_1000", "1000_1200", "1200_1400",
            "1400_1600", "1600_1800", "1700_1900", "1900_2100", "2100_2300", "2300_0100")
        val timeSlot = if (index in timeSlots.indices) timeSlots[index] else "0700_0900"
        return EpisodeIdInfo(stationId, date, index, timeSlot)
    }

    private fun constructAudioUrl(info: EpisodeIdInfo): String {
        val urlDate = info.date.replace("-", "")
        val stationPart = when (info.stationId) {
            "henan-news" -> "xinwen"
            "henan-economy" -> "jingji"
            "henan-traffic" -> "jiaotong"
            "henan-opera" -> "xiqu"
            "henan-music" -> "yinyue"
            "henan-rural" -> "xinwen"
            "henan-myradio" -> "myradio"
            "henan-private-car" -> "sijiache"
            "henan-edu" -> "jiaoyu"
            "henan-info" -> "xinxi"
            "henan-bigradio" -> "bigradio"
            else -> "sijiache"
        }
        return "https://new-file.hntv.tv/bdmz/data/new_record/jmd_$urlDate/${stationPart}_${urlDate}_${info.timeSlot}.mp4"
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    // [v2.2.1] Format time slot for display: "0700_0900" -> "07:00-09:00"
    private fun formatTimeSlot(slot: String): String {
        val parts = slot.split("_")
        if (parts.size != 2) return slot
        val start = parts[0]
        val end = parts[1]
        return "${start.take(2)}:${start.drop(2)}-${end.take(2)}:${end.drop(2)}"
    }

    // [v2.2.1] Calculate episode duration from time slot
    // "0700_0900" -> 2 hours = 7200000 ms
    private fun calculateEpisodeDurationMs(timeSlot: String): Long {
        val parts = timeSlot.split("_")
        if (parts.size != 2) return 0L
        val startHour = parts[0].take(2).toIntOrNull() ?: return 0L
        val startMin = parts[0].drop(2).toIntOrNull() ?: return 0L
        val endHour = parts[1].take(2).toIntOrNull() ?: return 0L
        val endMin = parts[1].drop(2).toIntOrNull() ?: return 0L
        val startTotalMin = startHour * 60 + startMin
        val endTotalMin = endHour * 60 + endMin
        // Handle cross-midnight: 2300_0100
        val diffMin = if (endTotalMin > startTotalMin) endTotalMin - startTotalMin else (24 * 60 - startTotalMin) + endTotalMin
        return diffMin * 60 * 1000L
    }

    // [v2.2.1] Look up episode title from cache
    private fun findEpisodeTitle(epId: String): String? {
        return episodeCache.firstOrNull { it.id == epId }?.title
    }

    private fun search(q: String) {
        if (q.isEmpty()) {
            results.clear()
            adapter?.notifyDataSetChanged()
            return
        }
        Thread {
            try {
                val dbHelper = RadioDatabaseHelper.getInstance(requireContext())
                val transcripts = dbHelper.searchTranscripts(q)
                val searchResults = mutableListOf<SearchResult>()

                for (t in transcripts) {
                    val epId = t.episodeId ?: continue
                    val info = parseEpisodeId(epId)
                    if (info == null) {
                        val r = SearchResult().apply {
                            id = epId
                            title = epId
                            type = "transcript"
                            stationName = "未知电台"
                            this.matchedText = t.text?.take(60) ?: ""
                            this.transcript = t
                        }
                        searchResults.add(r)
                        continue
                    }

                    val stationName = EpisodeApiService.getStationName(info.stationId)

                    // [v2.2.1] Try to find actual episode title from cache
                    val episodeTitle = findEpisodeTitle(epId)
                    // [v2.2.1] Build title: episode title if found, otherwise station name + date
                    val title = episodeTitle ?: "$stationName ${info.date}"

                    // [v2.2.1] Extract matched text snippet
                    val fullText = t.text ?: ""
                    val queryIdx = fullText.indexOf(q, ignoreCase = true)
                    val matchedText = if (queryIdx >= 0) {
                        val start = maxOf(0, queryIdx - 20)
                        val end = minOf(fullText.length, queryIdx + q.length + 20)
                        (if (start > 0) "..." else "") + fullText.substring(start, end) + (if (end < fullText.length) "..." else "")
                    } else {
                        fullText.take(60) + if (fullText.length > 60) "..." else ""
                    }

                    // [v2.2.1] Calculate episode duration from time slot (not PCM 5-min duration)
                    val episodeDurationMs = calculateEpisodeDurationMs(info.timeSlot)
                    val totalDurationStr = if (episodeDurationMs > 0) formatTime(episodeDurationMs) else "未知"
                    val playPosStr = formatTime(t.segmentStart)
                    val timeSlotDisplay = formatTimeSlot(info.timeSlot)
                    // [v2.2.1] Display: station | time slot | episode duration | playback position
                    val displayStation = "$stationName | $timeSlotDisplay | 总时长: $totalDurationStr | 位置: $playPosStr"

                    val r = SearchResult().apply {
                        id = epId
                        this.title = title
                        type = "transcript"
                        this.stationName = displayStation
                        this.matchedText = matchedText
                        this.transcript = t
                    }
                    searchResults.add(r)
                }

                if (activity == null) return@Thread
                requireActivity().runOnUiThread {
                    results.clear()
                    results.addAll(searchResults)
                    adapter?.notifyDataSetChanged()
                    if (searchResults.isEmpty() && q.isNotEmpty()) {
                        Toast.makeText(requireContext(), "未找到包含\"$q\"的字幕", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (activity == null) return@Thread
                requireActivity().runOnUiThread {
                    results.clear()
                    adapter?.notifyDataSetChanged()
                }
            }
        }.start()
    }

    // [v2.2.1] Click search result: switch to the target episode and seek
    override fun onSearchResultClick(r: SearchResult) {
        val t = r.transcript ?: return
        val epId = t.episodeId ?: return

        val info = parseEpisodeId(epId)
        if (info == null) {
            Toast.makeText(requireContext(), "无法解析节目信息: $epId", Toast.LENGTH_SHORT).show()
            return
        }

        val stationName = EpisodeApiService.getStationName(info.stationId)
        val audioUrl = constructAudioUrl(info)
        val episodeTitle = findEpisodeTitle(epId) ?: "$stationName ${info.date}"

        val e = Episode().apply {
            id = epId
            title = episodeTitle
            this.stationId = info.stationId
            this.stationName = stationName
            this.audioUrl = audioUrl
            isLive = false
        }

        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("episode", e)
            putExtra("seek_position_ms", t.segmentStart)
            putExtra("force_episode_switch", true)
            putExtra("target_episode_id", epId)
        }
        startActivity(intent)
    }
}
