package com.radio.app.models;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AppSettings {
    private static final String PREFS_NAME = "radio_app_settings";
    private static AppSettings instance;

    public static synchronized AppSettings getInstance(Context context) {
        if (instance == null) {
            instance = new AppSettings();
            instance.load(context);
        }
        return instance;
    }

    private void load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        aiModel = prefs.getString("ai_model", AI_MODEL_WENXIN);
        asrProvider = prefs.getString("asr_provider", ASR_BAIDU);
        uiTheme = prefs.getString("ui_theme", THEME_DARK);
        preloadCache = prefs.getBoolean("preload_cache", false);
        preloadCacheCount = prefs.getInt("preload_cache_count", 1);
        enablePreprocessing = prefs.getBoolean("enable_preprocessing", false);
        preprocessingCount = prefs.getInt("preprocessing_count", 1);
        audioFocus = prefs.getBoolean("audio_focus", true);
        continuousPlay = prefs.getBoolean("continuous_play", true);
        String dislikedJson = prefs.getString("disliked_episodes", "[]");
        try {
            JSONArray arr = new JSONArray(dislikedJson);
            dislikedEpisodes.clear();
            for (int i = 0; i < arr.length(); i++) {
                dislikedEpisodes.add(arr.getString(i));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void save(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("ai_model", aiModel);
        editor.putString("asr_provider", asrProvider);
        editor.putString("ui_theme", uiTheme);
        editor.putBoolean("preload_cache", preloadCache);
        editor.putInt("preload_cache_count", preloadCacheCount);
        editor.putBoolean("enable_preprocessing", enablePreprocessing);
        editor.putInt("preprocessing_count", preprocessingCount);
        editor.putBoolean("audio_focus", audioFocus);
        editor.putBoolean("continuous_play", continuousPlay);
        JSONArray arr = new JSONArray();
        for (String id : dislikedEpisodes) arr.put(id);
        editor.putString("disliked_episodes", arr.toString());
        editor.apply();
    }

    public void addDislikedEpisode(Context context, String episodeId) {
        if (!dislikedEpisodes.contains(episodeId)) {
            dislikedEpisodes.add(episodeId);
            save(context);
        }
    }

    public void removeDislikedEpisode(Context context, String episodeId) {
        dislikedEpisodes.remove(episodeId);
        save(context);
    }

    public boolean isDisliked(String episodeId) {
        return dislikedEpisodes.contains(episodeId);
    }

    public static final String AI_MODEL_WENXIN = "wenxin";
    public static final String AI_MODEL_DEEPSEEK = "deepseek";
    public static final String AI_MODEL_QWEN = "qwen";
    public static final String AI_MODEL_FUNASR = "funasr";
    public static final String AI_MODEL_WHISPER = "whisper";
    public static final String AI_MODEL_JIU_AI_TING = "jiu-ai-ting";

    public static final String ASR_BAIDU = "baidu";
    public static final String ASR_FUNASR = "funasr";
    public static final String ASR_WHISPER = "whisper";
    public static final String ASR_VOSK = "vosk";

    public static final String THEME_DARK = "dark";
    public static final String THEME_FRESH = "fresh";
    public static final String THEME_CLASSIC = "classic";
    public static final String THEME_MINIMAL = "minimal";
    public static final String THEME_CUSTOM = "custom";

    public static final String SUBTITLE_SMALL = "small";
    public static final String SUBTITLE_MEDIUM = "medium";
    public static final String SUBTITLE_LARGE = "large";
    public static final String LANG_CN = "zh-CN";
    public static final String LANG_EN = "en-US";

    private String aiModel = AI_MODEL_WENXIN;
    private boolean autoSkipWater = true;
    private boolean autoDownload = false;
    private boolean autoCache = false;
    private String subtitleSize = SUBTITLE_MEDIUM;
    private String subtitleLanguage = LANG_CN;
    private String voiceLanguage = LANG_CN;
    private String asrProvider = ASR_BAIDU;
    private String uiTheme = THEME_DARK;
    private CustomColors customColors = new CustomColors();
    private KeywordConfig keywordConfig = new KeywordConfig();
    private boolean preloadCache = false;
    private int preloadCacheCount = 1;
    private boolean enablePreprocessing = false;
    private int preprocessingCount = 1;
    private boolean audioFocus = true;
    private boolean continuousPlay = true;
    private List<String> dislikedEpisodes = new ArrayList<>();

    public String getAiModel() { return aiModel; }
    public void setAiModel(String aiModel) { this.aiModel = aiModel; }
    public String getAsrProvider() { return asrProvider; }
    public void setAsrProvider(String asrProvider) { this.asrProvider = asrProvider; }
    public String getUiTheme() { return uiTheme; }
    public void setUiTheme(String uiTheme) { this.uiTheme = uiTheme; }
    public CustomColors getCustomColors() { return customColors; }
    public void setCustomColors(CustomColors customColors) { this.customColors = customColors; }
    public KeywordConfig getKeywordConfig() { return keywordConfig; }
    public void setKeywordConfig(KeywordConfig keywordConfig) { this.keywordConfig = keywordConfig; }
    public boolean isPreloadCache() { return preloadCache; }
    public void setPreloadCache(boolean preloadCache) { this.preloadCache = preloadCache; }
    public int getPreloadCacheCount() { return preloadCacheCount; }
    public void setPreloadCacheCount(int preloadCacheCount) { this.preloadCacheCount = preloadCacheCount; }
    public boolean isEnablePreprocessing() { return enablePreprocessing; }
    public void setEnablePreprocessing(boolean enablePreprocessing) { this.enablePreprocessing = enablePreprocessing; }
    public int getPreprocessingCount() { return preprocessingCount; }
    public void setPreprocessingCount(int preprocessingCount) { this.preprocessingCount = preprocessingCount; }
    public boolean isAudioFocus() { return audioFocus; }
    public void setAudioFocus(boolean audioFocus) { this.audioFocus = audioFocus; }
    public boolean isContinuousPlay() { return continuousPlay; }
    public void setContinuousPlay(boolean continuousPlay) { this.continuousPlay = continuousPlay; }
    public boolean isAutoSkipWater() { return autoSkipWater; }
    public void setAutoSkipWater(boolean autoSkipWater) { this.autoSkipWater = autoSkipWater; }
    public boolean isAutoDownload() { return autoDownload; }
    public void setAutoDownload(boolean autoDownload) { this.autoDownload = autoDownload; }
    public boolean isAutoCache() { return autoCache; }
    public void setAutoCache(boolean autoCache) { this.autoCache = autoCache; }
    public String getSubtitleSize() { return subtitleSize; }
    public void setSubtitleSize(String subtitleSize) { this.subtitleSize = subtitleSize; }
    public String getSubtitleLanguage() { return subtitleLanguage; }
    public void setSubtitleLanguage(String subtitleLanguage) { this.subtitleLanguage = subtitleLanguage; }
    public String getVoiceLanguage() { return voiceLanguage; }
    public void setVoiceLanguage(String voiceLanguage) { this.voiceLanguage = voiceLanguage; }
    public List<String> getDislikedEpisodes() { return dislikedEpisodes; }
    public void setDislikedEpisodes(List<String> dislikedEpisodes) { this.dislikedEpisodes = dislikedEpisodes; }

    public static class CustomColors {
        private String primary = "#0f3460";
        private String accent = "#e94560";
        private String background = "#1a1a2e";
        private String text = "#eaeaea";
        private String card = "#16213e";
        private String border = "#0f3460";
        private String success = "#4ecca3";
        private String warning = "#f4d03f";

        public String getPrimary() { return primary; } public void setPrimary(String primary) { this.primary = primary; }
        public String getAccent() { return accent; } public void setAccent(String accent) { this.accent = accent; }
        public String getBackground() { return background; } public void setBackground(String background) { this.background = background; }
        public String getText() { return text; } public void setText(String text) { this.text = text; }
        public String getCard() { return card; } public void setCard(String card) { this.card = card; }
        public String getBorder() { return border; } public void setBorder(String border) { this.border = border; }
        public String getSuccess() { return success; } public void setSuccess(String success) { this.success = success; }
        public String getWarning() { return warning; } public void setWarning(String warning) { this.warning = warning; }
    }

    public static class KeywordConfig {
        private List<String> dryKeywords = new ArrayList<>(Arrays.asList("新闻", "资讯", "报道", "访谈", "评论"));
        private List<String> waterKeywords = new ArrayList<>(Arrays.asList("广告", "音乐", "歌曲", "休息"));
        private List<String> contentDryKeywords = new ArrayList<>();
        private List<String> contentWaterKeywords = new ArrayList<>();
        private String dryLogic = "or";
        private String waterLogic = "or";
        private String contentDryLogic = "or";
        private String contentWaterLogic = "or";

        public List<String> getDryKeywords() { return dryKeywords; } public void setDryKeywords(List<String> dryKeywords) { this.dryKeywords = dryKeywords; }
        public List<String> getWaterKeywords() { return waterKeywords; } public void setWaterKeywords(List<String> waterKeywords) { this.waterKeywords = waterKeywords; }
        public List<String> getContentDryKeywords() { return contentDryKeywords; } public void setContentDryKeywords(List<String> contentDryKeywords) { this.contentDryKeywords = contentDryKeywords; }
        public List<String> getContentWaterKeywords() { return contentWaterKeywords; } public void setContentWaterKeywords(List<String> contentWaterKeywords) { this.contentWaterKeywords = contentWaterKeywords; }
        public String getDryLogic() { return dryLogic; } public void setDryLogic(String dryLogic) { this.dryLogic = dryLogic; }
        public String getWaterLogic() { return waterLogic; } public void setWaterLogic(String waterLogic) { this.waterLogic = waterLogic; }
        public String getContentDryLogic() { return contentDryLogic; } public void setContentDryLogic(String contentDryLogic) { this.contentDryLogic = contentDryLogic; }
        public String getContentWaterLogic() { return contentWaterLogic; } public void setContentWaterLogic(String contentWaterLogic) { this.contentWaterLogic = contentWaterLogic; }
    }
}
