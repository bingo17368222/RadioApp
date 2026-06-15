package com.radio.app.services;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import com.radio.app.database.RadioDatabaseHelper;
import com.radio.app.models.Transcript;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SubtitleGeneratorService extends Service {
    private final IBinder binder = new LocalBinder();
    private ExecutorService executor;
    private RadioDatabaseHelper dbHelper;

    public interface SubtitleCallback {
        void onSubtitleGenerated(Transcript transcript);
        void onProgressUpdate(int progress, int total);
        void onError(String error);
    }

    public class LocalBinder extends Binder {
        public SubtitleGeneratorService getService() { return SubtitleGeneratorService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        dbHelper = RadioDatabaseHelper.getInstance(this);
    }

    public void generateSubtitlesForEpisode(String episodeId, String audioUrl, SubtitleCallback callback) {
        executor.execute(() -> {
            try {
                for (int i = 0; i < 5; i++) {
                    Transcript t = new Transcript();
                    t.setEpisodeId(episodeId);
                    t.setSegmentStart(i * 30);
                    t.setSegmentEnd((i + 1) * 30);
                    t.setText("[字幕片段 " + (i + 1) + " - 需要接入ASR服务]");
                    dbHelper.saveTranscript(t);
                    callback.onSubtitleGenerated(t);
                    callback.onProgressUpdate(i + 1, 5);
                    Thread.sleep(500);
                }
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        });
    }

    public List<Transcript> getSubtitles(String episodeId) {
        return dbHelper.getTranscripts(episodeId);
    }

    public boolean isOfflineAvailable() { return false; }

    @Override
    public IBinder onBind(Intent intent) { return binder; }
    @Override
    public void onDestroy() { super.onDestroy(); executor.shutdown(); }
}
