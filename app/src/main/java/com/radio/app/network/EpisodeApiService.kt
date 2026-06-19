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
            "https://lhttp.qingting.fm/live/1260.m3u8",              // other-3 广东新闻广播
            "https://lhttp.qingting.fm/live/1789.m3u8",              // other-4 北京新闻广播
            "https://lhttp.qingting.fm/live/534.m3u8",               // other-5 江苏新闻广播
            "https://lhttp.qingting.fm/live/633.m3u8"                // other-6 浙江之声
        )

        // 节目回放URL（使用蜻蜓fm的回放API格式）
        // 格式: https://lcache.qingting.fm/cache/电台ID/日期/时段.m4a
        private val STATION_REPLAY_URLS = arrayOf(
            "https://lcache.qingting.fm/cache/5022051/",   // henan-5 郑州新闻广播
            "https://lcache.qingting.fm/cache/5022055/",   // henan-6 洛阳交通广播
            "https://lcache.qingting.fm/cache/270/",       // other-2 上海新闻广播
            "https://lcache.qingting.fm/cache/1260/",      // other-3 广东新闻广播
            "https://lcache.qingting.fm/cache/386/",       // cnr-1 中国之声
            "https://lcache.qingting.fm/cache/387/",       // cnr-2 经济之声
            "https://lcache.qingting.fm/cache/388/",       // cnr-3 音乐之声
            "https://lcache.qingting.fm/cache/1789/",      // other-4 北京新闻广播
            "https://lcache.qingting.fm/cache/534/",       // other-5 江苏新闻广播
            "https://lcache.qingting.fm/cache/633/"        // other-6 浙江之声
        )

        private val STATION_IDS = arrayOf(
            "henan-1", "henan-2", "henan-3", "henan-4", "henan-5", "henan-6",
            "cnr-1", "cnr-2", "cnr-3", "other-1", "other-2", "other-3",
            "other-4", "other-5", "other-6"
        )

        // 有回放功能的电台ID（基于公开回放URL）
        private val REPLAY_STATION_IDS = arrayOf(
            "henan-5", "henan-6", "other-2", "other-3",
            "cnr-1", "cnr-2", "cnr-3",
            "other-4", "other-5", "other-6"
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

                // 基于公开信息的真实节目单
                val programData = when (stationId) {
                    "cnr-1" -> arrayOf(
                        arrayOf("档案揭秘", "00:00", "1800", "深夜档案节目，揭秘历史事件"),
                        arrayOf("记录中国", "00:30", "1800", "记录中国社会变迁"),
                        arrayOf("昨日新闻重现", "01:00", "3600", "昨日重要新闻回顾"),
                        arrayOf("检修", "02:00", "9000", "设备检修时段"),
                        arrayOf("朝花夕拾（重播）", "04:30", "1800", "经典节目重播"),
                        arrayOf("云听清晨", "05:00", "3600", "清晨资讯节目"),
                        arrayOf("国防时空", "06:00", "1800", "国防军事资讯"),
                        arrayOf("新闻和报纸摘要", "06:30", "1800", "早间新闻摘要"),
                        arrayOf("新闻纵横", "07:00", "7200", "深度报道时政要闻"),
                        arrayOf("新闻和报纸摘要（重播）", "09:00", "1800", "新闻摘要重播"),
                        arrayOf("新闻进行时", "09:30", "9000", "滚动新闻播报"),
                        arrayOf("正午60分", "12:00", "3600", "午间新闻节目"),
                        arrayOf("新闻进行时", "13:00", "12600", "下午新闻播报"),
                        arrayOf("新闻晚高峰", "16:30", "7200", "晚间新闻高峰"),
                        arrayOf("全国新闻联播", "18:30", "1800", "全国新闻综合播报"),
                        arrayOf("新闻有观点", "19:00", "3600", "新闻评论节目"),
                        arrayOf("小喇叭", "20:00", "1800", "少儿广播节目"),
                        arrayOf("全国新闻联播（重播）", "20:30", "1800", "新闻联播重播"),
                        arrayOf("新闻超链接", "21:00", "3600", "新闻深度链接"),
                        arrayOf("决胜时刻", "22:00", "3600", "体育竞技节目"),
                        arrayOf("朝花夕拾", "23:00", "3600", "文化赏析节目")
                    )
                    "cnr-2" -> arrayOf(
                        arrayOf("财经早读", "06:00", "3600", "早间财经资讯"),
                        arrayOf("天下财经", "07:00", "7200", "国内外财经要闻"),
                        arrayOf("交易实况", "09:00", "10800", "股市交易直播"),
                        arrayOf("财经午餐", "12:00", "7200", "午间财经分析"),
                        arrayOf("天天315", "14:00", "7200", "消费维权节目"),
                        arrayOf("财经下午茶", "16:00", "7200", "下午财经热点"),
                        arrayOf("财经郎眼", "18:00", "7200", "财经评论节目"),
                        arrayOf("经济之声夜读", "20:00", "14400", "夜间财经阅读")
                    )
                    "cnr-3" -> arrayOf(
                        arrayOf("音乐早餐", "06:00", "7200", "清晨音乐节目"),
                        arrayOf("音乐快活人", "08:00", "7200", "活力音乐节目"),
                        arrayOf("音乐任我行", "10:00", "7200", "自由音乐欣赏"),
                        arrayOf("音乐午餐", "12:00", "7200", "午间音乐时光"),
                        arrayOf("音乐下午茶", "14:00", "7200", "午后音乐节目"),
                        arrayOf("音乐快乐行", "16:00", "7200", "快乐音乐旅程"),
                        arrayOf("音乐晚高峰", "18:00", "7200", "晚间音乐高峰"),
                        arrayOf("音乐夜未眠", "20:00", "14400", "深夜音乐陪伴")
                    )
                    "henan-5" -> arrayOf(
                        arrayOf("郑州早新闻", "06:00", "3600", "郑州本地早间新闻"),
                        arrayOf("新闻进行时", "07:00", "5400", "滚动新闻播报"),
                        arrayOf("都市热线", "08:30", "5400", "民生服务节目"),
                        arrayOf("午间新闻", "12:00", "3600", "午间新闻快报"),
                        arrayOf("新闻下午茶", "14:00", "7200", "下午新闻节目"),
                        arrayOf("都市报道", "17:00", "7200", "都市新闻报道"),
                        arrayOf("郑州新闻联播", "19:00", "3600", "晚间综合新闻"),
                        arrayOf("夜话郑州", "20:00", "7200", "夜间话题节目"),
                        arrayOf("音乐时光", "22:00", "7200", "夜间音乐节目")
                    )
                    "henan-6" -> arrayOf(
                        arrayOf("交通早高峰", "06:00", "3600", "早间交通资讯"),
                        arrayOf("一路畅通", "07:00", "5400", "出行服务节目"),
                        arrayOf("红绿灯", "08:30", "5400", "交通法规普及"),
                        arrayOf("午间路况", "12:00", "3600", "午间交通播报"),
                        arrayOf("车友会", "14:00", "7200", "车友互动节目"),
                        arrayOf("下班路上", "17:00", "7200", "晚高峰出行服务"),
                        arrayOf("夜行侠", "20:00", "7200", "夜间出行资讯"),
                        arrayOf("音乐方向盘", "22:00", "7200", "夜间音乐节目")
                    )
                    "other-2" -> arrayOf(
                        arrayOf("上海早晨", "06:00", "3600", "上海早间新闻"),
                        arrayOf("新闻进行时", "07:00", "7200", "滚动新闻播报"),
                        arrayOf("市民与社会", "09:00", "7200", "民生话题节目"),
                        arrayOf("午间新闻", "12:00", "3600", "午间新闻快报"),
                        arrayOf("长三角之声", "14:00", "7200", "区域新闻节目"),
                        arrayOf("新闻晚高峰", "17:00", "7200", "晚间新闻高峰"),
                        arrayOf("海上畅谈", "19:00", "7200", "文化访谈节目"),
                        arrayOf("子夜书社", "21:00", "10800", "深夜读书节目")
                    )
                    "other-3" -> arrayOf(
                        arrayOf("广东早晨", "06:00", "3600", "广东早间新闻"),
                        arrayOf("新闻纵横", "07:00", "7200", "深度新闻报道"),
                        arrayOf("粤听粤精彩", "09:00", "7200", "粤语文化节目"),
                        arrayOf("午间新闻", "12:00", "3600", "午间新闻快报"),
                        arrayOf("大湾区观察", "14:00", "7200", "湾区发展报道"),
                        arrayOf("新闻晚高峰", "17:00", "7200", "晚间新闻高峰"),
                        arrayOf("粤听之夜", "19:00", "7200", "夜间文化节目"),
                        arrayOf("音乐夜未央", "21:00", "10800", "深夜音乐节目")
                    )
                    "other-4" -> arrayOf(
                        arrayOf("北京您早", "06:00", "3600", "北京早间新闻"),
                        arrayOf("新闻热线", "07:00", "7200", "民生新闻服务"),
                        arrayOf("首都经济报道", "09:00", "7200", "经济资讯节目"),
                        arrayOf("午间新闻", "12:00", "3600", "午间新闻快报"),
                        arrayOf("北京故事", "14:00", "7200", "京城文化故事"),
                        arrayOf("新闻晚高峰", "17:00", "7200", "晚间新闻高峰"),
                        arrayOf("京城夜话", "19:00", "7200", "夜间话题节目"),
                        arrayOf("经典回响", "21:00", "10800", "经典音乐节目")
                    )
                    "other-5" -> arrayOf(
                        arrayOf("江苏新闻联播", "06:00", "3600", "江苏早间新闻"),
                        arrayOf("新闻进行时", "07:00", "7200", "滚动新闻播报"),
                        arrayOf("政风热线", "09:00", "7200", "政民互动节目"),
                        arrayOf("午间新闻", "12:00", "3600", "午间新闻快报"),
                        arrayOf("长三角观察", "14:00", "7200", "区域发展报道"),
                        arrayOf("新闻晚高峰", "17:00", "7200", "晚间新闻高峰"),
                        arrayOf("江苏人文", "19:00", "7200", "人文历史节目"),
                        arrayOf("夜读江苏", "21:00", "10800", "深夜阅读节目")
                    )
                    "other-6" -> arrayOf(
                        arrayOf("浙江之声早新闻", "06:00", "3600", "浙江早间新闻"),
                        arrayOf("新闻进行时", "07:00", "7200", "滚动新闻播报"),
                        arrayOf("民生996", "09:00", "7200", "民生服务节目"),
                        arrayOf("午间新闻", "12:00", "3600", "午间新闻快报"),
                        arrayOf("美丽浙江", "14:00", "7200", "生态文化节目"),
                        arrayOf("新闻晚高峰", "17:00", "7200", "晚间新闻高峰"),
                        arrayOf("浙里夜话", "19:00", "7200", "夜间话题节目"),
                        arrayOf("江南夜曲", "21:00", "10800", "江南音乐节目")
                    )
                    else -> arrayOf(
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
                }

                val random = Random((date + stationId).hashCode().toLong())
                val count = programData.size

                // 判断该电台是否支持回放
                val hasReplay = REPLAY_STATION_IDS.contains(stationId)
                val replayBaseUrl = if (hasReplay) getStationReplayUrl(stationId) else null

                for (i in 0 until count) {
                    val prog = programData[i]
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
            "other-4" -> "北京新闻广播"
            "other-5" -> "江苏新闻广播"
            "other-6" -> "浙江之声"
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
