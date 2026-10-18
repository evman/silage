package dev.silage.app.playback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.silage.player.MpvPlayer
import dev.silage.player.PlayerEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.koin.core.Koin
import dev.silage.app.XdgPaths
import dev.silage.app.playback.hud.attachEmbeddedInput
import dev.silage.app.playback.hud.PlayerHud
import java.awt.Canvas
import java.awt.GraphicsEnvironment
import java.io.File
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.apiv2.WatchDetailV2Api
import org.siloserver.silo.repository.PlaybackRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.model.catalog.EpisodeListItem

private class Surface(val canvas: Canvas, val wid: Long?)

/** The KDE output under the canvas, to see whether its monitor has HDR on. */
private fun outputUnder(canvas: Canvas): Output? = runCatching {
    val config = XdgPaths.kwinOutputConfig.toFile().takeIf { it.isFile } ?: return null
    val origins = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .map { it.defaultConfiguration.bounds.let { b -> b.x to b.y } }.toSet()
    val p = canvas.locationOnScreen
    KwinOutputs.outputAt(KwinOutputs.parse(config.readText(), origins), p.x + canvas.width / 2, p.y + canvas.height / 2)
}.getOrNull()

private fun openMpv(koin: Koin, surface: Surface?, ready: LaunchResult.Ready, paused: Boolean = false,
    fullscreen: PlaybackFullscreen? = null, video: VideoSession = VideoSession(),
    hasNextEpisode: () -> Boolean = { false }, onNextEpisode: (() -> Unit)? = null,
    onAudio: (Int, Double, Boolean) -> Unit): OpenedPlayer {
    val hdrMode = System.getenv("SILAGE_HDR") ?: koin.get<dev.silage.app.ui.UiPrefs>().hdrMode.takeIf { it != "auto" }
    val output = VideoOutput.choose(surface?.wid, surface?.canvas?.let(::outputUnder), hdrMode)
    val mpv = MpvPlayer(playbackMpvOptions(output, paused, muted = System.getenv("SILAGE_DEV_MUTE") == "1"))
    val sourceSwitch = !ready.direct && ready.sourceAudio.isNotEmpty()
    val hud = try { PlayerHud(mpv, ready.meta,
        fullscreenWindow = if (output is VideoOutput.Embedded) fullscreen else null,
        videoSession = video,
        autoSkipIntro = koin.get<dev.silage.app.ui.UiPrefs>().autoSkipIntro,
        hasNextEpisode = hasNextEpisode,
        onNextEpisode = if (onNextEpisode != null) ({ onNextEpisode(); mpv.command("stop"); Unit }) else null,
        audioRows = { mounted -> audioMenuTracks(ready.sourceAudio, mounted, ready.direct, ready.selectedAudioOrdinal) },
        selectSourceAudio = if (sourceSwitch) ({ ordinal ->
            if (ordinal != ready.selectedAudioOrdinal && ordinal in ready.sourceAudio.indices) {
                onAudio(ordinal, ready.sourcePositionSeconds(mpv.state.value.positionSeconds), mpv.state.value.paused)
                mpv.command("stop")
            }
        }) else null,
    ) } catch (t: Throwable) { mpv.close(); throw t }
    // Subscribed before the controller loads the file, so FileLoaded can't be missed.
    val subtitles = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
        mpv.events.filterIsInstance<PlayerEvent.FileLoaded>().first()
        video.apply(mpv::setProperty, mpv::getProperty)
        if (ready.direct) ready.selectedAudioOrdinal?.let { ordinal ->
            val mounted = dev.silage.app.playback.hud.Tracks.parse(mpv.getProperty("track-list") ?: "[]").audio
            nativeAudioId(mounted, ordinal)?.let { mpv.command("set", "aid", it.toString()) }
        }
        ready.subtitles.forEach { mpv.command(*SubtitleCommands.add(it)) }
        ready.embeddedSubtitleStream?.let { stream ->
            SubtitleCommands.sidForStream(mpv.getProperty("track-list") ?: "[]", stream)?.let { mpv.command("set", "sid", it.toString()) }
        }
    }
    val canvas = surface?.canvas
    val detach = if (output is VideoOutput.Embedded && canvas != null) attachEmbeddedInput(canvas, hud) else ({})
    val port = mpv.asOpened()
    return object : OpenedPlayer by port {
        override fun close() {
            subtitles.cancel()
            detach()
            hud.close()
            port.close()
        }
    }
}

/** Starts a session, plays it inside the Silage window, returns when done. */
@Composable
fun PlayingScreen(koin: Koin, contentId: String, fromStart: Boolean = false, fileId: Int? = null, onDone: () -> Unit,
    shuffleId: String? = null,
    onPlayNext: (contentId: String, shuffleId: String?) -> Unit = { _, _ -> }) {
    val fullscreen = LocalPlaybackFullscreen.current
    val video = remember(contentId) { VideoSession() }
    DisposableEffect(fullscreen, contentId) { onDispose { fullscreen?.leave() } }
    var error by remember { mutableStateOf<String?>(null) }
    // Shown after an episode plays to its end, when another follows.
    var upNext by remember { mutableStateOf<Pair<EpisodeListItem, String?>?>(null) }
    // A running shuffle: the server picks each next item; its post-roll replaces "Up next".
    val shuffle = remember(shuffleId) { shuffleId?.let { org.siloserver.silo.playback.ShufflePlayback(koin.get(), it) } }
    var shufflePick by remember { mutableStateOf<org.siloserver.silo.playback.ShuffleNextPick?>(null) }
    val surface = remember { CompletableDeferred<Surface>() }
    if (error == null && upNext == null && shufflePick == null) {
        VideoSurface(onReady = { canvas, wid -> surface.complete(Surface(canvas, wid)) })
    }
    LaunchedEffect(contentId) {
        val details = koin.get<WatchDetailV2Api>()
        val playback = koin.get<PlaybackRepository>()
        val launcher = PlaybackLauncher(
            detail = { id ->
                val owner = details.capture() ?: return@PlaybackLauncher ApiResult.Error(401, "no_scope", "Not signed in")
                details.detail(id, owner)
            },
            start = { req -> playback.startPlaybackV3(req).also { if (it is ApiResult.Success) PlaybackLog.record(it.data) } },
            profileId = { koin.get<ProfileRepository>().getActiveProfileId() },
            serverUrl = { koin.get<TokenManager>().getServerUrl() },
        )
        // The canvas gets its native window on the next frame; without one in
        // time, mpv falls back to its own fullscreen window.
        val shown = withTimeoutOrNull(3_000) { surface.await() }
        // A packaged stream can contain only one audio track. Stop that session
        // before requesting another source ordinal using the existing start API.
        data class AudioChange(val ordinal: Int, val position: Double, val paused: Boolean)
        val change = java.util.concurrent.atomic.AtomicReference<AudioChange?>(null)
        var requested: AudioChange? = null
        var selectedFile = fileId
        // The next episode is looked up while this one plays, for the credits'
        // "Next Episode" button and the "Up next" screen afterwards.
        val nextEpisode = java.util.concurrent.atomic.AtomicReference<EpisodeListItem?>(null)
        val nextRequested = java.util.concurrent.atomic.AtomicBoolean(false)
        var meta: PlaybackMeta? = null
        var lookup: kotlinx.coroutines.Job? = null
        val catalog = koin.get<CatalogRepository>()
        // Read the shuffle while this item plays, so its post-roll knows the next pick.
        shuffle?.let { s -> launch { s.refresh() } }
        while (true) {
            val session = PlaybackSession(
                launch = { id -> launcher.launch(id, fromStart, selectedFile, requested?.ordinal, requested?.position) },
                openPlayer = { ready ->
                    selectedFile = ready.fileId
                    meta = ready.meta
                    if (lookup == null && shuffle == null && ready.meta.seriesId != null) {
                        lookup = launch(Dispatchers.IO) {
                            nextEpisode.set(runCatching {
                                findNextEpisode(ready.meta) { series, season -> (catalog.getEpisodes(series, season) as? ApiResult.Success)?.data?.episodes }
                            }.getOrNull())
                        }
                    }
                    openMpv(koin, shown, ready, requested?.paused ?: false, fullscreen, video,
                        hasNextEpisode = { nextEpisode.get() != null },
                        onNextEpisode = if (shuffle == null && ready.meta.seriesId != null) ({ nextRequested.set(true) }) else null,
                    ) { ordinal, position, paused ->
                        change.compareAndSet(null, AudioChange(ordinal, position, paused))
                    }
                },
                progress = { s, pos, paused -> playback.updateProgress(s, pos, paused) },
                stopSession = { s -> playback.stopPlayback(s) },
            )
            val outcome = withContext(Dispatchers.IO) { session.play(contentId) }
            requested = change.getAndSet(null)
            if (outcome is PlayOutcome.Failed) { fullscreen?.leave(); error = outcome.message; break }
            if (requested == null && shuffle != null) {
                if (outcome is PlayOutcome.Completed) {
                    if (shuffle.latest == null) shuffle.refresh()
                    val pick = shuffle.nextPickAfter(contentId)
                    if (pick != null) { shufflePick = pick; break }
                }
                // Stopped early, or nothing left to pick: end the shuffle.
                shuffle.stopInBackground(); fullscreen?.leave(); onDone(); break
            }
            if (requested == null) {
                val wantsNext = nextRequested.get() || outcome is PlayOutcome.Completed
                lookup?.let { if (wantsNext) it.join() }
                val next = nextEpisode.get()
                when {
                    next != null && nextRequested.get() -> onPlayNext(next.contentId, null)
                    next != null && outcome is PlayOutcome.Completed -> upNext = next to meta?.seriesTitle
                    else -> { fullscreen?.leave(); onDone() }
                }
                break
            }
        }
    }
    error?.let { msg -> PlaybackFailure(msg, onDone) }
    upNext?.let { (next, seriesTitle) ->
        val autoplay = remember { koin.get<dev.silage.app.ui.UiPrefs>().autoplayNext }
        UpNextScreen(next, seriesTitle, autoplay, onPlay = { onPlayNext(next.contentId, null) }, onBack = { fullscreen?.leave(); onDone() })
    }
    val running = shuffle
    if (running != null) shufflePick?.let { pick ->
        val autoplay = remember { koin.get<dev.silage.app.ui.UiPrefs>().autoplayNext }
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        ShuffleUpNextScreen(
            pick, running.scopeLabel, autoplay,
            onPlay = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        // Advancing tells the server this item finished; it answers with what plays now.
                        val now = (running.advance(contentId) as? ApiResult.Success)?.data?.current?.contentId ?: pick.contentId
                        onPlayNext(now, shuffleId)
                    }
                }
            },
            onPickAnother = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        running.pickAnother()
                        running.nextPickAfter(contentId)?.let { shufflePick = it }
                        busy = false
                    }
                }
            },
            onStop = { running.stopInBackground(); fullscreen?.leave(); onDone() },
        )
    }
}

/** Separate rendering seam so a server/player failure's visibility is testable. */
@Composable
internal fun PlaybackFailure(message: String, onDone: () -> Unit) {
    val back = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { back.requestFocus() } }
    Column(Modifier.padding(96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(message, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleLarge)
        Button(onClick = onDone, modifier = Modifier.focusRequester(back)) { Text("Back") }
    }
}
