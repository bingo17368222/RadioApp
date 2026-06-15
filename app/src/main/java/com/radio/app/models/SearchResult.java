package com.radio.app.models;

public class SearchResult {
    public enum Type { EPISODE, TRANSCRIPT }
    private Type type;
    private Episode episode;
    private Transcript transcript;
    private String matchedText;

    public Type getType() { return type; } public void setType(Type type) { this.type = type; }
    public Episode getEpisode() { return episode; } public void setEpisode(Episode episode) { this.episode = episode; }
    public Transcript getTranscript() { return transcript; } public void setTranscript(Transcript transcript) { this.transcript = transcript; }
    public String getMatchedText() { return matchedText; } public void setMatchedText(String matchedText) { this.matchedText = matchedText; }
}
