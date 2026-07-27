package com.radio.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.radio.app.R
import com.radio.app.activities.PlayerActivity
import com.radio.app.database.AudioFingerprint
import com.radio.app.database.RadioDatabaseHelper
import com.radio.app.utils.ChromaprintExtractor
import com.radio.app.utils.PcmSegmentExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v3.0.2: 音频指纹后台服务。
 * 处理「添加为水分指纹」任务：截取片段 PCM -> 提取 Chromaprint 指纹 -> 存入数据库。
 * v3.0.4: 保留水印指纹片段 PCM，不再自动删除，供音频指纹管理页播放。
 * 全程通过前台通知展示进度，类似字幕生成服务。
 */
class AudioFingerprintService : Service() {

    companion object {
        private const val TAG = "AudioFingerprintService"
        private const val CHANNEL_ID = "audio_fingerprint_channel"
        private const val NOTIFICATION_ID = 2001

        private const val EXTRA_EPISODE_ID = "episode_id"
        private const val EXTRA_START_MS = "start_ms"
        private const val EXTRA_END_MS = "end_ms"
        private const val EXTRA_EPISODE_TITLE = "episode_title"

        private const val ACTION_ADD_FINGERPRINT = "com.radio.app.action.ADD_FINGERPRINT"

        /**
         * 启动添加水分指纹任务。
         */
        fun startAddFingerprint(context: Context, episodeId: String, startMs: Long, endMs: Long, episodeTitle: String? = null) {
            val intent = Intent(context, AudioFingerprintService::class.java).apply {
                action = ACTION_ADD_FINGERPRINT
                putExtra(EXTRA_EPISODE_ID, episodeId)
                putExtra(EXTRA_START_MS, startMs)
                putExtra(EXTRA_END_MS, endMs)
                putExtra(EXTRA_EPISODE_TITLE, episodeTitle)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadioApp:AudioFingerprint")
            wakeLock?.setReferenceCounted(false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create wake lock: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try {
            wakeLock?.release()
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (intent.action != ACTION_ADD_FINGERPRINT) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID) ?: ""
        val startMs = intent.getLongExtra(EXTRA_START_MS, 0L)
        val endMs = intent.getLongExtra(EXTRA_END_MS, 0L)
        val episodeTitle = intent.getStringExtra(EXTRA_EPISODE_TITLE)

        if (episodeId.isBlank() || endMs <= startMs) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val title = buildTitle(episodeId, episodeTitle)
        startForeground(NOTIFICATION_ID, buildProgressNotification(title, 0, "准备中..."))

        serviceScope.launch {
            runAddFingerprint(episodeId, startMs, endMs, title)
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private suspend fun runAddFingerprint(episodeId: String, startMs: Long, endMs: Long, title: String) {
        try {
            wakeLock?.acquire(5 * 60 * 1000L)
        } catch (_: Exception) {}

        var watermarkPcmFile: File? = null
        try {
            // 1. 检查 Chromaprint 库
            updateNotification(title, 10, "检查指纹库...")
            if (!ChromaprintExtractor.ensureLibraryLoaded(this)) {
                showErrorNotification(title, "Chromaprint 指纹库未加载，请先在离线引擎管理中下载")
                return
            }

            // 2. 截取 PCM 片段到固定水印路径（保留不删）
            updateNotification(title, 30, "截取音频片段...")
            watermarkPcmFile = withContext(Dispatchers.IO) {
                // 修正/添加时强制重新截取，确保 PCM 与指纹同步
                PcmSegmentExtractor.extractWatermarkPcm(this@AudioFingerprintService, episodeId, startMs, endMs, force = true)
            }
            if (watermarkPcmFile == null || !watermarkPcmFile.exists() || watermarkPcmFile.length() <= 0) {
                showErrorNotification(title, "无法截取音频片段（缺少 PCM 缓存）")
                return
            }

            // 3. 提取指纹
            updateNotification(title, 60, "提取音频指纹...")
            val fingerprint = withContext(Dispatchers.IO) {
                ChromaprintExtractor.extractFingerprintFromFile(watermarkPcmFile)
            }
            if (fingerprint.isNullOrBlank()) {
                showErrorNotification(title, "指纹提取失败")
                return
            }

            // 4. 存入数据库（先查询是否已有同一片段，有则更新）
            updateNotification(title, 90, "保存指纹...")
            val dbHelper = RadioDatabaseHelper.getInstance(this)
            val existing = dbHelper.getAudioFingerprintsByEpisode(episodeId).find {
                it.startMs == startMs && it.endMs == endMs
            }
            val audioFingerprint = AudioFingerprint(
                id = existing?.id ?: 0,
                episodeId = episodeId,
                startMs = startMs,
                endMs = endMs,
                fingerprint = fingerprint,
                durationMs = endMs - startMs
            )
            dbHelper.saveAudioFingerprint(audioFingerprint)

            // 5. 保留水印 PCM，不删除

            updateNotification(title, 100, "完成")
            showCompleteNotification(title, "已添加为水分指纹")

            Log.i(TAG, "runAddFingerprint: saved fingerprint for $episodeId [$startMs, $endMs], fp_len=${fingerprint.length}")
        } catch (e: Exception) {
            Log.e(TAG, "runAddFingerprint failed: ${e.message}", e)
            showErrorNotification(title, "添加指纹失败: ${e.message}")
        } finally {
            // 保留水印 PCM，不自动删除；仅释放唤醒锁
            try {
                wakeLock?.release()
            } catch (_: Exception) {}
        }
    }

    private fun buildTitle(episodeId: String, title: String?): String {
        val dateMatch = Regex("(\\d{4}-\\d{2}-\\d{2})").find(episodeId)
        val dateStr = dateMatch?.value ?: ""
        val baseTitle = title ?: episodeId
        return if (dateStr.isNotEmpty() && baseTitle != null && !baseTitle.startsWith(dateStr)) {
            "$dateStr $baseTitle"
        } else {
            baseTitle ?: "音频指纹"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音频指纹处理",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "添加音频指纹时的后台进度通知"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(title: String, progress: Int, status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("正在生成音频指纹")
            .setContentText("$title - $status")
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(title: String, progress: Int, status: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildProgressNotification(title, progress, status))
        } catch (e: Exception) {
            Log.e(TAG, "updateNotification failed: ${e.message}")
        }
    }

    private fun showCompleteNotification(title: String, message: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, PlayerActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "showCompleteNotification failed: ${e.message}")
        }
    }

    private fun showErrorNotification(title: String, error: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, PlayerActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("音频指纹生成失败 - $title")
                .setContentText(error)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "showErrorNotification failed: ${e.message}")
        }
    }
}
