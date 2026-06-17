package com.radio.app.fragments;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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
import com.radio.app.activities.OfflineEngineActivity;

import java.io.File;

public class SettingsFragment extends Fragment {

    private PreferenceManager prefManager;
    private AppSettings settings;
    private String previousTheme;

    private Switch switchAutoSkip, switchContinuousPlay, switchAutoDownload, switchAutoCache;
    private Spinner spinnerTheme, spinnerSubtitleSize, spinnerSubtitleLang, spinnerVoiceLang;
    private TextView tvCacheSize, btnClearCache, btnManageOfflineEngine, btnCustomizeColors;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);
        prefManager = new PreferenceManager(requireContext());
        settings = prefManager.loadSettings();
        previousTheme = settings.getUiTheme();

        initViews(view);
        setupListeners();
        updateUI();
        return view;
    }

    private void initViews(View view) {
        switchAutoSkip = view.findViewById(R.id.switch_auto_skip);
        switchContinuousPlay = view.findViewById(R.id.switch_continuous_play);
        switchAutoDownload = view.findViewById(R.id.switch_auto_download);
        switchAutoCache = view.findViewById(R.id.switch_auto_cache);
        spinnerTheme = view.findViewById(R.id.spinner_theme);
        spinnerSubtitleSize = view.findViewById(R.id.spinner_subtitle_size);
        spinnerSubtitleLang = view.findViewById(R.id.spinner_subtitle_lang);
        spinnerVoiceLang = view.findViewById(R.id.spinner_voice_lang);
        tvCacheSize = view.findViewById(R.id.tv_cache_size);
        btnClearCache = view.findViewById(R.id.btn_clear_cache);
        btnManageOfflineEngine = view.findViewById(R.id.btn_manage_offline_engine);
        btnCustomizeColors = view.findViewById(R.id.btn_customize_colors);
    }

    private void setupListeners() {
        switchAutoSkip.setOnCheckedChangeListener((buttonView, isChecked) -> { settings.setAutoSkipWater(isChecked); save(); });
        switchContinuousPlay.setOnCheckedChangeListener((buttonView, isChecked) -> { settings.setContinuousPlay(isChecked); save(); });
        switchAutoDownload.setOnCheckedChangeListener((buttonView, isChecked) -> { settings.setAutoDownload(isChecked); save(); });
        switchAutoCache.setOnCheckedChangeListener((buttonView, isChecked) -> { settings.setAutoCache(isChecked); save(); });

        spinnerTheme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String[] themes = {AppSettings.THEME_DARK, AppSettings.THEME_FRESH, AppSettings.THEME_CLASSIC, AppSettings.THEME_MINIMAL, AppSettings.THEME_CUSTOM};
                settings.setUiTheme(themes[position]);
                save();
                applyTheme();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        spinnerSubtitleSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String[] sizes = {AppSettings.SUBTITLE_SMALL, AppSettings.SUBTITLE_MEDIUM, AppSettings.SUBTITLE_LARGE};
                settings.setSubtitleSize(sizes[position]);
                save();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        spinnerSubtitleLang.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String[] langs = {AppSettings.LANG_CN, AppSettings.LANG_EN};
                settings.setSubtitleLanguage(langs[position]);
                save();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        spinnerVoiceLang.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String[] langs = {AppSettings.LANG_CN, AppSettings.LANG_EN};
                settings.setVoiceLanguage(langs[position]);
                save();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnClearCache.setOnClickListener(v -> showClearCacheDialog());
        btnManageOfflineEngine.setOnClickListener(v -> startActivity(new Intent(requireContext(), OfflineEngineActivity.class)));
        btnCustomizeColors.setOnClickListener(v -> showColorPickerDialog());
    }

    private void updateUI() {
        switchAutoSkip.setChecked(settings.isAutoSkipWater());
        switchContinuousPlay.setChecked(settings.isContinuousPlay());
        switchAutoDownload.setChecked(settings.isAutoDownload());
        switchAutoCache.setChecked(settings.isAutoCache());

        String[] themes = {AppSettings.THEME_DARK, AppSettings.THEME_FRESH, AppSettings.THEME_CLASSIC, AppSettings.THEME_MINIMAL, AppSettings.THEME_CUSTOM};
        for (int i = 0; i < themes.length; i++) { if (themes[i].equals(settings.getUiTheme())) { spinnerTheme.setSelection(i); break; } }

        String[] sizes = {AppSettings.SUBTITLE_SMALL, AppSettings.SUBTITLE_MEDIUM, AppSettings.SUBTITLE_LARGE};
        for (int i = 0; i < sizes.length; i++) { if (sizes[i].equals(settings.getSubtitleSize())) { spinnerSubtitleSize.setSelection(i); break; } }

        String[] langs = {AppSettings.LANG_CN, AppSettings.LANG_EN};
        for (int i = 0; i < langs.length; i++) { if (langs[i].equals(settings.getSubtitleLanguage())) { spinnerSubtitleLang.setSelection(i); break; } }
        for (int i = 0; i < langs.length; i++) { if (langs[i].equals(settings.getVoiceLanguage())) { spinnerVoiceLang.setSelection(i); break; } }

        long cacheSize = calculateCacheSize();
        tvCacheSize.setText("缓存大小: " + formatSize(cacheSize));
    }

    private long calculateCacheSize() {
        File cacheDir = requireContext().getCacheDir();
        return calculateDirSize(cacheDir);
    }

    private long calculateDirSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) size += calculateDirSize(file);
                else size += file.length();
            }
        }
        return size;
    }

    private void showClearCacheDialog() {
        java.io.File cacheDir = requireContext().getCacheDir();
        java.util.List<java.io.File> allFiles = new java.util.ArrayList<>();
        scanFilesRecursive(cacheDir, allFiles);

        if (allFiles.isEmpty()) {
            Toast.makeText(requireContext(), "暂无缓存文件", Toast.LENGTH_SHORT).show();
            return;
        }

        java.io.File[] files = allFiles.toArray(new java.io.File[0]);
        String[] fileNames = new String[files.length];
        boolean[] checked = new boolean[files.length];
        for (int i = 0; i < files.length; i++) {
            fileNames[i] = files[i].getAbsolutePath().replace(cacheDir.getAbsolutePath(), "...") + " (" + formatSize(files[i].length()) + ")";
            checked[i] = true;
        }

        new AlertDialog.Builder(requireContext())
            .setTitle("选择要删除的缓存文件")
            .setMultiChoiceItems(fileNames, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
            .setPositiveButton("删除选中", (dialog, which) -> {
                long deletedSize = 0;
                for (int i = 0; i < files.length; i++) {
                    if (checked[i] && files[i].delete()) deletedSize += files[i].length();
                }
                Toast.makeText(requireContext(), "已删除 " + formatSize(deletedSize), Toast.LENGTH_SHORT).show();
                updateUI();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void scanFilesRecursive(java.io.File dir, java.util.List<java.io.File> result) {
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        for (java.io.File f : files) {
            if (f.isDirectory()) scanFilesRecursive(f, result);
            else result.add(f);
        }
    }

    private String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        return String.format("%.1f MB", size / (1024.0 * 1024));
    }

    private void showColorPickerDialog() {
        AppSettings.CustomColors colors = settings.getCustomColors();
        if (colors == null) colors = new AppSettings.CustomColors();

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        String[] labels = {"主色", "强调色", "背景色", "文字色", "卡片色", "边框色", "成功色", "警告色"};
        String[] values = {colors.getPrimary(), colors.getAccent(), colors.getBackground(), colors.getText(),
                colors.getCard(), colors.getBorder(), colors.getSuccess(), colors.getWarning()};
        final EditText[] edits = new EditText[labels.length];

        for (int i = 0; i < labels.length; i++) {
            TextView tv = new TextView(requireContext());
            tv.setText(labels[i]);
            tv.setTextColor(getResources().getColor(R.color.text_primary));
            layout.addView(tv);

            EditText et = new EditText(requireContext());
            et.setText(values[i]);
            et.setTextColor(getResources().getColor(R.color.text_primary));
            edits[i] = et;
            layout.addView(et);

            LinearLayout presetRow = new LinearLayout(requireContext());
            presetRow.setOrientation(LinearLayout.HORIZONTAL);
            String[][] presets = {
                {"#FF5722", "#E91E63", "#9C27B0", "#673AB7"},
                {"#3F51B5", "#2196F3", "#03A9F4", "#00BCD4"},
                {"#009688", "#4CAF50", "#8BC34A", "#CDDC39"},
                {"#FFEB3B", "#FFC107", "#FF9800", "#795548"}
            };
            for (String[] row : presets) {
                for (String color : row) {
                    Button btn = new Button(requireContext());
                    btn.setLayoutParams(new LinearLayout.LayoutParams(80, 80));
                    btn.setBackgroundColor(Color.parseColor(color));
                    btn.setOnClickListener(v -> et.setText(color));
                    presetRow.addView(btn);
                }
            }
            layout.addView(presetRow);
        }

        new AlertDialog.Builder(requireContext())
            .setTitle("自定义颜色 - 点击色块快速选择")
            .setView(layout)
            .setPositiveButton("应用", (d, w) -> {
                colors.setPrimary(edits[0].getText().toString());
                colors.setAccent(edits[1].getText().toString());
                colors.setBackground(edits[2].getText().toString());
                colors.setText(edits[3].getText().toString());
                colors.setCard(edits[4].getText().toString());
                colors.setBorder(edits[5].getText().toString());
                colors.setSuccess(edits[6].getText().toString());
                colors.setWarning(edits[7].getText().toString());
                settings.setCustomColors(colors);
                settings.setUiTheme(AppSettings.THEME_CUSTOM);
                save();
                previousTheme = "_force_";
                applyTheme();
                Toast.makeText(requireContext(), "颜色已应用", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void applyTheme() {
        String currentTheme = settings.getUiTheme();
        if (previousTheme != null && !previousTheme.equals(currentTheme)) {
            previousTheme = currentTheme;
            if (getActivity() != null) {
                try { getActivity().recreate(); }
                catch (Exception e) { e.printStackTrace(); }
            }
        }
    }

    private void save() { prefManager.saveSettings(settings); }
}
