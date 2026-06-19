package com.radio.app.utils

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.Log
import android.view.Window
import com.radio.app.R
import com.radio.app.models.AppSettings

class ThemeManager(context: Context) {

    companion object {
        private const val TAG = "ThemeManager"

        fun applyTheme(activity: Activity?) {
            if (activity == null) return
            try {
                // 使用 PreferenceManager 读取设置（与 SettingsFragment 使用同一存储）
                val prefManager = PreferenceManager(activity)
                val settings = prefManager.loadSettings()
                val theme = settings.uiTheme

                Log.d(TAG, "Applying theme: $theme")

                // 先应用基础主题
                when (theme) {
                    AppSettings.THEME_DARK -> activity.setTheme(R.style.Theme_RadioApp)
                    AppSettings.THEME_FRESH -> activity.setTheme(R.style.Theme_RadioApp_Fresh)
                    AppSettings.THEME_CLASSIC -> activity.setTheme(R.style.Theme_RadioApp_Classic)
                    AppSettings.THEME_MINIMAL -> activity.setTheme(R.style.Theme_RadioApp_Minimal)
                    AppSettings.THEME_CUSTOM -> activity.setTheme(R.style.Theme_RadioApp)
                    else -> activity.setTheme(R.style.Theme_RadioApp)
                }

                // 如果是自定义主题，动态应用颜色
                if (AppSettings.THEME_CUSTOM == theme) {
                    applyCustomColors(activity, settings.customColors)
                }
            } catch (e: Exception) {
                Log.e(TAG, "applyTheme failed, using default", e)
                activity.setTheme(R.style.Theme_RadioApp)
            }
        }

        private fun applyCustomColors(activity: Activity?, colors: AppSettings.CustomColors?) {
            if (activity == null || colors == null) return
            try {
                val window: Window = activity.window ?: return

                // 状态栏颜色
                val primaryColor = parseColorSafe(activity, colors.primary, R.color.primary_dark)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    window.statusBarColor = primaryColor
                }

                // ActionBar颜色
                activity.actionBar?.setBackgroundDrawable(ColorDrawable(primaryColor))

                Log.d(TAG, "Custom colors applied: primary=$primaryColor")
            } catch (e: Exception) {
                Log.e(TAG, "applyCustomColors failed", e)
            }
        }

        private fun parseColorSafe(context: Context, colorStr: String?, defaultResId: Int): Int {
            try {
                if (!colorStr.isNullOrEmpty()) {
                    return Color.parseColor(colorStr)
                }
            } catch (e: Exception) {
                Log.e(TAG, "parseColor failed: $colorStr")
            }
            return context.getColor(defaultResId)
        }

        fun getColorFromTheme(context: Context, attrResId: Int): Int {
            val typedValue = android.util.TypedValue()
            val a = context.obtainStyledAttributes(typedValue.data, intArrayOf(attrResId))
            val color = a.getColor(0, 0)
            a.recycle()
            return color
        }
    }
}
