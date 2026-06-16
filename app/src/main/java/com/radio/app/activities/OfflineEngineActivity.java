package com.radio.app.activities;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.radio.app.R;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OfflineEngineActivity extends AppCompatActivity {
    private Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Map<String, Boolean> engineStatus = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offline_engine);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle("离线引擎管理");

        // 初始化引擎状态（模拟）
        engineStatus.put("whisper", false);
        engineStatus.put("vosk", false);
        engineStatus.put("pocketsphinx", false);

        setupEngine("whisper", R.id.btn_whisper_action, R.id.tv_whisper_status, R.id.progress_whisper, "Whisper");
        setupEngine("vosk", R.id.btn_vosk_action, R.id.tv_vosk_status, R.id.progress_vosk, "Vosk");
        setupEngine("pocketsphinx", R.id.btn_pocketsphinx_action, R.id.tv_pocketsphinx_status, R.id.progress_pocketsphinx, "PocketSphinx");
    }

    private void setupEngine(String engineId, int btnId, int statusId, int progressId, String name) {
        Button btn = findViewById(btnId);
        TextView tvStatus = findViewById(statusId);
        ProgressBar progress = findViewById(progressId);

        updateEngineUI(btn, tvStatus, progress, engineStatus.get(engineId));

        btn.setOnClickListener(v -> {
            boolean isInstalled = engineStatus.get(engineId);
            if (isInstalled) {
                // 模拟删除
                engineStatus.put(engineId, false);
                updateEngineUI(btn, tvStatus, progress, false);
                Toast.makeText(this, name + " 已删除", Toast.LENGTH_SHORT).show();
            } else {
                // 模拟安装
                btn.setEnabled(false);
                progress.setVisibility(View.VISIBLE);
                progress.setProgress(0);
                tvStatus.setText("下载中...");
                tvStatus.setTextColor(getResources().getColor(R.color.accent));

                executor.execute(() -> {
                    for (int i = 0; i <= 100; i += 5) {
                        try { Thread.sleep(50); } catch (InterruptedException e) { break; }
                        int finalProgress = i;
                        handler.post(() -> {
                            progress.setProgress(finalProgress);
                            tvStatus.setText("下载中... " + finalProgress + "%");
                        });
                    }
                    handler.post(() -> {
                        engineStatus.put(engineId, true);
                        updateEngineUI(btn, tvStatus, progress, true);
                        btn.setEnabled(true);
                        Toast.makeText(this, name + " 安装完成", Toast.LENGTH_SHORT).show();
                    });
                });
            }
        });
    }

    private void updateEngineUI(Button btn, TextView tvStatus, ProgressBar progress, boolean isInstalled) {
        if (isInstalled) {
            btn.setText("删除");
            btn.setBackgroundTintList(getResources().getColorStateList(R.color.accent));
            tvStatus.setText("已下载");
            tvStatus.setTextColor(getResources().getColor(R.color.success));
            progress.setVisibility(View.GONE);
        } else {
            btn.setText("安装");
            btn.setBackgroundTintList(getResources().getColorStateList(R.color.success));
            tvStatus.setText("未下载");
            tvStatus.setTextColor(getResources().getColor(R.color.warning));
            progress.setVisibility(View.GONE);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
