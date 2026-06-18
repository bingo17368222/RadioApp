package com.radio.app.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.TextView
import androidx.cardview.widget.CardView
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

    fun interface OnEpisodeClickListener {
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
        holder.tvStation.text = episode.stationName

        val timeText = try {
            val date: Date? = dateIn.parse(episode.broadcastAt)
            date?.let { dateOut.format(it) } ?: episode.broadcastAt
        } catch (_: Exception) {
            episode.broadcastAt
        }
        holder.tvTime.text = timeText

        holder.tvDuration.text = String.format(
            Locale.getDefault(),
            "%02d:%02d",
            episode.duration / 60,
            episode.duration % 60
        )

        // Live indicator with blinking animation
        if (episode.isLive) {
            holder.tvLive.visibility = View.VISIBLE
            holder.tvLive.text = "LIVE"
            holder.tvLive.setBackgroundResource(R.drawable.bg_live_indicator)
            val blink = AlphaAnimation(1.0f, 0.3f).apply {
                duration = 800
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            }
            holder.tvLive.startAnimation(blink)
        } else {
            holder.tvLive.visibility = View.GONE
            holder.tvLive.clearAnimation()
        }

        // Disliked episode - reduce opacity
        holder.card.alpha = if (episode.isDisliked) 0.5f else 1.0f

        holder.card.setOnClickListener { listener?.onEpisodeClick(episode) }
        holder.card.setOnLongClickListener {
            listener?.onEpisodeLongClick(episode)
            true
        }
    }

    override fun getItemCount(): Int = episodes.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: CardView = view.findViewById(R.id.card_view)
        val tvTitle: TextView = view.findViewById(R.id.tv_title)
        val tvDescription: TextView = view.findViewById(R.id.tv_description)
        val tvStation: TextView = view.findViewById(R.id.tv_station)
        val tvTime: TextView = view.findViewById(R.id.tv_time)
        val tvDuration: TextView = view.findViewById(R.id.tv_duration)
        val tvLive: TextView = view.findViewById(R.id.tv_live)
    }
}
