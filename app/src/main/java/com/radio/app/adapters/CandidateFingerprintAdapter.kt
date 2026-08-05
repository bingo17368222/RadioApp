package com.radio.app.adapters

import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.radio.app.R
import com.radio.app.database.RadioDatabaseHelper.ObservationPoolCandidate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v3.2.2: 候选指纹列表适配器。
 * 展示观察池中的候选指纹，支持删除、播放和备注功能。
 */
class CandidateFingerprintAdapter : RecyclerView.Adapter<CandidateFingerprintAdapter.ViewHolder>() {

    private val TAG = "CandidateFingerprintAdapter"
    private var items: List<ObservationPoolCandidate> = emptyList()
    private var playingPosition: Int = -1
    private var onDeleteListener: ((ObservationPoolCandidate) -> Unit)? = null
    private var onPlayListener: ((ObservationPoolCandidate) -> Unit)? = null
    private var onStopListener: (() -> Unit)? = null
    private var onNoteUpdateListener: ((ObservationPoolCandidate, String) -> Unit)? = null

    /**
     * 备注缓存（key 为 episodeId）。
     * ObservationPoolCandidate 没有 note 字段，用内部缓存保存备注。
     */
    private val noteCache = mutableMapOf<String, String>()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun setItems(items: List<ObservationPoolCandidate>) {
        this.items = items
        playingPosition = -1
        notifyDataSetChanged()
    }

    fun setOnDeleteListener(listener: (ObservationPoolCandidate) -> Unit) {
        onDeleteListener = listener
    }

    fun setOnPlayListener(listener: (ObservationPoolCandidate) -> Unit) {
        onPlayListener = listener
    }

    fun setOnStopListener(listener: () -> Unit) {
        onStopListener = listener
    }

    fun setOnNoteUpdateListener(listener: (ObservationPoolCandidate, String) -> Unit) {
        onNoteUpdateListener = listener
    }

    fun setPlayingPosition(position: Int) {
        val old = playingPosition
        playingPosition = position
        if (old >= 0 && old < itemCount) notifyItemChanged(old)
        if (position >= 0 && position < itemCount) notifyItemChanged(position)
    }

    fun stopPlaying() {
        setPlayingPosition(-1)
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

        // 绑定备注
        holder.bindNote(candidate, noteCache)
        holder.itemView.tag = onNoteUpdateListener

        // 播放/停止按钮
        val isPlaying = position == playingPosition
        holder.btnPlay.text = if (isPlaying) "停止" else "播放"
        holder.btnPlay.setOnClickListener {
            if (isPlaying) {
                onStopListener?.invoke()
            } else {
                setPlayingPosition(position)
                onPlayListener?.invoke(candidate)
            }
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
        val btnPlay: Button = view.findViewById(R.id.btn_play_candidate)
        val btnDelete: Button = view.findViewById(R.id.btn_delete_candidate)
        val etNote: EditText = view.findViewById(R.id.et_candidate_note)

        private var currentCandidate: ObservationPoolCandidate? = null
        private var isBinding = false

        private val noteWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {}
        }

        init {
            etNote.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus && !isBinding) {
                    val candidate = currentCandidate
                    val newText = etNote.text?.toString()?.trim() ?: ""
                    if (candidate != null) {
                        Log.d("CandidateFingerprintAdapter", "备注失焦保存: episodeId=${candidate.episodeId} note='$newText'")
                        val callback = itemView.tag as? ((ObservationPoolCandidate, String) -> Unit)
                        callback?.invoke(candidate, newText)
                    }
                }
            }
            etNote.setOnEditorActionListener { _, _, _ ->
                etNote.clearFocus()
                true
            }
        }

        fun bindNote(candidate: ObservationPoolCandidate, noteCache: MutableMap<String, String>) {
            isBinding = true
            currentCandidate = candidate
            val note = noteCache[candidate.episodeId] ?: ""
            etNote.setText(note)
            etNote.setSelection(note.length)
            isBinding = false
        }
    }
}