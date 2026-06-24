package com.radio.app.adapters

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.radio.app.R
import com.radio.app.models.AppSettings
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
    private val settings = AppSettings.getInstance(ctx)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(ctx).inflate(R.layout.item_episode, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val episode = episodes[position]
        holder.tvTitle.text = episode.title

        val timeText = try {
            val date: Date? = dateIn.parse(episode.broadcastAt)
            date?.let { dateOut.format(it) } ?: episode.broadcastAt
        } catch (_: Exception) {
            episode.broadcastAt
        }
        holder.tvTime.text = timeText

        val durationMin = episode.duration / 60
        val segments = episode.voiceSegments?.size ?: 0
        holder.tvDescription.text = "${durationMin}分钟 · ${segments}片段"

        // 不喜欢状态 - 按节目名称判断（每天该节目都不喜欢）
        val isDisliked = settings.isDislikedByTitle(episode.stationId, episode.title)
        if (isDisliked) {
            holder.itemView.alpha = 0.5f
            holder.tvTitle.setTextColor(Color.parseColor("#999999"))
            holder.tvDislikeIndicator.visibility = View.VISIBLE
        } else {
            holder.itemView.alpha = 1.0f
            holder.tvTitle.setTextColor(ctx.resources.getColor(android.R.color.black, null))
            holder.tvDislikeIndicator.visibility = View.GONE
        }

        // 检查是否已缓存
        val cacheFileName = try {
            val url = java.net.URL(episode.audioUrl)
            url.path.substringAfterLast("/")
        } catch (e: Exception) {
            episode.audioUrl.substringAfterLast("/")
        }
        val cacheFile = java.io.File(ctx.cacheDir, "episodes/$cacheFileName")
        val isCached = cacheFile.exists() && cacheFile.length() > 1024
        if (isCached) {
            holder.tvCachedIndicator.visibility = View.VISIBLE
        } else {
            holder.tvCachedIndicator.visibility = View.GONE
        }

        // 是否有回放URL
        if (episode.audioUrl.isNullOrBlank()) {
            holder.btnPlay.setImageResource(android.R.drawable.ic_menu_info_details)
        } else {
            holder.btnPlay.setImageResource(android.R.drawable.ic_media_play)
        }

        holder.btnPlay.setOnClickListener { listener?.onEpisodeClick(episode) }
        holder.tvTitle.setOnClickListener { listener?.onEpisodeClick(episode) }
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
        val tvDislikeIndicator: TextView = view.findViewById(R.id.tv_dislike_indicator)
        val tvCachedIndicator: TextView = view.findViewById(R.id.tv_cached_indicator)
    }
}