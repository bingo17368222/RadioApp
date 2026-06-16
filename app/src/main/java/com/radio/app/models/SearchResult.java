package com.radio.app.models;

public class SearchResult {
    public enum Type { EPISODE, TRANSCRIPT }
    private String id;
    private String title;
    private String type;
    private String stationName;
    private String matchedText;
    private Type enumType;
    private Episode episode;
    private Transcript transcript;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getStationName() { return stationName; }
    public void setStationName(String stationName) { this.stationName = stationName; }
    public String getMatchedText() { return matchedText; }
    public void setMatchedText(String matchedText) { this.matchedText = matchedText; }
    public Episode getEpisode() { return episode; }
    public void setEpisode(Episode episode) { this.episode = episode; }
    public Transcript getTranscript() { return transcript; }
    public void setTranscript(Transcript transcript) { this.transcript = transcript; }

    // Enum-based type (legacy compatibility)
    public Type getEnumType() { return enumType; }
    public void setEnumType(Type enumType) { this.enumType = enumType; }

    /** @deprecated Use getType() returning String instead. */
    @Deprecated
    public Type getTypeEnum() { return enumType; }
    /** @deprecated Use setType(String) instead. */
    @Deprecated
    public void setType(Type type) { this.enumType = type; }
}
