package com.radio.app.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.radio.app.models.VoiceSegment

/**
 * v3.2.59: 播放页进度条上方的分段色块条。
 * 干货段=绿色，水货段=灰色，按起止时间占整期节目比例绘制。
 */
class SegmentStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66BB6A")
        style = Paint.Style.FILL
    }
    private val waterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#78909C")
        style = Paint.Style.FILL
    }
    private val rect = RectF()

    private var segments: List<VoiceSegment> = emptyList()
    private var durationMs: Long = 0L
    private var lastSig: String = ""

    fun setSegments(segs: List<VoiceSegment>, duration: Long) {
        // 脏检查：分段数量/边界/时长未变时不触发重绘
        val sig = "${segs.size}_${segs.firstOrNull()?.start}_${segs.lastOrNull()?.end}_$duration"
        if (sig == lastSig) return
        lastSig = sig
        segments = segs
        durationMs = duration
        visibility = if (segs.isEmpty() || duration <= 0) GONE else VISIBLE
        invalidate()
    }

    /** 查询某时间点所在分段的类型，供拖动气泡显示"干货/水货" */
    fun segmentTypeAt(positionMs: Long): String? {
        if (segments.isEmpty()) return null
        val seg = segments.firstOrNull { positionMs >= it.start && positionMs < it.end } ?: return null
        return if (seg.isEffectiveDry()) "干货" else "水货"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (segments.isEmpty() || durationMs <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val radius = h / 2f
        for (seg in segments) {
            val left = (w * seg.start / durationMs).coerceIn(0f, w)
            val right = (w * seg.end / durationMs).coerceIn(0f, w)
            if (right - left < 1f) continue
            rect.set(left, 0f, right, h)
            canvas.drawRoundRect(rect, radius, radius, if (seg.isEffectiveDry()) dryPaint else waterPaint)
        }
    }
}
