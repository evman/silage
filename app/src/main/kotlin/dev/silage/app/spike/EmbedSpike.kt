package dev.silage.app.spike

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
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent

/** `./gradlew :app:runEmbedSpike -Pvideo=FILE [-PmpvLog=FILE]`: mpv + HUD inside a Compose window. */
fun main(args: Array<String>) {
    val video = requireNotNull(args.firstOrNull()) { "pass -Pvideo=FILE" }
    application {
        Window(onCloseRequest = ::exitApplication, title = "Silage embed spike",
            state = rememberWindowState(placement = WindowPlacement.Fullscreen)) {
            VideoSurface(onReady = { canvas, wid ->
                val out = System.getenv("SILAGE_HDR")?.let { VideoOutput.choose(wid, null, it) }
                    ?: (wid?.let { VideoOutput.Embedded(it) } ?: VideoOutput.OwnWindow(null))
                println("embed: output=$out")
                val mpv = MpvPlayer(out.mpvOptions() + listOfNotNull(args.getOrNull(1)?.let { "log-file" to it }))
                val hud = PlayerHud(mpv, PlaybackMeta("Test Pattern", "Silage · S1 E1", intro = TimeRange(2.0, 30.0)))
                canvas.addKeyListener(object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) { HudKey.ofAwt(e.keyCode)?.let(hud::key) }
                })
                canvas.requestFocusInWindow()
                mpv.load(video)
                // SPIKE_SHOW=pause|audio|subs|info: drive the HUD for screenshots without synthetic input.
                System.getenv("SPIKE_SHOW")?.let { show ->
                    Thread {
                        Thread.sleep(6_000)
                        hud.key(HudKey.PlayPause)
                        when (show) {
                            "audio" -> hud.open(dev.silage.app.playback.hud.HudMenu.Audio)
                            "subs" -> hud.open(dev.silage.app.playback.hud.HudMenu.Subtitles)
                            "info" -> hud.open(dev.silage.app.playback.hud.HudMenu.Info)
                        }
                    }.apply { isDaemon = true }.start()
                }
            })
        }
    }
}
