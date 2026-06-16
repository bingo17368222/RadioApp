package com.radio.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

import com.radio.app.models.AppSettings;
import com.radio.app.utils.PreferenceManager;

public class RadioApplication extends Application {
    public static final String CHANNEL_ID = "radio_playback_channel";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        applyTheme();
    }

    private void applyTheme() {
        try {
            PreferenceManager prefMgr = new PreferenceManager(this);
            AppSettings settings = prefMgr.loadSettings();
            String theme = settings.getUiTheme();
            if (AppSettings.THEME_FRESH.equals(theme)) {
                setTheme(R.style.Theme_RadioApp_Fresh);
            } else if (AppSettings.THEME_CLASSIC.equals(theme)) {
                setTheme(R.style.Theme_RadioApp_Classic);
            } else if (AppSettings.THEME_MINIMAL.equals(theme)) {
                setTheme(R.style.Theme_RadioApp_Minimal);
            } else {
                setTheme(R.style.Theme_RadioApp);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Radio Playback", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Radio playback controls");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
}
