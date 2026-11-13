package com.radio.app.adapters

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.radio.app.R
import com.radio.app.models.VoiceSegment

class VoiceSegmentAdapter : RecyclerView.Adapter<VoiceSegmentAdapter.ViewHolder>() {

    private var segments: List<VoiceSegment> = emptyList()
    private var listener: OnSegmentClickListener? = null
    private var itemLongClickListener: OnItemLongClickListener? = null
    private var currentSegmentIndex = -1

    interface OnSegmentClickListener {
        fun onSegmentClick(position: Int, segment: VoiceSegment)
        fun onSegmentLongClick(position: Int, segment: VoiceSegment)
    }

    /**
     * [功能1] 独立的长按监听接口，用于弹出"标记为干货/水货"对话框。
     * 设置后将优先于 OnSegmentClickListener.onSegmentLongClick 触发。
     */
    interface OnItemLongClickListener {
        fun onItemLongClick(position: Int, segment: VoiceSegment)
    }

    fun setSegments(segments: List<VoiceSegment>?) {
        this.segments = segments ?: emptyList()
        notifyDataSetChanged()
    }

    /**
     * [功能1] 注册长按监听器。长按分段时回调，由调用方弹出标记对话框。
     */
    fun setOnItemLongClickListener(listener: OnItemLongClickListener) {
        this.itemLongClickListener = listener
    }

    fun setCurrentSegmentIndex(index: Int) {
        val old = this.currentSegmentIndex
        this.currentSegmentIndex = index
        if (old >= 0 && old < segments.size) notifyItemChanged(old)
        if (index >= 0 && index < segments.size) notifyItemChanged(index)
    }

    fun setOnSegmentClickListener(listener: OnSegmentClickListener) {
        this.listener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_voice_segment, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val segment = segments[position]
        holder.tvTimeRange.text = "${formatTime(segment.start)} - ${formatTime(segment.end)}"
        holder.tvLabel.text = segment.label ?: ""

        val isDry = segment.isEffectiveDry()
        // v3.2.70: 分段详情标注判水原因——水分段显示"水分·指纹命中/模型判水/漏判召回/手动标记"
        val reason = if (isDry) null else segment.judgementReason()
        holder.tvType.text = if (isDry) "干货" else "水分·${reason ?: "未知"}"

        // v3.2.71: 指纹命中素材去重key——显示截断预览，点击复制完整key（分段日志里可对照命中时间轴）
        val hitKey = segment.hitKey
        if (!isDry && !hitKey.isNullOrBlank()) {
            holder.tvHitKey.visibility = View.VISIBLE
            val simPart = if (segment.hitSim > 0) " 相似度${segment.hitSim}%" else ""
            holder.tvHitKey.text = "指纹去重key: ${hitKey.take(16)}…${simPart} 点击复制"
            holder.tvHitKey.setOnClickListener { v ->
                val cm = v.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("指纹去重key", hitKey))
                Toast.makeText(v.context, "去重key已复制，可到指纹管理搜索试听", Toast.LENGTH_SHORT).show()
            }
            // v3.2.72: 修复长按菜单失灵——key行可点击后会吸收长按事件、不再冒泡到itemView，
            // 导致水分段长按落点在key行区域时弹不出标记菜单。此处按与itemView相同的优先级
            // 规则手动分发：OnItemLongClickListener优先、回退旧回调。
            holder.tvHitKey.setOnLongClickListener {
                if (itemLongClickListener != null) {
                    itemLongClickListener?.onItemLongClick(position, segment)
                } else {
                    listener?.onSegmentLongClick(position, segment)
                }
                true
            }
        } else {
            holder.tvHitKey.visibility = View.GONE
            holder.tvHitKey.setOnClickListener(null)
            holder.tvHitKey.setOnLongClickListener(null)
        }

        val ctx = holder.itemView.context
        val typedValue = android.util.TypedValue()
        val theme = ctx.theme
        val successColor = if (theme.resolveAttribute(R.attr.appSuccess, typedValue, true)) typedValue.data else ContextCompat.getColor(ctx, android.R.color.holo_green_dark)
        val warningColor = if (theme.resolveAttribute(R.attr.appWarning, typedValue, true)) typedValue.data else ContextCompat.getColor(ctx, android.R.color.holo_orange_dark)
        holder.tvType.setTextColor(if (isDry) successColor else warningColor)

        if (segment.isManuallyMarked) {
            holder.ivManualMark.visibility = View.VISIBLE
            holder.ivManualMark.setImageResource(if (isDry) R.drawable.ic_manual_dry else R.drawable.ic_manual_water)
        } else {
            holder.ivManualMark.visibility = View.GONE
        }

        if (position == currentSegmentIndex) {
            holder.itemView.alpha = 1.0f
            val primaryColor = if (theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)) typedValue.data else 0
            holder.itemView.setBackgroundColor(primaryColor)
        } else {
            holder.itemView.alpha = 0.8f
            holder.itemView.setBackgroundColor(0)
        }

        holder.itemView.setOnClickListener {
            listener?.onSegmentClick(position, segment)
        }
        holder.itemView.setOnLongClickListener {
            // [功能1] 优先使用独立的 OnItemLongClickListener；未设置时回退到旧回调。
            if (itemLongClickListener != null) {
                itemLongClickListener?.onItemLongClick(position, segment)
            } else {
                listener?.onSegmentLongClick(position, segment)
            }
            true
        }
    }

    override fun getItemCount(): Int = segments.size

    private fun formatTime(ms: Long): String {
        val s = (ms / 1000).toInt()
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTimeRange: TextView = view.findViewById(R.id.tv_time_range)
        val tvLabel: TextView = view.findViewById(R.id.tv_label)
        val tvType: TextView = view.findViewById(R.id.tv_type)
        val ivManualMark: ImageView = view.findViewById(R.id.iv_manual_mark)
        val tvHitKey: TextView = view.findViewById(R.id.tv_hit_key)
    }
}
