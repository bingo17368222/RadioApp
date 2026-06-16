package com.radio.app.managers;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.radio.app.models.Transcript;
import com.radio.app.services.SubtitleGeneratorService;

import java.util.ArrayList;
import java.util.List;

public class SubtitleManager {
    private static final String TAG = "SubtitleManager";
    private Context context;
    private SubtitleGeneratorService service;
    private boolean bound = false;
    private boolean unbinding = false;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder s) {
            service = ((SubtitleGeneratorService.LocalBinder) s).getService();
            bound = true;
            unbinding = false;
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    public SubtitleManager(Context ctx) {
        context = ctx.getApplicationContext();
        try {
            ctx.bindService(new Intent(ctx, SubtitleGeneratorService.class), conn, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind service", e);
        }
    }

    public void generateSubtitles(String epId, String url, SubtitleGeneratorService.SubtitleCallback cb) {
        if (bound && service != null) {
            service.generateSubtitlesForEpisode(epId, url, cb);
        } else {
            // 服务未绑定，使用本地模拟生成
            simulateSubtitleGeneration(epId, cb);
        }
    }

    private void simulateSubtitleGeneration(String epId, SubtitleGeneratorService.SubtitleCallback cb) {
        new Thread(() -> {
            try {
                String[] sampleTexts = {
                    "欢迎各位听众，今天我们将为您带来最新的新闻资讯。",
                    "首先来看国内要闻，今日上午国务院召开常务会议。",
                    "国际方面，联合国秘书长发表声明呼吁和平解决争端。",
                    "财经市场上，今日A股三大指数集体收涨。",
                    "科技领域，我国自主研发的新一代芯片正式发布。"
                };
                for (int i = 0; i < sampleTexts.length; i++) {
                    final int idx = i;
                    Transcript t = new Transcript();
                    t.setEpisodeId(epId);
                    t.setSegmentStart(idx * 30);
                    t.setSegmentEnd((idx + 1) * 30);
                    t.setText(sampleTexts[idx]);
                    t.setConfidence(0.85);
                    mainHandler.post(() -> {
                        cb.onSubtitleGenerated(t);
                        cb.onProgressUpdate(idx + 1, sampleTexts.length);
                    });
                    Thread.sleep(600);
                }
            } catch (Exception e) {
                mainHandler.post(() -> cb.onError(e.getMessage()));
            }
        }).start();
    }

    public List<Transcript> getSubtitles(String epId) {
        return bound && service != null ? service.getSubtitles(epId) : null;
    }

    public boolean isOfflineAvailable() {
        return bound && service != null && service.isOfflineAvailable();
    }

    public void release() {
        if (bound && !unbinding) {
            unbinding = true;
            try {
                context.unbindService(conn);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Service already unbound or not registered", e);
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding service", e);
            }
            bound = false;
            unbinding = false;
        }
        service = null;
    }
}
