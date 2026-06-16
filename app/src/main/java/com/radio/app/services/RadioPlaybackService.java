package com.radio.app.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.radio.app.R;
import com.radio.app.RadioApplication;
import com.radio.app.activities.PlayerActivity;
import com.radio.app.database.RadioDatabaseHelper;
import com.radio.app.models.AppSettings;
import com.radio.app.models.Episode;
import com.radio.app.models.RadioStation;
import com.radio.app.models.VoiceSegment;
import com.radio.app.utils.PreferenceManager;

import java.util.List;

public class RadioPlaybackService extends Service implements
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener,
        MediaPlayer.OnBufferingUpdateListener,
        AudioManager.OnAudioFocusChangeListener {

    public static final String ACTION_PLAY = "com.radio.app.PLAY";
    public static final String ACTION_PAUSE = "com.radio.app.PAUSE";
    public static final String ACTION_STOP = "com.radio.app.STOP";
    public static final String ACTION_PREV_SEGMENT = "com.radio.app.PREV_SEGMENT";
    public static final String ACTION_NEXT_SEGMENT = "com.radio.app.NEXT_SEGMENT";
    public static final String ACTION_REWIND = "com.radio.app.REWIND";
    public static final String ACTION_FORWARD = "com.radio.app.FORWARD";

    public static final String BROADCAST_BUFFER_UPDATE = "com.radio.app.BUFFER_UPDATE";
    public static final String BROADCAST_STATE_CHANGED = "com.radio.app.STATE_CHANGED";
    public static final String EXTRA_BUFFER_PERCENT = "buffer_percent";
    public static final String EXTRA_IS_PLAYING = "is_playing";

    private MediaPlayer player;
    private final IBinder binder = new LocalBinder();
    private Episode currentEpisode;
    private RadioStation currentStation;
    private boolean isLive = false;
    private Callback callback;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private Handler autoSkipHandler;
    private Runnable autoSkipRunnable;
    private boolean continuousPlay = true;
    private int bufferPercent = 0;

    public interface Callback {
        void onStateChanged(boolean playing);
        void onPositionChanged(long position, long duration);
        void onBufferUpdate(int percent);
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
        player.setOnPreparedListener(this);
        player.setOnCompletionListener(this);
        player.setOnBufferingUpdateListener(this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        autoSkipHandler = new Handler(Looper.getMainLooper());
        loadSettings();
    }

    private void loadSettings() {
        PreferenceManager prefMgr = new PreferenceManager(this);
        AppSettings settings = prefMgr.loadSettings();
        continuousPlay = settings.isContinuousPlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY: play(); break;
                case ACTION_PAUSE: pause(); break;
                case ACTION_STOP: stop(); break;
                case ACTION_PREV_SEGMENT: jumpToPrevSegment(); break;
                case ACTION_NEXT_SEGMENT: jumpToNextSegment(); break;
                case ACTION_REWIND: skipBackward(); break;
                case ACTION_FORWARD: skipForward(); break;
            }
        }
        return START_STICKY;
    }

    // ===== Audio Focus =====

    private boolean requestAudioFocus() {
        if (audioManager == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setWillPauseWhenDucked(true)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(this)
                    .build();
            audioFocusRequest = request;
            return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            //noinspection deprecation
            return audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            //noinspection deprecation
            audioManager.abandonAudioFocus(this);
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                play();
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (player != null && player.isPlaying()) {
                    player.setVolume(0.3f, 0.3f);
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT:
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK:
                if (player != null) {
                    player.setVolume(1.0f, 1.0f);
                    play();
                }
                break;
        }
    }

    // ===== Playback Controls =====

    public void playStation(RadioStation station) {
        this.currentStation = station;
        this.currentEpisode = null;
        this.isLive = true;
        stopAutoSkipCheck();
        try {
            player.reset();
            player.setDataSource(station.getStreamUrl());
            player.prepareAsync();
            requestAudioFocus();
            startForegroundNotification();
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void playEpisode(Episode episode, boolean live) {
        this.currentEpisode = episode;
        this.currentStation = null;
        this.isLive = live;
        stopAutoSkipCheck();
        try {
            player.reset();
            player.setDataSource(episode.getAudioUrl());
            player.prepareAsync();
            requestAudioFocus();
            startForegroundNotification();
            startAutoSkipCheck();
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void play() {
        if (player != null && !player.isPlaying()) {
            player.start();
            if (callback != null) callback.onStateChanged(true);
            sendStateBroadcast(true);
            startForegroundNotification();
        }
    }

    public void pause() {
        if (player != null && player.isPlaying()) {
            player.pause();
            if (callback != null) callback.onStateChanged(false);
            sendStateBroadcast(false);
            startForegroundNotification();
        }
    }

    public void stop() {
        stopAutoSkipCheck();
        if (player != null) {
            player.stop();
            abandonAudioFocus();
            stopForeground(true);
            stopSelf();
        }
    }

    public void seekTo(long pos) {
        if (player != null && !isLive) player.seekTo((int) pos);
    }

    public void skipForward() {
        if (player == null || isLive) return;
        long p = player.getCurrentPosition() + 15000;
        long dur = player.getDuration();
        if (p > dur) p = dur;
        player.seekTo((int) p);
    }

    public void skipBackward() {
        if (player == null || isLive) return;
        player.seekTo(Math.max(0, player.getCurrentPosition() - 15000));
    }

    public boolean isPlaying() { return player != null && player.isPlaying(); }
    public boolean isLive() { return isLive; }
    public long getCurrentPosition() { return player != null ? player.getCurrentPosition() : 0; }
    public long getDuration() { return player != null ? player.getDuration() : 0; }
    public Episode getCurrentEpisode() { return currentEpisode; }
    public RadioStation getCurrentStation() { return currentStation; }
    public int getBufferPercent() { return bufferPercent; }
    public void setCallback(Callback cb) { this.callback = cb; }

    // ===== Segment Navigation =====

    public void jumpToNextSegment() {
        if (currentEpisode == null || currentEpisode.getVoiceSegments() == null) return;
        List<VoiceSegment> segments = currentEpisode.getVoiceSegments();
        long currentPos = player != null ? player.getCurrentPosition() : 0;
        for (int i = 0; i < segments.size(); i++) {
            VoiceSegment seg = segments.get(i);
            if (seg.getStart() > currentPos) {
                seekTo(seg.getStart());
                return;
            }
        }
    }

    public void jumpToPrevSegment() {
        if (currentEpisode == null || currentEpisode.getVoiceSegments() == null) return;
        List<VoiceSegment> segments = currentEpisode.getVoiceSegments();
        long currentPos = player != null ? player.getCurrentPosition() : 0;
        VoiceSegment prev = null;
        for (int i = 0; i < segments.size(); i++) {
            VoiceSegment seg = segments.get(i);
            if (seg.getEnd() >= currentPos) {
                if (i > 0) prev = segments.get(i - 1);
                break;
            }
        }
        if (prev != null) {
            seekTo(prev.getStart());
        } else if (!segments.isEmpty()) {
            seekTo(segments.get(0).getStart());
        }
    }

    // ===== Manual Marking =====

    public void markSegment(int index, boolean isDry) {
        if (currentEpisode == null || currentEpisode.getVoiceSegments() == null) return;
        List<VoiceSegment> segments = currentEpisode.getVoiceSegments();
        if (index < 0 || index >= segments.size()) return;
        VoiceSegment seg = segments.get(index);
        seg.setManuallyMarked(true);
        seg.setHasVoice(isDry);
        seg.setLabel(isDry ? "手动标记:干货" : "手动标记:水分");
        // Persist to database
        RadioDatabaseHelper dbHelper = RadioDatabaseHelper.getInstance(this);
        dbHelper.saveManualSegmentMark(currentEpisode.getId(), seg.getStart(), seg.getEnd(), isDry);
    }

    public void setSkipThisTime(int index, boolean skip) {
        if (currentEpisode == null || currentEpisode.getVoiceSegments() == null) return;
        List<VoiceSegment> segments = currentEpisode.getVoiceSegments();
        if (index < 0 || index >= segments.size()) return;
        segments.get(index).setSkipThisTime(skip);
    }

    // ===== Auto-skip Water Segments =====

    private void startAutoSkipCheck() {
        stopAutoSkipCheck();
        autoSkipRunnable = () -> {
            if (player != null && player.isPlaying() && currentEpisode != null
                    && currentEpisode.getVoiceSegments() != null && !isLive) {
                long currentPos = player.getCurrentPosition();
                for (VoiceSegment seg : currentEpisode.getVoiceSegments()) {
                    if (currentPos >= seg.getStart() && currentPos < seg.getEnd()) {
                        if (seg.shouldAutoSkip()) {
                            // Jump to next dry segment
                            jumpToNextDrySegment(seg);
                        }
                        break;
                    }
                }
            }
            if (autoSkipHandler != null && autoSkipRunnable != null) {
                autoSkipHandler.postDelayed(autoSkipRunnable, 1000);
            }
        };
        autoSkipHandler.postDelayed(autoSkipRunnable, 1000);
    }

    private void jumpToNextDrySegment(VoiceSegment currentSeg) {
        if (currentEpisode.getVoiceSegments() == null) return;
        for (VoiceSegment seg : currentEpisode.getVoiceSegments()) {
            if (seg.getStart() > currentSeg.getEnd() && seg.isEffectiveDry()) {
                seekTo(seg.getStart());
                return;
            }
        }
    }

    private void stopAutoSkipCheck() {
        if (autoSkipHandler != null && autoSkipRunnable != null) {
            autoSkipHandler.removeCallbacks(autoSkipRunnable);
            autoSkipRunnable = null;
        }
    }

    // ===== Continuous Play =====

    @Override
    public void onCompletion(MediaPlayer mp) {
        if (callback != null) callback.onStateChanged(false);
        sendStateBroadcast(false);
        stopAutoSkipCheck();
        if (continuousPlay && currentEpisode != null && !isLive) {
            // In a real app, we would play the next episode from the list.
            // For now, just restart the current episode.
            playEpisode(currentEpisode, false);
        }
    }

    // ===== Buffering =====

    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {
        bufferPercent = percent;
        if (callback != null) callback.onBufferUpdate(percent);
        Intent intent = new Intent(BROADCAST_BUFFER_UPDATE);
        intent.putExtra(EXTRA_BUFFER_PERCENT, percent);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    // ===== Foreground Notification with MediaStyle =====

    private void startForegroundNotification() {
        Intent openIntent = new Intent(this, PlayerActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = currentEpisode != null ? currentEpisode.getTitle()
                : (currentStation != null ? currentStation.getName() : "Radio App");

        boolean playing = player != null && player.isPlaying();

        // Action intents
        Intent rewindIntent = new Intent(this, RadioPlaybackService.class);
        rewindIntent.setAction(ACTION_REWIND);
        PendingIntent rewindPI = PendingIntent.getService(this, 1, rewindIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent prevIntent = new Intent(this, RadioPlaybackService.class);
        prevIntent.setAction(ACTION_PREV_SEGMENT);
        PendingIntent prevPI = PendingIntent.getService(this, 2, prevIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent playPauseIntent = new Intent(this, RadioPlaybackService.class);
        playPauseIntent.setAction(playing ? ACTION_PAUSE : ACTION_PLAY);
        PendingIntent playPausePI = PendingIntent.getService(this, 3, playPauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent nextIntent = new Intent(this, RadioPlaybackService.class);
        nextIntent.setAction(ACTION_NEXT_SEGMENT);
        PendingIntent nextPI = PendingIntent.getService(this, 4, nextIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent forwardIntent = new Intent(this, RadioPlaybackService.class);
        forwardIntent.setAction(ACTION_FORWARD);
        PendingIntent forwardPI = PendingIntent.getService(this, 5, forwardIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        androidx.media.app.NotificationCompat.MediaStyle mediaStyle =
                new androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(1, 2, 3);

        Notification notification = new NotificationCompat.Builder(this, RadioApplication.CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(playing ? "正在播放" : "已暂停")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(R.drawable.ic_rewind, "快退", rewindPI)
                .addAction(R.drawable.ic_prev, "上一片段", prevPI)
                .addAction(playing ? R.drawable.ic_pause : R.drawable.ic_play,
                        playing ? "暂停" : "播放", playPausePI)
                .addAction(R.drawable.ic_next, "下一片段", nextPI)
                .addAction(R.drawable.ic_forward, "快进", forwardPI)
                .setStyle(mediaStyle)
                .build();

        startForeground(1, notification);
    }

    // ===== Broadcast Helpers =====

    private void sendStateBroadcast(boolean playing) {
        Intent intent = new Intent(BROADCAST_STATE_CHANGED);
        intent.putExtra(EXTRA_IS_PLAYING, playing);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    // ===== MediaPlayer Callbacks =====

    @Override
    public void onPrepared(MediaPlayer mp) {
        mp.start();
        if (callback != null) callback.onStateChanged(true);
        sendStateBroadcast(true);
        startForegroundNotification();
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAutoSkipCheck();
        abandonAudioFocus();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
