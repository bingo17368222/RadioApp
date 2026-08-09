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
 * v2.4.150/v2.4.154: Independent notification helper for AI audio segmentation.
 *
 * Uses applicationContext so the notification keeps updating even when the
 * PlayerActivity that started the segment thread has been destroyed.
 *
 * v2.4.154: Reverted to the standard notification template so the original
 * system color scheme is used (better visibility). Keeps the cancel action
 * and shows progress / elapsed / ETA in the content text instead of a progress bar.
 *
 * v2.4.186: Adds per-episode session ownership. Because the helper uses a single
 * notification ID, concurrent segmentation tasks (e.g. background pre-segment
 * and a manual segment request) would otherwise flip the same notification
 * between two percentages. Only the most recently started session may update
 * or cancel the notification; older sessions continue silently in the background.
 *
 * v2.4.187: Tightens session ownership. Background sessions (same priority) can
 * no longer steal the notification from an already-running background session,
 * preventing patrols that arrive while another pre-segment task is running from
 * making the progress text cycle or flicker.
 */
object SegmentNotificationHelper {
    private const val SEGMENT_NOTIFICATION_ID = 20001
    private const val SEGMENT_CHANNEL_ID = "segment_processing"
    const val SEGMENT_CANCEL_ACTION = "com.radio.app.CANCEL_SEGMENT"

    // v2.4.170: Guard against stale progress callbacks re-posting the notification
    // after the user has cancelled it. Reset at the start of each new analysis.
    @Volatile
    private var cancelled = false

    // v2.4.186: Episode ID that currently owns the shared notification.
    // Updates from any other episode are dropped so two concurrent tasks don't
    // cycle the notification between two progress values.
    @Volatile
    private var activeEpisodeId: String? = null

    // v2.4.186: Priority of the active session. Manual segmentation uses a higher priority
    // than background pre-segmentation so a user-initiated task can take over, but a
    // background task cannot steal the notification from an active manual task.
    @Volatile
    private var activePriority: Int = 0

    // v2.4.186: When the active session started; useful for diagnostics.
    @Volatile
    private var activeStartTime: Long = 0

    // v2.4.186: Whether the active session is still running. Used to stop
    // same-priority background sessions from repeatedly stealing the notification
    // while another background pre-segment task is already in progress.
    @Volatile
    private var activeSessionRunning: Boolean = false

    // v3.1.41: 防抖机制，防止三个层级的进度快速循环导致通知栏闪烁
    @Volatile
    private var lastUpdateTimeMs: Long = 0
    @Volatile
    private var lastProgressValue: Int = -1
    // v3.1.47: 记录上次更新的层名，当层名变化时（如从第1层切换到第2层）跳过防抖，
    // 确保进度通知栏在层切换时持续更新，不会因防抖而消失
    @Volatile
    private var lastLayerName: String = ""
    private const val UPDATE_DEBOUNCE_MS = 300L  // 同一层最小更新间隔300ms
    private const val PROGRESS_SIGNIFICANT_CHANGE = 5  // 进度变化超过5%才算有意义的变化

    /**
     * v3.1.50: 全局分段中标志。当三层分段正在进行时，新的请求先检查此标志。
     * 由 SegmentGenerator 在 generateJiuAiTingSegments 开始/结束时设置。
     * 替代 v3.1.48 的 2秒防循环守卫（回避问题的手段），直接防止并发分段。
     * 
     * v3.1.51: 移除 private set——SegmentGenerator 需要设置此标志。
     * 之前 private set 导致此标志永远为 false，检查被跳过，通知栏循环未被阻止。
     */
    @Volatile
    @JvmField
    var isSegmenting: Boolean = false

    /**
     * v2.4.186: Begin a new notification session for [episodeId].
     * This becomes the active owner of the shared segment notification;
     * any updates from previous sessions are ignored.
     *
     * @param priority Higher values win. Manual segmentation should use [PRIORITY_MANUAL];
     *                 background pre-segmentation should use [PRIORITY_BACKGROUND].
     */
    /**
     * @return true if the session was started, false if rejected (higher-priority or same-priority session active).
     */
    @JvmStatic
    @Synchronized
    fun startSession(
        context: Context,
        episodeId: String,
        title: String,
        priority: Int = PRIORITY_BACKGROUND
    ): Boolean {
        // v2.4.187: A lower-priority session must not steal the notification from a
        // higher-priority one. Sessions with the same priority are also rejected while
        // the current session is still running, so two background pre-segment tasks
        // cannot fight over the same notification.
        if (activeEpisodeId != null) {
            if (priority < activePriority) return false
            if (priority == activePriority && activeSessionRunning) return false
        }
        // v3.1.50: 移除2秒防循环守卫（回避手段），改用全局 isSegmenting 标志。
        // 如果全局分段正在进行中，拒绝新会话，由 SegmentGenerator 在调用前检查此标志。
        if (isSegmenting) {
            android.util.Log.w("SegmentNotificationHelper",
                "startSession: 拒绝新会话，全局分段中（isSegmenting=true，episodeId=$episodeId）")
            return false
        }
        activeEpisodeId = episodeId
        activePriority = priority
        activeStartTime = System.currentTimeMillis()
        activeSessionRunning = true
        cancelled = false
        // v3.1.41: 不先cancelNotification再update，避免通知栏短暂消失。
        // 直接update会覆盖旧通知内容，确保通知栏持续可见无闪烁。
        update(context, episodeId, title, 0, "初始化")
        return true
    }

    // v2.4.186: Session priorities.
    const val PRIORITY_BACKGROUND = 0
    const val PRIORITY_MANUAL = 1

    /**
     * v2.4.186: End the session for [episodeId].
     * Only the active owner is allowed to dismiss the notification.
     */
    @JvmStatic
    @Synchronized
    fun endSession(context: Context, episodeId: String) {
        if (episodeId == activeEpisodeId) {
            activeEpisodeId = null
            activePriority = 0
            activeStartTime = 0
            activeSessionRunning = false
            cancelNotification(context)
        }
    }

    @JvmStatic
    fun setCancelled(cancelled: Boolean) {
        this.cancelled = cancelled
    }

    /**
     * v2.4.186: Reset only the cancellation flag.
     * Do NOT clear [activeEpisodeId] here; callers manage the active session
     * via [startSession] / [endSession].
     */
    @JvmStatic
    fun reset() {
        cancelled = false
        // v3.1.41: 重置防抖状态，确保新会话的进度更新不因旧值被跳过
        lastUpdateTimeMs = 0
        lastProgressValue = -1
        // v3.1.47: 重置层名，确保新会话的层名变化检测正确
        lastLayerName = ""
    }

    @JvmStatic
    fun update(
        context: Context,
        episodeId: String,
        episodeTitle: String,
        progress: Int,
        layerName: String = ""
    ) {
        // v2.4.170: Drop any stale update that races in after the user cancelled.
        if (cancelled) return

        // v2.4.186: Ignore updates from a session that is no longer active.
        // This prevents two concurrent segmentation tasks from cycling the
        // same notification between two different percentages.
        if (episodeId != activeEpisodeId) return

        // v3.1.41: 防抖 - 同一层级的进度更新如果变化不大且更新间隔太短，则跳过
        // 解决三层分段过程中三个层级的进度快速循环显示的问题
        // v3.1.47: 当层名变化时（如从第1层切换到第2层）跳过防抖，
        // 确保进度通知栏在层切换时持续更新，不会因防抖而消失
        val now = System.currentTimeMillis()
        val layerChanged = layerName.isNotEmpty() && layerName != lastLayerName
        if (progress == 1000 || progress == 0 || layerChanged) {
            // 完成(100%)、初始化(0%)或层名变化时的更新总是允许通过
            lastUpdateTimeMs = now
            lastProgressValue = progress
            lastLayerName = layerName
        } else {
            if (now - lastUpdateTimeMs < UPDATE_DEBOUNCE_MS) {
                // 更新间隔太短，跳过
                return
            }
            if (kotlin.math.abs(progress - lastProgressValue) < PROGRESS_SIGNIFICANT_CHANGE) {
                // 进度变化太小，跳过
                return
            }
            lastUpdateTimeMs = now
            lastProgressValue = progress
        }

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

            // progress is 0-1000 permille; show as x.x%.
            val percentText = String.format(java.util.Locale.US, "%.1f", progress / 10f)

            // 计算已用时间和ETA
            val elapsedMs = System.currentTimeMillis() - activeStartTime
            val elapsedStr = formatDurationMmSs(elapsedMs)
            val etaStr = if (progress > 0 && progress < 1000) {
                val remainingMs = ((elapsedMs * 1000L) / progress - elapsedMs).toLong().coerceAtLeast(0L)
                formatDurationMmSs(remainingMs)
            } else {
                ""
            }

            val infoText = buildString {
                append("AI分段: ${percentText}%")
                if (layerName.isNotEmpty()) {
                    append(" ($layerName")
                    append("，已用 $elapsedStr")
                    if (etaStr.isNotEmpty()) append("，预计剩余 $etaStr")
                    append(")")
                } else {
                    append(" (已用 $elapsedStr")
                    if (etaStr.isNotEmpty()) append("，预计剩余 $etaStr")
                    append(")")
                }
            }

            val cancelIntent = Intent(SEGMENT_CANCEL_ACTION).setClass(appCtx, com.radio.app.utils.SegmentCancelReceiver::class.java)
            val cancelPending = PendingIntent.getBroadcast(
                appCtx, 20001, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(appCtx, SEGMENT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(episodeTitle)
                .setContentText(infoText)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .setBigContentTitle(episodeTitle)
                        .bigText(infoText)
                )
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelPending)
                .build()
            nm.notify(SEGMENT_NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    private fun formatDurationMmSs(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(java.util.Locale.US, "%02d:%02d", min, sec)
    }

    @JvmStatic
    @Synchronized
    fun cancel(context: Context, episodeId: String? = null) {
        // v2.4.186: A specific episode may only cancel the notification if it
        // still owns the active session. A null episodeId forces cancellation
        // (used when the system/global cancel action is invoked) and clears the
        // active session so stale progress callbacks cannot re-post it.
        if (episodeId != null && episodeId != activeEpisodeId) return
        if (episodeId == null) {
            activeEpisodeId = null
            activePriority = 0
            activeStartTime = 0
            activeSessionRunning = false
        }
        cancelNotification(context)
    }

    private fun cancelNotification(context: Context) {
        try {
            val nm = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(SEGMENT_NOTIFICATION_ID)
        } catch (_: Exception) {}
    }
}
