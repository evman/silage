package dev.silage.app.playback

import com.sun.jna.Native
import dev.silage.app.playback.hud.*
import dev.silage.player.MpvPlayer
import java.awt.BorderLayout
import java.awt.Canvas
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.*

/** Opt-in integration: real libmpv + the same HUD actions used by the application. */
class WindowsAudioSmokeTest {
    @Test fun hudSwitchesActualMountedAudioTracks() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val clip = System.getenv("SILAGE_AUDIO_SMOKE_CLIP") ?: return
        lateinit var frame: JFrame
        lateinit var canvas: Canvas
        SwingUtilities.invokeAndWait {
            canvas = Canvas()
            frame = JFrame("Silage audio selection verification").apply {
                layout = BorderLayout(); add(canvas); setSize(800, 500); isVisible = true
            }
        }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 15_000_000_000L
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(50)
            assertTrue(condition(), message)
        }
        try {
            MpvPlayer(VideoOutput.Embedded(Native.getComponentID(canvas)).mpvOptions() + mapOf("volume" to "0")).use { mpv ->
                PlayerHud(mpv, PlaybackMeta("Three real audio tracks")).use { hud ->
                    mpv.load(clip)
                    await("File never loaded") { Tracks.parse(mpv.getProperty("track-list") ?: "[]").audio.size == 3 }
                    val tracks = Tracks.parse(mpv.getProperty("track-list")!!).audio
                    assertEquals(3, audioMenuTracks(emptyList(), tracks, true, null).size)
                    for (index in listOf(1, 2, 0)) {
                        hud.open(HudMenu.Audio)
                        // The menu initially focuses the current selection; Home isn't a HUD key.
                        val selected = Tracks.parse(mpv.getProperty("track-list")!!).audio.indexOfFirst { it.selected }
                        repeat(kotlin.math.abs(index - selected)) { hud.key(if (index > selected) HudKey.Down else HudKey.Up) }
                        hud.key(HudKey.Enter)
                        await("HUD did not select mounted track ${tracks[index].id}") {
                            mpv.getProperty("aid")?.toIntOrNull() == tracks[index].id
                        }
                        println("AUDIO_SMOKE ordinal=$index actual-aid=${mpv.getProperty("aid")} label=${tracks[index].label}")
                    }
                    await("No advancing native playback") { (mpv.getProperty("time-pos")?.toDoubleOrNull() ?: 0.0) >= 1.0 }
                    assertTrue(mpv.getProperty("video-out-params")?.contains("640") == true)
                    println("AUDIO_SMOKE complete time-pos=${mpv.getProperty("time-pos")} vo=${mpv.getProperty("current-vo")}")
                }
            }
        } finally { SwingUtilities.invokeAndWait { frame.dispose() } }
    }
}
