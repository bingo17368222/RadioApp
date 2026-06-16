package com.radio.app.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.radio.app.R;
import com.radio.app.models.AppSettings;
import com.radio.app.utils.PreferenceManager;

public class KeywordSettingsActivity extends AppCompatActivity {

    private EditText etDryKeywords, etWaterKeywords, etContentDryKeywords, etContentWaterKeywords;
    private Spinner spinnerDryLogic, spinnerWaterLogic, spinnerContentDryLogic, spinnerContentWaterLogic;
    private PreferenceManager prefMgr;
    private AppSettings settings;
    private TextView tvTitle;
    private ImageButton btnBack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_keyword_settings);

        tvTitle = findViewById(R.id.tv_title);
        btnBack = findViewById(R.id.btn_back);
        if (tvTitle != null) tvTitle.setText("关键词设置");
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        prefMgr = new PreferenceManager(this);
        settings = prefMgr.loadSettings();

        etDryKeywords = findViewById(R.id.et_dry_keywords);
        etWaterKeywords = findViewById(R.id.et_water_keywords);
        etContentDryKeywords = findViewById(R.id.et_content_dry_keywords);
        etContentWaterKeywords = findViewById(R.id.et_content_water_keywords);
        spinnerDryLogic = findViewById(R.id.spinner_dry_logic);
        spinnerWaterLogic = findViewById(R.id.spinner_water_logic);
        spinnerContentDryLogic = findViewById(R.id.spinner_content_dry_logic);
        spinnerContentWaterLogic = findViewById(R.id.spinner_content_water_logic);

        ArrayAdapter<CharSequence> logicAdapter = ArrayAdapter.createFromResource(this,
                R.array.logic_options, android.R.layout.simple_spinner_item);
        logicAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDryLogic.setAdapter(logicAdapter);
        spinnerWaterLogic.setAdapter(logicAdapter);
        spinnerContentDryLogic.setAdapter(logicAdapter);
        spinnerContentWaterLogic.setAdapter(logicAdapter);

        loadCurrentSettings();

        findViewById(R.id.btn_save).setOnClickListener(v -> saveSettings());
        findViewById(R.id.btn_ai_extract_dry).setOnClickListener(v -> simulateAiExtract("dry"));
        findViewById(R.id.btn_ai_extract_water).setOnClickListener(v -> simulateAiExtract("water"));
        findViewById(R.id.btn_ai_extract_content_dry).setOnClickListener(v -> simulateAiExtract("content_dry"));
        findViewById(R.id.btn_ai_extract_content_water).setOnClickListener(v -> simulateAiExtract("content_water"));
    }

    private void loadCurrentSettings() {
        AppSettings.KeywordConfig config = settings.getKeywordConfig();
        etDryKeywords.setText(String.join(",", config.getDryKeywords()));
        etWaterKeywords.setText(String.join(",", config.getWaterKeywords()));
        etContentDryKeywords.setText(String.join(",", config.getContentDryKeywords()));
        etContentWaterKeywords.setText(String.join(",", config.getContentWaterKeywords()));

        setLogicSpinner(spinnerDryLogic, config.getDryLogic());
        setLogicSpinner(spinnerWaterLogic, config.getWaterLogic());
        setLogicSpinner(spinnerContentDryLogic, config.getContentDryLogic());
        setLogicSpinner(spinnerContentWaterLogic, config.getContentWaterLogic());
    }

    private void setLogicSpinner(Spinner spinner, String logic) {
        if ("and".equals(logic)) spinner.setSelection(1);
        else spinner.setSelection(0);
    }

    private String getLogicFromSpinner(Spinner spinner) {
        return spinner.getSelectedItemPosition() == 1 ? "and" : "or";
    }

    private void saveSettings() {
        AppSettings.KeywordConfig config = settings.getKeywordConfig();
        config.setDryKeywords(parseKeywords(etDryKeywords.getText().toString()));
        config.setWaterKeywords(parseKeywords(etWaterKeywords.getText().toString()));
        config.setContentDryKeywords(parseKeywords(etContentDryKeywords.getText().toString()));
        config.setContentWaterKeywords(parseKeywords(etContentWaterKeywords.getText().toString()));
        config.setDryLogic(getLogicFromSpinner(spinnerDryLogic));
        config.setWaterLogic(getLogicFromSpinner(spinnerWaterLogic));
        config.setContentDryLogic(getLogicFromSpinner(spinnerContentDryLogic));
        config.setContentWaterLogic(getLogicFromSpinner(spinnerContentWaterLogic));
        settings.setKeywordConfig(config);
        prefMgr.saveSettings(settings);
        Toast.makeText(this, "关键词设置已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    private java.util.List<String> parseKeywords(String text) {
        java.util.List<String> list = new java.util.ArrayList<>();
        if (text == null || text.trim().isEmpty()) return list;
        String[] parts = text.split("[,，]");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) list.add(trimmed);
        }
        return list;
    }

    private void simulateAiExtract(String type) {
        Toast.makeText(this, "AI提取功能（模拟）- " + type, Toast.LENGTH_SHORT).show();
    }
}
