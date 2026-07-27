package com.radio.app.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.radio.app.R
import com.radio.app.database.AudioFingerprint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v3.0.2: 音频指纹列表适配器。
 */
class AudioFingerprintAdapter : RecyclerView.Adapter<AudioFingerprintAdapter.ViewHolder>() {

    private var items: List<AudioFingerprint> = emptyList()
    private var onDeleteListener: ((AudioFingerprint) -> Unit)? = null
    private var onRefreshListener: ((AudioFingerprint) -> Unit)? = null
    private var onPlayListener: ((AudioFingerprint) -> Unit)? = null

    fun setItems(items: List<AudioFingerprint>) {
        this.items = items
        notifyDataSetChanged()
    }

    fun setOnDeleteListener(listener: (AudioFingerprint) -> Unit) {
        onDeleteListener = listener
    }

    fun setOnRefreshListener(listener: (AudioFingerprint) -> Unit) {
        onRefreshListener = listener
    }

    fun setOnPlayListener(listener: (AudioFingerprint) -> Unit) {
        onPlayListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audio_fingerprint, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val fp = items[position]
        holder.tvTime.text = "${formatTime(fp.startMs)} - ${formatTime(fp.endMs)}"
        holder.tvEpisode.text = fp.episodeId
        val durationSec = fp.durationMs / 1000
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(fp.createdAt))
        holder.tvMeta.text = "时长 ${durationSec}s · $dateStr"

        holder.btnPlay.setOnClickListener { onPlayListener?.invoke(fp) }
        holder.btnRefresh.setOnClickListener { onRefreshListener?.invoke(fp) }
        holder.btnDelete.setOnClickListener { onDeleteListener?.invoke(fp) }
    }

    override fun getItemCount(): Int = items.size

    private fun formatTime(ms: Long): String {
        val s = (ms / 1000).toInt()
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(R.id.tv_fingerprint_time)
        val tvEpisode: TextView = view.findViewById(R.id.tv_fingerprint_episode)
        val tvMeta: TextView = view.findViewById(R.id.tv_fingerprint_meta)
        val btnPlay: Button = view.findViewById(R.id.btn_play_fingerprint)
        val btnRefresh: Button = view.findViewById(R.id.btn_refresh_fingerprint)
        val btnDelete: Button = view.findViewById(R.id.btn_delete_fingerprint)
    }
}
