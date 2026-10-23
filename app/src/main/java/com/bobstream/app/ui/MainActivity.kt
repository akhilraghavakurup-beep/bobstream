package com.bobstream.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
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
import com.bobstream.app.torrent.TorrentEngine
import com.bobstream.app.torrent.TorrentState
import com.bobstream.app.ui.components.BobStreamLogo
import com.bobstream.app.ui.tabs.ManualStreamTab
import com.bobstream.app.ui.tabs.MatchesTab
import com.bobstream.app.ui.tabs.MoviesTab
import com.bobstream.app.ui.theme.BobStreamTheme
import com.bobstream.app.ui.theme.CardBackground
import com.bobstream.app.ui.theme.DarkBackground
import com.bobstream.app.ui.theme.NeonCyan
import com.bobstream.app.ui.theme.SurfaceDark
import com.bobstream.app.util.StreamParser
import kotlinx.coroutines.launch

import android.widget.Toast
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.platform.LocalContext
import com.bobstream.app.util.UpdateInfo
import com.bobstream.app.util.UpdateManager

class MainActivity : ComponentActivity() {

    private lateinit var configRepository: ConfigRepository
    private lateinit var torrentEngine: TorrentEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configRepository = ConfigRepository(applicationContext)
        torrentEngine = TorrentEngine(applicationContext)

        lifecycleScope.launch {
            configRepository.initialize()
        }

        // Listen for torrent state ready to launch player
        lifecycleScope.launch {
            torrentEngine.state.collect { state ->
                if (state is TorrentState.Ready) {
                    launchPlayer(
                        StreamRequest(
                            title = state.fileName,
                            mediaUrl = state.streamUrl,
                            isTorrent = true
                        )
                    )
                }
            }
        }

        setContent {
            BobStreamTheme {
                MainScreen(
                    configRepository = configRepository,
                    torrentEngine = torrentEngine,
                    onLaunchStream = { req -> launchPlayer(req) }
                )
            }
        }
    }

    private fun launchPlayer(request: StreamRequest) {
        if (request.isTorrent && request.mediaUrl.startsWith("magnet:", ignoreCase = true)) {
            torrentEngine.startMagnetStream(request.mediaUrl)
            return
        }

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_TITLE, request.title)
            putExtra(PlayerActivity.EXTRA_URL, request.mediaUrl)
            putExtra(PlayerActivity.EXTRA_DRM_SCHEME, request.drmScheme.name)
            putExtra(PlayerActivity.EXTRA_DRM_KEY, request.drmKeyOrLicense)
            putExtra(PlayerActivity.EXTRA_IS_TORRENT, request.isTorrent)

            val headersBundle = Bundle()
            request.headers.forEach { (k, v) -> headersBundle.putString(k, v) }
            putExtra(PlayerActivity.EXTRA_HEADERS, headersBundle)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        torrentEngine.stop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    configRepository: ConfigRepository,
    torrentEngine: TorrentEngine,
    onLaunchStream: (StreamRequest) -> Unit
) {
    val context = LocalContext.current
    val updateManager = remember { UpdateManager(context) }

    var selectedTab by remember { mutableStateOf(0) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    // OTA update states
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableFloatStateOf(0f) }

    val config by configRepository.configState.collectAsState()
    val isRefreshing by configRepository.isRefreshing.collectAsState()
    val torrentState by torrentEngine.state.collectAsState()

    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { BobStreamLogo() },
                actions = {
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = "About", tint = Color.LightGray)
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.LightGray)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceDark,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.SportsSoccer, contentDescription = "Matches") },
                    label = { Text("Matches", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = NeonCyan,
                        indicatorColor = NeonCyan,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Movie, contentDescription = "Movies") },
                    label = { Text("Movies", fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = NeonCyan,
                        indicatorColor = NeonCyan,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.PlayCircleOutline, contentDescription = "Stream") },
                    label = { Text("Stream", fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = NeonCyan,
                        indicatorColor = NeonCyan,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )
            }
        },
        containerColor = DarkBackground
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
                1 -> MoviesTab(
                    movies = config.movies,
                    torrentState = torrentState,
                    onMovieClick = { movie ->
                        torrentEngine.startMagnetStream(movie.magnet)
                    },
                    onCancelTorrent = { torrentEngine.stop() }
                )
                2 -> ManualStreamTab(
                    onPlayStream = { request -> onLaunchStream(request) }
                )
            }
        }
    }

    // About & Inbuilt OTA Dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            containerColor = CardBackground,
            shape = RoundedCornerShape(18.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = NeonCyan)
                    Text("About BobStream", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Version: v${UpdateManager.CURRENT_VERSION_NAME} (Build ${UpdateManager.CURRENT_VERSION_CODE})", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Engine: AndroidX Media3 1.4.1 + Sequential P2P Engine", color = Color.LightGray, fontSize = 12.sp)
                    Text("Features: HLS/DASH, ClearKey/Widevine DRM, Sequential Magnet Streaming, Inbuilt OTA Updates.", color = Color.Gray, fontSize = 12.sp)

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
                            Text("New version v${updateInfo!!.latestVersion} is available!", color = Color(0xFF3FB950), fontWeight = FontWeight.Bold)
                            updateInfo!!.releaseNotes?.let {
                                Text(it, color = Color.LightGray, fontSize = 12.sp, maxLines = 3)
                            }
                        } else {
                            Text("You are on the latest version.", color = Color.Gray, fontSize = 13.sp)
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
                        Text("Download & Install", color = Color.Black, fontWeight = FontWeight.Bold)
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
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                        } else {
                            Text("Check for Updates", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("Close", color = Color.Gray)
                }
            }
        )
    }

    if (showSettingsDialog) {
        var urlInput by remember { mutableStateOf(configRepository.getConfigUrl()) }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            containerColor = CardBackground,
            shape = RoundedCornerShape(18.dp),
            title = { Text("Config Source URL", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Update the remote GitHub JSON URL for live streams and movies list:",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = NeonCyan
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        configRepository.setConfigUrl(urlInput)
                        coroutineScope.launch { configRepository.refresh() }
                        showSettingsDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                ) {
                    Text("Save & Sync", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }
}
