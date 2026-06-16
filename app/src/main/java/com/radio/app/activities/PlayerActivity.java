package com.radio.app.activities;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.radio.app.R;
import com.radio.app.adapters.VoiceSegmentAdapter;
import com.radio.app.managers.SubtitleManager;
import com.radio.app.models.Episode;
import com.radio.app.models.Transcript;
import com.radio.app.models.VoiceSegment;
import com.radio.app.services.RadioPlaybackService;
import com.radio.app.services.SubtitleGeneratorService;

import java.util.ArrayList;
import java.util.List;

public class PlayerActivity extends AppCompatActivity {

    private static final String TAG = "PlayerActivity";

    // 顶部信息
    private ImageButton btnBack;
    private TextView tvStationName;
    private TextView tvProgramName;
    private TextView tvLiveIndicator;
    private ImageButton btnGenerateSubtitle;

    // 字幕
    private com.radio.app.views.SubtitleView subtitleView;
    private TextView tvSubtitleStatus;

    // AI分析进度
    private TextView tvAiProgress;

    // 进度条
    private LinearLayout progressArea;
    private SeekBar seekBar;
    private SeekBar cacheSeekBar;
    private TextView tvCurrentTime;
    private TextView tvTotalTime;

    // 播放控制
    private ImageButton btnRewind;
    private ImageButton btnPrev;
    private ImageButton btnPlayPause;
    private ImageButton btnNext;
    private ImageButton btnForward;

    // 片段列表
    private TextView tvSegmentsTitle;
    private RecyclerView recyclerSegments;
    private VoiceSegmentAdapter segmentAdapter;

    // Service
    private RadioPlaybackService playbackService;
    private boolean serviceBound = false;
    private LocalBroadcastManager broadcastManager;

    // 字幕管理
    private SubtitleManager subtitleManager;
    private boolean generating = false;

    // 进度轮询
    private Handler progressHandler;
    private Runnable progressRunnable;

    // Service连接
    private ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playbackService = ((RadioPlaybackService.LocalBinder) service).getService();
            serviceBound = true;
            updateUI();
            loadSubtitles();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            playbackService = null;
            serviceBound = false;
        }
    };

    // 广播接收器
    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case RadioPlaybackService.BROADCAST_STATE_CHANGED:
                    boolean playing = intent.getBooleanExtra(RadioPlaybackService.EXTRA_PLAYING, false);
                    updatePlayButton(playing);
                    break;

                case RadioPlaybackService.BROADCAST_POSITION_UPDATE:
                    long pos = intent.getLongExtra(RadioPlaybackService.EXTRA_POSITION, 0);
                    long dur = intent.getLongExtra(RadioPlaybackService.EXTRA_DURATION, 0);
                    updateProgress(pos, dur);
                    break;

                case RadioPlaybackService.BROADCAST_CACHE_UPDATE:
                    int cachePercent = intent.getIntExtra(RadioPlaybackService.EXTRA_CACHE_PERCENT, 0);
                    updateCacheProgress(cachePercent);
                    break;

                case RadioPlaybackService.BROADCAST_SEGMENTS_UPDATED:
                    int currentIndex = intent.getIntExtra(RadioPlaybackService.EXTRA_CURRENT_SEGMENT_INDEX, -1);
                    ArrayList<VoiceSegment> segments = intent.getParcelableArrayListExtra(RadioPlaybackService.EXTRA_SEGMENTS);
                    if (segments != null) {
                        segmentAdapter.setSegments(segments);
                        segmentAdapter.setCurrentSegmentIndex(currentIndex);
                    }
                    updateSegmentsVisibility(segments);
                    break;

                case RadioPlaybackService.BROADCAST_EPISODE_CHANGED:
                    String episodeTitle = intent.getStringExtra(RadioPlaybackService.EXTRA_EPISODE_TITLE);
                    String stationName = intent.getStringExtra(RadioPlaybackService.EXTRA_STATION_NAME_BROADCAST);
                    boolean isLive = intent.getBooleanExtra(RadioPlaybackService.EXTRA_IS_LIVE_BROADCAST, false);
                    String reqAction = intent.getStringExtra("action");
                    if ("next".equals(reqAction)) {
                        Toast.makeText(PlayerActivity.this, "请求播放下一个节目", Toast.LENGTH_SHORT).show();
                    } else if ("prev".equals(reqAction)) {
                        Toast.makeText(PlayerActivity.this, "请求播放上一个节目", Toast.LENGTH_SHORT).show();
                    }
                    if (playbackService != null) {
                        updateUI();
                    }
                    break;
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        initViews();
        setupListeners();
        setupBroadcastReceiver();
        setupRecyclerView();
        startProgressPolling();
        subtitleManager = new SubtitleManager(this);

        // 绑定Service
        Intent serviceIntent = new Intent(this, RadioPlaybackService.class);
        bindService(serviceIntent, conn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 处理从通知栏点击进来的情况
        if (playbackService != null) {
            updateUI();
            loadSubtitles();
        }
    }

    private void initViews() {
        btnBack = findViewById(R.id.btn_back);
        tvStationName = findViewById(R.id.tv_station_name);
        tvProgramName = findViewById(R.id.tv_program_name);
        tvLiveIndicator = findViewById(R.id.tv_live_indicator);
        btnGenerateSubtitle = findViewById(R.id.btn_generate_subtitle);

        subtitleView = findViewById(R.id.subtitle_view);
        tvSubtitleStatus = findViewById(R.id.tv_subtitle_status);

        tvAiProgress = findViewById(R.id.tv_ai_progress);

        progressArea = findViewById(R.id.progress_area);
        seekBar = findViewById(R.id.seek_bar);
        cacheSeekBar = findViewById(R.id.cache_seek_bar);
        tvCurrentTime = findViewById(R.id.tv_current_time);
        tvTotalTime = findViewById(R.id.tv_total_time);

        btnRewind = findViewById(R.id.btn_rewind);
        btnPrev = findViewById(R.id.btn_prev);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnNext = findViewById(R.id.btn_next);
        btnForward = findViewById(R.id.btn_forward);

        tvSegmentsTitle = findViewById(R.id.tv_segments_title);
        recyclerSegments = findViewById(R.id.recycler_segments);
    }

    private void setupListeners() {
        // 返回按钮
        btnBack.setOnClickListener(v -> finish());

        // 播放/暂停
        btnPlayPause.setOnClickListener(v -> {
            if (playbackService != null) {
                if (playbackService.isPlaying()) {
                    playbackService.pause();
                } else {
                    playbackService.play();
                }
            }
        });

        // 快退15秒
        btnRewind.setOnClickListener(v -> {
            if (playbackService != null) playbackService.skipBackward();
        });

        // 快进15秒
        btnForward.setOnClickListener(v -> {
            if (playbackService != null) playbackService.skipForward();
        });

        // 上一片段/上一首
        btnPrev.setOnClickListener(v -> {
            if (playbackService != null) {
                if (playbackService.isLive()) {
                    // 直播模式：上一首
                    Intent intent = new Intent(RadioPlaybackService.ACTION_PREV_EPISODE);
                    sendServiceAction(intent);
                } else {
                    // 回放模式：上一片段
                    playbackService.jumpToPrevSegment();
                }
            }
        });

        // 下一片段/下一首
        btnNext.setOnClickListener(v -> {
            if (playbackService != null) {
                if (playbackService.isLive()) {
                    // 直播模式：下一首
                    Intent intent = new Intent(RadioPlaybackService.ACTION_NEXT_EPISODE);
                    sendServiceAction(intent);
                } else {
                    // 回放模式：下一片段
                    playbackService.jumpToNextSegment();
                }
            }
        });

        // 生成字幕
        btnGenerateSubtitle.setOnClickListener(v -> {
            if (!generating) startSubtitleGeneration();
        });

        // 进度条拖动
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && playbackService != null && !playbackService.isLive()) {
                    playbackService.seekTo(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 拖动时暂停进度更新
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 拖动结束
            }
        });

        // 字幕点击跳转
        subtitleView.setOnSubtitleClickListener(startTime -> {
            if (playbackService != null && !playbackService.isLive()) {
                playbackService.seekTo(startTime * 1000);
            }
        });
    }

    private void setupBroadcastReceiver() {
        broadcastManager = LocalBroadcastManager.getInstance(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(RadioPlaybackService.BROADCAST_STATE_CHANGED);
        filter.addAction(RadioPlaybackService.BROADCAST_POSITION_UPDATE);
        filter.addAction(RadioPlaybackService.BROADCAST_CACHE_UPDATE);
        filter.addAction(RadioPlaybackService.BROADCAST_SEGMENTS_UPDATED);
        filter.addAction(RadioPlaybackService.BROADCAST_EPISODE_CHANGED);
        broadcastManager.registerReceiver(broadcastReceiver, filter);
    }

    private void setupRecyclerView() {
        segmentAdapter = new VoiceSegmentAdapter(this);
        segmentAdapter.setOnSegmentClickListener(new VoiceSegmentAdapter.OnSegmentClickListener() {
            @Override
            public void onSegmentClick(int position, VoiceSegment segment) {
                // 点击跳转到对应时间
                if (playbackService != null && !playbackService.isLive()) {
                    playbackService.seekTo((int) segment.getStart());
                }
            }

            @Override
            public void onSegmentLongClick(int position, VoiceSegment segment) {
                // 长按事件由Adapter内部PopupMenu处理
            }

            @Override
            public void onMarkAsDry(int position) {
                if (playbackService != null) {
                    playbackService.markSegment(position, true);
                    Toast.makeText(PlayerActivity.this, "已标记为干货", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onMarkAsWater(int position) {
                if (playbackService != null) {
                    playbackService.markSegment(position, false);
                    Toast.makeText(PlayerActivity.this, "已标记为水分", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onSkipThisTime(int position) {
                if (playbackService != null) {
                    List<VoiceSegment> segments = playbackService.getVoiceSegments();
                    if (position >= 0 && position < segments.size()) {
                        boolean currentSkip = segments.get(position).isSkipThisTime();
                        playbackService.setSkipThisTime(position, !currentSkip);
                        Toast.makeText(PlayerActivity.this,
                                currentSkip ? "已取消本次不跳过" : "已设置本次不跳过",
                                Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        recyclerSegments.setLayoutManager(new LinearLayoutManager(this));
        recyclerSegments.setAdapter(segmentAdapter);
    }

    private void startProgressPolling() {
        progressHandler = new Handler(Looper.getMainLooper());
        progressRunnable = () -> {
            if (playbackService != null) {
                long pos = playbackService.getCurrentPosition();
                long dur = playbackService.getDuration();
                updateProgress(pos, dur);
                updateCacheProgress(playbackService.getCachePercent());
            }
            progressHandler.postDelayed(progressRunnable, 1000);
        };
        progressHandler.post(progressRunnable);
    }

    // ==================== UI更新 ====================

    private void updateUI() {
        if (playbackService == null) return;

        boolean live = playbackService.isLive();

        // 更新顶部信息
        if (playbackService.getCurrentEpisode() != null) {
            Episode ep = playbackService.getCurrentEpisode();
            tvStationName.setText(ep.getStationName() != null ? ep.getStationName() : "");
            tvProgramName.setText(ep.getTitle() != null ? ep.getTitle() : "");
        } else if (playbackService.getCurrentStation() != null) {
            tvStationName.setText(playbackService.getCurrentStation().getName());
            tvProgramName.setText(playbackService.getCurrentStation().getCurrentProgram() != null
                    ? playbackService.getCurrentStation().getCurrentProgram() : "直播");
        }

        // 直播指示器
        tvLiveIndicator.setVisibility(live ? View.VISIBLE : View.GONE);

        // 进度区域：直播模式隐藏
        progressArea.setVisibility(live ? View.GONE : View.VISIBLE);

        // 快退/快进按钮：直播模式禁用
        btnRewind.setEnabled(!live);
        btnRewind.setAlpha(live ? 0.3f : 1.0f);
        btnForward.setEnabled(!live);
        btnForward.setAlpha(live ? 0.3f : 1.0f);

        // 生成字幕按钮：直播模式隐藏
        btnGenerateSubtitle.setVisibility(live ? View.GONE : View.VISIBLE);

        // 更新片段列表
        List<VoiceSegment> segments = playbackService.getVoiceSegments();
        segmentAdapter.setSegments(segments);
        segmentAdapter.setCurrentSegmentIndex(playbackService.getCurrentSegmentIndex());
        updateSegmentsVisibility(segments);

        // 更新播放按钮
        updatePlayButton(playbackService.isPlaying());

        // 更新缓存进度
        updateCacheProgress(playbackService.getCachePercent());
    }

    private void updatePlayButton(boolean playing) {
        btnPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void updateProgress(long pos, long dur) {
        tvCurrentTime.setText(formatTime(pos));
        tvTotalTime.setText(formatTime(dur));
        if (dur > 0 && !playbackService.isLive()) {
            seekBar.setMax((int) dur);
            seekBar.setProgress((int) pos);
        }
        // 更新字幕高亮
        if (subtitleView != null) {
            subtitleView.highlightSubtitle(pos / 1000);
        }
    }

    private void updateCacheProgress(int percent) {
        if (cacheSeekBar != null && percent >= 0 && percent <= 100) {
            int dur = playbackService != null ? (int) playbackService.getDuration() : 100;
            cacheSeekBar.setMax(Math.max(dur, 100));
            cacheSeekBar.setProgress((int) (dur * percent / 100.0));
        }
    }

    private void updateSegmentsVisibility(List<VoiceSegment> segments) {
        if (segments != null && !segments.isEmpty()) {
            tvSegmentsTitle.setVisibility(View.VISIBLE);
            recyclerSegments.setVisibility(View.VISIBLE);
        } else {
            tvSegmentsTitle.setVisibility(View.GONE);
            recyclerSegments.setVisibility(View.GONE);
        }
    }

    /**
     * 更新AI分析进度显示
     * @param totalDurationMs 节目总时长（毫秒）
     * @param processedMs 已处理时长（毫秒）
     */
    public void updateAIProgress(long totalDurationMs, long processedMs) {
        if (totalDurationMs <= 0) {
            tvAiProgress.setVisibility(View.GONE);
            return;
        }

        tvAiProgress.setVisibility(View.VISIBLE);

        long totalSec = totalDurationMs / 1000;
        long processedSec = processedMs / 1000;
        long remainingMs = totalDurationMs - processedMs;

        String text = String.format("节目总时长: %d分%d秒 | 已完成: %d分%d秒 | 预计还需%d秒",
                totalSec / 60, totalSec % 60,
                processedSec / 60, processedSec % 60,
                remainingMs / 1000);
        tvAiProgress.setText(text);
    }

    // ==================== 字幕 ====================

    private void loadSubtitles() {
        if (playbackService == null || playbackService.getCurrentEpisode() == null) return;
        List<Transcript> list = subtitleManager.getSubtitles(playbackService.getCurrentEpisode().getId());
        if (list != null && !list.isEmpty()) {
            subtitleView.setSubtitles(list);
            tvSubtitleStatus.setText("字幕已加载");
            tvSubtitleStatus.setVisibility(View.VISIBLE);
        } else {
            tvSubtitleStatus.setText("点击生成字幕");
            tvSubtitleStatus.setVisibility(View.VISIBLE);
        }
    }

    private void startSubtitleGeneration() {
        if (playbackService == null || playbackService.getCurrentEpisode() == null) return;
        generating = true;
        btnGenerateSubtitle.setEnabled(false);
        tvSubtitleStatus.setText("正在生成字幕...");
        tvSubtitleStatus.setVisibility(View.VISIBLE);

        subtitleManager.generateSubtitles(
                playbackService.getCurrentEpisode().getId(),
                playbackService.getCurrentEpisode().getAudioUrl(),
                new SubtitleGeneratorService.SubtitleCallback() {
                    @Override
                    public void onSubtitleGenerated(Transcript t) {
                        runOnUiThread(() -> subtitleView.addRealtimeSubtitle(t));
                    }

                    @Override
                    public void onProgressUpdate(int progress, int total) {
                        runOnUiThread(() -> {
                            tvSubtitleStatus.setText(String.format("生成字幕: %d/%d", progress, total));
                            // 同时更新AI分析进度
                            if (playbackService != null) {
                                long dur = playbackService.getDuration();
                                if (dur > 0) {
                                    long processed = (long) (dur * (double) progress / total);
                                    updateAIProgress(dur, processed);
                                }
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            generating = false;
                            btnGenerateSubtitle.setEnabled(true);
                            tvSubtitleStatus.setText("失败: " + error);
                            Toast.makeText(PlayerActivity.this, error, Toast.LENGTH_SHORT).show();
                        });
                    }
                });
    }

    // ==================== 辅助方法 ====================

    private void sendServiceAction(Intent intent) {
        intent.setClass(this, RadioPlaybackService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    // ==================== 生命周期 ====================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 停止进度轮询
        if (progressHandler != null && progressRunnable != null) {
            progressHandler.removeCallbacks(progressRunnable);
        }
        // 注销广播
        if (broadcastManager != null) {
            try {
                broadcastManager.unregisterReceiver(broadcastReceiver);
            } catch (Exception e) {
                Log.w(TAG, "注销广播接收器失败", e);
            }
        }
        // 解绑Service
        if (serviceBound) {
            try {
                unbindService(conn);
            } catch (Exception e) {
                Log.w(TAG, "解绑Service失败", e);
            }
            serviceBound = false;
        }
        // 释放字幕管理器
        if (subtitleManager != null) {
            subtitleManager.release();
        }
    }
}
