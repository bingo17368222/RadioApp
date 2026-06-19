package com.radio.app.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.radio.app.R
import com.radio.app.models.Episode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EpisodeAdapter(
    private val ctx: Context,
    private val episodes: List<Episode>,
    private val listener: OnEpisodeClickListener?
) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    interface OnEpisodeClickListener {
        fun onEpisodeClick(e: Episode)
        fun onEpisodeLongClick(e: Episode)
    }

    private val dateIn = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
    private val dateOut = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(ctx).inflate(R.layout.item_episode, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val episode = episodes[position]
        holder.tvTitle.text = episode.title
        holder.tvDescription.text = episode.description

        val timeText = try {
            val date: Date? = dateIn.parse(episode.broadcastAt)
            date?.let { dateOut.format(it) } ?: episode.broadcastAt
        } catch (_: Exception) {
            episode.broadcastAt
        }
        holder.tvTime.text = timeText

        // 时长·片段数
        val durationMin = episode.duration / 60
        val segments = episode.voiceSegments?.size ?: 0
        holder.tvDescription.text = "${durationMin}分钟 · ${segments}片段"

        holder.btnPlay.setOnClickListener { listener?.onEpisodeClick(episode) }
        holder.itemView.setOnLongClickListener {
            listener?.onEpisodeLongClick(episode)
            true
        }
    }

    override fun getItemCount(): Int = episodes.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(R.id.tv_time)
        val tvTitle: TextView = view.findViewById(R.id.tv_title)
        val tvDescription: TextView = view.findViewById(R.id.tv_description)
        val btnPlay: ImageView = view.findViewById(R.id.btn_play)
    }
}
