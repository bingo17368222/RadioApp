package com.radio.app.fragments

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
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

    // [v2.2.3] Cache: episodeId -> Episode (for title + audioUrl lookup)
    private val episodeCacheMap = mutableMapOf<String, Episode>()

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

    private fun loadEpisodeCache() {
        try {
            val json = requireContext().getSharedPreferences("episode_list_cache", android.content.Context.MODE_PRIVATE)
                .getString("episodes", "") ?: ""
            if (json.isNotEmpty()) {
                val gson = Gson()
                val type = object : TypeToken<List<Episode>>() {}.type
                val list: List<Episode> = gson.fromJson(json, type) ?: emptyList()
                episodeCacheMap.clear()
                list.forEach { episodeCacheMap[it.id ?: ""] = it }
            }
        } catch (_: Exception) {}
    }

    private fun debounceSearch(query: String) {
        debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
        debounceRunnable = Runnable { search(query) }
        debounceHandler.postDelayed(debounceRunnable!!, 300)
    }

    private data class EpisodeIdInfo(
        val stationId: String, val date: String, val index: Int
    )

    private fun parseEpisodeId(epId: String): EpisodeIdInfo? {
        val dateRegex = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
        val dateMatch = dateRegex.find(epId) ?: return null
        val date = dateMatch.value
        val stationId = epId.substring(0, dateMatch.range.first).trimEnd('-')
        val afterDate = epId.substring(dateMatch.range.last + 1).trimStart('-')
        val index = afterDate.toIntOrNull() ?: -1
        return EpisodeIdInfo(stationId, date, index)
    }

    // [v2.2.3] Fetch episode from API by episodeId (synchronous, runs in background thread)
    // This replaces the broken hardcoded URL construction
    private fun fetchEpisodeFromApi(epId: String): Episode? {
        // Check cache first
        episodeCacheMap[epId]?.let { return it }

        val info = parseEpisodeId(epId) ?: return null
        try {
            val episodes = EpisodeApiService.fetchEpisodesByDateSync(info.stationId, info.date)
            if (episodes != null) {
                // Save to cache for future lookups
                episodes.forEach { e ->
                    e.id?.let { id -> episodeCacheMap[id] = e }
                }
                return episodes.firstOrNull { it.id == epId }
            }
        } catch (e: Exception) {
            Log.e("SearchFragment", "fetchEpisodeFromApi failed for $epId", e)
        }
        return null
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    // [v2.2.3] Format time slot from broadcast_at field (format: "2024-07-15T10:00:00")
    private fun formatTimeSlotFromEpisode(ep: Episode): String {
        val bat = ep.broadcastAt
        if (bat.isBlank()) return ""
        try {
            // Parse "2024-07-15T10:00:00" -> extract time part
            val timePart = bat.substringAfter("T") // "10:00:00"
            val startH = timePart.substring(0, 2).toIntOrNull() ?: return ""
            val startM = timePart.substring(3, 5).toIntOrNull() ?: return ""
            // Calculate end time from duration
            val durMs = ep.duration
            val durHours = if (durMs > 0) durMs / 3600000.0 else 2.0
            val startTotalMin = startH * 60 + startM
            val endTotalMin = startTotalMin + (durHours * 60).toInt()
            val endH = (endTotalMin / 60) % 24
            val endM = endTotalMin % 60
            return String.format("%02d:%02d-%02d:%02d", startH, startM, endH, endM)
        } catch (_: Exception) {
            return ""
        }
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

                    // [v2.2.3] Fetch real episode from API (with cache)
                    val episode = fetchEpisodeFromApi(epId)
                    val stationName = episode?.stationName
                        ?: EpisodeApiService.getStationName(parseEpisodeId(epId)?.stationId ?: "")
                    val title = episode?.title ?: "$stationName ${parseEpisodeId(epId)?.date ?: ""}"
                    val audioUrl = episode?.audioUrl ?: ""

                    // [v2.2.3] Extract matched text snippet
                    val fullText = t.text ?: ""
                    val queryIdx = fullText.indexOf(q, ignoreCase = true)
                    val matchedText = if (queryIdx >= 0) {
                        val start = maxOf(0, queryIdx - 20)
                        val end = minOf(fullText.length, queryIdx + q.length + 20)
                        (if (start > 0) "..." else "") + fullText.substring(start, end) + (if (end < fullText.length) "..." else "")
                    } else {
                        fullText.take(60) + if (fullText.length > 60) "..." else ""
                    }

                    // [v2.2.3] Build display info from real episode data
                    val timeSlotDisplay = if (episode != null) formatTimeSlotFromEpisode(episode) else ""
                    val totalDurationMs = episode?.duration ?: 0L
                    val totalDurationStr = if (totalDurationMs > 0) formatTime(totalDurationMs) else "未知"
                    val playPosStr = formatTime(t.segmentStart)
                    val displayStation = if (timeSlotDisplay.isNotEmpty()) {
                        "$stationName | $timeSlotDisplay | 总时长: $totalDurationStr | 位置: $playPosStr"
                    } else {
                        "$stationName | 总时长: $totalDurationStr | 位置: $playPosStr"
                    }

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

    // [v2.2.3] Click search result: switch to the target episode and seek
    override fun onSearchResultClick(r: SearchResult) {
        val t = r.transcript ?: return
        val epId = t.episodeId ?: return

        // [v2.2.3] Fetch real episode from API to get correct audioUrl
        val episode = fetchEpisodeFromApi(epId)
        if (episode == null || episode.audioUrl.isBlank()) {
            Toast.makeText(requireContext(), "无法获取节目信息: $epId", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("episode", episode)
            putExtra("seek_position_ms", t.segmentStart)
            putExtra("force_episode_switch", true)
            putExtra("target_episode_id", epId)
        }
        startActivity(intent)
    }
}
