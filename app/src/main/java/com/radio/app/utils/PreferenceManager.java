package com.radio.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.radio.app.models.AppSettings;

public class PreferenceManager {
    private static final String PREF_NAME = "radio_app_prefs";
    private static final String KEY_SETTINGS = "settings";
    private final SharedPreferences prefs;
    private final Gson gson;

    public PreferenceManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
    }

    public AppSettings loadSettings() {
        String json = prefs.getString(KEY_SETTINGS, null);
        return json != null ? gson.fromJson(json, AppSettings.class) : new AppSettings();
    }

    public void saveSettings(AppSettings settings) {
        prefs.edit().putString(KEY_SETTINGS, gson.toJson(settings)).apply();
    }

    public void clearCache() {
        prefs.edit().remove("cache_data").apply();
    }
}
