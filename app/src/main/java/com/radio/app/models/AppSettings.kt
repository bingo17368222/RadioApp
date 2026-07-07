package com.radio.app.models

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

class AppSettings private constructor() {

    companion object {
        private const val PREFS_NAME = "radio_app_settings"

        const val AI_MODEL_WENXIN = "wenxin"
        const val AI_MODEL_DEEPSEEK = "deepseek"
        const val AI_MODEL_QWEN = "qwen"
        const val AI_MODEL_FUNASR = "funasr"
        const val AI_MODEL_WHISPER = "whisper"
        const val AI_MODEL_JIU_AI_TING = "jiu-ai-ting"
        const val AI_MODEL_MNN_LLM = "mnn-llm"

        const val ASR_BAIDU = "baidu"
        const val ASR_FUNASR = "funasr"
        const val ASR_WHISPER = "whisper"
        const val ASR_VOSK = "vosk"

        const val THEME_DARK = "dark"
        const val THEME_FRESH = "fresh"
        const val THEME_CLASSIC = "classic"
        const val THEME_MINIMAL = "minimal"
        const val THEME_CUSTOM = "custom"

        const val SUBTITLE_SMALL = "small"
        const val SUBTITLE_MEDIUM = "medium"
        const val SUBTITLE_LARGE = "large"
        const val LANG_CN = "zh-CN"
        const val LANG_EN = "en-US"

        const val SPLIT_MODE_NONE = "none"
        const val SPLIT_MODE_HORIZONTAL = "horizontal"
        const val SPLIT_MODE_VERTICAL = "vertical"

        @Volatile
        private var instance: AppSettings? = null

        fun getInstance(context: Context): AppSettings {
            return instance ?: synchronized(this) {
                instance ?: AppSettings().also {
                    it.load(context)
                    instance = it
                }
            }
        }
    }

    var aiModel: String = AI_MODEL_WENXIN
    var asrProvider: String = ASR_BAIDU
    var voskModelDir: String = ""  // [v2.0.61] Issue 6 Fix: Save selected Vosk model directory name
    var whisperModelDir: String = ""  // [v2.2.9] Save selected Whisper model directory name (whisper-tiny, whisper-base, etc.)
    // [v2.0.50] Issue 3 Fix: Track Whisper crash count to permanently disable after 2 crashes
    var whisperCrashCount: Int = 0
    var forceVoskUntil: Long = 0  // [v2.2.8] Force Vosk after Whisper crashes (timestamp until which to skip Whisper)
    var uiTheme: String = THEME_DARK
    var subtitleSize: String = SUBTITLE_MEDIUM
    var subtitleLanguage: String = LANG_CN
    var voiceLanguage: String = LANG_CN
    var splitMode: String = SPLIT_MODE_NONE
    var customColors: CustomColors = CustomColors()
    var keywordConfig: KeywordConfig = KeywordConfig()
    var preloadCache: Boolean = false
    var preloadCacheCount: Int = 10
    var enablePreprocessing: Boolean = true
    var preprocessingCount: Int = 1
    var audioFocus: Boolean = true
    var continuousPlay: Boolean = true
    var autoSkipWater: Boolean = true
    var autoDownload: Boolean = false
    var autoCache: Boolean = false
    var savePlaybackPosition: Boolean = true
    var rememberLastEpisode: Boolean = true
    var wifiOnlyPreCache: Boolean = true
    var notificationStyle: String = "compact"
    var skipSeconds: Int = 15
    var dislikedEpisodes: MutableList<String> = mutableListOf()
    var stationPlayCount: MutableMap<String, Int> = mutableMapOf()
    var lastSelectedDate: String = ""
    var lastSelectedStationId: String = ""
    var pinduoduoDetectionInterval: Int = 5  // [v2.1.2] seconds, default 5

    /** Gson 安全访问：确保字段不为 null */
    fun safeSubtitleSize(): String = subtitleSize ?: SUBTITLE_MEDIUM
    fun safeSubtitleLanguage(): String = subtitleLanguage ?: LANG_CN
    fun safeVoiceLanguage(): String = voiceLanguage ?: LANG_CN
    fun safeUiTheme(): String = uiTheme ?: THEME_DARK
    fun safeAiModel(): String = aiModel ?: AI_MODEL_WENXIN
    fun safeAsrProvider(): String = asrProvider ?: ASR_BAIDU
    fun safeSplitMode(): String = splitMode ?: SPLIT_MODE_NONE

    /**
     * v2.0.97 - Reload ASR provider and Vosk model dir from SharedPreferences.
     * SubtitleGeneratorService runs in a separate process (:subtitle), whose AppSettings
     * singleton is loaded once at process start and never refreshed. When user changes ASR
     * engine in UI process, the subtitle process's singleton is stale. This method re-reads
     * the latest values from SharedPreferences.
     */
    fun reloadAsrSettings(context: Context) {
        // [v2.4.0] Force reload from disk — SharedPreferences doesn't sync across processes
        // (UI process writes, :subtitle process has stale cache). The broadcast handler writes
        // to this process's prefs, but if the broadcast wasn't received, we need to force-read.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS)
        prefs.all  // Force access to trigger reload
        asrProvider = prefs.getString("asr_provider", ASR_BAIDU) ?: ASR_BAIDU
        voskModelDir = prefs.getString("vosk_model_dir", "") ?: ""
        whisperModelDir = prefs.getString("whisper_model_dir", "") ?: ""  // [v2.2.9]
        // [v2.1.2] Also reload pinduoduo detection interval for RadioPlaybackService
        pinduoduoDetectionInterval = prefs.getInt("pinduoduo_detection_interval", 5)
    }

    private fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS)
        aiModel = prefs.getString("ai_model", AI_MODEL_WENXIN) ?: AI_MODEL_WENXIN
        asrProvider = prefs.getString("asr_provider", ASR_BAIDU) ?: ASR_BAIDU
        voskModelDir = prefs.getString("vosk_model_dir", "") ?: ""  // [v2.0.61] Issue 6
        whisperModelDir = prefs.getString("whisper_model_dir", "") ?: ""  // [v2.2.9]
        whisperCrashCount = prefs.getInt("whisper_crash_count", 0)  // [v2.0.50]
        forceVoskUntil = prefs.getLong("force_vosk_until", 0)  // [v2.2.8]
        uiTheme = prefs.getString("ui_theme", THEME_DARK) ?: THEME_DARK
        preloadCache = prefs.getBoolean("preload_cache", false)
        preloadCacheCount = prefs.getInt("preload_cache_count", 1)
        autoCache = prefs.getBoolean("auto_cache", false)
        enablePreprocessing = prefs.getBoolean("enable_preprocessing", true)
        preprocessingCount = prefs.getInt("preprocessing_count", 1)
        audioFocus = prefs.getBoolean("audio_focus", true)
        continuousPlay = prefs.getBoolean("continuous_play", true)
        savePlaybackPosition = prefs.getBoolean("save_playback_position", true)
        rememberLastEpisode = prefs.getBoolean("remember_last_episode", true)
        wifiOnlyPreCache = prefs.getBoolean("wifi_only_precache", true)
        notificationStyle = prefs.getString("notification_style", "compact") ?: "compact"
        skipSeconds = prefs.getInt("skip_seconds", 15)
        splitMode = prefs.getString("split_mode", SPLIT_MODE_NONE) ?: SPLIT_MODE_NONE

        val dislikedJson = prefs.getString("disliked_episodes", "[]") ?: "[]"
        try {
            val arr = JSONArray(dislikedJson)
            dislikedEpisodes.clear()
            for (i in 0 until arr.length()) {
                dislikedEpisodes.add(arr.getString(i))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 加载上次选择的日期和电台
        lastSelectedDate = prefs.getString("last_selected_date", "") ?: ""
        lastSelectedStationId = prefs.getString("last_selected_station_id", "") ?: ""
        pinduoduoDetectionInterval = prefs.getInt("pinduoduo_detection_interval", 5)  // [v2.1.2]

        // 加载播放次数
        val playCountJson = prefs.getString("station_play_count", "{}") ?: "{}"
        try {
            val obj = org.json.JSONObject(playCountJson)
            stationPlayCount.clear()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                stationPlayCount[key] = obj.getInt(key)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS)
        prefs.edit().apply {
            putString("ai_model", aiModel)
            putString("asr_provider", asrProvider)
            putString("vosk_model_dir", voskModelDir)  // [v2.0.61] Issue 6
            putString("whisper_model_dir", whisperModelDir)  // [v2.2.9]
            putInt("whisper_crash_count", whisperCrashCount)  // [v2.0.50]
            putLong("force_vosk_until", forceVoskUntil)  // [v2.2.8]
            putString("ui_theme", uiTheme)
            putBoolean("preload_cache", preloadCache)
            putInt("preload_cache_count", preloadCacheCount)
            putBoolean("auto_cache", autoCache)
            putBoolean("enable_preprocessing", enablePreprocessing)
            putInt("preprocessing_count", preprocessingCount)
            putBoolean("audio_focus", audioFocus)
            putBoolean("continuous_play", continuousPlay)
            putBoolean("save_playback_position", savePlaybackPosition)
            putBoolean("remember_last_episode", rememberLastEpisode)
            putBoolean("wifi_only_precache", wifiOnlyPreCache)
            putString("notification_style", notificationStyle)
            putInt("skip_seconds", skipSeconds)
            putString("split_mode", splitMode)
            val arr = JSONArray()
            dislikedEpisodes.forEach { arr.put(it) }
            putString("disliked_episodes", arr.toString())
            val playCountObj = org.json.JSONObject()
            stationPlayCount.forEach { (k, v) -> playCountObj.put(k, v) }
            putString("station_play_count", playCountObj.toString())
            putString("last_selected_date", lastSelectedDate)
            putString("last_selected_station_id", lastSelectedStationId)
            putInt("pinduoduo_detection_interval", pinduoduoDetectionInterval)  // [v2.1.2]
            apply()
        }
    }

    fun addDislikedEpisode(context: Context, episodeId: String, stationId: String? = null, title: String? = null) {
        var changed = false
        if (!dislikedEpisodes.contains(episodeId)) {
            dislikedEpisodes.add(episodeId)
            changed = true
        }
        // 同时存储基于 title 的 key，使 dislike 过滤能跨天匹配同一节目
        if (!stationId.isNullOrBlank() && !title.isNullOrBlank()) {
            val titleKey = "$stationId::$title"
            if (!dislikedEpisodes.contains(titleKey)) {
                dislikedEpisodes.add(titleKey)
                changed = true
            }
        }
        if (changed) save(context)
    }

    fun removeDislikedEpisode(context: Context, episodeId: String) {
        dislikedEpisodes.remove(episodeId)
        save(context)
    }

    fun isDisliked(episodeId: String): Boolean = dislikedEpisodes.contains(episodeId)

    /**
     * 按节目名称判断是否不喜欢（每天这个节目都不喜欢）
     * 兼容《》括号： disliked列表可能有《标题》，但实际标题可能没有《》
     */
    fun isDislikedByTitle(stationId: String?, title: String?): Boolean {
        if (stationId.isNullOrBlank() || title.isNullOrBlank()) return false
        val key = "$stationId::$title"
        if (dislikedEpisodes.contains(key)) return true
        // 尝试去除《》括号后匹配
        val strippedTitle = title.replace(Regex("^《|》$"), "")
        if (strippedTitle != title) {
            val strippedKey = "$stationId::$strippedTitle"
            if (dislikedEpisodes.contains(strippedKey)) return true
        }
        // 尝试添加《》括号后匹配
        val wrappedTitle = "《$title》"
        val wrappedKey = "$stationId::$wrappedTitle"
        if (dislikedEpisodes.contains(wrappedKey)) return true
        // 归一化标题：去除所有标点符号后比较（兼容 ·、-、（）等标点差异）
        // 说明：v2.0.39 的子串模糊匹配过于激进，会把用户喜欢的节目误判为不喜欢。
        // 现改为精确的标点归一化匹配——仅当去掉所有标点后标题完全相同（或仅差「重播」后缀）时才命中。
        val normalizeTitle = { s: String -> s.replace(Regex("[《》·\\-—（）()\\[】【]"), "") }
        val normTitle = normalizeTitle(title)
        val normStrippedTitle = normalizeTitle(strippedTitle)
        for (dislikedKey in dislikedEpisodes) {
            if (!dislikedKey.startsWith("$stationId::")) continue
            val dislikedTitle = dislikedKey.removePrefix("$stationId::")
            val strippedDisliked = dislikedTitle.replace(Regex("^《|》$"), "")
            val normDisliked = normalizeTitle(strippedDisliked)
            if (normDisliked == normTitle || normDisliked == normStrippedTitle) return true
            // 兼容（重播）后缀：disliked 标题去除标点后加上"重播"与当前标题匹配
            if (normDisliked + "重播" == normTitle || normDisliked + "重播" == normStrippedTitle) return true
        }
        return false
    }

    fun toggleDislikedEpisode(context: Context, episode: com.radio.app.models.Episode): Boolean {
        val stationId = episode.stationId ?: return false
        val title = episode.title ?: return false
        val key = "$stationId::$title"
        val epId = episode.id
        // 判断是否已 dislike：title-based key 或 episode ID 任一存在即视为已 dislike
        val isCurrentlyDisliked = dislikedEpisodes.contains(key) || dislikedEpisodes.contains(epId)
        return if (isCurrentlyDisliked) {
            // --- 取消 dislike：移除所有相关 key ---
            // 移除 title-based key 及其变体（有/无《》括号）
            dislikedEpisodes.remove(key)
            val strippedTitle = title.replace(Regex("^《|》$"), "")
            if (strippedTitle != title) {
                val strippedKey = "$stationId::$strippedTitle"
                dislikedEpisodes.remove(strippedKey)
            }
            val wrappedTitle = "《$title》"
            val wrappedKey = "$stationId::$wrappedTitle"
            dislikedEpisodes.remove(wrappedKey)
            // 移除所有模糊匹配的 title-based key
            val toRemove = mutableListOf<String>()
            for (dislikedKey in dislikedEpisodes) {
                if (!dislikedKey.startsWith("$stationId::")) continue
                val dislikedTitle = dislikedKey.removePrefix("$stationId::")
                val strippedDisliked = dislikedTitle.replace(Regex("^《|》$"), "")
                if (strippedDisliked == title || strippedDisliked == strippedTitle) {
                    toRemove.add(dislikedKey)
                }
            }
            dislikedEpisodes.removeAll(toRemove)
            // 同时移除 episode ID（精确匹配）
            dislikedEpisodes.remove(epId)
            save(context)
            false
        } else {
            // --- 设置 dislike：同时存储 title-based key 和 episode ID ---
            dislikedEpisodes.add(key)       // title-based key，跨天匹配同一节目
            dislikedEpisodes.add(epId)      // episode ID，精确匹配当天那一集
            save(context)
            // 同时保存到 all_episodes 持久化存储，确保 dislike 筛选能跨天找到该节目
            if (!episode.audioUrl.isNullOrBlank()) {
                try {
                    val prefs = context.getSharedPreferences("all_episodes", android.content.Context.MODE_PRIVATE)
                    prefs.edit().putString(episode.audioUrl, com.google.gson.Gson().toJson(episode)).apply()
                } catch (e: Exception) { /* ignore */ }
            }
            true
        }
    }

    fun incrementStationPlayCount(context: Context, stationId: String) {
        val count = stationPlayCount.getOrDefault(stationId, 0) + 1
        stationPlayCount[stationId] = count
        save(context)
    }

    fun getStationPlayCount(stationId: String): Int {
        return stationPlayCount.getOrDefault(stationId, 0)
    }

    data class CustomColors(
        var primary: String = "#0f3460",
        var accent: String = "#e94560",
        var background: String = "#1a1a2e",
        var text: String = "#eaeaea",
        var card: String = "#16213e",
        var border: String = "#0f3460",
        var success: String = "#4ecca3",
        var warning: String = "#f4d03f"
    )

    data class KeywordConfig(
        var dryKeywords: MutableList<String> = mutableListOf("新闻", "资讯", "报道", "访谈", "评论"),
        var waterKeywords: MutableList<String> = mutableListOf("广告", "音乐", "歌曲", "休息"),
        var contentDryKeywords: MutableList<String> = mutableListOf(),
        var contentWaterKeywords: MutableList<String> = mutableListOf(),
        var dryLogic: String = "or",
        var waterLogic: String = "or",
        var contentDryLogic: String = "or",
        var contentWaterLogic: String = "or"
    )
}
