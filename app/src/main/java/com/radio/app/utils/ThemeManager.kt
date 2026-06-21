package com.radio.app.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import com.radio.app.R

object ThemeManager {

    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_THEME = "current_theme"
    private const val KEY_CUSTOM_PRIMARY = "custom_primary"
    private const val KEY_CUSTOM_BACKGROUND = "custom_background"
    private const val KEY_CUSTOM_TEXT = "custom_text"

    enum class AppTheme(val value: String) {
        DARK("dark"),
        FRESH("fresh"),
        CLASSIC("classic"),
        MINIMAL("minimal"),
        CUSTOM("custom")
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getCurrentTheme(context: Context): String {
        return getPrefs(context).getString(KEY_THEME, AppTheme.FRESH.value) ?: AppTheme.FRESH.value
    }

    fun setTheme(context: Context, theme: String) {
        getPrefs(context).edit().putString(KEY_THEME, theme).apply()
    }

    fun applyTheme(activity: AppCompatActivity) {
        val theme = getCurrentTheme(activity)
        val themeId = when (theme) {
            AppTheme.DARK.value -> R.style.Theme_RadioApp
            AppTheme.FRESH.value -> R.style.Theme_RadioApp_Fresh
            AppTheme.CLASSIC.value -> R.style.Theme_RadioApp_Classic
            AppTheme.MINIMAL.value -> R.style.Theme_RadioApp_Minimal
            AppTheme.CUSTOM.value -> R.style.Theme_RadioApp_Fresh // 自定义主题基于清新
            else -> R.style.Theme_RadioApp_Fresh
        }
        activity.setTheme(themeId)
    }

    // 自定义颜色
    fun setCustomColors(context: Context, primary: Int, background: Int, text: Int) {
        getPrefs(context).edit()
            .putInt(KEY_CUSTOM_PRIMARY, primary)
            .putInt(KEY_CUSTOM_BACKGROUND, background)
            .putInt(KEY_CUSTOM_TEXT, text)
            .apply()
    }

    fun getCustomPrimary(context: Context): Int {
        return getPrefs(context).getInt(KEY_CUSTOM_PRIMARY, Color.parseColor("#7ED321"))
    }

    fun getCustomBackground(context: Context): Int {
        return getPrefs(context).getInt(KEY_CUSTOM_BACKGROUND, Color.parseColor("#F5F7F5"))
    }

    fun getCustomText(context: Context): Int {
        return getPrefs(context).getInt(KEY_CUSTOM_TEXT, Color.parseColor("#1A1A1A"))
    }
}
