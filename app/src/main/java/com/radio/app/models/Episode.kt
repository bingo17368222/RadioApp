package com.radio.app.models

import java.io.Serializable

data class Episode(
    var id: String = "",
    var title: String = "",
    var broadcastAt: String = "",
    var duration: Long = 0,
    var description: String = "",
    var stationId: String = "",
    var stationName: String = "",
    var audioUrl: String = "",
    var isLive: Boolean = false,
    var isDisliked: Boolean = false,
    var isCached: Boolean = false,
    var voiceSegments: List<VoiceSegment> = emptyList(),
    var transcripts: List<Transcript> = emptyList()
) : Serializable
