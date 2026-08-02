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
 * v3.1.7: 指纹分组成员列表适配器。
 * 用于在分组详情中展示组内成员。
 */
class FingerprintGroupMemberAdapter : RecyclerView.Adapter<FingerprintGroupMemberAdapter.ViewHolder>() {

    private var items: List<MemberItem> = emptyList()
    private var onRemoveListener: ((MemberItem) -> Unit)? = null
    // v3.1.11: 成员备注编辑回调
    private var onEditNoteListener: ((MemberItem) -> Unit)? = null

    data class MemberItem(
        val fingerprint: AudioFingerprint,
        val similarityToRepresentative: Float = 1f,
        val isRepresentative: Boolean = false,
        val memberDbId: Long = 0
    )

    fun setItems(items: List<MemberItem>) {
        this.items = items
        notifyDataSetChanged()
    }

    fun setOnRemoveListener(listener: (MemberItem) -> Unit) {
        onRemoveListener = listener
    }

    // v3.1.11: 设置成员备注编辑回调
    fun setOnEditNoteListener(listener: (MemberItem) -> Unit) {
        onEditNoteListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_fingerprint_group_member, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val fp = item.fingerprint
        val timeText = "${formatTime(fp.startMs)} - ${formatTime(fp.endMs)}"
        val durationSec = fp.durationMs / 1000
        val fpSize = fp.fingerprint.split(",").size
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(fp.createdAt))

        // 第1行：节目ID，代表加标记
        holder.tvEpisode.text = if (item.isRepresentative) "★ ${fp.episodeId} (代表)" else fp.episodeId

        // 第2行：时间范围 + 时长
        holder.tvTime.text = timeText
        holder.tvDuration.text = "${durationSec}s"

        // 第3行：指纹点数 + 创建日期
        holder.tvFpSize.text = "${fpSize} 点"
        holder.tvDate.text = dateStr

        // 第4行：备注
        if (fp.note.isNotEmpty()) {
            holder.tvNote.text = fp.note
            holder.tvNote.visibility = View.VISIBLE
        } else {
            holder.tvNote.visibility = View.GONE
        }

        // 第5行：相似度 + 编辑备注 + 移除按钮
        holder.tvSimilarity.text = "%.0f%%".format(item.similarityToRepresentative * 100)
        holder.tvSimilarity.visibility = if (item.isRepresentative) View.GONE else View.VISIBLE
        // v3.1.11: 成员备注编辑按钮
        holder.btnEditNote.visibility = if (item.isRepresentative) View.GONE else View.VISIBLE
        holder.btnEditNote.setOnClickListener { onEditNoteListener?.invoke(item) }
        holder.btnRemove.visibility = if (item.isRepresentative) View.GONE else View.VISIBLE
        holder.btnRemove.setOnClickListener { onRemoveListener?.invoke(item) }
    }

    override fun getItemCount(): Int = items.size

    private fun formatTime(ms: Long): String {
        val s = (ms / 1000).toInt()
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvEpisode: TextView = view.findViewById(R.id.tv_member_episode)
        val tvTime: TextView = view.findViewById(R.id.tv_member_time)
        val tvDuration: TextView = view.findViewById(R.id.tv_member_duration)
        val tvFpSize: TextView = view.findViewById(R.id.tv_member_fp_size)
        val tvDate: TextView = view.findViewById(R.id.tv_member_date)
        val tvNote: TextView = view.findViewById(R.id.tv_member_note)
        val tvSimilarity: TextView = view.findViewById(R.id.tv_member_similarity)
        val btnEditNote: Button = view.findViewById(R.id.btn_edit_member_note)  // v3.1.11
        val btnRemove: Button = view.findViewById(R.id.btn_remove_member)
    }
}