package com.radio.app.fragments;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.NumberPicker;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.radio.app.R;
import com.radio.app.models.AppSettings;
import com.radio.app.utils.PreferenceManager;
import com.radio.app.utils.ThemeManager;

public class SettingsFragment extends Fragment {
    private Spinner spinnerAiModel, spinnerAsr, spinnerTheme;
    private Switch swPreload, swPreprocess, swAudioFocus;
    private TextView tvCustomColors;
    private TextView tvPreloadCount, tvPreprocessCount;
    private LinearLayout layoutPreloadCount, layoutPreprocessCount;
    private PreferenceManager prefMgr;
    private ThemeManager themeMgr;
    private AppSettings settings;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);
        prefMgr = new PreferenceManager(requireContext());
        themeMgr = new ThemeManager(requireContext());
        settings = prefMgr.loadSettings();

        spinnerAiModel = view.findViewById(R.id.spinner_ai_model);
        spinnerAsr = view.findViewById(R.id.spinner_asr_provider);
        spinnerTheme = view.findViewById(R.id.spinner_theme);
        swPreload = view.findViewById(R.id.switch_preload_cache);
        swPreprocess = view.findViewById(R.id.switch_enable_preprocessing);
        swAudioFocus = view.findViewById(R.id.switch_audio_focus);
        tvCustomColors = view.findViewById(R.id.tv_custom_colors);
        tvPreloadCount = view.findViewById(R.id.tv_preload_count);
        tvPreprocessCount = view.findViewById(R.id.tv_preprocess_count);
        layoutPreloadCount = view.findViewById(R.id.layout_preload_count);
        layoutPreprocessCount = view.findViewById(R.id.layout_preprocess_count);

        // AI模型
        String[] aiModels = {"文心 ERNIE", "DeepSeek", "通义千问 Qwen", "FunASR", "Whisper", "就AI听"};
        String[] aiVals = {AppSettings.AI_MODEL_WENXIN, AppSettings.AI_MODEL_DEEPSEEK, AppSettings.AI_MODEL_QWEN, AppSettings.AI_MODEL_FUNASR, AppSettings.AI_MODEL_WHISPER, AppSettings.AI_MODEL_JIU_AI_TING};
        ArrayAdapter<String> aiAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, aiModels);
        aiAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAiModel.setAdapter(aiAdapter);
        spinnerAiModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int i, long id) { 
                settings.setAiModel(aiVals[i]); 
                save(); 
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        // ASR
        String[] asrNames = {"百度ASR", "FunASR", "Whisper", "Vosk"};
        String[] asrVals = {AppSettings.ASR_BAIDU, AppSettings.ASR_FUNASR, AppSettings.ASR_WHISPER, AppSettings.ASR_VOSK};
        ArrayAdapter<String> asrAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, asrNames);
        asrAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAsr.setAdapter(asrAdapter);
        spinnerAsr.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int i, long id) { 
                settings.setAsrProvider(asrVals[i]); 
                save(); 
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        // 主题
        String[] themes = {"默认暗色", "清新风格", "经典蓝", "简约黑白", "自定义"};
        String[] themeVals = {AppSettings.THEME_DARK, AppSettings.THEME_FRESH, AppSettings.THEME_CLASSIC, AppSettings.THEME_MINIMAL, AppSettings.THEME_CUSTOM};
        ArrayAdapter<String> themeAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, themes);
        themeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTheme.setAdapter(themeAdapter);
        spinnerTheme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int i, long id) { 
                settings.setUiTheme(themeVals[i]); 
                save(); 
                applyTheme();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        // 自定义颜色按钮
        tvCustomColors.setOnClickListener(v -> showCustomColorDialog());
        updateCustomColorsVisibility();

        // 开关
        swPreload.setChecked(settings.isPreloadCache());
        swPreprocess.setChecked(settings.isEnablePreprocessing());
        swAudioFocus.setChecked(settings.isAudioFocus());
        swPreload.setOnCheckedChangeListener((b, c) -> { 
            settings.setPreloadCache(c); 
            save(); 
            swPreprocess.setEnabled(c); 
            updateCountVisibility();
        });
        swPreprocess.setOnCheckedChangeListener((b, c) -> { 
            settings.setEnablePreprocessing(c); 
            save(); 
            updateCountVisibility();
        });
        swAudioFocus.setOnCheckedChangeListener((b, c) -> { 
            settings.setAudioFocus(c); 
            save(); 
        });

        // 个数设置点击
        layoutPreloadCount.setOnClickListener(v -> showCountPicker("预缓存个数", 1, 10, settings.getPreloadCacheCount(), count -> {
            settings.setPreloadCacheCount(count);
            save();
            updateCountDisplay();
        }));
        layoutPreprocessCount.setOnClickListener(v -> showCountPicker("预处理个数", 1, 10, settings.getPreprocessingCount(), count -> {
            settings.setPreprocessingCount(count);
            save();
            updateCountDisplay();
        }));

        // 清理缓存
        view.findViewById(R.id.tv_clear_cache).setOnClickListener(v -> showClearCacheDialog());

        // 加载当前设置
        loadSettings(aiVals, asrVals, themeVals);
        
        return view;
    }

    private void applyTheme() {
        // 重新创建Activity以应用主题
        Intent intent = getActivity().getIntent();
        getActivity().finish();
        startActivity(intent);
    }

    private void updateCustomColorsVisibility() {
        tvCustomColors.setVisibility(
            AppSettings.THEME_CUSTOM.equals(settings.getUiTheme()) ? View.VISIBLE : View.GONE
        );
    }

    private void showCustomColorDialog() {
        AppSettings.CustomColors colors = settings.getCustomColors();
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 16, 32, 16);

        String[] labels = {"主色", "强调色", "背景色", "文字色", "卡片色", "边框色", "成功色", "警告色"};
        String[] keys = {"primary", "accent", "background", "text", "card", "border", "success", "warning"};
        String[] values = {colors.getPrimary(), colors.getAccent(), colors.getBackground(), colors.getText(),
                          colors.getCard(), colors.getBorder(), colors.getSuccess(), colors.getWarning()};

        EditText[] edits = new EditText[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextView tv = new TextView(requireContext());
            tv.setText(labels[i] + " (当前: " + values[i] + ")");
            tv.setTextColor(getResources().getColor(R.color.text_primary));
            layout.addView(tv);
            
            EditText et = new EditText(requireContext());
            et.setText(values[i]);
            et.setTextColor(getResources().getColor(R.color.text_primary));
            edits[i] = et;
            layout.addView(et);
        }

        new AlertDialog.Builder(requireContext())
            .setTitle("自定义颜色")
            .setView(layout)
            .setPositiveButton("保存", (d, w) -> {
                colors.setPrimary(edits[0].getText().toString());
                colors.setAccent(edits[1].getText().toString());
                colors.setBackground(edits[2].getText().toString());
                colors.setText(edits[3].getText().toString());
                colors.setCard(edits[4].getText().toString());
                colors.setBorder(edits[5].getText().toString());
                colors.setSuccess(edits[6].getText().toString());
                colors.setWarning(edits[7].getText().toString());
                settings.setCustomColors(colors);
                save();
                applyTheme();
                Toast.makeText(requireContext(), "颜色已保存", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showClearCacheDialog() {
        // 获取缓存文件列表
        java.io.File cacheDir = requireContext().getCacheDir();
        java.io.File[] files = cacheDir.listFiles();
        
        if (files == null || files.length == 0) {
            Toast.makeText(requireContext(), "暂无缓存文件", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] fileNames = new String[files.length];
        boolean[] checked = new boolean[files.length];
        for (int i = 0; i < files.length; i++) {
            fileNames[i] = files[i].getName() + " (" + formatSize(files[i].length()) + ")";
            checked[i] = true;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("清理缓存");
        builder.setMultiChoiceItems(fileNames, checked, (d, which, isChecked) -> checked[which] = isChecked);
        
        LinearLayout btnLayout = new LinearLayout(requireContext());
        btnLayout.setOrientation(LinearLayout.HORIZONTAL);
        btnLayout.setGravity(android.view.Gravity.CENTER);
        btnLayout.setPadding(0, 16, 0, 0);
        
        builder.setPositiveButton("删除选中", (d, w) -> {
            int count = 0;
            for (int i = 0; i < files.length; i++) {
                if (checked[i] && files[i].delete()) count++;
            }
            Toast.makeText(requireContext(), "已删除 " + count + " 个文件", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("取消", null);
        
        AlertDialog dialog = builder.create();
        dialog.show();
        
        // 添加全选/全不选/反选按钮
        android.view.ViewGroup parent = (android.view.ViewGroup) dialog.getButton(AlertDialog.BUTTON_POSITIVE).getParent();
        
        Button btnSelectAll = new Button(requireContext());
        btnSelectAll.setText("全选");
        btnSelectAll.setOnClickListener(v -> {
            for (int i = 0; i < checked.length; i++) {
                checked[i] = true;
                dialog.getListView().setItemChecked(i, true);
            }
        });
        
        Button btnSelectNone = new Button(requireContext());
        btnSelectNone.setText("全不选");
        btnSelectNone.setOnClickListener(v -> {
            for (int i = 0; i < checked.length; i++) {
                checked[i] = false;
                dialog.getListView().setItemChecked(i, false);
            }
        });
        
        Button btnInvert = new Button(requireContext());
        btnInvert.setText("反选");
        btnInvert.setOnClickListener(v -> {
            for (int i = 0; i < checked.length; i++) {
                checked[i] = !checked[i];
                dialog.getListView().setItemChecked(i, checked[i]);
            }
        });
        
        parent.addView(btnSelectAll, 0);
        parent.addView(btnSelectNone, 1);
        parent.addView(btnInvert, 2);
    }

    private String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        return String.format("%.1f MB", size / (1024.0 * 1024));
    }

    private void loadSettings(String[] aiVals, String[] asrVals, String[] themeVals) {
        for (int i = 0; i < aiVals.length; i++) {
            if (aiVals[i].equals(settings.getAiModel())) { spinnerAiModel.setSelection(i); break; }
        }
        for (int i = 0; i < asrVals.length; i++) {
            if (asrVals[i].equals(settings.getAsrProvider())) { spinnerAsr.setSelection(i); break; }
        }
        for (int i = 0; i < themeVals.length; i++) {
            if (themeVals[i].equals(settings.getUiTheme())) { spinnerTheme.setSelection(i); break; }
        }
        updateCustomColorsVisibility();
        updateCountDisplay();
        updateCountVisibility();
    }

    private void updateCountDisplay() {
        tvPreloadCount.setText(String.valueOf(settings.getPreloadCacheCount()));
        tvPreprocessCount.setText(String.valueOf(settings.getPreprocessingCount()));
    }

    private void updateCountVisibility() {
        layoutPreloadCount.setVisibility(settings.isPreloadCache() ? View.VISIBLE : View.GONE);
        layoutPreprocessCount.setVisibility(settings.isEnablePreprocessing() ? View.VISIBLE : View.GONE);
    }

    private void showCountPicker(String title, int min, int max, int current, CountCallback callback) {
        NumberPicker picker = new NumberPicker(requireContext());
        picker.setMinValue(min);
        picker.setMaxValue(max);
        picker.setValue(current);
        new AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(picker)
            .setPositiveButton("确定", (d, w) -> callback.onCountSelected(picker.getValue()))
            .setNegativeButton("取消", null)
            .show();
    }

    private interface CountCallback {
        void onCountSelected(int count);
    }

    private void save() { 
        prefMgr.saveSettings(settings); 
    }
}
