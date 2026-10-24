package com.bobstream.app.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import android.content.Context
import android.os.BatteryManager
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    currentSpeed: Float,
    isPipMode: Boolean,
    onPlayPauseToggle: () -> Unit,
    onSeekRelative: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onResizeModeToggle: () -> Unit,
    onOpenAudioTrackSelector: () -> Unit,
    onOpenVideoQualitySelector: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onPipToggle: () -> Unit,
    onRotateScreenToggle: () -> Unit,
    onCastClick: () -> Unit,
    onBrightnessChange: (Float) -> Float,
    onVolumeChange: (Float) -> Float,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isPipMode) return // In PiP mode, hide all overlay controls

    var controlsVisible by remember { mutableStateOf(true) }
    var isTouchLocked by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var currentTimeStr by remember { mutableStateOf("") }
    var batteryPercent by remember { mutableIntStateOf(-1) }

    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            currentTimeStr = sdf.format(Date())
            try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                batteryPercent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            } catch (_: Exception) {}
        }
    }

    var showSpeedDialog by remember { mutableStateOf(false) }
    var brightnessFeedback by remember { mutableStateOf<Float?>(null) }
    var volumeFeedback by remember { mutableStateOf<Float?>(null) }
    var doubleTapSeekSide by remember { mutableStateOf<String?>(null) }

    // Auto-hide controls after 5 seconds if not locked
    LaunchedEffect(controlsVisible, isPlaying, isTouchLocked) {
        if (controlsVisible && isPlaying && !isTouchLocked) {
            delay(5000)
            controlsVisible = false
        }
    }

    // Auto-hide feedback HUDs
    LaunchedEffect(doubleTapSeekSide) {
        if (doubleTapSeekSide != null) {
            delay(650)
            doubleTapSeekSide = null
        }
    }

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

        // Touch & Gesture Interaction Layer (Active only when unlocked)
        if (!isTouchLocked) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Left Zone: Double-tap seek backward (-10s) + Vertical drag brightness
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { controlsVisible = !controlsVisible },
                                onDoubleTap = {
                                    doubleTapSeekSide = "LEFT"
                                    onSeekRelative(-10_000L)
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val delta = -dragAmount.y / 600f
                                val updatedLevel = onBrightnessChange(delta)
                                brightnessFeedback = updatedLevel
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

                // Right Zone: Double-tap seek forward (+10s) + Vertical drag volume
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { controlsVisible = !controlsVisible },
                                onDoubleTap = {
                                    doubleTapSeekSide = "RIGHT"
                                    onSeekRelative(10_000L)
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val delta = -dragAmount.y / 500f
                                val updatedLevel = onVolumeChange(delta)
                                volumeFeedback = updatedLevel
                            }
                        }
                )
            }
        } else {
            // Screen is locked - Tap anywhere to toggle unlock button visibility
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                    }
            )
        }

        // YouTube-style Animated Double-Tap Seek Ripples
        doubleTapSeekSide?.let { side ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 40.dp),
                contentAlignment = if (side == "LEFT") Alignment.CenterStart else Alignment.CenterEnd
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier.size(90.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = if (side == "LEFT") Icons.Default.Replay10 else Icons.Default.Forward10,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(if (side == "LEFT") "-10s" else "+10s", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Touch Lock Guard Floating Pill Button (Always accessible on tap)
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp)
        ) {
            IconButton(
                onClick = { isTouchLocked = !isTouchLocked },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (isTouchLocked) Color(0xFFEF4444).copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.6f))
            ) {
                Icon(
                    imageVector = if (isTouchLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = if (isTouchLocked) "Unlock Controls" else "Lock Screen",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Vertical Gesture Feedback HUDs with smooth enter/exit animations
        AnimatedVisibility(
            visible = brightnessFeedback != null,
            enter = fadeIn() + slideInHorizontally { -it },
            exit = fadeOut() + slideOutHorizontally { -it },
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp)
        ) {
            brightnessFeedback?.let { level ->
                VerticalGestureSlider(
                    icon = Icons.Default.BrightnessMedium,
                    label = "Brightness",
                    progress = level
                )
            }
        }

        AnimatedVisibility(
            visible = volumeFeedback != null,
            enter = fadeIn() + slideInHorizontally { it },
            exit = fadeOut() + slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp)
        ) {
            volumeFeedback?.let { level ->
                VerticalGestureSlider(
                    icon = if (level <= 0.01f) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                    label = "Volume",
                    progress = level
                )
            }
        }

        // Buffering Spinner (always visible if buffering)
        if (isBuffering) {
            CircularProgressIndicator(
                color = NeonCyan,
                strokeWidth = 3.5.dp,
                modifier = Modifier.size(54.dp).align(Alignment.Center)
            )
        }

        // Main HUD Controls (Visible only when unlocked and visible)
        AnimatedVisibility(
            visible = controlsVisible && !isTouchLocked,
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                    )

                    // Live Clock & Battery HUD
                    if (currentTimeStr.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            Text(currentTimeStr, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            if (batteryPercent in 0..100) {
                                Icon(
                                    imageVector = if (batteryPercent < 20) Icons.Default.BatteryAlert else Icons.Default.BatteryChargingFull,
                                    contentDescription = null,
                                    tint = if (batteryPercent < 20) Color(0xFFEF4444) else Color(0xFF00E5FF),
                                    modifier = Modifier.size(13.dp)
                                )
                                Text("${batteryPercent}%", color = Color(0xFFD1D5DB), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    // Cast / Share to External Devices & Players
                    IconButton(onClick = onCastClick) {
                        Icon(Icons.Default.Cast, contentDescription = "Cast / External Player", tint = Color.White)
                    }

                    // Picture-in-Picture
                    IconButton(onClick = onPipToggle) {
                        Icon(Icons.Default.PictureInPictureAlt, contentDescription = "PiP", tint = Color.White)
                    }

                    // Playback Speed Toggle
                    OutlinedButton(
                        onClick = { showSpeedDialog = true },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text("${if (currentSpeed == 1.0f) "1.0" else currentSpeed}x", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

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
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
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
                        // Live stream indicator with "Catch up to Live" button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
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
                                Text("LIVE BROADCAST", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            TextButton(
                                onClick = { onSeekTo(Long.MAX_VALUE) },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF388BFD))
                            ) {
                                Text("SYNC TO LIVE", fontSize = 11.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }
            }
        }
    }

    // Playback Speed Dialog
    if (showSpeedDialog) {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            containerColor = Color(0xFF1E2433),
            title = { Text("Playback Speed", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    speeds.forEach { sp ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSpeedChange(sp)
                                    showSpeedDialog = false
                                }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${sp}x ${if (sp == 1.0f) "(Normal)" else ""}", color = if (currentSpeed == sp) NeonCyan else Color.White, fontSize = 15.sp)
                            if (currentSpeed == sp) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSpeedDialog = false }) {
                    Text("Close", color = Color.Gray)
                }
            }
        )
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
fun VerticalGestureSlider(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    progress: Float,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color(0xDD0D1117),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x4400E5FF)),
        shadowElevation = 10.dp,
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .width(46.dp)
                .height(180.dp)
                .padding(vertical = 12.dp, horizontal = 6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = NeonCyan,
                modifier = Modifier.size(22.dp)
            )

            // Vertical Track & Fluid Fill
            Box(
                modifier = Modifier
                    .weight(1f)
                    .width(10.dp)
                    .padding(vertical = 8.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0x33FFFFFF)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(progress.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(NeonCyan, Color(0xFF0070F3))
                            )
                        )
                )
            }

            Text(
                text = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
