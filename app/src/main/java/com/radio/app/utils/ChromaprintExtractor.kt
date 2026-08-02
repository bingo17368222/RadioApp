package com.radio.app.utils

import android.content.Context
import android.util.Log
import com.radio.app.database.AudioFingerprint
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * v3.0.2: Chromaprint 音频指纹提取器。
 * 输入 16kHz 单声道 16bit 小端 PCM 数据，输出逗号分隔的 32 位整数指纹字符串。
 */
object ChromaprintExtractor {
    private const val TAG = "ChromaprintExtractor"
    private const val SAMPLE_RATE = 16000
    private const val CHANNELS = 1

    init {
        try {
            System.loadLibrary("chromaprint_jni")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load chromaprint_jni: ${e.message}")
        }
    }

    /**
     * 从 16kHz 单声道 16bit PCM 字节数组中提取指纹。
     * @param pcmData PCM 字节数组（小端）
     * @return 逗号分隔的指纹整数字符串，失败返回 null
     */
    fun extractFingerprint(pcmData: ByteArray): String? {
        if (pcmData.isEmpty()) return null
        return try {
            nativeExtractFingerprint(pcmData, SAMPLE_RATE, CHANNELS)
        } catch (e: Exception) {
            Log.e(TAG, "extractFingerprint failed: ${e.message}")
            null
        }
    }

    /**
     * 从 PCM 文件中提取指纹。
     */
    fun extractFingerprintFromFile(pcmFile: File): String? {
        if (!pcmFile.exists() || pcmFile.length() <= 0) return null
        return try {
            nativeExtractFingerprintFromFile(pcmFile.absolutePath, SAMPLE_RATE, CHANNELS)
        } catch (e: Exception) {
            Log.e(TAG, "extractFingerprintFromFile failed: ${e.message}")
            null
        }
    }

    /**
     * 确保 Chromaprint 库已加载。
     */
    fun ensureLibraryLoaded(context: Context): Boolean {
        return ChromaprintLoader.ensureLoaded(context)
    }

    /**
     * v3.0.6: 设置原生库绝对路径，供 JNI dlopen 回退使用。
     */
    fun setNativeLibraryPath(path: String) {
        try {
            nativeSetLibraryPath(path)
        } catch (e: Exception) {
            Log.e(TAG, "setNativeLibraryPath failed: ${e.message}")
        }
    }

    /**
     * 检查库是否已下载。
     */
    fun isLibraryAvailable(context: Context): Boolean {
        return ChromaprintLoader.isLibraryDownloaded(context)
    }

    /**
     * v3.0.9: 将指纹字符串解析为整数列表。
     * 优先尝试逗号分隔的 raw fingerprint（支持无符号 32 位整数）；失败时尝试将旧版 base64 编码解码为 raw fingerprint。
     */
    fun parseFingerprint(fingerprint: String?): List<Int> {
        if (fingerprint.isNullOrBlank()) return emptyList()
        val list = parseRawFingerprintString(fingerprint)
        if (list.isNotEmpty()) return list
        // 兼容旧版 base64 编码指纹
        return decodeFingerprintToRaw(fingerprint)
    }

    /**
     * v3.0.9: 解析逗号分隔的 raw fingerprint 字符串，支持 uint32（大于 Int.MAX_VALUE 的值按位转为 Int）。
     */
    private fun parseRawFingerprintString(raw: String): List<Int> {
        return raw.split(",").mapNotNull { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val longValue = trimmed.toLongOrNull() ?: return@mapNotNull null
            // Chromaprint raw fingerprint 是 32 位无符号整数，以 Int 的位模式保存
            (longValue and 0xFFFFFFFFL).toInt()
        }
    }

    /**
     * v3.0.9: 将 base64 编码的 fingerprint 解码为逗号分隔的 raw fingerprint 整数列表。
     */
    fun decodeFingerprintToRaw(encoded: String?): List<Int> {
        if (encoded.isNullOrBlank()) return emptyList()
        return try {
            val raw = nativeDecodeFingerprint(encoded)
            if (raw.isNullOrBlank()) emptyList() else parseRawFingerprintString(raw)
        } catch (e: Exception) {
            Log.e(TAG, "decodeFingerprintToRaw failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * v3.1.3: 计算两段指纹的相似度（0.0 - 1.0）。
     * 使用全滑动窗口位误差率（bit error rate）比较：
     * 将较短指纹在较长指纹上完全滑动，找到最佳对齐位置。
     * 仅在长度差异显著（>20%）时施加惩罚，避免同时长音频因微小指纹长度差异被误判。
     */
    fun compareFingerprints(fp1: String, fp2: String): Float {
        return compareFingerprintsDetailed(fp1, fp2).similarity
    }

    /**
     * v3.1.3: 详细比较结果，包含相似度、最佳偏移、指纹长度等诊断信息。
     */
    data class CompareResult(
        val similarity: Float,
        val bestOffset: Int,
        val minErrors: Int,
        val totalBits: Int,
        val len1: Int,
        val len2: Int,
        val rawSimilarity: Float,
        val lengthPenalty: Float
    )

    /**
     * v3.1.3: 全滑动窗口指纹比较，返回详细诊断信息。
     * 核心改进：
     * 1. 全滑动窗口：短指纹在长指纹上完整滑动，找到最佳对齐（而非仅 10% 窗口）
     * 2. 长度惩罚仅在差异 >20% 时生效，同时长音频几乎不受影响
     * 3. 返回最佳偏移位置，便于诊断跨节目匹配问题
     */
    fun compareFingerprintsDetailed(fp1: String, fp2: String): CompareResult {
        val a = parseFingerprint(fp1)
        val b = parseFingerprint(fp2)
        if (a.isEmpty() || b.isEmpty()) {
            return CompareResult(0f, 0, 0, 0, a.size, b.size, 0f, 0f)
        }
        if (a.size == b.size && a == b) {
            return CompareResult(1f, 0, 0, a.size * 32, a.size, b.size, 1f, 1f)
        }

        val minLen = minOf(a.size, b.size)
        val maxLen = maxOf(a.size, b.size)
        if (minLen <= 0) {
            return CompareResult(0f, 0, 0, 0, a.size, b.size, 0f, 0f)
        }

        val short = if (a.size <= b.size) a else b
        val long = if (a.size <= b.size) b else a

        // v3.1.3: 全滑动窗口 — 短指纹在长指纹上完整滑动
        val maxOffset = long.size - minLen
        var minErrors = Int.MAX_VALUE
        var bestOffset = 0
        val totalBits = minLen * 32

        for (offset in 0..maxOffset) {
            var errors = 0
            for (i in 0 until minLen) {
                errors += Integer.bitCount(short[i] xor long[offset + i])
            }
            if (errors < minErrors) {
                minErrors = errors
                bestOffset = offset
            }
        }

        val rawSimilarity = 1f - (minErrors.toFloat() / totalBits.toFloat())

        // v3.1.3: 长度惩罚 — 仅在长度差异 >20% 时逐步施加
        val lengthRatio = minLen.toFloat() / maxLen.toFloat()
        val lengthPenalty = if (lengthRatio >= 0.8f) {
            1f  // 长度差异 ≤20%，不惩罚（同时长音频微小的指纹长度差异不扣分）
        } else {
            // 差异 >20% 时，线性惩罚最多 30%
            1f - (1f - lengthRatio) * 0.3f
        }

        val similarity = (rawSimilarity * lengthPenalty).coerceIn(0f, 1f)
        return CompareResult(similarity, bestOffset, minErrors, totalBits, a.size, b.size, rawSimilarity, lengthPenalty)
    }

    /**
     * v3.1.3: 直接比较两个已解析的指纹整数列表（避免重复解析）。
     */
    fun compareFingerprintArrays(a: List<Int>, b: List<Int>): CompareResult {
        if (a.isEmpty() || b.isEmpty()) {
            return CompareResult(0f, 0, 0, 0, a.size, b.size, 0f, 0f)
        }
        if (a.size == b.size && a == b) {
            return CompareResult(1f, 0, 0, a.size * 32, a.size, b.size, 1f, 1f)
        }

        val minLen = minOf(a.size, b.size)
        val maxLen = maxOf(a.size, b.size)
        if (minLen <= 0) {
            return CompareResult(0f, 0, 0, 0, a.size, b.size, 0f, 0f)
        }

        val short = if (a.size <= b.size) a else b
        val long = if (a.size <= b.size) b else a

        val maxOffset = long.size - minLen
        var minErrors = Int.MAX_VALUE
        var bestOffset = 0
        val totalBits = minLen * 32

        for (offset in 0..maxOffset) {
            var errors = 0
            for (i in 0 until minLen) {
                errors += Integer.bitCount(short[i] xor long[offset + i])
            }
            if (errors < minErrors) {
                minErrors = errors
                bestOffset = offset
            }
        }

        val rawSimilarity = 1f - (minErrors.toFloat() / totalBits.toFloat())
        val lengthRatio = minLen.toFloat() / maxLen.toFloat()
        val lengthPenalty = if (lengthRatio >= 0.8f) 1f else 1f - (1f - lengthRatio) * 0.3f
        val similarity = (rawSimilarity * lengthPenalty).coerceIn(0f, 1f)
        return CompareResult(similarity, bestOffset, minErrors, totalBits, a.size, b.size, rawSimilarity, lengthPenalty)
    }

    /**
     * 判断两段指纹是否匹配。
     * v3.1.3: 默认阈值从 0.75 降至 0.70，适应跨节目匹配的轻微差异。
     * @param threshold 相似度阈值，默认 0.70
     */
    fun isMatch(fp1: String, fp2: String, threshold: Float = 0.70f): Boolean {
        return compareFingerprints(fp1, fp2) >= threshold
    }

    // ==================== v3.1.4: 指纹分组机制 ====================

    // v3.1.7: 滑动搜索相关常量
    private const val PCM_BYTES_PER_MS = 32L  // 16000Hz * 2bytes * 1ch / 1000ms

    /**
     * v3.1.7: 指纹滑动搜索完整PCM结果。
     */
    data class PcmSearchResult(
        val similarity: Float,
        val bestMatchStartMs: Long,
        val bestMatchEndMs: Long,
        val totalPositionsScanned: Int,
        val positionsAboveThreshold: Int,
        val pcmDurationMs: Long,
        val searchDurationMs: Long
    )

    /**
     * v3.1.7: 在完整PCM文件中滑动搜索指定指纹的最佳匹配位置。
     * 使用完整PCM指纹提取+指纹数组滑动比较策略：
     * 1. 提取完整PCM文件的一整条指纹
     * 2. 在指纹数组上滑动比较目标指纹（粗搜步长10帧 → 中搜步长1帧 → 精搜PCM验证）
     * 3. 将指纹偏移转换为PCM时间位置
     * 4. 在最佳位置附近做PCM片段验证（文件方式提取指纹，确保一致性）
     *
     * v3.1.7-fix: 修复两个关键问题：
     * - 原始位置验证改用文件方式提取指纹（extractFingerprintFromFile），避免ByteArray版本差异
     * - 增加PCM验证范围至±30秒，步长500ms，提高命中率
     * - 有效阈值提升至0.85，大幅降低长PCM的假阳性
     * - 支持原始位置附近多偏移验证（±100ms/200ms/500ms/1000ms）
     *
     * @param fingerprint 要搜索的短指纹字符串
     * @param pcmFile 完整PCM文件
     * @param searchDurationMs 搜索窗口大小（与原指纹时长一致）
     * @param threshold 匹配阈值，默认0.70（滑动搜索内部使用0.85）
     * @param originalStartMs 已知的原始起始位置（可选），用于直接验证
     * @return 最佳匹配结果，如PCM文件无效返回null
     */
    fun searchFingerprintInPcm(
        fingerprint: String,
        pcmFile: File,
        searchDurationMs: Long,
        threshold: Float = 0.70f,
        originalStartMs: Long? = null,
        progressCallback: ((Int, String) -> Unit)? = null
    ): PcmSearchResult? {
        if (!pcmFile.exists() || pcmFile.length() <= 0) return null
        val pcmDurationMs = pcmFile.length() / PCM_BYTES_PER_MS
        if (pcmDurationMs <= searchDurationMs) return null

        val searchStartTime = System.currentTimeMillis()
        val parsedTarget = parseFingerprint(fingerprint)
        if (parsedTarget.isEmpty()) return null

        // v3.1.7-fix: 滑动搜索使用更高的有效阈值，避免长PCM假阳性
        val effectiveThreshold = 0.85f

        // ========== 阶段1: 原始位置验证 ==========
        // 使用文件方式提取指纹，确保与原始提取方式一致
        if (originalStartMs != null && originalStartMs >= 0 && originalStartMs + searchDurationMs <= pcmDurationMs) {
            progressCallback?.invoke(1, "验证原始位置...")
            // 尝试多偏移量：0ms, ±100ms, ±200ms, ±500ms, ±1000ms
            val offsetsToTry = listOf(0L, 100L, -100L, 200L, -200L, 500L, -500L, 1000L, -1000L)
            var bestOrigSim = 0f
            var bestOrigPos = originalStartMs!!
            for (offsetMs in offsetsToTry) {
                val pos = (originalStartMs + offsetMs).coerceAtLeast(0L)
                if (pos + searchDurationMs > pcmDurationMs) continue
                val segmentFile = PcmSegmentExtractor.extractSegmentFromFile(pcmFile, pos, pos + searchDurationMs)
                if (segmentFile != null && segmentFile.exists() && segmentFile.length() > 0) {
                    val origFp = extractFingerprintFromFile(segmentFile)
                    segmentFile.delete()
                    if (origFp != null) {
                        val sim = compareFingerprints(fingerprint, origFp)
                        if (sim > bestOrigSim) {
                            bestOrigSim = sim
                            bestOrigPos = pos
                        }
                        if (sim >= effectiveThreshold) {
                            val elapsed = System.currentTimeMillis() - searchStartTime
                            return PcmSearchResult(
                                similarity = sim,
                                bestMatchStartMs = pos,
                                bestMatchEndMs = pos + searchDurationMs,
                                totalPositionsScanned = 1,
                                positionsAboveThreshold = 1,
                                pcmDurationMs = pcmDurationMs,
                                searchDurationMs = elapsed
                            )
                        }
                    }
                }
            }
            // 原始位置有较高相似度但未达阈值，记录为备选
            val hasOriginalCandidate = bestOrigSim >= 0.70f
            if (hasOriginalCandidate) {
                // 仍在原始位置附近找到了较高相似度，直接返回
                val elapsed = System.currentTimeMillis() - searchStartTime
                return PcmSearchResult(
                    similarity = bestOrigSim,
                    bestMatchStartMs = bestOrigPos,
                    bestMatchEndMs = bestOrigPos + searchDurationMs,
                    totalPositionsScanned = 1,
                    positionsAboveThreshold = 1,
                    pcmDurationMs = pcmDurationMs,
                    searchDurationMs = elapsed
                )
            }
        }

        // ========== 阶段2: 完整PCM指纹提取 ==========
        progressCallback?.invoke(5, "正在提取完整PCM指纹...")
        val fullFingerprint = extractFingerprintFromFile(pcmFile)
        if (fullFingerprint == null) return null
        val parsedFull = parseFingerprint(fullFingerprint)
        if (parsedFull.size <= parsedTarget.size) return null

        val maxOffset = parsedFull.size - parsedTarget.size
        val msPerFrame = pcmDurationMs.toFloat() / parsedFull.size.toFloat()
        val totalBits = parsedTarget.size * 32

        var totalScanned = 0
        var aboveThreshold = 0

        // ========== 阶段3: 粗搜 — 大步长快速扫描 ==========
        progressCallback?.invoke(10, "粗搜中（步长10帧）...")
        val coarseStep = 10
        var bestArraySim = 0f
        var bestArrayOffset = 0
        val candidates = mutableListOf<Pair<Int, Float>>()

        var coarsePos = 0
        while (coarsePos <= maxOffset) {
            totalScanned++
            var errors = 0
            for (i in 0 until parsedTarget.size) {
                errors += Integer.bitCount(parsedTarget[i] xor parsedFull[coarsePos + i])
            }
            val sim = 1f - (errors.toFloat() / totalBits.toFloat())

            if (sim > bestArraySim) {
                bestArraySim = sim
                bestArrayOffset = coarsePos
            }
            candidates.add(coarsePos to sim)
            if (sim >= effectiveThreshold) aboveThreshold++

            coarsePos += coarseStep
        }

        // 按相似度排序取前10个候选（增加候选数量）
        candidates.sortByDescending { it.second }
        val topCandidates = candidates.take(10).map { it.first }

        // ========== 阶段4: 中搜 — 每个候选附近精搜 ==========
        progressCallback?.invoke(30, "中搜中（步长1帧）...")
        // 合并候选邻域，避免重复检查
        val searchRegions = mutableSetOf<Int>()
        for (candidateOffset in topCandidates) {
            val regionStart = (candidateOffset - 100).coerceAtLeast(0)
            val regionEnd = (candidateOffset + 100).coerceAtMost(maxOffset)
            for (off in regionStart..regionEnd) {
                searchRegions.add(off)
            }
        }

        for (offset in searchRegions) {
            if (offset % coarseStep == 0) continue  // 跳过粗搜已检查的
            totalScanned++
            var errors = 0
            for (i in 0 until parsedTarget.size) {
                errors += Integer.bitCount(parsedTarget[i] xor parsedFull[offset + i])
            }
            val sim = 1f - (errors.toFloat() / totalBits.toFloat())
            if (sim > bestArraySim) {
                bestArraySim = sim
                bestArrayOffset = offset
            }
            if (sim >= effectiveThreshold) aboveThreshold++
        }

        // ========== 阶段5: 指纹偏移→时间位置 ==========
        var bestMatchStartMs = (bestArrayOffset * msPerFrame).toLong()
        bestMatchStartMs = bestMatchStartMs.coerceAtMost(pcmDurationMs - searchDurationMs)

        // ========== 阶段6: PCM片段验证（文件方式） ==========
        progressCallback?.invoke(60, "PCM验证中（±30秒）...")
        val verifyRange = 30000L  // ±30秒验证范围
        val verifyStep = 500L     // 500ms步长
        val verifyStart = (bestMatchStartMs - verifyRange).coerceAtLeast(0L)
        val verifyEnd = (bestMatchStartMs + verifyRange).coerceAtMost(pcmDurationMs - searchDurationMs)

        var verifiedBestSim = bestArraySim
        var verifiedBestPos = bestMatchStartMs
        var verifyPos = verifyStart
        var verifyCount = 0

        while (verifyPos <= verifyEnd) {
            verifyCount++
            // 每10个位置输出一次进度
            if (verifyCount % 20 == 0) {
                val progress = (60 + (verifyPos - verifyStart) * 30 / (verifyEnd - verifyStart + 1)).toInt().coerceIn(60, 90)
                progressCallback?.invoke(progress, "PCM验证中... (${verifyCount}处)")
            }
            val segmentFile = PcmSegmentExtractor.extractSegmentFromFile(pcmFile, verifyPos, verifyPos + searchDurationMs)
            if (segmentFile != null && segmentFile.exists() && segmentFile.length() > 0) {
                val fp = extractFingerprintFromFile(segmentFile)
                segmentFile.delete()
                if (fp != null) {
                    val sim = compareFingerprints(fingerprint, fp)
                    if (sim > verifiedBestSim) {
                        verifiedBestSim = sim
                        verifiedBestPos = verifyPos
                        if (sim >= effectiveThreshold) {
                            // 已经找到足够好的匹配，提前结束验证
                            break
                        }
                    }
                }
            }
            verifyPos += verifyStep
        }

        // ========== 阶段7: 最终验证（如果PCM验证结果优于指纹数组结果） ==========
        // 在最佳验证位置附近再做一次精细验证（步长100ms）
        if (verifiedBestSim > bestArraySim) {
            progressCallback?.invoke(92, "精细验证中...")
            val fineVerifyStart = (verifiedBestPos - 2000L).coerceAtLeast(0L)
            val fineVerifyEnd = (verifiedBestPos + 2000L).coerceAtMost(pcmDurationMs - searchDurationMs)
            var finePos = fineVerifyStart
            while (finePos <= fineVerifyEnd) {
                val segmentFile = PcmSegmentExtractor.extractSegmentFromFile(pcmFile, finePos, finePos + searchDurationMs)
                if (segmentFile != null && segmentFile.exists() && segmentFile.length() > 0) {
                    val fp = extractFingerprintFromFile(segmentFile)
                    segmentFile.delete()
                    if (fp != null) {
                        val sim = compareFingerprints(fingerprint, fp)
                        if (sim > verifiedBestSim) {
                            verifiedBestSim = sim
                            verifiedBestPos = finePos
                        }
                    }
                }
                finePos += 200L  // 200ms步长
            }
        }

        val elapsed = System.currentTimeMillis() - searchStartTime
        return PcmSearchResult(
            similarity = verifiedBestSim,
            bestMatchStartMs = verifiedBestPos,
            bestMatchEndMs = verifiedBestPos + searchDurationMs,
            totalPositionsScanned = totalScanned + verifyCount,
            positionsAboveThreshold = aboveThreshold,
            pcmDurationMs = pcmDurationMs,
            searchDurationMs = elapsed
        )
    }

    /**
     * v3.1.7: 搜索指纹是否在某个完整PCM中，使用分组优化。
     * 如果搜索指纹与某个已保存指纹在同一分组（相似度≥95%），
     * 则使用该分组内所有成员的PCM位置进行验证。
     * 这是生产代码中的核心匹配函数。
     * v3.1.7-fix: 改用文件方式提取指纹，确保一致性。
     */
    fun searchFingerprintInPcmWithGroup(
        fingerprint: String,
        pcmFile: File,
        searchDurationMs: Long,
        groupFingerprints: List<AudioFingerprint>? = null,
        threshold: Float = 0.70f,
        originalStartMs: Long? = null,
        progressCallback: ((Int, String) -> Unit)? = null
    ): PcmSearchResult? {
        // 如果有同组指纹，先用组内代表指纹的PCM位置快速验证
        if (groupFingerprints != null && groupFingerprints.isNotEmpty()) {
            for (gf in groupFingerprints) {
                val gfDuration = gf.endMs - gf.startMs
                if (gfDuration <= 0) continue
                val pos = gf.startMs.coerceAtMost(pcmFile.length() / PCM_BYTES_PER_MS - gfDuration)
                val segmentFile = PcmSegmentExtractor.extractSegmentFromFile(pcmFile, pos, gf.startMs + gfDuration)
                if (segmentFile != null && segmentFile.exists() && segmentFile.length() > 0) {
                    val fp = extractFingerprintFromFile(segmentFile)
                    segmentFile.delete()
                    if (fp != null) {
                        val sim = compareFingerprints(fingerprint, fp)
                        if (sim >= threshold) {
                            return PcmSearchResult(
                                similarity = sim,
                                bestMatchStartMs = gf.startMs,
                                bestMatchEndMs = gf.startMs + gfDuration,
                                totalPositionsScanned = 1,
                                positionsAboveThreshold = 1,
                                pcmDurationMs = pcmFile.length() / PCM_BYTES_PER_MS,
                                searchDurationMs = 0
                            )
                        }
                    }
                }
            }
        }

        // 组内位置验证失败，执行完整滑动搜索（传递原始位置）
        return searchFingerprintInPcm(fingerprint, pcmFile, searchDurationMs, threshold, originalStartMs, progressCallback)
    }

    /**
     * v3.1.4: 指纹分组阈值。相似度 ≥ 95% 的指纹归为一组，组内共享匹配结果。
     */
    const val FINGERPRINT_GROUP_THRESHOLD = 0.95f

    /**
     * v3.1.4: 指纹分组信息。
     * @param groupId 组ID
     * @param memberIndices 成员在原列表中的索引
     * @param representativeIndex 代表指纹索引（选最长的指纹，稳定性好）
     */
    data class FingerprintGroup(
        val groupId: Int,
        val memberIndices: List<Int>,
        val representativeIndex: Int
    )

    /**
     * v3.1.4: 构建指纹分组。
     * 将已解析的指纹列表按相似度 ≥95% 分组。
     * 每组选最长的指纹作为代表，用于后续快速匹配。
     * 使用并查集（Union-Find）算法，O(n²) 建图。
     */
    fun buildFingerprintGroups(parsedFingerprints: List<List<Int>>): List<FingerprintGroup> {
        if (parsedFingerprints.size <= 1) {
            return parsedFingerprints.indices.map { i ->
                FingerprintGroup(groupId = i, memberIndices = listOf(i), representativeIndex = i)
            }
        }

        val n = parsedFingerprints.size
        val parent = IntArray(n) { it }

        fun find(x: Int): Int {
            var p = x
            while (parent[p] != p) { parent[p] = parent[parent[p]]; p = parent[p] }
            return p
        }
        fun union(x: Int, y: Int) { parent[find(x)] = find(y) }

        // 逐对比较，相似度 ≥95% 的合并
        for (i in 0 until n) {
            val fi = parsedFingerprints[i]
            if (fi.isEmpty()) continue
            for (j in (i + 1) until n) {
                val fj = parsedFingerprints[j]
                if (fj.isEmpty()) continue
                // 快速剪枝：长度差异超过 20% 的肯定不相似
                val lenRatio = minOf(fi.size, fj.size).toFloat() / maxOf(fi.size, fj.size).toFloat()
                if (lenRatio < 0.8f) continue
                val result = compareFingerprintArrays(fi, fj)
                if (result.similarity >= FINGERPRINT_GROUP_THRESHOLD) {
                    union(i, j)
                }
            }
        }

        // 按根节点分组
        val groups = mutableMapOf<Int, MutableList<Int>>()
        for (i in 0 until n) {
            val root = find(i)
            groups.getOrPut(root) { mutableListOf() }.add(i)
        }

        return groups.values.mapIndexed { gid, indices ->
            // 选指纹最长的作为代表
            val repIdx = indices.maxByOrNull { parsedFingerprints[it].size } ?: indices.first()
            FingerprintGroup(
                groupId = gid,
                memberIndices = indices.sorted(),
                representativeIndex = repIdx
            )
        }
    }

    /**
     * v3.1.4: 带时间伸缩容错的指纹比较。
     * 在标准滑动窗口基础上，额外尝试对长指纹进行 ±5% 的伸缩，
     * 以应对采样率微小差异导致的指纹长度变化。
     * 返回最佳匹配结果。
     */
    fun compareFingerprintsWithStretch(fp1: String, fp2: String, stretchTolerance: Float = 0.05f): CompareResult {
        val a = parseFingerprint(fp1)
        val b = parseFingerprint(fp2)
        return compareFingerprintArraysWithStretch(a, b, stretchTolerance)
    }

    /**
     * v3.1.4: 对已解析的指纹数组做带伸缩容错的比较。
     */
    fun compareFingerprintArraysWithStretch(a: List<Int>, b: List<Int>, stretchTolerance: Float = 0.05f): CompareResult {
        if (a.isEmpty() || b.isEmpty()) {
            return CompareResult(0f, 0, 0, 0, a.size, b.size, 0f, 0f)
        }
        if (a.size == b.size && a == b) {
            return CompareResult(1f, 0, 0, a.size * 32, a.size, b.size, 1f, 1f)
        }

        // 标准比较结果
        val standard = compareFingerprintArrays(a, b)
        if (standard.similarity >= 0.99f) return standard  // 已经很好，不需要伸缩

        var best = standard
        val short = if (a.size <= b.size) a else b
        val long = if (a.size <= b.size) b else a
        val shortLen = short.size
        val longLen = long.size

        if (longLen <= shortLen || longLen <= 2) return best

        // 尝试缩小长指纹（模拟时间压缩）
        val shrinkInterval = (1.0 / stretchTolerance).toInt().coerceIn(15, 30) // ~20
        val shrunk = long.filterIndexed { index, _ -> (index + 1) % shrinkInterval != 0 }
        if (shrunk.size >= shortLen) {
            val sr = if (a.size <= b.size) compareFingerprintArrays(short, shrunk)
                     else compareFingerprintArrays(shrunk, short)
            if (sr.similarity > best.similarity) best = sr
        }

        // 尝试放大长指纹（模拟时间拉伸）
        val grown = long.flatMapIndexed { index, value ->
            if (index > 0 && index % shrinkInterval == 0) listOf(value, value) else listOf(value)
        }
        if (grown.size >= shortLen) {
            val gr = if (a.size <= b.size) compareFingerprintArrays(short, grown)
                     else compareFingerprintArrays(grown, short)
            if (gr.similarity > best.similarity) best = gr
        }

        return best
    }

    @JvmStatic
    private external fun nativeExtractFingerprint(pcmData: ByteArray, sampleRate: Int, channels: Int): String?

    @JvmStatic
    private external fun nativeExtractFingerprintFromFile(filePath: String, sampleRate: Int, channels: Int): String?

    @JvmStatic
    private external fun nativeSetLibraryPath(libPath: String)

    @JvmStatic
    private external fun nativeDecodeFingerprint(encodedFp: String): String?
}
