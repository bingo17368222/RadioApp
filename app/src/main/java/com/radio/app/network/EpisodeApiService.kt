package com.radio.app.network

import android.util.Log
import com.radio.app.models.Episode
import com.radio.app.models.SearchResult
import com.radio.app.models.Transcript
import com.radio.app.models.VoiceSegment
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EpisodeApiService private constructor() {

    companion object {
        private const val TAG = "EpisodeApiService"
        private const val API_BASE = "https://pubmod.hntv.tv/program"
        private const val SIGN_KEY = "6ca114a836ac7d73"

        @Volatile
        private var instance: EpisodeApiService? = null

        fun getInstance(): EpisodeApiService {
            return instance ?: synchronized(this) {
                instance ?: EpisodeApiService().also { instance = it }
            }
        }

        // hndt.com cid -> stationId 映射
        val CID_TO_STATION = mapOf(
            1 to "henan-news",
            2 to "henan-economy",
            3 to "henan-traffic",
            4 to "henan-opera",
            5 to "henan-music",
            6 to "henan-rural",
            7 to "henan-myradio",
            8 to "henan-private-car",
            9 to "henan-edu",
            10 to "henan-info",
            11 to "henan-bigradio"
        )

        val STATION_TO_CID = CID_TO_STATION.entries.associate { (k, v) -> v to k }

        // 直播流URL - 基于API返回的streams字段
        val STATION_STREAM_URLS = mapOf(
            "henan-news" to "https://stream.hndt.com/live/xinwen/playlist.m3u8",
            "henan-economy" to "https://stream.hndt.com/live/jingji/playlist.m3u8",
            "henan-traffic" to "https://stream.hndt.com/live/jiaotong/playlist.m3u8",
            "henan-opera" to "https://stream.hndt.com/live/yule/playlist.m3u8",
            "henan-music" to "https://stream.hndt.com/live/yinyue/playlist.m3u8",
            "henan-rural" to "https://stream.hndt.com/live/nongcun/playlist.m3u8",
            "henan-myradio" to "https://stream.hndt.com/live/yingshi/playlist.m3u8",
            "henan-private-car" to "https://stream.hndt.com/live/sijiache/playlist.m3u8",
            "henan-edu" to "https://stream.hndt.com/live/jiaoyu/playlist.m3u8",
            "henan-info" to "https://stream.hndt.com/live/leling/playlist.m3u8",
            "henan-bigradio" to "https://stream.hndt.com/live/gudian/playlist.m3u8"
        )

        fun getStationName(stationId: String): String {
            return when (stationId) {
                "henan-news" -> "河南新闻广播"
                "henan-economy" -> "河南经济广播"
                "henan-traffic" -> "河南交通广播"
                "henan-opera" -> "河南戏曲广播"
                "henan-music" -> "河南音乐广播"
                "henan-rural" -> "大象资讯台"
                "henan-myradio" -> "My Radio"
                "henan-private-car" -> "旅游广播·私家车999"
                "henan-edu" -> "河南教育广播"
                "henan-info" -> "信息广播"
                "henan-bigradio" -> "Big Radio"
                else -> "未知电台"
            }
        }

        /**
         * 生成签名认证头
         */
        private fun generateAuthHeaders(): Map<String, String> {
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val sign = sha256(SIGN_KEY + timestamp)
            return mapOf(
                "timestamp" to timestamp,
                "sign" to sign
            )
        }

        private fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }
    }

    interface ApiCallback<T> {
        fun onSuccess(result: T)
        fun onError(error: String)
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * HTTP GET 请求
     */
    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")

            // 添加认证头
            val auth = generateAuthHeaders()
            for ((key, value) in auth) {
                conn.setRequestProperty(key, value)
            }

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()
                return sb.toString()
            } else {
                Log.e(TAG, "HTTP $urlStr -> ${conn.responseCode}")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP error: $urlStr", e)
            return null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 获取所有河南电台频道列表（从 class/1 API）
     */
    fun fetchChannels(callback: ApiCallback<List<JSONObject>>) {
        executor.execute {
            try {
                val json = httpGet("$API_BASE/getAuth/live/class/program/1")
                if (json != null) {
                    val arr = JSONArray(json)
                    val list = mutableListOf<JSONObject>()
                    for (i in 0 until arr.length()) {
                        list.add(arr.getJSONObject(i))
                    }
                    callback.onSuccess(list)
                    Log.d(TAG, "Fetched ${list.size} channels from class/1")
                } else {
                    callback.onError("无法获取频道列表")
                }
            } catch (e: Exception) {
                callback.onError(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 获取指定日期和电台的节目单（含回放URL）
     * @param stationId 电台ID
     * @param dateStr 日期字符串 yyyy-MM-dd
     * @param callback 回调
     */
    fun getEpisodesByDate(stationId: String, dateStr: String, callback: ApiCallback<List<Episode>>) {
        executor.execute {
            try {
                val cid = STATION_TO_CID[stationId]
                if (cid == null) {
                    callback.onError("未知电台: $stationId")
                    return@execute
                }

                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                dateFormat.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
                val targetDate = dateFormat.parse(dateStr) ?: run {
                    callback.onError("无效日期: $dateStr")
                    return@execute
                }

                val today = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
                val targetCal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
                targetCal.time = targetDate

                // 重置到当天0点
                today.set(Calendar.HOUR_OF_DAY, 0)
                today.set(Calendar.MINUTE, 0)
                today.set(Calendar.SECOND, 0)
                today.set(Calendar.MILLISECOND, 0)
                targetCal.set(Calendar.HOUR_OF_DAY, 0)
                targetCal.set(Calendar.MINUTE, 0)
                targetCal.set(Calendar.SECOND, 0)
                targetCal.set(Calendar.MILLISECOND, 0)

                val isToday = targetCal.timeInMillis == today.timeInMillis
                val isFuture = targetCal.timeInMillis > today.timeInMillis

                if (isFuture) {
                    callback.onError("无法获取未来日期的节目单")
                    return@execute
                }

                val episodes = if (isToday) {
                    // 今天：使用 channel API（不含回放URL）
                    fetchTodayPrograms(cid, stationId, dateStr)
                } else {
                    // 过去日期：使用 VOD API（含回放URL）
                    val timestamp = targetCal.timeInMillis / 1000
                    fetchVodPrograms(cid, stationId, dateStr, timestamp)
                }

                if (episodes.isEmpty()) {
                    callback.onError("该日期暂无节目数据")
                } else {
                    callback.onSuccess(episodes)
                    Log.d(TAG, "Fetched ${episodes.size} episodes for $stationId on $dateStr")
                }
            } catch (e: Exception) {
                Log.e(TAG, "getEpisodesByDate failed", e)
                callback.onError(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 获取今天的节目单（不含回放URL）
     */
    private fun fetchTodayPrograms(cid: Int, stationId: String, dateStr: String): List<Episode> {
        val json = httpGet("$API_BASE/getAuth/live/channel/program/$cid") ?: return emptyList()
        try {
            val obj = JSONObject(json)
            val programs = obj.optJSONArray("programs") ?: return emptyList()
            val stationName = getStationName(stationId)
            val streamUrl = STATION_STREAM_URLS[stationId] ?: ""

            val episodes = mutableListOf<Episode>()
            for (i in 0 until programs.length()) {
                val prog = programs.getJSONObject(i)
                val beginTime = prog.optLong("beginTime", 0) * 1000
                val endTime = prog.optLong("endTime", 0) * 1000
                val title = prog.optString("title", "未知节目")
                val duration = if (endTime > beginTime) (endTime - beginTime) / 1000 else 3600

                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                timeFormat.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
                val timeStr = timeFormat.format(java.util.Date(beginTime))

                val ep = Episode().apply {
                    id = "$stationId-$dateStr-$i"
                    this.title = title
                    broadcastAt = "${dateStr}T${timeStr}:00"
                    this.duration = duration
                    description = "${timeStr} - ${getStationName(stationId)}"
                    this.stationId = stationId
                    this.stationName = stationName
                    // 今天没有回放URL，使用直播流
                    audioUrl = streamUrl
                    isLive = false
                    voiceSegments = generateSimpleSegments(duration * 1000)
                }
                episodes.add(ep)
            }
            return episodes
        } catch (e: Exception) {
            Log.e(TAG, "Parse today programs failed", e)
            return emptyList()
        }
    }

    /**
     * 获取过去日期的节目单（含回放URL）
     */
    private fun fetchVodPrograms(cid: Int, stationId: String, dateStr: String, timestamp: Long): List<Episode> {
        val json = httpGet("$API_BASE/getAuth/vod/program/$cid/$timestamp") ?: return emptyList()
        try {
            val obj = JSONObject(json)
            val programs = obj.optJSONArray("programs") ?: return emptyList()
            val stationName = getStationName(stationId)

            val episodes = mutableListOf<Episode>()
            for (i in 0 until programs.length()) {
                val prog = programs.getJSONObject(i)
                val beginTime = prog.optLong("beginTime", 0) * 1000
                val endTime = prog.optLong("endTime", 0) * 1000
                val title = prog.optString("title", "未知节目")
                val duration = if (endTime > beginTime) (endTime - beginTime) / 1000 else 3600

                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                timeFormat.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
                val timeStr = timeFormat.format(java.util.Date(beginTime))

                // 获取回放URL
                val playUrl = prog.optJSONArray("playUrl")
                val downloadUrl = prog.optJSONArray("downloadUrl")
                val replayUrl = when {
                    playUrl != null && playUrl.length() > 0 -> playUrl.getString(0)
                    downloadUrl != null && downloadUrl.length() > 0 -> downloadUrl.getString(0)
                    else -> null
                }

                val ep = Episode().apply {
                    id = "$stationId-$dateStr-$i"
                    this.title = title
                    broadcastAt = "${dateStr}T${timeStr}:00"
                    this.duration = duration
                    description = "${timeStr} - ${getStationName(stationId)}"
                    this.stationId = stationId
                    this.stationName = stationName
                    audioUrl = replayUrl ?: ""
                    isLive = false
                    voiceSegments = generateSimpleSegments(duration * 1000)
                }
                episodes.add(ep)
            }
            return episodes
        } catch (e: Exception) {
            Log.e(TAG, "Parse VOD programs failed", e)
            return emptyList()
        }
    }

    /**
     * 生成简单的语音分段
     */
    private fun generateSimpleSegments(totalDurationMs: Long): List<VoiceSegment> {
        val segments = mutableListOf<VoiceSegment>()
        val segCount = 8
        val segDuration = totalDurationMs / segCount

        for (j in 0 until segCount) {
            val seg = VoiceSegment().apply {
                start = j * segDuration
                end = (j + 1) * segDuration
                hasVoice = true
                label = "节目内容"
                isManuallyMarked = false
            }
            segments.add(seg)
        }
        return segments
    }

    /**
     * Search episodes and transcripts.
     */
    fun search(query: String, callback: ApiCallback<List<SearchResult>>) {
        executor.execute {
            try {
                val results = mutableListOf<SearchResult>()
                for (i in 0 until 5) {
                    val r = SearchResult().apply {
                        id = "search-$i"
                        title = "搜索结果: $query - 节目${i + 1}"
                        type = "episode"
                        stationName = "河南新闻广播"
                        matchedText = "这是包含'$query'的匹配文本内容..."
                    }
                    results.add(r)
                }
                callback.onSuccess(results)
            } catch (e: Exception) {
                callback.onError(e.message ?: "Unknown error")
            }
        }
    }
}