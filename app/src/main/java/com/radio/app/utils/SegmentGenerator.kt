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
     */
    private fun mergeAdjacentSegments(segments: List<VoiceSegment>): MutableList<VoiceSegment> {
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
                // v3.1.12: 就AI听方案重构为音频加指纹方案
                // 1. 先检查是否已有音频分段结果（VAD+YAMNet双模型）
                // 2. 如不存在，调用双模型音频分段生成音频分段结果
                // 3. 对干货片段用水分指纹二次审核，匹配则改为指纹水货片段
                // 4. 合并相邻水货片段，标记为指纹水货片段
                val fpMsgScheme = "postSegmentKeyword: 就AI听 音频+指纹方案 for episode=$episodeId"
                Log.i(TAG, fpMsgScheme)
                writeFingerprintLog(context, fpMsgScheme)

                // 检查数据库是否已有音频分段结果
                val existingSegments = dbHelper.getVoiceSegments(episodeId)
                val realSegments = existingSegments.filter { !it.isSimulated }
                var audioSegments: List<VoiceSegment> = emptyList()
                var audioEngineName = "就AI听"

                if (realSegments.isEmpty()) {
                    // 没有音频分段结果，调用双模型音频分段
                    Log.i(TAG, "postSegmentKeyword: 无音频分段结果，调用VAD+YAMNet双模型分段 for episode=$episodeId")
                    val audioUrl = try {
                        RadioDatabaseHelper.getInstance(context).getEpisodeInfo(episodeId)?.audioUrl
                    } catch (_: Exception) { null }
                    val result = tryGenerateAudioSegments(context, episodeId, durationMs, audioUrl)
                    if (result != null && result.segments.isNotEmpty()) {
                        audioSegments = result.segments
                        audioEngineName = "${result.engineName}+指纹"
                        Log.i(TAG, "postSegmentKeyword: 音频分段生成${audioSegments.size}个片段 for episode=$episodeId")
                    } else {
                        Log.w(TAG, "postSegmentKeyword: 音频分段无结果，回退到关键词分段 for episode=$episodeId")
                    }
                } else {
                    audioSegments = realSegments
                    Log.i(TAG, "postSegmentKeyword: 使用已有音频分段结果(${audioSegments.size}个) for episode=$episodeId")
                }

                // 如果音频分段结果为空，回退到关键词分段
                val baseSegments = if (audioSegments.isNotEmpty()) audioSegments else {
                    generateKeywordSegments(context, episodeId, durationMs)
                }

                if (baseSegments.isEmpty()) {
                    segments = emptyList()
                    engineName = audioEngineName
                    processingTimeMs = System.currentTimeMillis() - segStartTime
                    audioDurationMs = durationMs
                } else {
                    engineName = audioEngineName
                    processingTimeMs = System.currentTimeMillis() - segStartTime
                    audioDurationMs = durationMs

                    // 对干货片段进行指纹二次审核
                    val waterFingerprints = try { dbHelper.getAllAudioFingerprints() } catch (_: Exception) { emptyList() }
                    var totalDrySegments = baseSegments.count { it.hasVoice }
                    var matchedCount = 0

                    Log.i(TAG, "就AI听方案指纹审核: 准备审核，水分指纹库${waterFingerprints.size}条，待审核干货${totalDrySegments}段，指纹引擎${if (ChromaprintExtractor.ensureLibraryLoaded(context)) "已就绪" else "未加载"}")
                    writeFingerprintLog(context, "就AI听方案指纹审核: 准备审核，水分指纹库${waterFingerprints.size}条，待审核干货${totalDrySegments}段，指纹引擎${if (ChromaprintExtractor.ensureLibraryLoaded(context)) "已就绪" else "未加载"}")
                    if (waterFingerprints.isNotEmpty() && ChromaprintExtractor.ensureLibraryLoaded(context)) {
                        val fingerprintChecked = applyAudioFingerprintSecondaryCheck(
                            context, episodeId, baseSegments, waterFingerprints
                        )
                        // 统计被指纹匹配的干货片段数
                        val matchedSegments = fingerprintChecked.filter { !it.hasVoice }
                            .filter { fSeg ->
                                val orig = baseSegments.find { it.start == fSeg.start && it.end == fSeg.end }
                                orig != null && orig.hasVoice
                            }
                        matchedCount = matchedSegments.size

                        // 合并相邻水货片段，标记为指纹水货片段
                        val merged = mergeAdjacentSegments(fingerprintChecked)
                        for (seg in merged) {
                            if (!seg.hasVoice) {
                                // 标记为指纹水货片段
                                seg.label = "指纹水货"
                            }
                        }

                        // 日志记录
                        if (matchedCount > 0) {
                            val matchedDetails = matchedSegments.joinToString("; ") { 
                                "${it.start/1000}秒-${it.end/1000}秒"
                            }
                            val mergedWaterCount = merged.count { !it.hasVoice }
                            val dryPct = if (totalDrySegments > 0) 
                                "%.1f%%".format(matchedCount.toFloat() / totalDrySegments * 100) else "0%"
                            val fpMsg1 = "就AI听方案指纹审核: 匹配${matchedCount}个干货片段 [$matchedDetails]"
                            Log.i(TAG, fpMsg1)
                            writeFingerprintLog(context, fpMsg1)
                            val fpMsg2 = "就AI听方案指纹审核: 合并后${mergedWaterCount}段指纹水货，原干货匹配率$dryPct"
                            Log.i(TAG, fpMsg2)
                            writeFingerprintLog(context, fpMsg2)
                        } else {
                            val fpMsg = "就AI听方案指纹审核: 未匹配到任何水分指纹，所有干货保持原分类"
                            Log.i(TAG, fpMsg)
                            writeFingerprintLog(context, fpMsg)
                        }
                        segments = merged
                    } else {
                        val fpMsg = "就AI听方案指纹审核: 跳过（水分指纹库为空或指纹引擎未就绪）"
                        Log.i(TAG, fpMsg)
                        writeFingerprintLog(context, fpMsg)
                        segments = baseSegments
                    }

                    val fpMsgDone = "就AI听方案完成: ${segments.size}个片段（原干货${totalDrySegments}段，指纹匹配${matchedCount}段）"
                    Log.i(TAG, fpMsgDone)
                    writeFingerprintLog(context, fpMsgDone)
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
    data class JiuAiTingResult(
        val segments: List<VoiceSegment>,
        val engineName: String,
        val processingTimeMs: Long,
        val matchedCount: Int,
        val totalDrySegments: Int
    )

    /**
     * v3.1.15: 就AI听方案 - 音频+指纹分段
     * 1. 调用双模型音频分段（VAD+YAMNet）生成音频分段结果
     * 2. 对分段结果中的干货片段用水分指纹二次审核
     * 3. 合并相邻水货片段，标记为指纹水货片段
     * 4. 日志记录匹配的指纹片段和合并的指纹水货片段
     */
    fun generateJiuAiTingSegments(
        context: Context,
        episodeId: String,
        durationMs: Long,
        audioUrl: String? = null,
        progressCallback: ((Int, Long, Long) -> Unit)? = null
    ): JiuAiTingResult? {
        val segStartTime = System.currentTimeMillis()
        val fpMsgStart = "generateJiuAiTingSegments: 就AI听音频+指纹方案 for episode=$episodeId"
        Log.i(TAG, fpMsgStart)
        writeFingerprintLog(context, fpMsgStart)

        // 1. 调用双模型音频分段
        val audioResult = tryGenerateAudioSegments(context, episodeId, durationMs, audioUrl, progressCallback)
        val baseSegments: List<VoiceSegment> = audioResult?.segments ?: emptyList()
        val engineName = if (audioResult != null) "${audioResult.engineName}+指纹" else "就AI听"

        if (baseSegments.isEmpty()) {
            val fpMsgEmpty = "generateJiuAiTingSegments: 音频分段无结果"
            Log.w(TAG, fpMsgEmpty)
            writeFingerprintLog(context, fpMsgEmpty)
            return null
        }
        val fpMsgSegCount = "就AI听音频分段: 生成${baseSegments.size}个片段"
        Log.i(TAG, fpMsgSegCount)
        writeFingerprintLog(context, fpMsgSegCount)

        val dbHelper = RadioDatabaseHelper.getInstance(context)
        val waterFingerprints = try { dbHelper.getAllAudioFingerprints() } catch (_: Exception) { emptyList() }
        val totalDrySegments = baseSegments.count { it.hasVoice }
        var matchedCount = 0

        val fpMsgPrepare = "就AI听方案指纹审核: 准备审核，水分指纹库${waterFingerprints.size}条，待审核干货${totalDrySegments}段，指纹引擎${if (ChromaprintExtractor.ensureLibraryLoaded(context)) "已就绪" else "未加载"}"
        Log.i(TAG, fpMsgPrepare)
        writeFingerprintLog(context, fpMsgPrepare)
        val finalSegments = if (waterFingerprints.isNotEmpty() && ChromaprintExtractor.ensureLibraryLoaded(context)) {
            // 2. 对干货片段进行指纹二次审核
            val fingerprintChecked = applyAudioFingerprintSecondaryCheck(context, episodeId, baseSegments, waterFingerprints)

            // 统计被指纹匹配的干货片段数
            matchedCount = fingerprintChecked.filter { !it.hasVoice }
                .count { fSeg ->
                    val orig = baseSegments.find { it.start == fSeg.start && it.end == fSeg.end }
                    orig != null && orig.hasVoice
                }

            // 3. 合并相邻水货片段，标记为指纹水货片段
            val merged = mergeAdjacentSegments(fingerprintChecked)
            for (seg in merged) {
                if (!seg.hasVoice) {
                    seg.label = "指纹水货"
                }
            }

            // 4. 日志记录
            if (matchedCount > 0) {
                val matchedDetails = fingerprintChecked.filter { !it.hasVoice }
                    .filter { fSeg ->
                        val orig = baseSegments.find { it.start == fSeg.start && it.end == fSeg.end }
                        orig != null && orig.hasVoice
                    }.joinToString("; ") { "${it.start/1000}秒-${it.end/1000}秒" }
                val mergedWaterCount = merged.count { !it.hasVoice }
                val dryPct = if (totalDrySegments > 0)
                    "%.1f%%".format(matchedCount.toFloat() / totalDrySegments * 100) else "0%"
                val fpMsg1 = "就AI听方案指纹审核: 匹配${matchedCount}个干货片段 [$matchedDetails]"
                Log.i(TAG, fpMsg1)
                writeFingerprintLog(context, fpMsg1)
                val fpMsg2 = "就AI听方案指纹审核: 合并后${mergedWaterCount}段指纹水货，原干货匹配率$dryPct"
                Log.i(TAG, fpMsg2)
                writeFingerprintLog(context, fpMsg2)
            } else {
                val fpMsg = "就AI听方案指纹审核: 未匹配到任何水分指纹，所有干货保持原分类"
                Log.i(TAG, fpMsg)
                writeFingerprintLog(context, fpMsg)
            }
            merged
        } else {
            val fpMsg = "就AI听方案指纹审核: 跳过（水分指纹库为空或指纹引擎未就绪）"
            Log.i(TAG, fpMsg)
            writeFingerprintLog(context, fpMsg)
            baseSegments
        }

        val fpMsgDone = "就AI听方案完成: ${finalSegments.size}个片段（原干货${totalDrySegments}段，指纹匹配${matchedCount}段）"
        Log.i(TAG, fpMsgDone)
        writeFingerprintLog(context, fpMsgDone)

        return JiuAiTingResult(
            segments = finalSegments,
            engineName = engineName,
            processingTimeMs = System.currentTimeMillis() - segStartTime,
            matchedCount = matchedCount,
            totalDrySegments = totalDrySegments
        )
    }
}
