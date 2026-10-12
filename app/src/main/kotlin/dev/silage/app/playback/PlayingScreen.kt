package dev.silage.app.playback

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.silage.player.MpvPlayer
import org.koin.core.Koin
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.apiv2.WatchDetailV2Api
import org.siloserver.silo.repository.PlaybackRepository
import org.siloserver.silo.repository.ProfileRepository

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
        when (val r = launcher.launch(contentId)) {
            is LaunchResult.Failed -> error = r.message
            is LaunchResult.Ready -> {
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
                try {
                    PlaybackController(
                        mpv.asPort(),
                        progress = { s, pos, paused -> playback.updateProgress(s, pos, paused) },
                        stop = { s -> playback.stopPlayback(s) },
                    ).run(r)
                } finally { mpv.close() }
                onDone()
            }
        }
    }
    error?.let { msg ->
        Column(Modifier.padding(96.dp)) {
            Text(msg, style = MaterialTheme.typography.titleLarge)
            Button(onClick = onDone) { Text("Back") }
        }
    }
}
