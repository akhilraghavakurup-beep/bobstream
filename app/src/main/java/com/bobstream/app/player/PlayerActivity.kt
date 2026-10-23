package com.bobstream.app.player

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.bobstream.app.data.model.DrmScheme
import com.bobstream.app.ui.theme.BobStreamTheme
import com.bobstream.app.ui.theme.NeonCyan
import com.bobstream.app.util.StreamParser

import android.content.pm.ActivityInfo
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import kotlinx.coroutines.delay

@UnstableApi
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_HEADERS = "extra_headers"
        const val EXTRA_DRM_SCHEME = "extra_drm_scheme"
        const val EXTRA_DRM_KEY = "extra_drm_key"
        const val EXTRA_IS_TORRENT = "extra_is_torrent"
    }

    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private lateinit var audioManager: AudioManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Live Stream"
        val mediaUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        val drmSchemeName = intent.getStringExtra(EXTRA_DRM_SCHEME) ?: DrmScheme.NONE.name
        val drmKeyOrLicense = intent.getStringExtra(EXTRA_DRM_KEY)
        val isTorrent = intent.getBooleanExtra(EXTRA_IS_TORRENT, false)

        val headersBundle = intent.getBundleExtra(EXTRA_HEADERS)
        val headers = mutableMapOf<String, String>()
        headersBundle?.keySet()?.forEach { key ->
            headersBundle.getString(key)?.let { headers[key] = it }
        }

        setContent {
            BobStreamTheme {
                PlayerScreen(
                    title = title,
                    mediaUrl = mediaUrl,
                    headers = headers,
                    drmScheme = DrmScheme.valueOf(drmSchemeName),
                    drmKeyOrLicense = drmKeyOrLicense,
                    isTorrent = isTorrent,
                    onBack = { finish() }
                )
            }
        }
    }

    @Composable
    private fun PlayerScreen(
        title: String,
        mediaUrl: String,
        headers: Map<String, String>,
        drmScheme: DrmScheme,
        drmKeyOrLicense: String?,
        isTorrent: Boolean,
        onBack: () -> Unit
    ) {
        var isPlaying by remember { mutableStateOf(true) }
        var isBuffering by remember { mutableStateOf(true) }
        var resizeMode by remember { mutableStateOf(ResizeMode.FIT) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var currentPositionMs by remember { mutableLongStateOf(0L) }
        var durationMs by remember { mutableLongStateOf(0L) }
        var isLandscape by remember { mutableStateOf(false) }

        // Progress polling loop
        LaunchedEffect(isPlaying) {
            while (true) {
                exoPlayer?.let { p ->
                    currentPositionMs = p.currentPosition.coerceAtLeast(0L)
                    durationMs = p.duration.coerceAtLeast(0L)
                }
                delay(500)
            }
        }

        // Brightness & Volume state
        var currentBrightness by remember {
            val initial = window.attributes.screenBrightness
            mutableStateOf(if (initial < 0) 0.5f else initial)
        }
        val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
        var currentVolume by remember {
            mutableStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume.toFloat())
        }

        // Initialize ExoPlayer
        LaunchedEffect(mediaUrl) {
            if (mediaUrl.isBlank()) {
                errorMessage = "Invalid stream URL"
                return@LaunchedEffect
            }

            // 1. Build HTTP Data Source Factory with custom headers
            val userAgent = headers["User-Agent"] ?: "BobStream/1.0 (Android)"
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)

            val customHeaders = headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
            if (customHeaders.isNotEmpty()) {
                httpDataSourceFactory.setDefaultRequestProperties(customHeaders)
            }

            // 2. Configure DRM session manager if needed
            val mediaSourceFactory = DefaultMediaSourceFactory(this@PlayerActivity)
                .setDataSourceFactory(httpDataSourceFactory)

            val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(mediaUrl))

            when (drmScheme) {
                DrmScheme.CLEARKEY -> {
                    if (!drmKeyOrLicense.isNullOrBlank()) {
                        try {
                            val w3cJsonBytes = StreamParser.buildClearKeyW3cJsonBytes(drmKeyOrLicense)
                            val callback = LocalMediaDrmCallback(w3cJsonBytes)
                            val drmManager = DefaultDrmSessionManager.Builder()
                                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                                .build(callback)
                            mediaSourceFactory.setDrmSessionManagerProvider { drmManager }
                        } catch (e: Exception) {
                            errorMessage = "Failed to parse ClearKey parameters: ${e.message}"
                        }
                    }
                }
                DrmScheme.WIDEVINE -> {
                    if (!drmKeyOrLicense.isNullOrBlank()) {
                        val callback = HttpMediaDrmCallback(drmKeyOrLicense, httpDataSourceFactory)
                        val drmManager = DefaultDrmSessionManager.Builder()
                            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                            .build(callback)
                        mediaSourceFactory.setDrmSessionManagerProvider { drmManager }
                    }
                }
                else -> {}
            }

            // Low-latency synchronized buffer tuning to prevent audio preceding video
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    1500,  // minBufferMs (reduced for fast startup)
                    15000, // maxBufferMs
                    800,   // bufferForPlaybackMs (synchronize A/V decode start)
                    1200   // bufferForPlaybackAfterRebufferMs
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val renderersFactory = DefaultRenderersFactory(this@PlayerActivity).apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                setEnableDecoderFallback(true)
            }

            val player = ExoPlayer.Builder(this@PlayerActivity, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .build()

            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    isBuffering = (playbackState == Player.STATE_BUFFERING)
                }

                override fun onPlayerError(error: PlaybackException) {
                    val message = when (val cause = error.cause) {
                        is HttpDataSource.InvalidResponseCodeException -> {
                            when (cause.responseCode) {
                                401, 403 -> "Authentication failed or token expired (HTTP ${cause.responseCode})"
                                404 -> "Stream not found on remote server (HTTP 404)"
                                else -> "Network playback error: HTTP ${cause.responseCode}"
                            }
                        }
                        is androidx.media3.exoplayer.drm.DrmSession.DrmSessionException -> {
                            "DRM License negotiation failed. Check your ClearKey or Widevine license URL."
                        }
                        else -> {
                            if (isTorrent) "Torrent stream buffering error: ${error.localizedMessage ?: "Unknown error"}"
                            else "Playback error: ${error.localizedMessage ?: "Unknown error"}"
                        }
                    }
                    errorMessage = message
                }
            })

            player.setMediaItem(mediaItemBuilder.build())
            player.prepare()
            player.playWhenReady = true
            exoPlayer = player
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // AndroidView embedding ExoPlayer PlayerView
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setResizeMode(ResizeMode.FIT.modeValue)
                        player = exoPlayer
                        playerView = this
                    }
                },
                update = { view ->
                    view.player = exoPlayer
                    view.setResizeMode(resizeMode.modeValue)
                },
                modifier = Modifier.fillMaxSize()
            )

            // Compose Custom Overlay on top of Video
            PlayerOverlay(
                title = title,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                currentResizeMode = resizeMode,
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                onPlayPauseToggle = {
                    exoPlayer?.let {
                        if (it.isPlaying) it.pause() else it.play()
                    }
                },
                onSeekRelative = { deltaMs ->
                    exoPlayer?.let {
                        val current = it.currentPosition
                        val target = (current + deltaMs).coerceAtLeast(0)
                        it.seekTo(target)
                    }
                },
                onSeekTo = { posMs ->
                    exoPlayer?.seekTo(posMs)
                },
                onResizeModeToggle = {
                    val next = when (resizeMode) {
                        ResizeMode.FIT -> ResizeMode.FILL
                        ResizeMode.FILL -> ResizeMode.ZOOM
                        ResizeMode.ZOOM -> ResizeMode.FIT
                    }
                    resizeMode = next
                },
                onOpenAudioTrackSelector = {
                    exoPlayer?.let { player ->
                        TrackSelectionDialogBuilder(
                            this@PlayerActivity,
                            "Audio Track Selection",
                            player,
                            C.TRACK_TYPE_AUDIO
                        ).build().show()
                    }
                },
                onOpenVideoQualitySelector = {
                    exoPlayer?.let { player ->
                        TrackSelectionDialogBuilder(
                            this@PlayerActivity,
                            "Stream Quality Selection",
                            player,
                            C.TRACK_TYPE_VIDEO
                        ).setShowDisableOption(false)
                        .build().show()
                    }
                },
                onRotateScreenToggle = {
                    isLandscape = !isLandscape
                    requestedOrientation = if (isLandscape) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                },
                onBrightnessChange = { delta ->
                    currentBrightness = (currentBrightness + delta).coerceIn(0.05f, 1f)
                    val lp = window.attributes
                    lp.screenBrightness = currentBrightness
                    window.attributes = lp
                },
                onVolumeChange = { delta ->
                    currentVolume = (currentVolume + delta).coerceIn(0f, 1f)
                    val newVolumeInt = (currentVolume * maxVolume).toInt()
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolumeInt, 0)
                },
                onBack = onBack
            )

            // Friendly Error Dialog
            errorMessage?.let { msg ->
                AlertDialog(
                    onDismissRequest = { errorMessage = null },
                    containerColor = Color(0xFF1E2330),
                    title = { Text("Stream Notice", color = Color.White) },
                    text = { Text(msg, color = Color.LightGray) },
                    confirmButton = {
                        Button(
                            onClick = {
                                errorMessage = null
                                exoPlayer?.prepare()
                                exoPlayer?.play()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                        ) {
                            Text("Retry", color = Color.Black)
                        }
                    },
                    dismissButton = {
                        Button(
                            onClick = {
                                errorMessage = null
                                finish()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                        ) {
                            Text("Close", color = Color.White)
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        exoPlayer?.play()
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }
}
