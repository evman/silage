package dev.silage.app.spike

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.silage.app.playback.EmbeddedVideo
import dev.silage.app.playback.VideoOutput
import dev.silage.app.playback.VideoSurface
import dev.silage.player.MpvPlayer
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent

/** `./gradlew :app:runEmbedSpike -Pvideo=FILE`: mpv drawing inside a Compose window. */
fun main(args: Array<String>) {
    val video = requireNotNull(args.firstOrNull()) { "pass -Pvideo=FILE" }
    application {
        Window(onCloseRequest = ::exitApplication, title = "Silage embed spike",
            state = rememberWindowState(placement = WindowPlacement.Fullscreen)) {
            VideoSurface(onReady = { canvas, wid ->
                println("embed: wid=$wid toolkit=${java.awt.Toolkit.getDefaultToolkit().javaClass.name}")
                val mpv = MpvPlayer((wid?.let { VideoOutput.Embedded(it) } ?: VideoOutput.OwnWindow(null)).mpvOptions() + listOfNotNull(args.getOrNull(1)?.let { "log-file" to it }))
                canvas.addKeyListener(object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        if (e.keyCode == KeyEvent.VK_ESCAPE) { mpv.close(); exitApplication() }
                        else EmbeddedVideo.commandFor(e.keyCode)?.let { mpv.command(*it) }
                    }
                })
                canvas.requestFocusInWindow()
                mpv.load(video)
            })
        }
    }
}
