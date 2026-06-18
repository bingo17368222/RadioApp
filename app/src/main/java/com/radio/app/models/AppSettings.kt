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
    var uiTheme: String = THEME_DARK
    var subtitleSize: String = SUBTITLE_MEDIUM
    var subtitleLanguage: String = LANG_CN
    var voiceLanguage: String = LANG_CN
    var splitMode: String = SPLIT_MODE_NONE
    var customColors: CustomColors = CustomColors()
    var keywordConfig: KeywordConfig = KeywordConfig()
    var preloadCache: Boolean = false
    var preloadCacheCount: Int = 1
    var enablePreprocessing: Boolean = false
    var preprocessingCount: Int = 1
    var audioFocus: Boolean = true
    var continuousPlay: Boolean = true
    var autoSkipWater: Boolean = true
    var autoDownload: Boolean = false
    var autoCache: Boolean = false
    var dislikedEpisodes: MutableList<String> = mutableListOf()

    private fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        aiModel = prefs.getString("ai_model", AI_MODEL_WENXIN) ?: AI_MODEL_WENXIN
        asrProvider = prefs.getString("asr_provider", ASR_BAIDU) ?: ASR_BAIDU
        uiTheme = prefs.getString("ui_theme", THEME_DARK) ?: THEME_DARK
        preloadCache = prefs.getBoolean("preload_cache", false)
        preloadCacheCount = prefs.getInt("preload_cache_count", 1)
        enablePreprocessing = prefs.getBoolean("enable_preprocessing", false)
        preprocessingCount = prefs.getInt("preprocessing_count", 1)
        audioFocus = prefs.getBoolean("audio_focus", true)
        continuousPlay = prefs.getBoolean("continuous_play", true)
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
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("ai_model", aiModel)
            putString("asr_provider", asrProvider)
            putString("ui_theme", uiTheme)
            putBoolean("preload_cache", preloadCache)
            putInt("preload_cache_count", preloadCacheCount)
            putBoolean("enable_preprocessing", enablePreprocessing)
            putInt("preprocessing_count", preprocessingCount)
            putBoolean("audio_focus", audioFocus)
            putBoolean("continuous_play", continuousPlay)
            putString("split_mode", splitMode)
            val arr = JSONArray()
            dislikedEpisodes.forEach { arr.put(it) }
            putString("disliked_episodes", arr.toString())
            apply()
        }
    }

    fun addDislikedEpisode(context: Context, episodeId: String) {
        if (!dislikedEpisodes.contains(episodeId)) {
            dislikedEpisodes.add(episodeId)
            save(context)
        }
    }

    fun removeDislikedEpisode(context: Context, episodeId: String) {
        dislikedEpisodes.remove(episodeId)
        save(context)
    }

    fun isDisliked(episodeId: String): Boolean = dislikedEpisodes.contains(episodeId)

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
