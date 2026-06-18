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
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.radio.app.R
import com.radio.app.activities.MainActivity
import com.radio.app.activities.PlayerActivity
import com.radio.app.adapters.SearchResultAdapter
import com.radio.app.models.Episode
import com.radio.app.models.SearchResult
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

    private fun search(q: String) {
        if (q.isEmpty()) {
            results.clear()
            adapter?.notifyDataSetChanged()
            return
        }
        EpisodeApiService.getInstance().search(q, object : EpisodeApiService.ApiCallback<List<SearchResult>> {
            override fun onSuccess(searchResults: List<SearchResult>) {
                if (activity == null) return
                results.clear()
                results.addAll(searchResults)
                adapter?.notifyDataSetChanged()
            }

            override fun onError(error: String) {
                if (activity == null) return
                results.clear()
                adapter?.notifyDataSetChanged()
            }
        })
    }

    override fun onSearchResultClick(r: SearchResult) {
        val e = Episode().apply {
            id = r.id ?: ""
            title = r.title ?: ""
            stationName = r.stationName ?: ""
            audioUrl = "https://example.com/audio/${r.id}.mp3"
            isLive = false
        }

        (activity as? MainActivity)?.let { a ->
            if (a.isServiceBound && a.playbackService != null) {
                a.playbackService?.playEpisode(e, false)
            }
        }
        startActivity(Intent(context, PlayerActivity::class.java))
    }
}
