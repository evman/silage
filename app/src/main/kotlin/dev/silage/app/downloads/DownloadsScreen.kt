package dev.silage.app.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.XdgPaths
import dev.silage.app.browse.Pill
import dev.silage.app.playback.LaunchResult
import dev.silage.app.playback.LocalPlaybackFullscreen
import dev.silage.app.playback.PlayOutcome
import dev.silage.app.playback.PlaybackFailure
import dev.silage.app.playback.PlaybackMeta
import dev.silage.app.playback.PlaybackSession
import dev.silage.app.playback.SubtitleLoad
import dev.silage.app.playback.SubtitleModes
import dev.silage.app.playback.Surface
import dev.silage.app.playback.VideoSession
import dev.silage.app.playback.VideoSurface
import dev.silage.app.playback.languageCode
import dev.silage.app.playback.openMpv
import dev.silage.app.ui.Artwork
import dev.silage.app.ui.Silage
import dev.silage.app.ui.UiPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.Koin
import org.siloserver.silo.model.download.DownloadQuality
import org.siloserver.silo.model.download.DownloadStatus
import org.siloserver.silo.model.download.effectiveDefault
import org.siloserver.silo.model.download.labelFor
import org.siloserver.silo.repository.DownloadsRepository

/** "1.4 GB", "730 MB". */
internal fun sizeLabel(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(java.util.Locale.ROOT, bytes / (1L shl 30).toDouble())
    else -> "${bytes / (1L shl 20)} MB"
}

/** A download's state in words. */
internal fun statusLine(row: LocalDownload): String = when {
    row.problem != null -> row.problem
    row.finished -> sizeLabel(row.bytes.coerceAtLeast(row.total))
    row.status == DownloadStatus.Preparing -> "The server is preparing this copy…"
    row.status == DownloadStatus.Failed -> "The server couldn't prepare this copy."
    row.status == DownloadStatus.Cancelled -> "Cancelled on the server."
    row.total > 0 -> "${sizeLabel(row.bytes)} of ${sizeLabel(row.total)}"
    row.bytes > 0 -> sizeLabel(row.bytes)
    else -> "Waiting…"
}

/** Whether this profile can download on this server. */
@Composable
fun rememberDownloadsOffered(koin: Koin): Boolean {
    val repository = remember { koin.get<DownloadsRepository>() }
    val capability by repository.capability.collectAsState()
    LaunchedEffect(Unit) { runCatching { repository.refreshCapability() } }
    return capability?.isUsable == true
}

/** Every download on this PC: playing, removing, and the quality for new ones. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadsScreen(koin: Koin, onPlay: (id: String) -> Unit) {
    val downloads = remember { koin.get<DesktopDownloads>() }
    val repository = remember { koin.get<DownloadsRepository>() }
    val prefs = remember { koin.get<UiPrefs>() }
    val version by prefs.changes.collectAsState()
    val capability by repository.capability.collectAsState()
    val rows by downloads.rows.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { downloads.start(); runCatching { repository.refreshCapability() } }
    val saved = remember(version) { DownloadQuality.fromWire(prefs.downloadQuality) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 64.dp, top = 48.dp, end = 64.dp, bottom = 64.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Downloads", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Text("Saved on this PC to watch with no connection. Download from a title's page.", color = Silage.Text2, fontSize = 19.sp)
        capability?.takeIf { it.isUsable && it.allowedQualities().size > 1 }?.let { c ->
            Spacer(Modifier.height(10.dp))
            Text("Quality for new downloads", color = Silage.Text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val chosen = c.effectiveDefault(saved)
                c.allowedQualities().forEach { q -> Pill(capability.labelFor(q), selected = q == chosen) { prefs.downloadQuality = q.wire } }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (rows.isEmpty()) Text("Nothing downloaded yet.", color = Silage.Text3, fontSize = 20.sp)
        rows.forEach { row ->
            key(row.id) {
                Row(
                    Modifier.widthIn(max = 1100.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.05f)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Artwork(row.sidecar.offlinePosterPath?.let { java.io.File(it).toURI().toString() }, row.sidecar.posterThumbhash, row.sidecar.title,
                        Modifier.width(80.dp).height(120.dp).clip(RoundedCornerShape(8.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.sidecar.title, color = Silage.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        row.sidecar.subtitle?.let { Text(it, color = Silage.Text2, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        Text(statusLine(row), color = if (row.problem != null) Silage.Error else Silage.Text3, fontSize = 16.sp)
                        if (!row.finished && row.total > 0) {
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.14f))) {
                                Box(Modifier.fillMaxWidth((row.bytes.toFloat() / row.total).coerceIn(0f, 1f)).fillMaxHeight().background(Silage.Amber))
                            }
                        }
                    }
                    if (row.finished) Pill("Play") { onPlay(row.id) }
                    if (row.problem != null) Pill("Try again") { downloads.retry(row.id) }
                    Pill(if (row.finished) "Remove" else "Cancel") { scope.launch { downloads.remove(row.id) } }
                }
            }
        }
        if (rows.isNotEmpty()) Text("Stored in ${XdgPaths.data.resolve("downloads")}", color = Silage.Text3, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

/** Plays a finished download from this PC: no server, no session; the position is kept beside the file. */
@Composable
fun OfflinePlayingScreen(koin: Koin, id: String, onDone: () -> Unit) {
    val downloads = remember { koin.get<DesktopDownloads>() }
    val fullscreen = LocalPlaybackFullscreen.current
    val video = remember(id) { VideoSession() }
    DisposableEffect(fullscreen, id) { onDispose { fullscreen?.leave() } }
    var error by remember { mutableStateOf<String?>(null) }
    val surface = remember { CompletableDeferred<Surface>() }
    if (error == null) VideoSurface(onReady = { canvas, wid -> surface.complete(Surface(canvas, wid)) })
    LaunchedEffect(id) {
        val row = downloads.rows.value.firstOrNull { it.id == id && it.finished }
        if (row == null) { error = "This download isn't on this PC any more."; return@LaunchedEffect }
        val shown = withTimeoutOrNull(3_000) { surface.await() }
        val c = row.sidecar
        val languages = dev.silage.app.playback.showLanguages(koin, c.seriesContentId)
        var lastPosition: Double? = null
        val session = PlaybackSession(
            launch = {
                LaunchResult.Ready(
                    sessionId = "offline",
                    url = row.directory.resolve(c.localUri!!).toString(),
                    headers = emptyMap(),
                    startSeconds = downloads.position(id),
                    meta = PlaybackMeta(title = c.title, line = c.subtitle, seriesId = c.seriesContentId, seriesTitle = c.seriesTitle, seasonNumber = c.seasonNumber, episodeNumber = c.episodeNumber),
                    subtitles = offlineSubtitles(c.offlineTracks?.subtitles.orEmpty(), languages),
                    subtitlesOff = languages.subtitleMode == SubtitleModes.Off,
                    fileId = c.record.mediaFileId,
                    versionLabel = "Downloaded",
                )
            },
            openPlayer = { ready -> openMpv(koin, shown, ready, false, fullscreen, video) { _, _, _ -> } },
            progress = { _, position, _ -> lastPosition = position; downloads.savePosition(id, position) },
            stopSession = { },
        )
        val outcome = withContext(Dispatchers.IO) { session.play(c.record.contentId) }
        when (outcome) {
            is PlayOutcome.Failed -> { fullscreen?.leave(); error = "Couldn't play this download. ${outcome.message}" }
            PlayOutcome.Completed -> { downloads.finishedAt(id, lastPosition); fullscreen?.leave(); onDone() }
            else -> { fullscreen?.leave(); onDone() }
        }
        downloads.syncPositionsSoon()
    }
    error?.let { PlaybackFailure(it, onDone) }
}

/** The saved subtitle files as tracks to load, with the wanted language switched on. */
internal fun offlineSubtitles(saved: List<org.siloserver.silo.model.download.OfflineSubtitleFile>, languages: dev.silage.app.playback.LanguagePrefs): List<SubtitleLoad> {
    val want = if (languages.subtitleMode == SubtitleModes.On) languageCode(languages.subtitle) else null
    val pick = want?.let { w -> saved.firstOrNull { languageCode(it.language) == w && !it.forced } }
    return saved.map { s ->
        SubtitleLoad(s.path, listOfNotNull(s.title ?: s.language ?: "Subtitle", "Forced".takeIf { s.forced }).joinToString(" "), s.language, select = s === pick)
    }
}
