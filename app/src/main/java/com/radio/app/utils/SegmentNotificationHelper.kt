package com.radio.app.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.radio.app.R

/**
 * v2.4.150: Independent notification helper for AI audio segmentation.
 *
 * Uses applicationContext so the notification keeps updating even when the
 * PlayerActivity that started the segment thread has been destroyed.
 */
object SegmentNotificationHelper {
    private const val SEGMENT_NOTIFICATION_ID = 20001
    private const val SEGMENT_CHANNEL_ID = "segment_processing"
    private const val SEGMENT_CANCEL_ACTION = "com.radio.app.CANCEL_SEGMENT"

    @JvmStatic
    fun update(
        context: Context,
        episodeTitle: String,
        progress: Int,
        elapsedText: String = "",
        etaText: String = ""
    ) {
        try {
            val appCtx = context.applicationContext
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    SEGMENT_CHANNEL_ID,
                    "AI分段处理",
                    NotificationManager.IMPORTANCE_LOW
                )
                nm.createNotificationChannel(channel)
            }

            // v2.4.152: progress is 0-1000 permille; show as x.x%.
            val percentText = String.format(java.util.Locale.US, "%.1f", progress / 10f)
            val content = buildString {
                append("AI分段: ${percentText}%")
                if (elapsedText.isNotEmpty()) {
                    append(" (已用 $elapsedText")
                    if (etaText.isNotEmpty()) append("，预计剩余 $etaText")
                    append(")")
                }
            }

            val cancelIntent = Intent(SEGMENT_CANCEL_ACTION).setPackage(appCtx.packageName)
            val cancelPending = PendingIntent.getBroadcast(
                appCtx, 20001, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(appCtx, SEGMENT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(episodeTitle)
                .setContentText(content)
                .setProgress(1000, progress, progress == 0)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelPending)
                .build()
            nm.notify(SEGMENT_NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    @JvmStatic
    fun cancel(context: Context) {
        try {
            val nm = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(SEGMENT_NOTIFICATION_ID)
        } catch (_: Exception) {}
    }
}
