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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.Koin
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.apiv2.WatchDetailV2Api
import org.siloserver.silo.repository.PlaybackRepository
import org.siloserver.silo.repository.ProfileRepository

private fun openMpv(): OpenedPlayer {
    val mpv = MpvPlayer(mapOf(
        "vo" to "gpu-next", "hwdec" to "auto-safe", "target-colorspace-hint" to "yes",
        "fullscreen" to "yes", "force-window" to "yes",
    ))
    // Until Plan 3's overlay: q / Esc in the mpv window end playback.
    mpv.command("keybind", "q", "quit")
    mpv.command("keybind", "ESC", "quit")
    mpv.command("keybind", "SPACE", "cycle pause")
    mpv.command("keybind", "LEFT", "seek -10")
    mpv.command("keybind", "RIGHT", "seek 10")
    return mpv.asOpened()
}

/** Starts a session, plays it in mpv's own window, returns when done. */
@Composable
fun PlayingScreen(koin: Koin, contentId: String, onDone: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
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
        val session = PlaybackSession(
            launch = launcher::launch,
            openPlayer = ::openMpv,
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
