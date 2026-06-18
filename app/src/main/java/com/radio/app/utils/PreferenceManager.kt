package com.radio.app.utils

import android.content.Context
import android.content.SharedPreferences
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
            gson.fromJson(json, AppSettings::class.java)
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
