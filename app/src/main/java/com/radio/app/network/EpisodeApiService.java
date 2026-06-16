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
    private ExecutorService executor;
    private Random random;

    private static final String[] MOCK_PROGRAM_NAMES = {
            "早间新闻", "新闻纵横", "天下财经", "环球资讯",
            "法治在线", "健康之路", "读书时间", "音乐漫步",
            "体育直播间", "科技前沿", "文化之旅", "生活百科",
            "交通早班车", "午间新闻", "财经评论", "城市之声",
            "晚间报道", "夜话人生", "影视同期声", "美食地图"
    };

    private static final String[] MOCK_DESCRIPTIONS = {
            "今日国内外重要新闻汇总与分析",
            "深入解读经济形势与市场动态",
            "关注民生热点，倾听百姓心声",
            "科技改变生活，创新引领未来",
            "品味经典文学，感受文字魅力",
            "追踪体育赛事，分享运动激情",
            "法律知识普及，案例分析解读",
            "健康养生指南，专家在线答疑"
    };

    private static final String[] MOCK_TRANSCRIPT_LINES = {
            "各位听众朋友大家好，欢迎收听今天的节目。",
            "今天我们要和大家聊一个非常有趣的话题。",
            "根据最新的数据显示，这个领域的增长速度非常快。",
            "专家表示，未来几年这个趋势将会持续下去。",
            "让我们先来看一下具体的数据分析。",
            "从统计图表中可以看出，整体呈现上升趋势。",
            "这个发现对于行业发展具有重要的指导意义。",
            "接下来我们请到了一位业内资深人士来为大家解读。",
            "好的，感谢您的精彩分享，让我们受益匪浅。",
            "以上就是今天节目的全部内容，感谢大家的收听。",
            "下面请听一段优美的音乐。",
            "广告时间，稍后继续。",
            "欢迎回来，我们继续今天的话题讨论。",
            "据可靠消息透露，相关部门已经介入调查。",
            "这个事件引发了社会各界的广泛关注。",
            "我们来看一下现场记者发回的报道。",
            "目前情况已经得到了有效控制。",
            "希望大家能够提高警惕，注意安全。",
            "今天的天气情况就介绍到这里。",
            "让我们一起来关注一下最新的政策动向。"
    };

    private EpisodeApiService() {
        executor = Executors.newCachedThreadPool();
        random = new Random();
    }

    public static EpisodeApiService getInstance() {
        if (instance == null) {
            synchronized (EpisodeApiService.class) {
                if (instance == null) {
                    instance = new EpisodeApiService();
                }
            }
        }
        return instance;
    }

    public interface ApiCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    /**
     * 获取某日期节目列表（模拟数据）
     * @param stationId 电台ID
     * @param date 日期，格式 yyyy-MM-dd
     * @param callback 回调
     */
    public void getEpisodesByDate(String stationId, String date, ApiCallback<List<Episode>> callback) {
        executor.execute(() -> {
            try {
                // 模拟网络延迟
                Thread.sleep(300 + random.nextInt(500));

                int episodeCount = 8 + random.nextInt(5); // 8-12个节目
                List<Episode> episodes = new ArrayList<>();

                // 生成电台名称
                String stationName = getStationName(stationId);

                for (int i = 0; i < episodeCount; i++) {
                    Episode episode = new Episode();
                    episode.setId(stationId + "-" + date + "-" + i);
                    episode.setTitle(MOCK_PROGRAM_NAMES[i % MOCK_PROGRAM_NAMES.length]);
                    episode.setStationId(stationId);
                    episode.setStationName(stationName);
                    episode.setBroadcastAt(date + "T" + String.format("%02d:00:00", 6 + i * 2));
                    episode.setDuration(1800 + random.nextInt(1800)); // 30-60分钟
                    episode.setDescription(MOCK_DESCRIPTIONS[i % MOCK_DESCRIPTIONS.length]);
                    episode.setAudioUrl("https://mock-radio.example.com/audio/" + episode.getId() + ".mp3");
                    episode.setLive(false);
                    episode.setDisliked(false);
                    episode.setCached(false);

                    // 生成语音分段（模拟干货/水分分析）
                    List<VoiceSegment> voiceSegments = generateVoiceSegments(episode.getDuration());
                    episode.setVoiceSegments(voiceSegments);

                    // 生成字幕（模拟）
                    List<Transcript> transcripts = generateTranscripts(episode.getId(), voiceSegments);
                    episode.setTranscripts(transcripts);

                    episodes.add(episode);
                }

                callback.onSuccess(episodes);
            } catch (Exception e) {
                e.printStackTrace();
                callback.onError("获取节目列表失败: " + e.getMessage());
            }
        });
    }

    /**
     * 搜索（模拟）
     * @param query 搜索关键词
     * @param callback 回调
     */
    public void search(String query, ApiCallback<List<SearchResult>> callback) {
        executor.execute(() -> {
            try {
                // 模拟网络延迟
                Thread.sleep(200 + random.nextInt(300));

                List<SearchResult> results = new ArrayList<>();

                // 模拟搜索结果 - 生成匹配的节目
                int matchCount = 3 + random.nextInt(8);
                for (int i = 0; i < matchCount; i++) {
                    SearchResult result = new SearchResult();

                    if (random.nextBoolean()) {
                        // 节目匹配
                        result.setType(SearchResult.Type.EPISODE);
                        Episode episode = new Episode();
                        episode.setId("search-ep-" + i);
                        episode.setTitle(MOCK_PROGRAM_NAMES[random.nextInt(MOCK_PROGRAM_NAMES.length)] + " - " + query);
                        episode.setStationName(getStationName("search-station-" + random.nextInt(5)));
                        episode.setDescription("包含关键词「" + query + "」的相关节目内容");
                        episode.setBroadcastAt("2025-01-" + String.format("%02d", 1 + random.nextInt(28)) + "T08:00:00");
                        episode.setDuration(1800 + random.nextInt(1800));
                        result.setEpisode(episode);
                    } else {
                        // 字幕匹配
                        result.setType(SearchResult.Type.TRANSCRIPT);
                        Episode episode = new Episode();
                        episode.setId("search-ep-" + i);
                        episode.setTitle(MOCK_PROGRAM_NAMES[random.nextInt(MOCK_PROGRAM_NAMES.length)]);
                        episode.setStationName(getStationName("search-station-" + random.nextInt(5)));

                        Transcript transcript = new Transcript();
                        transcript.setEpisodeId(episode.getId());
                        transcript.setSegmentStart(random.nextInt(3000));
                        transcript.setSegmentEnd(transcript.getSegmentStart() + 30 + random.nextInt(60));
                        transcript.setText("在这段讨论中提到了关于「" + query + "」的重要内容，专家指出...");

                        result.setEpisode(episode);
                        result.setTranscript(transcript);
                        result.setMatchedText(transcript.getText());
                    }

                    results.add(result);
                }

                callback.onSuccess(results);
            } catch (Exception e) {
                e.printStackTrace();
                callback.onError("搜索失败: " + e.getMessage());
            }
        });
    }

    /**
     * 获取单个节目详情（模拟）
     */
    public void getEpisodeDetail(String episodeId, ApiCallback<Episode> callback) {
        executor.execute(() -> {
            try {
                Thread.sleep(200 + random.nextInt(300));

                Episode episode = new Episode();
                episode.setId(episodeId);
                episode.setTitle(MOCK_PROGRAM_NAMES[random.nextInt(MOCK_PROGRAM_NAMES.length)]);
                episode.setBroadcastAt("2025-01-15T08:00:00");
                episode.setDuration(3600);
                episode.setDescription("这是一个模拟的节目详情");
                episode.setStationId("station-1");
                episode.setStationName("河南新闻广播");
                episode.setAudioUrl("https://mock-radio.example.com/audio/" + episodeId + ".mp3");
                episode.setLive(false);
                episode.setDisliked(false);
                episode.setCached(false);

                List<VoiceSegment> voiceSegments = generateVoiceSegments(episode.getDuration());
                episode.setVoiceSegments(voiceSegments);

                List<Transcript> transcripts = generateTranscripts(episodeId, voiceSegments);
                episode.setTranscripts(transcripts);

                callback.onSuccess(episode);
            } catch (Exception e) {
                e.printStackTrace();
                callback.onError("获取节目详情失败: " + e.getMessage());
            }
        });
    }

    /**
     * 生成模拟的语音分段
     */
    private List<VoiceSegment> generateVoiceSegments(long durationSeconds) {
        List<VoiceSegment> segments = new ArrayList<>();
        long currentStart = 0;
        int segmentIndex = 0;

        while (currentStart < durationSeconds) {
            // 每段30-120秒
            long segmentDuration = 30 + random.nextInt(91);
            long segmentEnd = Math.min(currentStart + segmentDuration, durationSeconds);

            // 模拟干货/水分判断（约60%概率为干货）
            boolean hasVoice = random.nextDouble() < 0.6;
            String label = hasVoice ? "干货内容" : "水分片段";

            VoiceSegment segment = new VoiceSegment(
                    currentStart, segmentEnd, hasVoice, label, false
            );
            segments.add(segment);

            currentStart = segmentEnd;
            segmentIndex++;
        }

        return segments;
    }

    /**
     * 生成模拟字幕
     */
    private List<Transcript> generateTranscripts(String episodeId, List<VoiceSegment> voiceSegments) {
        List<Transcript> transcripts = new ArrayList<>();

        for (VoiceSegment segment : voiceSegments) {
            // 每个语音段生成1-3条字幕
            int lineCount = 1 + random.nextInt(3);
            long lineDuration = (segment.getEnd() - segment.getStart()) / lineCount;

            for (int i = 0; i < lineCount; i++) {
                long lineStart = segment.getStart() + i * lineDuration;
                long lineEnd = (i == lineCount - 1) ? segment.getEnd() : lineStart + lineDuration;

                String text = MOCK_TRANSCRIPT_LINES[random.nextInt(MOCK_TRANSCRIPT_LINES.length)];
                Transcript transcript = new Transcript(episodeId, lineStart, lineEnd, text);
                transcripts.add(transcript);
            }
        }

        return transcripts;
    }

    /**
     * 根据电台ID获取模拟电台名称
     */
    private String getStationName(String stationId) {
        if (stationId == null) return "未知电台";
        if (stationId.contains("henan")) return "河南新闻广播";
        if (stationId.contains("cnr")) return "中国之声";
        if (stationId.contains("music")) return "音乐之声";
        if (stationId.contains("traffic")) return "交通广播";
        if (stationId.contains("search")) return "搜索结果电台";
        return "广播电台";
    }

    /**
     * 关闭线程池
     */
    public void shutdown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}
