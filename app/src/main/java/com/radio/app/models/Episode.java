package com.radio.app.models;

import java.io.Serializable;
import java.util.List;

public class Episode implements Serializable {
    private String id;
    private String title;
    private String broadcastAt;
    private long duration;
    private String description;
    private String stationId;
    private String stationName;
    private String audioUrl;
    private boolean isLive;
    private List<VoiceSegment> voiceSegments;
    private List<Transcript> transcripts;
    private boolean isDisliked;
    private boolean isCached;

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBroadcastAt() { return broadcastAt; }
    public void setBroadcastAt(String broadcastAt) { this.broadcastAt = broadcastAt; }
    public long getDuration() { return duration; }
    public void setDuration(long duration) { this.duration = duration; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStationId() { return stationId; }
    public void setStationId(String stationId) { this.stationId = stationId; }
    public String getStationName() { return stationName; }
    public void setStationName(String stationName) { this.stationName = stationName; }
    public String getAudioUrl() { return audioUrl; }
    public void setAudioUrl(String audioUrl) { this.audioUrl = audioUrl; }
    public boolean isLive() { return isLive; }
    public void setLive(boolean live) { isLive = live; }
    public List<VoiceSegment> getVoiceSegments() { return voiceSegments; }
    public void setVoiceSegments(List<VoiceSegment> voiceSegments) { this.voiceSegments = voiceSegments; }
    public List<Transcript> getTranscripts() { return transcripts; }
    public void setTranscripts(List<Transcript> transcripts) { this.transcripts = transcripts; }
    public boolean isDisliked() { return isDisliked; }
    public void setDisliked(boolean disliked) { isDisliked = disliked; }
    public boolean isCached() { return isCached; }
    public void setCached(boolean cached) { isCached = cached; }
}
