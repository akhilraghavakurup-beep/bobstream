package com.bobstream.app.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bobstream.app.data.model.DrmScheme
import com.bobstream.app.data.model.StreamRequest
import com.bobstream.app.util.StreamParser
import com.bobstream.app.ui.theme.AccentGreen
import com.bobstream.app.ui.theme.CardBackground
import com.bobstream.app.ui.theme.CardBorder
import com.bobstream.app.ui.theme.NeonCyan
import com.bobstream.app.ui.theme.NeonPurple

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualStreamTab(
    onPlayStream: (StreamRequest) -> Unit,
    modifier: Modifier = Modifier
) {
    var streamUrl by remember { mutableStateOf("") }
    var userAgent by remember { mutableStateOf("") }
    var referer by remember { mutableStateOf("") }
    var origin by remember { mutableStateOf("") }
    var cookie by remember { mutableStateOf("") }

    var drmScheme by remember { mutableStateOf(DrmScheme.NONE) }
    var drmDropdownExpanded by remember { mutableStateOf(false) }
    var drmKeyOrLicense by remember { mutableStateOf("") }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
            .padding(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Stream",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = com.bobstream.app.ui.theme.TextPrimary
        )
        Text(
            text = "Paste any manual stream URL (HLS .m3u8, DASH .mpd, HTTP/HTTPS). Supports pipe syntax (|) or explicit headers & DRM.",
            fontSize = 13.sp,
            color = com.bobstream.app.ui.theme.TextSecondary
        )

        // Stream URL input
        StreamInputField(
            value = streamUrl,
            onValueChange = { streamUrl = it },
            label = "Stream URL (HLS / DASH / MP4) *",
            placeholder = "https://.../manifest.mpd or master.m3u8"
        )

        // Headers section
        Text("Network Headers (Optional)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = NeonCyan)
        StreamInputField(
            value = userAgent,
            onValueChange = { userAgent = it },
            label = "User-Agent",
            placeholder = "Mozilla/5.0 ... or custom UA"
        )
        StreamInputField(
            value = referer,
            onValueChange = { referer = it },
            label = "Referer",
            placeholder = "https://example.com/"
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StreamInputField(
                value = origin,
                onValueChange = { origin = it },
                label = "Origin",
                placeholder = "https://...",
                modifier = Modifier.weight(1f)
            )
            StreamInputField(
                value = cookie,
                onValueChange = { cookie = it },
                label = "Cookie",
                placeholder = "name=val; ...",
                modifier = Modifier.weight(1f)
            )
        }

        // DRM section
        Text("DRM Configuration (Optional)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = NeonPurple)

        ExposedDropdownMenuBox(
            expanded = drmDropdownExpanded,
            onExpandedChange = { drmDropdownExpanded = !drmDropdownExpanded }
        ) {
            OutlinedTextField(
                value = drmScheme.name,
                onValueChange = {},
                readOnly = true,
                label = { Text("DRM Scheme", color = com.bobstream.app.ui.theme.TextSecondary) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = NeonCyan) },
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().menuAnchor()
            )

            ExposedDropdownMenu(
                expanded = drmDropdownExpanded,
                onDismissRequest = { drmDropdownExpanded = false },
                modifier = Modifier.background(Color.White)
            ) {
                DrmScheme.values().forEach { scheme ->
                    DropdownMenuItem(
                        text = { Text(scheme.name, color = com.bobstream.app.ui.theme.TextPrimary) },
                        onClick = {
                            drmScheme = scheme
                            drmDropdownExpanded = false
                        }
                    )
                }
            }
        }

        if (drmScheme != DrmScheme.NONE) {
            StreamInputField(
                value = drmKeyOrLicense,
                onValueChange = { drmKeyOrLicense = it },
                label = if (drmScheme == DrmScheme.CLEARKEY) "ClearKey Hex Pair (kidHex:keyHex)" else "DRM License URL (http://...)",
                placeholder = if (drmScheme == DrmScheme.CLEARKEY) "eb676a...:100b6c..." else "https://proxy.widevine.com/proxy"
            )
        }

        Spacer(Modifier.height(8.dp))

        var isLaunching by remember { mutableStateOf(false) }

        // Play Button
        Button(
            onClick = {
                if (streamUrl.isNotBlank() && !isLaunching) {
                    isLaunching = true
                    val trimmed = streamUrl.trim()
                    val request = if (trimmed.contains("|")) {
                        StreamParser.parse(title = "Custom Stream", rawLink = trimmed)
                    } else {
                        val headersMap = mutableMapOf<String, String>()
                        if (userAgent.isNotBlank()) headersMap["User-Agent"] = userAgent.trim()
                        if (referer.isNotBlank()) headersMap["Referer"] = referer.trim()
                        if (origin.isNotBlank()) headersMap["Origin"] = origin.trim()
                        if (cookie.isNotBlank()) headersMap["Cookie"] = cookie.trim()

                        StreamRequest(
                            title = "Custom Stream",
                            mediaUrl = trimmed,
                            headers = headersMap,
                            drmScheme = drmScheme,
                            drmKeyOrLicense = drmKeyOrLicense.trim().ifEmpty { null }
                        )
                    }
                    onPlayStream(request)
                    isLaunching = false
                }
            },
            enabled = !isLaunching && streamUrl.isNotBlank(),
            shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                if (isLaunching) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("LAUNCHING STREAM...", color = Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp)
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("PLAY STREAM", color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
                }
            }
        }
    }

    @Composable
    fun StreamInputField(
        value: String,
        onValueChange: (String) -> Unit,
        label: String,
        placeholder: String,
        modifier: Modifier = Modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, color = com.bobstream.app.ui.theme.TextSecondary, fontSize = 13.sp) },
            placeholder = { Text(placeholder, color = com.bobstream.app.ui.theme.TextTertiary, fontSize = 13.sp) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors(),
            modifier = modifier.fillMaxWidth()
        )
    }

    @Composable
    private fun fieldColors() = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = NeonCyan,
        unfocusedBorderColor = CardBorder,
        focusedContainerColor = com.bobstream.app.ui.theme.SurfaceLight,
        unfocusedContainerColor = com.bobstream.app.ui.theme.SurfaceLight,
        focusedTextColor = com.bobstream.app.ui.theme.TextPrimary,
        unfocusedTextColor = com.bobstream.app.ui.theme.TextPrimary
    )
