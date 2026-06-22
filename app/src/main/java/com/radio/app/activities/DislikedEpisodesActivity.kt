package com.radio.app.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.radio.app.R
import com.radio.app.models.AppSettings

class DislikedEpisodesActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: DislikedAdapter
    private lateinit var settings: AppSettings
    private val dislikedList = mutableListOf<Pair<String, String>>()
    private lateinit var tvTitle: TextView
    private lateinit var btnBack: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_disliked_episodes)

        tvTitle = findViewById(R.id.tv_title)
        btnBack = findViewById(R.id.btn_back)
        tvTitle.text = "不喜欢的节目"
        btnBack.setOnClickListener { finish() }

        settings = AppSettings.getInstance(this)
        recyclerView = findViewById(R.id.recycler_view)
        adapter = DislikedAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        loadDislikedEpisodes()
    }

    private fun loadDislikedEpisodes() {
        dislikedList.clear()
        // 从 AppSettings 读取不喜欢列表（格式: "stationId::title"）
        for (entry in settings.dislikedEpisodes) {
            val parts = entry.split("::")
            if (parts.size >= 2) {
                val stationId = parts[0]
                val title = parts[1]
                val stationName = com.radio.app.network.EpisodeApiService.getStationName(stationId)
                dislikedList.add(Pair(entry, "$title ($stationName)"))
            } else {
                dislikedList.add(Pair(entry, entry))
            }
        }
        adapter.notifyDataSetChanged()

        val tvEmpty = findViewById<View>(R.id.tv_empty)
        tvEmpty?.visibility = if (dislikedList.isEmpty()) View.VISIBLE else View.GONE
    }

    inner class DislikedAdapter : RecyclerView.Adapter<DislikedAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_disliked_episode, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = dislikedList[position]
            val key = item.first
            val displayText = item.second
            holder.tvTitle.text = displayText
            holder.tvStation.text = if (displayText.contains("(")) {
                displayText.substringAfterLast("(").removeSuffix(")")
            } else ""
            holder.btnRemove.setOnClickListener {
                settings.removeDislikedEpisode(this@DislikedEpisodesActivity, key)
                dislikedList.removeAt(position)
                notifyDataSetChanged()
                Toast.makeText(this@DislikedEpisodesActivity, "已取消不喜欢", Toast.LENGTH_SHORT).show()
                if (dislikedList.isEmpty()) {
                    val tvEmpty = findViewById<View>(R.id.tv_empty)
                    tvEmpty?.visibility = View.VISIBLE
                }
            }
        }

        override fun getItemCount(): Int = dislikedList.size

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tv_title)
            val tvStation: TextView = v.findViewById(R.id.tv_station)
            val btnRemove: Button = v.findViewById(R.id.btn_remove)
        }
    }
}