package com.radio.app.activities;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.radio.app.R;
import com.radio.app.models.AppSettings;
import com.radio.app.utils.PreferenceManager;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class KeywordSettingsActivity extends AppCompatActivity {
    private EditText etDryKeywords, etWaterKeywords, etContentDryKeywords, etContentWaterKeywords;
    private Spinner spinnerDryLogic, spinnerWaterLogic, spinnerContentDryLogic, spinnerContentWaterLogic;
    private PreferenceManager prefMgr;
    private AppSettings settings;
    private Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_keyword_settings);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle("关键词设置");

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

        // 加载当前设置
        loadCurrentSettings();

        // AI提取按钮
        findViewById(R.id.btn_ai_dry).setOnClickListener(v -> simulateAiExtract(etDryKeywords, "干货"));
        findViewById(R.id.btn_ai_water).setOnClickListener(v -> simulateAiExtract(etWaterKeywords, "水分"));
        findViewById(R.id.btn_ai_content_dry).setOnClickListener(v -> simulateAiExtract(etContentDryKeywords, "内容干货"));
        findViewById(R.id.btn_ai_content_water).setOnClickListener(v -> simulateAiExtract(etContentWaterKeywords, "内容水分"));

        // 保存按钮
        findViewById(R.id.btn_save).setOnClickListener(v -> saveSettings());
    }

    private void loadCurrentSettings() {
        AppSettings.KeywordConfig config = settings.getKeywordConfig();
        etDryKeywords.setText(join(config.getDryKeywords()));
        etWaterKeywords.setText(join(config.getWaterKeywords()));
        etContentDryKeywords.setText(join(config.getContentDryKeywords()));
        etContentWaterKeywords.setText(join(config.getContentWaterKeywords()));

        setSpinnerSelection(spinnerDryLogic, "or".equals(config.getDryLogic()) ? 0 : 1);
        setSpinnerSelection(spinnerWaterLogic, "or".equals(config.getWaterLogic()) ? 0 : 1);
        setSpinnerSelection(spinnerContentDryLogic, "or".equals(config.getContentDryLogic()) ? 0 : 1);
        setSpinnerSelection(spinnerContentWaterLogic, "or".equals(config.getContentWaterLogic()) ? 0 : 1);
    }

    private String join(List<String> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private List<String> split(String text) {
        if (text == null || text.trim().isEmpty()) return new java.util.ArrayList<>();
        String[] parts = text.split("[,，]");
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    private void setSpinnerSelection(Spinner spinner, int index) {
        spinner.setSelection(index);
    }

    private void simulateAiExtract(EditText editText, String type) {
        Button btn = null;
        // 找到对应的按钮
        if (editText == etDryKeywords) btn = findViewById(R.id.btn_ai_dry);
        else if (editText == etWaterKeywords) btn = findViewById(R.id.btn_ai_water);
        else if (editText == etContentDryKeywords) btn = findViewById(R.id.btn_ai_content_dry);
        else if (editText == etContentWaterKeywords) btn = findViewById(R.id.btn_ai_content_water);

        if (btn == null) return;
        btn.setEnabled(false);
        btn.setText("AI提取中... (3s)");

        final int[] countdown = {3};
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (countdown[0] > 0) {
                    btn.setText("AI提取中... (" + countdown[0] + "s)");
                    countdown[0]--;
                    handler.postDelayed(this, 1000);
                } else {
                    btn.setEnabled(true);
                    btn.setText("AI提取");
                    // 模拟返回关键词
                    String[] mockKeywords;
                    switch (type) {
                        case "干货":
                            mockKeywords = new String[]{"新闻", "资讯", "报道", "访谈", "评论", "分析", "深度"};
                            break;
                        case "水分":
                            mockKeywords = new String[]{"广告", "音乐", "歌曲", "休息", "片头", "片尾"};
                            break;
                        case "内容干货":
                            mockKeywords = new String[]{"观点", "数据", "案例", "专家", "解读", "趋势"};
                            break;
                        case "内容水分":
                            mockKeywords = new String[]{"重复", "废话", "填充", "寒暄", "过渡"};
                            break;
                        default:
                            mockKeywords = new String[]{"关键词1", "关键词2"};
                    }
                    editText.setText(join(Arrays.asList(mockKeywords)));
                    Toast.makeText(KeywordSettingsActivity.this, type + "关键词已提取", Toast.LENGTH_SHORT).show();
                }
            }
        }, 1000);
    }

    private void saveSettings() {
        AppSettings.KeywordConfig config = settings.getKeywordConfig();
        config.setDryKeywords(split(etDryKeywords.getText().toString()));
        config.setWaterKeywords(split(etWaterKeywords.getText().toString()));
        config.setContentDryKeywords(split(etContentDryKeywords.getText().toString()));
        config.setContentWaterKeywords(split(etContentWaterKeywords.getText().toString()));

        config.setDryLogic(spinnerDryLogic.getSelectedItemPosition() == 0 ? "or" : "and");
        config.setWaterLogic(spinnerWaterLogic.getSelectedItemPosition() == 0 ? "or" : "and");
        config.setContentDryLogic(spinnerContentDryLogic.getSelectedItemPosition() == 0 ? "or" : "and");
        config.setContentWaterLogic(spinnerContentWaterLogic.getSelectedItemPosition() == 0 ? "or" : "and");

        settings.setKeywordConfig(config);
        prefMgr.saveSettings(settings);
        Toast.makeText(this, "关键词设置已保存", Toast.LENGTH_SHORT).show();
        finish();
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
