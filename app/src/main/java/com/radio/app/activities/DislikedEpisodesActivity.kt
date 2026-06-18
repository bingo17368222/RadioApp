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
import com.radio.app.database.RadioDatabaseHelper

class DislikedEpisodesActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: DislikedAdapter
    private lateinit var dbHelper: RadioDatabaseHelper
    private val dislikedList = mutableListOf<Array<String>>()
    private lateinit var tvTitle: TextView
    private lateinit var btnBack: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_disliked_episodes)

        tvTitle = findViewById(R.id.tv_title)
        btnBack = findViewById(R.id.btn_back)
        tvTitle.text = "不喜欢的节目"
        btnBack.setOnClickListener { finish() }

        dbHelper = RadioDatabaseHelper.getInstance(this)
        recyclerView = findViewById(R.id.recycler_view)
        adapter = DislikedAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        loadDislikedEpisodes()
    }

    private fun loadDislikedEpisodes() {
        dislikedList.clear()
        val db = dbHelper.readableDatabase
        val cursor = db.query("disliked_episodes", null, null, null, null, null, "created_at DESC")
        while (cursor.moveToNext()) {
            val id = cursor.getString(0)
            val title = cursor.getString(1) ?: ""
            val station = cursor.getString(2) ?: ""
            dislikedList.add(arrayOf(id, title, station))
        }
        cursor.close()
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
            holder.tvTitle.text = item[1]
            holder.tvStation.text = item[2]
            holder.btnRemove.setOnClickListener {
                dbHelper.removeDislikedEpisode(item[0])
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
