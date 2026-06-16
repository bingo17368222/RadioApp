package com.radio.app.activities;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.radio.app.R;
import com.radio.app.adapters.VoiceSegmentAdapter;
import com.radio.app.managers.SubtitleManager;
import com.radio.app.models.Episode;
import com.radio.app.models.RadioStation;
import com.radio.app.models.Transcript;
import com.radio.app.models.VoiceSegment;
import com.radio.app.services.RadioPlaybackService;
import com.radio.app.services.SubtitleGeneratorService;
import com.radio.app.views.SubtitleView;

import java.util.ArrayList;
import java.util.List;

public class PlayerActivity extends AppCompatActivity {
    private boolean isUserSeeking = false;
    private TextView tvStationName, tvEpisodeTitle, tvCurrentTime, tvTotalTime, tvLiveIndicator, tvSubtitleStatus;
    private TextView tvAiProgress, tvNetworkUrl, tvCacheUrl;
    private SeekBar seekBar, seekBarCache;
    private ProgressBar progressBuffer;
    private ImageButton btnPlayPause, btnSkipBackward, btnSkipForward, btnClose, btnGenerateSubtitle;
    private ImageButton btnPrevSegment, btnNextSegment, btnSubtitleToggle;
    private RecyclerView recyclerSegments;
    private SubtitleView subtitleView;
    private VoiceSegmentAdapter segmentAdapter;
    private RadioPlaybackService playbackService;
    private boolean serviceBound = false;
    private Handler handler;
    private Runnable runnable;
    private SubtitleManager subtitleManager;
    private boolean generating = false;
    private boolean showingSegments = true;

    // Intent extras
    private String stationId, stationName, streamUrl, episodeId, episodeTitle, audioUrl;
    private boolean isLive;
    private long duration;
    private ArrayList<VoiceSegment> voiceSegments;
    private ArrayList<Transcript> transcripts;

    private ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder s) {
            playbackService = ((RadioPlaybackService.LocalBinder) s).getService();
            serviceBound = true;
            playbackService.setCallback(new RadioPlaybackService.Callback() {
                @Override public void onStateChanged(boolean playing) { updatePlayBtn(); }
                @Override public void onPositionChanged(long pos, long dur) { updateProgress(pos, dur); }
                @Override public void onBufferUpdate(int percent) { updateBufferProgress(percent); }
            });
            // Start playback based on intent
            startPlayback();
            updateUI();
            loadSubtitles();
            loadSegments();
        }
        @Override public void onServiceDisconnected(ComponentName name) { playbackService = null; serviceBound = false; }
    };

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (RadioPlaybackService.BROADCAST_BUFFER_UPDATE.equals(intent.getAction())) {
                int percent = intent.getIntExtra(RadioPlaybackService.EXTRA_BUFFER_PERCENT, 0);
                updateBufferProgress(percent);
            } else if (RadioPlaybackService.BROADCAST_STATE_CHANGED.equals(intent.getAction())) {
                boolean playing = intent.getBooleanExtra(RadioPlaybackService.EXTRA_IS_PLAYING, false);
                updatePlayBtn();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        // Parse intent extras
        Intent intent = getIntent();
        isLive = intent.getBooleanExtra("is_live", false);
        stationId = intent.getStringExtra("station_id");
        stationName = intent.getStringExtra("station_name");
        streamUrl = intent.getStringExtra("stream_url");
        episodeId = intent.getStringExtra("episode_id");
        episodeTitle = intent.getStringExtra("title");
        audioUrl = intent.getStringExtra("audio_url");
        duration = intent.getLongExtra("duration", 0);
        voiceSegments = (ArrayList<VoiceSegment>) intent.getSerializableExtra("voice_segments");
        transcripts = (ArrayList<Transcript>) intent.getSerializableExtra("transcripts");

        initViews();
        setupListeners();
        startProgress();
        subtitleManager = new SubtitleManager(this);
        bindService(new Intent(this, RadioPlaybackService.class), conn, Context.BIND_AUTO_CREATE);

        // Register for broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(RadioPlaybackService.BROADCAST_BUFFER_UPDATE);
        filter.addAction(RadioPlaybackService.BROADCAST_STATE_CHANGED);
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, filter);
    }

    private void startPlayback() {
        if (playbackService == null) return;
        try {
            if (isLive && streamUrl != null) {
                RadioStation station = new RadioStation();
                station.setId(stationId != null ? stationId : "live");
                station.setName(stationName != null ? stationName : "直播");
                station.setStreamUrl(streamUrl);
                playbackService.playStation(station);
                // 直播模式隐藏缓存进度条和URL
                tvNetworkUrl.setVisibility(View.GONE);
                tvCacheUrl.setVisibility(View.GONE);
                progressBuffer.setVisibility(View.GONE);
            } else if (audioUrl != null) {
                Episode episode = new Episode();
                episode.setId(episodeId != null ? episodeId : "ep");
                episode.setTitle(episodeTitle != null ? episodeTitle : "节目");
                episode.setAudioUrl(audioUrl);
                episode.setStationName(stationName != null ? stationName : "电台");
                episode.setDuration(duration);
                if (voiceSegments != null) episode.setVoiceSegments(voiceSegments);
                if (transcripts != null) episode.setTranscripts(transcripts);
                playbackService.playEpisode(episode, false);
                // 非直播模式显示网络URL
                tvNetworkUrl.setText("网络: " + audioUrl);
                tvNetworkUrl.setVisibility(View.VISIBLE);
                tvCacheUrl.setVisibility(View.VISIBLE);
                progressBuffer.setVisibility(View.VISIBLE);
                tvCacheUrl.setText("缓存: 等待中...");
            }
        } catch (Exception e) {
            Toast.makeText(this, "播放失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void initViews() {
        tvStationName = findViewById(R.id.tv_station_name);
        tvEpisodeTitle = findViewById(R.id.tv_episode_title);
        tvCurrentTime = findViewById(R.id.tv_current_time);
        tvTotalTime = findViewById(R.id.tv_total_time);
        tvLiveIndicator = findViewById(R.id.tv_live_indicator);
        tvSubtitleStatus = findViewById(R.id.tv_subtitle_status);
        tvAiProgress = findViewById(R.id.tv_ai_progress);
        tvNetworkUrl = findViewById(R.id.tv_network_url);
        tvCacheUrl = findViewById(R.id.tv_cache_url);
        progressBuffer = findViewById(R.id.progress_buffer);
        seekBar = findViewById(R.id.seek_bar);
        seekBarCache = findViewById(R.id.seek_bar_cache);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnSkipBackward = findViewById(R.id.btn_skip_backward);
        btnSkipForward = findViewById(R.id.btn_skip_forward);
        btnClose = findViewById(R.id.btn_close);
        btnGenerateSubtitle = findViewById(R.id.btn_generate_subtitle);
        btnPrevSegment = findViewById(R.id.btn_prev_segment);
        btnNextSegment = findViewById(R.id.btn_next_segment);
        btnSubtitleToggle = findViewById(R.id.btn_subtitle_toggle);
        recyclerSegments = findViewById(R.id.recycler_segments);
        subtitleView = findViewById(R.id.subtitle_view);

        segmentAdapter = new VoiceSegmentAdapter();
        segmentAdapter.setOnSegmentClickListener(new VoiceSegmentAdapter.OnSegmentClickListener() {
            @Override
            public void onSegmentClick(int position, VoiceSegment segment) {
                if (playbackService != null && !playbackService.isLive()) {
                    playbackService.seekTo(segment.getStart());
                }
            }
            @Override
            public void onSegmentLongClick(int position, VoiceSegment segment) {
                showSegmentMarkDialog(position, segment);
            }
        });
        recyclerSegments.setLayoutManager(new LinearLayoutManager(this));
        recyclerSegments.setAdapter(segmentAdapter);

        subtitleView.setOnSubtitleClickListener(startTime -> {
            if (playbackService != null && !playbackService.isLive()) playbackService.seekTo(startTime * 1000);
        });
    }

    private void setupListeners() {
        btnClose.setOnClickListener(v -> finish());
        btnPlayPause.setOnClickListener(v -> {
            if (playbackService != null) {
                if (playbackService.isPlaying()) playbackService.pause(); else playbackService.play();
            }
        });
        btnSkipBackward.setOnClickListener(v -> { if (playbackService != null) playbackService.skipBackward(); });
        btnSkipForward.setOnClickListener(v -> { if (playbackService != null) playbackService.skipForward(); });
        btnPrevSegment.setOnClickListener(v -> { if (playbackService != null) playbackService.jumpToPrevSegment(); });
        btnNextSegment.setOnClickListener(v -> { if (playbackService != null) playbackService.jumpToNextSegment(); });
        btnGenerateSubtitle.setOnClickListener(v -> { if (!generating) startSubtitleGen(); });
        btnSubtitleToggle.setOnClickListener(v -> toggleSubtitleView());
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) { isUserSeeking = true; tvCurrentTime.setText(fmtTime(p)); }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { isUserSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                if (playbackService != null && !playbackService.isLive()) playbackService.seekTo(sb.getProgress());
                isUserSeeking = false;
            }
        });
    }

    private void toggleSubtitleView() {
        showingSegments = !showingSegments;
        recyclerSegments.setVisibility(showingSegments ? View.VISIBLE : View.GONE);
        subtitleView.setVisibility(showingSegments ? View.GONE : View.VISIBLE);
    }

    private void showSegmentMarkDialog(int position, VoiceSegment segment) {
        String[] items = {"标记为干货", "标记为水分", "本次跳过", "取消跳过"};
        new android.app.AlertDialog.Builder(this)
            .setTitle(segment.getLabel())
            .setItems(items, (dialog, which) -> {
                if (playbackService == null) return;
                switch (which) {
                    case 0: playbackService.markSegment(position, true); break;
                    case 1: playbackService.markSegment(position, false); break;
                    case 2: playbackService.setSkipThisTime(position, true); break;
                    case 3: playbackService.setSkipThisTime(position, false); break;
                }
                loadSegments();
            })
            .show();
    }

    private void loadSegments() {
        if (playbackService == null || playbackService.getCurrentEpisode() == null) return;
        List<VoiceSegment> segments = playbackService.getCurrentEpisode().getVoiceSegments();
        segmentAdapter.setSegments(segments);
    }

    private void updateUI() {
        if (playbackService == null) return;
        if (playbackService.getCurrentEpisode() != null) {
            tvStationName.setText(playbackService.getCurrentEpisode().getStationName());
            tvEpisodeTitle.setText(playbackService.getCurrentEpisode().getTitle());
        } else if (playbackService.getCurrentStation() != null) {
            tvStationName.setText(playbackService.getCurrentStation().getName());
            tvEpisodeTitle.setText("直播中");
        }
        boolean live = playbackService.isLive();
        tvLiveIndicator.setVisibility(live ? View.VISIBLE : View.GONE);
        seekBar.setEnabled(!live);
        seekBarCache.setEnabled(!live);
        btnGenerateSubtitle.setVisibility(live ? View.GONE : View.VISIBLE);
        btnPrevSegment.setVisibility(live ? View.GONE : View.VISIBLE);
        btnNextSegment.setVisibility(live ? View.GONE : View.VISIBLE);
        recyclerSegments.setVisibility(live ? View.GONE : (showingSegments ? View.VISIBLE : View.GONE));
        updatePlayBtn();
    }

    private void updatePlayBtn() {
        if (playbackService != null)
            btnPlayPause.setImageResource(playbackService.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void updateProgress(long pos, long dur) {
        if (dur <= 0) {
            // 直播模式：显示"直播中"
            if (playbackService != null && playbackService.isLive()) {
                tvCurrentTime.setText("直播中");
                tvTotalTime.setText("");
            } else {
                // 非直播模式：显示"缓冲中..."
                tvCurrentTime.setText("缓冲中...");
                tvTotalTime.setText("");
            }
        } else {
            tvCurrentTime.setText(fmtTime(pos));
            tvTotalTime.setText(fmtTime(dur));
            if (!isUserSeeking) { seekBar.setMax((int) dur); seekBar.setProgress((int) pos); }
        }
        if (subtitleView != null && subtitleView.getVisibility() == View.VISIBLE) subtitleView.highlightSubtitle(pos / 1000);

        // Update current segment highlight
        if (playbackService != null && playbackService.getCurrentEpisode() != null
                && playbackService.getCurrentEpisode().getVoiceSegments() != null) {
            List<VoiceSegment> segments = playbackService.getCurrentEpisode().getVoiceSegments();
            for (int i = 0; i < segments.size(); i++) {
                VoiceSegment seg = segments.get(i);
                if (pos >= seg.getStart() && pos < seg.getEnd()) {
                    segmentAdapter.setCurrentSegmentIndex(i);
                    break;
                }
            }
        }
    }

    private void updateBufferProgress(int percent) {
        if (playbackService != null) {
            long dur = playbackService.getDuration();
            if (dur > 0) {
                seekBarCache.setMax((int) dur);
                seekBarCache.setProgress((int) (dur * percent / 100));
            }
            // 更新缓存进度条
            if (progressBuffer != null && progressBuffer.getVisibility() == View.VISIBLE) {
                progressBuffer.setProgress(percent);
            }
            // 更新缓存URL显示
            if (tvCacheUrl != null && tvCacheUrl.getVisibility() == View.VISIBLE) {
                String url = playbackService.getCurrentStreamUrl();
                if (url != null && !url.isEmpty()) {
                    tvCacheUrl.setText("缓存: " + percent + "% - " + url);
                }
            }
        }
    }

    private void startProgress() {
        handler = new Handler(Looper.getMainLooper());
        runnable = () -> {
            if (playbackService != null) {
                updateProgress(playbackService.getCurrentPosition(), playbackService.getDuration());
                updateBufferProgress(playbackService.getBufferPercent());
            }
            handler.postDelayed(runnable, 1000);
        };
        handler.post(runnable);
    }

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

    private void startSubtitleGen() {
        if (playbackService == null || playbackService.getCurrentEpisode() == null) return;
        generating = true;
        btnGenerateSubtitle.setEnabled(false);
        tvSubtitleStatus.setText("正在生成字幕...");
        subtitleManager.generateSubtitles(playbackService.getCurrentEpisode().getId(),
                playbackService.getCurrentEpisode().getAudioUrl(),
                new SubtitleGeneratorService.SubtitleCallback() {
                    @Override public void onSubtitleGenerated(Transcript t) {
                        runOnUiThread(() -> subtitleView.addRealtimeSubtitle(t));
                    }
                    @Override public void onProgressUpdate(int p, int t) {
                        runOnUiThread(() -> {
                            tvSubtitleStatus.setText(String.format("生成字幕: %d/%d", p, t));
                            if (p >= t) { generating = false; btnGenerateSubtitle.setEnabled(true); tvSubtitleStatus.setText("字幕生成完成"); }
                        });
                    }
                    @Override public void onError(String e) {
                        runOnUiThread(() -> {
                            generating = false;
                            btnGenerateSubtitle.setEnabled(true);
                            tvSubtitleStatus.setText("失败: " + e);
                            Toast.makeText(PlayerActivity.this, e, Toast.LENGTH_SHORT).show();
                        });
                    }
                });
    }

    private String fmtTime(long ms) { long s = ms / 1000; return String.format("%02d:%02d", s / 60, s % 60); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null && runnable != null) handler.removeCallbacks(runnable);
        if (playbackService != null) playbackService.setCallback(null);
        if (serviceBound) { unbindService(conn); serviceBound = false; }
        if (subtitleManager != null) subtitleManager.release();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver);
    }
}
