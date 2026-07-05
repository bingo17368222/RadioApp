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

class SearchFragment : Fragment(), SearchResultAdapter.OnSearchResultClickListener {

    private var etSearch: EditText? = null
    private var recyclerView: RecyclerView? = null
    private var adapter: SearchResultAdapter? = null
    private val results = mutableListOf<SearchResult>()
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null

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
        return view
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

    // [v2.1.8] Format time slot for display: "0700_0900" -> "07:00-09:00"
    private fun formatTimeSlot(slot: String): String {
        val parts = slot.split("_")
        if (parts.size != 2) return slot
        val start = parts[0]
        val end = parts[1]
        return "${start.take(2)}:${start.drop(2)}-${end.take(2)}:${end.drop(2)}"
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

                // [v2.1.8] Cache episode info to avoid repeated DB queries
                val episodeInfoCache = mutableMapOf<String, Pair<Long, Long>?>()

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

                    // [v2.1.8] Get episode transcript duration info
                    val episodeDuration = episodeInfoCache.getOrPut(epId) {
                        dbHelper.getEpisodeTranscriptInfo(epId)
                    }
                    val firstMs = episodeDuration?.first ?: 0L
                    val lastMs = episodeDuration?.second ?: 0L
                    val totalDurationMs = if (lastMs > firstMs) lastMs - firstMs else 0L

                    // [v2.1.8] Build comprehensive title: station name + date
                    val title = "$stationName ${info.date}"

                    // [v2.1.8] Extract matched text snippet
                    val fullText = t.text ?: ""
                    val queryIdx = fullText.indexOf(q, ignoreCase = true)
                    val matchedText = if (queryIdx >= 0) {
                        val start = maxOf(0, queryIdx - 20)
                        val end = minOf(fullText.length, queryIdx + q.length + 20)
                        (if (start > 0) "..." else "") + fullText.substring(start, end) + (if (end < fullText.length) "..." else "")
                    } else {
                        fullText.take(60) + if (fullText.length > 60) "..." else ""
                    }

                    // [v2.1.8] Build display: station | time slot | total duration | playback position
                    val timeSlotDisplay = formatTimeSlot(info.timeSlot)
                    val totalDurationStr = if (totalDurationMs > 0) formatTime(totalDurationMs) else "未知"
                    val playPosStr = formatTime(t.segmentStart)
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

    // [v2.1.8] Click search result: switch to the target episode and seek
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

        val e = Episode().apply {
            id = epId
            title = "$stationName ${info.date}"
            this.stationId = info.stationId
            this.stationName = stationName
            this.audioUrl = audioUrl
            isLive = false
        }

        // [v2.1.8] Pass episode_id AND audio_url for proper episode switching.
        // PlayerActivity will compare episode_id with current playing episode.
        // If different, it will call playEpisode to switch; if same, just seek.
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("episode", e)
            putExtra("seek_position_ms", t.segmentStart)
            // [v2.1.8] Flag to force episode switch even if URL doesn't match exactly
            putExtra("force_episode_switch", true)
            putExtra("target_episode_id", epId)
        }
        startActivity(intent)
    }
}
