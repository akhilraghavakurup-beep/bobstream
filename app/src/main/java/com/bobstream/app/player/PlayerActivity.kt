package com.bobstream.app.player

import android.content.Context
import android.content.Intent
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
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.PlaybackParameters
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
    }

    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private lateinit var audioManager: AudioManager
    private var isPip = mutableStateOf(false)

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                exoPlayer?.pause()
            }
        }
    }

    private fun updateSystemBars(isPlaying: Boolean) {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape && isPlaying && !isPip.value) {
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            insetsController.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateSystemBars(exoPlayer?.isPlaying == true)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isPip.value = isInPictureInPictureMode
        updateSystemBars(exoPlayer?.isPlaying == true)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        val prefs = getSharedPreferences("bobstream_prefs", Context.MODE_PRIVATE)
        val defaultLandscape = prefs.getBoolean("default_landscape", true)
        if (defaultLandscape) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Live Stream"
        val mediaUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        val drmSchemeName = intent.getStringExtra(EXTRA_DRM_SCHEME) ?: DrmScheme.NONE.name
        val drmKeyOrLicense = intent.getStringExtra(EXTRA_DRM_KEY)

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
        onBack: () -> Unit
    ) {
        var isPlaying by remember { mutableStateOf(true) }
        var isBuffering by remember { mutableStateOf(true) }
        var resizeMode by remember { mutableStateOf(ResizeMode.FIT) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var showCastDialog by remember { mutableStateOf(false) }
        var currentPositionMs by remember { mutableLongStateOf(0L) }
        var durationMs by remember { mutableLongStateOf(0L) }
        var currentSpeed by remember { mutableFloatStateOf(1.0f) }
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

        val context = LocalContext.current
        val playerInstance = remember(mediaUrl) {
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

            val mediaSourceFactory = DefaultMediaSourceFactory(this@PlayerActivity)
                .setDataSourceFactory(httpDataSourceFactory)

            val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(mediaUrl))
            if (mediaUrl.contains(".mpd", ignoreCase = true)) {
                mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
            } else if (mediaUrl.contains(".m3u8", ignoreCase = true)) {
                mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
            } else if (mediaUrl.endsWith(".mkv", ignoreCase = true)) {
                mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.VIDEO_MATROSKA)
            } else if (mediaUrl.endsWith(".mp4", ignoreCase = true)) {
                mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.VIDEO_MP4)
            }

            when (drmScheme) {
                DrmScheme.CLEARKEY -> {
                    if (!drmKeyOrLicense.isNullOrBlank()) {
                        try {
                            val w3cJsonBytes = StreamParser.buildClearKeyW3cJsonBytes(drmKeyOrLicense)
                            val callback = LocalMediaDrmCallback(w3cJsonBytes)
                            val drmManager = DefaultDrmSessionManager.Builder()
                                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                                .setMultiSession(true)
                                .setPlayClearSamplesWithoutKeys(true)
                                .build(callback)
                            mediaSourceFactory.setDrmSessionManagerProvider { drmManager }

                            // Also attach declarative DrmConfiguration so manifest cenc elements bind seamlessly
                            val drmConfig = MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
                                .setMultiSession(true)
                                .setForceDefaultLicenseUri(false)
                                .build()
                            mediaItemBuilder.setDrmConfiguration(drmConfig)
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
                            .setMultiSession(true)
                            .build(callback)
                        mediaSourceFactory.setDrmSessionManagerProvider { drmManager }

                        val drmConfig = MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                            .setLicenseUri(drmKeyOrLicense)
                            .setMultiSession(true)
                            .setForceDefaultLicenseUri(true)
                            .build()
                        mediaItemBuilder.setDrmConfiguration(drmConfig)
                    }
                }
                else -> {}
            }

            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(1500, 15000, 800, 1200)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val renderersFactory = DefaultRenderersFactory(this@PlayerActivity).apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                setEnableDecoderFallback(true)
            }

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()

            val player = ExoPlayer.Builder(this@PlayerActivity, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
                .build()

            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                    updateSystemBars(playing)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    isBuffering = (playbackState == Player.STATE_BUFFERING)
                }

                override fun onRenderedFirstFrame() {
                    isBuffering = false
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
                            "Playback error: ${error.localizedMessage ?: "Unknown error"}"
                        }
                    }
                    errorMessage = message
                }
            })

            player.setMediaItem(mediaItemBuilder.build())
            player.prepare()
            player.playWhenReady = true
            player
        }

        DisposableEffect(playerInstance) {
            exoPlayer = playerInstance
            onDispose {
                playerInstance.release()
            }
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
                        keepScreenOn = true
                        player = playerInstance
                        playerView = this
                        requestLayout()
                    }
                },
                update = { view ->
                    view.player = playerInstance
                    view.setResizeMode(resizeMode.modeValue)
                    view.requestLayout()
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
                currentSpeed = currentSpeed,
                isPipMode = isPip.value,
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
                onSpeedChange = { speed ->
                    currentSpeed = speed
                    exoPlayer?.playbackParameters = PlaybackParameters(speed)
                },
                onPipToggle = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        enterPictureInPictureMode(PictureInPictureParams.Builder().build())
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
                onCastClick = {
                    showCastDialog = true
                },
                onBrightnessChange = { delta ->
                    currentBrightness = (currentBrightness + delta).coerceIn(0.05f, 1f)
                    val lp = window.attributes
                    lp.screenBrightness = currentBrightness
                    window.attributes = lp
                    currentBrightness
                },
                onVolumeChange = { delta ->
                    currentVolume = (currentVolume + delta).coerceIn(0f, 1f)
                    val newVolumeInt = (currentVolume * maxVolume).toInt()
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolumeInt, 0)
                    currentVolume
                },
                onBack = onBack
            )

            // Cast / Screen Mirroring Dialog (Hardware Decrypted Video to TV)
            if (showCastDialog) {
                AlertDialog(
                    onDismissRequest = { showCastDialog = false },
                    containerColor = Color(0xFF1E2330),
                    title = { Text("Cast to Screen / TV", color = Color.White, fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            "Because live sports streams use DRM encryption (ClearKey) and edge authentication, Smart TVs cannot play raw links directly.\n\nConnect via Wireless Display / Screen Cast to render decrypted video from your phone straight onto your TV.",
                            color = Color.LightGray,
                            fontSize = 13.sp
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showCastDialog = false
                                try {
                                    startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
                                } catch (_: Exception) {
                                    try {
                                        startActivity(Intent(Settings.ACTION_WIFI_DISPLAY_SETTINGS))
                                    } catch (e: Exception) {
                                        errorMessage = "Could not open Cast settings: ${e.message}"
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                        ) {
                            Text("Open Wireless Display", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        Button(
                            onClick = {
                                showCastDialog = false
                                try {
                                    val mime = when {
                                        mediaUrl.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
                                        mediaUrl.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
                                        mediaUrl.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
                                        mediaUrl.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
                                        else -> "video/*"
                                    }
                                    val castIntent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(Uri.parse(mediaUrl), mime)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        putExtra("title", title)
                                        headers.forEach { (k, v) -> putExtra(k, v) }
                                    }
                                    val chooser = Intent.createChooser(castIntent, "Play via External App")
                                    startActivity(chooser)
                                } catch (e: Exception) {
                                    errorMessage = "No external player found: ${e.message}"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                        ) {
                            Text("External App (Non-DRM)", color = Color.White)
                        }
                    }
                )
            }

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
        if (exoPlayer?.isPlaying == false && !isFinishing) {
            exoPlayer?.play()
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (!isPip.value) {
            exoPlayer?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {}
        exoPlayer?.release()
        exoPlayer = null
    }
}
