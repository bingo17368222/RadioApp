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
                String[] programNames = {"早间新闻", "新闻纵横", "天下财经", "新闻和报纸摘要",
                    "直播中国", "体育直播间", "音乐之声", "文化之旅", "法治在线", "健康之路"};
                Random random = new Random(date.hashCode());
                int count = 8 + random.nextInt(5);
                for (int i = 0; i < count; i++) {
                    Episode ep = new Episode();
                    ep.setId(stationId + "-" + date + "-" + i);
                    ep.setTitle(programNames[i % programNames.length]);
                    ep.setBroadcastAt(date + "T" + String.format("%02d:00:00", 6 + i * 2));
                    ep.setDuration(3600 + random.nextInt(1800));
                    ep.setStationId(stationId);
                    ep.setStationName("河南新闻广播");
                    ep.setAudioUrl("https://example.com/audio/" + ep.getId() + ".mp3");
                    ep.setLive(false);
                    ep.setDisliked(false);

                    // Generate voice segments (simulated dry/water analysis)
                    List<VoiceSegment> segments = new ArrayList<>();
                    int segCount = 10 + random.nextInt(10);
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
                        t.setText("这是第" + (j + 1) + "段的字幕内容，包含模拟的语音识别结果。");
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
