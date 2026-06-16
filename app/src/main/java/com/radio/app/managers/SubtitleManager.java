package com.radio.app.managers;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import com.radio.app.models.Transcript;
import com.radio.app.services.SubtitleGeneratorService;

import java.util.List;

public class SubtitleManager {
    private static final String TAG = "SubtitleManager";
    private Context context;
    private SubtitleGeneratorService service;
    private boolean bound = false;
    private boolean unbinding = false;

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
            cb.onError("服务未就绪");
        }
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
