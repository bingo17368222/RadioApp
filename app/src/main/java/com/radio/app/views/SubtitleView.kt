package com.radio.app.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.radio.app.R
import com.radio.app.models.Transcript

class SubtitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val scrollView: ScrollView
    private val container: LinearLayout
    private val tvCurrent: TextView
    private var highlightIdx = -1

    interface OnSubtitleClickListener {
        fun onSubtitleClick(startTime: Long)
    }

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_subtitle, this, true)
        scrollView = findViewById(R.id.scroll_view)
        container = findViewById(R.id.subtitle_container)
        tvCurrent = findViewById(R.id.tv_current_subtitle)
    }

    fun setSubtitles(transcripts: List<Transcript>?) {
        container.removeAllViews()
        if (transcripts.isNullOrEmpty()) {
            tvCurrent.text = "暂无字幕"
            tvCurrent.visibility = VISIBLE
            return
        }
        tvCurrent.visibility = GONE
        for (i in transcripts.indices) {
            container.addView(createItem(transcripts[i], i))
        }
    }

    private fun createItem(t: Transcript, idx: Int): View {
        val typedValue = android.util.TypedValue()
        val theme = context.theme
        val cardBg = if (theme.resolveAttribute(R.attr.appCardBackground, typedValue, true)) typedValue.data else 0
        val textSecondary = if (theme.resolveAttribute(R.attr.appTextSecondary, typedValue, true)) typedValue.data else 0
        val textPrimary = if (theme.resolveAttribute(R.attr.appTextPrimary, typedValue, true)) typedValue.data else 0

        val card = CardView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setCardBackgroundColor(cardBg)
            radius = 8f
            setContentPadding(16, 12, 16, 12)
            tag = idx
        }
        val layout = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        val tvTime = TextView(context).apply {
            text = fmtTime(t.segmentStart)
            setTextColor(textSecondary)
            textSize = 12f
        }
        val tvText = TextView(context).apply {
            text = t.text
            setTextColor(textPrimary)
            textSize = 14f
            setPadding(0, 4, 0, 0)
        }
        layout.addView(tvTime)
        layout.addView(tvText)
        card.addView(layout)
        card.setOnClickListener {
            val tag = getTag()
            if (tag is OnSubtitleClickListener) {
                tag.onSubtitleClick(t.segmentStart)
            }
        }
        return card
    }

    fun highlightSubtitle(ms: Long) {
        val typedValue = android.util.TypedValue()
        val theme = context.theme
        val cardBg = if (theme.resolveAttribute(R.attr.appCardBackground, typedValue, true)) typedValue.data else 0
        val primaryColor = if (theme.resolveAttribute(R.attr.colorPrimary, typedValue, true)) typedValue.data else 0

        val idx = (ms / 30).toInt()
        if (idx == highlightIdx || idx >= container.childCount) return
        if (highlightIdx >= 0 && highlightIdx < container.childCount) {
            (container.getChildAt(highlightIdx) as CardView).setCardBackgroundColor(cardBg)
        }
        if (idx >= 0 && idx < container.childCount) {
            (container.getChildAt(idx) as CardView).setCardBackgroundColor(primaryColor)
            scrollView.post { scrollView.smoothScrollTo(0, container.getChildAt(idx).top) }
        }
        highlightIdx = idx
    }

    fun addRealtimeSubtitle(t: Transcript) {
        container.addView(createItem(t, container.childCount))
        scrollView.post { scrollView.fullScroll(FOCUS_DOWN) }
    }

    fun setOnSubtitleClickListener(l: OnSubtitleClickListener) {
        setTag(l)
    }

    fun clearSubtitles() {
        container.removeAllViews()
        highlightIdx = -1
    }

    private fun fmtTime(s: Long): String {
        return String.format("%02d:%02d", s / 60, s % 60)
    }
}
