package com.radio.app.models

import android.os.Parcel
import android.os.Parcelable
import java.io.Serializable

data class VoiceSegment(
    var start: Long = 0L,
    var end: Long = 0L,
    var hasVoice: Boolean = false,
    var label: String? = null,
    var isManuallyMarked: Boolean = false,
    var isSkipThisTime: Boolean = false
) : Parcelable, Serializable {

    constructor(parcel: Parcel) : this(
        start = parcel.readLong(),
        end = parcel.readLong(),
        hasVoice = parcel.readByte() != 0.toByte(),
        label = parcel.readString(),
        isManuallyMarked = parcel.readByte() != 0.toByte(),
        isSkipThisTime = parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(start)
        parcel.writeLong(end)
        parcel.writeByte(if (hasVoice) 1 else 0)
        parcel.writeString(label)
        parcel.writeByte(if (isManuallyMarked) 1 else 0)
        parcel.writeByte(if (isSkipThisTime) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<VoiceSegment> {
        override fun createFromParcel(parcel: Parcel): VoiceSegment = VoiceSegment(parcel)
        override fun newArray(size: Int): Array<VoiceSegment?> = arrayOfNulls(size)
    }

    /**
     * Effective dry check: manually marked takes highest priority.
     * If manually marked as dry -> dry. If manually marked as water -> water.
     * Otherwise, fall back to AI-detected hasVoice.
     */
    fun isEffectiveDry(): Boolean {
        return if (isManuallyMarked) {
            hasVoice
        } else {
            hasVoice
        }
    }

    /**
     * Whether this segment should be auto-skipped.
     * Skip if: skipThisTime is set, or if it's water (not dry) content.
     */
    fun shouldAutoSkip(): Boolean {
        if (isSkipThisTime) return true
        return !isEffectiveDry()
    }
}
