package dev.silage.app.spike

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.silage.app.playback.PlaybackMeta
import dev.silage.app.playback.VideoOutput
import dev.silage.app.playback.VideoSurface
import dev.silage.app.playback.hud.HudKey
import dev.silage.app.playback.hud.PlayerHud
import dev.silage.player.MpvPlayer
import org.siloserver.silo.model.catalog.TimeRange
import java.awt.event.KeyEvent

/** `./gradlew :app:runEmbedSpike -Pvideo=FILE [-PmpvLog=FILE]`: mpv + HUD inside a Compose window. */
fun main(args: Array<String>) {
    val video = requireNotNull(args.firstOrNull()) { "pass -Pvideo=FILE" }
    application {
        Window(onCloseRequest = ::exitApplication, title = "Silage embed spike",
            state = rememberWindowState(placement = WindowPlacement.Fullscreen)) {
            // Same uniform 1080p scaling as Main.kt (SPIKE_SCALE=1 to disable).
            androidx.compose.foundation.layout.BoxWithConstraints(androidx.compose.ui.Modifier.fillMaxSize()) {
            val scale = if (System.getenv("SPIKE_SCALE") == "1") androidx.compose.ui.platform.LocalDensity.current.density else constraints.maxHeight / 1080f
            val windowDensity = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(scale, 1f),
                dev.silage.app.playback.LocalWindowDensity provides windowDensity,
            ) {
            VideoSurface(onReady = { canvas, wid ->
                val out = System.getenv("SILAGE_HDR")?.let { VideoOutput.choose(wid, null, it) }
                    ?: (wid?.let { VideoOutput.Embedded(it) } ?: VideoOutput.OwnWindow(null))
                println("embed: output=$out")
                val mpv = MpvPlayer(out.mpvOptions() + listOfNotNull(args.getOrNull(1)?.let { "log-file" to it }) +
                    (System.getenv("SPIKE_MPV_OPTS") ?: "").split(',').filter { '=' in it }.map { it.substringBefore('=') to it.substringAfter('=') })
                mpv.load(video)
                if (System.getenv("SPIKE_NOHUD") == null) {
                    val hud = PlayerHud(mpv, PlaybackMeta("Test Pattern", "Silage · S1 E1", intro = TimeRange(2.0, 30.0)))
                    if (out is VideoOutput.Embedded) dev.silage.app.playback.hud.attachEmbeddedInput(canvas, hud)
                // SPIKE_SHOW=pause|audio|subs|info: drive the HUD for screenshots without synthetic input.
                System.getenv("SPIKE_SHOW")?.let { show ->
                    Thread {
                        Thread.sleep(6_000)
                        if (show == "keys") {
                            // Real AWT key events through the event queue, as a keyboard would send them.
                            fun press(code: Int) {
                                java.awt.Toolkit.getDefaultToolkit().systemEventQueue.postEvent(
                                    KeyEvent(canvas, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, code, KeyEvent.CHAR_UNDEFINED))
                                Thread.sleep(250)
                            }
                            press(KeyEvent.VK_SPACE)                       // pause (HUD stays up)
                            repeat(3) { press(KeyEvent.VK_RIGHT) }         // play/pause → fwd10 → audio → subtitles
                            press(KeyEvent.VK_ENTER)                       // open Subtitles
                            press(KeyEvent.VK_DOWN)                        // highlight the first track
                            return@Thread
                        }
                        if (show == "hud") { hud.open(dev.silage.app.playback.hud.HudMenu.Info); return@Thread }
                        hud.key(HudKey.PlayPause)
                        when (show) {
                            "audio" -> hud.open(dev.silage.app.playback.hud.HudMenu.Audio)
                            "subs" -> hud.open(dev.silage.app.playback.hud.HudMenu.Subtitles)
                            "info" -> hud.open(dev.silage.app.playback.hud.HudMenu.Info)
                        }
                    }.apply { isDaemon = true }.start()
                }
                }
            })
            }
            }
        }
    }
}
