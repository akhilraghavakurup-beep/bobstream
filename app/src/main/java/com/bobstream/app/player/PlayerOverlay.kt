package com.bobstream.app.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.ui.AspectRatioFrameLayout
import com.bobstream.app.ui.theme.NeonCyan
import kotlinx.coroutines.delay

enum class ResizeMode(val label: String, val modeValue: Int) {
    FIT("FIT", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("FILL", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    ZOOM("ZOOM", AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
}

@Composable
fun PlayerOverlay(
    title: String,
    isPlaying: Boolean,
    isBuffering: Boolean,
    currentResizeMode: ResizeMode,
    onPlayPauseToggle: () -> Unit,
    onSeekRelative: (Long) -> Unit,
    onResizeModeToggle: () -> Unit,
    onOpenTrackSelector: () -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var brightnessFeedback by remember { mutableStateOf<Float?>(null) }
    var volumeFeedback by remember { mutableStateOf<Float?>(null) }

    // Auto-hide controls after 5 seconds
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(4500)
            controlsVisible = false
        }
    }

    // Auto-hide brightness/volume HUD
    LaunchedEffect(brightnessFeedback) {
        if (brightnessFeedback != null) {
            delay(1200)
            brightnessFeedback = null
        }
    }
    LaunchedEffect(volumeFeedback) {
        if (volumeFeedback != null) {
            delay(1200)
            volumeFeedback = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {

        // Touch & Gesture Interaction Layer
        Row(modifier = Modifier.fillMaxSize()) {
            // Left Zone: Double-tap seek backward + Vertical drag brightness
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { controlsVisible = !controlsVisible },
                            onDoubleTap = {
                                onSeekRelative(-10_000L)
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val delta = -dragAmount.y / 800f
                            onBrightnessChange(delta)
                        }
                    }
            )

            // Center Zone: Tap to toggle controls
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { controlsVisible = !controlsVisible }
                        )
                    }
            )

            // Right Zone: Double-tap seek forward + Vertical drag volume
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { controlsVisible = !controlsVisible },
                            onDoubleTap = {
                                onSeekRelative(10_000L)
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val delta = -dragAmount.y / 600f
                            onVolumeChange(delta)
                        }
                    }
            )
        }

        // Gesture Feedback HUDs
        brightnessFeedback?.let { level ->
            GestureIndicator(
                icon = Icons.Default.BrightnessMedium,
                label = "Brightness",
                progress = level,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 28.dp)
            )
        }

        volumeFeedback?.let { level ->
            GestureIndicator(
                icon = Icons.Default.VolumeUp,
                label = "Volume",
                progress = level,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 28.dp)
            )
        }

        // Buffering Spinner (always visible if buffering)
        if (isBuffering) {
            CircularProgressIndicator(
                color = NeonCyan,
                strokeWidth = 3.5.dp,
                modifier = Modifier.size(54.dp).align(Alignment.Center)
            )
        }

        // HUD Controls
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.75f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.75f)
                            )
                        )
                    )
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }

                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )

                    // Track selector button (Audio & Subtitles)
                    IconButton(onClick = onOpenTrackSelector) {
                        Icon(Icons.Default.Subtitles, contentDescription = "Tracks", tint = Color.White)
                    }

                    // Aspect ratio mode toggle
                    OutlinedButton(
                        onClick = onResizeModeToggle,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan)
                    ) {
                        Text(currentResizeMode.label, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }
                }

                // Center Play/Pause button
                if (!isBuffering) {
                    IconButton(
                        onClick = onPlayPauseToggle,
                        modifier = Modifier
                            .size(68.dp)
                            .align(Alignment.Center)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = NeonCyan,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GestureIndicator(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    progress: Float,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(10.dp)
    ) {
        Icon(icon, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(24.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            color = NeonCyan,
            trackColor = Color.DarkGray,
            modifier = Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
        )
        Text("${(progress * 100).toInt()}%", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
