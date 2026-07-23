package com.radio.app.models

import java.io.Serializable

data class Episode(
    var id: String = "",
    var title: String = "",
    var broadcastAt: String = "",
    var duration: Long = 0,
    // v2.4.147: Store the program start/end timestamps so we can display them offline.
    var startTime: Long = 0,
    var endTime: Long = 0,
    var description: String = "",
    var stationId: String = "",
    var stationName: String = "",
    var audioUrl: String = "",
    var isLive: Boolean = false,
    var isDisliked: Boolean = false,
    var isCached: Boolean = false,
    var voiceSegments: List<VoiceSegment> = emptyList(),
    var transcripts: List<Transcript> = emptyList(),
    var programName: String? = null
) : Serializable
