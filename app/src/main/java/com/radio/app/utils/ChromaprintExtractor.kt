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

    @Volatile
    private var jniLoaded = false

    // v3.1.64: 恢复v3.1.41的init块方案，在类初始化时加载libchromaprint_jni.so。
    // 这样 extractFingerprintFromFile 等native方法无需前置 ensureLibraryLoaded 即可工作。
    // 移除后（v3.1.57）导致所有指纹测试都提示"指纹库未加载"。
    init {
        try {
            System.loadLibrary("chromaprint_jni")
            jniLoaded = true
            Log.i(TAG, "chromaprint_jni loaded in init block")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "init: Failed to load chromaprint_jni: ${e.message}")
        }
    }

    /**
     * v3.1.56: 尝试加载 libchromaprint_jni.so（CMake 构建的 JNI 桥接库）。
     * 该库通过 dlopen 加载外部 libchromaprint.so 实现指纹提取。
     */
    private fun loadJniLibrary(): Boolean {
        if (jniLoaded) return true
        return try {
            System.loadLibrary("chromaprint_jni")
            jniLoaded = true
            Log.i(TAG, "chromaprint_jni loaded successfully")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load chromaprint_jni: ${e.message}")
            false
        }
    }

    /**
     * 从 16kHz 单声道 16bit PCM 字节数组中提取指纹。
     * @param pcmData PCM 字节数组（小端）
     * @return 逗号分隔的指纹整数字符串，失败返回 null
     */
    fun extractFingerprint(pcmData: ByteArray): String? {
        if (pcmData.isEmpty()) return null
        if (!jniLoaded) {
            Log.e(TAG, "extractFingerprint: chromaprint_jni not loaded")
            return null
        }
        return try {
            nativeExtractFingerprint(pcmData, SAMPLE_RATE, CHANNELS)
        } catch (e: UnsatisfiedLinkError) {
            // v3.1.58: 捕获UnsatisfiedLinkError，重置jniLoaded标志位，避免进程重启后标志位失效导致崩溃
            jniLoaded = false
            Log.e(TAG, "extractFingerprint: JNI library unavailable, resetting jniLoaded: ${e.message}")
            null
        } catch (e: Throwable) {
            Log.e(TAG, "extractFingerprint failed: ${e.message}")
            null
        }
    }

    /**
     * v3.1.64: 从 PCM 文件中提取指纹。
     * 恢复v3.1.41方案：init块已加载JNI，此处不再调用 ChromaprintLoader.ensureLoaded，
     * 因为调用者应通过 ensureLibraryLoaded(context) 先确保 libchromaprint.so 已加载。
     * 如果 JNI 库未加载（init块失败），自动尝试加载。
     */
    fun extractFingerprintFromFile(pcmFile: File): String? {
        if (!pcmFile.exists() || pcmFile.length() <= 0) return null
        if (!jniLoaded && !loadJniLibrary()) {
            Log.e(TAG, "extractFingerprintFromFile: chromaprint_jni not loaded")
            return null
        }
        return try {
            nativeExtractFingerprintFromFile(pcmFile.absolutePath, SAMPLE_RATE, CHANNELS)
        } catch (e: UnsatisfiedLinkError) {
            // v3.1.58: 捕获UnsatisfiedLinkError，重置jniLoaded标志位，避免进程重启后标志位失效导致崩溃
            jniLoaded = false
            Log.e(TAG, "extractFingerprintFromFile: JNI library unavailable, resetting jniLoaded: ${e.message}")
            null
        } catch (e: Throwable) {
            Log.e(TAG, "extractFingerprintFromFile failed: ${e.message}")
            null
        }
    }

    /**
     * v3.1.56: 确保 Chromaprint 库已加载（包括 libchromaprint.so 和 libchromaprint_jni.so）。
     * 先通过 ChromaprintLoader 确保 libchromaprint.so 可用（供 dlopen 使用），
     * 再加载 libchromaprint_jni.so（JNI 桥接）。
     */
    fun ensureLibraryLoaded(context: Context): Boolean {
        // 1. 确保 libchromaprint.so 已就绪（供 dlopen 使用）
        val chromaprintReady = ChromaprintLoader.ensureLoaded(context)
        if (!chromaprintReady) {
            Log.e(TAG, "ensureLibraryLoaded: libchromaprint.so not available")
            return false
        }
        // 2. 加载 libchromaprint_jni.so（JNI 桥接）
        return loadJniLibrary()
    }

    /**
     * v3.0.6: 设置原生库绝对路径，供 JNI dlopen 回退使用。
     */
    fun setNativeLibraryPath(path: String) {
        if (!jniLoaded) {
            Log.e(TAG, "setNativeLibraryPath: chromaprint_jni not loaded")
            return
        }
        try {
            nativeSetLibraryPath(path)
        } catch (e: Throwable) {
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
        if (!jniLoaded) {
            Log.e(TAG, "decodeFingerprintToRaw: chromaprint_jni not loaded")
            return emptyList()
        }
        return try {
            val raw = nativeDecodeFingerprint(encoded)
            if (raw.isNullOrBlank()) emptyList() else parseRawFingerprintString(raw)
        } catch (e: Throwable) {
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
        return try {
            compareFingerprintsDetailed(fp1, fp2).similarity
        } catch (e: UnsatisfiedLinkError) {
            // v3.1.58: 捕获UnsatisfiedLinkError保护
            Log.e(TAG, "compareFingerprints: UnsatisfiedLinkError: ${e.message}")
            0f
        }
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
        val lengthPenalty = if (lengthRatio >= 0.8f) {
            1f
        } else {
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
        return try {
            compareFingerprints(fp1, fp2) >= threshold
        } catch (e: UnsatisfiedLinkError) {
            // v3.1.58: 捕获UnsatisfiedLinkError保护
            Log.e(TAG, "isMatch: UnsatisfiedLinkError: ${e.message}")
            false
        }
    }

    // ==================== v3.1.4: 指纹分组机制 ====================

    private const val PCM_BYTES_PER_MS = 32L

    data class PcmSearchResult(
        val similarity: Float,
        val bestMatchStartMs: Long,
        val bestMatchEndMs: Long,
        val totalPositionsScanned: Int,
        val positionsAboveThreshold: Int,
        val pcmDurationMs: Long,
        val searchDurationMs: Long
    )

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

        val effectiveThreshold = 0.85f

        if (originalStartMs != null && originalStartMs >= 0 && originalStartMs + searchDurationMs <= pcmDurationMs) {
            progressCallback?.invoke(1, "验证原始位置...")
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
                            return PcmSearchResult(similarity = sim, bestMatchStartMs = pos, bestMatchEndMs = pos + searchDurationMs, totalPositionsScanned = 1, positionsAboveThreshold = 1, pcmDurationMs = pcmDurationMs, searchDurationMs = elapsed)
                        }
                    }
                }
            }
            val hasOriginalCandidate = bestOrigSim >= 0.70f
            if (hasOriginalCandidate) {
                val elapsed = System.currentTimeMillis() - searchStartTime
                return PcmSearchResult(similarity = bestOrigSim, bestMatchStartMs = bestOrigPos, bestMatchEndMs = bestOrigPos + searchDurationMs, totalPositionsScanned = 1, positionsAboveThreshold = 1, pcmDurationMs = pcmDurationMs, searchDurationMs = elapsed)
            }
        }

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
            if (sim > bestArraySim) { bestArraySim = sim; bestArrayOffset = coarsePos }
            candidates.add(coarsePos to sim)
            if (sim >= effectiveThreshold) aboveThreshold++
            coarsePos += coarseStep
        }

        candidates.sortByDescending { it.second }
        val topCandidates = candidates.take(10).map { it.first }

        progressCallback?.invoke(30, "中搜中（步长1帧）...")
        val searchRegions = mutableSetOf<Int>()
        for (candidateOffset in topCandidates) {
            val regionStart = (candidateOffset - 100).coerceAtLeast(0)
            val regionEnd = (candidateOffset + 100).coerceAtMost(maxOffset)
            for (off in regionStart..regionEnd) { searchRegions.add(off) }
        }

        for (offset in searchRegions) {
            if (offset % coarseStep == 0) continue
            totalScanned++
            var errors = 0
            for (i in 0 until parsedTarget.size) {
                errors += Integer.bitCount(parsedTarget[i] xor parsedFull[offset + i])
            }
            val sim = 1f - (errors.toFloat() / totalBits.toFloat())
            if (sim > bestArraySim) { bestArraySim = sim; bestArrayOffset = offset }
            if (sim >= effectiveThreshold) aboveThreshold++
        }

        var bestMatchStartMs = (bestArrayOffset * msPerFrame).toLong()
        bestMatchStartMs = bestMatchStartMs.coerceAtMost(pcmDurationMs - searchDurationMs)

        progressCallback?.invoke(60, "PCM验证中（±30秒）...")
        val verifyRange = 30000L
        val verifyStep = 500L
        val verifyStart = (bestMatchStartMs - verifyRange).coerceAtLeast(0L)
        val verifyEnd = (bestMatchStartMs + verifyRange).coerceAtMost(pcmDurationMs - searchDurationMs)

        var verifiedBestSim = bestArraySim
        var verifiedBestPos = bestMatchStartMs
        var verifyPos = verifyStart
        var verifyCount = 0

        while (verifyPos <= verifyEnd) {
            verifyCount++
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
                        if (sim >= effectiveThreshold) break
                    }
                }
            }
            verifyPos += verifyStep
        }

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
                        if (sim > verifiedBestSim) { verifiedBestSim = sim; verifiedBestPos = finePos }
                    }
                }
                finePos += 200L
            }
        }

        val elapsed = System.currentTimeMillis() - searchStartTime
        return PcmSearchResult(similarity = verifiedBestSim, bestMatchStartMs = verifiedBestPos, bestMatchEndMs = verifiedBestPos + searchDurationMs, totalPositionsScanned = totalScanned + verifyCount, positionsAboveThreshold = aboveThreshold, pcmDurationMs = pcmDurationMs, searchDurationMs = elapsed)
    }

    fun searchFingerprintInPcmWithGroup(
        fingerprint: String,
        pcmFile: File,
        searchDurationMs: Long,
        groupFingerprints: List<AudioFingerprint>? = null,
        threshold: Float = 0.70f,
        originalStartMs: Long? = null,
        progressCallback: ((Int, String) -> Unit)? = null
    ): PcmSearchResult? {
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
                            return PcmSearchResult(similarity = sim, bestMatchStartMs = gf.startMs, bestMatchEndMs = gf.startMs + gfDuration, totalPositionsScanned = 1, positionsAboveThreshold = 1, pcmDurationMs = pcmFile.length() / PCM_BYTES_PER_MS, searchDurationMs = 0)
                        }
                    }
                }
            }
        }
        return searchFingerprintInPcm(fingerprint, pcmFile, searchDurationMs, threshold, originalStartMs, progressCallback)
    }

    const val FINGERPRINT_GROUP_THRESHOLD = 0.95f

    data class FingerprintGroup(
        val groupId: Int,
        val memberIndices: List<Int>,
        val representativeIndex: Int
    )

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

        // v3.1.129: 哈希前缀预过滤——取指纹前10个整数的hashCode作为哈希键
        // 相比v3.1.128的take(5).joinToString(",")改为hashCode，范围更宽，过滤更灵活
        // 同一广告的指纹通常前几个整数高度相似，不同广告的指纹前几个整数差异显著
        data class IndexedFp(val index: Int, val parsed: List<Int>, val hashKey: Int)
        val indexed = parsedFingerprints.mapIndexedNotNull { idx, fp ->
            if (fp.isEmpty()) null
            else IndexedFp(idx, fp, fp.take(10).hashCode())
        }
        // 按哈希键分组，仅在同一分组内进行O(n²)对比
        val hashGroups = indexed.groupBy { it.hashKey }
        val sortedKeys = hashGroups.keys.sorted()

        for (key in sortedKeys) {
            val group = hashGroups[key]!!
            if (group.size <= 1) {
                // v3.1.129: 单成员哈希组——与相邻哈希组尝试对比，扩大搜索范围
                val keyIndex = sortedKeys.indexOf(key)
                val adjacentKeys = mutableListOf<Int>()
                if (keyIndex > 0) adjacentKeys.add(sortedKeys[keyIndex - 1])
                if (keyIndex < sortedKeys.size - 1) adjacentKeys.add(sortedKeys[keyIndex + 1])
                for (adjKey in adjacentKeys) {
                    val adjGroup = hashGroups[adjKey] ?: continue
                    if (adjGroup.size <= 1) continue
                    val fi = group[0].parsed
                    if (fi.isEmpty()) continue
                    for (adjEntry in adjGroup) {
                        val fj = adjEntry.parsed
                        if (fj.isEmpty()) continue
                        val lenRatio = minOf(fi.size, fj.size).toFloat() / maxOf(fi.size, fj.size).toFloat()
                        if (lenRatio < 0.8f) continue
                        val result = compareFingerprintArrays(fi, fj)
                        if (result.similarity >= FINGERPRINT_GROUP_THRESHOLD) {
                            union(group[0].index, adjEntry.index)
                        }
                    }
                }
                continue
            }
            for (i in 0 until group.size) {
                val fi = group[i].parsed
                if (fi.isEmpty()) continue
                for (j in (i + 1) until group.size) {
                    val fj = group[j].parsed
                    if (fj.isEmpty()) continue
                    val lenRatio = minOf(fi.size, fj.size).toFloat() / maxOf(fi.size, fj.size).toFloat()
                    if (lenRatio < 0.8f) continue
                    val result = compareFingerprintArrays(fi, fj)
                    if (result.similarity >= FINGERPRINT_GROUP_THRESHOLD) {
                        union(group[i].index, group[j].index)
                    }
                }
            }
        }

        val groups = mutableMapOf<Int, MutableList<Int>>()
        for (i in 0 until n) {
            val root = find(i)
            groups.getOrPut(root) { mutableListOf() }.add(i)
        }

        return groups.values.mapIndexed { gid, indices ->
            val repIdx = indices.maxByOrNull { parsedFingerprints[it].size } ?: indices.first()
            FingerprintGroup(groupId = gid, memberIndices = indices.sorted(), representativeIndex = repIdx)
        }
    }

    fun compareFingerprintsWithStretch(fp1: String, fp2: String, stretchTolerance: Float = 0.05f): CompareResult {
        val a = parseFingerprint(fp1)
        val b = parseFingerprint(fp2)
        return compareFingerprintArraysWithStretch(a, b, stretchTolerance)
    }

    fun compareFingerprintArraysWithStretch(a: List<Int>, b: List<Int>, stretchTolerance: Float = 0.05f): CompareResult {
        if (a.isEmpty() || b.isEmpty()) {
            return CompareResult(0f, 0, 0, 0, a.size, b.size, 0f, 0f)
        }
        if (a.size == b.size && a == b) {
            return CompareResult(1f, 0, 0, a.size * 32, a.size, b.size, 1f, 1f)
        }

        val standard = compareFingerprintArrays(a, b)
        if (standard.similarity >= 0.99f) return standard

        var best = standard
        val short = if (a.size <= b.size) a else b
        val long = if (a.size <= b.size) b else a
        val shortLen = short.size
        val longLen = long.size

        if (longLen <= shortLen || longLen <= 2) return best

        val shrinkInterval = (1.0 / stretchTolerance).toInt().coerceIn(15, 30)
        val shrunk = long.filterIndexed { index, _ -> (index + 1) % shrinkInterval != 0 }
        if (shrunk.size >= shortLen) {
            val sr = if (a.size <= b.size) compareFingerprintArrays(short, shrunk) else compareFingerprintArrays(shrunk, short)
            if (sr.similarity > best.similarity) best = sr
        }

        val grown = long.flatMapIndexed { index, value ->
            if (index > 0 && index % shrinkInterval == 0) listOf(value, value) else listOf(value)
        }
        if (grown.size >= shortLen) {
            val gr = if (a.size <= b.size) compareFingerprintArrays(short, grown) else compareFingerprintArrays(grown, short)
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
