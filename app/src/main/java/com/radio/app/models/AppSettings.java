package com.radio.app.models;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AppSettings {
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

    private String aiModel = AI_MODEL_WENXIN;
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
