package com.radio.app.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import com.radio.app.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v3.1.8: 指纹匹配测试通知栏进度管理。
 * 在指纹匹配测试过程中显示通知栏进度，包含：
 * - 正在对比的指纹和PCM名称
 * - 完成百分比
 * - 已用时间和估计剩余时间
 */
object FingerprintTestNotificationHelper {

    private const val TAG = "FpTestNotification"
    private const val CHANNEL_ID = "fingerprint_test_channel"
    private const val NOTIFICATION_ID = 4001
    const val CANCEL_ACTION = "com.radio.app.CANCEL_FINGERPRINT_TEST"

    @Volatile
    var isCancelled = false
        private set

    private var startTimeMs: Long = 0L

    /**
     * 初始化通知通道（仅需在Application中调用一次）。
     */
    fun initChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(CHANNEL_ID, "指纹匹配测试", NotificationManager.IMPORTANCE_LOW)
                channel.setShowBadge(false)
                nm.createNotificationChannel(channel)
            }
        }
    }

    /**
     * 显示测试进度通知。
     * @param context 上下文
     * @param fpLabel 指纹标签（如 "节目A 01:23-01:33"）
     * @param pcmLabel PCM标签（如 "节目B_full.pcm"）
     * @param progress 进度百分比（0-100）
     * @param elapsedMs 已用时间毫秒
     */
    fun showProgress(
        context: Context,
        fpLabel: String,
        pcmLabel: String,
        progress: Int,
        elapsedMs: Long
    ) {
        if (isCancelled) return
        if (startTimeMs == 0L) startTimeMs = System.currentTimeMillis() - elapsedMs

        val elapsed = formatDuration(elapsedMs)
        val eta = if (progress > 0 && progress < 100) {
            val estTotal = (elapsedMs * 100L) / progress
            val etaMs = (estTotal - elapsedMs).coerceAtLeast(0L)
            "，预计剩余 ${formatDuration(etaMs)}"
        } else {
            ""
        }

        val contentText = "对比: $fpLabel → $pcmLabel\n已完成: ${progress}% (已用 $elapsed$eta)"

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val cancelIntent = PendingIntent.getBroadcast(
                context, 0,
                Intent(CANCEL_ACTION).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("指纹匹配测试")
                .setContentText(contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
                .setProgress(100, progress, false)
                .setOngoing(progress in 1..99)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelIntent)
                .build()
            nm.notify(NOTIFICATION_ID, notif)
        } catch (_: Exception) {}
    }

    /**
     * 显示完成通知。
     */
    fun showComplete(context: Context, summary: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("指纹匹配测试完成")
                .setContentText(summary)
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                .setAutoCancel(true)
                .setOngoing(false)
                .build()
            nm.notify(NOTIFICATION_ID, notif)
        } catch (_: Exception) {}
    }

    /**
     * v3.1.11: 取消通知（不再重置 isCancelled 标志，由 resetCancel 单独负责）。
     * 修复：点击取消后通知栏消失但主界面对比仍在进行的bug。
     */
    fun cancel(context: Context) {
        startTimeMs = 0L
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    /**
     * 设置取消标志（由BroadcastReceiver调用）。
     */
    fun setCancelled() {
        isCancelled = true
    }

    /**
     * 重置取消标志（开始新测试前调用）。
     */
    fun resetCancel() {
        isCancelled = false
        startTimeMs = 0L
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%02d:%02d", min, sec)
    }
}

/**
 * v3.1.8: 指纹测试取消广播接收器。
 * 处理指纹匹配测试通知栏的取消按钮点击。
 */
class FingerprintTestCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == FingerprintTestNotificationHelper.CANCEL_ACTION) {
            FingerprintTestNotificationHelper.setCancelled()
            FingerprintTestNotificationHelper.cancel(context)
        }
    }
}