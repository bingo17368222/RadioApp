package com.radio.app.managers;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.radio.app.models.Transcript;
import com.radio.app.services.SubtitleGeneratorService;

import java.util.List;

public class SubtitleManager {
    private Context context;
    private SubtitleGeneratorService service;
    private boolean bound = false;

    private ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder s) {
            service = ((SubtitleGeneratorService.LocalBinder) s).getService();
            bound = true;
        }
        @Override
        public void onServiceDisconnected(ComponentName name) { service = null; bound = false; }
    };

    public SubtitleManager(Context ctx) {
        context = ctx.getApplicationContext();
        ctx.bindService(new Intent(ctx, SubtitleGeneratorService.class), conn, Context.BIND_AUTO_CREATE);
    }

    public void generateSubtitles(String epId, String url, SubtitleGeneratorService.SubtitleCallback cb) {
        if (bound && service != null) service.generateSubtitlesForEpisode(epId, url, cb);
        else cb.onError("服务未就绪");
    }

    public List<Transcript> getSubtitles(String epId) { return bound && service != null ? service.getSubtitles(epId) : null; }
    public boolean isOfflineAvailable() { return bound && service != null && service.isOfflineAvailable(); }
    public void release() { if (bound) { context.unbindService(conn); bound = false; } }
}
