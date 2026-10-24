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
import java.util.concurrent.atomic.AtomicReference
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.apiv2.WatchDetailV2Api
import org.siloserver.silo.repository.PlaybackRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.model.catalog.EpisodeListItem

/** How often a converted stream's sign-in headers are renewed (access tokens last longer). */
private const val MediaAuthRefreshMs = 4 * 60_000L

/** The headers Silo's API requests carry: the access token and the active profile. */
internal suspend fun mediaAuthHeaders(koin: Koin): Map<String, String> {
    val tokens = koin.get<TokenManager>()
    return buildMap {
        tokens.getAccessToken()?.let { put("Authorization", "Bearer $it") }
        tokens.getProfileId()?.let { put("X-Profile-Id", it) }
        tokens.getProfileToken()?.let { put("X-Profile-Token", it) }
    }
}

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
    dvAuthorization: DvHelperAuthorization? = null,
    hasNextEpisode: () -> Boolean = { false }, onNextEpisode: (() -> Unit)? = null,
    subtitleTools: ((MpvPlayer) -> dev.silage.app.playback.hud.SubtitleTools)? = null,
    extraOptions: Map<String, String> = emptyMap(),
    onAudio: (Int, Double, Boolean) -> Unit): OpenedPlayer {
    val hdrMode = System.getenv("SILAGE_HDR") ?: koin.get<dev.silage.app.ui.UiPrefs>().hdrMode.takeIf { it != "auto" }
    val output = VideoOutput.choose(surface?.wid, surface?.canvas?.let(::outputUnder), hdrMode)
    val mpv = MpvPlayer(playbackMpvOptions(output, paused, muted = System.getenv("SILAGE_DEV_MUTE") == "1") +
        koin.get<dev.silage.app.ui.UiPrefs>().subtitleStyle.mpvOptions() + extraOptions)
    // The chosen audio output, if it is still connected; otherwise the Windows default.
    koin.get<dev.silage.app.ui.UiPrefs>().audioDevice?.let { device ->
        if (AudioDevices.parse(mpv.getProperty("audio-device-list")).any { it.name == device }) mpv.setProperty("audio-device", device)
    }
    val dvBackend = System.getenv("SILAGE_EXPERIMENTAL_DV")
    val helperRequested = dvAuthorization != null
    val helper = AtomicReference<ExperimentalDvHelper?>()
    val sourceSwitch = !ready.direct && ready.sourceAudio.isNotEmpty()
    val hud = try { PlayerHud(mpv, ready.meta,
        fullscreenWindow = if (output is VideoOutput.Embedded) fullscreen else null,
        videoSession = video,
        autoSkip = koin.get<dev.silage.app.ui.UiPrefs>().let { dev.silage.app.playback.hud.AutoSkip(it.autoSkipIntro, it.autoSkipRecap, it.autoSkipCredits) },
        infoRows = listOfNotNull(ready.versionLabel?.let { "Version" to it }),
        liveInfoRows = {
            if (helperRequested) listOf("DV helper" to (helper.get()?.description() ?: "Requires native HDR on a known monitor"))
            else emptyList()
        },
        subtitleTools = subtitleTools?.invoke(mpv),
        // On the player's clock, which starts at the plan's timeline offset.
        knownDuration = ready.sourceDurationSeconds?.let { it - ready.timelineOffsetSeconds }?.takeIf { it > 0 },
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
    // A converted stream fetches segments for as long as it plays: renew the
    // sign-in headers before the access token in them expires.
    val authRefresh = if (ready.needsMediaAuth) CoroutineScope(Dispatchers.IO).launch {
        while (true) {
            kotlinx.coroutines.delay(MediaAuthRefreshMs)
            val fresh = runCatching { mediaAuthHeaders(koin) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            mpv.command("change-list", "http-header-fields", "clr", "")
            (ready.headers + fresh).forEach { (k, v) -> mpv.command("change-list", "http-header-fields", "append", "$k: $v") }
        }
    } else null
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
        if (ready.subtitlesOff) mpv.command("set", "sid", "no")
    }
    val canvas = surface?.canvas
    val detach = if (output is VideoOutput.Embedded && canvas != null) attachEmbeddedInput(canvas, hud) else ({})
    // Play/Pause, Next, Previous and Stop on the keyboard, only while this video is open.
    val mediaKeys: AutoCloseable = if (System.getProperty("os.name").startsWith("Windows"))
        WindowsMediaKeys { hud.mediaKey(it) }
    else dev.silage.app.desktop.LinuxMediaSession(mpv.state, hud::mediaKey,
        command = { mpv.command(*it); Unit }, property = mpv::getProperty, title = ready.meta.title)
    val port = mpv.asOpened()
    val experimentalDv: AutoCloseable? = if (helperRequested ||
        (System.getProperty("os.name").startsWith("Linux") && dvBackend == "compositor")) {
        (output as? VideoOutput.OwnWindow)?.screen?.let { screen ->
            if (dvBackend == "compositor") ExperimentalDvSession(screen, mpv.state, mpv::getProperty)
            else dvAuthorization?.let { owner ->
                ExperimentalDvHelper(screen, mpv.state, mpv::getProperty, authorization = owner).also { helper.set(it) }
            }
        }
    } else null
    val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    return object : OpenedPlayer by port {
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            experimentalDv?.close()
            subtitles.cancel()
            authRefresh?.cancel()
            detach()
            mediaKeys.close()
            hud.close()
            port.close()
        }
    }
}

/** Starts a session, plays it inside the Silage window, returns when done. */
@Composable
fun PlayingScreen(koin: Koin, contentId: String, fromStart: Boolean = false, fileId: Int? = null, onDone: () -> Unit,
    shuffleId: String? = null,
    trailer: dev.silage.app.nav.Trailer? = null,
    onPlayNext: (contentId: String, shuffleId: String?) -> Unit = { _, _ -> }) {
    val fullscreen = LocalPlaybackFullscreen.current
    val dvAuthorization = LocalDvHelperAuthorization.current
    val video = remember(contentId) { VideoSession() }
    DisposableEffect(fullscreen, contentId) { onDispose { fullscreen?.leave() } }
    // Browsing artwork isn't on screen while a video plays: give most of its memory back.
    LaunchedEffect(Unit) { runCatching { dev.silage.app.ui.Images.loader.memoryCache?.trimToSize(48L shl 20) } }
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
            preferLastPlayed = { koin.get<dev.silage.app.ui.UiPrefs>().preferredVersion == "last" },
            mediaAuth = { mediaAuthHeaders(koin) },
            languages = { koin.get<dev.silage.app.ui.UiPrefs>().languages },
        )
        // The canvas gets its native window on the next frame; without one in
        // time, mpv falls back to its own fullscreen window.
        val shown = withTimeoutOrNull(3_000) { surface.await() }
        if (trailer != null) {
            // A provider trailer: yt-dlp finds the YouTube streams (usually already, while the title page
            // was open) and mpv plays them; the server is not involved. Should that lookup fail, mpv
            // opens the YouTube page itself through yt-dlp.
            var playedDirect = false
            fun trailerSession(direct: Boolean): PlaybackSession {
                var found: TrailerPrefetch.Streams? = null
                return PlaybackSession(
                    launch = {
                        found = if (direct) TrailerPrefetch.streams(trailer.url).await() else null
                        playedDirect = found != null
                        LaunchResult.Ready(sessionId = "trailer", url = found?.playUrl ?: trailer.url, headers = emptyMap(), startSeconds = null,
                            meta = PlaybackMeta(title = trailer.title, line = "Trailer"))
                    },
                    openPlayer = { ready ->
                        val options = found?.let(TrailerTools::directOptions) ?: TrailerTools.mpvOptions()
                        openMpv(koin, shown, ready, false, fullscreen, video, extraOptions = options) { _, _, _ -> }
                    },
                    progress = { _, _, _ -> },
                    stopSession = { },
                )
            }
            var outcome = withContext(Dispatchers.IO) { trailerSession(direct = true).play(trailer.url) }
            // Streams found earlier that won't play (say, a link YouTube has since refused): let mpv look again.
            if (outcome is PlayOutcome.Failed && playedDirect) outcome = withContext(Dispatchers.IO) { trailerSession(direct = false).play(trailer.url) }
            fullscreen?.leave()
            if (outcome is PlayOutcome.Failed) error = "Couldn't play this trailer. ${outcome.message}" else onDone()
            return@LaunchedEffect
        }
        // A packaged stream can contain only one audio track. Stop that session
        // before requesting another source ordinal using the existing start API.
        // Also a stored subtitle to switch to (found or AI-made): the new session's plan lists it.
        data class AudioChange(val ordinal: Int?, val position: Double, val paused: Boolean, val storedSubtitle: Int? = null)
        // Kept for later restarts (an audio switch) so the chosen subtitle stays on.
        var storedSubtitle: Int? = null
        val profile = withContext(Dispatchers.IO) { runCatching { koin.get<ProfileRepository>().getActiveProfile() }.getOrNull() }
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
            var handedBack = false
            val session = PlaybackSession(
                launch = { id -> launcher.launch(id, fromStart, selectedFile, requested?.ordinal, requested?.position, storedSubtitle) },
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
                    val tools: ((MpvPlayer) -> dev.silage.app.playback.hud.SubtitleTools)? = ready.fileId?.let { mediaFileId -> { mpv: MpvPlayer ->
                        val ordinal = ready.selectedAudioOrdinal ?: 0
                        ServerSubtitleTools(
                            koin.get(), mediaFileId,
                            languages = searchLanguages(profile?.subtitleLanguage, profile?.language),
                            audioOrdinal = ordinal,
                            audioLanguage = ready.sourceAudio.getOrNull(ordinal)?.language,
                            profileLanguage = profile?.subtitleLanguage ?: profile?.language,
                            // Restart the session where it is; its new plan lists the stored subtitle.
                            onUse = { id ->
                                val s = mpv.state.value
                                change.compareAndSet(null, AudioChange(ready.selectedAudioOrdinal, ready.sourcePositionSeconds(s.positionSeconds), s.paused, storedSubtitle = id))
                                mpv.command("stop")
                            },
                        )
                    } }
                    openMpv(koin, shown, ready, requested?.paused ?: false, fullscreen, video, dvAuthorization, subtitleTools = tools,
                        hasNextEpisode = { nextEpisode.get() != null },
                        onNextEpisode = if (shuffle == null && ready.meta.seriesId != null) ({ nextRequested.set(true) }) else null,
                    ) { ordinal, position, paused ->
                        change.compareAndSet(null, AudioChange(ordinal, position, paused))
                    }
                },
                progress = { s, pos, paused -> playback.updateProgress(s, pos, paused) },
                stopSession = { s -> playback.stopPlayback(s) },
                beforeClose = { outcome ->
                    if (outcome == PlayOutcome.Finished && change.get() == null && !nextRequested.get() && shuffle == null) {
                        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                            handedBack = true
                            fullscreen?.leave()
                            onDone()
                            // Render the destination underneath the native HDR window before
                            // destroying it. Server cleanup continues after the handoff.
                            withFrameNanos { }
                        }
                    }
                },
            )
            val outcome = withContext(Dispatchers.IO) { session.play(contentId) }
            if (handedBack) break
            requested = change.getAndSet(null)
            requested?.storedSubtitle?.let { storedSubtitle = it }
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
