package com.bobstream.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.bobstream.app.data.model.StreamRequest
import com.bobstream.app.data.repository.ConfigRepository
import com.bobstream.app.player.PlayerActivity
import com.bobstream.app.ui.components.BobStreamLogo
import com.bobstream.app.ui.tabs.ManualStreamTab
import com.bobstream.app.ui.tabs.MatchesTab
import com.bobstream.app.ui.theme.BobStreamTheme
import com.bobstream.app.ui.theme.CardBackground
import com.bobstream.app.ui.theme.DarkBackground
import com.bobstream.app.ui.theme.NeonCyan
import com.bobstream.app.ui.theme.SurfaceDark
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import com.bobstream.app.ui.theme.CardBorder
import android.widget.Toast
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.platform.LocalContext
import com.bobstream.app.util.StreamParser
import kotlinx.coroutines.launch
import com.bobstream.app.util.UpdateInfo
import com.bobstream.app.util.UpdateManager

class MainActivity : ComponentActivity() {

    private lateinit var configRepository: ConfigRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configRepository = ConfigRepository(applicationContext)

        lifecycleScope.launch {
            configRepository.initialize()
        }

        setContent {
            BobStreamTheme {
                MainScreen(
                    configRepository = configRepository,
                    onLaunchStream = { req -> launchPlayer(req) }
                )
            }
        }
    }

    private fun launchPlayer(request: StreamRequest) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_TITLE, request.title)
            putExtra(PlayerActivity.EXTRA_URL, request.mediaUrl)
            putExtra(PlayerActivity.EXTRA_DRM_SCHEME, request.drmScheme.name)
            putExtra(PlayerActivity.EXTRA_DRM_KEY, request.drmKeyOrLicense)

            val headersBundle = Bundle()
            request.headers.forEach { (k, v) -> headersBundle.putString(k, v) }
            putExtra(PlayerActivity.EXTRA_HEADERS, headersBundle)
        }
        startActivity(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    configRepository: ConfigRepository,
    onLaunchStream: (StreamRequest) -> Unit
) {
    val context = LocalContext.current
    val updateManager = remember { UpdateManager(context) }

    var selectedTab by remember { mutableStateOf(0) }
    var showAboutDialog by remember { mutableStateOf(false) }

    // OTA update states
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableFloatStateOf(0f) }

    val config by configRepository.configState.collectAsState()
    val isRefreshing by configRepository.isRefreshing.collectAsState()

    val coroutineScope = rememberCoroutineScope()

    var showSplash by remember { mutableStateOf(true) }
    val splashAlpha = remember { Animatable(1f) }
    val splashScale = remember { Animatable(0.85f) }

    LaunchedEffect(Unit) {
        splashScale.animateTo(
            targetValue = 1.05f,
            animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing)
        )
        kotlinx.coroutines.delay(200)
        splashAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 400, easing = LinearOutSlowInEasing)
        )
        showSplash = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { BobStreamLogo() },
                    actions = {
                        IconButton(onClick = { showAboutDialog = true }) {
                            Icon(Icons.Default.Info, contentDescription = "About", tint = com.bobstream.app.ui.theme.TextSecondary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.White
                    )
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = Color.White,
                    tonalElevation = 6.dp
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.SportsSoccer, contentDescription = "Matches") },
                        label = { Text("Matches", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = NeonCyan,
                            indicatorColor = NeonCyan,
                            unselectedIconColor = com.bobstream.app.ui.theme.TextSecondary,
                            unselectedTextColor = com.bobstream.app.ui.theme.TextSecondary
                        )
                    )

                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.PlayCircleOutline, contentDescription = "Stream") },
                        label = { Text("Stream", fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = NeonCyan,
                            indicatorColor = NeonCyan,
                            unselectedIconColor = com.bobstream.app.ui.theme.TextSecondary,
                            unselectedTextColor = com.bobstream.app.ui.theme.TextSecondary
                        )
                    )
                }
            },
            containerColor = com.bobstream.app.ui.theme.SurfaceLight
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (selectedTab) {
                    0 -> MatchesTab(
                        matches = config.matches,
                        isRefreshing = isRefreshing,
                        onRefresh = { coroutineScope.launch { configRepository.refresh() } },
                        onMatchClick = { match ->
                            val request = StreamParser.parse(match.name, match.link)
                            onLaunchStream(request)
                        }
                    )
                    1 -> ManualStreamTab(
                        onPlayStream = { request -> onLaunchStream(request) }
                    )
                }
            }
        }

        // App Launch Splash Animation Overlay
        if (showSplash) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = splashAlpha.value))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.scale(splashScale.value)
                ) {
                    BobStreamLogo(iconSize = 64.dp, showText = true)
                    Text(
                        text = "Premium Live & DRM Streaming",
                        color = com.bobstream.app.ui.theme.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    // About & Inbuilt OTA Dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(18.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = NeonCyan)
                    Text("About BobStream", color = com.bobstream.app.ui.theme.TextPrimary, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Version: v${UpdateManager.CURRENT_VERSION_NAME} (Build ${UpdateManager.CURRENT_VERSION_CODE})", color = com.bobstream.app.ui.theme.TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Engine: AndroidX Media3 1.4.1 + Sequential P2P Engine", color = com.bobstream.app.ui.theme.TextSecondary, fontSize = 12.sp)
                    Text("Features: HLS/DASH, ClearKey/Widevine DRM, Sequential Magnet Streaming, Inbuilt OTA Updates.", color = com.bobstream.app.ui.theme.TextSecondary, fontSize = 12.sp)

                    Spacer(Modifier.height(6.dp))

                    if (isDownloadingUpdate) {
                        Text("Downloading update: ${(updateProgress * 100).toInt()}%", color = NeonCyan, fontSize = 13.sp)
                        LinearProgressIndicator(
                            progress = { updateProgress },
                            color = NeonCyan,
                            trackColor = CardBorder,
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                        )
                    } else if (updateInfo != null) {
                        if (updateInfo!!.hasUpdate) {
                            Text("New version v${updateInfo!!.latestVersion} is available!", color = Color(0xFF16A34A), fontWeight = FontWeight.Bold)
                            updateInfo!!.releaseNotes?.let {
                                Text(it, color = com.bobstream.app.ui.theme.TextSecondary, fontSize = 12.sp, maxLines = 3)
                            }
                        } else {
                            Text("You are on the latest version.", color = com.bobstream.app.ui.theme.TextSecondary, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                if (updateInfo?.hasUpdate == true && updateInfo?.downloadUrl != null && !isDownloadingUpdate) {
                    Button(
                        onClick = {
                            isDownloadingUpdate = true
                            coroutineScope.launch {
                                val result = updateManager.downloadAndInstallApk(updateInfo!!.downloadUrl!!) { prog ->
                                    updateProgress = prog
                                }
                                isDownloadingUpdate = false
                                result.onSuccess { apkFile ->
                                    updateManager.promptInstall(apkFile)
                                    showAboutDialog = false
                                }.onFailure { err ->
                                    Toast.makeText(context, "Download failed: ${err.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                    ) {
                        Text("Download & Install", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                } else if (!isDownloadingUpdate) {
                    Button(
                        onClick = {
                            isCheckingUpdate = true
                            coroutineScope.launch {
                                updateInfo = updateManager.checkForUpdates()
                                isCheckingUpdate = false
                            }
                        },
                        enabled = !isCheckingUpdate,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Check for Updates", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("Close", color = com.bobstream.app.ui.theme.TextSecondary)
                }
            }
        )
    }
}
