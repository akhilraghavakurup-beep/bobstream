package com.bobstream.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    @SerialName("matches") val matches: List<MatchItem> = emptyList()
)

@Serializable
data class MatchItem(
    @SerialName("name") val name: String,
    @SerialName("image") val image: String = "",
    @SerialName("link") val link: String
)

enum class DrmScheme {
    NONE,
    CLEARKEY,
    WIDEVINE,
    PLAYREADY
}

data class StreamRequest(
    val title: String,
    val mediaUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val drmScheme: DrmScheme = DrmScheme.NONE,
    val drmKeyOrLicense: String? = null
)

