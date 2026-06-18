package com.radio.app.models

import java.io.Serializable

data class Transcript(
    var episodeId: String? = null,
    var segmentStart: Long = 0L,
    var segmentEnd: Long = 0L,
    var text: String? = null,
    var confidence: Double = 0.0
) : Serializable {

    var startTime: Long
        get() = segmentStart
        set(value) { segmentStart = value }

    var endTime: Long
        get() = segmentEnd
        set(value) { segmentEnd = value }
}
