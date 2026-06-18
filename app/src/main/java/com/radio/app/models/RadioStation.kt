package com.radio.app.models

import org.json.JSONObject

data class RadioStation(
    var id: String = "",
    var name: String = "",
    var streamUrl: String = "",
    var favicon: String = "",
    var country: String = "",
    var bitrate: Int = 0,
    var lastCheckOk: Boolean = false,
    var currentProgram: String? = null,
    var isLive: Boolean = true
) {
    companion object {
        fun fromJson(json: JSONObject): RadioStation {
            return RadioStation(
                id = json.optString("stationuuid", ""),
                name = json.optString("name", ""),
                streamUrl = json.optString("url_resolved", json.optString("url", "")),
                favicon = json.optString("favicon", ""),
                country = json.optString("country", ""),
                bitrate = json.optInt("bitrate", 0),
                lastCheckOk = json.optInt("lastcheckok", 0) == 1
            )
        }
    }
}
