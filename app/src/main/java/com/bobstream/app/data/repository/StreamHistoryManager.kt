package com.bobstream.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.bobstream.app.data.model.StreamRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class StreamHistoryItem(
    val title: String,
    val mediaUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val drmScheme: String = "NONE",
    val drmKeyOrLicense: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

class StreamHistoryManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("bobstream_history", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun getHistory(): List<StreamHistoryItem> {
        val raw = prefs.getString("items", null) ?: return emptyList()
        return try {
            json.decodeFromString<List<StreamHistoryItem>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addStream(request: StreamRequest) {
        val current = getHistory().toMutableList()
        current.removeAll { it.mediaUrl == request.mediaUrl }
        current.add(
            0,
            StreamHistoryItem(
                title = request.title,
                mediaUrl = request.mediaUrl,
                headers = request.headers,
                drmScheme = request.drmScheme.name,
                drmKeyOrLicense = request.drmKeyOrLicense,
                timestamp = System.currentTimeMillis()
            )
        )
        val trimmed = current.take(25)
        prefs.edit().putString("items", json.encodeToString(trimmed)).apply()
    }

    fun clearHistory() {
        prefs.edit().remove("items").apply()
    }

    fun removeItem(item: StreamHistoryItem) {
        val current = getHistory().toMutableList()
        current.removeAll { it.mediaUrl == item.mediaUrl && it.timestamp == item.timestamp }
        prefs.edit().putString("items", json.encodeToString(current)).apply()
    }
}
