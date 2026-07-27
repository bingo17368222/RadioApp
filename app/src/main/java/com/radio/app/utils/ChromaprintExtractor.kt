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
     * 检查库是否已下载。
     */
    fun isLibraryAvailable(context: Context): Boolean {
        return ChromaprintLoader.isLibraryDownloaded(context)
    }

    /**
     * 将指纹字符串解析为整数列表。
     */
    fun parseFingerprint(fingerprint: String?): List<Int> {
        if (fingerprint.isNullOrBlank()) return emptyList()
        return fingerprint.split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    /**
     * 计算两段指纹的相似度（0.0 - 1.0）。
     * 使用位误差率（bit error rate）：Chromaprint 指纹每个整数按位比较。
     * 对不等长的指纹，按较短长度对齐，允许首尾各 10% 的偏移窗口取最大相似度。
     */
    fun compareFingerprints(fp1: String, fp2: String): Float {
        val a = parseFingerprint(fp1)
        val b = parseFingerprint(fp2)
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a.size == b.size && a == b) return 1f

        val minLen = minOf(a.size, b.size)
        val maxLen = maxOf(a.size, b.size)
        if (minLen <= 0) return 0f

        // 对不等长指纹，截断到相同长度比较（允许首尾轻微偏移）
        val shiftWindow = (minLen * 0.1).toInt().coerceAtLeast(0).coerceAtMost(minLen / 2)
        var minErrors = Int.MAX_VALUE
        var totalBits = minLen * 32

        val short = if (a.size <= b.size) a else b
        val long = if (a.size <= b.size) b else a

        for (offset in 0..shiftWindow.coerceAtMost(long.size - minLen)) {
            var errors = 0
            for (i in 0 until minLen) {
                errors += Integer.bitCount(short[i] xor long[offset + i])
            }
            if (errors < minErrors) minErrors = errors
        }

        val similarity = 1f - (minErrors.toFloat() / totalBits.toFloat())
        // 长度差异惩罚：长度差异越大，相似度越低
        val lengthPenalty = 1f - (minLen.toFloat() / maxLen.toFloat()) * 0.3f
        return (similarity * lengthPenalty).coerceIn(0f, 1f)
    }

    /**
     * 判断两段指纹是否匹配。
     * @param threshold 相似度阈值，默认 0.75
     */
    fun isMatch(fp1: String, fp2: String, threshold: Float = 0.75f): Boolean {
        return compareFingerprints(fp1, fp2) >= threshold
    }

    @JvmStatic
    private external fun nativeExtractFingerprint(pcmData: ByteArray, sampleRate: Int, channels: Int): String?

    @JvmStatic
    private external fun nativeExtractFingerprintFromFile(filePath: String, sampleRate: Int, channels: Int): String?
}
