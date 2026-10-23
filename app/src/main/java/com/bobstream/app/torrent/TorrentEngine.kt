package com.bobstream.app.torrent

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.PieceFinishedAlert
import java.io.File
import java.io.FileInputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

sealed class TorrentState {
    object Idle : TorrentState()
    data class Connecting(val peers: Int = 0, val message: String = "Connecting to DHT & trackers...") : TorrentState()
    data class Buffering(val progress: Float, val speedKbps: Long, val peers: Int) : TorrentState()
    data class Ready(val streamUrl: String, val fileName: String, val totalBytes: Long) : TorrentState()
    data class Error(val reason: String) : TorrentState()
}

/**
 * Sequential Torrent Streaming Engine using native libtorrent4j.
 * Connects to DHT & public trackers, fetches torrent metadata, identifies the target media file,
 * sets sequential download priorities, and streams bytes to ExoPlayer via a local HTTP Range server.
 */
class TorrentEngine(private val context: Context) {

    companion object {
        private const val TAG = "BobStream_Torrent"
        // Target 4MB initial buffer before exposing stream to player
        private const val MIN_STREAM_BUFFER_BYTES = 4L * 1024 * 1024
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var proxyServer: LocalStreamServer? = null
    private var activeJob: Job? = null
    private var sessionManager: SessionManager? = null

    private val _state = MutableStateFlow<TorrentState>(TorrentState.Idle)
    val state: StateFlow<TorrentState> = _state.asStateFlow()

    fun startMagnetStream(magnetUri: String) {
        stop()

        activeJob = scope.launch {
            try {
                val displayName = parseDisplayName(magnetUri)
                _state.value = TorrentState.Connecting(peers = 0, message = "Initializing native BitTorrent engine...")

                val downloadDir = File(context.cacheDir, "torrents").apply { mkdirs() }

                val session = SessionManager(false)
                sessionManager = session

                val sp = SettingsPack()
                sp.setEnableDht(true)
                sp.connectionsLimit(200)
                sp.downloadRateLimit(0) // unlimited
                session.start()

                _state.value = TorrentState.Connecting(peers = 0, message = "Connecting to DHT swarm & trackers...")

                val metadataReceived = AtomicBoolean(false)
                var activeHandle: TorrentHandle? = null

                val alertListener = object : AlertListener {
                    override fun types(): IntArray? {
                        return intArrayOf(
                            AlertType.METADATA_RECEIVED.swig(),
                            AlertType.PIECE_FINISHED.swig()
                        )
                    }

                    override fun alert(alert: Alert<*>) {
                        try {
                            if (alert.type() == AlertType.METADATA_RECEIVED) {
                                val mdAlert = alert as? MetadataReceivedAlert
                                mdAlert?.handle()?.let { th ->
                                    if (th.isValid) {
                                        activeHandle = th
                                        metadataReceived.set(true)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Alert processing error: ${e.message}")
                        }
                    }
                }

                session.addListener(alertListener)

                // Add magnet download
                session.download(magnetUri, downloadDir)

                // Poll for metadata and peers
                var waitSeconds = 0
                while (!metadataReceived.get() && isActive && waitSeconds < 45) {
                    delay(1000)
                    waitSeconds++
                    val currentPeers = try {
                        activeHandle?.status()?.numPeers() ?: 0
                    } catch (_: Exception) { 0 }

                    _state.value = TorrentState.Connecting(
                        peers = currentPeers,
                        message = "Finding peers & downloading metadata ($waitSeconds s)..."
                    )
                }

                val handle = activeHandle ?: run {
                    val torrents = session.swig()?.torrents
                    if (torrents != null && !torrents.isEmpty) {
                        TorrentHandle(torrents[0])
                    } else null
                }

                if (handle == null || !handle.isValid) {
                    throw IllegalStateException("Failed to discover torrent metadata from swarm. Check tracker connectivity.")
                }

                // Enable sequential download on native handle
                try {
                    handle.swig()?.set_sequential_download(true)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set sequential download via SWIG: ${e.message}")
                }

                // Inspect files to locate target video file
                val ti = handle.torrentFile()
                var targetFileIndex = 0
                var targetFileSize = 0L
                var targetFileName = displayName

                if (ti != null && ti.numFiles() > 0) {
                    val fs = ti.files()
                    val numFiles = ti.numFiles()
                    var maxVideoSize = 0L

                    for (i in 0 until numFiles) {
                        val size = fs.fileSize(i)
                        val name = fs.fileName(i)
                        val isVideo = name.endsWith(".mkv", ignoreCase = true) ||
                                      name.endsWith(".mp4", ignoreCase = true) ||
                                      name.endsWith(".avi", ignoreCase = true) ||
                                      name.endsWith(".mov", ignoreCase = true)

                        if (isVideo && size > maxVideoSize) {
                            maxVideoSize = size
                            targetFileIndex = i
                            targetFileSize = size
                            targetFileName = name
                        }
                    }

                    if (maxVideoSize == 0L) {
                        // Fallback to largest file
                        for (i in 0 until numFiles) {
                            val size = fs.fileSize(i)
                            if (size > targetFileSize) {
                                targetFileSize = size
                                targetFileIndex = i
                                targetFileName = fs.fileName(i)
                            }
                        }
                    }

                    // Prioritize target video file, ignore others
                    try {
                        val prios = Priority.array(Priority.IGNORE, numFiles)
                        prios[targetFileIndex] = Priority.SEVEN
                        handle.prioritizeFiles(prios)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error setting file priorities: ${e.message}")
                    }
                }

                // Prioritize initial pieces (first ~10 pieces) with high piece deadlines
                val pieceCount = ti?.numPieces() ?: 100
                val initialPieces = (pieceCount * 0.05f).toInt().coerceIn(4, 20)
                for (p in 0 until initialPieces) {
                    try {
                        handle.setPieceDeadline(p, 1000 + (p * 500))
                    } catch (_: Exception) {}
                }

                // Wait for target video file on disk
                var targetDiskFile: File? = null
                var buffered = false
                var elapsed = 0

                while (isActive && !buffered && elapsed < 120) {
                    delay(1000)
                    elapsed++

                    val st = handle.status()
                    val peers = st?.numPeers() ?: 0
                    val speedBytes = st?.downloadRate() ?: 0L
                    val speedKbps = speedBytes / 1024

                    // Search for downloaded target file in directory
                    if (targetDiskFile == null || !targetDiskFile!!.exists()) {
                        targetDiskFile = findFileRecursive(downloadDir, targetFileName)
                    }

                    val currentFileBytes = targetDiskFile?.length() ?: 0L
                    val requiredBytes = if (targetFileSize > 0) {
                        (targetFileSize * 0.015f).toLong().coerceIn(2L * 1024 * 1024, MIN_STREAM_BUFFER_BYTES)
                    } else MIN_STREAM_BUFFER_BYTES

                    val bufferProgress = (currentFileBytes.toFloat() / requiredBytes.toFloat()).coerceIn(0f, 1f)

                    _state.value = TorrentState.Buffering(
                        progress = bufferProgress,
                        speedKbps = speedKbps,
                        peers = peers
                    )

                    // Once initial buffer is ready on disk (or first few pieces downloaded)
                    if (currentFileBytes >= requiredBytes || (targetDiskFile != null && currentFileBytes > 1024 * 1024 && elapsed > 15)) {
                        buffered = true
                    }
                }

                val finalFile = targetDiskFile ?: findFileRecursive(downloadDir, targetFileName)
                if (finalFile == null || !finalFile.exists()) {
                    throw IllegalStateException("Torrent download buffer could not be established.")
                }

                // Start local HTTP server proxy serving actual video file
                val port = findFreePort()
                proxyServer = LocalStreamServer(port, finalFile, if (targetFileSize > 0) targetFileSize else finalFile.length())
                proxyServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

                val streamUrl = "http://127.0.0.1:$port/${finalFile.name}"
                _state.value = TorrentState.Ready(
                    streamUrl = streamUrl,
                    fileName = targetFileName,
                    totalBytes = if (targetFileSize > 0) targetFileSize else finalFile.length()
                )

                // Background monitoring loop to keep adjusting deadlines as playback continues
                while (isActive) {
                    delay(2000)
                    val st = handle.status()
                    Log.d(TAG, "Torrent streaming: peers=${st?.numPeers()}, speed=${st?.downloadRate()?.div(1024)} KB/s, progress=${st?.progress()}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Torrent stream error", e)
                _state.value = TorrentState.Error(e.message ?: "Failed to stream torrent")
            }
        }
    }

    private fun findFileRecursive(dir: File, targetName: String): File? {
        if (!dir.exists()) return null
        val files = dir.listFiles() ?: return null
        for (f in files) {
            if (f.isDirectory) {
                val found = findFileRecursive(f, targetName)
                if (found != null) return found
            } else {
                if (f.name.equals(targetName, ignoreCase = true) || f.name.contains(targetName, ignoreCase = true)) {
                    return f
                }
                // Match media extensions if single video
                if (f.length() > 500 * 1024 && (f.name.endsWith(".mkv", ignoreCase = true) || f.name.endsWith(".mp4", ignoreCase = true))) {
                    return f
                }
            }
        }
        return null
    }

    fun stop() {
        activeJob?.cancel()
        activeJob = null
        try {
            proxyServer?.stop()
        } catch (_: Exception) {}
        proxyServer = null

        scope.launch {
            try {
                sessionManager?.stop()
            } catch (_: Exception) {}
            sessionManager = null
        }

        _state.value = TorrentState.Idle
    }

    private fun findFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private fun parseDisplayName(magnet: String): String {
        val dnPrefix = "dn="
        val idx = magnet.indexOf(dnPrefix)
        if (idx != -1) {
            val endIdx = magnet.indexOf('&', idx)
            val raw = if (endIdx != -1) magnet.substring(idx + dnPrefix.length, endIdx) else magnet.substring(idx + dnPrefix.length)
            return java.net.URLDecoder.decode(raw.replace("+", " "), "UTF-8")
        }
        return "Torrent Stream"
    }

    private class LocalStreamServer(
        port: Int,
        private val file: File,
        private val virtualTotalLength: Long
    ) : NanoHTTPD("127.0.0.1", port) {

        override fun serve(session: IHTTPSession): Response {
            val isMkv = file.name.endsWith(".mkv", ignoreCase = true) || session.uri.endsWith(".mkv", ignoreCase = true)
            val mimeType = if (isMkv) "video/x-matroska" else "video/mp4"

            val range = session.headers["range"]
            val actualFileLength = if (file.exists()) file.length() else 0L
            val totalBytes = if (virtualTotalLength > actualFileLength) virtualTotalLength else actualFileLength

            if (actualFileLength <= 0L) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Buffer not ready yet")
            }

            return if (range != null && range.startsWith("bytes=")) {
                val parts = range.substring("bytes=".length).split("-")
                val start = parts[0].toLongOrNull() ?: 0L
                val requestedEnd = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toLongOrNull() else null

                // Serve up to what is currently available or requested end
                val end = (requestedEnd ?: (totalBytes - 1)).coerceAtMost(actualFileLength - 1).coerceAtLeast(start)
                val contentLength = (end - start + 1).coerceAtLeast(0L)

                val fis = FileInputStream(file)
                var skipped = 0L
                while (skipped < start) {
                    val s = fis.skip(start - skipped)
                    if (s <= 0) break
                    skipped += s
                }

                val res = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT,
                    mimeType,
                    fis,
                    contentLength
                )
                res.addHeader("Content-Range", "bytes $start-$end/$totalBytes")
                res.addHeader("Accept-Ranges", "bytes")
                res.addHeader("Content-Length", contentLength.toString())
                res.addHeader("Connection", "keep-alive")
                res
            } else {
                val fis = FileInputStream(file)
                val res = newFixedLengthResponse(Response.Status.OK, mimeType, fis, actualFileLength)
                res.addHeader("Accept-Ranges", "bytes")
                res.addHeader("Content-Length", actualFileLength.toString())
                res.addHeader("Connection", "keep-alive")
                res
            }
        }
    }
}
