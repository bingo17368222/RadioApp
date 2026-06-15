package com.radio.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

public class RadioApplication extends Application {
    public static final String CHANNEL_ID = "radio_playback_channel";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
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
