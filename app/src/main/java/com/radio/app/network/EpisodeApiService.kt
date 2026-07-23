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

        val CID_TO_STATION = mapOf(
            1 to "henan-news", 2 to "henan-economy", 3 to "henan-traffic",
            4 to "henan-opera", 5 to "henan-music", 6 to "henan-rural",
            7 to "henan-myradio", 8 to "henan-private-car", 9 to "henan-edu",
            10 to "henan-info", 11 to "henan-bigradio"
        )

        val STATION_TO_CID = CID_TO_STATION.entries.associate { (k, v) -> v to k }

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

        private fun generateAuthHeaders(): Map<String, String> {
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val sign = sha256(SIGN_KEY + timestamp)
            return mapOf("timestamp" to timestamp, "sign" to sign)
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

    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
            val auth = generateAuthHeaders()
            for ((key, value) in auth) conn.setRequestProperty(key, value)
            val code = conn.responseCode
            Log.d(TAG, "HTTP $urlStr -> $code")
            if (code == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) sb.append(line)
                reader.close()
                val body = sb.toString()
                Log.d(TAG, "HTTP response: ${body.take(200)}...")
                return body
            } else {
                val errorBody = try {
                    conn.errorStream?.bufferedReader()?.readText() ?: "(no body)"
                } catch (_: Exception) { "(read error)" }
                Log.e(TAG, "HTTP $urlStr -> $code, body=$errorBody")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP error: $urlStr, ${e.message}")
            return null
        } finally {
            conn?.disconnect()
        }
    }

    fun getEpisodesByDate(stationId: String, dateStr: String, callback: ApiCallback<List<Episode>>) {
        executor.execute {
            try {
                val episodes = fetchEpisodesByDateSync(stationId, dateStr)
                if (episodes != null) {
                    if (episodes.isEmpty()) {
                        callback.onError("该日期暂无节目数据")
                    } else {
                        callback.onSuccess(episodes)
                    }
                } else {
                    callback.onError("获取节目失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "getEpisodesByDate failed", e)
                callback.onError(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 同步获取指定日期的节目列表（在后台线程中调用）
     */
    fun fetchEpisodesByDateSync(stationId: String, dateStr: String): List<Episode>? {
        try {
            val cid = STATION_TO_CID[stationId] ?: return null

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            dateFormat.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            val targetDate = dateFormat.parse(dateStr) ?: return null

            val today = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
            val targetCal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
            targetCal.time = targetDate
            today.set(Calendar.HOUR_OF_DAY, 0); today.set(Calendar.MINUTE, 0)
            today.set(Calendar.SECOND, 0); today.set(Calendar.MILLISECOND, 0)
            targetCal.set(Calendar.HOUR_OF_DAY, 0); targetCal.set(Calendar.MINUTE, 0)
            targetCal.set(Calendar.SECOND, 0); targetCal.set(Calendar.MILLISECOND, 0)

            val isToday = targetCal.timeInMillis == today.timeInMillis
            val isFuture = targetCal.timeInMillis > today.timeInMillis

            if (isFuture) {
                Log.d(TAG, "fetchEpisodesByDateSync: future date $dateStr, returning empty")
                return emptyList()
            }

            val now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
            val nowTimestamp = now.timeInMillis / 1000

            val episodes = if (isToday) {
                fetchTodayWithVod(cid, stationId, dateStr, nowTimestamp)
            } else {
                val timestamp = targetCal.timeInMillis / 1000
                fetchVodPrograms(cid, stationId, dateStr, timestamp)
            }

            // v2.4.138: Automatically mark weekend episodes as "no preprocessing needed".
            val dayOfWeek = targetCal.get(Calendar.DAY_OF_WEEK)
            val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
            if (isWeekend && !episodes.isNullOrEmpty()) {
                try {
                    val settings = com.radio.app.models.AppSettings.getInstance(com.radio.app.RadioApplication.instance)
                    var markedCount = 0
                    for (ep in episodes) {
                        ep.id?.let {
                            if (!settings.isNoPreprocess(it)) {
                                settings.markNoPreprocess(com.radio.app.RadioApplication.instance, it)
                                markedCount++
                            }
                        }
                    }
                    Log.d(TAG, "fetchEpisodesByDateSync: auto-marked $markedCount weekend episodes as no-preprocess for $dateStr")
                } catch (e: Exception) {
                    Log.w(TAG, "fetchEpisodesByDateSync: failed to auto-mark weekend episodes", e)
                }
            }

            return episodes
        } catch (e: Exception) {
            Log.e(TAG, "fetchEpisodesByDateSync failed for $stationId $dateStr", e)
            return null
        }
    }

    /**
     * 今天：获取节目列表，已过时间的节目尝试用VOD API获取回放URL
     */
    private fun fetchTodayWithVod(cid: Int, stationId: String, dateStr: String, nowTimestamp: Long): List<Episode> {
        val json = httpGet("$API_BASE/getAuth/live/channel/program/$cid") ?: return emptyList()
        try {
            val obj = JSONObject(json)
            val programs = obj.optJSONArray("programs") ?: return emptyList()
            val stationName = getStationName(stationId)
            val streamUrl = STATION_STREAM_URLS[stationId] ?: ""
            val episodes = mutableListOf<Episode>()

            // 计算当天0点时间戳
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            dateFormat.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            val dayMidnight = dateFormat.parse(dateStr)?.time?.div(1000) ?: (nowTimestamp / 86400 * 86400)

            for (i in 0 until programs.length()) {
                val prog = programs.getJSONObject(i)
                val beginTime = prog.optLong("beginTime", 0) * 1000
                val endTime = prog.optLong("endTime", 0) * 1000
                val title = prog.optString("title", "未知节目")
                val duration = if (endTime > beginTime) (endTime - beginTime) / 1000 else 3600

                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                timeFormat.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
                val timeStr = timeFormat.format(java.util.Date(beginTime))

                // 判断该节目是否已结束：endTime < now (加60秒缓冲)
                // 如果已结束，尝试从VOD API获取回放URL
                // 使用 beginTimeSec（节目开始时间）作为VOD时间戳，因为VOD CDN按开始时间索引
                var replayUrl: String? = null
                val beginTimeSec = beginTime / 1000
                if (endTime / 1000 < nowTimestamp + 60) {
                    // 先按开始时间+标题匹配
                    replayUrl = tryFetchVodUrl(cid, beginTimeSec, title)
                    // 失败则用当天0点时间戳+标题匹配
                    if (replayUrl == null) {
                        replayUrl = tryFetchVodUrl(cid, dayMidnight, title)
                    }
                    // 仍失败则不带标题匹配（取第一个结果）
                    if (replayUrl == null) {
                        replayUrl = tryFetchVodUrl(cid, beginTimeSec, null)
                    }
                    if (replayUrl == null) {
                        replayUrl = tryFetchVodUrl(cid, dayMidnight, null)
                    }
                    if (replayUrl == null) {
                        Log.w(TAG, "No VOD url for: $title (beginTimeSec=$beginTimeSec, now=$nowTimestamp)")
                    }
                }

                val ep = Episode().apply {
                    id = "$stationId-$dateStr-$i"
                    this.title = title
                    broadcastAt = "${dateStr}T${timeStr}:00"
                    this.duration = duration
                    // v2.4.147: Persist start/end timestamps for offline display.
                    this.startTime = beginTime
                    this.endTime = endTime
                    description = "${timeStr} - ${getStationName(stationId)}"
                    this.stationId = stationId
                    this.stationName = stationName
                    // 已过节目：只有获取到VOD URL才可回放，不回退到直播流
                    audioUrl = replayUrl ?: ""
                    isLive = false
                    voiceSegments = generateSimpleSegments(duration * 1000)
                }
                episodes.add(ep)
            }
            return episodes
        } catch (e: Exception) {
            Log.e(TAG, "fetchTodayWithVod failed", e)
            return emptyList()
        }
    }

    /**
     * 尝试获取单个节目的VOD回放URL，按标题匹配确保正确节目
     */
    private fun tryFetchVodUrl(cid: Int, timestamp: Long, matchTitle: String? = null): String? {
        try {
            val url = "$API_BASE/getAuth/vod/program/$cid/$timestamp"
            Log.d(TAG, "tryFetchVodUrl: $url")
            val json = httpGet(url) ?: return null
            val obj = JSONObject(json)
            val programs = obj.optJSONArray("programs") ?: return null
            if (programs.length() == 0) return null
            for (i in 0 until programs.length()) {
                val prog = programs.getJSONObject(i)
                val title = prog.optString("title", "")
                // 如果指定了标题，匹配标题
                if (matchTitle != null && matchTitle.isNotEmpty() && title != matchTitle) continue
                val playUrl = prog.optJSONArray("playUrl")
                val downloadUrl = prog.optJSONArray("downloadUrl")
                val url = when {
                    playUrl != null && playUrl.length() > 0 -> playUrl.getString(0)
                    downloadUrl != null && downloadUrl.length() > 0 -> downloadUrl.getString(0)
                    else -> null
                }
                if (url != null) {
                    Log.d(TAG, "Found VOD url: $url for title=$title")
                    return url
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryFetchVodUrl failed for cid=$cid ts=$timestamp", e)
        }
        return null
    }

    private fun fetchVodPrograms(cid: Int, stationId: String, dateStr: String, timestamp: Long): List<Episode> {
        val url = "$API_BASE/getAuth/vod/program/$cid/$timestamp"
        Log.d(TAG, "fetchVodPrograms: $url (dateStr=$dateStr, timestamp=$timestamp)")
        val json = httpGet(url)
        if (json == null) {
            Log.e(TAG, "fetchVodPrograms: HTTP request failed for $url")
            return emptyList()
        }
        Log.d(TAG, "fetchVodPrograms: response length=${json.length}")
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
                    this.startTime = beginTime
                    this.endTime = endTime
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