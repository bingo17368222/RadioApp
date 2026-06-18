package com.radio.app.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.radio.app.models.AppSettings

class PreferenceManager(private val context: Context) {

    companion object {
        private const val PREF_NAME = "radio_app_prefs"
        private const val KEY_SETTINGS = "settings"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun loadSettings(): AppSettings {
        val json = prefs.getString(KEY_SETTINGS, null)
        return if (json != null) {
            try {
                val s = gson.fromJson(json, AppSettings::class.java)
                // Gson 不调用 Kotlin 属性初始化器，手动确保非 null
                if (s.subtitleSize.isNullOrEmpty()) s.subtitleSize = AppSettings.SUBTITLE_MEDIUM
                if (s.subtitleLanguage.isNullOrEmpty()) s.subtitleLanguage = AppSettings.LANG_CN
                if (s.voiceLanguage.isNullOrEmpty()) s.voiceLanguage = AppSettings.LANG_CN
                if (s.uiTheme.isNullOrEmpty()) s.uiTheme = AppSettings.THEME_DARK
                if (s.aiModel.isNullOrEmpty()) s.aiModel = AppSettings.AI_MODEL_WENXIN
                if (s.asrProvider.isNullOrEmpty()) s.asrProvider = AppSettings.ASR_BAIDU
                if (s.splitMode.isNullOrEmpty()) s.splitMode = AppSettings.SPLIT_MODE_NONE
                if (s.customColors == null) s.customColors = AppSettings.CustomColors()
                if (s.keywordConfig == null) s.keywordConfig = AppSettings.KeywordConfig()
                if (s.dislikedEpisodes == null) s.dislikedEpisodes = mutableListOf()
                s
            } catch (e: Exception) {
                Log.e("PreferenceManager", "Failed to load settings", e)
                AppSettings.getInstance(context)
            }
        } else {
            AppSettings.getInstance(context)
        }
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit().putString(KEY_SETTINGS, gson.toJson(settings)).apply()
    }

    fun clearCache() {
        prefs.edit().remove("cache_data").apply()
    }
}
