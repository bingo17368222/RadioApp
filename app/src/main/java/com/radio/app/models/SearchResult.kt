package com.radio.app.models

data class SearchResult(
    var id: String? = null,
    var title: String? = null,
    var type: String? = null,
    var stationName: String? = null,
    var matchedText: String? = null,
    var enumType: Type? = null,
    var episode: Episode? = null,
    var transcript: Transcript? = null
) {

    enum class Type {
        EPISODE, TRANSCRIPT
    }

    // Enum-based type (legacy compatibility)
    @Deprecated("Use getType() returning String instead.")
    fun getTypeEnum(): Type? = enumType

    @Deprecated("Use setType(String) instead.")
    fun setType(type: Type?) {
        this.enumType = type
    }
}
