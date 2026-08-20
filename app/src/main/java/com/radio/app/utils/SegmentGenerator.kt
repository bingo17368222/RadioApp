package com.radio.app.utils

import android.content.Context
import android.util.Log
import com.radio.app.database.AudioFingerprint
import com.radio.app.database.FingerprintGroupInfo
import com.radio.app.database.FingerprintGroupMember
import com.radio.app.database.RadioDatabaseHelper
import com.radio.app.models.AppSettings
import com.radio.app.models.VoiceSegment
import com.radio.app.utils.ChromaprintExtractor
import com.radio.app.utils.PcmSegmentExtractor
import org.tensorflow.lite.Interpreter
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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

    // v2.4.185: Track episodes currently being pre-segmented so that slow patrols or rapid
    // re-triggers do not start a second analysis while the first one is still running.
    // Without this, the shared segment notification flips between two progress values.
    private val segmentingEpisodes = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // v3.1.50: 全局三层分段标志。generateJiuAiTingSegments 开始前设为 true，结束后设为 false。
    // SegmentNotificationHelper.startSession 检查此标志，防止并发分段导致通知栏循环。
    // 外部调用方（如 patrolSubtitleGeneration）也应先检查此标志。
    @Volatile
    var isThreeLayerSegmenting: Boolean = false
        private set

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

    // v3.0.2: 音频指纹二次判定参数
    private const val FINGERPRINT_MATCH_THRESHOLD = 0.75f
    private const val MIN_SEGMENT_MS_FOR_FINGERPRINT = 3000L

    // v3.2.2: 三层架构参数
    // 第一层指纹快筛阈值（正式库匹配）
    private const val LAYER1_FAST_SCREEN_THRESHOLD = 0.70f
    // 第三层指纹漏判召回阈值（金标准匹配）
    private const val LAYER3_RECALL_THRESHOLD = 0.82f
    // 观察池进入时的重复判定阈值（与正式库/观察池已有指纹比较）
    private const val POOL_DUPLICATE_THRESHOLD = 0.92f
    // 观察池候选最小/最大时长
    private const val POOL_MIN_DURATION_MS = 15_000L   // 15秒
    private const val POOL_MAX_DURATION_MS = 600_000L  // 600秒
    // 观察池晋升默认阈值（跨节目出现次数）
    private const val POOL_PROMOTION_THRESHOLD_DEFAULT = 3
    // 指纹hash取前N字符作为快速索引
    private const val FINGERPRINT_HASH_PREFIX_LEN = 64
    // v3.1.131: 移除SILENCE_RMS_THRESHOLD和PCM_BYTES_PER_MS（第1层改用全量指纹提取，不再需要PCM切片）

    // v3.1.12: 指纹审核日志同时写入文件（logcat + 持久日志文件）
    private fun writeFingerprintLog(context: Context, message: String) {
        try {
            val logDir = java.io.File(com.radio.app.RadioApplication.getLogDir(context), "fingerprint")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = java.io.File(logDir, "fingerprint_segment.log")
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            java.io.FileWriter(logFile, true).use { it.append("[$ts] $message\n") }
        } catch (e: Exception) {
            Log.e(TAG, "writeFingerprintLog failed: ${e.message}")
        }
    }

    // v3.1.98: 耗时格式化：将毫秒转为"X分Y秒Z毫秒"格式
    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0秒"
        val minutes = ms / 60000
        val seconds = (ms % 60000) / 1000
        val millis = ms % 1000
        return buildString {
            if (minutes > 0) append("${minutes}分")
            if (seconds > 0 || minutes > 0) append("${seconds}秒")
            append("${millis}毫秒")
        }
    }

    /**
     * v3.1.46: 从音频URL解析节目时长（毫秒）。
     * 解析URL中的时间范围（如 0700_0900），计算时长。
     * 用于durationMs无效时的兜底。
     */
    private fun getDurationFromAudioUrl(audioUrl: String?): Long {
        if (audioUrl.isNullOrBlank()) return 0L
        try {
            val path = audioUrl.substringAfterLast("/").substringBefore("?")
            val regex = Regex("(\\d{2})(\\d{2})_(\\d{2})(\\d{2})")
            var bestMatch = 0L
            var bestPos = -1
            var searchPos = 0
            while (searchPos < path.length) {
                val match = regex.find(path, searchPos) ?: break
                searchPos = match.range.first + 1
                val (_, sh, sm, eh, em) = match.groupValues
                try {
                    val startH = sh.toInt(); val startM = sm.toInt()
                    val endH = eh.toInt(); val endM = em.toInt()
                    if (startH in 0..23 && startM in 0..59 && endH in 0..23 && endM in 0..59) {
                        var start = startH * 3600000L + startM * 60000L
                        var end = endH * 3600000L + endM * 60000L
                        if (end < start) end += 24 * 3600000L
                        val duration = end - start
                        if (duration in 600_000L..43_200_000L) {
                            if (match.range.first > bestPos) {
                                bestPos = match.range.first
                                bestMatch = duration
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            return bestMatch
        } catch (_: Exception) { return 0L }
    }

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
            var merged = mergeAdjacentSegments(segments)

            // v3.0.2: 就AI听方案升级为基于音频指纹的二次判定。
            // 对 keyword 初判后的干货片段，提取音频指纹并与已保存的水分指纹比对；
            // 若匹配则改为水货，最后再次合并相邻水货。
            val waterFingerprints = try { dbHelper.getAllAudioFingerprints() } catch (_: Exception) { emptyList() }
            if (waterFingerprints.isNotEmpty() && ChromaprintExtractor.ensureLibraryLoaded(context)) {
                val fingerprintChecked = applyAudioFingerprintSecondaryCheck(
                    context, episodeId, merged, waterFingerprints
                )
                merged = mergeAdjacentSegments(fingerprintChecked)
                val flippedCount = merged.count { !it.hasVoice } - segments.count { !it.hasVoice }
                if (flippedCount > 0) {
                    val fpMsg = "关键词方案指纹审核: 在${merged.size}个片段中，发现${flippedCount}个干货片段匹配水分指纹，已转为水货"
                    Log.i(TAG, fpMsg)
                    writeFingerprintLog(context, fpMsg)
                } else {
                    val fpMsg = "关键词方案指纹审核: 所有干货片段均未匹配水分指纹，保持原分类"
                    Log.i(TAG, fpMsg)
                    writeFingerprintLog(context, fpMsg)
                }
            } else {
                val fpMsg = "关键词方案指纹审核: 跳过（水分指纹库为空或指纹引擎未就绪）"
                Log.i(TAG, fpMsg)
                writeFingerprintLog(context, fpMsg)
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
     * v3.0.2: 合并相邻同类型分段。
     * v3.1.27: 分段合并条件改为 isWaterLabel 分类 + hasVoice 双层判断。
     * "待处理"与"指纹水货"/"水货"不合并，即使 hasVoice 相同。
     */
    /**
     * v3.1.44: 验证完整PCM时长是否与节目时长匹配（5%容差）。
     * 读取 .info 文件，检查 pcmDurationMs 是否与 expectedDurationMs 相差不超过5%。
     * @return true 如果时长匹配，false 如果缺少5%以上需要重新生成
     */
    private fun validatePcmDuration(pcmFile: File, infoFile: File, expectedDurationMs: Long): Boolean {
        if (!pcmFile.exists() || pcmFile.length() <= 16000) return false
        if (!infoFile.exists()) {
            Log.w(TAG, "validatePcmDuration: ${infoFile.name} 不存在，无法验证时长")
            return false
        }
        try {
            val text = infoFile.readText()
            fun longOf(name: String): Long = Regex("$name=(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            fun intOf(name: String): Int = Regex("$name=(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val version = intOf("version")
            val pcmDurationMs = longOf("pcmDurationMs")

            if (version <= 0 || pcmDurationMs <= 0) {
                Log.w(TAG, "validatePcmDuration: info文件无效 version=$version pcmDurationMs=$pcmDurationMs")
                return false
            }

            val missingRatio = 1.0 - pcmDurationMs.toDouble() / expectedDurationMs
            if (missingRatio > 0.05) {
                Log.w(TAG, "validatePcmDuration: PCM时长不足 - pcm=${pcmDurationMs}ms, expected=${expectedDurationMs}ms, 缺少${String.format(java.util.Locale.US, "%.1f", missingRatio * 100)}% > 5%，需要重新生成")
                return false
            }
            if (missingRatio > 0) {
                Log.i(TAG, "validatePcmDuration: PCM时长略短 - pcm=${pcmDurationMs}ms, expected=${expectedDurationMs}ms, 缺少${String.format(java.util.Locale.US, "%.1f", missingRatio * 100)}%（在5%容差内）")
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "validatePcmDuration: 读取info文件异常: ${e.message}")
            return false
        }
    }

    private fun isWaterLabel(label: String?): Boolean {
        return label == "指纹水货" || label == "水货" || label == "水货(漏判召回)"
    }

    /**
     * v3.1.116: 填充段间间隙为静音段，确保时间轴连续。
     * VAD活动段只覆盖了约89.3%的时间轴，剩余10.7%的静音间隙未被任何段覆盖。
     * 对段列表按start排序，在相邻段间填充"静音"段，最后补全首段前和末段后到总时长。
     * @return 填充的静音段数量
     */
    /**
     * v3.1.135: 大间隙阈值（3分钟），超过此阈值的间隙标记为"干货"而非"静音"。
     * 根因：YAMNet可能因VAD间隔或分类偏差跳过主持人讲话区域，
     * 导致fillSilenceGaps将大段讲话区域填充为静音。
     * 广播节目中间不会有连续3分钟以上的纯静音，所以大间隙应视为疑似的干货。
     */
    private val LARGE_GAP_THRESHOLD_MS = 180000L // 3分钟

    private fun fillSilenceGaps(segments: MutableList<VoiceSegment>, totalDurationMs: Long, context: Context? = null): Int {
        if (segments.isEmpty()) return 0
        segments.sortBy { it.start }
        var fillCount = 0

        // v3.1.137: 大间隙填充时，拆分为60秒一个的小段，避免单个大段覆盖多分钟
        // 防止12-16分钟（720~960s）与相邻时间段被合并为一个不可分割的段
        // 拆分后每个小段在后续mergeSilenceToAdjacentWater中会被相邻水段分别吸收
        // 但不会被合并为一个超长段，从而保护讲话区域不被水段吞没
        // v3.1.138: 大间隙拆分的小段始终标记为"静音"，不再标记为"干货"。
        // 根因：标记为"干货"后，mergeSilenceToAdjacentWater只处理"静音"标签的段，
        // 这些大间隙干货段不会被合并到相邻水段，导致干货比例过高。
        // 改为"静音"后，mergeSilenceToAdjacentWater会将它们合并到相邻水段，
        // 既保留了YAMNet缺失区域的覆盖，又不会增加干货比例。
        fun splitGapIntoChunks(start: Long, end: Long): List<VoiceSegment> {
            val chunks = mutableListOf<VoiceSegment>()
            val CHUNK_SIZE_MS = 60000L // 60秒一个块
            var chunkStart = start
            while (chunkStart < end) {
                val chunkEnd = minOf(chunkStart + CHUNK_SIZE_MS, end)
                chunks.add(VoiceSegment().apply {
                    this.start = chunkStart
                    this.end = chunkEnd
                    hasVoice = false
                    label = "静音"
                    isSimulated = false
                })
                chunkStart = chunkEnd
            }
            return chunks
        }

        // 1. 填充首段之前的间隙（从0到第一段start）
        val firstSeg = segments.first()
        if (firstSeg.start > 50) {
            val gapMs = firstSeg.start
            val isLargeGap = gapMs >= LARGE_GAP_THRESHOLD_MS
            if (isLargeGap) {
                // v3.1.137: 大间隙拆分为小段
                // v3.1.138: 移除isDry参数，大间隙段始终标记为"静音"
                val chunks = splitGapIntoChunks(0, firstSeg.start)
                segments.addAll(0, chunks)
                fillCount += chunks.size
            } else {
                segments.add(0, VoiceSegment().apply {
                    start = 0L
                    end = firstSeg.start
                    hasVoice = false
                    label = "静音"
                    isSimulated = false
                })
                fillCount++
            }
        }

        // 2. 填充相邻段之间的间隙
        var i = 0
        while (i < segments.size - 1) {
            val curr = segments[i]
            val next = segments[i + 1]
            val gapMs = next.start - curr.end
            if (gapMs > 50) {
                val isLargeGap = gapMs >= LARGE_GAP_THRESHOLD_MS
                if (isLargeGap) {
                    // v3.1.137: 大间隙拆分为小段
                    // v3.1.138: 移除isDry参数，大间隙段始终标记为"静音"
                    val chunks = splitGapIntoChunks(curr.end, next.start)
                    segments.addAll(i + 1, chunks)
                    fillCount += chunks.size
                    i += chunks.size + 1
                } else {
                    segments.add(i + 1, VoiceSegment().apply {
                        start = curr.end
                        end = next.start
                        hasVoice = false
                        label = "静音"
                        isSimulated = false
                    })
                    fillCount++
                    i += 2
                }
            } else {
                i++
            }
        }

        // 3. 填充末段之后的间隙（从最后一段end到totalDurationMs）
        val lastSeg = segments.last()
        if (totalDurationMs > 0 && lastSeg.end < totalDurationMs - 50) {
            val gapMs = totalDurationMs - lastSeg.end
            val isLargeGap = gapMs >= LARGE_GAP_THRESHOLD_MS
            if (isLargeGap) {
                // v3.1.137: 大间隙拆分为小段
                // v3.1.138: 移除isDry参数，大间隙段始终标记为"静音"
                val chunks = splitGapIntoChunks(lastSeg.end, totalDurationMs)
                segments.addAll(chunks)
                fillCount += chunks.size
            } else {
                segments.add(VoiceSegment().apply {
                    start = lastSeg.end
                    end = totalDurationMs
                    hasVoice = false
                    label = "静音"
                    isSimulated = false
                })
                fillCount++
            }
        }

        if (fillCount > 0 && context != null) {
            writeFingerprintLog(context, "三层架构: 大间隙填充: ${fillCount}个静音段填充完成，总段数: ${segments.size}段(干${segments.count { it.hasVoice }}/水${segments.count { !it.hasVoice }})")
        }

        return fillCount
    }

    /**
     * v3.1.125: 将静音段合并到相邻水分段。
     * v3.1.126: 同时将相邻静音段合并到干货段，消除"静音+干货+静音"、"静音+干货"、"干货+静音"
     * 三种碎片化模式，让干货保持连续大段而非被静音分割。
     * fillSilenceGaps填充的静音段位于两个段之间，或紧邻段时，
     * 只要相邻有非静音段（无论水/干），都应合并而非保留孤立静音段。
     * 具体规则：
     * - 静音段前面是非静音 → 静音段合并到前一个非静音段（延长end）
     * - 静音段后面是非静音且前面不是 → 静音段合并到后一个非静音段（前移start）
     * - 静音段前后都是非静音 → 静音段合并到前一个非静音段，后一个非静音段延后吸收
     * @return 合并的静音段数量
     */
    private fun mergeSilenceToAdjacentWater(segments: MutableList<VoiceSegment>): Int {
        if (segments.size <= 1) return 0
        segments.sortBy { it.start }
        // v3.1.135: 最大水段长度限制，与mergeAdjacentSegments一致
        val MAX_WATER_SEGMENT_LENGTH_MS = 300000L // 5分钟
        var mergeCount = 0
        var i = 0
        while (i < segments.size) {
            val seg = segments[i]
            // 只处理静音段
            if (seg.label != "静音") {
                i++
                continue
            }
            val hasPrev = i > 0
            val hasNext = i < segments.size - 1
            val prevIsNonSilence = hasPrev && segments[i-1].label != "静音"
            val nextIsNonSilence = hasNext && segments[i+1].label != "静音"

            if (prevIsNonSilence) {
                // v3.1.135: 如果合并后水段长度超过最大长度，不合并
                if (isWaterLabel(segments[i-1].label) && seg.end - segments[i-1].start >= MAX_WATER_SEGMENT_LENGTH_MS) {
                    i++
                    continue
                }
                // 合并到前一个非静音段（无论水/干）
                segments[i-1].end = seg.end
                segments.removeAt(i)
                mergeCount++
                // 不移i，因为removeAt后当前元素后移
            } else if (nextIsNonSilence) {
                // v3.1.135: 如果合并后水段长度超过最大长度，不合并
                if (isWaterLabel(segments[i+1].label) && segments[i+1].end - seg.start >= MAX_WATER_SEGMENT_LENGTH_MS) {
                    i++
                    continue
                }
                // 合并到后一个非静音段（无论水/干）
                segments[i+1].start = seg.start
                segments.removeAt(i)
                mergeCount++
                // 不移i
            } else {
                i++
            }
        }
        return mergeCount
    }

    /**
     * v3.1.44: 增强合并逻辑，处理连续水分片段被短间隔分隔未合并的问题
     */
    private fun mergeAdjacentSegments(segments: List<VoiceSegment>): MutableList<VoiceSegment> {
        if (segments.isEmpty()) return mutableListOf()
        val sorted = segments.sortedBy { it.start }.map { it.copy() }.toMutableList()

        // v3.1.98: 合并间隔从10s放宽到20s，减少总分段数
        val MAX_WATER_MERGE_GAP_MS = 20000L // 20秒
        // v3.1.135: 最大水段长度限制，防止主持人讲话区域被误合并到水段。
        // 根因：YAMNet可能将12-16分钟的主持人讲话误分类为水，或YAMNet层被跳过，
        // 导致mergeAdjacentSegments Pass 2将相邻水段无限合并，吞没讲话区域。
        // 5分钟（300秒）是一个合理的上限——正常水段（纯音乐/广告）不会超过5分钟。
        val MAX_WATER_SEGMENT_LENGTH_MS = 300000L // 5分钟

        // Pass 1: 合并相邻同类型片段（原有合并逻辑）
        // v3.1.135: 对水段增加MAX_WATER_SEGMENT_LENGTH_MS限制，防止YAMNet水段与指纹水段相邻合并导致超长
        var changed = true
        while (changed) {
            changed = false
            for (i in 0 until sorted.size - 1) {
                val curr = sorted[i]
                val next = sorted[i + 1]
                if (curr.hasVoice == next.hasVoice
                        && isWaterLabel(curr.label) == isWaterLabel(next.label)
                        && next.start <= curr.end + 10) {
                    // v3.1.135: 水段合并检查长度限制
                    if (isWaterLabel(curr.label) && (next.end - curr.start) >= MAX_WATER_SEGMENT_LENGTH_MS) continue
                    curr.end = maxOf(curr.end, next.end)
                    sorted.removeAt(i + 1)
                    changed = true
                    break
                }
            }
        }

        // Pass 2: 合并被短中间片段分隔的连续水分片段
        // 场景：指纹水货 → 短静音/待处理 → 指纹水货，应合并为一个大水分段
        // v3.1.87: 不合并通过干货（hasVoice=true且非水分）片段，保护YAMNet产出的干货不被吞没
        // v3.1.90: 静音段（label="静音", hasVoice=false）作为合并屏障，防止对所有水货段无限合并
        // v3.1.135: 增加MAX_WATER_SEGMENT_LENGTH_MS限制，防止水段无限合并吞没讲话区域
        changed = true
        while (changed) {
            changed = false
            for (i in 0 until sorted.size - 1) {
                val curr = sorted[i]
                if (!isWaterLabel(curr.label)) continue
                // v3.1.135: 如果当前水段已经超过最大长度，不再合并
                if (curr.end - curr.start >= MAX_WATER_SEGMENT_LENGTH_MS) continue
                var gapMs = 0L
                var j = i + 1
                var hasDryInGap = false
                var hasSilenceInGap = false
                while (j < sorted.size) {
                    val mid = sorted[j]
                    if (isWaterLabel(mid.label)) {
                        if (!hasDryInGap && !hasSilenceInGap) {
                            // v3.1.135: 检查合并后的水段长度是否超过最大长度
                            val mergedEnd = maxOf(curr.end, mid.end)
                            if (mergedEnd - curr.start < MAX_WATER_SEGMENT_LENGTH_MS) {
                                // 找到下一个水分片段，且中间没有干货也没有静音，合并（跳过中间的非水分片段）
                                curr.end = mergedEnd
                                repeat(j - i) { sorted.removeAt(i + 1) }
                                changed = true
                            }
                        }
                        break
                    }
                    // 标记中间有干货段（hasVoice=true且非水分），不合并
                    if (mid.hasVoice && !isWaterLabel(mid.label)) {
                        hasDryInGap = true
                    }
                    // 标记中间有静音段（label="静音", hasVoice=false），作为合并屏障
                    if (!mid.hasVoice && !isWaterLabel(mid.label)) {
                        hasSilenceInGap = true
                    }
                    // 非水分：累积间隔
                    gapMs += mid.end - mid.start
                    if (gapMs > MAX_WATER_MERGE_GAP_MS) break
                    j++
                }
                if (changed) break
            }
        }

        return sorted
    }

    /**
     * v3.0.2: 对干货分段进行音频指纹二次判定。
     * 若某干货片段的指纹与已保存的水分指纹匹配，则将其改为水货。
     * 返回新的分段列表（原始列表不会被修改）。
     */
    private fun applyAudioFingerprintSecondaryCheck(
        context: Context,
        episodeId: String,
        segments: List<VoiceSegment>,
        waterFingerprints: List<AudioFingerprint>
    ): List<VoiceSegment> {
        if (segments.isEmpty() || waterFingerprints.isEmpty()) return segments

        val appContext = context.applicationContext
        var libraryChecked = false
        var libraryOk = false
        val result = segments.map { it.copy() }.toMutableList()

        // v3.1.4: 预解析所有水分指纹并构建分组，同组指纹共享匹配结果
        val parsedWaterFps = waterFingerprints.map { ChromaprintExtractor.parseFingerprint(it.fingerprint) }
        val groups = ChromaprintExtractor.buildFingerprintGroups(parsedWaterFps)
        // 指纹索引 → 组ID 的快速查找
        val fpIndexToGroupId = mutableMapOf<Int, Int>()
        groups.forEach { group -> group.memberIndices.forEach { idx -> fpIndexToGroupId[idx] = group.groupId } }
        // 组ID → 组信息 的快速查找
        val groupIdToGroup = groups.associateBy { it.groupId }
        val fpMsg0 = "指纹二次审核: 水分指纹库共${waterFingerprints.size}条，已分组为${groups.size}组"
        Log.i(TAG, fpMsg0)
        writeFingerprintLog(context, fpMsg0)

        for (i in result.indices) {
            val seg = result[i]
            if (!seg.hasVoice) continue
            if (seg.end - seg.start < MIN_SEGMENT_MS_FOR_FINGERPRINT) continue

            if (!libraryChecked) {
                libraryOk = try { ChromaprintExtractor.ensureLibraryLoaded(appContext) } catch (_: Exception) { false }
                libraryChecked = true
            }
            if (!libraryOk) break

            var tempPcmFile: File? = null
            try {
                tempPcmFile = PcmSegmentExtractor.extractSegmentPcm(appContext, episodeId, seg.start, seg.end)
                if (tempPcmFile == null || !tempPcmFile.exists() || tempPcmFile.length() <= 0) {
                    val fpMsg1 = "指纹二次审核: 片段${seg.start/1000}秒-${seg.end/1000}秒无PCM数据，跳过"
                    Log.w(TAG, fpMsg1)
                    writeFingerprintLog(context, fpMsg1)
                    continue
                }

                val fingerprint = ChromaprintExtractor.extractFingerprintFromFile(tempPcmFile)
                if (fingerprint.isNullOrBlank()) {
                    val fpMsg2 = "指纹二次审核: 片段${seg.start/1000}秒-${seg.end/1000}秒无法提取指纹，跳过"
                    Log.w(TAG, fpMsg2)
                    writeFingerprintLog(context, fpMsg2)
                    continue
                }

                // v3.1.4: 分组匹配逻辑
                var matched = false
                var matchedGroupId: Int? = null
                for (j in waterFingerprints.indices) {
                    val waterFp = waterFingerprints[j]
                    val durationRatio = minOf(seg.end - seg.start, waterFp.durationMs).toFloat() /
                            maxOf(seg.end - seg.start, waterFp.durationMs).toFloat()
                    if (durationRatio < 0.4f) continue  // 时长差异过大，跳过

                    // 先用标准匹配
                    val detail = ChromaprintExtractor.isMatch(fingerprint, waterFp.fingerprint, FINGERPRINT_MATCH_THRESHOLD)
                    if (detail) {
                        matched = true
                        matchedGroupId = fpIndexToGroupId[j]
                        val fpMsg3 = "指纹二次审核: 片段${seg.start/1000}秒-${seg.end/1000}秒直接匹配指纹 #${j}（来源: ${waterFp.episodeId}）"
                        Log.i(TAG, fpMsg3)
                        writeFingerprintLog(context, fpMsg3)
                        break
                    }

                    // 接近阈值时，尝试用伸缩容错比较
                    val stretchDetail = ChromaprintExtractor.compareFingerprintsWithStretch(fingerprint, waterFp.fingerprint)
                    if (stretchDetail.similarity >= FINGERPRINT_MATCH_THRESHOLD) {
                        matched = true
                        matchedGroupId = fpIndexToGroupId[j]
                        val fpMsg4 = "指纹二次审核: 片段${seg.start/1000}秒-${seg.end/1000}秒伸缩匹配指纹 #${j}（来源: ${waterFp.episodeId}，相似度: ${"%.0f".format(stretchDetail.similarity * 100)}%）"
                        Log.i(TAG, fpMsg4)
                        writeFingerprintLog(context, fpMsg4)
                        break
                    }

                    // 如果接近阈值（≥50%），尝试同组其他指纹
                    val groupId = fpIndexToGroupId[j]
                    if (stretchDetail.similarity >= 0.50f && groupId != null) {
                        val group = groupIdToGroup[groupId] ?: continue
                        for (memberIdx in group.memberIndices) {
                            if (memberIdx == j) continue
                            val memberFp = waterFingerprints[memberIdx]
                            val memberDurationRatio = minOf(seg.end - seg.start, memberFp.durationMs).toFloat() /
                                    maxOf(seg.end - seg.start, memberFp.durationMs).toFloat()
                            if (memberDurationRatio < 0.4f) continue
                            // 对组内成员先用伸缩容错比较
                            val memberDetail = ChromaprintExtractor.compareFingerprintsWithStretch(fingerprint, memberFp.fingerprint)
                            if (memberDetail.similarity >= FINGERPRINT_MATCH_THRESHOLD) {
                                matched = true
                                matchedGroupId = groupId
                                val fpMsg5 = "指纹二次审核: 片段${seg.start/1000}秒-${seg.end/1000}秒同组匹配指纹 #${memberIdx}（来源: ${memberFp.episodeId}，相似度: ${"%.0f".format(memberDetail.similarity * 100)}%）"
                                Log.i(TAG, fpMsg5)
                                writeFingerprintLog(context, fpMsg5)
                                break
                            }
                        }
                        if (matched) break
                    }
                }

                if (matched) {
                    seg.hasVoice = false
                    seg.label = "水货"
                    val fpMsg6 = "指纹二次审核: 片段${seg.start/1000}秒-${seg.end/1000}秒被判定为水货（匹配指纹组 #${matchedGroupId}）"
                    Log.i(TAG, fpMsg6)
                    writeFingerprintLog(context, fpMsg6)
                }
            } catch (e: Exception) {
                val fpMsg7 = "指纹二次审核: 片段${seg.start/1000}秒-${seg.end/1000}秒处理异常: ${e.message}"
                Log.e(TAG, fpMsg7)
                writeFingerprintLog(context, fpMsg7)
            } finally {
                try { tempPcmFile?.delete() } catch (_: Exception) {}
            }
        }

        return result
    }

    /**
     * Pre-segment an episode: generate fixed 15-min segments and save to DB.
     * Called before subtitle generation starts.
     */
    fun preSegmentFixed(context: Context, episodeId: String, durationMs: Long) {
        // v3.1.36: 使用PRIORITY_MANUAL确保预分段进度通知始终可见，不被后台会话拦截
        val episodeTitle = try {
            val info = RadioDatabaseHelper.getInstance(context).getEpisodeInfo(episodeId)
            buildSegmentNotificationTitle(episodeId, info?.title)
        } catch (_: Exception) { episodeId }
        // v3.1.36: 使用PRIORITY_MANUAL，与手动分段同样的优先级，确保通知始终显示
        SegmentNotificationHelper.startSession(context, episodeId, episodeTitle, SegmentNotificationHelper.PRIORITY_MANUAL)
        SegmentNotificationHelper.update(context, episodeId, episodeTitle, 0, "预分段(15分钟固定)")
        try {
            val dbHelper = RadioDatabaseHelper.getInstance(context)
            // Check if any non-simulated (real AI) segments already exist
            val existing = dbHelper.getVoiceSegments(episodeId)
            val hasAnyRealSegments = existing.isNotEmpty() && existing.any { !it.isSimulated }
            if (hasAnyRealSegments) {
                Log.i(TAG, "preSegmentFixed: episode=$episodeId already has ${existing.count { !it.isSimulated }} real segments (total ${existing.size}), skipping")
                // v2.4.124: Write to precache log for visibility
                val logFile = java.io.File(com.radio.app.RadioApplication.getLogDir(context), "precache/precache.log")
                logFile.parentFile?.mkdirs()
                logFile.appendText("[${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}] preSegmentFixed: episode=$episodeId already has ${existing.count { !it.isSimulated }} real segments (total ${existing.size}), skipping (durationMs=$durationMs)\n")
                SegmentNotificationHelper.update(context, episodeId, episodeTitle, 1000, "预分段(已存在真实分段)")
                SegmentNotificationHelper.endSession(context, episodeId)
                return
            }
            SegmentNotificationHelper.update(context, episodeId, episodeTitle, 500, "预分段(生成固定分段)")
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
            SegmentNotificationHelper.update(context, episodeId, episodeTitle, 1000, "预分段完成")
        } catch (e: Exception) {
            Log.e(TAG, "preSegmentFixed failed: ${e.message}")
        }
        SegmentNotificationHelper.endSession(context, episodeId)
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
            } else if (settings.aiModel == com.radio.app.models.AppSettings.AI_MODEL_JIU_AI_TING) {
                // v3.2.2: 就AI听方案三层架构
                // 第一层：指纹快筛 → 第二层：双模型判定(VAD+YAMNet) → 第三层：指纹漏判召回
                val fpMsgScheme = "postSegmentKeyword: 就AI听三层架构方案 for episode=$episodeId"
                Log.i(TAG, fpMsgScheme)
                writeFingerprintLog(context, fpMsgScheme)

                val audioUrl = try {
                    RadioDatabaseHelper.getInstance(context).getEpisodeInfo(episodeId)?.audioUrl
                } catch (_: Exception) { null }

                val jiuAiTingResult = generateJiuAiTingSegments(context, episodeId, durationMs, audioUrl)
                if (jiuAiTingResult != null && jiuAiTingResult.segments.isNotEmpty()) {
                    segments = jiuAiTingResult.segments
                    engineName = jiuAiTingResult.engineName
                    processingTimeMs = jiuAiTingResult.processingTimeMs
                    audioDurationMs = durationMs

                    val fpMsgDone = "就AI听三层架构方案完成: ${segments.size}个片段（原干货${jiuAiTingResult.totalDrySegments}段，第一层快筛${jiuAiTingResult.layer1MatchCount}段，第三层召回${jiuAiTingResult.layer3RecallCount}段）"
                    Log.i(TAG, fpMsgDone)
                    writeFingerprintLog(context, fpMsgDone)
                } else {
                    segments = emptyList()
                    engineName = "就AI听"
                    processingTimeMs = System.currentTimeMillis() - segStartTime
                    audioDurationMs = durationMs
                    Log.w(TAG, "postSegmentKeyword: 就AI听三层架构方案无结果 for episode=$episodeId")
                }
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
        // v3.1.53: 检查全局分段标志，防止并发分段导致通知栏循环。
        // 根因：patrolSubtitleGeneration 每秒扫描节目并调用 preSegmentAudio，如果多个节目
        // 连续触发，通知栏会快速出现消失（startSession/endSession 循环），造成闪烁。
        // patrolSubtitleGeneration 外层已检查此标志，但 patrolSubtitleGeneration 检查后
        // 到 preSegmentAudio 执行之间仍有竞态窗口，所以 preSegmentAudio 自身也要检查。
        if (SegmentNotificationHelper.isSegmenting || isThreeLayerSegmenting) {
            Log.w(TAG, "preSegmentAudio: global segmentation in progress (isSegmenting=true), rejecting request for episode=$episodeId")
            return false
        }

        // v2.4.185: Prevent concurrent analyses for the same episode. The shared segment
        // notification flips between two progress values when two tasks run at once.
        if (!segmentingEpisodes.add(episodeId)) {
            Log.i(TAG, "preSegmentAudio: episode=$episodeId already being segmented, skipping")
            return false
        }

        val dbHelper = RadioDatabaseHelper.getInstance(context)

        try {
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

            val episodeInfo = dbHelper.getEpisodeInfo(episodeId)
            val episodeTitle = buildSegmentNotificationTitle(episodeId, episodeInfo?.title)

            // v2.4.186: Start a notification session for this episode only when we are actually
            // going to run analysis. Because the helper uses a single notification ID, a later
            // manual segment request will take over the session, and stale pre-segment progress
            // callbacks will be dropped. Background sessions use the lower priority.
            val sessionStarted = SegmentNotificationHelper.startSession(
                context, episodeId, episodeTitle, SegmentNotificationHelper.PRIORITY_BACKGROUND
            )
            if (!sessionStarted) {
                Log.i(TAG, "preSegmentAudio: notification session not started (another session is active), running silently for episode=$episodeId")
            }

            // v2.4.178: Large PCM files are now handled by memory-mapped sample access in
            // AudioSegmentAnalyzer, so the artificial 120MB limit is removed. Pre-segmentation
            // can run on any episode whose full PCM has already been decoded.
            // v3.1.97: 使用用户选择的方案
            Log.i(TAG, "preSegmentAudio: running audio segmentation for episode=$episodeId")

            val settings = AppSettings.getInstance(context)
            val segStartTime = System.currentTimeMillis()
            val segments: List<VoiceSegment>
            val engineName: String
            val processingTimeMs: Long
            val audioDurationMs: Long

            // v2.4.186: Use non-blocking mode so background pre-segmentation skips when
            // another audio analysis (manual or another pre-segment task) is already running.
            if (settings.aiModel == AppSettings.AI_MODEL_JIU_AI_TING) {
                Log.i(TAG, "preSegmentAudio: 使用就AI听三层架构方案 for episode=$episodeId")
                val jiuAiTingResult = generateJiuAiTingSegments(
                    context, episodeId, durationMs, audioUrl,
                    { permille, _, _ ->
                        SegmentNotificationHelper.update(context, episodeId, episodeTitle, permille)
                    }
                )
                if (jiuAiTingResult != null && jiuAiTingResult.segments.isNotEmpty()) {
                    segments = jiuAiTingResult.segments
                    engineName = jiuAiTingResult.engineName
                    processingTimeMs = jiuAiTingResult.processingTimeMs
                    audioDurationMs = durationMs
                } else {
                    Log.w(TAG, "preSegmentAudio: 就AI听三层架构无结果，回退VAD+YAMNet for episode=$episodeId")
                    val result = tryGenerateAudioSegments(
                        context, episodeId, durationMs, audioUrl,
                        progressCallback = { permille, _, _ ->
                            SegmentNotificationHelper.update(context, episodeId, episodeTitle, permille)
                        },
                        blocking = false
                    )
                    segments = result?.segments ?: emptyList()
                    engineName = result?.engineName ?: "VAD+YAMNet"
                    processingTimeMs = result?.processingTimeMs ?: (System.currentTimeMillis() - segStartTime)
                    audioDurationMs = result?.audioDurationMs ?: durationMs
                }
            } else {
                val result = tryGenerateAudioSegments(
                    context, episodeId, durationMs, audioUrl,
                    progressCallback = { permille, _, _ ->
                        SegmentNotificationHelper.update(context, episodeId, episodeTitle, permille)
                    },
                    blocking = false
                )
                segments = result?.segments ?: emptyList()
                engineName = result?.engineName ?: "VAD+YAMNet"
                processingTimeMs = result?.processingTimeMs ?: (System.currentTimeMillis() - segStartTime)
                audioDurationMs = result?.audioDurationMs ?: durationMs
            }

            if (segments.isEmpty()) {
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
                Log.e(TAG, "preSegmentAudio: failed to save segment analysis info: ${e.message}")
            }

            Log.i(TAG, "preSegmentAudio: saved ${segments.size} segments for episode=$episodeId (engine=${engineName}, time=${processingTimeMs}ms)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "preSegmentAudio failed: ${e.message}")
            return false
        } finally {
            // v2.4.186: Always end the notification session for this episode, but only
            // dismiss the notification if this episode still owns the active session.
            // v3.1.4: 只有当分析实际开始时才结束会话，避免通知被过早取消
            if (segmentingEpisodes.contains(episodeId)) {
                SegmentNotificationHelper.endSession(context, episodeId)
            }
            segmentingEpisodes.remove(episodeId)
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
     * v2.4.186: Background callers set [blocking] to false so they skip when another audio
     * analysis is already running, instead of piling up behind the lock.
     */
    private fun tryGenerateAudioSegments(
        context: Context,
        episodeId: String,
        durationMs: Long,
        audioUrl: String? = null,
        progressCallback: ((Int, Long, Long) -> Unit)? = null,
        blocking: Boolean = true
    ): AudioSegmentAnalyzer.SegmentAnalysisResult? {
        try {
            val result = AudioSegmentAnalyzer.analyzeEpisode(
                context, episodeId, durationMs, audioUrl,
                progressCallback = progressCallback, blocking = blocking
            )
            Log.i(TAG, "tryGenerateAudioSegments: got ${result.segments.size} segments from audio analysis (engine=${result.engineName}, time=${result.processingTimeMs}ms)")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "tryGenerateAudioSegments failed: ${e.message}")
            return null
        }
    }

    // v3.1.15: 就AI听方案结果（含指纹匹配统计）
    // v3.2.2: 增加三层架构各层统计
    data class JiuAiTingResult(
        val segments: List<VoiceSegment>,
        val engineName: String,
        val processingTimeMs: Long,
        val matchedCount: Int,
        val totalDrySegments: Int,
        val layer1MatchCount: Int = 0,
        val layer3RecallCount: Int = 0,
        val observationPoolNewCount: Int = 0,
        val observationPoolHitCount: Int = 0
    )

    /**
     * v3.2.3: 就AI听方案 - 三层架构（修正层顺序）
     * 第一层：指纹快筛 → 第二层：双模型判定(VAD+YAMNet，仅处理第一层剩余部分) → 第三层：指纹漏判召回
     *
     * 整体流程：
     * 1. 第一层指纹快筛：使用正式指纹库对分段做快速指纹匹配，命中则直接标记为水货
     * 2. 第二层双模型判定：仅对第一层未命中的干货片段，提取各片段PCM送入VAD+YAMNet做干湿细分
     * 3. 合并相邻同类型片段
     * 4. 第三层指纹漏判召回：仅对干货片段，仅用金标准指纹查询，匹配则改为水货并合并相邻水分
     * 5. 候选指纹观察池处理：第三层合并得到的大水分片段进入观察池
     */
    fun generateJiuAiTingSegments(
        context: Context,
        episodeId: String,
        durationMs: Long,
        audioUrl: String? = null,
        progressCallback: ((Int, Long, Long) -> Unit)? = null
    ): JiuAiTingResult? {
        // v3.1.108: 必须在try块外声明，确保finally块能访问
        var savedAnalysisThread: Thread? = null
        try {
            // v3.1.50: 检查全局三层分段标志，防止并发分段导致通知栏循环
            if (isThreeLayerSegmenting) {
                Log.w(TAG, "generateJiuAiTingSegments: 全局三层分段中，拒绝并发请求 for episode=$episodeId")
                return null
            }
            isThreeLayerSegmenting = true
            // v3.1.52: 修复关键bug——isSegmenting 必须在 startSession 成功之后设置。
            // 根因：v3.1.51 在 startSession 之前设置 isSegmenting=true，导致自身的
            // startSession 被 isSegmenting 检查拒绝（永远返回 false），通知栏永远不会启动。
            // 现在先执行 startSession，成功后再设置 isSegmenting，阻止外部并发请求。
        val segStartTime = System.currentTimeMillis()
        // v3.1.108: 设置当前分析线程引用，使 cancelCurrentAnalysis() 能正确中断线程
        // 原来 generateJiuAiTingSegments 未设置 currentAnalysisThread，导致：
        // 1. cancelCurrentAnalysis() 只能设 analysisCancelled 标志，不能中断线程
        // 2. 线程中断标志不清，后续层检查 Thread.interrupted() 时误判为"用户取消"
        savedAnalysisThread = AudioSegmentAnalyzer.getCurrentAnalysisThread()
        AudioSegmentAnalyzer.setCurrentAnalysisThread(Thread.currentThread())
        // v3.1.46: 校验durationMs，如果为0或<=60000则使用默认值2小时
        // 避免因durationMs无效导致shouldRunLayer2=false（仅运行第1层）或瞬间完成
        // v3.1.47: 增加最小时长限制（900秒=15分钟），确保即使durationMs>60000但过小时，
        // 也能生成足够的分段，避免"整个节目一个分段"的问题
        val rawDuration = if (durationMs > 60000) durationMs else {
            Log.w(TAG, "generateJiuAiTingSegments: durationMs=$durationMs 无效，使用默认值7200000ms(2小时) for episode=$episodeId")
            // 尝试从URL解析实际时长
            val urlDuration = getDurationFromAudioUrl(audioUrl)
            if (urlDuration > 60000) urlDuration else 7200_000L
        }
        val effectiveDurationMs = maxOf(rawDuration, 900_000L) // 最少15分钟
        val fpMsgStart = "generateJiuAiTingSegments: 就AI听三层架构方案 for episode=$episodeId, durationMs=$effectiveDurationMs(原=$durationMs)"
        Log.i(TAG, fpMsgStart)
        writeFingerprintLog(context, fpMsgStart)

        // v3.1.32: 清除历史取消标志，避免VAD因上次取消信号而立即失败
        AudioSegmentAnalyzer.resetCancellation()
        // v3.1.95: 同时清除线程中断标志。cancelCurrentAnalysis() 设置了 analysisCancelled
        // 并调用了 thread.interrupt()。resetCancellation 只清除了标志，但线程的中断状态
        // 仍保留，导致后续层或下一次运行的第一层检查 Thread.interrupted() 时立即退出。
        // Thread.interrupted() 会检查并清除中断状态，确保后续操作不受影响。
        Thread.interrupted() // clear interrupt flag
        SegmentNotificationHelper.reset()

        val dbHelper = RadioDatabaseHelper.getInstance(context)

        // v3.1.28: 启动通知会话，显示三层分段进度
        // v3.1.52: 先启动通知会话，成功后再设置 isSegmenting 标志。
        // 顺序不可颠倒——先设 isSegmenting 会导致自身的 startSession 被拒绝。
        val episodeInfo = try { dbHelper.getEpisodeInfo(episodeId) } catch (_: Exception) { null }
        val episodeTitle = buildSegmentNotificationTitle(episodeId, episodeInfo?.title)
        val sessionStarted = SegmentNotificationHelper.startSession(
            context, episodeId, episodeTitle, SegmentNotificationHelper.PRIORITY_MANUAL
        )
        // v3.1.52: startSession 成功后设置全局标志，阻止外部并发请求（如 patrolSubtitleGeneration）
        // 此时 startSession 已通过，不会再被自身拦截
        if (sessionStarted) {
            SegmentNotificationHelper.isSegmenting = true
        }
        if (!sessionStarted) {
            Log.w(TAG, "generateJiuAiTingSegments: 通知会话未启动（已有更高优先级会话）")
        }

        // ========== 获取指纹库 ==========
        // 正式指纹库（金标准+自动晋升）→ 第一层使用
        val formalLibrary = try { dbHelper.getFormalLibraryFingerprints() } catch (_: Exception) { emptyList() }
        // v3.1.129: 获取指纹分组，用于第一层缩减对比量
        val fingerprintGroups = try { dbHelper.getAllFingerprintGroups() } catch (_: Exception) { emptyList() }
        val groupMembers = try { dbHelper.getAllGroupMembers() } catch (_: Exception) { emptyList() }
        // 金标准指纹（仅人工录入）→ 第三层使用
        val goldStandardFingerprints = try { dbHelper.getGoldStandardFingerprints() } catch (_: Exception) { emptyList() }

        val fpMsgLib = "三层架构: 正式指纹库${formalLibrary.size}条（金标准${goldStandardFingerprints.size}条，自动晋升${formalLibrary.size - goldStandardFingerprints.size}条）"
        Log.i(TAG, fpMsgLib)
        writeFingerprintLog(context, fpMsgLib)

        // ========== 第一层：对完整PCM滑动窗口指纹匹配 ==========
        // 不管之前有没有分段，都从头开始，对节目完整PCM滑动窗口匹配
        // 匹配到的部分标记为水货，匹配剩余部分交给第2层
        var audioEngineName = "就AI听"
        val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(context)
        val fullPcmFile = File(pcmCacheDir, "${episodeId}_full.pcm")
        val fullInfoFile = File(pcmCacheDir, "${episodeId}_full.info")

        // v3.1.44: 检查完整PCM时长是否与节目时长匹配，缺少5%以上重新生成
        val pcmSourceFile = if (fullPcmFile.exists() && fullPcmFile.length() > 16000) {
            if (validatePcmDuration(fullPcmFile, fullInfoFile, effectiveDurationMs)) {
                // v3.1.86: 输出PCM信息到日志，验证采样率是否16kHz
                try {
                    val infoLines = if (fullInfoFile.exists()) fullInfoFile.readLines() else emptyList()
                    val sampleRateLine = infoLines.find { it.startsWith("sampleRate=") }
                    val pcmDurationLine = infoLines.find { it.startsWith("pcmDurationMs=") }
                    if (sampleRateLine != null) {
                        Log.i(TAG, "三层架构: PCM信息: $sampleRateLine for episode=$episodeId")
                        val sampleRate = sampleRateLine.substringAfter("sampleRate=").toIntOrNull()
                        if (sampleRate != null && sampleRate != 16000) {
                            Log.w(TAG, "三层架构: PCM采样率异常! 期望16000Hz, 实际${sampleRate}Hz for episode=$episodeId")
                        }
                    } else {
                        Log.w(TAG, "三层架构: PCM信息文件缺少sampleRate字段 for episode=$episodeId, content=${infoLines.joinToString(";")}")
                    }
                    // 验证PCM文件大小与实际时长是否匹配16kHz
                    val pcmBytes = fullPcmFile.length()
                    val pcmDurationMsAt16kHz = pcmBytes * 1000L / (16000L * 2L)
                    if (pcmDurationLine != null) {
                        val infoDurationMs = pcmDurationLine.substringAfter("pcmDurationMs=").toLongOrNull()
                        if (infoDurationMs != null && infoDurationMs > 0) {
                            val durationRatio = pcmDurationMsAt16kHz.toDouble() / infoDurationMs.toDouble()
                            if (durationRatio < 0.9 || durationRatio > 1.1) {
                                Log.w(TAG, "三层架构: PCM时长异常! 文件大小推算=${pcmDurationMsAt16kHz}ms, info文件=${infoDurationMs}ms, ratio=$durationRatio for episode=$episodeId")
                            } else {
                                Log.i(TAG, "三层架构: PCM时长验证通过: 文件推算=${pcmDurationMsAt16kHz}ms, info=${infoDurationMs}ms for episode=$episodeId")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "三层架构: 读取PCM信息文件失败: ${e.message}")
                }
                fullPcmFile
            } else {
                val fpMsgPcmDuration = "三层架构: 完整PCM时长不匹配，重新生成 for episode=$episodeId"
                Log.w(TAG, fpMsgPcmDuration)
                writeFingerprintLog(context, fpMsgPcmDuration)
                null
            }
        } else {
            null
        }

        val mergedAfterLayer1: List<VoiceSegment>
        var layer1MatchCount = 0
        var layer2DrySegments = 0
        var layer2WaterSegments = 0
        var layer3RecallCount = 0
        var observationPoolNewCount = 0
        var observationPoolHitCount = 0
        // v3.1.97: 各层耗时统计
        val layer1StartTime = System.currentTimeMillis()
        var layer1TimeMs = 0L
        var layer2TimeMs = 0L
        var layer3TimeMs = 0L

        if (pcmSourceFile != null && formalLibrary.isNotEmpty() && ChromaprintExtractor.ensureLibraryLoaded(context)) {
            // 滑动窗口指纹匹配，传递进度回调以更新通知
            // v3.1.46: 修复第1层进度值错误——permille本身就是0-1000的千分比，不需要除以10
            // 原代码 permille/10 导致进度始终限制在0-100（即0%-10%），用户看到的进度条永远走不完
            val slidingProgressCallback: ((Int, Long, Long) -> Unit)? = { permille, _, _ ->
                SegmentNotificationHelper.update(context, episodeId, episodeTitle, permille, "第1层指纹快筛")
            }
            val slidingResult = applyLayer1SlidingWindow(context, episodeId, pcmSourceFile, effectiveDurationMs, formalLibrary, slidingProgressCallback, fingerprintGroups, groupMembers, dbHelper)
            mergedAfterLayer1 = slidingResult
            layer1MatchCount = slidingResult.count { it.label == "指纹水货" }
            audioEngineName = "滑动窗口指纹"
            layer1TimeMs = System.currentTimeMillis() - layer1StartTime
            Log.i(TAG, "三层架构: 第一层滑动窗口完成，匹配${layer1MatchCount}个水货段，共${mergedAfterLayer1.size}个片段，耗时${formatDuration(layer1TimeMs)} for episode=$episodeId")
            writeFingerprintLog(context, "三层架构: 第1层耗时${formatDuration(layer1TimeMs)}，匹配${layer1MatchCount}个水货段，共${mergedAfterLayer1.size}个片段")
        } else {
            // 无PCM或无指纹库，运行全量VAD+YAMNet获取真实分段
            val fallbackReason = when {
                pcmSourceFile == null -> "PCM文件不存在"
                formalLibrary.isEmpty() -> "正式指纹库为空"
                !ChromaprintExtractor.ensureLibraryLoaded(context) -> "指纹引擎未就绪"
                else -> "未知原因"
            }
            Log.w(TAG, "三层架构: 第一层滑动窗口跳过（$fallbackReason），运行全量VAD+YAMNet")

            val fullVadResult = try {
                AudioSegmentAnalyzer.analyzeEpisode(context, episodeId, effectiveDurationMs, audioUrl, progressCallback)
            } catch (e: Exception) {
                Log.w(TAG, "三层架构: 全量VAD+YAMNet失败: ${e.message}")
                null
            }
            if (fullVadResult != null && fullVadResult.segments.size >= 2) {
                mergedAfterLayer1 = mergeAdjacentSegments(fullVadResult.segments.map { it.copy() })
                audioEngineName = fullVadResult.engineName
                layer1TimeMs = System.currentTimeMillis() - layer1StartTime
                Log.i(TAG, "三层架构: 全量VAD+YAMNet生成${mergedAfterLayer1.size}个真实分段，耗时${formatDuration(layer1TimeMs)} for episode=$episodeId")
                writeFingerprintLog(context, "三层架构: 第1层全量VAD+YAMNet耗时${formatDuration(layer1TimeMs)}，${mergedAfterLayer1.size}个分段")
            } else {
                // VAD也无结果，使用固定分段兜底
                val fixedSegs = generateFixedSegments(effectiveDurationMs)
                mergedAfterLayer1 = fixedSegs
                layer1TimeMs = System.currentTimeMillis() - layer1StartTime
                Log.w(TAG, "三层架构: 无有效分段，生成固定分段(${fixedSegs.size}个)兜底，耗时${formatDuration(layer1TimeMs)} for episode=$episodeId")
                writeFingerprintLog(context, "三层架构: 第1层固定分段兜底耗时${formatDuration(layer1TimeMs)}，${fixedSegs.size}个分段")
            }
        }

        // v3.1.50: 如果第1层处理期间用户取消了通知，不清除取消标志，也不重建会话。
        // 根因分析：原代码 resetCancellation + restartSession 导致通知栏出现消失循环。
        // 用户取消通知意味着不想看到进度，后续第2/3层静默运行即可，无需恢复通知。
        // 取消标志保持为true，后续层级的进度更新会被 SegmentNotificationHelper.update 中的 cancelled 检查拦截。
        // v3.1.108: 区分取消来源，记录详细原因
        if (AudioSegmentAnalyzer.isAnalysisCancelled()) {
            val cancelSource = if (Thread.currentThread().isInterrupted) "thread.interrupt" else "analysisCancelled flag"
            val fpMsgCancel = "三层架构: 第1层期间收到取消信号(cancelSource=$cancelSource)，后续层静默运行 for episode=$episodeId"
            Log.i(TAG, fpMsgCancel)
            writeFingerprintLog(context, fpMsgCancel)
        }

        // v3.1.129: 第一层取消后，重置取消标志让VAD正常运行
        // 第一层取消是因为超时，但VAD和YAMNet应该继续运行产生有效分段
        if (AudioSegmentAnalyzer.isAnalysisCancelled()) {
            AudioSegmentAnalyzer.resetCancellation()
            Thread.interrupted() // 清除中断标志
            Log.i(TAG, "三层架构: 第1层取消后重置取消标志，第2层继续运行 for episode=$episodeId")
        }

        // v3.1.110: 记录第1层完成时的取消状态到指纹日志，用于排查中断来源
        // 不清除取消标志，让第2层VAD自然处理——如果analysisCancelled为true，VAD会返回空结果，
        // 第2层会退化为仅使用pending段+YAMNet，结果仍合理，不会出现1个片段
        val isCancelledBeforeLayer2 = AudioSegmentAnalyzer.isAnalysisCancelled()
        val isInterruptedBeforeLayer2 = Thread.currentThread().isInterrupted
        if (isCancelledBeforeLayer2 || isInterruptedBeforeLayer2) {
            writeFingerprintLog(context, "三层架构: 第2层前取消状态: analysisCancelled=$isCancelledBeforeLayer2, isInterrupted=$isInterruptedBeforeLayer2")
        }

        if (mergedAfterLayer1.isEmpty()) {
            val fpMsgEmpty = "generateJiuAiTingSegments: 无分段结果"
            Log.w(TAG, fpMsgEmpty)
            writeFingerprintLog(context, fpMsgEmpty)
            SegmentNotificationHelper.endSession(context, episodeId)
            return null
        }

        val totalPendingSegments = mergedAfterLayer1.count { it.label == "待处理" }
        // v3.1.40: 允许跳过第2/3层的唯一条件是节目时长不足（<60秒）
        // 只要有足够的节目时长，始终运行全量VAD+YAMNet和第三层指纹召回
        // 如果PCM文件不存在，会在第2层运行时自动重新生成（见第二层VAD跳过逻辑）
        val shouldRunLayer2 = effectiveDurationMs > 60000

        if (shouldRunLayer2) {
        val layer2StartTime = System.currentTimeMillis()
        // ========== 第二层：优化三层架构（VAD-only + 区间YAMNet） ==========
        // v3.1.83: 优化流程：
        // 1. 第一层指纹匹配 → 指纹水货段 + 待处理段（指纹未覆盖区间）
        // 2. 第二层-A VAD：对完整PCM运行VAD-only，获取全时间轴活动段
        // 3. 计算交集：指纹未覆盖区间 ∩ VAD活动段 → YAMNet待处理区间
        // 4. 过滤 < 1.5s 的区间
        // 5. 第二层-B YAMNet：对每个区间独立做子段提取推理（零拷贝+padding）
        // 6. 拼图式合并：指纹段 + YAMNet段，边界冲突时指纹优先
        val waterSegmentsAfterLayer1 = mergedAfterLayer1.filter { it.label == "指纹水货" }
        val pendingSegments = mergedAfterLayer1.filter { it.label == "待处理" }

        val vadModelDir = AudioSegmentAnalyzer.getModelDir(context)
        val vadModelsReady = AudioSegmentAnalyzer.isModelInstalled(vadModelDir)

        var mergedAfterLayer2: List<VoiceSegment>
        if (vadModelsReady && pcmSourceFile != null) {
            val pcmFileSize = if (pcmSourceFile.exists()) pcmSourceFile.length() else 0L
            val pcmDurationMs = (pcmFileSize * 1000L / (AudioSegmentAnalyzer.YAMNET_SAMPLE_RATE * 2)).toLong() // 16bit mono
            val dualModelInfo = "三层架构: 双模型音频详情 — PCM文件=${pcmSourceFile.name}, 大小=${pcmFileSize / 1024}KB, 音频时长=${pcmDurationMs / 1000}秒, 有效分段时长=${effectiveDurationMs / 1000}秒, VAD模型=Silero, YAMNet模型=TFLite(${AudioSegmentAnalyzer.YAMNET_SAMPLE_RATE}Hz/${AudioSegmentAnalyzer.YAMNET_WINDOW_SAMPLES}样本/帧)"
            Log.i(TAG, dualModelInfo)
            writeFingerprintLog(context, dualModelInfo)
            Log.i(TAG, "三层架构: 第二层-A VAD + 第二层-B YAMNet（区间推理） for episode=$episodeId")
            try {
                // ===== 第二层-A：VAD-only，获取全时间轴活动段 =====
                SegmentNotificationHelper.update(context, episodeId, episodeTitle, 200, "第2层-A VAD活动检测")
                val vadStartTime = System.currentTimeMillis()
                val speechRanges = AudioSegmentAnalyzer.runVadOnly(
                    context, pcmSourceFile, effectiveDurationMs
                ) { permille ->
                    val mapped = 200 + (permille * 150 / 1000).coerceIn(0, 150)
                    SegmentNotificationHelper.update(context, episodeId, episodeTitle, mapped, "第2层-A VAD活动检测")
                }
                val vadDurationMs = System.currentTimeMillis() - vadStartTime
                val vadTotalActivityMs = speechRanges.sumOf { it.durationMs }
                val vadActivityRatio = if (effectiveDurationMs > 0) "%.1f".format(vadTotalActivityMs * 100.0 / effectiveDurationMs) else "0"
                writeFingerprintLog(context, "三层架构: 第2层-A VAD完成: ${speechRanges.size}个活动段（总${vadTotalActivityMs}ms, 占比${vadActivityRatio}%）, 耗时${formatDuration(vadDurationMs)}")

                // ===== VAD结果（可能为空，但不允许降级） =====
                // v3.1.85: 移除所有降级路径，VAD无活动时直接用pending段，YAMNet始终运行
                val rawIntervals = mutableListOf<Pair<Long, Long>>()
                if (speechRanges.isEmpty()) {
                    Log.w(TAG, "三层架构: VAD无活动段，直接使用pending段作为YAMNet区间 for episode=$episodeId")
                    for (pending in pendingSegments) {
                        rawIntervals.add(pending.start to pending.end)
                    }
                    // v3.1.139: VAD无活动段且pending也为空时，使用全音频范围作为YAMNet区间。
                    // 根因：第一层被取消后pending段可能为空（第一层未完成指纹扫描），
                    // 导致YAMNet区间为空，YAMNet不运行，最终结果退化为第一层少量结果（0干货）。
                    // 使用全音频范围确保YAMNet始终运行，依靠YAMNet自身的分类能力产生分段。
                    if (pendingSegments.isEmpty()) {
                        Log.w(TAG, "三层架构: VAD无活动段且pending为空，使用全音频范围(${effectiveDurationMs}ms)作为YAMNet区间 for episode=$episodeId")
                        writeFingerprintLog(context, "三层架构: VAD无活动段且pending为空，使用全音频范围作为YAMNet区间")
                        rawIntervals.add(0L to effectiveDurationMs)
                    }
                } else {
                    for (pending in pendingSegments) {
                        for (speech in speechRanges) {
                            val interStart = maxOf(pending.start, speech.startMs)
                            val interEnd = minOf(pending.end, speech.endMs)
                            if (interEnd > interStart) {
                                rawIntervals.add(interStart to interEnd)
                            }
                        }
                    }
                }

                // 过滤<1.5s，但保留至少1个区间（不允许降级跳过）
                var yamnetIntervals = rawIntervals.filter { it.second - it.first >= 1500 }
                if (yamnetIntervals.isEmpty() && rawIntervals.isNotEmpty()) {
                    Log.w(TAG, "三层架构: 所有区间<1.5s，保留最长1个区间 for episode=$episodeId")
                    yamnetIntervals = listOf(rawIntervals.maxByOrNull { it.second - it.first }!!)
                }

                // v3.1.86: 详细日志，输出VAD活动段和YAMNet区间的详细信息
                val vadDetail = if (speechRanges.size <= 10) {
                    speechRanges.joinToString("; ") { "${it.startMs}~${it.endMs}ms(${it.durationMs}ms)" }
                } else {
                    "首段[${speechRanges.first().startMs}~${speechRanges.first().endMs}ms], 末段[${speechRanges.last().startMs}~${speechRanges.last().endMs}ms]"
                }
                Log.i(TAG, "三层架构: VAD产出${speechRanges.size}个活动段: $vadDetail for episode=$episodeId")
                // v3.1.90: 写指纹日志
                writeFingerprintLog(context, "三层架构: 第2层-A VAD产出${speechRanges.size}个活动段（总${speechRanges.sumOf { it.durationMs }}ms）")
                Log.i(TAG, "三层架构: YAMNet待处理${yamnetIntervals.size}个区间(原始${rawIntervals.size}个) for episode=$episodeId")
                if (yamnetIntervals.isNotEmpty()) {
                    val intervalDetail = if (yamnetIntervals.size <= 10) {
                        yamnetIntervals.joinToString("; ") { "${it.first}~${it.second}ms(${it.second - it.first}ms)" }
                    } else {
                        "首区间[${yamnetIntervals.first().first}~${yamnetIntervals.first().second}ms], 末区间[${yamnetIntervals.last().first}~${yamnetIntervals.last().second}ms]"
                    }
                    Log.i(TAG, "三层架构: YAMNet区间详情: $intervalDetail for episode=$episodeId")
                    // v3.1.90: 写指纹日志
                    writeFingerprintLog(context, "三层架构: 第2层-B YAMNet待处理${yamnetIntervals.size}个区间: $intervalDetail")
                }

                if (yamnetIntervals.isEmpty()) {
                    Log.w(TAG, "三层架构: 无YAMNet待处理区间(pending为空)，使用第1层结果 for episode=$episodeId")
                    mergedAfterLayer2 = mergedAfterLayer1
                    audioEngineName = "VAD+YAMNet+三层(优化-无pending段)"
                } else {
                    // ===== 加载YAMNet模型（所有区间共用） =====
                    val yamnetInterpreter = AudioSegmentAnalyzer.loadYamnetInterpreter(context)
                    if (yamnetInterpreter == null) {
                        Log.w(TAG, "三层架构: YAMNet模型加载失败，使用第1层结果 for episode=$episodeId")
                        mergedAfterLayer2 = mergedAfterLayer1
                        audioEngineName = "VAD+YAMNet+三层(优化-YAMNet加载失败)"
                    } else {
                        try {
                            // ===== 第二层-B：对每个区间独立运行YAMNet推理 =====
                            val yamnetAllSegments = mutableListOf<VoiceSegment>()
                            var processedCount = 0
                            val totalIntervals = yamnetIntervals.size
                            // v3.1.143-fix: 跟踪进度起止时间，计算elapsed/remaining
                            val yamnetStartTimeMs = System.currentTimeMillis()
                            var yamnetProgressWriteTime = 0L

                            // v3.1.92: 打开PCM文件一次，避免重复打开316次
                            val pcmSamples = AudioSegmentAnalyzer.openPcmSamples(pcmSourceFile)
                            try {
                                for (interval in yamnetIntervals) {
                                    if (AudioSegmentAnalyzer.isAnalysisCancelled()) break
                                    val intervalStart = interval.first
                                    val intervalEnd = interval.second
                                    val intervalDurationMs = intervalEnd - intervalStart
                                    val intervalStartSec = intervalStart / 1000
                                    val intervalEndSec = intervalEnd / 1000

                                    // v3.1.140-fix: 记录每个YAMNet区间的音频详情
                                    if (processedCount < 5 || (processedCount % 10 == 0)) {
                                        val intervalInfo = "三层架构: 第2层-B YAMNet区间[${processedCount + 1}/$totalIntervals]: ${intervalStartSec}~${intervalEndSec}秒(${intervalDurationMs / 1000}s), 样本数=${(intervalDurationMs * AudioSegmentAnalyzer.YAMNET_SAMPLE_RATE / 1000).toInt()}"
                                        Log.i(TAG, "$intervalInfo for episode=$episodeId")
                                    }

                                    // v3.1.124: 恢复逐帧滑动窗口分类，YAMNet得分先过5帧滑动均值再分类，
                                    // 然后应用后处理规则（短段合并、水分占比、交替结构合并）
                                    // v3.1.142-fix: 传递区间内进度回调，避免长时间卡在单个大区间
                                    val baseMapped = 350 + (processedCount * 500 / totalIntervals).coerceIn(0, 500)
                                    var subSegments = emptyList<VoiceSegment>()
                                    try {
                                        subSegments = AudioSegmentAnalyzer.classifyPcmIntervalInner(
                                            pcmSamples, intervalStart, intervalEnd,
                                            yamnetInterpreter
                                        ) { subProgress ->
                                            // v3.1.143-fix: 区间内进度映射到5‰范围内有意义的变化
                                            val subMapped = baseMapped + (subProgress * 500 / totalIntervals / 1000).coerceIn(0, maxOf(1, 500 / totalIntervals))
                                            SegmentNotificationHelper.update(
                                                context, episodeId, episodeTitle, subMapped.coerceIn(350, 850),
                                                "第2层-B YAMNet ${processedCount + 1}/$totalIntervals 区间内${subProgress / 10}%"
                                            )
                                        }
                                    } catch (e: InterruptedException) {
                                        if (yamnetAllSegments.isEmpty()) throw
                                        break
                                    }

                                    val processedSegments = AudioSegmentAnalyzer.postProcessYamnetSubSegments(subSegments)
                                    yamnetAllSegments.addAll(processedSegments)
                                    processedCount++
                                    // v3.1.140-fix: 记录每个YAMNet区间的推理结果
                                    // v3.1.142-fix: 每个区间都记录日志，不遗漏
                                    val dryCount = processedSegments.count { it.label == "干货" }
                                    val waterCount = processedSegments.count { it.label == "水货" }
                                    val silenceCount = processedSegments.count { it.label == "静音" }
                                    val resultInfo = "三层架构: 第2层-B YAMNet区间[${processedCount}/$totalIntervals] ${intervalStartSec}~${intervalEndSec}秒(${intervalDurationMs / 1000}s): ${processedSegments.size}段(干${dryCount}/水${waterCount}/静${silenceCount})"
                                    Log.i(TAG, "$resultInfo for episode=$episodeId")
                                    writeFingerprintLog(context, "三层架构: 第2层-B YAMNet区间[${processedCount}/$totalIntervals]: ${processedSegments.size}段(干${dryCount}/水${waterCount}/静${silenceCount})")
                                    val mapped = 350 + (processedCount * 500 / totalIntervals).coerceIn(0, 500)
                                    SegmentNotificationHelper.update(
                                        context, episodeId, episodeTitle, mapped,
                                        "第2层-B YAMNet推理 ${processedCount}/$totalIntervals"
                                    )

                                    // v3.1.143-fix: 每5秒写入一次带elapsed/remaining的进度日志
                                    val now = System.currentTimeMillis()
                                    if (now - yamnetProgressWriteTime >= 5000 || processedCount == 1 || processedCount == totalIntervals || processedCount % (totalIntervals / 10).coerceAtLeast(1) == 0) {
                                        yamnetProgressWriteTime = now
                                        val elapsedMs = now - yamnetStartTimeMs
                                        val elapsedStr = formatDuration(elapsedMs)
                                        val avgPerIntervalMs = elapsedMs.toFloat() / processedCount
                                        val remainingMs = (avgPerIntervalMs * (totalIntervals - processedCount)).toLong()
                                        val remainingStr = formatDuration(remainingMs)
                                        val progressPct = (processedCount * 100 / totalIntervals).coerceIn(0, 100)
                                        val progressLog = "音频分段进度 Phase2 YAMNet: ${progressPct}% (已用${elapsedStr}/剩余${remainingStr})"
                                        writeFingerprintLog(context, progressLog)
                                        // 同时更新通知栏标题，带时间信息
                                        SegmentNotificationHelper.update(
                                            context, episodeId, episodeTitle, mapped,
                                            "第2层-B YAMNet ${processedCount}/$totalIntervals ${elapsedStr}/${remainingStr}"
                                        )
                                    }
                                }
                            } finally {
                                try { pcmSamples.close() } catch (_: Exception) {}
                            }

                            // ===== VAD回退：YAMNet未覆盖的VAD区间保留为干货 =====
                            // v3.1.139: 当YAMNet对某个VAD区间完全无产出时，该区间的VAD活动段应保留为干货。
                            // 根因：YAMNet可能在某些音频区域（如连续主持人讲话）不产生任何分类段，
                            // 导致这些区域在拼图中完全无覆盖，后续被fillSilenceGaps填充为静音，
                            // 再被mergeSilenceToAdjacentWater合并到相邻水段，造成大段主持人讲话被标记为水。
                            // 修复：对每个YAMNet处理区间，检查是否有YAMNet段覆盖，无覆盖时回退到VAD干货。
                            val vadFallbackSegments = mutableListOf<VoiceSegment>()
                            for (interval in yamnetIntervals) {
                                val hasYamnetCoverage = yamnetAllSegments.any { seg ->
                                    seg.start < interval.second && seg.end > interval.first
                                }
                                if (!hasYamnetCoverage) {
                                    val intervalDuration = interval.second - interval.first
                                    if (intervalDuration >= 1500) {
                                        vadFallbackSegments.add(VoiceSegment().apply {
                                            start = interval.first
                                            end = interval.second
                                            hasVoice = true
                                            label = "干货"
                                            isSimulated = false
                                        })
                                        Log.i(TAG, "三层架构: VAD回退: 区间${interval.first/1000}~${interval.second/1000}秒(${intervalDuration/1000}s) YAMNet无产出，保留为干货 for episode=$episodeId")
                                    }
                                }
                            }
                            if (vadFallbackSegments.isNotEmpty()) {
                                writeFingerprintLog(context, "三层架构: VAD回退: ${vadFallbackSegments.size}个VAD区间无YAMNet产出，保留为干货（总${vadFallbackSegments.sumOf { it.end - it.start } / 1000}秒）")
                            }

                            // ===== 拼图式合并：指纹段 + YAMNet段 + VAD回退段 =====
                            // v3.1.125: YAMNet干段优先于指纹水货段。YAMNet逐帧分类比指纹匹配更精确，
                            // 当指纹水货段与YAMNet干段冲突时，裁剪指纹水货段而非YAMNet干段。
                            // v3.1.126: 裁剪指纹水货段避开所有YAMNet段（干段和水段），
                            // 因为YAMNet逐帧分类比指纹15秒窗口匹配更精确，YAMNet的分类结果应完全替代指纹覆盖。
                            val jigsawSegments = mutableListOf<VoiceSegment>()

                            // 1. YAMNet子段（全部保留，干段优先）
                            jigsawSegments.addAll(yamnetAllSegments.map { it.copy() })
                            // v3.1.139: VAD回退段（YAMNet未覆盖的区间）
                            jigsawSegments.addAll(vadFallbackSegments.map { it.copy() })

                            // 2. 指纹水货段，做保护性边界裁剪（避开所有YAMNet段，不止干段）
                            // v3.1.134: 修复完全包含场景——当指纹水货段完全包含YAMNet段时，
                            // 原代码只处理clipStart/clipEnd在YAMNet段内的情况，不处理完全包含。
                            // 完全包含时，clipStart<YAMNet.start且clipEnd>YAMNet.end，两个条件都不触发，
                            // 导致指纹水货段未被裁剪，YAMNet段被指纹水货段覆盖。
                            // 修复：完全包含时，将指纹水货段拆分为两段（YAMNet段前和YAMNet段后）。
                            for (waterSeg in waterSegmentsAfterLayer1) {
                                var clipStart = waterSeg.start
                                var clipEnd = waterSeg.end
                                // 收集完全包含时需要分割的额外段
                                val extraSplits = mutableListOf<Pair<Long, Long>>()
                                for (yamnetSeg in yamnetAllSegments) {
                                    // v3.1.126: 避开所有YAMNet段（干段和水段），YAMNet逐帧分类比指纹更精确
                                    if (clipStart < yamnetSeg.end && clipEnd > yamnetSeg.start) {
                                        // 检查是否完全包含YAMNet段（clipStart < YAMNet.start 且 clipEnd > YAMNet.end）
                                        if (clipStart < yamnetSeg.start && clipEnd > yamnetSeg.end) {
                                            // 完全包含：将YAMNet段之后的部分作为额外段
                                            extraSplits.add(yamnetSeg.end to clipEnd)
                                            clipEnd = yamnetSeg.start
                                        } else {
                                            if (clipStart >= yamnetSeg.start && clipStart < yamnetSeg.end) {
                                                clipStart = yamnetSeg.end
                                            }
                                            if (clipEnd > yamnetSeg.start && clipEnd <= yamnetSeg.end) {
                                                clipEnd = yamnetSeg.start
                                            }
                                        }
                                    }
                                }
                                if (clipEnd - clipStart >= 500) {
                                    jigsawSegments.add(VoiceSegment().apply {
                                        start = clipStart; end = clipEnd
                                        hasVoice = false; label = "指纹水货"; isSimulated = false
                                    })
                                }
                                // 添加完全包含时分割出的额外段
                                for ((extraStart, extraEnd) in extraSplits) {
                                    if (extraEnd - extraStart >= 500) {
                                        jigsawSegments.add(VoiceSegment().apply {
                                            start = extraStart; end = extraEnd
                                            hasVoice = false; label = "指纹水货"; isSimulated = false
                                        })
                                    }
                                }
                            }

                            // v3.1.106: 彻底移除gap-filling填充
                            // 原因：
                            // 1. pendingSegments = 指纹未覆盖区间
                            // 2. 每个pending已经通过VAD∩pending得到yamnetIntervals，每个区间都已经做了YAMNet分类
                            // 3. 剩余未覆盖的都是VAD非活动段（静音），保持原样即可，不需要强行填充
                            // 4. 错误的gap-filling导致过度合并，总分段数过少，有时变成一个大段

                            // 4. 排序合并同类型相邻段
                            jigsawSegments.sortBy { it.start }
                            mergedAfterLayer2 = mergeAdjacentSegments(jigsawSegments)
                            audioEngineName = "VAD+YAMNet+三层(优化)"

                            // v3.1.86: 详细日志，输出YAMNet子段产出的干/水分类统计
                            val yamnetDryCount = yamnetAllSegments.count { it.hasVoice }
                            val yamnetWaterCount = yamnetAllSegments.count { !it.hasVoice }
                            val yamnetSilenceCount = yamnetAllSegments.count { it.label == "静音" }
                            val yamnetDetail = if (yamnetAllSegments.size <= 15) {
                                yamnetAllSegments.joinToString("; ") { "${it.start}~${it.end}ms[${it.label}]" }
                            } else {
                                "首段[${yamnetAllSegments.first().start}~${yamnetAllSegments.first().end}ms/${yamnetAllSegments.first().label}], 末段[${yamnetAllSegments.last().start}~${yamnetAllSegments.last().end}ms/${yamnetAllSegments.last().label}]"
                            }
                            // v3.1.135: 当段数>15时，额外输出720~960秒（12~16分钟）附近的段，便于调试主持人讲话被合并问题
                            val yamnetTargetRange = yamnetAllSegments.filter { it.start in 600000..1100000 || it.end in 600000..1100000 || (it.start < 600000 && it.end > 1100000) }
                            val yamnetTargetDetail = if (yamnetTargetRange.isNotEmpty()) {
                                "目标区域(600~1100s): " + yamnetTargetRange.joinToString("; ") { "${it.start}~${it.end}ms[${it.label}]" }
                            } else {
                                "目标区域(600~1100s): 无段覆盖（异常）"
                            }
                            Log.i(TAG, "三层架构: 第二层-B YAMNet完成，${yamnetIntervals.size}个区间产出${yamnetAllSegments.size}段(干${yamnetDryCount}/水${yamnetWaterCount}/静音${yamnetSilenceCount})，合并后${mergedAfterLayer2.size}段 for episode=$episodeId")
                            Log.i(TAG, "三层架构: YAMNet子段详情: $yamnetDetail for episode=$episodeId")
                            Log.i(TAG, "三层架构: YAMNet子段目标区域详情: $yamnetTargetDetail for episode=$episodeId")
                            // v3.1.90: 写指纹日志
                            writeFingerprintLog(context, "三层架构: 第2层-B YAMNet完成: ${yamnetIntervals.size}个区间→${yamnetAllSegments.size}段(干${yamnetDryCount}/水${yamnetWaterCount}/静音${yamnetSilenceCount})，合并后${mergedAfterLayer2.size}段")
                            writeFingerprintLog(context, "三层架构: YAMNet子段目标区域(600~1100s): $yamnetTargetDetail")
                        } finally {
                            try { yamnetInterpreter.close() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Throwable) {
                val fpMsgVadError = "三层架构: 第二层优化方案异常: ${e.javaClass.name}: ${e.message}"
                Log.e(TAG, fpMsgVadError)
                writeFingerprintLog(context, fpMsgVadError)
                // v3.1.112: 记录完整调用栈到指纹日志，并向上抛出异常（不允许回退第1层）
                val sw = java.io.StringWriter()
                val pw = java.io.PrintWriter(sw)
                e.printStackTrace(pw)
                writeFingerprintLog(context, "三层架构: 第二层优化方案异常详情:\n${sw.toString().take(1000)}")
                // v3.1.112: 不允许回退第1层分段结果，向上抛出异常
                // 外层catch会捕获该异常并返回null，由调用方决定降级策略
                throw e
            }
        } else if (vadModelsReady && pcmSourceFile == null) {
            // v3.1.40: PCM文件不存在时，先重新生成完整版PCM（带进度通知），再运行优化VAD+YAMNet
            val fpMsgPcmMissing = "三层架构: 完整PCM文件不存在，重新生成PCM for episode=$episodeId"
            Log.i(TAG, fpMsgPcmMissing)
            writeFingerprintLog(context, fpMsgPcmMissing)
            SegmentNotificationHelper.update(context, episodeId, episodeTitle, 50, "重新生成PCM")

            val audioFile = AudioSegmentAnalyzer.getCachedAudioFile(context, episodeId, audioUrl)
            if (audioFile != null && audioFile.exists() && audioFile.length() > 1024 * 100) {
                val regenResult = AudioSegmentAnalyzer.preGeneratePcmFiles(
                    context, episodeId, audioUrl, effectiveDurationMs,
                    progressCallback = { pct ->
                        SegmentNotificationHelper.update(context, episodeId, episodeTitle, 50 + pct / 2, "重新生成PCM ${pct}%")
                    }
                )
                if (regenResult) {
                    val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(context)
                    val newFullPcm = File(pcmCacheDir, "${episodeId}_full.pcm")
                    if (newFullPcm.exists() && newFullPcm.length() > 0) {
                        Log.i(TAG, "三层架构: PCM重新生成成功，运行优化VAD+YAMNet for episode=$episodeId")
                        SegmentNotificationHelper.update(context, episodeId, episodeTitle, 100, "PCM生成完成，开始VAD分析")
                        try {
                            // ===== 第二层-A：VAD-only =====
                            val vadStartTime2 = System.currentTimeMillis()
                            val speechRanges = AudioSegmentAnalyzer.runVadOnly(
                                context, newFullPcm, effectiveDurationMs
                            ) { permille ->
                                val mapped = 150 + (permille * 100 / 1000).coerceIn(0, 100)
                                SegmentNotificationHelper.update(context, episodeId, episodeTitle, mapped, "第2层-A VAD活动检测")
                            }
                            val vadDurationMs2 = System.currentTimeMillis() - vadStartTime2
                            val vadTotalActivityMs2 = speechRanges.sumOf { it.durationMs }
                            val vadActivityRatio2 = if (effectiveDurationMs > 0) "%.1f".format(vadTotalActivityMs2 * 100.0 / effectiveDurationMs) else "0"
                            writeFingerprintLog(context, "三层架构: PCM再生后第2层-A VAD完成: ${speechRanges.size}个活动段（总${vadTotalActivityMs2}ms, 占比${vadActivityRatio2}%）, 耗时${formatDuration(vadDurationMs2)}")

                            // ===== VAD结果（可能为空，但不允许降级） =====
                            // v3.1.85: 移除所有降级路径，VAD无活动时直接用pending段，YAMNet始终运行
                            val rawIntervals = mutableListOf<Pair<Long, Long>>()
                            if (speechRanges.isEmpty()) {
                                Log.w(TAG, "三层架构: VAD无活动段，直接使用pending段作为YAMNet区间 for episode=$episodeId")
                                for (pending in pendingSegments) {
                                    rawIntervals.add(pending.start to pending.end)
                                }
                            } else {
                                for (pending in pendingSegments) {
                                    for (speech in speechRanges) {
                                        val interStart = maxOf(pending.start, speech.startMs)
                                        val interEnd = minOf(pending.end, speech.endMs)
                                        if (interEnd > interStart) {
                                            rawIntervals.add(interStart to interEnd)
                                        }
                                    }
                                }
                            }

                            // 过滤<1.5s，但保留至少1个区间（不允许降级跳过）
                            var yamnetIntervals = rawIntervals.filter { it.second - it.first >= 1500 }
                            if (yamnetIntervals.isEmpty() && rawIntervals.isNotEmpty()) {
                                Log.w(TAG, "三层架构: 所有区间<1.5s，保留最长1个区间 for episode=$episodeId")
                                yamnetIntervals = listOf(rawIntervals.maxByOrNull { it.second - it.first }!!)
                            }

                            // v3.1.86: 详细日志
                            val vadDetail2 = if (speechRanges.size <= 10) {
                                speechRanges.joinToString("; ") { "${it.startMs}~${it.endMs}ms(${it.durationMs}ms)" }
                            } else {
                                "首段[${speechRanges.first().startMs}~${speechRanges.first().endMs}ms], 末段[${speechRanges.last().startMs}~${speechRanges.last().endMs}ms]"
                            }
                            Log.i(TAG, "三层架构: VAD产出${speechRanges.size}个活动段: $vadDetail2 for episode=$episodeId")
                            // v3.1.90: 写指纹日志
                            writeFingerprintLog(context, "三层架构: 第2层-A VAD产出${speechRanges.size}个活动段（总${speechRanges.sumOf { it.durationMs }}ms）")
                            Log.i(TAG, "三层架构: YAMNet待处理${yamnetIntervals.size}个区间(原始${rawIntervals.size}个) for episode=$episodeId")
                            if (yamnetIntervals.isNotEmpty()) {
                                val intervalDetail2 = if (yamnetIntervals.size <= 10) {
                                    yamnetIntervals.joinToString("; ") { "${it.first}~${it.second}ms(${it.second - it.first}ms)" }
                                } else {
                                    "首区间[${yamnetIntervals.first().first}~${yamnetIntervals.first().second}ms], 末区间[${yamnetIntervals.last().first}~${yamnetIntervals.last().second}ms]"
                                }
                                Log.i(TAG, "三层架构: YAMNet区间详情: $intervalDetail2 for episode=$episodeId")
                                // v3.1.90: 写指纹日志
                                writeFingerprintLog(context, "三层架构: 第2层-B YAMNet待处理${yamnetIntervals.size}个区间: $intervalDetail2")
                            }

                            if (yamnetIntervals.isEmpty()) {
                                Log.w(TAG, "三层架构: 无YAMNet待处理区间(pending为空)，使用第1层结果 for episode=$episodeId")
                                mergedAfterLayer2 = mergedAfterLayer1
                                audioEngineName = "VAD+YAMNet+三层(优化-无pending段)"
                            } else {
                                val yamnetInterpreter = AudioSegmentAnalyzer.loadYamnetInterpreter(context)
                                if (yamnetInterpreter == null) {
                                    Log.w(TAG, "三层架构: YAMNet模型加载失败，使用第1层结果 for episode=$episodeId")
                                    mergedAfterLayer2 = mergedAfterLayer1
                                    audioEngineName = "VAD+YAMNet+三层(优化-YAMNet加载失败)"
                                } else {
                                    try {
                                        val yamnetAllSegments = mutableListOf<VoiceSegment>()
                                        var processedCount = 0
                                        val totalIntervals = yamnetIntervals.size
                                        // v3.1.143-fix: 跟踪进度起止时间，计算elapsed/remaining
                                        val yamnetStartTimeMs2 = System.currentTimeMillis()
                                        var yamnetProgressWriteTime2 = 0L

                                        // v3.1.92: 打开PCM文件一次
                                        val pcmSamples2 = AudioSegmentAnalyzer.openPcmSamples(newFullPcm)
                                        try {
                                            for (interval in yamnetIntervals) {
                                                if (AudioSegmentAnalyzer.isAnalysisCancelled()) break
                                                val intervalStart = interval.first
                                                val intervalEnd = interval.second

                                                // v3.1.124: 恢复逐帧滑动窗口分类+后处理
                                                // v3.1.142-fix: 传递区间内进度回调
                                                var subSegments = emptyList<VoiceSegment>()
                                                val baseMapped2 = 250 + (processedCount * 550 / totalIntervals).coerceIn(0, 550)
                                                try {
                                                    subSegments = AudioSegmentAnalyzer.classifyPcmIntervalInner(
                                                        pcmSamples2, intervalStart, intervalEnd, yamnetInterpreter
                                                    ) { subProgress ->
                                                        // v3.1.143-fix: 区间内进度映射到有意义的变化
                                                        val subMapped2 = baseMapped2 + (subProgress * 550 / totalIntervals / 1000).coerceIn(0, maxOf(1, 550 / totalIntervals))
                                                        SegmentNotificationHelper.update(context, episodeId, episodeTitle,
                                                            subMapped2.coerceIn(250, 800),
                                                            "第2层-B YAMNet ${processedCount + 1}/$totalIntervals 区间内${subProgress / 10}%")
                                                    }
                                                } catch (e: InterruptedException) {
                                                    if (yamnetAllSegments.isEmpty()) throw
                                                    break
                                                }

                                                val processedSegments = AudioSegmentAnalyzer.postProcessYamnetSubSegments(subSegments)
                                                yamnetAllSegments.addAll(processedSegments)
                                                processedCount++
                                                val mapped2 = (250 + (processedCount * 550 / totalIntervals).coerceIn(0, 550)).coerceIn(250, 800)
                                                SegmentNotificationHelper.update(context, episodeId, episodeTitle,
                                                    mapped2,
                                                    "第2层-B YAMNet推理 ${processedCount}/$totalIntervals")

                                                // v3.1.143-fix: 每5秒写入一次带elapsed/remaining的进度日志
                                                val now2 = System.currentTimeMillis()
                                                if (now2 - yamnetProgressWriteTime2 >= 5000 || processedCount == 1 || processedCount == totalIntervals || processedCount % (totalIntervals / 10).coerceAtLeast(1) == 0) {
                                                    yamnetProgressWriteTime2 = now2
                                                    val elapsedMs2 = now2 - yamnetStartTimeMs2
                                                    val elapsedStr2 = formatDuration(elapsedMs2)
                                                    val avgPerIntervalMs2 = elapsedMs2.toFloat() / processedCount
                                                    val remainingMs2 = (avgPerIntervalMs2 * (totalIntervals - processedCount)).toLong()
                                                    val remainingStr2 = formatDuration(remainingMs2)
                                                    val progressPct2 = (processedCount * 100 / totalIntervals).coerceIn(0, 100)
                                                    val progressLog2 = "音频分段进度 Phase2 YAMNet: ${progressPct2}% (已用${elapsedStr2}/剩余${remainingStr2})"
                                                    writeFingerprintLog(context, progressLog2)
                                                    SegmentNotificationHelper.update(context, episodeId, episodeTitle,
                                                        mapped2,
                                                        "第2层-B YAMNet ${processedCount}/$totalIntervals ${elapsedStr2}/${remainingStr2}")
                                                }
                                            }
                                        } finally {
                                            try { pcmSamples2.close() } catch (_: Exception) {}
                                        }

                                        // v3.1.106: 拼图合并：指纹段 + YAMNet子段（移除gap-filling）
                                        // v3.1.125: YAMNet干段优先于指纹水货段，裁剪指纹水货段避开YAMNet干段
                                        // v3.1.126: 裁剪指纹水货段避开所有YAMNet段（干段和水段），
                                        // YAMNet逐帧分类比指纹15秒窗口匹配更精确，分类结果应完全替代指纹覆盖。
                                        val jigsawSegments = mutableListOf<VoiceSegment>()
                                        // 1. YAMNet子段（全部保留，干段优先）
                                        jigsawSegments.addAll(yamnetAllSegments.map { it.copy() })
                                        // 2. 指纹水货段，做保护性边界裁剪（避开所有YAMNet段）
                                        // v3.1.134: 修复完全包含场景——当指纹水货段完全包含YAMNet段时，
                                        // 原代码只处理clipStart/clipEnd在YAMNet段内的情况，不处理完全包含。
                                        for (waterSeg in waterSegmentsAfterLayer1) {
                                            var clipStart = waterSeg.start; var clipEnd = waterSeg.end
                                            val extraSplits = mutableListOf<Pair<Long, Long>>()
                                            for (yamnetSeg in yamnetAllSegments) {
                                                // v3.1.126: 避开所有YAMNet段（干段和水段），YAMNet逐帧分类比指纹更精确
                                                if (clipStart < yamnetSeg.end && clipEnd > yamnetSeg.start) {
                                                    if (clipStart < yamnetSeg.start && clipEnd > yamnetSeg.end) {
                                                        extraSplits.add(yamnetSeg.end to clipEnd)
                                                        clipEnd = yamnetSeg.start
                                                    } else {
                                                        if (clipStart >= yamnetSeg.start && clipStart < yamnetSeg.end) clipStart = yamnetSeg.end
                                                        if (clipEnd > yamnetSeg.start && clipEnd <= yamnetSeg.end) clipEnd = yamnetSeg.start
                                                    }
                                                }
                                            }
                                            if (clipEnd - clipStart >= 500) {
                                                jigsawSegments.add(VoiceSegment().apply {
                                                    start = clipStart; end = clipEnd; hasVoice = false; label = "指纹水货"; isSimulated = false
                                                })
                                            }
                                            for ((extraStart, extraEnd) in extraSplits) {
                                                if (extraEnd - extraStart >= 500) {
                                                    jigsawSegments.add(VoiceSegment().apply {
                                                        start = extraStart; end = extraEnd; hasVoice = false; label = "指纹水货"; isSimulated = false
                                                    })
                                                }
                                            }
                                        }
                                        jigsawSegments.sortBy { it.start }
                                        mergedAfterLayer2 = mergeAdjacentSegments(jigsawSegments)
                                        audioEngineName = "VAD+YAMNet+三层(优化-PCM重新生成)"

                                        // v3.1.86: 详细日志
                                        val yamnetDryCount2 = yamnetAllSegments.count { it.hasVoice }
                                        val yamnetWaterCount2 = yamnetAllSegments.count { !it.hasVoice }
                                        val yamnetSilenceCount2 = yamnetAllSegments.count { it.label == "静音" }
                                        val yamnetDetail2 = if (yamnetAllSegments.size <= 15) {
                                            yamnetAllSegments.joinToString("; ") { "${it.start}~${it.end}ms[${it.label}]" }
                                        } else {
                                            "首段[${yamnetAllSegments.first().start}~${yamnetAllSegments.first().end}ms/${yamnetAllSegments.first().label}], 末段[${yamnetAllSegments.last().start}~${yamnetAllSegments.last().end}ms/${yamnetAllSegments.last().label}]"
                                        }
                                        Log.i(TAG, "三层架构: 第二层优化完成（PCM重新生成后），${yamnetIntervals.size}个区间产出${yamnetAllSegments.size}段(干${yamnetDryCount2}/水${yamnetWaterCount2}/静音${yamnetSilenceCount2})，合并后${mergedAfterLayer2.size}段 for episode=$episodeId")
                                        Log.i(TAG, "三层架构: YAMNet子段详情: $yamnetDetail2 for episode=$episodeId")
                                        // v3.1.90: 写指纹日志
                                        writeFingerprintLog(context, "三层架构: 第2层-B YAMNet完成: ${yamnetIntervals.size}个区间→${yamnetAllSegments.size}段(干${yamnetDryCount2}/水${yamnetWaterCount2}/静音${yamnetSilenceCount2})，合并后${mergedAfterLayer2.size}段")
                                    } finally {
                                        try { yamnetInterpreter.close() } catch (_: Exception) {}
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            val fpMsgVadError = "三层架构: 重新生成PCM后优化方案异常: ${e.javaClass.name}: ${e.message}"
                            Log.e(TAG, fpMsgVadError)
                            writeFingerprintLog(context, fpMsgVadError)
                            val sw = java.io.StringWriter()
                            val pw = java.io.PrintWriter(sw)
                            e.printStackTrace(pw)
                            writeFingerprintLog(context, "三层架构: 重新生成PCM后优化方案异常详情:\n${sw.toString().take(1000)}")
                            // v3.1.112: 不允许回退第1层，向上抛出
                            throw e
                        }
                    } else {
                        mergedAfterLayer2 = mergedAfterLayer1
                        audioEngineName = "三层架构(仅第一层)"
                    }
                } else {
                    val fpMsgPcmFail = "三层架构: PCM重新生成失败，使用第1层结果"
                    Log.w(TAG, fpMsgPcmFail)
                    writeFingerprintLog(context, fpMsgPcmFail)
                    mergedAfterLayer2 = mergedAfterLayer1
                    audioEngineName = "三层架构(仅第一层)"
                }
            } else {
                val fpMsgNoAudio = "三层架构: 音频文件不存在，无法重新生成PCM for episode=$episodeId"
                Log.w(TAG, fpMsgNoAudio)
                writeFingerprintLog(context, fpMsgNoAudio)
                mergedAfterLayer2 = mergedAfterLayer1
                audioEngineName = "三层架构(仅第一层)"
            }
        } else {
            // VAD不可用（模型未安装），使用第1层结果
            val reason = when {
                !vadModelsReady -> "VAD/YAMNet模型未安装"
                else -> "未知原因"
            }
            val fpMsgVadUnavailable = "三层架构: 第二层跳过($reason)"
            Log.w(TAG, fpMsgVadUnavailable)
            writeFingerprintLog(context, fpMsgVadUnavailable)
            mergedAfterLayer2 = mergedAfterLayer1
            audioEngineName = "VAD+YAMNet+三层(优化跳过)"
        }

        // 统计第2层VAD产出
        layer2DrySegments = mergedAfterLayer2.count { it.hasVoice }
        layer2WaterSegments = mergedAfterLayer2.count { !it.hasVoice }
        layer2TimeMs = System.currentTimeMillis() - layer2StartTime

        Log.i(TAG, "三层架构: 第二层完成，共${mergedAfterLayer2.size}个片段（干货${layer2DrySegments}段，水货${layer2WaterSegments}段），耗时${formatDuration(layer2TimeMs)}")
        // v3.1.90: 写指纹日志
        writeFingerprintLog(context, "三层架构: 第2层完成: ${mergedAfterLayer2.size}个片段（干${layer2DrySegments}段/水${layer2WaterSegments}段），耗时${formatDuration(layer2TimeMs)}")

        // ========== 第三层：指纹漏判召回（仅干货 + 仅金标准） ==========
        // v3.1.46: 添加第三层进度通知栏更新，确保三层分段全程都有进度显示
        val layer3StartTime = System.currentTimeMillis()
        SegmentNotificationHelper.update(context, episodeId, episodeTitle, 900, "第3层指纹漏判召回")
        val layer3Result = if (goldStandardFingerprints.isNotEmpty() && ChromaprintExtractor.ensureLibraryLoaded(context)) {
            // v3.1.95: 使用带计数的召回函数，recallCount 是实际翻转的片段数
            // 原公式 recalled.waterCount - mergedAfterLayer2.waterCount 在合并后可能为负
            val (recalled, recallCount) = applyFingerprintRecallLayer3WithCount(context, episodeId, mergedAfterLayer2, goldStandardFingerprints)
            layer3RecallCount = recallCount
            layer3TimeMs = System.currentTimeMillis() - layer3StartTime
            Log.i(TAG, "三层架构: 第三层指纹漏判召回完成，召回${layer3RecallCount}个漏判片段，耗时${formatDuration(layer3TimeMs)}")
            writeFingerprintLog(context, "三层架构: 第3层指纹漏判召回完成，召回${layer3RecallCount}个漏判片段，耗时${formatDuration(layer3TimeMs)}")
            SegmentNotificationHelper.update(context, episodeId, episodeTitle, 950, "第3层召回完成，合并结果")
            recalled
        } else {
            val fpMsg = "三层架构: 第三层指纹漏判召回跳过（金标准库为空或指纹引擎未就绪）"
            Log.i(TAG, fpMsg)
            writeFingerprintLog(context, fpMsg)
            mergedAfterLayer2
        }

        // 最终合并
        // v3.1.95: 应用完整的后处理合并逻辑（碎片合并、干货合并、水分合并）
        // 解决三层架构连续水分分段未合并、总分段数过多的问题
        val merged = mergeAdjacentSegments(layer3Result)
        // v3.1.112: 记录合并前后的分段数，用于排查干货间丢失
        val dryBeforePost = merged.count { it.hasVoice }
        val waterBeforePost = merged.count { !it.hasVoice && isWaterLabel(it.label) }
        val finalSegments = AudioSegmentAnalyzer.postProcessSegments(merged).toMutableList().apply {
            for (seg in this) {
                if (!seg.hasVoice && seg.label == null) {
                    seg.label = "指纹水货"
                }
            }
        }
        val dryAfterPost = finalSegments.count { it.hasVoice }
        val waterAfterPost = finalSegments.count { !it.hasVoice }
        // v3.1.112: 详细记录后处理变化
        writeFingerprintLog(context, "三层架构: 后处理结果: ${merged.size}段(干${dryBeforePost}/水${waterBeforePost}) → ${finalSegments.size}段(干${dryAfterPost}/水${waterAfterPost}), 合并前${layer3Result.size}段")

        // v3.1.116: 填充段间间隙为静音段，确保时间轴100%连续
        // 根因：VAD活动段只覆盖了约89.3%的时间轴（见日志第2层-A VAD占比），
        // 剩余10.7%的静音间隙未被任何段覆盖，导致播放器显示时间不连续。
        // 拼图合并仅覆盖pending∩VAD区域，VAD非活动区域完全缺失。
        // 方法：对最终段按start排序，在相邻段间填充"静音"段。
        val gapFillCount = fillSilenceGaps(finalSegments, effectiveDurationMs, context)
        if (gapFillCount > 0) {
            writeFingerprintLog(context, "三层架构: 填充${gapFillCount}个静音间隙，总段数: ${finalSegments.size}段(干${finalSegments.count { it.hasVoice }}/水${finalSegments.count { !it.hasVoice }})")
        }

        // v3.1.125: 合并静音段到相邻非静音段。
        // v3.1.126: 同时合并静音段到干货段，消除"静音+干货+静音"、"静音+干货"、"干货+静音"碎片化模式。
        val silenceMergedCount = mergeSilenceToAdjacentWater(finalSegments)
        if (silenceMergedCount > 0) {
            writeFingerprintLog(context, "三层架构: 合并${silenceMergedCount}个静音段到相邻非静音段，总段数: ${finalSegments.size}段(干${finalSegments.count { it.hasVoice }}/水${finalSegments.count { !it.hasVoice }})")
        }

        // v3.1.135: 额外输出12~16分钟（720~960秒）区域的最终分段结果，便于调试主持人讲话被合并问题
        val finalTargetSegments = finalSegments.filter { it.start in 720000..960000 || it.end in 720000..960000 || (it.start < 720000 && it.end > 960000) }
        val finalTargetDetail = if (finalTargetSegments.isNotEmpty()) {
            val sb = StringBuilder()
            finalTargetSegments.forEach { seg ->
                sb.append("${seg.start}~${seg.end}ms[${seg.label} hasVoice=${seg.hasVoice}]; ")
            }
            sb.toString()
        } else {
            "无段覆盖（异常）"
        }
        writeFingerprintLog(context, "三层架构: 目标区域(720~960s=12~16分钟)最终分段详情: $finalTargetDetail for episode=$episodeId")
        Log.i(TAG, "三层架构: 目标区域(720~960s)最终分段详情: $finalTargetDetail for episode=$episodeId")

        // 日志统计（含各层耗时和干货占比）
        val totalTimeMs = System.currentTimeMillis() - segStartTime
        val finalDryCount = finalSegments.count { it.hasVoice }
        val dryRatio = if (finalSegments.isNotEmpty()) "%.1f".format(finalDryCount * 100.0 / finalSegments.size) else "0.0"
        val fpMsgStats = "三层架构完成: ${finalSegments.size}个片段（干货${finalDryCount}段，占比${dryRatio}%），总耗时${formatDuration(totalTimeMs)}（第1层${formatDuration(layer1TimeMs)}，第2层${formatDuration(layer2TimeMs)}，第3层${formatDuration(layer3TimeMs)}）"
        Log.i(TAG, fpMsgStats)
        writeFingerprintLog(context, fpMsgStats)

        // v3.1.30: 验证结果异常时仅记录日志，不允许回退其他方案
        val validationResult = validateThreeLayerResult(finalSegments, effectiveDurationMs)
        if (validationResult != null) {
            val fpMsgAbnormal = "三层架构结果异常: $validationResult（已记录，使用当前结果，不执行回退）"
            Log.w(TAG, fpMsgAbnormal)
            writeFingerprintLog(context, fpMsgAbnormal)
        }

        // ========== 观察池处理（v3.1.59: 整体try-catch防止DB异常导致崩溃） ==========
        try {
            if (goldStandardFingerprints.isNotEmpty() && ChromaprintExtractor.ensureLibraryLoaded(context)) {
                processObservationPoolForSegment(context, episodeId, finalSegments, mergedAfterLayer2, goldStandardFingerprints)
                val promoted = try {
                    dbHelper.getPromotableCandidates(POOL_PROMOTION_THRESHOLD_DEFAULT)
                } catch (e: Exception) {
                    Log.w(TAG, "观察池: getPromotableCandidates异常: ${e.message}")
                    emptyList()
                }
                for (candidate in promoted) {
                    try {
                        dbHelper.incrementObservationPoolHit(candidate.id, episodeId, POOL_PROMOTION_THRESHOLD_DEFAULT)
                    } catch (_: Exception) {}
                }
                try { dbHelper.cleanupExpiredObservationPool() } catch (_: Exception) {}

                val fpMsgPool = "观察池: 当前共${try { dbHelper.getObservationPoolCount() } catch (_: Exception) { 0 }}个候选"
                Log.i(TAG, fpMsgPool)
                writeFingerprintLog(context, fpMsgPool)
            } else {
                val fpMsg = "观察池处理: 跳过（金标准库为空或指纹引擎未就绪）"
                Log.i(TAG, fpMsg)
                writeFingerprintLog(context, fpMsg)
            }
        } catch (e: Throwable) {
            val fpMsgPoolError = "观察池处理异常: ${e.javaClass.name}: ${e.message}"
            Log.w(TAG, fpMsgPoolError)
            writeFingerprintLog(context, fpMsgPoolError)
        }

        // v3.1.28: 更新通知为100%完成
        SegmentNotificationHelper.update(context, episodeId, episodeTitle, 1000, "三层分段完成")

        val fpMsgDone = "就AI听三层架构完成: ${finalSegments.size}个片段（干货${finalDryCount}段，占比${dryRatio}%），总耗时${formatDuration(totalTimeMs)}（第1层${formatDuration(layer1TimeMs)}，第2层${formatDuration(layer2TimeMs)}，第3层${formatDuration(layer3TimeMs)}）"
        Log.i(TAG, fpMsgDone)
        writeFingerprintLog(context, fpMsgDone)

        SegmentNotificationHelper.endSession(context, episodeId)
        return JiuAiTingResult(
            segments = finalSegments,
            engineName = audioEngineName,
            processingTimeMs = System.currentTimeMillis() - segStartTime,
            matchedCount = layer1MatchCount + layer3RecallCount,
            totalDrySegments = 0,
            layer1MatchCount = layer1MatchCount,
            layer3RecallCount = layer3RecallCount,
            observationPoolNewCount = observationPoolNewCount,
            observationPoolHitCount = observationPoolHitCount
        )
        } else {
            // 没有待处理片段且无PCM文件，直接使用第一层结果
            val fpMsg = "三层架构: 无待处理片段且无PCM文件，跳过第二、三层，直接使用第一层结果"
            Log.i(TAG, fpMsg)
            writeFingerprintLog(context, fpMsg)
            SegmentNotificationHelper.endSession(context, episodeId)
            return JiuAiTingResult(
                segments = mergedAfterLayer1,
                engineName = "三层架构(仅第一层)",
                processingTimeMs = System.currentTimeMillis() - segStartTime,
                matchedCount = layer1MatchCount,
                totalDrySegments = mergedAfterLayer1.size,
                layer1MatchCount = layer1MatchCount,
                layer3RecallCount = 0,
                observationPoolNewCount = 0,
                observationPoolHitCount = 0
            )
        }
        } catch (e: Throwable) {
            val fpMsgCrash = "generateJiuAiTingSegments 崩溃: ${e.javaClass.name}: ${e.message}"
            Log.e(TAG, fpMsgCrash)
            writeFingerprintLog(context, fpMsgCrash)
            // v3.1.111: 记录崩溃的完整调用栈到指纹日志
            val sw = java.io.StringWriter()
            val pw = java.io.PrintWriter(sw)
            e.printStackTrace(pw)
            writeFingerprintLog(context, "generateJiuAiTingSegments 崩溃详情:\n${sw.toString().take(1000)}")
            // v3.1.59: 崩溃时确保通知会话结束，防止残留状态
            try { SegmentNotificationHelper.endSession(context, episodeId) } catch (_: Exception) {}
            return null
        } finally {
            // v3.1.108: 恢复之前的分析线程引用
            AudioSegmentAnalyzer.setCurrentAnalysisThread(savedAnalysisThread)
            isThreeLayerSegmenting = false
            // v3.1.51: 同时清除全局标志，允许后续分段请求
            SegmentNotificationHelper.isSegmenting = false
            // v3.1.59: 崩溃后清除segmentingEpisodes条目，防止后续请求被永久拒绝
            segmentingEpisodes.remove(episodeId)
        }
    }

    /**
     * v3.1.23: 第一层滑动窗口指纹匹配。
     * 对完整PCM以固定窗口大小滑动，提取每个窗口的指纹并与正式指纹库比对。
     * 匹配到的窗口合并为水货段，未匹配的窗口标记为干货。
     * 不再依赖任何已有分段结果，完全从头开始。
     *
     * v3.1.128: 三重加速优化：
     * 1. PCM能量静音跳过：计算窗口RMS能量，低于阈值跳过（静音窗口无需匹配）
     * 2. 指纹哈希前缀索引：预建指纹库哈希前缀→指纹列表映射，仅对比哈希前缀匹配的指纹
     * 3. 指纹分组去重：同一分组只保留代表指纹，避免近似指纹重复对比
     */
    private fun applyLayer1SlidingWindow(
        context: Context,
        episodeId: String,
        pcmFile: File,
        durationMs: Long,
        formalLibrary: List<AudioFingerprint>,
        progressCallback: ((Int, Long, Long) -> Unit)? = null,
        // v3.1.129: 指纹分组信息，用于缩减对比量
        fingerprintGroups: List<FingerprintGroupInfo> = emptyList(),
        groupMembers: List<FingerprintGroupMember> = emptyList(),
        dbHelper: RadioDatabaseHelper? = null
    ): List<VoiceSegment> {
        if (!ChromaprintExtractor.ensureLibraryLoaded(context)) {
            val fpMsg = "第一层滑动窗口: 跳过（指纹引擎未就绪）"
            Log.w(TAG, fpMsg)
            writeFingerprintLog(context, fpMsg)
            return listOf(VoiceSegment().apply {
                this.start = 0; this.end = durationMs; this.hasVoice = true; this.label = "干货"; this.isSimulated = true
            })
        }

        // 窗口参数：15秒窗口，5秒步长
        val WINDOW_MS = 15000L
        val STEP_MS = 5000L

        // v3.1.131: 预解析正式库指纹，去重后直接使用（移除哈希前缀索引——哈希前缀因指纹字符串
        // 格式差异频繁不命中，导致回退到全量库，实际效果等同于直接全量对比，且增加复杂度）。
        // v3.1.129: 增加指纹ID字段，用于分组映射和last_matched_at更新
        // v3.1.132: parsed改为IntArray——原始int[]直接内存访问，消除List<Int>装箱开销
        data class ParsedFpEntry(
            val id: Long,
            val parsed: IntArray,
            val originalFp: String
        )
        val parsedLibrary = formalLibrary.map { fp ->
            val parsed = ChromaprintExtractor.parseFingerprint(fp.fingerprint)
            // v3.1.132: List<Int> → IntArray，消除装箱开销
            val parsedArray = if (parsed is ArrayList<Int>) {
                IntArray(parsed.size) { parsed[it] }
            } else {
                parsed.toIntArray()
            }
            ParsedFpEntry(fp.id, parsedArray, fp.fingerprint)
        }
        // 指纹去重：取指纹的前5个整数作为快速去重标识
        val dedupedLibrary = mutableListOf<ParsedFpEntry>()
        val seenFpKeys = mutableSetOf<String>()
        for (entry in parsedLibrary) {
            if (entry.parsed.isEmpty()) continue
            val dedupKey = entry.parsed.take(5).joinToString(",")
            if (dedupKey in seenFpKeys) continue
            seenFpKeys.add(dedupKey)
            dedupedLibrary.add(entry)
        }
        val dedupCount = parsedLibrary.size - dedupedLibrary.size

        // v3.1.129: 构建指纹ID到分组的映射，同一分组只保留代表指纹参与对比
        // 从groupMembers构建fingerprintId → isRepresentative的映射
        val fpGroupMap = mutableMapOf<Long, Boolean>() // fingerprintId → isRepresentative
        if (fingerprintGroups.isNotEmpty() && groupMembers.isNotEmpty()) {
            for (member in groupMembers) {
                fpGroupMap[member.fingerprintId] = member.isRepresentative
            }
        }
        // v3.1.140-fix: 记录指纹分组统计信息，用于诊断分组是否起作用
        val totalFpInGroups = fpGroupMap.size
        val representativeCount = fpGroupMap.count { it.value }
        val nonRepresentativeCount = totalFpInGroups - representativeCount
        val fpGroupMsg = "指纹分组: ${fingerprintGroups.size}个分组, ${totalFpInGroups}条指纹映射(代表${representativeCount}条/非代表${nonRepresentativeCount}条), 正式库${dedupedLibrary.size}条"
        Log.i(TAG, fpGroupMsg)
        writeFingerprintLog(context, fpGroupMsg)

        val matchedRanges = mutableListOf<Pair<Long, Long>>()
        val hitDetails = mutableListOf<String>()
        var totalWindows = 0
        var matchedWindows = 0
        var lastReportedPct = -1

        // v3.1.131: 改用全量指纹一次提取+指纹级滑动窗口替代每窗口PCM提取+JNI调用。
        // 原方案(v3.1.128~v3.1.130): 每5秒提取一次15秒窗口的指纹（1080次JNI调用），
        // 每次JNI处理15秒PCM=480KB，1080次×50ms=54秒JNI耗时，加上copyOfRange内存分配GC压力。
        // 新方案: 一次提取完整PCM指纹（1次JNI调用），然后对指纹数组做滑动窗口切片。
        // 指纹数组是List<Int>，subList是O(1)视图操作，无内存分配，无GC压力。
        // 90分钟节目约54000帧指纹，1080个窗口，对比逻辑不变，JNI从1080次降至1次。
        // v3.1.132: 完整指纹和窗口都转为IntArray，使用compareFingerprintArraysFast消除装箱开销。
        // 原版List<Int>比较: 137,856次比较×22,500次迭代=31亿次装箱访问→49分钟。
        // IntArray版: 直接int[]内存访问，每次比较5~10微秒，总计约1~2秒。
        // v3.1.133: 增加关键节点计时日志，用于诊断速度瓶颈。
        val t0 = System.currentTimeMillis()
        val fullFingerprint = ChromaprintExtractor.extractFingerprintFromFile(pcmFile)
        if (fullFingerprint.isNullOrBlank()) {
            val fpMsg = "第一层滑动窗口: 提取完整PCM指纹失败，跳过"
            Log.w(TAG, fpMsg)
            writeFingerprintLog(context, fpMsg)
            return listOf(VoiceSegment().apply {
                this.start = 0; this.end = durationMs; this.hasVoice = true; this.label = "干货"; this.isSimulated = true
            })
        }
        val parsedFull = ChromaprintExtractor.parseFingerprint(fullFingerprint)
        if (parsedFull.size < 50) {
            val fpMsg = "第一层滑动窗口: 完整指纹帧数过少(${parsedFull.size})，跳过"
            Log.w(TAG, fpMsg)
            writeFingerprintLog(context, fpMsg)
            return listOf(VoiceSegment().apply {
                this.start = 0; this.end = durationMs; this.hasVoice = true; this.label = "干货"; this.isSimulated = true
            })
        }
        // v3.1.132: 转IntArray，消除装箱开销
        val fullArray = if (parsedFull is ArrayList<Int>) {
            IntArray(parsedFull.size) { parsedFull[it] }
        } else {
            parsedFull.toIntArray()
        }
        val t1 = System.currentTimeMillis()
        val extractTime = t1 - t0
        val fpMsgTime = "第一层滑动窗口: PCM指纹提取耗时${formatDuration(extractTime)}，帧数=${fullArray.size}，正式库=${parsedLibrary.size}条，去重后=${dedupedLibrary.size}条"
        Log.i(TAG, fpMsgTime)
        writeFingerprintLog(context, fpMsgTime)

        // 计算每帧对应的毫秒数，用于将帧位置映射为时间
        val msPerFrame = durationMs.toFloat() / fullArray.size.toFloat()
        // 窗口帧数：15秒窗口
        val windowFrames = (WINDOW_MS / msPerFrame).toInt().coerceAtLeast(50)
        // 步进帧数：5秒步长
        val stepFrames = (STEP_MS / msPerFrame).toInt().coerceAtLeast(10)
        val maxOffset = fullArray.size - windowFrames

        var framePos = 0
        // v3.1.59: 外层循环用try-catch捕获Throwable，防止单次窗口异常导致整个分段崩溃
        try {
        while (framePos <= maxOffset) {
            totalWindows++

            // v3.1.32: 响应取消信号，及时停止滑动窗口处理
            val threadInterrupted = Thread.interrupted()
            val flagCancelled = AudioSegmentAnalyzer.isAnalysisCancelled()
            if (threadInterrupted || flagCancelled) {
                val cancelSource = when {
                    threadInterrupted && flagCancelled -> "thread.interrupt+analysisCancelled"
                    threadInterrupted -> "thread.interrupt"
                    else -> "analysisCancelled flag"
                }
                val fpMsgCancel = "第一层滑动窗口: 取消处理(cancelSource=$cancelSource)，帧位置$framePos for episode=$episodeId"
                Log.i(TAG, fpMsgCancel)
                writeFingerprintLog(context, fpMsgCancel)
                break
            }

            // 进度回调
            val pct = ((framePos * 1000L) / (maxOffset + 1)).toInt().coerceIn(0, 1000)
            if (pct / 50 != lastReportedPct / 50) {
                lastReportedPct = pct
                progressCallback?.invoke(pct, System.currentTimeMillis() - 0, 0)
            }

            try {
                // v3.1.132: 从IntArray切片（copyOfRange分配int[150]=600字节，可忽略）
                val windowArray = fullArray.copyOfRange(framePos, framePos + windowFrames)

                // v3.1.131: 移除哈希前缀索引——哈希前缀因指纹字符串格式差异频繁不命中，
                // 导致回退到全量库，实际效果等同于直接全量对比。直接使用去重库。
                val candidates = dedupedLibrary

                var matched = false
                var bestSim = 0f
                var matchedFpId: Long? = null
                for (entry in candidates) {
                    if (entry.parsed.isEmpty()) continue
                    // v3.1.129: 如果该指纹有分组且不是代表指纹，跳过对比
                    val isRepresentative = fpGroupMap[entry.id] ?: true // 默认true（无分组时参与对比）
                    if (!isRepresentative) continue
                    // v3.1.132: 使用IntArray版快速比较，消除装箱开销
                    val sim = ChromaprintExtractor.compareFingerprintArraysFast(windowArray, entry.parsed)
                    if (sim > bestSim) { bestSim = sim; matchedFpId = entry.id }
                    if (sim >= LAYER1_FAST_SCREEN_THRESHOLD) {
                        matched = true
                        matchedFpId = entry.id
                        break
                    }
                }

                if (matched) {
                    val startMs = (framePos * msPerFrame).toLong()
                    val endMs = ((framePos + windowFrames) * msPerFrame).toLong()
                    matchedRanges.add(startMs to endMs)
                    matchedWindows++
                    if (matchedWindows <= 20 || matchedWindows % 10 == 0) {
                        hitDetails.add("${startMs/1000}秒(相似度:${"%.0f".format(bestSim*100)}%)")
                    }
                    // v3.1.129: 更新匹配指纹的last_matched_at
                    if (matchedFpId != null && matchedFpId!! > 0 && dbHelper != null) {
                        try {
                            dbHelper.updateFingerprintLastMatched(matchedFpId!!)
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "第一层滑动窗口: 帧位置${framePos}异常: ${e.message}")
            }
            framePos += stepFrames
        }
        } catch (e: Throwable) {
            val fpMsg = "第一层滑动窗口: 循环异常中止: ${e.javaClass.name}: ${e.message}"
            Log.w(TAG, fpMsg)
            writeFingerprintLog(context, fpMsg)
        }

        val t2 = System.currentTimeMillis()
        val loopTime = t2 - t0
        val fpMsgLoop = "第一层滑动窗口: 循环完成，总耗时${formatDuration(loopTime)}，窗口数=${totalWindows}，匹配=${matchedWindows}/${totalWindows}，正式库=${dedupedLibrary.size}条"
        Log.i(TAG, fpMsgLoop)
        writeFingerprintLog(context, fpMsgLoop)

        // 合并重叠/相邻的匹配范围
        matchedRanges.sortBy { it.first }
        val mergedRanges = mutableListOf<Pair<Long, Long>>()
        for (range in matchedRanges) {
            val last = mergedRanges.lastOrNull()
            if (last != null && range.first <= last.second + STEP_MS) {
                mergedRanges[mergedRanges.size - 1] = last.first to maxOf(last.second, range.second)
            } else {
                mergedRanges.add(range)
            }
        }

        // 生成全量分段列表（待处理+指纹水货交替）
        // v3.1.30: 第1层只负责指纹匹配，不管理hasVoice标签。
        // 待处理片段不需要默认属性（hasVoice保持默认false），由第2层VAD+YAMNet决定干湿。
        // 匹配部分标记为"指纹水货"(hasVoice=false)，第2层直接认可为水分。
        val segments = mutableListOf<VoiceSegment>()
        var currentPos = 0L
        for (waterRange in mergedRanges) {
            if (waterRange.first > currentPos) {
                segments.add(VoiceSegment().apply {
                    start = currentPos
                    end = waterRange.first
                    label = "待处理"
                    isSimulated = false
                })
            }
            segments.add(VoiceSegment().apply {
                start = waterRange.first
                end = waterRange.second
                hasVoice = false
                label = "指纹水货"
                isSimulated = false
            })
            currentPos = waterRange.second
        }
        if (currentPos < durationMs) {
            segments.add(VoiceSegment().apply {
                start = currentPos
                end = durationMs
                label = "待处理"
                isSimulated = false
            })
        }

        val fpMsg = "第一层滑动窗口: 共${totalWindows}个窗口，匹配${matchedWindows}个（${mergedRanges.size}个水货段），${segments.size}个片段，去重${dedupCount}个近似指纹 [${hitDetails.joinToString("; ")}]"
        Log.i(TAG, fpMsg)
        writeFingerprintLog(context, fpMsg)

        return segments
    }

    /**
     * v3.1.128: 计算PCM 16位样本的RMS能量值。
     * 16kHz单声道16bit小端PCM，每个样本2字节。
     * RMS越高表示窗口音量越大，越低表示越接近静音。
     */
    private fun computePcmRms(pcmBytes: ByteArray): Float {
        if (pcmBytes.size < 4) return 0f
        // v3.1.128: 采样——对完整15秒窗口(480000字节)计算RMS很耗时，
        // 只取前10%的样本估算能量，足够判断是否为静音
        val sampleStep = 10
        val sampleCount = pcmBytes.size / (2 * sampleStep)
        if (sampleCount <= 0) return 0f
        var sumSq = 0.0
        var samples = 0
        var byteIdx = 0
        while (byteIdx + 1 < pcmBytes.size) {
            val low = pcmBytes[byteIdx].toInt() and 0xFF
            val high = pcmBytes[byteIdx + 1].toInt() and 0xFF
            val sample = (low or (high shl 8)).toShort().toInt()
            sumSq += (sample * sample).toDouble()
            samples++
            byteIdx += 2 * sampleStep
        }
        return if (samples > 0) kotlin.math.sqrt(sumSq / samples).toFloat() else 0f
    }

    /**
     * v3.2.2: 第三层指纹漏判召回。
     * 仅处理第二层输出的干货片段，仅使用金标准指纹（人工录入）做查询。
     * 如果指纹命中：说明模型发生漏判，该片段实际是水分。
     * 将该片段与前后相邻的水分片段合并，生成一整块大的水分片段。
     */
    private fun applyFingerprintRecallLayer3(
        context: Context,
        episodeId: String,
        segments: List<VoiceSegment>,
        goldStandardFingerprints: List<AudioFingerprint>
    ): List<VoiceSegment> {
        if (segments.isEmpty() || goldStandardFingerprints.isEmpty()) return segments
        if (!ChromaprintExtractor.ensureLibraryLoaded(context)) {
            val fpMsg = "第三层指纹漏判召回: 跳过（指纹引擎未就绪）"
            Log.w(TAG, fpMsg)
            writeFingerprintLog(context, fpMsg)
            return segments
        }

        val appContext = context.applicationContext
        val result = segments.map { it.copy() }.toMutableList()
        var recallCount = 0
        val recallDetails = mutableListOf<String>()

        val fpMsgStart = "第三层指纹漏判召回: 金标准指纹库${goldStandardFingerprints.size}条，待审核干货${result.count { it.hasVoice }}段"
        Log.i(TAG, fpMsgStart)
        writeFingerprintLog(context, fpMsgStart)

        for (i in result.indices) {
            val seg = result[i]
            if (!seg.hasVoice) continue
            if (seg.end - seg.start < MIN_SEGMENT_MS_FOR_FINGERPRINT) continue

            var tempPcmFile: File? = null
            try {
                tempPcmFile = try {
                    PcmSegmentExtractor.extractSegmentPcm(appContext, episodeId, seg.start, seg.end)
                } catch (e: Throwable) {
                    Log.w(TAG, "第三层指纹漏判召回: 提取片段${seg.start/1000}秒PCM异常: ${e.message}")
                    null
                }
                if (tempPcmFile == null || !tempPcmFile.exists() || tempPcmFile.length() <= 0) continue

                val fingerprint = try {
                    ChromaprintExtractor.extractFingerprintFromFile(tempPcmFile)
                } catch (e: Throwable) {
                    Log.w(TAG, "第三层指纹漏判召回: 提取片段${seg.start/1000}秒指纹异常: ${e.message}")
                    null
                }
                if (fingerprint.isNullOrBlank()) continue

                var matched = false
                var matchedSimilarity = 0f
                for (goldFp in goldStandardFingerprints) {
                    val durationRatio = minOf(seg.end - seg.start, goldFp.durationMs).toFloat() /
                            maxOf(seg.end - seg.start, goldFp.durationMs).toFloat()
                    if (durationRatio < 0.4f) continue

                    val sim = ChromaprintExtractor.compareFingerprints(fingerprint, goldFp.fingerprint)
                    if (sim >= LAYER3_RECALL_THRESHOLD) {
                        matched = true
                        matchedSimilarity = sim
                        break
                    }
                }

                if (matched) {
                    seg.hasVoice = false
                    seg.label = "水货(漏判召回)"
                    recallCount++
                    recallDetails.add("${seg.start/1000}秒-${seg.end/1000}秒(相似度:${"%.0f".format(matchedSimilarity*100)}%)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "第三层指纹漏判召回: 片段${seg.start/1000}秒异常: ${e.message}")
            } finally {
                try { tempPcmFile?.delete() } catch (_: Exception) {}
            }
        }

        // 将被召回的片段与前后相邻的水分片段合并
        val merged = mergeAdjacentSegments(result)

        if (recallCount > 0) {
            val fpMsg = "第三层指纹漏判召回: 召回${recallCount}个漏判片段 [${recallDetails.joinToString("; ")}]，合并后共${merged.count{!it.hasVoice}}段水货"
            Log.i(TAG, fpMsg)
            writeFingerprintLog(context, fpMsg)
        } else {
            val fpMsg = "第三层指纹漏判召回: 无漏判，所有干货保持原分类"
            Log.i(TAG, fpMsg)
            writeFingerprintLog(context, fpMsg)
        }

        return merged
    }

    /**
     * v3.1.95: 第三层指纹漏判召回，带召回计数返回。
     * 与 applyFingerprintRecallLayer3 逻辑相同，但返回 Pair 包含实际召回数。
     * 解决原 recallCount 在函数内部无法传递到外层的问题。
     */
    private fun applyFingerprintRecallLayer3WithCount(
        context: Context,
        episodeId: String,
        segments: List<VoiceSegment>,
        goldStandardFingerprints: List<AudioFingerprint>
    ): Pair<List<VoiceSegment>, Int> {
        if (segments.isEmpty() || goldStandardFingerprints.isEmpty()) return Pair(segments, 0)
        if (!ChromaprintExtractor.ensureLibraryLoaded(context)) {
            val fpMsg = "第三层指纹漏判召回: 跳过（指纹引擎未就绪）"
            Log.w(TAG, fpMsg)
            writeFingerprintLog(context, fpMsg)
            return Pair(segments, 0)
        }

        val appContext = context.applicationContext
        val result = segments.map { it.copy() }.toMutableList()
        var recallCount = 0
        val recallDetails = mutableListOf<String>()

        val fpMsgStart = "第三层指纹漏判召回: 金标准指纹库${goldStandardFingerprints.size}条，待审核干货${result.count { it.hasVoice }}段"
        Log.i(TAG, fpMsgStart)
        writeFingerprintLog(context, fpMsgStart)

        for (i in result.indices) {
            val seg = result[i]
            if (!seg.hasVoice) continue
            if (seg.end - seg.start < MIN_SEGMENT_MS_FOR_FINGERPRINT) continue

            var tempPcmFile: File? = null
            try {
                tempPcmFile = try {
                    PcmSegmentExtractor.extractSegmentPcm(appContext, episodeId, seg.start, seg.end)
                } catch (e: Throwable) {
                    Log.w(TAG, "第三层指纹漏判召回: 提取片段${seg.start/1000}秒PCM异常: ${e.message}")
                    null
                }
                if (tempPcmFile == null || !tempPcmFile.exists() || tempPcmFile.length() <= 0) continue

                val fingerprint = try {
                    ChromaprintExtractor.extractFingerprintFromFile(tempPcmFile)
                } catch (e: Throwable) {
                    Log.w(TAG, "第三层指纹漏判召回: 提取片段${seg.start/1000}秒指纹异常: ${e.message}")
                    null
                }
                if (fingerprint.isNullOrBlank()) continue

                var matched = false
                var matchedSimilarity = 0f
                for (goldFp in goldStandardFingerprints) {
                    val durationRatio = minOf(seg.end - seg.start, goldFp.durationMs).toFloat() /
                            maxOf(seg.end - seg.start, goldFp.durationMs).toFloat()
                    if (durationRatio < 0.4f) continue

                    val sim = ChromaprintExtractor.compareFingerprints(fingerprint, goldFp.fingerprint)
                    if (sim >= LAYER3_RECALL_THRESHOLD) {
                        matched = true
                        matchedSimilarity = sim
                        break
                    }
                }

                if (matched) {
                    seg.hasVoice = false
                    seg.label = "水货(漏判召回)"
                    recallCount++
                    recallDetails.add("${seg.start/1000}秒-${seg.end/1000}秒(相似度:${"%.0f".format(matchedSimilarity*100)}%)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "第三层指纹漏判召回: 片段${seg.start/1000}秒异常: ${e.message}")
            } finally {
                try { tempPcmFile?.delete() } catch (_: Exception) {}
            }
        }

        val merged = mergeAdjacentSegments(result)

        if (recallCount > 0) {
            val fpMsg = "第三层指纹漏判召回: 召回${recallCount}个漏判片段 [${recallDetails.joinToString("; ")}]，合并后共${merged.count{!it.hasVoice}}段水货"
            Log.i(TAG, fpMsg)
            writeFingerprintLog(context, fpMsg)
        } else {
            val fpMsg = "第三层指纹漏判召回: 无漏判，所有干货保持原分类"
            Log.i(TAG, fpMsg)
            writeFingerprintLog(context, fpMsg)
        }

        return Pair(merged, recallCount)
    }

    /**
     * v3.2.2: 处理观察池候选入库。
     * 第三层合并得到的大水分片段，不直接加入正式指纹库，先进入观察池做重复出现验证。
     *
     * 前置过滤条件：
     * 1. 合并后片段时长：15秒～600秒
     * 2. 第三层指纹匹配相似度 ≥0.82
     * 3. 和正式库、观察池内已有指纹相似度大于0.92视为重复，不重复新增候选
     */
    private fun processObservationPoolForSegment(
        context: Context,
        episodeId: String,
        layer3MergedSegments: List<VoiceSegment>,
        layer2Segments: List<VoiceSegment>,
        goldStandardFingerprints: List<AudioFingerprint>
    ) {
        try {
            val dbHelper = RadioDatabaseHelper.getInstance(context)

            // 找出第三层新召回的片段（在layer2中是干货，在layer3中变成水货的片段）
            val recalledSegments = layer3MergedSegments.filter { !it.hasVoice }

            for (waterSeg in recalledSegments) {
                val durationMs = waterSeg.end - waterSeg.start

                // 前置过滤1: 时长 15秒～600秒
                if (durationMs < POOL_MIN_DURATION_MS || durationMs > POOL_MAX_DURATION_MS) {
                    Log.d(TAG, "观察池过滤: 片段${waterSeg.start/1000}s-${waterSeg.end/1000}s时长${durationMs/1000}s不在15~600s范围，丢弃")
                    continue
                }

                if (!ChromaprintExtractor.ensureLibraryLoaded(context)) continue
                var tempPcmFile: File? = null
                try {
                    tempPcmFile = PcmSegmentExtractor.extractSegmentPcm(
                        context.applicationContext, episodeId, waterSeg.start, waterSeg.end
                    )
                    if (tempPcmFile == null || !tempPcmFile.exists() || tempPcmFile.length() <= 0) continue

                    val fingerprint = ChromaprintExtractor.extractFingerprintFromFile(tempPcmFile)
                    if (fingerprint.isNullOrBlank()) continue

                    // 前置过滤2: 计算与金标准指纹的最大相似度
                    var maxSimilarity = 0f
                    for (goldFp in goldStandardFingerprints) {
                        val sim = ChromaprintExtractor.compareFingerprints(fingerprint, goldFp.fingerprint)
                        if (sim > maxSimilarity) maxSimilarity = sim
                    }
                    if (maxSimilarity < LAYER3_RECALL_THRESHOLD) {
                        Log.d(TAG, "观察池过滤: 片段${waterSeg.start/1000}s-${waterSeg.end/1000}s相似度${"%.2f".format(maxSimilarity)}<0.82，丢弃")
                        continue
                    }

                    // 前置过滤3: 检查是否与正式库或观察池重复（相似度 > 0.92）
                    if (dbHelper.isDuplicateFingerprint(fingerprint, POOL_DUPLICATE_THRESHOLD)) {
                        val hash = fingerprintHash(fingerprint)
                        val existing = dbHelper.findObservationPoolCandidateByHash(hash)
                        if (existing != null) {
                            dbHelper.incrementObservationPoolHit(
                                existing.id, episodeId, POOL_PROMOTION_THRESHOLD_DEFAULT
                            )
                            val fpMsg = "观察池: 候选指纹#${existing.id}跨节目命中（节目ID=$episodeId），hit_count=${existing.hitCount + 1}"
                            Log.i(TAG, fpMsg)
                            writeFingerprintLog(context, fpMsg)
                        } else {
                            // v3.1.134: hash不匹配时fallback到全量对比，因为指纹字符串前64字符可能不稳定
                            // 同一段音频在不同节目中的指纹提取可能有微小差异，导致hash不同。
                            var matchedCandidate: RadioDatabaseHelper.ObservationPoolCandidate? = null
                            var maxSim = 0f
                            try {
                                val allCandidates = dbHelper.getAllObservationPoolCandidates()
                                for (cand in allCandidates) {
                                    val sim = ChromaprintExtractor.compareFingerprints(fingerprint, cand.fingerprint)
                                    if (sim > POOL_DUPLICATE_THRESHOLD && sim > maxSim) {
                                        maxSim = sim
                                        matchedCandidate = cand
                                    }
                                }
                            } catch (_: Exception) {}
                            if (matchedCandidate != null) {
                                dbHelper.incrementObservationPoolHit(
                                    matchedCandidate.id, episodeId, POOL_PROMOTION_THRESHOLD_DEFAULT
                                )
                                val fpMsg = "观察池: 候选指纹#${matchedCandidate.id}跨节目命中（hash fallback，相似度${"%.0f".format(maxSim*100)}%，节目ID=$episodeId），hit_count=${matchedCandidate.hitCount + 1}"
                                Log.i(TAG, fpMsg)
                                writeFingerprintLog(context, fpMsg)
                            } else {
                                val fpMsg = "观察池: 重复但hash未匹配且无fallback匹配（节目ID=$episodeId），跳过"
                                Log.d(TAG, fpMsg)
                                writeFingerprintLog(context, fpMsg)
                            }
                        }
                        continue
                    }

                    // 全部条件满足，进入观察池
                    val hash = fingerprintHash(fingerprint)
                    val now = System.currentTimeMillis()
                    val candidate = RadioDatabaseHelper.ObservationPoolCandidate(
                        fingerprintHash = hash,
                        fingerprint = fingerprint,
                        episodeId = episodeId,
                        durationMs = durationMs,
                        similarity = maxSimilarity,
                        hitCount = 1,
                        lastHitTime = now,
                        expiredAt = now + 30L * 24 * 60 * 60 * 1000,
                        createdAt = now,
                        updatedAt = now
                    )
                    val poolId = dbHelper.saveObservationPoolCandidate(candidate)
                    if (poolId > 0) {
                        dbHelper.cleanupExpiredObservationPool()
                        val fpMsg = "观察池: 新候选#${poolId}入库（${waterSeg.start/1000}s-${waterSeg.end/1000}s，相似度${"%.2f".format(maxSimilarity)}，节目ID=$episodeId）"
                        Log.i(TAG, fpMsg)
                        writeFingerprintLog(context, fpMsg)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "观察池处理异常: ${e.message}")
                } finally {
                    try { tempPcmFile?.delete() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processObservationPoolForSegment failed: ${e.message}")
        }
    }

    /**
     * v3.2.2: 生成指纹hash（取指纹字符串前N字符）。
     */
    private fun fingerprintHash(fingerprint: String): String {
        return fingerprint.take(FINGERPRINT_HASH_PREFIX_LEN)
    }

    /**
     * v3.1.30: 叠加第1层指纹水货段到VAD结果上。
     * VAD结果中与第1层"指纹水货"段重叠的片段，改为"指纹水货"分类。
     * 不重叠的片段保留VAD的原始分类。
     */
    /**
     * v3.1.30: 验证三层分段结果是否正常。
     * - 全部水分或全部干货均为异常
     * - 分段数过少（两小时节目应>20段）为异常
     * 仅记录异常日志，不允许回退其他方案。
     * @return 验证结果描述，null表示正常
     */
    private fun validateThreeLayerResult(
        segments: List<VoiceSegment>,
        durationMs: Long
    ): String? {
        if (segments.isEmpty()) {
            return "分段结果为空"
        }

        val waterCount = segments.count { !it.hasVoice }
        val dryCount = segments.count { it.hasVoice }

        if (waterCount == segments.size) {
            return "全部${segments.size}个分段均为水分（0个干货）"
        }
        if (dryCount == segments.size) {
            return "全部${segments.size}个分段均为干货（0个水分）"
        }

        // 两小时节目应有60~100个分段，小于20个为异常
        val durationMinutes = durationMs / 60000
        if (durationMinutes >= 60 && segments.size < 20) {
            return "分段数过少: ${segments.size}个分段（节目时长${durationMinutes}分钟，预期60~100段）"
        }

        return null
    }
}
