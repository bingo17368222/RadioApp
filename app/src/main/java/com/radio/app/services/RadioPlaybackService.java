package com.radio.app.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.radio.app.R;
import com.radio.app.RadioApplication;
import com.radio.app.activities.PlayerActivity;
import com.radio.app.models.Episode;
import com.radio.app.models.RadioStation;

public class RadioPlaybackService extends Service implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener {
    public static final String ACTION_PLAY = "com.radio.app.PLAY";
    public static final String ACTION_PAUSE = "com.radio.app.PAUSE";
    public static final String ACTION_STOP = "com.radio.app.STOP";

    private MediaPlayer player;
    private final IBinder binder = new LocalBinder();
    private Episode currentEpisode;
    private RadioStation currentStation;
    private boolean isLive = false;
    private Callback callback;

    public interface Callback {
        void onStateChanged(boolean playing);
        void onPositionChanged(long position, long duration);
    }

    public class LocalBinder extends Binder {
        public RadioPlaybackService getService() { return RadioPlaybackService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY: play(); break;
                case ACTION_PAUSE: pause(); break;
                case ACTION_STOP: stop(); break;
            }
        }
        return START_STICKY;
    }

    public void playStation(RadioStation station) {
        this.currentStation = station;
        this.currentEpisode = null;
        this.isLive = true;
        try {
            player.reset();
            player.setDataSource(station.getStreamUrl());
            player.prepareAsync();
            startForegroundNotification();
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void playEpisode(Episode episode, boolean live) {
        this.currentEpisode = episode;
        this.currentStation = null;
        this.isLive = live;
        try {
            player.reset();
            player.setDataSource(episode.getAudioUrl());
            player.prepareAsync();
            startForegroundNotification();
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void play() { if (player != null && !player.isPlaying()) player.start(); }
    public void pause() { if (player != null && player.isPlaying()) player.pause(); }
    public void stop() { if (player != null) { player.stop(); stopForeground(true); stopSelf(); } }
    public void seekTo(long pos) { if (player != null && !isLive) player.seekTo((int) pos); }
    public void skipForward() { long p = player.getCurrentPosition() + 15000; if (p < player.getDuration()) player.seekTo((int) p); }
    public void skipBackward() { player.seekTo(Math.max(0, player.getCurrentPosition() - 15000)); }
    public boolean isPlaying() { return player != null && player.isPlaying(); }
    public boolean isLive() { return isLive; }
    public long getCurrentPosition() { return player != null ? player.getCurrentPosition() : 0; }
    public long getDuration() { return player != null ? player.getDuration() : 0; }
    public Episode getCurrentEpisode() { return currentEpisode; }
    public RadioStation getCurrentStation() { return currentStation; }
    public void setCallback(Callback cb) { this.callback = cb; }

    private void startForegroundNotification() {
        Intent openIntent = new Intent(this, PlayerActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = currentEpisode != null ? currentEpisode.getTitle() : (currentStation != null ? currentStation.getName() : "Radio App");

        Notification notification = new NotificationCompat.Builder(this, RadioApplication.CHANNEL_ID)
                .setContentTitle(title)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();

        startForeground(1, notification);
    }

    @Override
    public void onPrepared(MediaPlayer mp) { mp.start(); if (callback != null) callback.onStateChanged(true); }
    @Override
    public void onCompletion(MediaPlayer mp) { if (callback != null) callback.onStateChanged(false); }

    @Override
    public IBinder onBind(Intent intent) { return binder; }
    @Override
    public void onDestroy() { super.onDestroy(); if (player != null) { player.release(); player = null; } }
}
