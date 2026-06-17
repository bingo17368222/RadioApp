package com.radio.app.activities;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.radio.app.R;
import com.radio.app.models.Episode;
import com.radio.app.services.RadioPlaybackService;

public class PlayerActivity extends AppCompatActivity {

    private TextView tvTitle, tvNetworkUrl, tvCacheUrl, tvStatus, tvProgress, tvBuffer;
    private SeekBar seekBar;
    private ImageButton btnPlayPause, btnStop, btnForward, btnRewind;

    private RadioPlaybackService playbackService;
    private boolean serviceBound = false;
    private Episode currentEpisode;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            RadioPlaybackService.LocalBinder binder = (RadioPlaybackService.LocalBinder) service;
            playbackService = binder.getService();
            serviceBound = true;
            playbackService.setCallback(playbackCallback);
            updateUI();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            playbackService = null;
            serviceBound = false;
        }
    };

    private RadioPlaybackService.Callback playbackCallback = new RadioPlaybackService.Callback() {
        @Override
        public void onStateChanged(boolean playing) {
            runOnUiThread(() -> {
                updatePlayPauseButton(playing);
                tvStatus.setText(playing ? "播放中" : "已暂停");
            });
        }

        @Override
        public void onPositionChanged(long position, long duration) {
            runOnUiThread(() -> {
                int pos = (int) position;
                int dur = (int) duration;
                if (dur > 0) {
                    seekBar.setMax(dur);
                    seekBar.setProgress(pos);
                    tvProgress.setText(formatTime(pos) + " / " + formatTime(dur));
                } else {
                    tvProgress.setText(formatTime(pos));
                }
            });
        }

        @Override
        public void onBufferUpdate(int percent) {
            runOnUiThread(() -> {
                tvBuffer.setText("缓冲: " + percent + "%");
                if (percent >= 100) {
                    tvBuffer.setVisibility(View.GONE);
                } else {
                    tvBuffer.setVisibility(View.VISIBLE);
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        currentEpisode = (Episode) getIntent().getSerializableExtra("episode");
        if (currentEpisode == null) {
            Toast.makeText(this, "无效的播放内容", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initViews();
        setupListeners();
        bindPlaybackService();
    }

    private void initViews() {
        tvTitle = findViewById(R.id.tv_station_name);
        tvNetworkUrl = findViewById(R.id.tv_network_url);
        tvCacheUrl = findViewById(R.id.tv_cache_url);
        tvStatus = findViewById(R.id.tv_live_indicator);
        tvProgress = findViewById(R.id.tv_current_time);
        tvBuffer = findViewById(R.id.tv_ai_progress);
        seekBar = findViewById(R.id.seek_bar);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnStop = findViewById(R.id.btn_skip_backward);
        btnForward = findViewById(R.id.btn_skip_forward);
        btnRewind = findViewById(R.id.btn_skip_backward);

        tvTitle.setText(currentEpisode.getTitle());
        tvNetworkUrl.setText("网络: " + currentEpisode.getAudioUrl());
        tvNetworkUrl.setVisibility(View.VISIBLE);
        tvCacheUrl.setVisibility(View.GONE);
        tvStatus.setText("准备播放...");
        tvStatus.setVisibility(View.VISIBLE);
        tvProgress.setText("00:00 / 00:00");
        tvBuffer.setText("缓冲: 0%");
        tvBuffer.setVisibility(View.VISIBLE);
    }

    private void setupListeners() {
        btnPlayPause.setOnClickListener(v -> {
            if (playbackService != null) {
                if (playbackService.isPlaying()) {
                    playbackService.pause();
                } else {
                    playbackService.play();
                }
            }
        });

        btnStop.setOnClickListener(v -> {
            if (playbackService != null) {
                playbackService.stop();
                updatePlayPauseButton(false);
                tvStatus.setText("已停止");
                tvProgress.setText("00:00 / 00:00");
                seekBar.setProgress(0);
            }
        });

        btnForward.setOnClickListener(v -> {
            if (playbackService != null) {
                playbackService.seekTo(playbackService.getCurrentPosition() + 30000);
            }
        });

        btnRewind.setOnClickListener(v -> {
            if (playbackService != null) {
                playbackService.seekTo(Math.max(0, playbackService.getCurrentPosition() - 30000));
            }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && playbackService != null) {
                    playbackService.seekTo(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void bindPlaybackService() {
        Intent intent = new Intent(this, RadioPlaybackService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void updateUI() {
        if (playbackService != null) {
            updatePlayPauseButton(playbackService.isPlaying());
        }
    }

    private void updatePlayPauseButton(boolean isPlaying) {
        btnPlayPause.setImageResource(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
    }

    private String formatTime(int millis) {
        int seconds = millis / 1000;
        int minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            if (playbackService != null) {
                playbackService.setCallback(null);
            }
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }
}
