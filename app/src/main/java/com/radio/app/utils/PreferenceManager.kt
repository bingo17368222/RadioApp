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
                // Gson 反序列化 AppSettings 可能因私有构造函数失败
                // 使用更安全的方式：直接创建新实例并复制字段
                val s = AppSettings.getInstance(context)
                // 尝试解析 JSON 中的关键字段
                try {
                    val jsonObj = org.json.JSONObject(json)
                    if (jsonObj.has("uiTheme")) s.uiTheme = jsonObj.getString("uiTheme")
                    if (jsonObj.has("aiModel")) s.aiModel = jsonObj.getString("aiModel")
                    if (jsonObj.has("asrProvider")) s.asrProvider = jsonObj.getString("asrProvider")
                    if (jsonObj.has("subtitleSize")) s.subtitleSize = jsonObj.getString("subtitleSize")
                    if (jsonObj.has("subtitleLanguage")) s.subtitleLanguage = jsonObj.getString("subtitleLanguage")
                    if (jsonObj.has("voiceLanguage")) s.voiceLanguage = jsonObj.getString("voiceLanguage")
                    if (jsonObj.has("splitMode")) s.splitMode = jsonObj.getString("splitMode")
                    if (jsonObj.has("autoSkipWater")) s.autoSkipWater = jsonObj.getBoolean("autoSkipWater")
                    if (jsonObj.has("continuousPlay")) s.continuousPlay = jsonObj.getBoolean("continuousPlay")
                    if (jsonObj.has("autoDownload")) s.autoDownload = jsonObj.getBoolean("autoDownload")
                    if (jsonObj.has("autoCache")) s.autoCache = jsonObj.getBoolean("autoCache")
                    if (jsonObj.has("audioFocus")) s.audioFocus = jsonObj.getBoolean("audioFocus")
                    if (jsonObj.has("preloadCacheCount")) s.preloadCacheCount = jsonObj.getInt("preloadCacheCount")
                    if (jsonObj.has("preloadCache")) s.preloadCache = jsonObj.getBoolean("preloadCache")
                } catch (e: Exception) {
                    Log.w("PreferenceManager", "JSON parse fallback failed", e)
                }
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
