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
 *
 * v3.1.27: 修复通知ID不匹配问题——从intent中获取真实的notif_id（动态递增的30000+），
 * 而非通过hashCode计算，确保能正确取消通知。
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

        // 从intent中获取真实的notif_id（由RadioPlaybackService动态生成，从30000开始递增）
        val notifId = intent.getIntExtra("notif_id", -1)
        if (notifId >= 0) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)
            android.util.Log.d("PcmPregenCancelReceiver", "Cancelled notification notif_id=$notifId for episode=$episodeId")
        }
    }
}