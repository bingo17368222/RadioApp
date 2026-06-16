package com.radio.app.activities;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.radio.app.R;

public class OfflineEngineActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offline_engine);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle("离线引擎管理");

        setupEngineCard("Whisper", "OpenAI语音识别模型", "约75MB", false);
        setupEngineCard("Vosk", "轻量级离线语音识别", "约50MB", false);
        setupEngineCard("PocketSphinx", "CMU离线语音识别", "约30MB", false);
    }

    private void setupEngineCard(String name, String description, String size, boolean installed) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_engine_card, null, false);
        TextView tvName = card.findViewById(R.id.tv_engine_name);
        TextView tvDesc = card.findViewById(R.id.tv_engine_desc);
        TextView tvSize = card.findViewById(R.id.tv_engine_size);
        Button btnAction = card.findViewById(R.id.btn_engine_action);

        tvName.setText(name);
        tvDesc.setText(description);
        tvSize.setText(size);

        if (installed) {
            btnAction.setText("删除");
            btnAction.setOnClickListener(v -> {
                btnAction.setText("安装");
                Toast.makeText(this, name + " 已删除", Toast.LENGTH_SHORT).show();
            });
        } else {
            btnAction.setText("安装");
            btnAction.setOnClickListener(v -> {
                btnAction.setText("删除");
                Toast.makeText(this, name + " 安装完成（模拟）", Toast.LENGTH_SHORT).show();
            });
        }

        ((android.widget.LinearLayout) findViewById(R.id.engine_container)).addView(card);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
