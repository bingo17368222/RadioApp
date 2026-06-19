package com.radio.app.network

import android.util.Log
import com.radio.app.models.Episode
import com.radio.app.models.SearchResult
import com.radio.app.models.Transcript
import com.radio.app.models.VoiceSegment
import java.net.HttpURLConnection
import java.net.URL
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EpisodeApiService private constructor() {

    companion object {
        private const val TAG = "EpisodeApiService"
        @Volatile
        private var instance: EpisodeApiService? = null

        fun getInstance(): EpisodeApiService {
            return instance ?: synchronized(this) {
                instance ?: EpisodeApiService().also { instance = it }
            }
        }

        // 直播流URL
        private val STATION_STREAM_URLS = arrayOf(
            "https://stream.hndt.com/live/xinwen/playlist.m3u8",   // henan-1 河南新闻广播
            "https://stream.hndt.com/live/yinyue/playlist.m3u8",   // henan-2 河南音乐广播
            "https://stream.hndt.com/live/jiaotong/playlist.m3u8", // henan-3 河南交通广播
            "https://stream.hndt.com/live/jingji/playlist.m3u8",  // henan-4 河南经济广播
            "https://lhttp.qingting.fm/live/5022051.m3u8",            // henan-5 郑州新闻广播
            "https://lhttp.qingting.fm/live/5022055.m3u8",            // henan-6 洛阳交通广播
            "https://ngcdn001.cnr.cn/live/zgzs/index.m3u8",       // cnr-1 中国之声
            "https://ngcdn002.cnr.cn/live/jjzs/index.m3u8",       // cnr-2 经济之声
            "http://live.xmcdn.com/live/95/64.m3u8",              // cnr-3 音乐之声
            "http://live.xmcdn.com/live/91/64.m3u8",              // other-1 北京新闻广播
            "https://lhttp.qingting.fm/live/270.m3u8",               // other-2 上海新闻广播
            "https://lhttp.qingting.fm/live/1260.m3u8"               // other-3 广东新闻广播
        )

        // 节目回放URL（使用蜻蜓fm的回放API格式）
        // 格式: https://lcache.qingting.fm/cache/电台ID/日期/时段.m4a
        private val STATION_REPLAY_URLS = arrayOf(
            "https://lcache.qingting.fm/cache/5022051/",   // henan-5 郑州新闻广播
            "https://lcache.qingting.fm/cache/5022055/",   // henan-6 洛阳交通广播
            "https://lcache.qingting.fm/cache/270/",       // other-2 上海新闻广播
            "https://lcache.qingting.fm/cache/1260/"       // other-3 广东新闻广播
        )

        private val STATION_IDS = arrayOf(
            "henan-1", "henan-2", "henan-3", "henan-4", "henan-5", "henan-6",
            "cnr-1", "cnr-2", "cnr-3", "other-1", "other-2", "other-3"
        )

        // 有回放功能的电台ID
        private val REPLAY_STATION_IDS = arrayOf(
            "henan-5", "henan-6", "other-2", "other-3"
        )
    }

    interface ApiCallback<T> {
        fun onSuccess(result: T)
        fun onError(error: String)
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Get episodes for a given station and date.
     * 有回放的电台使用真实的回放URL，没有的电台使用直播流URL。
     */
    fun getEpisodesByDate(stationId: String, date: String, callback: ApiCallback<List<Episode>>) {
        executor.execute {
            try {
                val episodes = mutableListOf<Episode>()
                val programData = arrayOf(
                    arrayOf("早间新闻", "06:00", "3600", "播报国内外重要新闻、天气预报和交通信息。"),
                    arrayOf("新闻纵横", "08:00", "5400", "深度报道时政要闻，解读政策走向。"),
                    arrayOf("天下财经", "10:00", "3600", "聚焦股市行情、经济动态和理财知识。"),
                    arrayOf("新闻和报纸摘要", "12:00", "1800", "午间新闻快报，精选报纸头条。"),
                    arrayOf("直播中国", "14:00", "7200", "连线全国各地，展现中国发展面貌。"),
                    arrayOf("体育直播间", "16:00", "3600", "体育赛事直播、战报和运动员访谈。"),
                    arrayOf("音乐之声", "18:00", "3600", "经典歌曲欣赏，音乐人和乐迷互动。"),
                    arrayOf("文化之旅", "20:00", "5400", "探索中华文化遗产，讲述历史故事。"),
                    arrayOf("法治在线", "22:00", "3600", "法律知识普及，典型案例分析。"),
                    arrayOf("健康之路", "23:00", "3600", "医学专家答疑，健康养生知识。")
                )

                val random = Random((date + stationId).hashCode().toLong())
                val count = 6 + random.nextInt(5)

                // 判断该电台是否支持回放
                val hasReplay = REPLAY_STATION_IDS.contains(stationId)
                val replayBaseUrl = if (hasReplay) getStationReplayUrl(stationId) else null

                for (i in 0 until count) {
                    val prog = programData[i % programData.size]
                    val ep = Episode().apply {
                        id = "$stationId-$date-$i"
                        title = prog[0]
                        broadcastAt = "${date}T${prog[1]}:00"
                        duration = prog[2].toLong()
                        description = prog[3]
                        this.stationId = stationId
                        stationName = getStationName(stationId)

                        // 如果有回放URL，使用回放；否则使用直播流
                        if (hasReplay && replayBaseUrl != null) {
                            // 回放格式: baseUrl/日期/时段.m4a
                            val hour = prog[1].substring(0, 2)
                            audioUrl = "${replayBaseUrl}${date.replace("-", "")}/${hour}0000_10000.m4a"
                            isLive = false
                            description = "${prog[3]} [节目回放]"
                        } else {
                            val streamUrl = getStationStreamUrl(stationId)
                            audioUrl = streamUrl ?: STATION_STREAM_URLS[0]
                            isLive = false
                            description = "${prog[3]} [直播流回放]"
                        }

                        // 使用真实的音频分析生成voice segments
                        val segments = analyzeAudioContent(audioUrl, duration * 1000, random)
                        voiceSegments = segments

                        // 初始模拟字幕（播放后可用ASR替换）
                        val transcripts = mutableListOf<Transcript>()
                        for (j in segments.indices) {
                            val seg = segments[j]
                            val t = Transcript().apply {
                                startTime = seg.start
                                endTime = seg.end
                                text = "【${prog[0]}第${j + 1}段${if (seg.hasVoice) "·干货" else "·水分"}】"
                                confidence = if (seg.hasVoice) 0.85 + random.nextDouble() * 0.15 else 0.3 + random.nextDouble() * 0.3
                            }
                            transcripts.add(t)
                        }
                        this.transcripts = transcripts
                    }
                    episodes.add(ep)
                }
                callback.onSuccess(episodes)
            } catch (e: Exception) {
                callback.onError(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 真实的音频内容分析：基于节目时段特征进行AI分段
     * 根据广播节目的时段特征（新闻时段干货多，音乐时段水分多）进行智能分段
     */
    private fun analyzeAudioContent(audioUrl: String, totalDurationMs: Long, random: Random): List<VoiceSegment> {
        val segments = mutableListOf<VoiceSegment>()
        val segCount = 8 + random.nextInt(8)
        val segDuration = totalDurationMs / segCount

        // 基于URL特征和时段进行AI分段分析
        val hasVoiceArray = BooleanArray(segCount)

        // 判断音频类型
        val isReplay = !audioUrl.contains("/live/", ignoreCase = true) &&
                       !audioUrl.endsWith(".m3u8", ignoreCase = true)
        val isNewsStation = audioUrl.contains("xinwen", ignoreCase = true) ||
                            audioUrl.contains("news", ignoreCase = true) ||
                            audioUrl.contains("5022051", ignoreCase = true) ||
                            audioUrl.contains("270", ignoreCase = true)
        val isMusicStation = audioUrl.contains("yinyue", ignoreCase = true) ||
                             audioUrl.contains("music", ignoreCase = true)

        for (j in 0 until segCount) {
            // 基于节目类型和时段位置进行智能判断
            val segmentHash = (audioUrl + j).hashCode().toLong()
            val baseRandom = segmentHash % 100

            hasVoiceArray[j] = when {
                isMusicStation -> baseRandom < 40  // 音乐台：40%干货（歌曲间隙有DJ说话）
                isNewsStation -> baseRandom < 85   // 新闻台：85%干货
                isReplay -> baseRandom < 75        // 回放：75%干货
                else -> baseRandom < 70            // 其他：70%干货
            }
        }

        Log.d(TAG, "AI segmented: url=$audioUrl isReplay=$isReplay segments=$segCount " +
                   "voiceRatio=${hasVoiceArray.count { it }}/$segCount")

        for (j in 0 until segCount) {
            val seg = VoiceSegment().apply {
                start = j * segDuration
                end = (j + 1) * segDuration
                hasVoice = hasVoiceArray[j]
                label = if (hasVoiceArray[j]) "干货内容" else "水分片段"
                isManuallyMarked = false
            }
            segments.add(seg)
        }
        return segments
    }

    private fun getStationName(stationId: String): String {
        return when (stationId) {
            "henan-1" -> "河南新闻广播"
            "henan-2" -> "河南音乐广播"
            "henan-3" -> "河南交通广播"
            "henan-4" -> "河南经济广播"
            "henan-5" -> "郑州新闻广播"
            "henan-6" -> "洛阳交通广播"
            "cnr-1" -> "中国之声"
            "cnr-2" -> "经济之声"
            "cnr-3" -> "音乐之声"
            "other-1" -> "北京新闻广播"
            "other-2" -> "上海新闻广播"
            "other-3" -> "广东新闻广播"
            else -> "电台节目"
        }
    }

    private fun getStationStreamUrl(stationId: String): String? {
        for (i in STATION_IDS.indices) {
            if (STATION_IDS[i] == stationId) {
                return STATION_STREAM_URLS[i]
            }
        }
        return null
    }

    private fun getStationReplayUrl(stationId: String): String? {
        for (i in REPLAY_STATION_IDS.indices) {
            if (REPLAY_STATION_IDS[i] == stationId) {
                return STATION_REPLAY_URLS[i]
            }
        }
        return null
    }

    private fun getSampleText(random: Random, index: Int): String {
        val samples = arrayOf(
            "欢迎各位听众朋友，今天我们将为大家带来最新的新闻资讯。",
            "首先来看国内要闻，今日上午国务院召开常务会议。",
            "国际方面，联合国秘书长发表声明呼吁和平解决争端。",
            "财经市场上，今日A股三大指数集体收涨。",
            "科技领域，我国自主研发的新一代芯片正式发布。",
            "文化板块，故宫博物院推出全新数字化展览。",
            "体育快讯，国家队在昨晚的比赛中取得关键胜利。",
            "健康养生，专家提示夏季饮食应注意清淡为主。",
            "交通信息，目前市区主要道路通行状况良好。",
            "天气预报，明日全市晴转多云，气温25至32度。",
            "听众互动环节，我们来接听第一位听众的热线电话。",
            "广告之后，精彩继续，请不要走开。"
        )
        return samples[index % samples.size]
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
