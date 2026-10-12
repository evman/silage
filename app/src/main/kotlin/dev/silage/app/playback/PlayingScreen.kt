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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.koin.core.Koin
import java.awt.Canvas
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.apiv2.WatchDetailV2Api
import org.siloserver.silo.repository.PlaybackRepository
import org.siloserver.silo.repository.ProfileRepository

private class Surface(val canvas: Canvas, val wid: Long?)

private fun openMpv(surface: Surface?): OpenedPlayer {
    val wid = surface?.wid
    val mpv = MpvPlayer(EmbeddedVideo.mpvOptions(wid))
    if (wid == null) {
        // Own window: mpv reads the keyboard itself.
        mpv.command("keybind", "q", "stop")
        mpv.command("keybind", "ESC", "stop")
        mpv.command("keybind", "SPACE", "cycle pause")
        mpv.command("keybind", "LEFT", "seek -10")
        mpv.command("keybind", "RIGHT", "seek 10")
        return mpv.asOpened()
    }
    val canvas = surface.canvas
    val keys = object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            EmbeddedVideo.commandFor(e.keyCode)?.let { mpv.command(*it) }
        }
    }
    SwingUtilities.invokeLater { canvas.addKeyListener(keys); canvas.requestFocusInWindow() }
    val port = mpv.asOpened()
    return object : OpenedPlayer by port {
        override fun close() {
            SwingUtilities.invokeLater { canvas.removeKeyListener(keys) }
            port.close()
        }
    }
}

/** Starts a session, plays it inside the Silage window, returns when done. */
@Composable
fun PlayingScreen(koin: Koin, contentId: String, onDone: () -> Unit) {
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
            start = { playback.startPlaybackV3(it) },
            profileId = { koin.get<ProfileRepository>().getActiveProfileId() },
            serverUrl = { koin.get<TokenManager>().getServerUrl() },
        )
        // The canvas gets its native window on the next frame; without one in
        // time, mpv falls back to its own fullscreen window.
        val ready = withTimeoutOrNull(3_000) { surface.await() }
        val session = PlaybackSession(
            launch = launcher::launch,
            openPlayer = { openMpv(ready) },
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
