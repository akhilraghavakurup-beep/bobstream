package com.bobstream.app.data.repository

import android.content.Context
import com.bobstream.app.data.model.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class ConfigRepository(private val context: Context) {

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val prefs = context.getSharedPreferences("bobstream_prefs", Context.MODE_PRIVATE)

    companion object {
        const val KEY_CONFIG_URL = "remote_config_url"
        const val DEFAULT_CONFIG_URL = "https://raw.githubusercontent.com/bobstream/config/main/config.json"
        private const val CACHE_FILE_NAME = "config_cache.json"
    }

    private val _configState = MutableStateFlow<AppConfig>(AppConfig())
    val configState: StateFlow<AppConfig> = _configState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun getConfigUrl(): String {
        return prefs.getString(KEY_CONFIG_URL, DEFAULT_CONFIG_URL) ?: DEFAULT_CONFIG_URL
    }

    fun setConfigUrl(url: String) {
        prefs.edit().putString(KEY_CONFIG_URL, url.trim()).apply()
    }

    /**
     * Initializes configuration:
     * 1. Loads from local disk cache if present.
     * 2. Falls back to bundled assets/default_config.json.
     * 3. Triggers asynchronous background fetch with cache-busting timestamp.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        val initialConfig = loadLocalCache() ?: loadBundledAsset()
        _configState.value = initialConfig
        fetchRemoteConfig()
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        fetchRemoteConfig()
    }

    private suspend fun fetchRemoteConfig() = withContext(Dispatchers.IO) {
        _isRefreshing.value = true
        try {
            val baseUrl = getConfigUrl()
            val sep = if (baseUrl.contains("?")) "&" else "?"
            val cacheBustedUrl = "$baseUrl${sep}t=${System.currentTimeMillis()}"

            val request = Request.Builder()
                .url(cacheBustedUrl)
                .header("User-Agent", "BobStream/1.0 (Android)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string()
                    if (!bodyString.isNullOrBlank()) {
                        val parsed = jsonParser.decodeFromString<AppConfig>(bodyString)
                        saveToLocalCache(bodyString)
                        _configState.value = parsed
                    }
                }
            }
        } catch (e: Exception) {
            // Silently fall back to cached data on network error
        } finally {
            _isRefreshing.value = false
        }
    }

    private fun loadLocalCache(): AppConfig? {
        val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
        if (!cacheFile.exists()) return null
        return try {
            val content = cacheFile.readText()
            jsonParser.decodeFromString<AppConfig>(content)
        } catch (e: Exception) {
            null
        }
    }

    private fun saveToLocalCache(rawJson: String) {
        try {
            val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
            cacheFile.writeText(rawJson)
        } catch (_: Exception) {}
    }

    private fun loadBundledAsset(): AppConfig {
        return try {
            context.assets.open("default_config.json").use { stream ->
                InputStreamReader(stream).use { reader ->
                    jsonParser.decodeFromString<AppConfig>(reader.readText())
                }
            }
        } catch (e: Exception) {
            AppConfig()
        }
    }
}
