package com.radio.app.models;

import android.os.Parcel;
import android.os.Parcelable;

import java.io.Serializable;

public class VoiceSegment implements Parcelable, Serializable {
    private long start;
    private long end;
    private boolean hasVoice;
    private String label;
    private boolean manuallyMarked = false;
    private boolean skipThisTime = false;

    public VoiceSegment() {}

    public VoiceSegment(long start, long end, boolean hasVoice, String label) {
        this.start = start;
        this.end = end;
        this.hasVoice = hasVoice;
        this.label = label;
    }

    protected VoiceSegment(Parcel in) {
        start = in.readLong();
        end = in.readLong();
        hasVoice = in.readByte() != 0;
        label = in.readString();
        manuallyMarked = in.readByte() != 0;
        skipThisTime = in.readByte() != 0;
    }

    public static final Creator<VoiceSegment> CREATOR = new Creator<VoiceSegment>() {
        @Override
        public VoiceSegment createFromParcel(Parcel in) {
            return new VoiceSegment(in);
        }
        @Override
        public VoiceSegment[] newArray(int size) {
            return new VoiceSegment[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(start);
        dest.writeLong(end);
        dest.writeByte((byte) (hasVoice ? 1 : 0));
        dest.writeString(label);
        dest.writeByte((byte) (manuallyMarked ? 1 : 0));
        dest.writeByte((byte) (skipThisTime ? 1 : 0));
    }

    public long getStart() { return start; }
    public void setStart(long start) { this.start = start; }
    public long getEnd() { return end; }
    public void setEnd(long end) { this.end = end; }
    public boolean isHasVoice() { return hasVoice; }
    public void setHasVoice(boolean hasVoice) { this.hasVoice = hasVoice; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public boolean isManuallyMarked() { return manuallyMarked; }
    public void setManuallyMarked(boolean manuallyMarked) { this.manuallyMarked = manuallyMarked; }
    public boolean isSkipThisTime() { return skipThisTime; }
    public void setSkipThisTime(boolean skipThisTime) { this.skipThisTime = skipThisTime; }

    /**
     * Effective dry check: manually marked takes highest priority.
     * If manually marked as dry -> dry. If manually marked as water -> water.
     * Otherwise, fall back to AI-detected hasVoice.
     */
    public boolean isEffectiveDry() {
        if (manuallyMarked) {
            return hasVoice;
        }
        return hasVoice;
    }

    /**
     * Whether this segment should be auto-skipped.
     * Skip if: skipThisTime is set, or if it's water (not dry) content.
     */
    public boolean shouldAutoSkip() {
        if (skipThisTime) return true;
        return !isEffectiveDry();
    }
}
