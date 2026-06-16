package com.radio.app.models;

import java.io.Serializable;

public class Transcript implements Serializable {
    private String episodeId;
    private long segmentStart;
    private long segmentEnd;
    private String text;
    private double confidence;

    public Transcript() {}

    public Transcript(String episodeId, long segmentStart, long segmentEnd, String text) {
        this.episodeId = episodeId;
        this.segmentStart = segmentStart;
        this.segmentEnd = segmentEnd;
        this.text = text;
    }

    public String getEpisodeId() { return episodeId; }
    public void setEpisodeId(String episodeId) { this.episodeId = episodeId; }
    public long getSegmentStart() { return segmentStart; }
    public void setSegmentStart(long segmentStart) { this.segmentStart = segmentStart; }
    public long getSegmentEnd() { return segmentEnd; }
    public void setSegmentEnd(long segmentEnd) { this.segmentEnd = segmentEnd; }
    public long getStartTime() { return segmentStart; }
    public void setStartTime(long startTime) { this.segmentStart = startTime; }
    public long getEndTime() { return segmentEnd; }
    public void setEndTime(long endTime) { this.segmentEnd = endTime; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
}
