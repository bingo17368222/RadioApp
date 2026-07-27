package com.radio.app.utils

import android.content.Context
import android.util.Log
import com.radio.app.RadioApplication
import java.io.File
import java.io.RandomAccessFile

/**
 * v3.0.2: PCM 片段截取工具。
 * 从完整 PCM 缓存中按起止时间截取 16kHz 单声道 16bit PCM 片段。
 */
object PcmSegmentExtractor {
    private const val TAG = "PcmSegmentExtractor"
    private const val BYTES_PER_MS = 32L // 16000 samples/sec * 2 bytes/sample / 1000 ms

    /**
     * 从 episode 的完整 PCM 缓存中截取 [startMs, endMs] 片段。
     * 优先使用 _full.pcm，其次 _5min.pcm。
     * 返回临时 PCM 文件，调用方负责删除。
     */
    fun extractSegmentPcm(context: Context, episodeId: String, startMs: Long, endMs: Long): File? {
        val pcmCacheDir = RadioApplication.getPcmCacheDir(context)
        val fullPcm = File(pcmCacheDir, "${episodeId}_full.pcm")
        val min5Pcm = File(pcmCacheDir, "${episodeId}_5min.pcm")

        val sourceFile = when {
            fullPcm.exists() && fullPcm.length() > 0 -> fullPcm
            min5Pcm.exists() && min5Pcm.length() > 0 -> min5Pcm
            else -> {
                Log.w(TAG, "extractSegmentPcm: no PCM cache found for $episodeId")
                return null
            }
        }

        return extractSegmentFromFile(sourceFile, startMs, endMs)
    }

    /**
     * 从指定 PCM 文件中截取片段。
     */
    fun extractSegmentFromFile(sourceFile: File, startMs: Long, endMs: Long): File? {
        if (!sourceFile.exists() || sourceFile.length() <= 0) return null
        if (endMs <= startMs) {
            Log.w(TAG, "extractSegmentFromFile: invalid range [$startMs, $endMs]")
            return null
        }

        val startByte = startMs.coerceAtLeast(0) * BYTES_PER_MS
        val endByte = endMs * BYTES_PER_MS
        val fileSize = sourceFile.length()
        val actualEndByte = endByte.coerceAtMost(fileSize)
        val actualStartByte = startByte.coerceAtMost(actualEndByte)
        val length = (actualEndByte - actualStartByte).toInt()

        if (length <= 0) {
            Log.w(TAG, "extractSegmentFromFile: empty segment after clamp [$actualStartByte, $actualEndByte]")
            return null
        }

        val outputFile = File.createTempFile("segment_${startMs}_${endMs}_", ".pcm", sourceFile.parentFile)
        try {
            RandomAccessFile(sourceFile, "r").use { raf ->
                raf.seek(actualStartByte)
                val buffer = ByteArray(8192)
                var remaining = length
                outputFile.outputStream().use { fos ->
                    while (remaining > 0) {
                        val toRead = minOf(remaining, buffer.size)
                        val read = raf.read(buffer, 0, toRead)
                        if (read < 0) break
                        fos.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            if (outputFile.length() > 0) {
                Log.i(TAG, "extractSegmentFromFile: extracted ${outputFile.length()} bytes from ${sourceFile.name} [$startMs, $endMs]")
                return outputFile
            } else {
                outputFile.delete()
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractSegmentFromFile failed: ${e.message}")
            outputFile.delete()
            return null
        }
    }

    /**
     * 从完整 PCM 文件中直接读取片段字节数据（不创建临时文件）。
     */
    fun readSegmentBytes(sourceFile: File, startMs: Long, endMs: Long): ByteArray? {
        if (!sourceFile.exists() || sourceFile.length() <= 0) return null
        val startByte = startMs.coerceAtLeast(0) * BYTES_PER_MS
        val endByte = endMs * BYTES_PER_MS
        val fileSize = sourceFile.length()
        val actualEndByte = endByte.coerceAtMost(fileSize)
        val actualStartByte = startByte.coerceAtMost(actualEndByte)
        val length = (actualEndByte - actualStartByte).toInt()
        if (length <= 0) return null

        return try {
            RandomAccessFile(sourceFile, "r").use { raf ->
                raf.seek(actualStartByte)
                val bytes = ByteArray(length)
                raf.readFully(bytes)
                bytes
            }
        } catch (e: Exception) {
            Log.e(TAG, "readSegmentBytes failed: ${e.message}")
            null
        }
    }
}
