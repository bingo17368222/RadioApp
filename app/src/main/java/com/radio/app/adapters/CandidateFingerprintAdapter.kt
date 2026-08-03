package com.radio.app.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.radio.app.R
import com.radio.app.database.RadioDatabaseHelper.ObservationPoolCandidate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v3.2.2: 候选指纹列表适配器。
 * 展示观察池中的候选指纹，支持删除操作。
 */
class CandidateFingerprintAdapter : RecyclerView.Adapter<CandidateFingerprintAdapter.ViewHolder>() {

    private var items: List<ObservationPoolCandidate> = emptyList()
    private var onDeleteListener: ((ObservationPoolCandidate) -> Unit)? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun setItems(items: List<ObservationPoolCandidate>) {
        this.items = items
        notifyDataSetChanged()
    }

    fun setOnDeleteListener(listener: (ObservationPoolCandidate) -> Unit) {
        onDeleteListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_candidate_fingerprint, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val candidate = items[position]

        // 节目ID
        holder.tvEpisode.text = candidate.episodeId

        // 时长格式化: "X秒" 或 "X分X秒"
        holder.tvDuration.text = formatDuration(candidate.durationMs)

        // 相似度: "XX.X%"
        holder.tvSimilarity.text = String.format("相似度: %.1f%%", candidate.similarity * 100f)

        // 命中次数: "命中 X 次"
        holder.tvHitCount.text = "命中 ${candidate.hitCount} 次"

        // 最后命中时间
        holder.tvLastHitTime.text = "最后命中: ${dateFormat.format(Date(candidate.lastHitTime))}"

        // 过期时间: 格式化日期，或"已过期"
        val now = System.currentTimeMillis()
        if (candidate.expiredAt <= now) {
            holder.tvExpiredTime.text = "已过期"
            holder.tvExpiredTime.setTextColor(holder.itemView.context.getColor(android.R.color.holo_red_light))
        } else {
            holder.tvExpiredTime.text = "过期时间: ${dateFormat.format(Date(candidate.expiredAt))}"
            holder.tvExpiredTime.setTextColor(
                holder.itemView.context.resources.getColor(
                    android.R.color.darker_gray,
                    holder.itemView.context.theme
                )
            )
        }

        // 删除按钮
        holder.btnDelete.setOnClickListener { onDeleteListener?.invoke(candidate) }
    }

    override fun getItemCount(): Int = items.size

    /**
     * 格式化时长。
     * 小于60秒显示 "X秒"，否则显示 "X分X秒"。
     */
    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        return if (totalSec < 60) {
            "${totalSec}秒"
        } else {
            val min = totalSec / 60
            val sec = totalSec % 60
            if (sec > 0) "${min}分${sec}秒" else "${min}分"
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvEpisode: TextView = view.findViewById(R.id.tv_candidate_episode)
        val tvDuration: TextView = view.findViewById(R.id.tv_candidate_duration)
        val tvSimilarity: TextView = view.findViewById(R.id.tv_candidate_similarity)
        val tvHitCount: TextView = view.findViewById(R.id.tv_candidate_hit_count)
        val tvLastHitTime: TextView = view.findViewById(R.id.tv_candidate_last_hit_time)
        val tvExpiredTime: TextView = view.findViewById(R.id.tv_candidate_expired_time)
        val btnDelete: Button = view.findViewById(R.id.btn_delete_candidate)
    }
}