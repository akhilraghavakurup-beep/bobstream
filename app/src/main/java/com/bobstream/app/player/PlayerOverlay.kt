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
    currentPositionMs: Long,
    durationMs: Long,
    onPlayPauseToggle: () -> Unit,
    onSeekRelative: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onResizeModeToggle: () -> Unit,
    onOpenAudioTrackSelector: () -> Unit,
    onOpenVideoQualitySelector: () -> Unit,
    onRotateScreenToggle: () -> Unit,
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
            delay(5000)
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
                                Color.Black.copy(alpha = 0.85f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f)
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
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )

                    // Video Quality Selector
                    IconButton(onClick = onOpenVideoQualitySelector) {
                        Icon(Icons.Default.HighQuality, contentDescription = "Stream Quality", tint = NeonCyan)
                    }

                    // Audio Track Selector
                    IconButton(onClick = onOpenAudioTrackSelector) {
                        Icon(Icons.Default.Audiotrack, contentDescription = "Audio Track", tint = Color.White)
                    }

                    // Full Screen / Orientation Toggle
                    IconButton(onClick = onRotateScreenToggle) {
                        Icon(Icons.Default.ScreenRotation, contentDescription = "Rotate Screen", tint = Color.White)
                    }

                    // Aspect ratio mode toggle (Fit / Fill / Zoom)
                    OutlinedButton(
                        onClick = onResizeModeToggle,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan)
                    ) {
                        Text(currentResizeMode.label, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }
                }

                // Center Navigation Controls: 10s Rewind, Play/Pause, 10s Forward
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(36.dp)
                ) {
                    // Rewind 10s
                    IconButton(
                        onClick = { onSeekRelative(-10_000L) },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                    ) {
                        Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", tint = Color.White, modifier = Modifier.size(32.dp))
                    }

                    // Play / Pause Toggle
                    if (!isBuffering) {
                        IconButton(
                            onClick = onPlayPauseToggle,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.65f))
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = NeonCyan,
                                modifier = Modifier.size(42.dp)
                            )
                        }
                    }

                    // Forward 10s
                    IconButton(
                        onClick = { onSeekRelative(10_000L) },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                    ) {
                        Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }

                // Bottom Timeline & Progress Scrubber
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (durationMs > 0L) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(formatTimeMs(currentPositionMs), color = Color.White, fontSize = 12.sp)
                            Text(formatTimeMs(durationMs), color = Color.Gray, fontSize = 12.sp)
                        }
                        Slider(
                            value = (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f),
                            onValueChange = { frac ->
                                onSeekTo((frac * durationMs).toLong())
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = NeonCyan,
                                activeTrackColor = NeonCyan,
                                inactiveTrackColor = Color.DarkGray
                            ),
                            modifier = Modifier.fillMaxWidth().height(24.dp)
                        )
                    } else {
                        // Live stream indicator
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color.Red)
                            )
                            Text("LIVE BROADCAST", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimeMs(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000).coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
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
