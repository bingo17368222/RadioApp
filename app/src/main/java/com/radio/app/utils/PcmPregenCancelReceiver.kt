package com.radio.app.utils

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.radio.app.services.RadioPlaybackService

/**
 * v3.2.0: Broadcast receiver for the PCM pre-generation notification cancel action.
 *
 * Registered in AndroidManifest.xml so it works regardless of activity state.
 * When the user taps "取消" on the PCM pre-generation progress notification,
 * this receiver sets the cancel flag and dismisses the notification.
 */
class PcmPregenCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != RadioPlaybackService.ACTION_CANCEL_PCM_PREGEN) return

        val episodeId = intent.getStringExtra("episode_id") ?: return
        if (episodeId.isBlank()) return

        // 设置取消标志
        RadioPlaybackService.pcmPregenCancelFlags[episodeId] = true

        // 记录取消日志
        android.util.Log.d("PcmPregenCancelReceiver", "PCM pre-generation cancelled for episode: $episodeId")

        // 取消该节目的通知
        val notifId = 2000 + (kotlin.math.abs(episodeId.hashCode()) % 1000)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(notifId)
    }
}