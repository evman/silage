package dev.silage.app.playback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

private class Surface(val canvas: Canvas, val wid: Long?)

/** The KDE output under the canvas, to see whether its monitor has HDR on. */
private fun outputUnder(canvas: Canvas): Output? = runCatching {
    val config = XdgPaths.kwinOutputConfig.toFile().takeIf { it.isFile } ?: return null
    val origins = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .map { it.defaultConfiguration.bounds.let { b -> b.x to b.y } }.toSet()
    val p = canvas.locationOnScreen
    KwinOutputs.outputAt(KwinOutputs.parse(config.readText(), origins), p.x + canvas.width / 2, p.y + canvas.height / 2)
}.getOrNull()

private fun openMpv(surface: Surface?, ready: LaunchResult.Ready): OpenedPlayer {
    val output = VideoOutput.choose(surface?.wid, surface?.canvas?.let(::outputUnder), System.getenv("SILAGE_HDR"))
    java.nio.file.Files.createDirectories(PlaybackLog.mpvLogFile.parent)
    val mpv = MpvPlayer(output.mpvOptions() + ("log-file" to PlaybackLog.mpvLogFile.toString()))
    val hud = try { PlayerHud(mpv, ready.meta) } catch (t: Throwable) { mpv.close(); throw t }
    // Subscribed before the controller loads the file, so FileLoaded can't be missed.
    val subtitles = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
        mpv.events.filterIsInstance<PlayerEvent.FileLoaded>().first()
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
fun PlayingScreen(koin: Koin, contentId: String, fromStart: Boolean = false, onDone: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    val surface = remember { CompletableDeferred<Surface>() }
    if (error == null) {
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
        val session = PlaybackSession(
            launch = { id -> launcher.launch(id, fromStart) },
            openPlayer = { ready -> openMpv(shown, ready) },
            progress = { s, pos, paused -> playback.updateProgress(s, pos, paused) },
            stopSession = { s -> playback.stopPlayback(s) },
        )
        // Off the UI thread: creating, running and closing mpv can block.
        when (val outcome = withContext(Dispatchers.IO) { session.play(contentId) }) {
            PlayOutcome.Finished -> onDone()
            is PlayOutcome.Failed -> error = outcome.message
        }
    }
    error?.let { msg ->
        Column(Modifier.padding(96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(msg, style = MaterialTheme.typography.titleLarge)
            Button(onClick = onDone) { Text("Back") }
        }
    }
}
