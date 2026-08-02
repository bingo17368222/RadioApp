package com.radio.app.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.radio.app.R
import com.radio.app.database.AudioFingerprint
import com.radio.app.database.RadioDatabaseHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v3.1.7: 指纹分组列表适配器。
 * 展示每个分组及其可展开的成员列表，支持备注显示与编辑。
 */
class FingerprintGroupAdapter(
    private val dbHelper: RadioDatabaseHelper
) : RecyclerView.Adapter<FingerprintGroupAdapter.ViewHolder>() {

    data class GroupItem(
        val groupId: Long,
        val name: String,
        val note: String = "",  // v3.1.7: 分组备注
        val representative: AudioFingerprint,
        val members: List<AudioFingerprint>,
        val memberSimilarities: Map<Long, Float> = emptyMap(), // fingerprintId -> similarity
        val memberDbIds: Map<Long, Long> = emptyMap() // fingerprintId -> memberDbId
    )

    private var groups: List<GroupItem> = emptyList()
    private var expandedPositions: MutableSet<Int> = mutableSetOf()
    private var onDeleteGroupListener: ((GroupItem) -> Unit)? = null
    private var onRemoveMemberListener: ((GroupItem, AudioFingerprint) -> Unit)? = null
    private var onEditNoteListener: ((GroupItem) -> Unit)? = null  // v3.1.7
    // v3.1.11: 成员备注编辑回调
    private var onEditMemberNoteListener: ((GroupItem, AudioFingerprint) -> Unit)? = null

    fun setGroups(groups: List<GroupItem>) {
        this.groups = groups
        notifyDataSetChanged()
    }

    fun setOnDeleteGroupListener(listener: (GroupItem) -> Unit) {
        onDeleteGroupListener = listener
    }

    fun setOnRemoveMemberListener(listener: (GroupItem, AudioFingerprint) -> Unit) {
        onRemoveMemberListener = listener
    }

    // v3.1.7: 设置编辑备注回调
    fun setOnEditNoteListener(listener: (GroupItem) -> Unit) {
        onEditNoteListener = listener
    }

    // v3.1.11: 设置成员备注编辑回调
    fun setOnEditMemberNoteListener(listener: (GroupItem, AudioFingerprint) -> Unit) {
        onEditMemberNoteListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_fingerprint_group, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = groups[position]
        val isExpanded = expandedPositions.contains(position)

        holder.tvGroupName.text = group.name
        holder.tvMemberCount.text = "${group.members.size}个成员"

        // v3.1.7: 显示分组备注
        if (group.note.isNotEmpty()) {
            holder.tvGroupNote.visibility = View.VISIBLE
            holder.tvGroupNote.text = "备注: ${group.note}"
        } else {
            holder.tvGroupNote.visibility = View.GONE
        }

        val rep = group.representative
        val repNote = if (rep.note.isNotEmpty()) " [${rep.note}]" else ""
        holder.tvRepresentative.text = "代表: ${rep.episodeId}${repNote} (${formatTime(rep.startMs)}-${formatTime(rep.endMs)})"

        // v3.1.7: 显示代表指纹详情
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(rep.createdAt))
        val repFpSize = rep.fingerprint.split(",").size
        holder.tvRepresentativeDetail.text = "  时长: ${rep.durationMs / 1000}s, 指纹点数: $repFpSize, 创建: $dateStr"

        // 展开/收起成员列表
        if (isExpanded) {
            holder.layoutMembers.visibility = View.VISIBLE
            holder.btnToggle.text = "收起成员"
            setupMemberAdapter(holder, group, position)
        } else {
            holder.layoutMembers.visibility = View.GONE
            holder.btnToggle.text = "展开成员 (${group.members.size})"
        }

        holder.btnToggle.setOnClickListener {
            if (expandedPositions.contains(position)) {
                expandedPositions.remove(position)
            } else {
                expandedPositions.add(position)
            }
            notifyItemChanged(position)
        }

        holder.btnDeleteGroup.setOnClickListener { onDeleteGroupListener?.invoke(group) }
        // v3.1.7: 编辑备注按钮
        holder.btnEditNote.setOnClickListener { onEditNoteListener?.invoke(group) }
    }

    private fun setupMemberAdapter(holder: ViewHolder, group: GroupItem, groupPosition: Int) {
        val memberAdapter = FingerprintGroupMemberAdapter()
        val memberItems = group.members.map { fp ->
            val sim = group.memberSimilarities[fp.id] ?: 1f
            val dbId = group.memberDbIds[fp.id] ?: 0L
            FingerprintGroupMemberAdapter.MemberItem(
                fingerprint = fp,
                similarityToRepresentative = sim,
                isRepresentative = fp.id == group.representative.id,
                memberDbId = dbId
            )
        }
        memberAdapter.setItems(memberItems)
        memberAdapter.setOnRemoveListener { memberItem ->
            onRemoveMemberListener?.invoke(group, memberItem.fingerprint)
        }
        memberAdapter.setOnEditNoteListener { memberItem ->
            onEditMemberNoteListener?.invoke(group, memberItem.fingerprint)
        }
        holder.recyclerMembers.layoutManager = LinearLayoutManager(holder.itemView.context)
        holder.recyclerMembers.adapter = memberAdapter
    }

    override fun getItemCount(): Int = groups.size

    private fun formatTime(ms: Long): String {
        val s = (ms / 1000).toInt()
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvGroupName: TextView = view.findViewById(R.id.tv_group_name)
        val tvGroupNote: TextView = view.findViewById(R.id.tv_group_note)  // v3.1.7
        val tvMemberCount: TextView = view.findViewById(R.id.tv_member_count)
        val tvRepresentative: TextView = view.findViewById(R.id.tv_representative)
        val tvRepresentativeDetail: TextView = view.findViewById(R.id.tv_representative_detail)  // v3.1.7
        val layoutMembers: LinearLayout = view.findViewById(R.id.layout_members)
        val recyclerMembers: RecyclerView = view.findViewById(R.id.recycler_group_members)
        val btnToggle: Button = view.findViewById(R.id.btn_toggle_members)
        val btnDeleteGroup: Button = view.findViewById(R.id.btn_delete_group)
        val btnEditNote: Button = view.findViewById(R.id.btn_edit_group_note)  // v3.1.7
    }
}