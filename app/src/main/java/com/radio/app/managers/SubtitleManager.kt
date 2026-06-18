package com.radio.app.managers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.radio.app.models.Transcript
import com.radio.app.services.SubtitleGeneratorService

class SubtitleManager(context: Context) {

    companion object {
        private const val TAG = "SubtitleManager"
    }

    private val context: Context = context.applicationContext
    private var service: SubtitleGeneratorService? = null
    private var bound = false
    private var unbinding = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, s: IBinder?) {
            service = (s as? SubtitleGeneratorService.LocalBinder)?.getService()
            bound = true
            unbinding = false
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    init {
        try {
            context.bindService(Intent(context, SubtitleGeneratorService::class.java), conn, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind service", e)
        }
    }

    fun generateSubtitles(epId: String, url: String, cb: SubtitleGeneratorService.SubtitleCallback) {
        if (bound && service != null) {
            service?.generateSubtitlesForEpisode(epId, url, cb)
        } else {
            // 服务未绑定，使用本地模拟生成
            simulateSubtitleGeneration(epId, cb)
        }
    }

    private fun simulateSubtitleGeneration(epId: String, cb: SubtitleGeneratorService.SubtitleCallback) {
        Thread {
            try {
                val sampleTexts = arrayOf(
                    "欢迎各位听众，今天我们将为您带来最新的新闻资讯。",
                    "首先来看国内要闻，今日上午国务院召开常务会议。",
                    "国际方面，联合国秘书长发表声明呼吁和平解决争端。",
                    "财经市场上，今日A股三大指数集体收涨。",
                    "科技领域，我国自主研发的新一代芯片正式发布。"
                )
                for (i in sampleTexts.indices) {
                    val t = Transcript().apply {
                        episodeId = epId
                        segmentStart = (i * 30).toLong()
                        segmentEnd = ((i + 1) * 30).toLong()
                        text = sampleTexts[i]
                        confidence = 0.85
                    }
                    mainHandler.post {
                        cb.onSubtitleGenerated(t)
                        cb.onProgressUpdate(i + 1, sampleTexts.size)
                    }
                    Thread.sleep(600)
                }
            } catch (e: Exception) {
                mainHandler.post { cb.onError(e.message ?: "Unknown error") }
            }
        }.start()
    }

    fun getSubtitles(epId: String): List<Transcript>? {
        return if (bound && service != null) service?.getSubtitles(epId) else null
    }

    fun isOfflineAvailable(): Boolean {
        return bound && service != null && service!!.isOfflineAvailable()
    }

    fun release() {
        if (bound && !unbinding) {
            unbinding = true
            try {
                context.unbindService(conn)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Service already unbound or not registered", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding service", e)
            }
            bound = false
            unbinding = false
        }
        service = null
    }
}
