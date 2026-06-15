package com.radio.app.models;

public class Transcript {
    private String episodeId;
    private long segmentStart;
    private long segmentEnd;
    private String text;

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
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
}
