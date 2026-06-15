package com.radio.app.models;

public class VoiceSegment {
    private long start;
    private long end;
    private boolean hasVoice;
    private String label;

    public VoiceSegment() {}

    public VoiceSegment(long start, long end, boolean hasVoice, String label) {
        this.start = start;
        this.end = end;
        this.hasVoice = hasVoice;
        this.label = label;
    }

    public long getStart() { return start; }
    public void setStart(long start) { this.start = start; }
    public long getEnd() { return end; }
    public void setEnd(long end) { this.end = end; }
    public boolean isHasVoice() { return hasVoice; }
    public void setHasVoice(boolean hasVoice) { this.hasVoice = hasVoice; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}
