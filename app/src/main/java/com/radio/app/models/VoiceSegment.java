package com.radio.app.models;

import android.os.Parcel;
import android.os.Parcelable;

public class VoiceSegment implements Parcelable {
    private long start;
    private long end;
    private boolean hasVoice;
    private String label;
    private boolean manuallyMarked;
    private boolean skipThisTime; // 本次不跳过标记

    public VoiceSegment() {}

    public VoiceSegment(long start, long end, boolean hasVoice, String label) {
        this.start = start;
        this.end = end;
        this.hasVoice = hasVoice;
        this.label = label;
    }

    public VoiceSegment(long start, long end, boolean hasVoice, String label, boolean manuallyMarked) {
        this.start = start;
        this.end = end;
        this.hasVoice = hasVoice;
        this.label = label;
        this.manuallyMarked = manuallyMarked;
    }

    // Parcelable implementation
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

    // Getters and setters
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
     * 判断该片段是否应被视为干货。
     * 手动标记的优先级最高，其次是AI标记的hasVoice。
     */
    public boolean isEffectiveDry() {
        if (manuallyMarked) return hasVoice;
        return hasVoice;
    }

    /**
     * 判断该片段是否应被自动跳过。
     * 如果是水分片段且没有设置"本次不跳过"，则应被跳过。
     */
    public boolean shouldAutoSkip() {
        return !isEffectiveDry() && !skipThisTime;
    }
}
