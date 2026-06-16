package com.radio.app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.radio.app.R;
import com.radio.app.RadioApplication;
import com.radio.app.activities.PlayerActivity;
import com.radio.app.models.AppSettings;
import com.radio.app.models.Episode;
import com.radio.app.models.RadioStation;
import com.radio.app.models.VoiceSegment;
import com.radio.app.utils.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

public class RadioPlaybackService extends Service
        implements MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener,
        MediaPlayer.OnBufferingUpdateListener,
        MediaPlayer.OnErrorListener,
        AudioManager.OnAudioFocusChangeListener {

    private static final String TAG = "RadioPlaybackService";
    private static final int NOTIFICATION_ID = 1;

    // Intent Actions
    public static final String ACTION_PLAY_STATION = "com.radio.app.PLAY_STATION";
    public static final String ACTION_PLAY_EPISODE = "com.radio.app.PLAY_EPISODE";
    public static final String ACTION_PLAY = "com.radio.app.PLAY";
    public static final String ACTION_PAUSE = "com.radio.app.PAUSE";
    public static final String ACTION_STOP = "com.radio.app.STOP";
    public static final String ACTION_NEXT_SEGMENT = "com.radio.app.NEXT_SEGMENT";
    public static final String ACTION_PREV_SEGMENT = "com.radio.app.PREV_SEGMENT";
    public static final String ACTION_NEXT_EPISODE = "com.radio.app.NEXT_EPISODE";
    public static final String ACTION_PREV_EPISODE = "com.radio.app.PREV_EPISODE";

    // Intent Extras
    public static final String EXTRA_STATION_ID = "station_id";
    public static final String EXTRA_STATION_NAME = "station_name";
    public static final String EXTRA_STREAM_URL = "stream_url";
    public static final String EXTRA_IS_LIVE = "is_live";
    public static final String EXTRA_EPISODE_ID = "episode_id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_AUDIO_URL = "audio_url";

    // Broadcast Actions
    public static final String BROADCAST_STATE_CHANGED = "com.radio.app.STATE_CHANGED";
    public static final String BROADCAST_POSITION_UPDATE = "com.radio.app.POSITION_UPDATE";
    public static final String BROADCAST_CACHE_UPDATE = "com.radio.app.CACHE_UPDATE";
    public static final String BROADCAST_SEGMENTS_UPDATED = "com.radio.app.SEGMENTS_UPDATED";
    public static final String BROADCAST_EPISODE_CHANGED = "com.radio.app.EPISODE_CHANGED";

    // Broadcast Extras
    public static final String EXTRA_PLAYING = "playing";
    public static final String EXTRA_POSITION = "position";
    public static final String EXTRA_DURATION = "duration";
    public static final String EXTRA_CACHE_PERCENT = "cache_percent";
    public static final String EXTRA_SEGMENTS = "segments";
    public static final String EXTRA_CURRENT_SEGMENT_INDEX = "current_segment_index";
    public static final String EXTRA_EPISODE_TITLE = "episode_title";
    public static final String EXTRA_STATION_NAME_BROADCAST = "station_name_broadcast";
    public static final String EXTRA_IS_LIVE_BROADCAST = "is_live_broadcast";

    private MediaPlayer player;
    private final IBinder binder = new LocalBinder();
    private Episode currentEpisode;
    private RadioStation currentStation;
    private boolean isLive = false;
    private List<VoiceSegment> voiceSegments = new ArrayList<>();
    private int currentSegmentIndex = -1;
    private boolean wasPlayingBeforeLoss = false;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private Handler progressHandler;
    private Runnable progressRunnable;
    private LocalBroadcastManager broadcastManager;
    private PreferenceManager preferenceManager;
    private int cachePercent = 0;

    public interface Callback {
        void onStateChanged(boolean playing);
        void onPositionChanged(long position, long duration);
        void onCacheUpdate(int percent);
        void onSegmentsUpdated(List<VoiceSegment> segments, int currentIndex);
        void onEpisodeChanged(Episode episode, RadioStation station, boolean live);
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
        player.setOnErrorListener(this);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        broadcastManager = LocalBroadcastManager.getInstance(this);
        preferenceManager = new PreferenceManager(this);

        progressHandler = new Handler(Looper.getMainLooper());
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (player != null && isPlaying()) {
                    long pos = player.getCurrentPosition();
                    long dur = player.getDuration();
                    sendPositionBroadcast(pos, dur);
                    checkAutoSkip(pos);
                    updateCurrentSegmentIndex(pos);
                }
                progressHandler.postDelayed(this, 1000);
            }
        };
        progressHandler.post(progressRunnable);

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY_STATION:
                    handlePlayStation(intent);
                    break;
                case ACTION_PLAY_EPISODE:
                    handlePlayEpisode(intent);
                    break;
                case ACTION_PLAY:
                    play();
                    break;
                case ACTION_PAUSE:
                    pause();
                    break;
                case ACTION_STOP:
                    stop();
                    break;
                case ACTION_NEXT_SEGMENT:
                    jumpToNextSegment();
                    break;
                case ACTION_PREV_SEGMENT:
                    jumpToPrevSegment();
                    break;
                case ACTION_NEXT_EPISODE:
                    playNextEpisode();
                    break;
                case ACTION_PREV_EPISODE:
                    playPrevEpisode();
                    break;
            }
        }
        return START_STICKY;
    }

    // ==================== 音频焦点管理 ====================

    private void requestAudioFocus() {
        AppSettings settings = preferenceManager.loadSettings();
        if (!settings.isAudioFocus()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAcceptsDelayedFocusGain(true)
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener(this)
                        .build();
            }
            int result = audioManager.requestAudioFocus(audioFocusRequest);
            if (result == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                Log.w(TAG, "音频焦点请求失败");
            }
        } else {
            //noinspection deprecation
            int result = audioManager.requestAudioFocus(this,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "音频焦点请求失败");
            }
        }
    }

    private void abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            //noinspection deprecation
            audioManager.abandonAudioFocus(this);
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        AppSettings settings = preferenceManager.loadSettings();
        if (!settings.isAudioFocus()) {
            return;
        }
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                // 焦点恢复，恢复播放
                if (wasPlayingBeforeLoss) {
                    play();
                    wasPlayingBeforeLoss = false;
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                // 永久失去焦点，暂停播放
                if (isPlaying()) {
                    wasPlayingBeforeLoss = true;
                    pause();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // 临时失去焦点，暂停播放
                if (isPlaying()) {
                    wasPlayingBeforeLoss = true;
                    pause();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // 临时失去焦点但可以降低音量（不处理，保持正常音量）
                break;
        }
    }

    // ==================== 播放控制 ====================

    private void handlePlayStation(Intent intent) {
        String stationId = intent.getStringExtra(EXTRA_STATION_ID);
        String stationName = intent.getStringExtra(EXTRA_STATION_NAME);
        String streamUrl = intent.getStringExtra(EXTRA_STREAM_URL);
        boolean live = intent.getBooleanExtra(EXTRA_IS_LIVE, true);

        RadioStation station = new RadioStation();
        station.setId(stationId);
        station.setName(stationName);
        station.setStreamUrl(streamUrl);
        playStation(station);
    }

    private void handlePlayEpisode(Intent intent) {
        String episodeId = intent.getStringExtra(EXTRA_EPISODE_ID);
        String title = intent.getStringExtra(EXTRA_TITLE);
        String audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL);
        boolean live = intent.getBooleanExtra(EXTRA_IS_LIVE, false);
        String stationName = intent.getStringExtra(EXTRA_STATION_NAME);

        Episode episode = new Episode();
        episode.setId(episodeId);
        episode.setTitle(title);
        episode.setAudioUrl(audioUrl);
        episode.setLive(live);
        episode.setStationName(stationName);
        playEpisode(episode, live);
    }

    public void playStation(RadioStation station) {
        this.currentStation = station;
        this.currentEpisode = null;
        this.isLive = true;
        this.voiceSegments.clear();
        this.currentSegmentIndex = -1;
        try {
            player.reset();
            player.setDataSource(station.getStreamUrl());
            player.prepareAsync();
            requestAudioFocus();
            startForegroundNotification();
            sendSegmentsBroadcast();
        } catch (Exception e) {
            Log.e(TAG, "播放电台失败", e);
        }
    }

    public void playEpisode(Episode episode, boolean live) {
        this.currentEpisode = episode;
        this.currentStation = null;
        this.isLive = live;
        if (episode.getVoiceSegments() != null) {
            this.voiceSegments = new ArrayList<>(episode.getVoiceSegments());
        } else {
            this.voiceSegments.clear();
        }
        this.currentSegmentIndex = -1;
        try {
            player.reset();
            player.setDataSource(episode.getAudioUrl());
            player.prepareAsync();
            requestAudioFocus();
            startForegroundNotification();
            sendSegmentsBroadcast();
            sendEpisodeChangedBroadcast();
        } catch (Exception e) {
            Log.e(TAG, "播放节目失败", e);
        }
    }

    public void play() {
        if (player != null && !player.isPlaying()) {
            player.start();
            startForegroundNotification();
            sendStateChangedBroadcast(true);
        }
    }

    public void pause() {
        if (player != null && player.isPlaying()) {
            player.pause();
            startForegroundNotification();
            sendStateChangedBroadcast(false);
        }
    }

    public void stop() {
        if (player != null) {
            player.stop();
            player.reset();
        }
        abandonAudioFocus();
        stopForeground(true);
        stopSelf();
        sendStateChangedBroadcast(false);
    }

    public void seekTo(long pos) {
        if (player != null && !isLive) {
            player.seekTo((int) pos);
        }
    }

    public void skipForward() {
        if (player != null && !isLive) {
            long p = player.getCurrentPosition() + 15000;
            long dur = player.getDuration();
            if (dur > 0 && p >= dur) {
                p = dur - 1000;
            }
            if (p > 0) {
                player.seekTo((int) p);
            }
        }
    }

    public void skipBackward() {
        if (player != null && !isLive) {
            player.seekTo(Math.max(0, player.getCurrentPosition() - 15000));
        }
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    public boolean isLive() {
        return isLive;
    }

    public long getCurrentPosition() {
        return player != null ? player.getCurrentPosition() : 0;
    }

    public long getDuration() {
        return player != null ? player.getDuration() : 0;
    }

    public Episode getCurrentEpisode() {
        return currentEpisode;
    }

    public RadioStation getCurrentStation() {
        return currentStation;
    }

    public List<VoiceSegment> getVoiceSegments() {
        return voiceSegments;
    }

    public int getCurrentSegmentIndex() {
        return currentSegmentIndex;
    }

    public int getCachePercent() {
        return cachePercent;
    }

    // ==================== 片段跳转 ====================

    /**
     * 跳转到下一个干货片段
     */
    public void jumpToNextSegment() {
        if (voiceSegments.isEmpty() || isLive) return;
        long currentPos = player != null ? player.getCurrentPosition() : 0;
        int nextIndex = findNextDrySegment(currentPos);
        if (nextIndex >= 0 && nextIndex < voiceSegments.size()) {
            VoiceSegment seg = voiceSegments.get(nextIndex);
            player.seekTo((int) seg.getStart());
            currentSegmentIndex = nextIndex;
            sendSegmentsBroadcast();
        }
    }

    /**
     * 跳转到上一个干货片段
     */
    public void jumpToPrevSegment() {
        if (voiceSegments.isEmpty() || isLive) return;
        long currentPos = player != null ? player.getCurrentPosition() : 0;
        int prevIndex = findPrevDrySegment(currentPos);
        if (prevIndex >= 0 && prevIndex < voiceSegments.size()) {
            VoiceSegment seg = voiceSegments.get(prevIndex);
            player.seekTo((int) seg.getStart());
            currentSegmentIndex = prevIndex;
            sendSegmentsBroadcast();
        }
    }

    /**
     * 从当前位置查找下一个干货片段
     */
    private int findNextDrySegment(long currentPos) {
        // 先找当前位置之后的干货片段
        for (int i = 0; i < voiceSegments.size(); i++) {
            VoiceSegment seg = voiceSegments.get(i);
            if (seg.getStart() > currentPos && seg.isEffectiveDry()) {
                return i;
            }
        }
        // 如果后面没有了，从头找
        for (int i = 0; i < voiceSegments.size(); i++) {
            VoiceSegment seg = voiceSegments.get(i);
            if (seg.isEffectiveDry()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 从当前位置查找上一个干货片段
     */
    private int findPrevDrySegment(long currentPos) {
        // 先找当前位置之前的干货片段
        for (int i = voiceSegments.size() - 1; i >= 0; i--) {
            VoiceSegment seg = voiceSegments.get(i);
            if (seg.getEnd() < currentPos && seg.isEffectiveDry()) {
                return i;
            }
        }
        // 如果前面没有了，从末尾找
        for (int i = voiceSegments.size() - 1; i >= 0; i--) {
            VoiceSegment seg = voiceSegments.get(i);
            if (seg.isEffectiveDry()) {
                return i;
            }
        }
        return -1;
    }

    // ==================== 自动跳过水分片段 ====================

    /**
     * 检查当前位置是否在水分片段中，如果是则自动跳到下一个干货片段
     */
    private void checkAutoSkip(long currentPos) {
        if (voiceSegments.isEmpty() || isLive) return;
        for (int i = 0; i < voiceSegments.size(); i++) {
            VoiceSegment seg = voiceSegments.get(i);
            if (currentPos >= seg.getStart() && currentPos <= seg.getEnd()) {
                if (seg.shouldAutoSkip()) {
                    Log.d(TAG, "自动跳过水分片段: " + seg.getLabel() + " [" + seg.getStart() + "-" + seg.getEnd() + "]");
                    int nextIndex = findNextDrySegment(currentPos);
                    if (nextIndex >= 0 && nextIndex < voiceSegments.size()) {
                        VoiceSegment nextSeg = voiceSegments.get(nextIndex);
                        player.seekTo((int) nextSeg.getStart());
                        currentSegmentIndex = nextIndex;
                        sendSegmentsBroadcast();
                    }
                }
                break;
            }
        }
    }

    /**
     * 设置指定片段"本次不跳过"
     */
    public void setSkipThisTime(int segmentIndex, boolean skip) {
        if (segmentIndex >= 0 && segmentIndex < voiceSegments.size()) {
            voiceSegments.get(segmentIndex).setSkipThisTime(skip);
            sendSegmentsBroadcast();
        }
    }

    /**
     * 手动标记片段为干货或水分
     */
    public void markSegment(int segmentIndex, boolean hasVoice) {
        if (segmentIndex >= 0 && segmentIndex < voiceSegments.size()) {
            VoiceSegment seg = voiceSegments.get(segmentIndex);
            seg.setHasVoice(hasVoice);
            seg.setManuallyMarked(true);
            sendSegmentsBroadcast();
        }
    }

    /**
     * 更新片段列表
     */
    public void setVoiceSegments(List<VoiceSegment> segments) {
        this.voiceSegments = segments != null ? new ArrayList<>(segments) : new ArrayList<>();
        this.currentSegmentIndex = -1;
        sendSegmentsBroadcast();
    }

    // ==================== 连续播放 ====================

    /**
     * 播放下一个节目（连续播放）
     */
    private void playNextEpisode() {
        // 通知Activity去获取下一个节目
        Intent intent = new Intent(BROADCAST_EPISODE_CHANGED);
        intent.putExtra("action", "next");
        broadcastManager.sendBroadcast(intent);
    }

    /**
     * 播放上一个节目
     */
    private void playPrevEpisode() {
        Intent intent = new Intent(BROADCAST_EPISODE_CHANGED);
        intent.putExtra("action", "prev");
        broadcastManager.sendBroadcast(intent);
    }

    // ==================== 当前片段索引更新 ====================

    private void updateCurrentSegmentIndex(long pos) {
        int newIndex = -1;
        for (int i = 0; i < voiceSegments.size(); i++) {
            VoiceSegment seg = voiceSegments.get(i);
            if (pos >= seg.getStart() && pos <= seg.getEnd()) {
                newIndex = i;
                break;
            }
        }
        if (newIndex != currentSegmentIndex) {
            currentSegmentIndex = newIndex;
            sendSegmentsBroadcast();
        }
    }

    // ==================== 广播发送 ====================

    private void sendStateChangedBroadcast(boolean playing) {
        Intent intent = new Intent(BROADCAST_STATE_CHANGED);
        intent.putExtra(EXTRA_PLAYING, playing);
        broadcastManager.sendBroadcast(intent);
    }

    private void sendPositionBroadcast(long pos, long dur) {
        Intent intent = new Intent(BROADCAST_POSITION_UPDATE);
        intent.putExtra(EXTRA_POSITION, pos);
        intent.putExtra(EXTRA_DURATION, dur);
        broadcastManager.sendBroadcast(intent);
    }

    private void sendCacheBroadcast(int percent) {
        Intent intent = new Intent(BROADCAST_CACHE_UPDATE);
        intent.putExtra(EXTRA_CACHE_PERCENT, percent);
        broadcastManager.sendBroadcast(intent);
    }

    private void sendSegmentsBroadcast() {
        Intent intent = new Intent(BROADCAST_SEGMENTS_UPDATED);
        intent.putExtra(EXTRA_CURRENT_SEGMENT_INDEX, currentSegmentIndex);
        intent.putParcelableArrayListExtra(EXTRA_SEGMENTS,
                voiceSegments.isEmpty() ? new ArrayList<VoiceSegment>() : new ArrayList<>(voiceSegments));
        broadcastManager.sendBroadcast(intent);
    }

    private void sendEpisodeChangedBroadcast() {
        Intent intent = new Intent(BROADCAST_EPISODE_CHANGED);
        intent.putExtra(EXTRA_EPISODE_TITLE,
                currentEpisode != null ? currentEpisode.getTitle() : "");
        intent.putExtra(EXTRA_STATION_NAME_BROADCAST,
                currentEpisode != null ? currentEpisode.getStationName() :
                        (currentStation != null ? currentStation.getName() : ""));
        intent.putExtra(EXTRA_IS_LIVE_BROADCAST, isLive);
        broadcastManager.sendBroadcast(intent);
    }

    // ==================== 通知栏控制 ====================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    RadioApplication.CHANNEL_ID,
                    "Radio Playback",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Radio playback controls");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void startForegroundNotification() {
        Intent openIntent = new Intent(this, PlayerActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = currentEpisode != null ? currentEpisode.getTitle()
                : (currentStation != null ? currentStation.getName() : "Radio App");
        String subtitle = isLive ? "直播" : "回放";

        // 播放/暂停按钮
        Intent playPauseIntent = new Intent(this, RadioPlaybackService.class);
        playPauseIntent.setAction(isPlaying() ? ACTION_PAUSE : ACTION_PLAY);
        PendingIntent playPausePendingIntent = PendingIntent.getService(this, 1, playPauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 快退按钮
        Intent rewindIntent = new Intent(this, RadioPlaybackService.class);
        rewindIntent.setAction(ACTION_PREV_SEGMENT);
        PendingIntent rewindPendingIntent = PendingIntent.getService(this, 2, rewindIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 快进按钮
        Intent forwardIntent = new Intent(this, RadioPlaybackService.class);
        forwardIntent.setAction(ACTION_NEXT_SEGMENT);
        PendingIntent forwardPendingIntent = PendingIntent.getService(this, 3, forwardIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 上一首/上一片段按钮
        Intent prevIntent = new Intent(this, RadioPlaybackService.class);
        prevIntent.setAction(isLive ? ACTION_PREV_EPISODE : ACTION_PREV_SEGMENT);
        PendingIntent prevPendingIntent = PendingIntent.getService(this, 4, prevIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 下一首/下一片段按钮
        Intent nextIntent = new Intent(this, RadioPlaybackService.class);
        nextIntent.setAction(isLive ? ACTION_NEXT_EPISODE : ACTION_NEXT_SEGMENT);
        PendingIntent nextPendingIntent = PendingIntent.getService(this, 5, nextIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        int playPauseIcon = isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play;
        String playPauseText = isPlaying() ? "暂停" : "播放";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, RadioApplication.CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(R.drawable.ic_skip_backward, "快退", rewindPendingIntent)
                .addAction(R.drawable.ic_skip_backward, isLive ? "上一首" : "上一片段", prevPendingIntent)
                .addAction(playPauseIcon, playPauseText, playPausePendingIntent)
                .addAction(R.drawable.ic_skip_forward, isLive ? "下一首" : "下一片段", nextPendingIntent)
                .addAction(R.drawable.ic_skip_forward, "快进", forwardPendingIntent)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(1, 2, 3));

        Notification notification = builder.build();
        startForeground(NOTIFICATION_ID, notification);
    }

    // ==================== MediaPlayer 回调 ====================

    @Override
    public void onPrepared(MediaPlayer mp) {
        mp.start();
        startForegroundNotification();
        sendStateChangedBroadcast(true);
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        sendStateChangedBroadcast(false);
        // 回放模式：自动播放下一个节目
        if (!isLive && currentEpisode != null) {
            playNextEpisode();
        }
    }

    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {
        cachePercent = percent;
        sendCacheBroadcast(percent);
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e(TAG, "MediaPlayer错误: what=" + what + " extra=" + extra);
        sendStateChangedBroadcast(false);
        return true;
    }

    // ==================== Service 生命周期 ====================

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (progressHandler != null && progressRunnable != null) {
            progressHandler.removeCallbacks(progressRunnable);
        }
        if (player != null) {
            player.release();
            player = null;
        }
        abandonAudioFocus();
    }
}
