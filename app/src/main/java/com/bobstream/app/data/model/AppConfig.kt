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
) {
    val displayImage: String
        get() = if (image.isNotBlank()) image else DEFAULT_STREAM_IMAGE

    companion object {
        const val DEFAULT_STREAM_IMAGE = "https://images.unsplash.com/photo-1540747913346-19e32dc3e97e?w=800&auto=format&fit=crop&q=80"
    }
}

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

