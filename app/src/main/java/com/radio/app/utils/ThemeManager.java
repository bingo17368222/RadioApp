package com.radio.app.utils;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.util.Log;
import android.view.Window;

import com.radio.app.R;
import com.radio.app.models.AppSettings;

public class ThemeManager {
    private static final String TAG = "ThemeManager";
    private Context context;

    public ThemeManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static void applyTheme(Activity activity) {
        if (activity == null) return;
        AppSettings settings = AppSettings.getInstance(activity);
        String theme = settings.getUiTheme();

        // 先应用基础主题
        switch (theme) {
            case AppSettings.THEME_DARK:
                activity.setTheme(R.style.Theme_RadioApp);
                break;
            case AppSettings.THEME_FRESH:
                activity.setTheme(R.style.Theme_RadioApp_Fresh);
                break;
            case AppSettings.THEME_CLASSIC:
                activity.setTheme(R.style.Theme_RadioApp_Classic);
                break;
            case AppSettings.THEME_MINIMAL:
                activity.setTheme(R.style.Theme_RadioApp_Minimal);
                break;
            case AppSettings.THEME_CUSTOM:
                activity.setTheme(R.style.Theme_RadioApp);
                break;
            default:
                activity.setTheme(R.style.Theme_RadioApp);
                break;
        }

        // 如果是自定义主题，动态应用颜色
        if (AppSettings.THEME_CUSTOM.equals(theme)) {
            applyCustomColors(activity, settings.getCustomColors());
        }
    }

    private static void applyCustomColors(Activity activity, AppSettings.CustomColors colors) {
        if (activity == null || colors == null) return;
        try {
            Window window = activity.getWindow();
            if (window == null) return;

            // 状态栏颜色
            int primaryColor = parseColorSafe(colors.getPrimary(), R.color.primary_dark);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.setStatusBarColor(primaryColor);
            }

            // ActionBar颜色
            if (activity.getActionBar() != null) {
                activity.getActionBar().setBackgroundDrawable(new ColorDrawable(primaryColor));
            }

            // 尝试通过反射或遍历View树来应用颜色
            // 实际应用中，更好的方式是在onResume中刷新所有View
            Log.d(TAG, "Custom colors applied: primary=" + colors.getPrimary());
        } catch (Exception e) {
            Log.e(TAG, "applyCustomColors failed", e);
        }
    }

    private static int parseColorSafe(String colorStr, int defaultResId) {
        try {
            if (colorStr != null && !colorStr.isEmpty()) {
                return Color.parseColor(colorStr);
            }
        } catch (Exception e) {
            Log.e(TAG, "parseColor failed: " + colorStr);
        }
        return defaultResId; // 这里会返回资源ID，需要context来获取颜色值
    }

    public static int getColorFromTheme(Context context, int attrResId) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        android.content.res.TypedArray a = context.obtainStyledAttributes(typedValue.data, new int[]{attrResId});
        int color = a.getColor(0, 0);
        a.recycle();
        return color;
    }
}
