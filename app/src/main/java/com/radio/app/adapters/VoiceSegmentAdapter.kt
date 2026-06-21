package com.radio.app.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.radio.app.R
import com.radio.app.models.VoiceSegment

class VoiceSegmentAdapter : RecyclerView.Adapter<VoiceSegmentAdapter.ViewHolder>() {

    private var segments: List<VoiceSegment> = emptyList()
    private var listener: OnSegmentClickListener? = null
    private var currentSegmentIndex = -1

    interface OnSegmentClickListener {
        fun onSegmentClick(position: Int, segment: VoiceSegment)
        fun onSegmentLongClick(position: Int, segment: VoiceSegment)
    }

    fun setSegments(segments: List<VoiceSegment>?) {
        this.segments = segments ?: emptyList()
        notifyDataSetChanged()
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
        holder.tvType.text = if (isDry) "干货" else "水分"

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
            val primaryColor = if (theme.resolveAttribute(R.attr.colorPrimary, typedValue, true)) typedValue.data else 0
            holder.itemView.setBackgroundColor(primaryColor)
        } else {
            holder.itemView.alpha = 0.8f
            holder.itemView.setBackgroundColor(0)
        }

        holder.itemView.setOnClickListener {
            listener?.onSegmentClick(position, segment)
        }
        holder.itemView.setOnLongClickListener {
            listener?.onSegmentLongClick(position, segment)
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
    }
}
