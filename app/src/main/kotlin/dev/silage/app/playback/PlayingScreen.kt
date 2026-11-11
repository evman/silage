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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
import org.siloserver.silo.watchtogether.playbackContext

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

internal class Surface(val canvas: Canvas, val wid: Long?)

/** The KDE output under the canvas, to see whether its monitor has HDR on. */
private fun outputUnder(canvas: Canvas): Output? = runCatching {
    val config = XdgPaths.kwinOutputConfig.toFile().takeIf { it.isFile } ?: return null
    val origins = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .map { it.defaultConfiguration.bounds.let { b -> b.x to b.y } }.toSet()
    val p = canvas.locationOnScreen
    KwinOutputs.outputAt(KwinOutputs.parse(config.readText(), origins), p.x + canvas.width / 2, p.y + canvas.height / 2)
}.getOrNull()

internal fun openMpv(koin: Koin, surface: Surface?, ready: LaunchResult.Ready, paused: Boolean = false,
    fullscreen: PlaybackFullscreen? = null, video: VideoSession = VideoSession(),
    dvAuthorization: DvHelperAuthorization? = null,
    hasNextEpisode: () -> Boolean = { false }, onNextEpisode: (() -> Unit)? = null,
    subtitleTools: ((MpvPlayer) -> dev.silage.app.playback.hud.SubtitleTools)? = null,
    extraOptions: Map<String, String> = emptyMap(),
    /** A quality picked in the player: restart the session with it, at this position. */
    onQuality: ((quality: String, position: Double, paused: Boolean) -> Unit)? = null,
    /** The room's title revision, in a Watch Party: the room then drives this player. */
    partyRevision: Long? = null,
    /** A copy to move to when buffering won't stop, with what to carry over; null stays on this one. */
    bufferingCopy: org.siloserver.silo.model.catalog.FileVersion? = null,
    /** Asks before moving to [bufferingCopy], even under Automatically. */
    askBeforeSwitch: Boolean = false,
    /** Just moved to 1080p: the 4K copy to offer going back to. */
    backTo: org.siloserver.silo.model.catalog.FileVersion? = null,
    /** The subtitle to find again in this copy. */
    keepSubtitle: dev.silage.app.playback.hud.CarriedSubtitle? = null,
    onCopy: ((copy: org.siloserver.silo.model.catalog.FileVersion, position: Double, paused: Boolean, subtitle: dev.silage.app.playback.hud.CarriedSubtitle?) -> Unit)? = null,
    onAudio: (Int, Double, Boolean) -> Unit): OpenedPlayer {
    val hdrMode = System.getenv("SILAGE_HDR") ?: koin.get<dev.silage.app.ui.UiPrefs>().hdrMode.takeIf { it != "auto" }
    // In a normal window (issue #7), HDR video opens in a normal window too.
    val output = VideoOutput.choose(surface?.wid, surface?.canvas?.let(::outputUnder), hdrMode, fullscreen = fullscreen?.active ?: true).let { o ->
        if (o is VideoOutput.OwnWindow && !o.fullscreen && fullscreen != null)
            o.copy(maximized = fullscreen.windowMaximized, size = fullscreen.windowSize) else o
    }
    // The volume and mute the last video was left at (keyboard shortcuts set them).
    val (savedVolume, savedMute) = koin.get<dev.silage.app.ui.UiPrefs>().playerVolume
    val remembered = buildMap {
        savedVolume?.let { put("volume", it.toString()) }
        if (savedMute) put("mute", "yes")
    }
    val exclusiveAudio = System.getProperty("os.name").startsWith("Windows") && koin.get<dev.silage.app.ui.UiPrefs>().exclusiveAudio
    val mpv = MpvPlayer(remembered + playbackMpvOptions(output, paused, muted = System.getenv("SILAGE_DEV_MUTE") == "1", exclusiveAudio = exclusiveAudio) +
        koin.get<dev.silage.app.ui.UiPrefs>().subtitleStyle.mpvOptions() + extraOptions)
    // HDR video in its own window while Silage is in a normal one: one window, not two.
    val steppedAside = if (output is VideoOutput.OwnWindow && !output.fullscreen && !System.getProperty("os.name").startsWith("Windows"))
        fullscreen?.stepAside() else null
    // The chosen audio output, if it is still connected; otherwise the Windows default.
    koin.get<dev.silage.app.ui.UiPrefs>().audioDevice?.let { device ->
        if (AudioDevices.parse(mpv.getProperty("audio-device-list")).any { it.name == device }) mpv.setProperty("audio-device", device)
    }
    val compositorDv = dvPath(koin.get<dev.silage.app.ui.UiPrefs>().experimentalDolbyVision, compositor = kwinDvLease) == DvPath.Compositor
    val helperRequested = dvAuthorization != null
    val helper = AtomicReference<ExperimentalDvHelper?>()
    val compositorSession = AtomicReference<ExperimentalDvSession?>()
    // D in the player: Dolby Vision off (HDR10 base layer) to compare.
    val dvComparedOff = java.util.concurrent.atomic.AtomicBoolean(false)
    // A restart for a converted stream's audio would drop this viewer out of the room's timing.
    val sourceSwitch = !ready.direct && ready.sourceAudio.isNotEmpty() && partyRevision == null
    val partyPlayback = partyRevision?.let { dev.silage.app.watchparty.PartyPlayback(koin.get(), mpv, ready, it) }
    val prefs = koin.get<dev.silage.app.ui.UiPrefs>()
    fun restartAt(quality: String, restart: (String, Double, Boolean) -> Unit) {
        restart(quality, ready.sourcePositionSeconds(mpv.state.value.positionSeconds), mpv.state.value.paused)
        mpv.command("stop")
    }
    val bufferingMode = dev.silage.app.playback.hud.BufferingMode.of(prefs.bufferingMode)
    fun switchTo(copy: org.siloserver.silo.model.catalog.FileVersion) {
        val s = mpv.state.value
        val showing = dev.silage.app.playback.hud.Tracks.parse(mpv.getProperty("track-list") ?: "[]").subtitles
        onCopy?.invoke(copy, ready.sourcePositionSeconds(s.positionSeconds), s.paused, dev.silage.app.playback.hud.carriedSubtitle(showing))
        mpv.command("stop")
    }
    val bufferingPlan = bufferingCopy?.takeIf { partyRevision == null && onCopy != null && bufferingMode != dev.silage.app.playback.hud.BufferingMode.Off }?.let { copy ->
        dev.silage.app.playback.hud.BufferingPlan(
            automatic = bufferingMode == dev.silage.app.playback.hud.BufferingMode.Auto && !askBeforeSwitch,
            switch = { switchTo(copy) },
        )
    }
    // A deadlocked mpv can't be closed and keeps the keyboard: after 20 s of a
    // silent player loop, save a note and restart Silage instead of staying frozen.
    val watchdog = PlaybackWatchdog()
    val watching = Thread({
        while (!Thread.currentThread().isInterrupted) {
            try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
            if (watchdog.stuck(System.currentTimeMillis())) restartAfterStuckPlayback()
        }
    }, "silage-watchdog").apply { isDaemon = true; start() }
    val hud = try { PlayerHud(mpv, ready.meta,
        watchdog = watchdog,
        bufferingPlan = bufferingPlan,
        fullscreenWindow = if (output is VideoOutput.Embedded) fullscreen else null,
        videoSession = video,
        // In a party only the room moves playback, so nothing skips by itself.
        autoSkipDefaults = if (partyRevision != null) dev.silage.app.playback.hud.AutoSkip() else koin.get<dev.silage.app.ui.UiPrefs>().let { dev.silage.app.playback.hud.AutoSkip(it.autoSkipIntro, it.autoSkipRecap, it.autoSkipCredits) },
        party = partyPlayback?.transport,
        subtitleLook = koin.get<dev.silage.app.ui.UiPrefs>().subtitleStyle,
        onSubtitleLook = { koin.get<dev.silage.app.ui.UiPrefs>().subtitleStyle = it },
        showMemory = if (partyRevision == null) showMemory(koin, ready.meta.seriesId) else null,
        infoRows = listOfNotNull(ready.versionLabel?.let { "Version" to it }),
        liveInfoRows = {
            if (dvComparedOff.get()) listOf("Dolby Vision" to "Off for comparison (D turns it back on) · HDR10")
            else if (helperRequested) listOf("DV helper" to (helper.get()?.description() ?: "Requires native HDR on a known monitor"))
            else if (compositorDv) listOf("Dolby Vision" to (compositorSession.get()?.description() ?: "Requires fullscreen native HDR"))
            else emptyList()
        },
        subtitleTools = subtitleTools?.invoke(mpv),
        // On the player's clock, which starts at the plan's timeline offset.
        knownDuration = ready.sourceDurationSeconds?.let { it - ready.timelineOffsetSeconds }?.takeIf { it > 0 },
        hasNextEpisode = hasNextEpisode,
        onQuit = { koin.get<dev.silage.app.AppExit>().quit() },
        onToggleDolbyVision = if (compositorDv || helperRequested) ({
            val on = dvComparedOff.get()
            mpv.command(*dvComparison(on).toTypedArray())
            dvComparedOff.set(!on)
            dvComparisonMessage(on)
        }) else null,
        onNextEpisode = if (onNextEpisode != null) ({ onNextEpisode(); mpv.command("stop"); Unit }) else null,
        audioRows = { mounted -> audioMenuTracks(ready.sourceAudio, mounted, ready.direct, ready.selectedAudioOrdinal) },
        qualities = if (onQuality == null) emptyList() else qualityRows(ready.qualities, ready.activeQuality),
        onVolumeChange = { volume, muted -> koin.get<dev.silage.app.ui.UiPrefs>().playerVolume = volume to muted },
        // The original file only: jumping around a converted stream would make the server convert elsewhere.
        thumbnails = if (ready.direct && ready.fileId != null) ({ onFrame -> SeekThumbnails(ready.url, ready.headers, onFrame) }) else null,
        selectQuality = onQuality?.let { restart -> { id: String ->
            if (id != ready.activeQuality) restartAt(id, restart)
        } },
        selectSourceAudio = if (sourceSwitch) ({ ordinal ->
            if (ordinal != ready.selectedAudioOrdinal && ordinal in ready.sourceAudio.indices) {
                onAudio(ordinal, ready.sourcePositionSeconds(mpv.state.value.positionSeconds), mpv.state.value.paused)
                mpv.command("stop")
            }
        }) else null,
    ) } catch (t: Throwable) { watchdog.stop(); watching.interrupt(); partyPlayback?.close(); mpv.close(); steppedAside?.close(); throw t }
    partyPlayback?.start(hud)
    backTo?.let { copy -> hud.offerBackTo4k { switchTo(copy) } }
    // Subscribed before the controller loads the file, so FileLoaded can't be missed.
    // A stream from the server fetches (segments, or byte ranges after a seek)
    // for as long as it plays: renew the sign-in headers before the access
    // token in them expires.
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
        // Dolby Vision output for Profile 7 needs the frames' Dolby Vision metadata, which copy-back decoders drop.
        val dvProfile = mpv.getProperty("current-tracks/video/dolby-vision-profile")?.toIntOrNull()
        if (compositorDv || helperRequested) dvDecoders(dvProfile)?.let { mpv.setProperty("hwdec", it) }
        // Wait on the first frame while the monitor switches to Dolby Vision (a few seconds of
        // black), so the opening isn't missed; play anyway if the switch doesn't happen.
        if (compositorDv && (output as? VideoOutput.OwnWindow)?.let { it.screen != null && it.fullscreen } == true && dvShouldHold(dvProfile, paused)) launch {
            val heldSince = System.currentTimeMillis()
            mpv.setProperty("pause", "yes")
            hud.notice("Switching to Dolby Vision…", DV_HOLD_MAX_MS)
            while (!dvHoldEnds(System.currentTimeMillis(), heldSince, compositorSession.get()?.activeSinceMs,
                    compositorSession.get()?.unavailable == true)) kotlinx.coroutines.delay(100)
            hud.notice(if (compositorSession.get()?.activeSinceMs != null) "Dolby Vision" else "Playing in HDR")
            mpv.setProperty("pause", "no")
        }
        if (ready.direct) ready.selectedAudioOrdinal?.let { ordinal ->
            val mounted = dev.silage.app.playback.hud.Tracks.parse(mpv.getProperty("track-list") ?: "[]").audio
            nativeAudioId(mounted, ordinal)?.let { mpv.command("set", "aid", it.toString()) }
        }
        ready.subtitles.forEach { load ->
            // Stored on the server while the video comes from its proxy: fetch it with the sign-in first.
            val usable = if (!load.fetchWithSignIn) load else {
                val auth = runCatching { mediaAuthHeaders(koin) }.getOrDefault(emptyMap())
                val file = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    SubtitleFetch.download(load.url, auth, dev.silage.app.XdgPaths.cache.resolve("subtitles"))
                }
                file?.let { load.copy(url = it.toString()) }
            }
            if (usable != null) mpv.command(*SubtitleCommands.add(usable))
            else System.err.println("Silage: couldn't download the subtitle \"${load.title}\" from the server")
        }
        ready.embeddedSubtitleStream?.let { stream ->
            SubtitleCommands.sidForStream(mpv.getProperty("track-list") ?: "[]", stream)?.let { mpv.command("set", "sid", it.toString()) }
        }
        if (ready.subtitlesOff) mpv.command("set", "sid", "no")
        dev.silage.app.playback.hud.carriedSubtitleId(keepSubtitle, dev.silage.app.playback.hud.Tracks.parse(mpv.getProperty("track-list") ?: "[]").subtitles)
            ?.let { mpv.command("set", "sid", it) }
    }
    val canvas = surface?.canvas
    val detach = if (output is VideoOutput.Embedded && canvas != null) attachEmbeddedInput(canvas, hud) else ({})
    // The poster for the media controls and Discord, looked up once; current servers keep it in the full details.
    val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val posters = artworkScope.async(start = CoroutineStart.LAZY) {
        playbackPosters(ready.meta) { id ->
            (koin.get<org.siloserver.silo.repository.CatalogRepository>().getItemDetail(id) as? org.siloserver.silo.network.ApiResult.Success)?.data
        }
    }
    // Where a poster found after opening goes: the Windows overlay or the Linux media controls.
    var showPoster: ((String) -> Unit)? = null
    // Play/Pause, Next, Previous and Stop on the keyboard, only while this video is open.
    val mediaKeys: AutoCloseable = if (System.getProperty("os.name").startsWith("Windows")) {
        val keys = WindowsMediaKeys { hud.mediaKey(it) }
        // The Windows media overlay, with what is playing and its buttons.
        val controls = WindowsMediaControls(
            window = canvas?.let(javax.swing.SwingUtilities::getWindowAncestor) ?: java.awt.Window.getWindows().firstOrNull { it.isShowing },
            info = MediaControlsInfo(ready.meta.title, ready.meta.line, ready.meta.artworkUrl),
            state = mpv.state,
            onKey = hud::mediaKey,
            setPaused = { paused -> if (partyPlayback != null) partyPlayback.transport.playPause(paused) else mpv.setPaused(paused) },
            seekTo = { seconds -> if (partyPlayback != null) partyPlayback.transport.seekTo(seconds) else mpv.seekAbsolute(seconds) },
        )
        showPoster = controls::showArtwork
        val setPlaying: (Boolean) -> Unit = { paused -> if (partyPlayback != null) partyPlayback.transport.playPause(paused) else mpv.setPaused(paused) }
        // The taskbar entry: buttons under its thumbnail and the position on its icon.
        val taskbar = WindowsTaskbar.open(
            canvas?.let(javax.swing.SwingUtilities::getWindowAncestor) ?: java.awt.Window.getWindows().firstOrNull { it.isShowing },
            mpv.state, hud::mediaKey, setPlaying,
        )
        AutoCloseable { taskbar.close(); controls.close(); keys.close() }
    } else dev.silage.app.desktop.LinuxMediaSession(mpv.state, hud::mediaKey,
        command = { mpv.command(*it); Unit }, property = mpv::getProperty, title = ready.meta.title)
        .also { session -> showPoster = session::showArtwork }
    showPoster?.let { show -> artworkScope.launch { posters.await().firstOrNull()?.let(show) } }
    // What's playing as a Discord status, only when turned on in Settings.
    val uiPrefs = koin.get<dev.silage.app.ui.UiPrefs>()
    // Trailers leave the status alone: it's for what you sat down to watch.
    val discord = if (uiPrefs.discordPresence && !ready.meta.trailer && dev.silage.app.desktop.DiscordClientId.isNotBlank()) {
        val coverArt = uiPrefs.discordCoverArt
        dev.silage.app.desktop.DiscordPresence(mpv.state, dev.silage.app.desktop.PresenceInfo(
            title = ready.meta.title, line = ready.meta.line, seriesTitle = ready.meta.seriesTitle, inParty = partyPlayback != null),
            artwork = if (coverArt) ({ posters.await().firstNotNullOfOrNull { dev.silage.app.desktop.discordImageUrl(it) } }) else null)
    } else null
    // A controller drives this player until it closes; then it goes back to the pages.
    val playerKeys: (dev.silage.app.playback.hud.HudKey) -> Unit = hud::key
    dev.silage.app.input.GamepadInput.player = playerKeys
    val port = mpv.asOpened()
    val experimentalDv: AutoCloseable? = if (helperRequested || compositorDv) {
        (output as? VideoOutput.OwnWindow)?.screen?.let { screen ->
            if (compositorDv) ExperimentalDvSession(screen, mpv.state, mpv::getProperty).also { compositorSession.set(it) }
            else dvAuthorization?.let { owner ->
                ExperimentalDvHelper(screen, mpv.state, mpv::getProperty, authorization = owner).also { helper.set(it) }
            }
        }
    } else null
    val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    return object : OpenedPlayer by port {
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            watchdog.stop()
            watching.interrupt()
            if (dev.silage.app.input.GamepadInput.player === playerKeys) dev.silage.app.input.GamepadInput.player = null
            partyPlayback?.close()
            experimentalDv?.close()
            subtitles.cancel()
            authRefresh?.cancel()
            detach()
            mediaKeys.close()
            discord?.close()
            artworkScope.cancel()
            hud.close()
            port.close()
            steppedAside?.close()
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
            quality = { koin.get<dev.silage.app.ui.UiPrefs>().quality },
            showPrefs = { series -> showMemory(koin, series)?.current },
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
                        // YouTube refused the lookup as a bot: mpv asking again would be refused too.
                        if (found == null && TrailerPrefetch.blocked()) LaunchResult.Failed(TrailerPrefetch.BlockedMessage)
                        else LaunchResult.Ready(sessionId = "trailer", url = found?.playUrl ?: trailer.url, headers = emptyMap(), startSeconds = null,
                            meta = PlaybackMeta(title = trailer.title, line = "Trailer", trailer = true))
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
            if (outcome is PlayOutcome.Failed && playedDirect && !TrailerPrefetch.blocked()) outcome = withContext(Dispatchers.IO) { trailerSession(direct = false).play(trailer.url) }
            fullscreen?.leave()
            if (outcome is PlayOutcome.Failed) {
                val message = outcome.message
                error = if (message == TrailerPrefetch.BlockedMessage) message else "Couldn't play this trailer. $message"
            } else onDone()
            return@LaunchedEffect
        }
        // A packaged stream can contain only one audio track. Stop that session
        // before requesting another source ordinal using the existing start API.
        // Also a stored subtitle to switch to (found or AI-made): the new session's plan lists it.
        // Also a quality picked in the player's Quality menu: the session restarts with it, where it was.
        // Also a move between a title's 4K and 1080p copies when buffering won't stop.
        data class AudioChange(val ordinal: Int?, val position: Double, val paused: Boolean, val storedSubtitle: Int? = null, val quality: String? = null,
            val copy: org.siloserver.silo.model.catalog.FileVersion? = null, val from: org.siloserver.silo.model.catalog.FileVersion? = null,
            val subtitle: dev.silage.app.playback.hud.CarriedSubtitle? = null)
        // Kept for later restarts (an audio switch) so the chosen subtitle stays on.
        var storedSubtitle: Int? = null
        // Kept for later restarts too, so an audio switch doesn't undo a quality picked in the player.
        var chosenQuality: String? = null
        // The 4K copy while buffering has moved this playback to 1080p.
        var switchedFrom: org.siloserver.silo.model.catalog.FileVersion? = null
        // Back to 4K by hand means ask next time; a 1080p copy that failed isn't tried again.
        var askBeforeSwitch = false
        var switchFailed = false
        var lastPosition = 0.0
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
                launch = { id -> launcher.launch(id, fromStart, selectedFile, requested?.ordinal, requested?.position, storedSubtitle, chosenQuality, fixedFile = switchedFrom != null) },
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
                            extras = koin.get(),
                        )
                    } }
                    openMpv(koin, shown, ready, requested?.paused ?: false, fullscreen, video, dvAuthorization, subtitleTools = tools,
                        // A quality picked in the player is the viewer's own answer to the buffering.
                        bufferingCopy = dev.silage.app.detail.hdCopyOf(ready.versions, ready.fileId).takeIf { chosenQuality == null && !switchFailed },
                        askBeforeSwitch = askBeforeSwitch,
                        backTo = switchedFrom.takeIf { requested?.copy != null },
                        keepSubtitle = requested?.subtitle,
                        onCopy = { copy, position, paused, subtitle ->
                            val audio = dev.silage.app.playback.hud.sameLanguageAudio(ready.sourceAudio, ready.selectedAudioOrdinal, copy.audioTracks.orEmpty())
                            change.compareAndSet(null, AudioChange(audio, position, paused, copy = copy, from = ready.versions.firstOrNull { it.fileId == ready.fileId }, subtitle = subtitle))
                        },
                        onQuality = { quality, position, paused ->
                            change.compareAndSet(null, AudioChange(ready.selectedAudioOrdinal, position, paused, quality = quality))
                        },
                        hasNextEpisode = { nextEpisode.get() != null },
                        onNextEpisode = if (shuffle == null && ready.meta.seriesId != null) ({ nextRequested.set(true) }) else null,
                    ) { ordinal, position, paused ->
                        change.compareAndSet(null, AudioChange(ordinal, position, paused))
                    }
                },
                progress = { s, pos, paused -> lastPosition = pos; playback.updateProgress(s, pos, paused) },
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
            val previous = requested
            requested = change.getAndSet(null)
            requested?.storedSubtitle?.let { storedSubtitle = it }
            requested?.quality?.let { chosenQuality = it }
            requested?.copy?.let { copy ->
                // A stored subtitle belongs to the copy it was made for.
                storedSubtitle = null
                if (switchedFrom == null) {
                    switchedFrom = requested?.from
                } else {
                    switchedFrom = null
                    askBeforeSwitch = true
                }
                selectedFile = copy.fileId
                lastPosition = requested?.position ?: 0.0
            }
            // The 1080p copy wouldn't play: carry on in 4K rather than end the film.
            val from = switchedFrom
            if (outcome is PlayOutcome.Failed && requested == null && from != null) {
                switchedFrom = null
                switchFailed = true
                selectedFile = from.fileId
                requested = AudioChange(null, maxOf(lastPosition, previous?.position ?: 0.0), previous?.paused ?: false, subtitle = previous?.subtitle)
                continue
            }
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

/** The player's Quality menu rows: the plan's options, with the one playing marked. */
internal fun qualityRows(options: List<org.siloserver.silo.model.playback.PlaybackQualityOption>, active: String?): List<dev.silage.app.playback.hud.QualityRow> =
    options.map { dev.silage.app.playback.hud.QualityRow(it.id, it.name, it.bitrateLabel, selected = it.id == active) }

/**
 * The player for a Watch Party's current title: the room's exact file, from the
 * room's position, paused until the room says to play. Leaving it keeps the
 * membership; a new title from the host restarts it.
 */
@Composable
fun PartyPlayingScreen(koin: Koin, roomId: String, onDone: () -> Unit) {
    val repository = remember { koin.get<org.siloserver.silo.repository.WatchTogetherRepository>() }
    val room by repository.roomSnapshot.collectAsState()
    val fullscreen = LocalPlaybackFullscreen.current
    val video = remember(roomId) { VideoSession() }
    DisposableEffect(fullscreen, roomId) { onDispose { fullscreen?.leave() } }
    LaunchedEffect(Unit) { runCatching { dev.silage.app.ui.Images.loader.memoryCache?.trimToSize(48L shl 20) } }
    var error by remember { mutableStateOf<String?>(null) }
    val surface = remember { CompletableDeferred<Surface>() }
    // Only a new title or file restarts the player; positions and members change constantly.
    val context = room?.takeIf { it.roomId == roomId }?.let { Triple(it.selectionRevision, it.selectedContentId, it.selectedFileId) }
    if (error == null) VideoSurface(onReady = { canvas, wid -> surface.complete(Surface(canvas, wid)) })
    // The room left playback (back to its lobby, or over): this screen's job is done.
    LaunchedEffect(room?.phase, room == null) {
        val r = room
        if (r == null || r.roomId != roomId || r.phase != org.siloserver.silo.model.watchtogether.RoomPhase.Playing) { fullscreen?.leave(); onDone() }
    }
    LaunchedEffect(context) {
        val snapshot = room ?: return@LaunchedEffect
        val play = with(snapshot) { playbackContext() } ?: return@LaunchedEffect
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
            mediaAuth = { mediaAuthHeaders(koin) },
            languages = { koin.get<dev.silage.app.ui.UiPrefs>().languages },
            quality = { koin.get<dev.silage.app.ui.UiPrefs>().quality },
        )
        val shown = withTimeoutOrNull(3_000) { surface.await() }
        val session = PlaybackSession(
            launch = { id -> launcher.launch(id, fileId = play.fileId, positionSeconds = play.positionSeconds, fixedFile = true) },
            openPlayer = { ready ->
                // keep-open holds the last frame: the room decides what happens after the end.
                openMpv(koin, shown, ready, play.paused, fullscreen, video, extraOptions = mapOf("keep-open" to "yes"), partyRevision = play.selectionRevision) { _, _, _ -> }
            },
            progress = { s, pos, paused -> playback.updateProgress(s, pos, paused) },
            stopSession = { s -> playback.stopPlayback(s) },
        )
        when (val outcome = withContext(Dispatchers.IO) { session.play(play.contentId) }) {
            is PlayOutcome.Failed -> { fullscreen?.leave(); error = outcome.message }
            else -> { fullscreen?.leave(); onDone() }
        }
    }
    error?.let { PlaybackFailure(it, onDone) }
}
