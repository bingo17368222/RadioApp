package com.radio.app.utils

import android.content.Context
import android.util.Log
import com.radio.app.database.AudioFingerprint
import com.radio.app.database.RadioDatabaseHelper
import com.radio.app.models.AppSettings
import com.radio.app.models.VoiceSegment
import com.radio.app.utils.ChromaprintExtractor
import com.radio.app.utils.PcmSegmentExtractor
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
    private fun isWaterLabel(label: String?): Boolean {
        return label == "指纹水货" || label == "水货" || label == "水货(漏判召回)"
    }

    private fun mergeAdjacentSegments(segments: List<VoiceSegment>): MutableList<VoiceSegment> {
        val merged = mutableListOf<VoiceSegment>()
        for (seg in segments) {
            val last = merged.lastOrNull()
            if (last != null && last.hasVoice == seg.hasVoice
                    && isWaterLabel(last.label) == isWaterLabel(seg.label)) {
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
        return merged
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
            Log.i(TAG, "preSegmentAudio: running audio segmentation for episode=$episodeId")

            // v2.4.186: Use non-blocking mode so background pre-segmentation skips when
            // another audio analysis (manual or another pre-segment task) is already running.
            val result = tryGenerateAudioSegments(
                context, episodeId, durationMs, audioUrl,
                progressCallback = { permille, elapsedMs, etaMs ->
                    val elapsedText = AudioSegmentAnalyzer.formatDurationMs(elapsedMs)
                    val etaText = AudioSegmentAnalyzer.formatDurationMs(etaMs)
                    SegmentNotificationHelper.update(context, episodeId, episodeTitle, permille, elapsedText, etaText)
                },
                blocking = false
            )
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
        val segStartTime = System.currentTimeMillis()
        val fpMsgStart = "generateJiuAiTingSegments: 就AI听三层架构方案 for episode=$episodeId"
        Log.i(TAG, fpMsgStart)
        writeFingerprintLog(context, fpMsgStart)

        val dbHelper = RadioDatabaseHelper.getInstance(context)

        // v3.1.28: 启动通知会话，显示三层分段进度
        val episodeInfo = try { dbHelper.getEpisodeInfo(episodeId) } catch (_: Exception) { null }
        val episodeTitle = buildSegmentNotificationTitle(episodeId, episodeInfo?.title)
        val sessionStarted = SegmentNotificationHelper.startSession(
            context, episodeId, episodeTitle, SegmentNotificationHelper.PRIORITY_MANUAL
        )
        if (!sessionStarted) {
            Log.w(TAG, "generateJiuAiTingSegments: 通知会话未启动（已有更高优先级会话）")
        }

        // ========== 获取指纹库 ==========
        // 正式指纹库（金标准+自动晋升）→ 第一层使用
        val formalLibrary = try { dbHelper.getFormalLibraryFingerprints() } catch (_: Exception) { emptyList() }
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
        val pcmSourceFile = when {
            fullPcmFile.exists() && fullPcmFile.length() > 0 -> fullPcmFile
            else -> null
        }

        val mergedAfterLayer1: List<VoiceSegment>
        var layer1MatchCount = 0
        var layer2DrySegments = 0
        var layer2WaterSegments = 0
        var layer3RecallCount = 0
        var observationPoolNewCount = 0
        var observationPoolHitCount = 0

        if (pcmSourceFile != null && formalLibrary.isNotEmpty() && ChromaprintExtractor.ensureLibraryLoaded(context)) {
            // 滑动窗口指纹匹配，传递进度回调以更新通知
            val slidingProgressCallback: ((Int, Long, Long) -> Unit)? = { permille, _, _ ->
                SegmentNotificationHelper.update(context, episodeId, episodeTitle, permille / 10, "第1层指纹快筛", "")
            }
            val slidingResult = applyLayer1SlidingWindow(context, episodeId, pcmSourceFile, durationMs, formalLibrary, slidingProgressCallback)
            mergedAfterLayer1 = slidingResult
            layer1MatchCount = slidingResult.count { it.label == "指纹水货" }
            audioEngineName = "滑动窗口指纹"
            Log.i(TAG, "三层架构: 第一层滑动窗口完成，匹配${layer1MatchCount}个水货段，共${mergedAfterLayer1.size}个片段 for episode=$episodeId")
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
                AudioSegmentAnalyzer.analyzeEpisode(context, episodeId, durationMs, audioUrl, progressCallback)
            } catch (e: Exception) {
                Log.w(TAG, "三层架构: 全量VAD+YAMNet失败: ${e.message}")
                null
            }
            if (fullVadResult != null && fullVadResult.segments.size >= 2) {
                mergedAfterLayer1 = mergeAdjacentSegments(fullVadResult.segments.map { it.copy() })
                audioEngineName = fullVadResult.engineName
                Log.i(TAG, "三层架构: 全量VAD+YAMNet生成${mergedAfterLayer1.size}个真实分段 for episode=$episodeId")
            } else {
                // VAD也无结果，使用固定分段兜底
                val fixedSegs = generateFixedSegments(durationMs)
                mergedAfterLayer1 = fixedSegs
                Log.w(TAG, "三层架构: 无有效分段，生成固定分段(${fixedSegs.size}个)兜底 for episode=$episodeId")
            }
        }

        if (mergedAfterLayer1.isEmpty()) {
            val fpMsgEmpty = "generateJiuAiTingSegments: 无分段结果"
            Log.w(TAG, fpMsgEmpty)
            writeFingerprintLog(context, fpMsgEmpty)
            SegmentNotificationHelper.endSession(context, episodeId)
            return null
        }

        val totalPendingSegments = mergedAfterLayer1.count { it.label == "待处理" }

        // ========== 第二层：双模型判定(VAD+YAMNet，仅处理第一层"待处理"部分) ==========
        // 提取第一层后标记为"待处理"的片段，第2层通过VAD+YAMNet决定干湿分类
        // 第1层已标记为"指纹水货"的片段，第2层直接认可为水分，不重新处理
        val pendingSegmentsAfterLayer1 = mergedAfterLayer1.filter { it.label == "待处理" }
        val waterSegmentsAfterLayer1 = mergedAfterLayer1.filter { it.label == "指纹水货" }

        // v3.1.22: 第二层条件改为检查VAD/YAMNet模型（而非Chromaprint指纹库）
        // 没有音频分段结果的节目，必须运行第二层VAD+YAMNet
        val vadModelDir = AudioSegmentAnalyzer.getModelDir(context)
        val vadModelsReady = AudioSegmentAnalyzer.isModelInstalled(vadModelDir)
        if (pendingSegmentsAfterLayer1.isNotEmpty() && vadModelsReady) {
            Log.i(TAG, "三层架构: 第二层VAD+YAMNet处理${pendingSegmentsAfterLayer1.size}个第一层待处理片段 for episode=$episodeId")

            // 对每个待处理片段提取PCM并运行VAD+YAMNet
            val layer2SubSegments = mutableListOf<VoiceSegment>()
            val layer2Total = pendingSegmentsAfterLayer1.size
            for ((layer2Idx, drySeg) in pendingSegmentsAfterLayer1.withIndex()) {
                // v3.1.28: 更新通知进度（第2层进度 0~1000‰）
                val layer2Progress = ((layer2Idx + 1) * 1000 / layer2Total).coerceIn(0, 1000)
                SegmentNotificationHelper.update(context, episodeId, episodeTitle, 500 + layer2Progress / 20, "第2层VAD分析(${layer2Idx + 1}/$layer2Total)", "")
                try {
                    // v3.1.29: 尝试提取PCM，如果失败则尝试重新生成
                    var tempPcmFile = PcmSegmentExtractor.extractSegmentPcm(
                        context.applicationContext, episodeId, drySeg.start, drySeg.end
                    )

                    // v3.1.29: 如果PCM提取失败，尝试重新生成完整PCM
                    if (tempPcmFile == null || !tempPcmFile.exists() || tempPcmFile.length() <= 0) {
                        val pcmCacheDir = com.radio.app.RadioApplication.getPcmCacheDir(context)
                        val fullPcmFile = File(pcmCacheDir, "${episodeId}_full.pcm")
                        if (fullPcmFile.exists() && fullPcmFile.length() > 0) {
                            // 完整PCM存在但提取失败，尝试直接读取
                            tempPcmFile = PcmSegmentExtractor.extractSegmentFromFile(fullPcmFile, drySeg.start, drySeg.end)
                            if (tempPcmFile != null && tempPcmFile.exists() && tempPcmFile.length() > 0) {
                                Log.i(TAG, "三层架构: 第二层从完整PCM直接提取成功 for ${drySeg.start/1000}s-${drySeg.end/1000}s")
                            }
                        } else {
                            // 完整PCM不存在，尝试重新生成
                            Log.w(TAG, "三层架构: 第二层完整PCM不存在，尝试重新生成 for episode=$episodeId")
                            tempPcmFile = regenerateFullPcmAndExtractSegment(context, episodeId, drySeg.start, drySeg.end, fullPcmFile, audioUrl)
                            if (tempPcmFile != null && tempPcmFile.exists() && tempPcmFile.length() > 0) {
                                Log.i(TAG, "三层架构: 第二层重新生成PCM成功 for ${drySeg.start/1000}s-${drySeg.end/1000}s")
                            }
                        }
                    }

                    if (tempPcmFile != null && tempPcmFile.exists() && tempPcmFile.length() > 0) {
                         val pcmDurationMs = drySeg.end - drySeg.start
                         // 对该片段PCM运行VAD+YAMNet分析（使用原始独立方案音频分段算法）
                         val result = AudioSegmentAnalyzer.analyzePcmFile(
                             context, tempPcmFile, pcmDurationMs, progressCallback
                         )
                         // 调整VAD结果中的时间偏移量（从片段相对偏移变为全局偏移）
                         for (subSeg in result.segments) {
                             subSeg.start += drySeg.start
                             subSeg.end += drySeg.start
                         }
                         if (result.segments.isNotEmpty()) {
                            layer2SubSegments.addAll(result.segments)
                            // 统计VAD+YAMNet产出的干湿片段数
                            for (subSeg in result.segments) {
                                if (subSeg.hasVoice) layer2DrySegments++ else layer2WaterSegments++
                            }
                            Log.i(TAG, "三层架构: 第二层分析片段${drySeg.start/1000}s-${drySeg.end/1000}s，产出${result.segments.size}个VAD子片段")
                        } else {
                            // v3.1.29: VAD无结果时，标记为干货（hasVoice已由第1层设为true）
                            drySeg.label = "干货"
                            layer2SubSegments.add(drySeg)
                            layer2DrySegments++
                            Log.w(TAG, "三层架构: 第二层分析片段${drySeg.start/1000}s-${drySeg.end/1000}s无VAD结果，标记为干货")
                        }
                        tempPcmFile.delete()
                    } else {
                        val failReason = when {
                            tempPcmFile == null -> "PCM提取返回null"
                            !tempPcmFile.exists() -> "PCM文件不存在"
                            tempPcmFile.length() <= 0 -> "PCM文件为空"
                            else -> "未知原因"
                        }
                        // v3.1.29: PCM提取失败，标记为干货并记录失败原因（hasVoice已由第1层设为true）
                        drySeg.label = "干货(分段失败: $failReason)"
                        layer2SubSegments.add(drySeg)
                        layer2DrySegments++
                        Log.w(TAG, "三层架构: 第二层片段${drySeg.start/1000}s-${drySeg.end/1000}s无PCM数据($failReason)，标记为干货")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "三层架构: 第二层分析片段${drySeg.start/1000}s异常: ${e.message}")
                    // v3.1.29: 异常时标记为干货并记录异常原因（hasVoice已由第1层设为true）
                    drySeg.label = "干货(分段失败: ${e.javaClass.simpleName}: ${e.message})"
                    layer2SubSegments.add(drySeg)
                    layer2DrySegments++
                }
            }

            // 合并第一层水货片段 + 第二层子片段
            val combinedSegments = (waterSegmentsAfterLayer1 + layer2SubSegments).sortedBy { it.start }
            val mergedAfterLayer2 = mergeAdjacentSegments(combinedSegments)
            audioEngineName = "VAD+YAMNet+三层"

            Log.i(TAG, "三层架构: 第二层完成，合并后${mergedAfterLayer2.size}个片段（干货${mergedAfterLayer2.count { it.hasVoice }}段，水货${mergedAfterLayer2.count { !it.hasVoice }}段）")

            // ========== 第三层：指纹漏判召回（仅干货 + 仅金标准） ==========
            val layer3Result = if (goldStandardFingerprints.isNotEmpty() && ChromaprintExtractor.ensureLibraryLoaded(context)) {
                val recalled = applyFingerprintRecallLayer3(context, episodeId, mergedAfterLayer2, goldStandardFingerprints)
                layer3RecallCount = recalled.count { !it.hasVoice } - mergedAfterLayer2.count { !it.hasVoice }
                Log.i(TAG, "三层架构: 第三层指纹漏判召回完成，召回${layer3RecallCount}个漏判片段")
                recalled
            } else {
                val fpMsg = "三层架构: 第三层指纹漏判召回跳过（金标准库为空或指纹引擎未就绪）"
                Log.i(TAG, fpMsg)
                writeFingerprintLog(context, fpMsg)
                mergedAfterLayer2
            }

            // 最终合并
            val finalSegments = mergeAdjacentSegments(layer3Result).apply {
                for (seg in this) {
                    if (!seg.hasVoice && seg.label == null) {
                        seg.label = "指纹水货"
                    }
                }
            }

            // 日志统计
            val fpMsgStats = "三层架构完成: ${finalSegments.size}个片段（原待处理${totalPendingSegments}段，第一层快筛${layer1MatchCount}段，第二层VAD产出${layer2DrySegments}干/${layer2WaterSegments}水，第三层召回${layer3RecallCount}段）"
            Log.i(TAG, fpMsgStats)
            writeFingerprintLog(context, fpMsgStats)

            // ========== 观察池处理 ==========
            if (goldStandardFingerprints.isNotEmpty() && ChromaprintExtractor.ensureLibraryLoaded(context)) {
                processObservationPoolForSegment(context, episodeId, finalSegments, mergedAfterLayer2, goldStandardFingerprints)
                // 处理观察池中已达晋升条件的候选
                val promoted = dbHelper.getPromotableCandidates(POOL_PROMOTION_THRESHOLD_DEFAULT)
                for (candidate in promoted) {
                    try {
                        dbHelper.incrementObservationPoolHit(candidate.id, episodeId, POOL_PROMOTION_THRESHOLD_DEFAULT)
                    } catch (_: Exception) {}
                }
                // 清理过期观察池候选
                dbHelper.cleanupExpiredObservationPool()

                val fpMsgPool = "观察池: 当前共${dbHelper.getObservationPoolCount()}个候选"
                Log.i(TAG, fpMsgPool)
                writeFingerprintLog(context, fpMsgPool)
            } else {
                val fpMsg = "观察池处理: 跳过（金标准库为空或指纹引擎未就绪）"
                Log.i(TAG, fpMsg)
                writeFingerprintLog(context, fpMsg)
            }

            // v3.1.29: 验证三层分段结果是否正常
            val validationResult = validateThreeLayerResult(finalSegments, durationMs)
            if (validationResult != null) {
                // 结果异常，回退到全量VAD+YAMNet分析
                val fpMsgAbnormal = "三层架构结果异常: $validationResult，回退到全量VAD+YAMNet分析"
                Log.w(TAG, fpMsgAbnormal)
                writeFingerprintLog(context, fpMsgAbnormal)

                val fullVadFallback = try {
                    val fullVadResult = AudioSegmentAnalyzer.analyzeEpisode(
                        context, episodeId, durationMs, audioUrl,
                        progressCallback = { permille, elapsedMs, etaMs ->
                            SegmentNotificationHelper.update(context, episodeId, episodeTitle, permille, "全量VAD回退分析", "")
                        }
                    )
                    if (fullVadResult.segments.size >= 2) {
                        val fallbackSegments = mergeAdjacentSegments(fullVadResult.segments.map { it.copy() })
                        val fpMsgFallbackOk = "三层架构回退成功: 全量VAD+YAMNet生成${fallbackSegments.size}个分段（原三层仅${finalSegments.size}个）"
                        Log.i(TAG, fpMsgFallbackOk)
                        writeFingerprintLog(context, fpMsgFallbackOk)
                        fallbackSegments
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "三层架构回退到全量VAD失败: ${e.message}")
                    null
                }

                if (fullVadFallback != null) {
                    // 使用回退结果
                    SegmentNotificationHelper.update(context, episodeId, episodeTitle, 1000, "全量VAD回退完成", "")
                    SegmentNotificationHelper.endSession(context, episodeId)
                    return JiuAiTingResult(
                        segments = fullVadFallback,
                        engineName = "VAD+YAMNet(三层回退)",
                        processingTimeMs = System.currentTimeMillis() - segStartTime,
                        matchedCount = 0,
                        totalDrySegments = 0,
                        layer1MatchCount = 0,
                        layer3RecallCount = 0,
                        observationPoolNewCount = 0,
                        observationPoolHitCount = 0
                    )
                } else {
                    // 回退也失败，继续使用三层结果（至少有问题也比空结果好）
                    val fpMsgFallbackFail = "三层架构回退失败，继续使用异常三层结果（${finalSegments.size}个分段）"
                    Log.w(TAG, fpMsgFallbackFail)
                    writeFingerprintLog(context, fpMsgFallbackFail)
                }
            }

            // v3.1.28: 更新通知为100%完成
            SegmentNotificationHelper.update(context, episodeId, episodeTitle, 1000, "三层分段完成", "")

            val fpMsgDone = "就AI听三层架构完成: ${finalSegments.size}个片段（原待处理${totalPendingSegments}段，快筛${layer1MatchCount}段，VAD${layer2WaterSegments}段，召回${layer3RecallCount}段）"
            Log.i(TAG, fpMsgDone)
            writeFingerprintLog(context, fpMsgDone)

            SegmentNotificationHelper.endSession(context, episodeId)
            return JiuAiTingResult(
                segments = finalSegments,
                engineName = audioEngineName,
                processingTimeMs = System.currentTimeMillis() - segStartTime,
                matchedCount = layer1MatchCount + layer3RecallCount,
                totalDrySegments = totalPendingSegments,
                layer1MatchCount = layer1MatchCount,
                layer3RecallCount = layer3RecallCount,
                observationPoolNewCount = observationPoolNewCount,
                observationPoolHitCount = observationPoolHitCount
            )
        } else {
            // 没有待处理片段需要处理，直接使用第一层结果
            val fpMsg = "三层架构: 第一层后无待处理片段，跳过第二、三层，直接使用第一层结果"
            Log.i(TAG, fpMsg)
            writeFingerprintLog(context, fpMsg)

            SegmentNotificationHelper.endSession(context, episodeId)
            return JiuAiTingResult(
                segments = mergedAfterLayer1,
                engineName = "三层架构(仅第一层)",
                processingTimeMs = System.currentTimeMillis() - segStartTime,
                matchedCount = layer1MatchCount,
                totalDrySegments = totalPendingSegments,
                layer1MatchCount = layer1MatchCount,
                layer3RecallCount = 0,
                observationPoolNewCount = 0,
                observationPoolHitCount = 0
            )
        }
    }

    /**
     * v3.1.23: 第一层滑动窗口指纹匹配。
     * 对完整PCM以固定窗口大小滑动，提取每个窗口的指纹并与正式指纹库比对。
     * 匹配到的窗口合并为水货段，未匹配的窗口标记为干货。
     * 不再依赖任何已有分段结果，完全从头开始。
     */
    private fun applyLayer1SlidingWindow(
        context: Context,
        episodeId: String,
        pcmFile: File,
        durationMs: Long,
        formalLibrary: List<AudioFingerprint>,
        progressCallback: ((Int, Long, Long) -> Unit)? = null
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

        // 预解析正式库指纹为整数数组（避免反复解析）
        val parsedLibrary = formalLibrary.map { fp ->
            ChromaprintExtractor.parseFingerprint(fp.fingerprint) to fp.fingerprint
        }

        val matchedRanges = mutableListOf<Pair<Long, Long>>()
        val hitDetails = mutableListOf<String>()
        var totalWindows = 0
        var matchedWindows = 0
        var lastReportedPct = -1

        var pos = 0L
        while (pos + WINDOW_MS <= durationMs) {
            totalWindows++

            // 进度回调
            val pct = ((pos * 1000L) / durationMs).toInt().coerceIn(0, 1000)
            if (pct / 50 != lastReportedPct / 50) {
                lastReportedPct = pct
                progressCallback?.invoke(pct, System.currentTimeMillis() - 0, 0)
            }

            try {
                val pcmBytes = PcmSegmentExtractor.readSegmentBytes(pcmFile, pos, pos + WINDOW_MS)
                if (pcmBytes == null || pcmBytes.isEmpty()) {
                    pos += STEP_MS
                    continue
                }

                val fingerprint = ChromaprintExtractor.extractFingerprint(pcmBytes)
                if (fingerprint.isNullOrBlank()) {
                    pos += STEP_MS
                    continue
                }

                val parsedWindow = ChromaprintExtractor.parseFingerprint(fingerprint)
                if (parsedWindow.isEmpty()) {
                    pos += STEP_MS
                    continue
                }

                var matched = false
                var bestSim = 0f
                for ((parsedFp, originalFp) in parsedLibrary) {
                    if (parsedFp.isEmpty()) continue
                    val sim = ChromaprintExtractor.compareFingerprintArrays(parsedWindow, parsedFp).similarity
                    if (sim > bestSim) bestSim = sim
                    if (sim >= LAYER1_FAST_SCREEN_THRESHOLD) {
                        matched = true
                        break
                    }
                }

                if (matched) {
                    matchedRanges.add(pos to (pos + WINDOW_MS))
                    matchedWindows++
                    if (matchedWindows <= 20 || matchedWindows % 10 == 0) {
                        hitDetails.add("${pos/1000}秒(相似度:${"%.0f".format(bestSim*100)}%)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "第一层滑动窗口: 位置${pos}ms异常: ${e.message}")
            }
            pos += STEP_MS
        }

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
        // v3.1.29: 第1层只判定是否符合指纹，hasVoice由第2层VAD+YAMNet判定。
        // 匹配部分标记为"指纹水货"(hasVoice=false)，第2层直接认可为水分；
        // 不匹配部分标记为"待处理"(hasVoice=true)，第2层通过VAD+YAMNet判断干湿。
        // 这样第2层VAD失败时，待处理片段默认显示为干货，不会全部变成水分。
        val segments = mutableListOf<VoiceSegment>()
        var currentPos = 0L
        for (waterRange in mergedRanges) {
            if (waterRange.first > currentPos) {
                segments.add(VoiceSegment().apply {
                    this.start = currentPos
                    this.end = waterRange.first
                    this.hasVoice = true
                    this.label = "待处理"
                    this.isSimulated = false
                })
            }
            segments.add(VoiceSegment().apply {
                this.start = waterRange.first
                this.end = waterRange.second
                this.hasVoice = false
                this.label = "指纹水货"
                this.isSimulated = false
            })
            currentPos = waterRange.second
        }
        if (currentPos < durationMs) {
            segments.add(VoiceSegment().apply {
                this.start = currentPos
                this.end = durationMs
                this.hasVoice = true
                this.label = "待处理"
                this.isSimulated = false
            })
        }

        val fpMsg = "第一层滑动窗口: 共${totalWindows}个窗口，匹配${matchedWindows}个（${mergedRanges.size}个水货段），${segments.size}个片段 [${hitDetails.joinToString("; ")}]"
        Log.i(TAG, fpMsg)
        writeFingerprintLog(context, fpMsg)

        return segments
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
                tempPcmFile = PcmSegmentExtractor.extractSegmentPcm(appContext, episodeId, seg.start, seg.end)
                if (tempPcmFile == null || !tempPcmFile.exists() || tempPcmFile.length() <= 0) continue

                val fingerprint = ChromaprintExtractor.extractFingerprintFromFile(tempPcmFile)
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
                            Log.d(TAG, "观察池过滤: 片段${waterSeg.start/1000}s-${waterSeg.end/1000}s与正式库/观察池重复，但hash未匹配，跳过")
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
     * v3.1.29: 重新生成完整PCM并提取指定片段。
     * 当完整PCM文件不存在时，尝试通过音频解码重新生成。
     */
    private fun regenerateFullPcmAndExtractSegment(
        context: Context,
        episodeId: String,
        startMs: Long,
        endMs: Long,
        fullPcmFile: File,
        audioUrl: String?
    ): File? {
        try {
            // 尝试通过 AudioSegmentAnalyzer 的 analyzeEpisode 来触发PCM生成
            // analyzeEpisode 会在PCM不存在时自动解码生成
            val dummyCallback: ((Int, Long, Long) -> Unit)? = { _, _, _ -> }
            val result = AudioSegmentAnalyzer.analyzeEpisode(
                context, episodeId, endMs, audioUrl,
                progressCallback = dummyCallback, blocking = false
            )
            // 重新生成后，检查完整PCM是否存在
            if (fullPcmFile.exists() && fullPcmFile.length() > 0) {
                return PcmSegmentExtractor.extractSegmentFromFile(fullPcmFile, startMs, endMs)
            }
            Log.e(TAG, "regenerateFullPcmAndExtractSegment: 重新生成PCM后文件仍不存在 for episode=$episodeId")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "regenerateFullPcmAndExtractSegment: 重新生成PCM失败: ${e.message}")
            return null
        }
    }

    /**
     * v3.1.29: 验证三层分段结果是否正常。
     * - 全部水分或全部干货均为异常
     * - 分段数过少（两小时节目应>20段）为异常
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
            // 全部干货不一定是异常（如果指纹库为空且VAD分类为干货）
            // 但记录日志供调试
            Log.w(TAG, "validateThreeLayerResult: 全部${segments.size}个分段均为干货，确认是否正常")
        }

        // 两小时节目应有60~100个分段，小于20个为异常
        val durationMinutes = durationMs / 60000
        if (durationMinutes >= 60 && segments.size < 20) {
            return "分段数过少: ${segments.size}个分段（节目时长${durationMinutes}分钟，预期>20段）"
        }

        return null
    }
}
