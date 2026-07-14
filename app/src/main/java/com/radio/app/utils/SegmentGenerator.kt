package com.radio.app.utils

import android.content.Context
import android.util.Log
import com.radio.app.database.RadioDatabaseHelper
import com.radio.app.models.AppSettings
import com.radio.app.models.VoiceSegment

/**
 * v2.4.91: Shared segment generation utility.
 * Used by both RadioPlaybackService (pre-segmentation) and PlayerActivity (on-demand).
 *
 * Two modes:
 * 1. Fixed 15-minute segments (before subtitles are generated)
 * 2. Keyword-based "就AI听" segments (after subtitles are complete)
 */
object SegmentGenerator {
    private const val TAG = "SegmentGenerator"

    // Default keywords for content-based classification (就AI听 scheme)
    private val DEFAULT_DRY_KEYWORDS = listOf(
        "新闻", "资讯", "报道", "访谈", "评论", "分析", "数据", "调查",
        "采访", "记者", "专家", "研究", "政策", "经济", "社会", "科技", "教育", "健康",
        "事故", "事件", "天气", "路况", "交通", "市场", "价格"
    )
    private val DEFAULT_WATER_KEYWORDS = listOf(
        "广告", "音乐", "歌曲", "休息", "片头", "片尾", "赞助",
        "微信", "公众号", "下载", "关注", "扫码", "二维码", "推广",
        "欢迎收听", "感谢收听", "这里是", "您正在收听", "广播电台",
        "接下来", "稍后", "马上回来", "不要走开",
        "早安", "晚安", "再见", "拜拜",
        "片花", "预告", "下周", "明天同一时间"
    )

    /**
     * Generate fixed 15-minute segments for an episode.
     * Used before subtitles are available as placeholder segments.
     * All segments default to 干货 (hasVoice = true).
     */
    fun generateFixedSegments(durationMs: Long, segmentDurationMs: Long = 15 * 60 * 1000L): List<VoiceSegment> {
        if (durationMs <= 0) return emptyList()
        val segments = mutableListOf<VoiceSegment>()
        var start = 0L
        while (start < durationMs) {
            val end = minOf(start + segmentDurationMs, durationMs)
            val seg = VoiceSegment().apply {
                this.start = start
                this.end = end
                this.hasVoice = true
                this.label = "干货"
                this.isSimulated = true
            }
            segments.add(seg)
            start = end
        }
        Log.i(TAG, "generateFixedSegments: ${segments.size} segments for duration=${durationMs}ms")
        return segments
    }

    /**
     * Generate keyword-based segments from transcripts (就AI听 scheme).
     * Groups transcripts into 3-minute segments, classifies each, then merges adjacent same-type.
     */
    fun generateKeywordSegments(
        context: Context,
        episodeId: String,
        durationMs: Long
    ): List<VoiceSegment> {
        try {
            val dbHelper = RadioDatabaseHelper.getInstance(context)
            val transcripts = dbHelper.getTranscripts(episodeId)
            if (transcripts.size < 3) return emptyList()

            val settings = AppSettings.getInstance(context)
            val dryKeywords = (DEFAULT_DRY_KEYWORDS + settings.getDryKeywords()).distinct()
            val waterKeywords = (DEFAULT_WATER_KEYWORDS + settings.getWaterKeywords()).distinct()

            val segmentDurationMs = 3 * 60 * 1000L
            val segments = mutableListOf<VoiceSegment>()
            var currentSegStart = 0L
            var currentSegEnd = segmentDurationMs
            var currentTexts = mutableListOf<String>()

            for (t in transcripts) {
                while (t.segmentStart >= currentSegEnd && currentTexts.isNotEmpty()) {
                    val combinedText = currentTexts.joinToString("")
                    val isDry = classifySegment(combinedText, dryKeywords, waterKeywords, segmentDurationMs, settings)
                    segments.add(VoiceSegment().apply {
                        this.start = currentSegStart
                        this.end = currentSegEnd
                        this.hasVoice = isDry
                        this.label = if (isDry) "干货" else "水货"
                        this.isSimulated = false
                    })
                    currentSegStart = currentSegEnd
                    currentSegEnd = currentSegStart + segmentDurationMs
                    currentTexts = mutableListOf()
                }
                currentTexts.add(t.text ?: "")
            }
            if (currentTexts.isNotEmpty()) {
                val combinedText = currentTexts.joinToString("")
                val isDry = classifySegment(combinedText, dryKeywords, waterKeywords, segmentDurationMs, settings)
                segments.add(VoiceSegment().apply {
                    this.start = currentSegStart
                    this.end = durationMs
                    this.hasVoice = isDry
                    this.label = if (isDry) "干货" else "水货"
                    this.isSimulated = false
                })
            }

            // Merge consecutive segments of the same type
            val merged = mutableListOf<VoiceSegment>()
            for (seg in segments) {
                val last = merged.lastOrNull()
                if (last != null && last.hasVoice == seg.hasVoice) {
                    last.end = seg.end
                } else {
                    merged.add(VoiceSegment().apply {
                        this.start = seg.start
                        this.end = seg.end
                        this.hasVoice = seg.hasVoice
                        this.label = seg.label
                        this.isSimulated = false
                    })
                }
            }

            Log.i(TAG, "generateKeywordSegments: ${merged.size} segments (merged from ${segments.size}) for episode=$episodeId")
            return merged
        } catch (e: Exception) {
            Log.e(TAG, "generateKeywordSegments failed: ${e.message}")
            return emptyList()
        }
    }

    private fun classifySegment(
        text: String,
        dryKeywords: List<String>,
        waterKeywords: List<String>,
        segmentDurationMs: Long,
        settings: AppSettings
    ): Boolean {
        // Check water combinations first
        try {
            val combinations = settings.getWaterCombinations()
            val trimmedText = text.trim()
            for ((start, end) in combinations) {
                if (start.isNotBlank() && end.isNotBlank() &&
                    trimmedText.startsWith(start) && trimmedText.endsWith(end)) {
                    return false
                }
            }
        } catch (_: Exception) {}

        val textLower = text.lowercase()
        var dryScore = 0
        var waterScore = 0
        for (kw in dryKeywords) {
            if (textLower.contains(kw.lowercase())) dryScore++
        }
        for (kw in waterKeywords) {
            if (textLower.contains(kw.lowercase())) waterScore++
        }

        val segmentMinutes = segmentDurationMs / 60000.0
        val charsPerMin = if (segmentMinutes > 0) text.length / segmentMinutes else 0.0

        if (text.length < 30) return false
        if (charsPerMin < 20) return false
        if (waterScore > 0 && dryScore <= waterScore) return false
        if (dryScore == 0 && waterScore == 0) {
            return charsPerMin > 50
        }
        return dryScore > waterScore
    }

    /**
     * Pre-segment an episode: generate fixed 15-min segments and save to DB.
     * Called before subtitle generation starts.
     */
    fun preSegmentFixed(context: Context, episodeId: String, durationMs: Long) {
        try {
            val dbHelper = RadioDatabaseHelper.getInstance(context)
            // Check if segments already exist
            val existing = dbHelper.getVoiceSegments(episodeId)
            if (existing.isNotEmpty()) {
                Log.i(TAG, "preSegmentFixed: episode=$episodeId already has ${existing.size} segments, skipping")
                return
            }
            val segments = generateFixedSegments(durationMs)
            if (segments.isNotEmpty()) {
                dbHelper.saveVoiceSegments(episodeId, segments)
                dbHelper.updateEpisodeSegmentCount(episodeId, segments.size)
                Log.i(TAG, "preSegmentFixed: saved ${segments.size} fixed segments for episode=$episodeId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "preSegmentFixed failed: ${e.message}")
        }
    }

    /**
     * Post-segment an episode: generate keyword-based segments and save to DB.
     * Called after subtitle generation completes.
     * Replaces any existing simulated segments with real keyword-classified ones.
     */
    fun postSegmentKeyword(context: Context, episodeId: String, durationMs: Long) {
        try {
            val dbHelper = RadioDatabaseHelper.getInstance(context)
            // Check if real (non-simulated) segments already exist
            val existing = dbHelper.getVoiceSegments(episodeId)
            val hasRealSegments = existing.any { !it.isSimulated }
            if (hasRealSegments) {
                Log.i(TAG, "postSegmentKeyword: episode=$episodeId already has real segments, skipping")
                return
            }
            val segments = generateKeywordSegments(context, episodeId, durationMs)
            if (segments.isNotEmpty()) {
                dbHelper.saveVoiceSegments(episodeId, segments)
                dbHelper.updateEpisodeSegmentCount(episodeId, segments.size)
                Log.i(TAG, "postSegmentKeyword: saved ${segments.size} keyword segments for episode=$episodeId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "postSegmentKeyword failed: ${e.message}")
        }
    }
}
