package com.radio.app.models

data class PlayProgress(
    var episodeId: String? = null,
    var progress: Long = 0L,
    var recordedAt: Long = 0L
)
