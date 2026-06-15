package com.radio.app.utils;

import android.content.Context;
import android.graphics.Color;
import com.radio.app.models.AppSettings;

public class ThemeManager {
    private final PreferenceManager preferenceManager;
    private AppSettings settings;

    public ThemeManager(Context context) {
        preferenceManager = new PreferenceManager(context);
        settings = preferenceManager.loadSettings();
    }

    public void reloadSettings() { settings = preferenceManager.loadSettings(); }
    public AppSettings getSettings() { return settings; }
    public void saveSettings(AppSettings s) { settings = s; preferenceManager.saveSettings(s); }

    public int getColor(String type) {
        String hex;
        switch (settings.getUiTheme()) {
            case AppSettings.THEME_FRESH: hex = getFreshColor(type); break;
            case AppSettings.THEME_CLASSIC: hex = getClassicColor(type); break;
            case AppSettings.THEME_MINIMAL: hex = getMinimalColor(type); break;
            case AppSettings.THEME_CUSTOM: hex = getCustomColor(type); break;
            default: hex = getDarkColor(type); break;
        }
        try { return Color.parseColor(hex); } catch (Exception e) { return Color.parseColor("#000000"); }
    }

    private String getDarkColor(String t) {
        switch (t) {
            case "primary": return "#0f3460"; case "accent": return "#e94560";
            case "background": return "#1a1a2e"; case "text": return "#eaeaea";
            case "card": return "#16213e"; case "border": return "#0f3460";
            case "success": return "#4ecca3"; case "warning": return "#f4d03f";
            default: return "#000000";
        }
    }

    private String getFreshColor(String t) {
        switch (t) {
            case "primary": return "#2ecc71"; case "accent": return "#1abc9c";
            case "background": return "#f8f9fa"; case "text": return "#2c3e50";
            case "card": return "#ffffff"; case "border": return "#e9ecef";
            case "success": return "#27ae60"; case "warning": return "#f39c12";
            default: return "#000000";
        }
    }

    private String getClassicColor(String t) {
        switch (t) {
            case "primary": return "#3498db"; case "accent": return "#2980b9";
            case "background": return "#ecf0f1"; case "text": return "#2c3e50";
            case "card": return "#ffffff"; case "border": return "#bdc3c7";
            case "success": return "#2ecc71"; case "warning": return "#f1c40f";
            default: return "#000000";
        }
    }

    private String getMinimalColor(String t) {
        switch (t) {
            case "primary": return "#000000"; case "accent": return "#333333";
            case "background": return "#ffffff"; case "text": return "#000000";
            case "card": return "#f5f5f5"; case "border": return "#dddddd";
            case "success": return "#000000"; case "warning": return "#666666";
            default: return "#000000";
        }
    }

    private String getCustomColor(String t) {
        AppSettings.CustomColors c = settings.getCustomColors();
        switch (t) {
            case "primary": return c.getPrimary(); case "accent": return c.getAccent();
            case "background": return c.getBackground(); case "text": return c.getText();
            case "card": return c.getCard(); case "border": return c.getBorder();
            case "success": return c.getSuccess(); case "warning": return c.getWarning();
            default: return "#000000";
        }
    }

    public boolean isDarkTheme() { return AppSettings.THEME_DARK.equals(settings.getUiTheme()); }
}
