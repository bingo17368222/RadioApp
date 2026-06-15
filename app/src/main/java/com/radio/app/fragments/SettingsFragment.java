package com.radio.app.fragments;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
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

        String[] aiModels = {"文心 ERNIE", "DeepSeek", "通义千问 Qwen", "FunASR", "Whisper", "就AI听"};
        String[] aiVals = {AppSettings.AI_MODEL_WENXIN, AppSettings.AI_MODEL_DEEPSEEK, AppSettings.AI_MODEL_QWEN, AppSettings.AI_MODEL_FUNASR, AppSettings.AI_MODEL_WHISPER, AppSettings.AI_MODEL_JIU_AI_TING};
        ArrayAdapter<String> aiAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, aiModels);
        aiAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAiModel.setAdapter(aiAdapter);
        spinnerAiModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int i, long id) { settings.setAiModel(aiVals[i]); save(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        String[] asrNames = {"百度ASR", "FunASR", "Whisper", "Vosk"};
        String[] asrVals = {AppSettings.ASR_BAIDU, AppSettings.ASR_FUNASR, AppSettings.ASR_WHISPER, AppSettings.ASR_VOSK};
        ArrayAdapter<String> asrAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, asrNames);
        asrAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAsr.setAdapter(asrAdapter);
        spinnerAsr.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int i, long id) { settings.setAsrProvider(asrVals[i]); save(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        String[] themes = {"默认暗色", "清新风格", "经典蓝", "简约黑白", "自定义"};
        String[] themeVals = {AppSettings.THEME_DARK, AppSettings.THEME_FRESH, AppSettings.THEME_CLASSIC, AppSettings.THEME_MINIMAL, AppSettings.THEME_CUSTOM};
        ArrayAdapter<String> themeAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, themes);
        themeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTheme.setAdapter(themeAdapter);
        spinnerTheme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int i, long id) { settings.setUiTheme(themeVals[i]); save(); themeMgr.reloadSettings(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        swPreload.setChecked(settings.isPreloadCache());
        swPreprocess.setChecked(settings.isEnablePreprocessing());
        swAudioFocus.setChecked(settings.isAudioFocus());
        swPreload.setOnCheckedChangeListener((b, c) -> { settings.setPreloadCache(c); save(); swPreprocess.setEnabled(c); });
        swPreprocess.setOnCheckedChangeListener((b, c) -> { settings.setEnablePreprocessing(c); save(); });
        swAudioFocus.setOnCheckedChangeListener((b, c) -> { settings.setAudioFocus(c); save(); });

        view.findViewById(R.id.tv_clear_cache).setOnClickListener(v -> new AlertDialog.Builder(requireContext()).setTitle("清理缓存").setMessage("确定清理?").setPositiveButton("确定", (d, w) -> { prefMgr.clearCache(); Toast.makeText(requireContext(), "已清理", Toast.LENGTH_SHORT).show(); }).setNegativeButton("取消", null).show());

        return view;
    }

    private void save() { prefMgr.saveSettings(settings); }
}
