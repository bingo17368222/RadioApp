package com.radio.app.activities;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.radio.app.R;
import com.radio.app.managers.SubtitleManager;
import com.radio.app.models.Transcript;
import com.radio.app.services.RadioPlaybackService;
import com.radio.app.services.SubtitleGeneratorService;
import com.radio.app.views.SubtitleView;

import java.util.List;

public class PlayerActivity extends AppCompatActivity {
    private TextView tvStationName, tvEpisodeTitle, tvCurrentTime, tvTotalTime, tvLiveIndicator, tvSubtitleStatus;
    private SeekBar seekBar;
    private ImageButton btnPlayPause, btnSkipBackward, btnSkipForward, btnClose, btnGenerateSubtitle;
    private SubtitleView subtitleView;
    private RadioPlaybackService playbackService;
    private boolean serviceBound = false;
    private Handler handler;
    private Runnable runnable;
    private SubtitleManager subtitleManager;
    private boolean generating = false;

    private ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder s) {
            playbackService = ((RadioPlaybackService.LocalBinder) s).getService();
            serviceBound = true;
            playbackService.setCallback(new RadioPlaybackService.Callback() {
                @Override public void onStateChanged(boolean playing) { updatePlayBtn(); }
                @Override public void onPositionChanged(long pos, long dur) { updateProgress(pos, dur); }
            });
            updateUI(); loadSubtitles();
        }
        @Override public void onServiceDisconnected(ComponentName name) { playbackService = null; serviceBound = false; }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        initViews();
        setupListeners();
        startProgress();
        subtitleManager = new SubtitleManager(this);
        bindService(new Intent(this, RadioPlaybackService.class), conn, Context.BIND_AUTO_CREATE);
    }

    private void initViews() {
        tvStationName = findViewById(R.id.tv_station_name);
        tvEpisodeTitle = findViewById(R.id.tv_episode_title);
        tvCurrentTime = findViewById(R.id.tv_current_time);
        tvTotalTime = findViewById(R.id.tv_total_time);
        tvLiveIndicator = findViewById(R.id.tv_live_indicator);
        tvSubtitleStatus = findViewById(R.id.tv_subtitle_status);
        seekBar = findViewById(R.id.seek_bar);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnSkipBackward = findViewById(R.id.btn_skip_backward);
        btnSkipForward = findViewById(R.id.btn_skip_forward);
        btnClose = findViewById(R.id.btn_close);
        btnGenerateSubtitle = findViewById(R.id.btn_generate_subtitle);
        subtitleView = findViewById(R.id.subtitle_view);
        subtitleView.setOnSubtitleClickListener(startTime -> { if (playbackService != null && !playbackService.isLive()) playbackService.seekTo(startTime * 1000); });
    }

    private void setupListeners() {
        btnClose.setOnClickListener(v -> finish());
        btnPlayPause.setOnClickListener(v -> { if (playbackService != null) { if (playbackService.isPlaying()) playbackService.pause(); else playbackService.play(); } });
        btnSkipBackward.setOnClickListener(v -> { if (playbackService != null) playbackService.skipBackward(); });
        btnSkipForward.setOnClickListener(v -> { if (playbackService != null) playbackService.skipForward(); });
        btnGenerateSubtitle.setOnClickListener(v -> { if (!generating) startSubtitleGen(); });
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { if (fromUser && playbackService != null && !playbackService.isLive()) playbackService.seekTo(p); }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void updateUI() {
        if (playbackService == null) return;
        if (playbackService.getCurrentEpisode() != null) {
            tvStationName.setText(playbackService.getCurrentEpisode().getStationName());
            tvEpisodeTitle.setText(playbackService.getCurrentEpisode().getTitle());
        }
        boolean live = playbackService.isLive();
        tvLiveIndicator.setVisibility(live ? View.VISIBLE : View.GONE);
        seekBar.setEnabled(!live);
        btnGenerateSubtitle.setVisibility(live ? View.GONE : View.VISIBLE);
        updatePlayBtn();
    }

    private void updatePlayBtn() { if (playbackService != null) btnPlayPause.setImageResource(playbackService.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play); }

    private void updateProgress(long pos, long dur) {
        tvCurrentTime.setText(fmtTime(pos));
        tvTotalTime.setText(fmtTime(dur));
        if (dur > 0) { seekBar.setMax((int) dur); seekBar.setProgress((int) pos); }
        if (subtitleView != null) subtitleView.highlightSubtitle(pos / 1000);
    }

    private void startProgress() {
        handler = new Handler(Looper.getMainLooper());
        runnable = () -> { if (playbackService != null) updateProgress(playbackService.getCurrentPosition(), playbackService.getDuration()); handler.postDelayed(runnable, 1000); };
        handler.post(runnable);
    }

    private void loadSubtitles() {
        if (playbackService == null || playbackService.getCurrentEpisode() == null) return;
        List<Transcript> list = subtitleManager.getSubtitles(playbackService.getCurrentEpisode().getId());
        if (list != null && !list.isEmpty()) { subtitleView.setSubtitles(list); tvSubtitleStatus.setText("字幕已加载"); tvSubtitleStatus.setVisibility(View.VISIBLE); }
        else { tvSubtitleStatus.setText("点击生成字幕"); tvSubtitleStatus.setVisibility(View.VISIBLE); }
    }

    private void startSubtitleGen() {
        if (playbackService == null || playbackService.getCurrentEpisode() == null) return;
        generating = true;
        btnGenerateSubtitle.setEnabled(false);
        tvSubtitleStatus.setText("正在生成字幕...");
        subtitleManager.generateSubtitles(playbackService.getCurrentEpisode().getId(), playbackService.getCurrentEpisode().getAudioUrl(), new SubtitleGeneratorService.SubtitleCallback() {
            @Override public void onSubtitleGenerated(Transcript t) { runOnUiThread(() -> subtitleView.addRealtimeSubtitle(t)); }
            @Override public void onProgressUpdate(int p, int t) { runOnUiThread(() -> tvSubtitleStatus.setText(String.format("生成字幕: %d/%d", p, t))); }
            @Override public void onError(String e) { runOnUiThread(() -> { generating = false; btnGenerateSubtitle.setEnabled(true); tvSubtitleStatus.setText("失败: " + e); Toast.makeText(PlayerActivity.this, e, Toast.LENGTH_SHORT).show(); }); }
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
    }
}
