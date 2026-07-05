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
import com.radio.app.activities.MainActivity
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

    // [v2.1.5] Search local transcripts database instead of mock API data
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
                    // Extract stationId from episodeId (format: henan-private-car-2024-07-11-2)
                    val parts = epId.split("-")
                    val stationId = if (parts.size >= 3) {
                        parts.subList(0, parts.size - 3).joinToString("-")
                    } else {
                        epId
                    }
                    val stationName = EpisodeApiService.getStationName(stationId)

                    // Extract date from episodeId
                    val dateStr = if (parts.size >= 3) {
                        "${parts[parts.size - 3]}-${parts[parts.size - 2]}-${parts[parts.size - 1]}"
                    } else {
                        ""
                    }

                    // Build episode title from station name and date
                    val title = "$stationName $dateStr"

                    // Extract the matched text snippet (surrounding context)
                    val fullText = t.text ?: ""
                    val queryIdx = fullText.indexOf(q, ignoreCase = true)
                    val matchedText = if (queryIdx >= 0) {
                        val start = maxOf(0, queryIdx - 20)
                        val end = minOf(fullText.length, queryIdx + q.length + 20)
                        (if (start > 0) "..." else "") + fullText.substring(start, end) + (if (end < fullText.length) "..." else "")
                    } else {
                        fullText.take(60) + if (fullText.length > 60) "..." else ""
                    }

                    val r = SearchResult().apply {
                        id = epId
                        this.title = title
                        type = "transcript"
                        this.stationName = stationName
                        this.matchedText = matchedText
                        this.transcript = t
                    }
                    searchResults.add(r)
                }

                // Update UI on main thread
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

    // [v2.1.5] Click search result to play the episode at the transcript's timestamp
    override fun onSearchResultClick(r: SearchResult) {
        val t = r.transcript ?: return
        val epId = t.episodeId ?: return

        // Build Episode object from episodeId
        val parts = epId.split("-")
        val stationId = if (parts.size >= 3) {
            parts.subList(0, parts.size - 3).joinToString("-")
        } else {
            epId
        }
        val dateStr = if (parts.size >= 3) {
            "${parts[parts.size - 3]}-${parts[parts.size - 2]}-${parts[parts.size - 1]}"
        } else {
            ""
        }
        val stationName = EpisodeApiService.getStationName(stationId)

        // Construct audio URL from episodeId pattern
        // episodeId format: henan-private-car-2024-07-11-2
        // URL pattern: https://new-file.hntv.tv/bdmz/data/new_record/jmd_YYYYMMDD/sijiache_YYYYMMDD_HH00_HH00.mp4
        val urlDateStr = dateStr.replace("-", "")
        val timeSlots = listOf("0700_0900", "0900_1000", "1000_1200", "1200_1400", "1400_1600", "1600_1800", "1700_1900", "1900_2100", "2100_2300", "2300_0100")
        val idx = parts.lastOrNull()?.toIntOrNull() ?: 0
        val timeSlot = if (idx < timeSlots.size) timeSlots[idx] else "0700_0900"
        val stationPart = when (stationId) {
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
        val audioUrl = "https://new-file.hntv.tv/bdmz/data/new_record/jmd_$urlDateStr/${stationPart}_$urlDateStr _$timeSlot.mp4".replace(" ", "")

        val e = Episode().apply {
            id = epId
            title = "$stationName $dateStr"
            this.stationId = stationId
            this.stationName = stationName
            this.audioUrl = audioUrl
            isLive = false
        }

        // Start PlayerActivity with seek position
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("episode", e)
            putExtra("seek_position_ms", t.segmentStart)
        }
        startActivity(intent)
    }
}
