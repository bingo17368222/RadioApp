package com.radio.app.network;

import com.radio.app.models.Episode;
import com.radio.app.models.SearchResult;
import com.radio.app.models.Transcript;
import com.radio.app.models.VoiceSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EpisodeApiService {
    private static volatile EpisodeApiService instance;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // 节目回放使用真实电台的M3U8流URL（模拟回放模式）
    private static final String[] STATION_STREAM_URLS = {
        "https://stream.hndt.com/live/xinwen/playlist.m3u8",   // henan-1 河南新闻广播
        "https://stream.hndt.com/live/yinyue/playlist.m3u8",   // henan-2 河南音乐广播
        "https://stream.hndt.com/live/jiaotong/playlist.m3u8", // henan-3 河南交通广播
        "https://stream.hndt.com/live/jingji/playlist.m3u8",  // henan-4 河南经济广播
        "http://ls.qingting.fm/live/5022051.m3u8",            // henan-5 郑州新闻广播
        "http://ls.qingting.fm/live/5022055.m3u8",            // henan-6 洛阳交通广播
        "https://ngcdn001.cnr.cn/live/zgzs/index.m3u8",       // cnr-1 中国之声
        "https://ngcdn002.cnr.cn/live/jjzs/index.m3u8",       // cnr-2 经济之声
        "http://live.xmcdn.com/live/95/64.m3u8",              // cnr-3 音乐之声
        "http://live.xmcdn.com/live/91/64.m3u8",              // other-1 北京新闻广播
        "http://ls.qingting.fm/live/270.m3u8",               // other-2 上海新闻广播
        "http://ls.qingting.fm/live/1260.m3u8"               // other-3 广东新闻广播
    };

    private static final String[] STATION_IDS = {
        "henan-1", "henan-2", "henan-3", "henan-4", "henan-5", "henan-6",
        "cnr-1", "cnr-2", "cnr-3", "other-1", "other-2", "other-3"
    };

    public interface ApiCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public static EpisodeApiService getInstance() {
        if (instance == null) {
            synchronized (EpisodeApiService.class) {
                if (instance == null) instance = new EpisodeApiService();
            }
        }
        return instance;
    }

    /**
     * Get episodes for a given station and date (simulated data, 8-12 episodes).
     */
    public void getEpisodesByDate(String stationId, String date, ApiCallback<List<Episode>> callback) {
        executor.execute(() -> {
            try {
                List<Episode> episodes = new ArrayList<>();
                String[][] programData = {
                    {"早间新闻", "06:00", "3600", "播报国内外重要新闻、天气预报和交通信息。"},
                    {"新闻纵横", "08:00", "5400", "深度报道时政要闻，解读政策走向。"},
                    {"天下财经", "10:00", "3600", "聚焦股市行情、经济动态和理财知识。"},
                    {"新闻和报纸摘要", "12:00", "1800", "午间新闻快报，精选报纸头条。"},
                    {"直播中国", "14:00", "7200", "连线全国各地，展现中国发展面貌。"},
                    {"体育直播间", "16:00", "3600", "体育赛事直播、战报和运动员访谈。"},
                    {"音乐之声", "18:00", "3600", "经典歌曲欣赏，音乐人和乐迷互动。"},
                    {"文化之旅", "20:00", "5400", "探索中华文化遗产，讲述历史故事。"},
                    {"法治在线", "22:00", "3600", "法律知识普及，典型案例分析。"},
                    {"健康之路", "23:00", "3600", "医学专家答疑，健康养生知识。"}
                };

                Random random = new Random(date.hashCode() + stationId.hashCode());
                int count = 6 + random.nextInt(5);

                for (int i = 0; i < count; i++) {
                    String[] prog = programData[i % programData.length];
                    Episode ep = new Episode();
                    ep.setId(stationId + "-" + date + "-" + i);
                    ep.setTitle(prog[0]);
                    ep.setBroadcastAt(date + "T" + prog[1] + ":00");
                    ep.setDuration(Long.parseLong(prog[2]));
                    ep.setDescription(prog[3]);
                    ep.setStationId(stationId);
                    ep.setStationName(getStationName(stationId));
                    // 使用该电台的直播流URL模拟回放
                    String streamUrl = getStationStreamUrl(stationId);
                    ep.setAudioUrl(streamUrl != null ? streamUrl : STATION_STREAM_URLS[0]);
                    ep.setLive(false);
                    ep.setDisliked(false);
                    // 标注为回放模式，添加时间偏移信息
                    ep.setDescription(prog[3] + " [回放模式]");

                    // Generate voice segments (simulated dry/water analysis)
                    List<VoiceSegment> segments = new ArrayList<>();
                    int segCount = 8 + random.nextInt(8);
                    long segDuration = ep.getDuration() * 1000 / segCount;
                    for (int j = 0; j < segCount; j++) {
                        VoiceSegment seg = new VoiceSegment();
                        seg.setStart(j * segDuration);
                        seg.setEnd((j + 1) * segDuration);
                        seg.setHasVoice(random.nextBoolean());
                        seg.setLabel(seg.isHasVoice() ? "干货内容" : "水分片段");
                        seg.setManuallyMarked(false);
                        segments.add(seg);
                    }
                    ep.setVoiceSegments(segments);

                    // Generate transcripts (simulated subtitles)
                    List<Transcript> transcripts = new ArrayList<>();
                    for (int j = 0; j < segCount; j++) {
                        Transcript t = new Transcript();
                        t.setStartTime(j * segDuration);
                        t.setEndTime((j + 1) * segDuration);
                        t.setText("【" + prog[0] + "第" + (j + 1) + "段】" + getSampleText(random, j));
                        t.setConfidence(0.8 + random.nextDouble() * 0.2);
                        transcripts.add(t);
                    }
                    ep.setTranscripts(transcripts);

                    episodes.add(ep);
                }
                callback.onSuccess(episodes);
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        });
    }

    private String getStationName(String stationId) {
        switch (stationId) {
            case "henan-1": return "河南新闻广播";
            case "henan-2": return "河南音乐广播";
            case "henan-3": return "河南交通广播";
            case "henan-4": return "河南经济广播";
            case "henan-5": return "郑州新闻广播";
            case "henan-6": return "洛阳交通广播";
            case "cnr-1": return "中国之声";
            case "cnr-2": return "经济之声";
            case "cnr-3": return "音乐之声";
            case "other-1": return "北京新闻广播";
            case "other-2": return "上海新闻广播";
            case "other-3": return "广东新闻广播";
            default: return "电台节目";
        }
    }

    private String getStationStreamUrl(String stationId) {
        for (int i = 0; i < STATION_IDS.length; i++) {
            if (STATION_IDS[i].equals(stationId)) {
                return STATION_STREAM_URLS[i];
            }
        }
        return null;
    }

    private String getSampleText(Random random, int index) {
        String[] samples = {
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
        };
        return samples[index % samples.length];
    }

    /**
     * Search episodes and transcripts.
     */
    public void search(String query, ApiCallback<List<SearchResult>> callback) {
        executor.execute(() -> {
            try {
                List<SearchResult> results = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    SearchResult r = new SearchResult();
                    r.setId("search-" + i);
                    r.setTitle("搜索结果: " + query + " - 节目" + (i + 1));
                    r.setType("episode");
                    r.setStationName("河南新闻广播");
                    r.setMatchedText("这是包含'" + query + "'的匹配文本内容...");
                    results.add(r);
                }
                callback.onSuccess(results);
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        });
    }
}
