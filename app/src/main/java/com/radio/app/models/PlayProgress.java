package com.radio.app.models;

public class PlayProgress {
    private String episodeId;
    private long progress;
    private long recordedAt;

    public PlayProgress() {}
    public PlayProgress(String episodeId, long progress, long recordedAt) {
        this.episodeId = episodeId; this.progress = progress; this.recordedAt = recordedAt;
    }
    public String getEpisodeId() { return episodeId; } public void setEpisodeId(String episodeId) { this.episodeId = episodeId; }
    public long getProgress() { return progress; } public void setProgress(long progress) { this.progress = progress; }
    public long getRecordedAt() { return recordedAt; } public void setRecordedAt(long recordedAt) { this.recordedAt = recordedAt; }
}
