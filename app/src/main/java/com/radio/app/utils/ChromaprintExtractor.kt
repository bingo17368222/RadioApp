package com.radio.app.utils

import android.content.Context
import android.util.Log
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

    @JvmStatic
    private external fun nativeExtractFingerprint(pcmData: ByteArray, sampleRate: Int, channels: Int): String?

    @JvmStatic
    private external fun nativeExtractFingerprintFromFile(filePath: String, sampleRate: Int, channels: Int): String?

    @JvmStatic
    private external fun nativeSetLibraryPath(libPath: String)

    @JvmStatic
    private external fun nativeDecodeFingerprint(encodedFp: String): String?
}
