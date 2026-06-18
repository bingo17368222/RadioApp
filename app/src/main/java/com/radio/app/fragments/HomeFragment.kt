package com.radio.app.fragments

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.radio.app.R
import com.radio.app.activities.PlayerActivity
import com.radio.app.adapters.StationAdapter
import com.radio.app.models.RadioStation
import com.radio.app.network.RadioApiService

class HomeFragment : Fragment(), StationAdapter.OnStationClickListener {

    private var recyclerView: RecyclerView? = null
    private var progressBar: ProgressBar? = null
    private var adapter: StationAdapter? = null
    private val stations = mutableListOf<RadioStation>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_home, container, false)
        recyclerView = v.findViewById(R.id.recycler_view)
        progressBar = v.findViewById(R.id.progress_bar)

        recyclerView?.layoutManager = LinearLayoutManager(context)
        adapter = StationAdapter(context, stations, this)
        recyclerView?.adapter = adapter
        loadStations()
        return v
    }

    private fun loadStations() {
        progressBar?.visibility = View.VISIBLE
        RadioApiService.getInstance().getAllStations(object : RadioApiService.ApiCallback<List<RadioStation>> {
            override fun onSuccess(result: List<RadioStation>) {
                mainHandler.post {
                    progressBar?.visibility = View.GONE
                    stations.clear()
                    stations.addAll(result)
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

    override fun onStationClick(s: RadioStation) {
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("station_id", s.id)
            putExtra("station_name", s.name)
            putExtra("stream_url", s.streamUrl)
            putExtra("is_live", true)
        }
        startActivity(intent)
    }

    override fun onStationLongClick(s: RadioStation) {
        // 长按可添加收藏等操作
    }
}
