package com.radio.app.utils

import android.content.Context
import com.radio.app.models.Episode
import org.json.JSONArray
import org.json.JSONObject

/**
 * v3.1.117: 播放历史记录管理。
 * 记录最近播放的10个节目，通过 SharedPreferences 持久化。
 */
object PlayHistoryUtils {

    private const val PREFS_NAME = "play_history"
    private const val KEY_HISTORY = "history_list"
    private const val MAX_HISTORY = 10

    data class HistoryItem(
        val episodeId: String,
        val title: String,
        val broadcastAt: String,
        val stationName: String,
        val stationId: String,
        val audioUrl: String,
        val duration: Long,
        val programName: String?,
        val lastPosition: Long,
        val playedAt: Long  // 播放时间戳，用于排序
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("episodeId", episodeId)
                put("title", title)
                put("broadcastAt", broadcastAt)
                put("stationName", stationName)
                put("stationId", stationId)
                put("audioUrl", audioUrl)
                put("duration", duration)
                put("programName", programName ?: "")
                put("lastPosition", lastPosition)
                put("playedAt", playedAt)
            }
        }

        companion object {
            fun fromJson(json: JSONObject): HistoryItem {
                return HistoryItem(
                    episodeId = json.optString("episodeId", ""),
                    title = json.optString("title", ""),
                    broadcastAt = json.optString("broadcastAt", ""),
                    stationName = json.optString("stationName", ""),
                    stationId = json.optString("stationId", ""),
                    audioUrl = json.optString("audioUrl", ""),
                    duration = json.optLong("duration", 0L),
                    programName = json.optString("programName", null),
                    lastPosition = json.optLong("lastPosition", 0L),
                    playedAt = json.optLong("playedAt", 0L)
                )
            }
        }

        fun toEpisode(): Episode {
            return Episode(
                id = episodeId,
                title = title,
                broadcastAt = broadcastAt,
                duration = duration,
                startTime = 0L,
                endTime = 0L,
                description = "",
                stationId = stationId,
                stationName = stationName,
                audioUrl = audioUrl,
                isLive = false,
                isDisliked = false,
                isCached = false,
                voiceSegments = emptyList(),
                transcripts = emptyList(),
                programName = programName
            )
        }
    }

    /**
     * 获取播放历史列表（按播放时间倒序，最近的在最前）
     */
    fun getHistory(context: Context): List<HistoryItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<HistoryItem>()
            for (i in 0 until arr.length()) {
                val item = HistoryItem.fromJson(arr.getJSONObject(i))
                if (item.episodeId.isNotBlank()) {
                    list.add(item)
                }
            }
            // 按播放时间倒序排序（最近的在最前）
            list.sortedByDescending { it.playedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 记录播放历史。
     * 如果已存在同episodeId，更新其位置和播放时间；否则添加新条目。
     * 最多保留 MAX_HISTORY 条。
     */
    fun recordHistory(context: Context, episode: Episode, positionMs: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = getHistory(context).toMutableList()

        // 移除同episodeId的旧条目（如果存在），再新插入到最前面
        existing.removeAll { it.episodeId == episode.id }

        val newItem = HistoryItem(
            episodeId = episode.id,
            title = episode.title,
            broadcastAt = episode.broadcastAt,
            stationName = episode.stationName,
            stationId = episode.stationId,
            audioUrl = episode.audioUrl,
            duration = episode.duration,
            programName = episode.programName,
            lastPosition = positionMs,
            playedAt = System.currentTimeMillis()
        )

        // 插入到最前面
        existing.add(0, newItem)

        // 只保留最近 MAX_HISTORY 条
        val trimmed = existing.take(MAX_HISTORY)

        // 序列化为JSON
        val arr = JSONArray()
        for (item in trimmed) {
            arr.put(item.toJson())
        }

        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    /**
     * 更新指定节目的播放位置
     */
    fun updatePosition(context: Context, episodeId: String, positionMs: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = getHistory(context).toMutableList()
        val idx = existing.indexOfFirst { it.episodeId == episodeId }
        if (idx < 0) return

        existing[idx] = existing[idx].copy(
            lastPosition = positionMs,
            playedAt = System.currentTimeMillis()
        )

        val arr = JSONArray()
        for (item in existing) {
            arr.put(item.toJson())
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    /**
     * 清空播放历史
     */
    fun clearHistory(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_HISTORY).apply()
    }
}