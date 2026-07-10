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

    // [v2.0.43] Issue 4: Currently playing episode ID for highlight
    var currentlyPlayingId: String? = null
    var currentlyPlayingUrl: String? = null

    private val dateIn = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
    private val dateOut = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val settings = AppSettings.getInstance(ctx)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(ctx).inflate(R.layout.item_episode, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val episode = episodes[position]
        // [v2.0.43] Issue 4: Highlight currently playing episode
        val isPlaying = episode.id == currentlyPlayingId || episode.audioUrl == currentlyPlayingUrl

        holder.tvTitle.text = if (isPlaying) "▶ ${episode.title}" else episode.title

        val timeText = try {
            val date: Date? = dateIn.parse(episode.broadcastAt)
            date?.let { dateOut.format(it) } ?: episode.broadcastAt
        } catch (_: Exception) {
            episode.broadcastAt
        }
        holder.tvTime.text = timeText

        val durationMin = episode.duration / 60
        // v2.4.44: Check DB for segment count if not loaded in memory
        var segments = episode.voiceSegments?.size ?: 0
        if (segments == 0) {
            // Try loading from DB
            try {
                val dbHelper = com.radio.app.database.RadioDatabaseHelper.getInstance(ctx)
                val dbSegments = dbHelper.getVoiceSegments(episode.id)
                segments = dbSegments.filter { !it.isSimulated }.size
            } catch (_: Exception) {}
        }
        holder.tvDescription.text = "${durationMin}分钟 · ${segments}片段"

        // 不喜欢状态 - 按节目ID或名称判断（每天该节目都不喜欢）
        val isDisliked = settings.isDisliked(episode.id) || settings.isDislikedByTitle(episode.stationId, episode.title)
        if (isDisliked) {
            holder.itemView.alpha = 0.5f
            holder.tvTitle.setTextColor(Color.parseColor("#999999"))
            holder.tvDislikeIndicator.visibility = View.VISIBLE
        } else {
            holder.itemView.alpha = 1.0f
            holder.tvTitle.setTextColor(ctx.resources.getColor(android.R.color.black, null))
            holder.tvDislikeIndicator.visibility = View.GONE
        }

        // [v2.0.43] Issue 4: Apply highlight for currently playing episode (after dislike check)
        if (isPlaying) {
            val accentColor = try {
                val tv = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.colorPrimary, tv, true)
                tv.data
            } catch (_: Exception) { Color.parseColor("#7ED321") }
            val tint = Color.argb(180, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            holder.itemView.setBackgroundColor(tint)
            holder.tvTitle.setTypeface(null, android.graphics.Typeface.BOLD)
            holder.tvTitle.setTextColor(accentColor)
            holder.tvPlayingIndicator.text = "正在播放"
            holder.tvPlayingIndicator.setTextColor(accentColor)
            holder.tvPlayingIndicator.visibility = View.VISIBLE
            holder.btnPlay.setImageResource(android.R.drawable.ic_media_pause)
            holder.btnPlay.setColorFilter(accentColor)
        } else {
            holder.itemView.background = holder.originalBackground
            holder.tvTitle.setTypeface(null, android.graphics.Typeface.NORMAL)
            holder.tvPlayingIndicator.visibility = View.GONE
            holder.btnPlay.clearColorFilter()
            if (episode.audioUrl.isNullOrBlank()) {
                holder.btnPlay.setImageResource(android.R.drawable.ic_menu_info_details)
            } else {
                holder.btnPlay.setImageResource(android.R.drawable.ic_media_play)
            }
        }

        // 检查是否已缓存
        val cacheFileName = try {
            val url = java.net.URL(episode.audioUrl)
            url.path.substringAfterLast("/")
        } catch (e: Exception) {
            episode.audioUrl.substringAfterLast("/")
        }
        // [v2.1.1] Use centralized cache dir from RadioApplication
        val cacheFile = java.io.File(com.radio.app.RadioApplication.getEpisodesCacheDir(ctx), cacheFileName)
        val isCached = cacheFile.exists() && cacheFile.length() > 1024
        if (isCached) {
            holder.tvCachedIndicator.visibility = View.VISIBLE
        } else {
            holder.tvCachedIndicator.visibility = View.GONE
        }

        // [v2.4.18] Check subtitle status: "完整字幕" (complete) vs "部分字幕" (incomplete) vs none
        val subtitleStatus = try {
            val dbHelper = com.radio.app.database.RadioDatabaseHelper.getInstance(ctx)
            when {
                dbHelper.hasCompleteSubtitles(episode.id) -> "complete"
                dbHelper.getTranscripts(episode.id).isNotEmpty() -> "partial"
                else -> "none"
            }
        } catch (e: Exception) {
            "none"
        }
        when (subtitleStatus) {
            "complete" -> {
                holder.tvSubtitleIndicator.text = "完整字幕"
                holder.tvSubtitleIndicator.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                holder.tvSubtitleIndicator.visibility = View.VISIBLE
            }
            "partial" -> {
                holder.tvSubtitleIndicator.text = "部分字幕"
                holder.tvSubtitleIndicator.setTextColor(android.graphics.Color.parseColor("#FF9800"))
                holder.tvSubtitleIndicator.visibility = View.VISIBLE
            }
            else -> {
                holder.tvSubtitleIndicator.visibility = View.GONE
            }
        }

        // [v2.4.14] Check if episode is marked as "no preprocessing needed"
        val isNoPreprocess = AppSettings.getInstance(ctx).isNoPreprocess(episode.id)
        if (isNoPreprocess) {
            holder.tvNoPreprocessIndicator.visibility = View.VISIBLE
        } else {
            holder.tvNoPreprocessIndicator.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { listener?.onEpisodeClick(episode) }
        holder.itemView.setOnLongClickListener {
            listener?.onEpisodeLongClick(episode)
            true
        }
    }

    override fun getItemCount(): Int = episodes.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val originalBackground: android.graphics.drawable.Drawable = view.background
        val tvTime: TextView = view.findViewById(R.id.tv_time)
        val tvTitle: TextView = view.findViewById(R.id.tv_title)
        val tvDescription: TextView = view.findViewById(R.id.tv_description)
        val btnPlay: ImageView = view.findViewById(R.id.btn_play)
        val tvDislikeIndicator: TextView = view.findViewById(R.id.tv_dislike_indicator)
        val tvCachedIndicator: TextView = view.findViewById(R.id.tv_cached_indicator)
        val tvSubtitleIndicator: TextView = view.findViewById(R.id.tv_subtitle_indicator)
        val tvNoPreprocessIndicator: TextView = view.findViewById(R.id.tv_no_preprocess_indicator)
        val tvPlayingIndicator: TextView = view.findViewById(R.id.tv_playing_indicator)
    }
}