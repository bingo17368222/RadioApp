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
                // v2.4.124: Write to precache log for visibility
                val logFile = java.io.File(context.getExternalFilesDir(null), "RadioApp/logs/precache/precache.log")
                logFile.parentFile?.mkdirs()
                logFile.appendText("[${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}] preSegmentFixed: episode=$episodeId already has ${existing.size} segments, skipping (durationMs=$durationMs)\n")
                return
            }
            val segments = generateFixedSegments(durationMs)
            if (segments.isNotEmpty()) {
                dbHelper.saveVoiceSegments(episodeId, segments)
                dbHelper.updateEpisodeSegmentCount(episodeId, segments.size)
                Log.i(TAG, "preSegmentFixed: saved ${segments.size} fixed segments for episode=$episodeId")
                // v2.4.124: Write to precache log for visibility
                val logFile = java.io.File(context.getExternalFilesDir(null), "RadioApp/logs/precache/precache.log")
                logFile.parentFile?.mkdirs()
                val segInfo = segments.mapIndexed { i, s -> "seg[$i]: ${s.start}-${s.end}ms (${(s.end - s.start) / 1000}s) ${s.label}" }.joinToString(", ")
                logFile.appendText("[${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}] preSegmentFixed: SAVED ${segments.size} fixed 15-min segments for episode=$episodeId (durationMs=$durationMs): $segInfo\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "preSegmentFixed failed: ${e.message}")
        }
    }

    /**
     * Post-segment an episode: generate segments and save to DB.
     * Called after subtitle generation completes.
     * v2.4.95: Routes based on aiModel setting:
     *   - AI_MODEL_AUDIO_VAD: audio-based (Silero VAD + YAMNet dual model)
     *   - Other models: keyword-based
     * Replaces any existing simulated segments with real classified ones.
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

            // v2.4.151: Track timing so we can persist engine + elapsed time for all segment paths.
            val segStartTime = System.currentTimeMillis()
            val settings = com.radio.app.models.AppSettings.getInstance(context)
            val segments: List<VoiceSegment>
            val engineName: String
            val processingTimeMs: Long
            val audioDurationMs: Long

            if (settings.aiModel == com.radio.app.models.AppSettings.AI_MODEL_AUDIO_VAD) {
                // v2.4.95: Audio-based segmentation (Silero VAD + YAMNet)
                Log.i(TAG, "postSegmentKeyword: using audio-vad mode for episode=$episodeId")
                // v2.4.99: Look up audio URL from database for PCM file finding
                val audioUrl = try {
                    RadioDatabaseHelper.getInstance(context).getEpisodeInfo(episodeId)?.audioUrl
                } catch (_: Exception) { null }
                val result = tryGenerateAudioSegments(context, episodeId, durationMs, audioUrl)
                segments = result?.segments ?: emptyList()
                engineName = result?.engineName ?: "VAD+YAMNet"
                processingTimeMs = result?.processingTimeMs ?: (System.currentTimeMillis() - segStartTime)
                audioDurationMs = result?.audioDurationMs ?: durationMs
            } else {
                // Keyword-based segments
                Log.i(TAG, "postSegmentKeyword: using keyword-based for episode=$episodeId")
                engineName = when (settings.aiModel) {
                    com.radio.app.models.AppSettings.AI_MODEL_JIU_AI_TING -> "就AI听"
                    else -> "关键词"
                }
                segments = generateKeywordSegments(context, episodeId, durationMs)
                processingTimeMs = System.currentTimeMillis() - segStartTime
                audioDurationMs = durationMs
            }

            if (segments.isNotEmpty()) {
                dbHelper.saveVoiceSegments(episodeId, segments)
                dbHelper.updateEpisodeSegmentCount(episodeId, segments.size)
                // v2.4.151: Persist engine and timing for permanent display.
                try {
                    val dryCount = segments.count { it.hasVoice }
                    dbHelper.saveSegmentAnalysisInfo(
                        com.radio.app.database.SegmentAnalysisInfo(
                            episodeId = episodeId,
                            engineName = engineName,
                            generatedAt = System.currentTimeMillis(),
                            processingTimeMs = processingTimeMs,
                            audioDurationMs = audioDurationMs,
                            segmentCount = segments.size,
                            dryCount = dryCount,
                            waterCount = segments.size - dryCount
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "postSegmentKeyword: failed to save segment analysis info: ${e.message}")
                }
                Log.i(TAG, "postSegmentKeyword: saved ${segments.size} segments for episode=$episodeId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "postSegmentKeyword failed: ${e.message}")
        }
    }

    /**
     * v2.4.176: Pre-segment an episode using the audio segmentation model (Silero VAD + YAMNet).
     * Called by preprocessing patrol when a full PCM file already exists and no real segments
     * have been generated yet. Saves segments, engine info and timing to the database and
     * updates the episode segment count for the episode list.
     *
     * @return true if segments were generated and saved, false otherwise.
     */
    fun preSegmentAudio(
        context: Context,
        episodeId: String,
        durationMs: Long,
        audioUrl: String? = null
    ): Boolean {
        try {
            val dbHelper = RadioDatabaseHelper.getInstance(context)
            val existing = dbHelper.getVoiceSegments(episodeId)
            if (existing.any { !it.isSimulated }) {
                Log.i(TAG, "preSegmentAudio: episode=$episodeId already has real segments, skipping")
                return false
            }

            val modelDir = AudioSegmentAnalyzer.getModelDir(context)
            if (!AudioSegmentAnalyzer.isModelInstalled(modelDir)) {
                Log.i(TAG, "preSegmentAudio: audio segmentation models not installed, skipping")
                return false
            }

            // v2.4.178: Large PCM files are now handled by memory-mapped sample access in
            // AudioSegmentAnalyzer, so the artificial 120MB limit is removed. Pre-segmentation
            // can run on any episode whose full PCM has already been decoded.
            Log.i(TAG, "preSegmentAudio: running audio segmentation for episode=$episodeId")

            // v2.4.180: Show the same progress notification as manual segmentation so the user
            // can see pre-segmentation progress in the background. Include the broadcast date in
            // the title so it matches the style used for manual segmentation.
            val episodeInfo = dbHelper.getEpisodeInfo(episodeId)
            val episodeTitle = buildSegmentNotificationTitle(episodeId, episodeInfo?.title)
            SegmentNotificationHelper.reset()
            SegmentNotificationHelper.update(context, episodeTitle, 0, "", "")
            val result = tryGenerateAudioSegments(
                context, episodeId, durationMs, audioUrl,
                progressCallback = { permille, elapsedMs, etaMs ->
                    val elapsedText = AudioSegmentAnalyzer.formatDurationMs(elapsedMs)
                    val etaText = AudioSegmentAnalyzer.formatDurationMs(etaMs)
                    SegmentNotificationHelper.update(context, episodeTitle, permille, elapsedText, etaText)
                }
            )
            SegmentNotificationHelper.cancel(context)
            val segments = result?.segments ?: emptyList()
            if (result == null || segments.isEmpty()) {
                Log.w(TAG, "preSegmentAudio: no segments generated for episode=$episodeId")
                return false
            }

            dbHelper.saveVoiceSegments(episodeId, segments)
            dbHelper.updateEpisodeSegmentCount(episodeId, segments.size)

            val dryCount = segments.count { it.hasVoice }
            try {
                dbHelper.saveSegmentAnalysisInfo(
                    com.radio.app.database.SegmentAnalysisInfo(
                        episodeId = episodeId,
                        engineName = result.engineName,
                        generatedAt = System.currentTimeMillis(),
                        processingTimeMs = result.processingTimeMs,
                        audioDurationMs = result.audioDurationMs,
                        segmentCount = segments.size,
                        dryCount = dryCount,
                        waterCount = segments.size - dryCount
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "preSegmentAudio: failed to save segment analysis info: ${e.message}")
            }

            Log.i(TAG, "preSegmentAudio: saved ${segments.size} segments for episode=$episodeId (engine=${result.engineName}, time=${result.processingTimeMs}ms)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "preSegmentAudio failed: ${e.message}")
            return false
        }
    }

    /**
     * v2.4.180: Build a segment-notification title that includes the broadcast date, matching
     * the style used in PlayerActivity for manual segmentation.
     */
    private fun buildSegmentNotificationTitle(episodeId: String?, title: String?): String {
        val dateMatch = Regex("(\\d{4}-\\d{2}-\\d{2})").find(episodeId ?: "")
        val dateStr = dateMatch?.value ?: ""
        val baseTitle = title ?: episodeId ?: "未知节目"
        return if (dateStr.isNotEmpty()) "$dateStr $baseTitle" else baseTitle
    }

    /**
     * v2.4.96: Try to generate segments using audio analysis (Silero VAD + YAMNet).
     * v2.4.151: Returns the full SegmentAnalysisResult so callers can persist engine & timing.
     * v2.4.179: Accepts an optional progress callback so background pre-segmentation can
     * post the same progress notification as manual segmentation.
     */
    private fun tryGenerateAudioSegments(
        context: Context,
        episodeId: String,
        durationMs: Long,
        audioUrl: String? = null,
        progressCallback: ((Int, Long, Long) -> Unit)? = null
    ): AudioSegmentAnalyzer.SegmentAnalysisResult? {
        try {
            val result = AudioSegmentAnalyzer.analyzeEpisode(
                context, episodeId, durationMs, audioUrl, progressCallback = progressCallback
            )
            Log.i(TAG, "tryGenerateAudioSegments: got ${result.segments.size} segments from audio analysis (engine=${result.engineName}, time=${result.processingTimeMs}ms)")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "tryGenerateAudioSegments failed: ${e.message}")
            return null
        }
    }
}
