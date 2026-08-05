package com.radio.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
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
import java.io.FileInputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v3.0.2: 音频指纹后台服务。
 * 处理「添加为水分指纹」任务：截取片段 PCM -> 提取 Chromaprint 指纹 -> 存入数据库。
 * v3.0.4: 保留水印指纹片段 PCM，不再自动删除，供音频指纹管理页播放。
 * v3.0.5: 增强日志与本地广播反馈，修复通知/入库不可见问题。
 * v3.0.5-fix: 增加持久化日志、入库结果强校验、通知通道默认可见。
 * 全程通过前台通知展示进度，类似字幕生成服务。
 */
class AudioFingerprintService : Service() {

    companion object {
        private const val TAG = "AudioFingerprintService"
        private const val CHANNEL_ID = "audio_fingerprint_channel_v2"
        private const val NOTIFICATION_ID_BASE = 3001

        private const val EXTRA_EPISODE_ID = "episode_id"
        private const val EXTRA_START_MS = "start_ms"
        private const val EXTRA_END_MS = "end_ms"
        private const val EXTRA_EPISODE_TITLE = "episode_title"

        private const val ACTION_ADD_FINGERPRINT = "com.radio.app.action.ADD_FINGERPRINT"

        // v3.0.5: 本地广播 Action，用于通知 UI 刷新
        const val ACTION_FINGERPRINT_ADDED = "com.radio.app.action.FINGERPRINT_ADDED"
        const val ACTION_FINGERPRINT_ERROR = "com.radio.app.action.FINGERPRINT_ERROR"
        const val EXTRA_FINGERPRINT_EPISODE_ID = "fingerprint_episode_id"
        const val EXTRA_FINGERPRINT_START_MS = "fingerprint_start_ms"
        const val EXTRA_FINGERPRINT_END_MS = "fingerprint_end_ms"
        const val EXTRA_FINGERPRINT_MESSAGE = "fingerprint_message"

        /**
         * 启动添加水分指纹任务。
         */
        fun startAddFingerprint(context: Context, episodeId: String, startMs: Long, endMs: Long, episodeTitle: String? = null) {
            Log.i(TAG, "startAddFingerprint called: $episodeId [$startMs, $endMs]")
            val intent = Intent(context, AudioFingerprintService::class.java).apply {
                action = ACTION_ADD_FINGERPRINT
                putExtra(EXTRA_EPISODE_ID, episodeId)
                putExtra(EXTRA_START_MS, startMs)
                putExtra(EXTRA_END_MS, endMs)
                putExtra(EXTRA_EPISODE_TITLE, episodeTitle)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                com.radio.app.utils.FileLogUtils.e(TAG, "startAddFingerprint failed to start service: ${e.message}", e)
                // 启动失败时立即发广播通知 UI
                try {
                    val errorIntent = Intent(ACTION_FINGERPRINT_ERROR).apply {
                        putExtra(EXTRA_FINGERPRINT_EPISODE_ID, episodeId)
                        putExtra(EXTRA_FINGERPRINT_START_MS, startMs)
                        putExtra(EXTRA_FINGERPRINT_END_MS, endMs)
                        putExtra(EXTRA_FINGERPRINT_MESSAGE, "启动指纹服务失败: ${e.message}")
                    }
                    LocalBroadcastManager.getInstance(context).sendBroadcast(errorIntent)
                } catch (_: Exception) {}
            }
        }

        // ===== v3.1.43: 指纹播放功能 =====
        private const val SAMPLE_RATE = 16000
        private const val PCM_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO

        @Volatile
        private var playbackAudioTrack: AudioTrack? = null
        @Volatile
        private var isPlaying = false

        /**
         * 播放指纹音频（从水印PCM文件读取原始PCM数据，使用AudioTrack播放）。
         */
        @JvmStatic
        fun playFingerprint(context: Context, episodeId: String, startMs: Long, endMs: Long) {
            // 如果已经在播放，先停止
            stopPlaybackInternal()

            try {
                val pcmFile = PcmSegmentExtractor.getWatermarkPcmFile(context, episodeId, startMs, endMs)
                if (!pcmFile.exists() || pcmFile.length() <= 0) {
                    Log.w(TAG, "playFingerprint: watermark PCM not found for $episodeId [$startMs, $endMs]")
                    return
                }

                val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, PCM_FORMAT)
                if (bufferSize <= 0) {
                    Log.w(TAG, "playFingerprint: invalid buffer size=$bufferSize")
                    return
                }

                val audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(PCM_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .build())
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                } else {
                    AudioTrack(
                        AudioAttributes.USAGE_MEDIA,
                        SAMPLE_RATE, CHANNEL_CONFIG, PCM_FORMAT,
                        bufferSize, AudioTrack.MODE_STREAM
                    )
                }

                playbackAudioTrack = audioTrack
                isPlaying = true
                audioTrack.play()

                // 在后台线程读取PCM数据并写入AudioTrack
                Thread {
                    try {
                        val buffer = ByteArray(bufferSize)
                        val fis = FileInputStream(pcmFile)
                        var bytesRead = 0
                        while (isPlaying && fis.read(buffer).also { bytesRead = it } > 0) {
                            audioTrack.write(buffer, 0, bytesRead)
                            // 如果写入失败或停止，退出循环
                            if (!isPlaying) break
                        }
                        fis.close()
                    } catch (e: Exception) {
                        com.radio.app.utils.FileLogUtils.e(TAG, "playFingerprint: playback error: ${e.message}", e)
                    } finally {
                        // 播放完毕，自动清理
                        if (isPlaying) {
                            isPlaying = false
                            try {
                                audioTrack.stop()
                                audioTrack.release()
                            } catch (_: Exception) {}
                            if (playbackAudioTrack == audioTrack) {
                                playbackAudioTrack = null
                            }
                        }
                    }
                }.apply {
                    name = "fingerprint-playback-${episodeId.take(8)}"
                    start()
                }
            } catch (e: Exception) {
                com.radio.app.utils.FileLogUtils.e(TAG, "playFingerprint failed: ${e.message}", e)
                isPlaying = false
                playbackAudioTrack = null
            }
        }

        /**
         * 停止指纹播放。
         */
        @JvmStatic
        fun stopPlayback(context: Context) {
            stopPlaybackInternal()
        }

        /**
         * 内部停止播放逻辑，不依赖Context。
         */
        private fun stopPlaybackInternal() {
            isPlaying = false
            val track = playbackAudioTrack
            if (track != null) {
                try {
                    track.stop()
                    track.release()
                } catch (_: Exception) {}
                playbackAudioTrack = null
            }
        }

        /**
         * 测试指纹匹配：从水印PCM文件重新提取指纹，与数据库中的指纹比较相似度。
         */
        @JvmStatic
        fun testFingerprint(context: Context, fingerprint: AudioFingerprint) {
            try {
                val pcmFile = PcmSegmentExtractor.getWatermarkPcmFile(
                    context, fingerprint.episodeId, fingerprint.startMs, fingerprint.endMs
                )
                if (!pcmFile.exists() || pcmFile.length() <= 0) {
                    Log.w(TAG, "testFingerprint: watermark PCM not found for ${fingerprint.episodeId}")
                    return
                }

                val extractedFp = ChromaprintExtractor.extractFingerprintFromFile(pcmFile)
                if (extractedFp.isNullOrBlank()) {
                    Log.w(TAG, "testFingerprint: fingerprint extraction failed from ${pcmFile.name}")
                    return
                }

                val similarity = ChromaprintExtractor.compareFingerprints(fingerprint.fingerprint, extractedFp)
                Log.i(TAG, "testFingerprint: similarity=${String.format(Locale.US, "%.4f", similarity)} for ${fingerprint.episodeId} [${fingerprint.startMs}-${fingerprint.endMs}]")

                // 发送广播通知UI
                try {
                    val intent = Intent(ACTION_FINGERPRINT_ADDED).apply {
                        putExtra(EXTRA_FINGERPRINT_EPISODE_ID, fingerprint.episodeId)
                        putExtra(EXTRA_FINGERPRINT_START_MS, fingerprint.startMs)
                        putExtra(EXTRA_FINGERPRINT_END_MS, fingerprint.endMs)
                        putExtra(EXTRA_FINGERPRINT_MESSAGE, "测试完成，相似度: ${String.format(Locale.US, "%.1f", similarity * 100)}%")
                    }
                    LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
                } catch (_: Exception) {}
            } catch (e: Exception) {
                com.radio.app.utils.FileLogUtils.e(TAG, "testFingerprint failed: ${e.message}", e)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
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
        Log.i(TAG, "onDestroy")
        serviceScope.cancel()
        try {
            wakeLock?.release()
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: startId=$startId, intent=${intent?.action}")
        writeFingerprintLog("onStartCommand: startId=$startId, action=${intent?.action}")
        if (intent == null) {
            Log.w(TAG, "onStartCommand: null intent, stopping")
            writeFingerprintLog("onStartCommand: null intent, stopping")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (intent.action != ACTION_ADD_FINGERPRINT) {
            Log.w(TAG, "onStartCommand: unknown action=${intent.action}")
            writeFingerprintLog("onStartCommand: unknown action=${intent.action}, stopping")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID) ?: ""
        val startMs = intent.getLongExtra(EXTRA_START_MS, 0L)
        val endMs = intent.getLongExtra(EXTRA_END_MS, 0L)
        val episodeTitle = intent.getStringExtra(EXTRA_EPISODE_TITLE)
        writeFingerprintLog("onStartCommand: episodeId=$episodeId, startMs=$startMs, endMs=$endMs, title=$episodeTitle")

        if (episodeId.isBlank() || endMs <= startMs) {
            Log.w(TAG, "onStartCommand: invalid params episodeId=$episodeId, startMs=$startMs, endMs=$endMs")
            writeFingerprintLog("onStartCommand: INVALID params, sending error")
            sendErrorBroadcast(episodeId, startMs, endMs, "参数无效")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val title = buildTitle(episodeId, episodeTitle)
        // v3.0.5-fix: 使用独立通知 ID，避免与字幕/预缓存通知冲突，并支持多任务并发
        val notificationId = NOTIFICATION_ID_BASE + startId
        writeFingerprintLog("onStartCommand: notificationId=$notificationId for startId=$startId")
        try {
            startForeground(notificationId, buildProgressNotification(title, 0, "准备中..."))
            Log.i(TAG, "onStartCommand: startForeground OK for $episodeId [$startMs, $endMs] notificationId=$notificationId")
            writeFingerprintLog("onStartCommand: startForeground OK for $episodeId [$startMs, $endMs] notificationId=$notificationId")
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand: startForeground failed: ${e.message}", e)
            writeFingerprintLog("onStartCommand: startForeground FAILED: ${e.message}")
            sendErrorBroadcast(episodeId, startMs, endMs, "前台服务启动失败: ${e.message}")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        serviceScope.launch {
            runAddFingerprint(episodeId, startMs, endMs, title, notificationId)
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private suspend fun runAddFingerprint(episodeId: String, startMs: Long, endMs: Long, title: String, notificationId: Int) {
        Log.i(TAG, "runAddFingerprint: START $episodeId [$startMs, $endMs] notificationId=$notificationId")
        writeFingerprintLog("runAddFingerprint: START $episodeId [$startMs, $endMs] notificationId=$notificationId")
        try {
            wakeLock?.acquire(5 * 60 * 1000L)
        } catch (_: Exception) {}

        var watermarkPcmFile: File? = null
        try {
            // 1. 检查 Chromaprint 库
            updateNotification(notificationId, title, 10, "检查指纹库...")
            val libLoaded = ChromaprintExtractor.ensureLibraryLoaded(this)
            Log.i(TAG, "runAddFingerprint: library loaded=$libLoaded")
            writeFingerprintLog("runAddFingerprint: library loaded=$libLoaded")
            if (!libLoaded) {
                val msg = "Chromaprint 指纹库未加载，请先在离线引擎管理中下载"
                showErrorNotification(notificationId, title, msg)
                sendErrorBroadcast(episodeId, startMs, endMs, msg)
                Log.w(TAG, "runAddFingerprint: Chromaprint library not loaded")
                writeFingerprintLog("runAddFingerprint: Chromaprint library NOT loaded, aborting")
                return
            }

            // 2. 截取 PCM 片段到固定水印路径（保留不删）
            updateNotification(notificationId, title, 30, "截取音频片段...")
            watermarkPcmFile = withContext(Dispatchers.IO) {
                // 修正/添加时强制重新截取，确保 PCM 与指纹同步
                PcmSegmentExtractor.extractWatermarkPcm(this@AudioFingerprintService, episodeId, startMs, endMs, force = true)
            }
            Log.i(TAG, "runAddFingerprint: watermarkPcmFile=$watermarkPcmFile, exists=${watermarkPcmFile?.exists()}, size=${watermarkPcmFile?.length()}")
            writeFingerprintLog("runAddFingerprint: watermarkPcmFile=${watermarkPcmFile?.absolutePath}, exists=${watermarkPcmFile?.exists()}, size=${watermarkPcmFile?.length()}")
            if (watermarkPcmFile == null || !watermarkPcmFile.exists() || watermarkPcmFile.length() <= 0) {
                val msg = "无法截取音频片段（缺少 PCM 缓存）"
                showErrorNotification(notificationId, title, msg)
                sendErrorBroadcast(episodeId, startMs, endMs, msg)
                writeFingerprintLog("runAddFingerprint: watermark PCM missing/empty, aborting")
                return
            }

            // 3. 提取指纹
            updateNotification(notificationId, title, 60, "提取音频指纹...")
            val fingerprint = withContext(Dispatchers.IO) {
                ChromaprintExtractor.extractFingerprintFromFile(watermarkPcmFile)
            }
            Log.i(TAG, "runAddFingerprint: fingerprint empty=${fingerprint.isNullOrBlank()}, length=${fingerprint?.length}")
            writeFingerprintLog("runAddFingerprint: fingerprint empty=${fingerprint.isNullOrBlank()}, length=${fingerprint?.length}")
            if (fingerprint.isNullOrBlank()) {
                val msg = "指纹提取失败，请尝试在离线引擎管理中重新下载 Chromaprint 指纹库"
                showErrorNotification(notificationId, title, msg)
                sendErrorBroadcast(episodeId, startMs, endMs, msg)
                writeFingerprintLog("runAddFingerprint: fingerprint extraction returned empty, aborting")
                return
            }

            // 4. 存入数据库（先删除同一片段旧记录，再插入新记录，避免 replace 歧义导致重复）
            updateNotification(notificationId, title, 90, "保存指纹...")
            val dbHelper = RadioDatabaseHelper.getInstance(this)
            // v3.0.9: 强制删除同 episode + 同起止时间的旧记录，确保修正不会新增重复项
            val deletedOld = dbHelper.deleteAudioFingerprintsByRange(episodeId, startMs, endMs)
            writeFingerprintLog("runAddFingerprint: deletedOld=$deletedOld for $episodeId [$startMs, $endMs]")
            val audioFingerprint = AudioFingerprint(
                id = 0,
                episodeId = episodeId,
                startMs = startMs,
                endMs = endMs,
                fingerprint = fingerprint,
                durationMs = endMs - startMs
            )
            val rowId = dbHelper.saveAudioFingerprint(audioFingerprint)
            Log.i(TAG, "runAddFingerprint: saved fingerprint rowId=$rowId for $episodeId [$startMs, $endMs]")
            writeFingerprintLog("runAddFingerprint: saved fingerprint rowId=$rowId for $episodeId [$startMs, $endMs]")

            // 5. 验证入库
            val saved = dbHelper.getAudioFingerprintsByEpisode(episodeId).find {
                it.startMs == startMs && it.endMs == endMs
            }
            writeFingerprintLog("runAddFingerprint: verify saved=${saved != null}, fingerprint length=${saved?.fingerprint?.length}")
            if (saved == null) {
                val msg = "指纹保存后查询失败"
                showErrorNotification(notificationId, title, msg)
                sendErrorBroadcast(episodeId, startMs, endMs, msg)
                writeFingerprintLog("runAddFingerprint: saved record not found after insert, aborting")
                return
            }

            // 6. 保留水印 PCM，不删除
            updateNotification(notificationId, title, 100, "完成")
            showCompleteNotification(notificationId, title, "已添加为水分指纹")
            sendSuccessBroadcast(episodeId, startMs, endMs)

            Log.i(TAG, "runAddFingerprint: COMPLETE for $episodeId [$startMs, $endMs], fp_len=${fingerprint.length}")
            writeFingerprintLog("runAddFingerprint: COMPLETE for $episodeId [$startMs, $endMs], fp_len=${fingerprint.length}")
        } catch (e: Exception) {
            Log.e(TAG, "runAddFingerprint failed: ${e.message}", e)
            writeFingerprintLog("runAddFingerprint: ERROR ${e.message} ${Log.getStackTraceString(e)}")
            val msg = "添加指纹失败: ${e.message}"
            showErrorNotification(notificationId, title, msg)
            sendErrorBroadcast(episodeId, startMs, endMs, msg)
        } finally {
            // 保留水印 PCM，不自动删除；仅释放唤醒锁
            try {
                wakeLock?.release()
            } catch (_: Exception) {}
        }
    }

    private fun sendSuccessBroadcast(episodeId: String, startMs: Long, endMs: Long) {
        try {
            val intent = Intent(ACTION_FINGERPRINT_ADDED).apply {
                putExtra(EXTRA_FINGERPRINT_EPISODE_ID, episodeId)
                putExtra(EXTRA_FINGERPRINT_START_MS, startMs)
                putExtra(EXTRA_FINGERPRINT_END_MS, endMs)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "sendSuccessBroadcast failed: ${e.message}")
        }
    }

    private fun sendErrorBroadcast(episodeId: String, startMs: Long, endMs: Long, message: String) {
        try {
            val intent = Intent(ACTION_FINGERPRINT_ERROR).apply {
                putExtra(EXTRA_FINGERPRINT_EPISODE_ID, episodeId)
                putExtra(EXTRA_FINGERPRINT_START_MS, startMs)
                putExtra(EXTRA_FINGERPRINT_END_MS, endMs)
                putExtra(EXTRA_FINGERPRINT_MESSAGE, message)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "sendErrorBroadcast failed: ${e.message}")
        }
    }

    /**
     * v3.0.5-fix: 将指纹服务关键步骤写入持久日志，便于排查“无通知、无入库、无报错”问题。
     */
    private fun writeFingerprintLog(message: String) {
        try {
            val logDir = java.io.File(com.radio.app.RadioApplication.getLogDir(this), "fingerprint")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = File(logDir, "fingerprint_service.log")
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            FileWriter(logFile, true).use { it.append("[$ts] $message\n") }
        } catch (e: Exception) {
            Log.e(TAG, "writeFingerprintLog failed: ${e.message}")
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
                NotificationManager.IMPORTANCE_DEFAULT
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

    private fun updateNotification(notificationId: Int, title: String, progress: Int, status: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(notificationId, buildProgressNotification(title, progress, status))
        } catch (e: Exception) {
            Log.e(TAG, "updateNotification failed: ${e.message}")
        }
    }

    private fun showCompleteNotification(notificationId: Int, title: String, message: String) {
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
            nm.notify(notificationId, notification)
        } catch (e: Exception) {
            Log.e(TAG, "showCompleteNotification failed: ${e.message}")
        }
    }

    private fun showErrorNotification(notificationId: Int, title: String, error: String) {
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
            nm.notify(notificationId, notification)
        } catch (e: Exception) {
            Log.e(TAG, "showErrorNotification failed: ${e.message}")
        }
    }
}
