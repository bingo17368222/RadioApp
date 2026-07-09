package com.radio.app.network

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * [v2.4.26] Ali DashScope API client for AI content segmentation.
 * Uses 通义千问 (Qwen) model to classify subtitle segments as 干货(content) or 水货(filler).
 */
class AliApiService {

    companion object {
        private const val TAG = "AliApiService"
        private const val DASHSCOPE_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation"
        private const val MODEL = "qwen-turbo"
    }

    data class SegmentResult(
        val start: Long,
        val end: Long,
        val isDry: Boolean,
        val label: String
    )

    /**
     * Send subtitles to Ali Qwen API for content classification.
     * Returns list of SegmentResult with dry/water classification.
     */
    fun classifySubtitles(
        apiKey: String,
        subtitles: List<Triple<Long, Long, String>>,
        segmentDurationMs: Long = 3 * 60 * 1000L
    ): List<SegmentResult> {
        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is empty")
            return emptyList()
        }
        if (subtitles.isEmpty()) return emptyList()

        // Group subtitles into 3-minute segments
        val groupedSegments = groupSubtitles(subtitles, segmentDurationMs)
        if (groupedSegments.isEmpty()) return emptyList()

        // Build prompt for the API
        val prompt = buildPrompt(groupedSegments)

        // Call the API
        val response = callDashScope(apiKey, prompt)
        if (response.isNullOrEmpty()) return emptyList()

        // Parse response
        return parseResponse(response, groupedSegments)
    }

    private fun groupSubtitles(
        subtitles: List<Triple<Long, Long, String>>,
        segmentDurationMs: Long
    ): MutableList<Pair<LongRange, String>> {
        val groups = mutableListOf<Pair<LongRange, String>>()
        var currentStart = 0L
        var currentEnd = segmentDurationMs
        var currentText = StringBuilder()

        for ((start, end, text) in subtitles) {
            while (start >= currentEnd && currentText.isNotEmpty()) {
                groups.add(Pair(LongRange(currentStart.toInt(), currentEnd.toInt()), currentText.toString()))
                currentStart = currentEnd
                currentEnd = currentStart + segmentDurationMs
                currentText = StringBuilder()
            }
            currentText.append(text)
        }
        if (currentText.isNotEmpty()) {
            groups.add(Pair(LongRange(currentStart.toInt(), currentEnd.toInt()), currentText.toString()))
        }
        return groups
    }

    private fun buildPrompt(segments: List<Pair<LongRange, String>>): String {
        val sb = StringBuilder()
        sb.append("你是一个广播电台内容分析专家。下面是广播节目的字幕，按时间段分组。")
        sb.append("请分析每段内容，判断是「干货」（有价值的新闻、资讯、访谈、评论）还是「水货」（广告、音乐、片头片尾、闲聊、填充内容）。\n\n")
        sb.append("请只返回JSON数组，每个元素包含：\n")
        sb.append("- index: 段落编号（从1开始）\n")
        sb.append("- type: \"干货\" 或 \"水货\"\n")
        sb.append("- reason: 简短理由（不超过10字）\n\n")
        sb.append("字幕内容：\n")

        for ((index, pair) in segments.withIndex()) {
            val (range, text) = pair
            val startTime = range.first / 1000
            val endTime = range.last / 1000
            // Limit text to 500 chars per segment to avoid token overflow
            val truncatedText = if (text.length > 500) text.substring(0, 500) + "..." else text
            sb.append("[段落${index + 1}] ${startTime}s-${endTime}s: $truncatedText\n")
        }

        return sb.toString()
    }

    private fun callDashScope(apiKey: String, prompt: String): String? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(DASHSCOPE_URL)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30000
                readTimeout = 60000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }

            val requestBody = JSONObject().apply {
                put("model", MODEL)
                put("input", JSONObject().apply {
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "你是一个专业的内容分析师，擅长分析广播节目内容并分类。")
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                })
                put("parameters", JSONObject().apply {
                    put("result_format", "message")
                    put("temperature", 0.1)
                    put("max_tokens", 2000)
                })
            }

            val outputStream: OutputStream = connection.outputStream
            outputStream.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            outputStream.flush()
            outputStream.close()

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorStream = connection.errorStream
                if (errorStream != null) {
                    val errorText = BufferedReader(InputStreamReader(errorStream)).readText()
                    Log.e(TAG, "API error $responseCode: $errorText")
                } else {
                    Log.e(TAG, "API error $responseCode")
                }
                return null
            }

            val responseStream = connection.inputStream
            val responseText = BufferedReader(InputStreamReader(responseStream)).readText()
            Log.d(TAG, "API response length: ${responseText.length}")
            return responseText
        } catch (e: Exception) {
            Log.e(TAG, "API call failed: ${e.message}")
            return null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseResponse(responseText: String, segments: List<Pair<LongRange, String>>): List<SegmentResult> {
        try {
            val responseJson = JSONObject(responseText)
            val output = responseJson.optJSONObject("output")
                ?: responseJson.optJSONObject("output", JSONObject())
            val choices = output?.optJSONArray("choices")
            val text = output?.optString("text", null)

            val content = when {
                choices != null && choices.length() > 0 -> {
                    choices.getJSONObject(0).optJSONObject("message")?.optString("content", "")
                }
                text != null -> text
                else -> ""
            }

            if (content.isNullOrEmpty()) {
                Log.e(TAG, "No content in response")
                return emptyList()
            }

            // Extract JSON array from response (may be embedded in text)
            val jsonStart = content.indexOf('[')
            val jsonEnd = content.lastIndexOf(']')
            if (jsonStart < 0 || jsonEnd < 0) {
                Log.e(TAG, "No JSON array in response: $content")
                return emptyList()
            }

            val jsonStr = content.substring(jsonStart, jsonEnd + 1)
            val jsonArray = JSONArray(jsonStr)

            val results = mutableListOf<SegmentResult>()
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val index = item.optInt("index", i + 1) - 1
                val type = item.optString("type", "")
                val isDry = type.contains("干货")

                if (index in segments.indices) {
                    val (range, _) = segments[index]
                    results.add(SegmentResult(
                        start = range.first.toLong(),
                        end = range.last.toLong(),
                        isDry = isDry,
                        label = if (isDry) "干货" else "水货"
                    ))
                }
            }

            Log.d(TAG, "Parsed ${results.size} segments (${results.count { it.isDry }} dry, ${results.count { !it.isDry }} water)")
            return results
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response: ${e.message}")
            return emptyList()
        }
    }
}
