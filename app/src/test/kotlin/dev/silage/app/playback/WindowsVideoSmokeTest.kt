package dev.silage.app.playback

import dev.silage.player.MpvPlayer
import java.awt.Canvas
import java.awt.BorderLayout
import javax.swing.JFrame
import javax.swing.SwingUtilities
import com.sun.jna.Native
import kotlin.test.Test
import kotlin.test.assertTrue
import java.nio.file.Path

class WindowsVideoSmokeTest {
    @Test fun nativeCanvasPlaysVideoUsingDirect3D() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val clip = System.getenv("SILAGE_SMOKE_CLIP") ?: return
        lateinit var frame: JFrame
        lateinit var canvas: Canvas
        var hwnd = 0L
        SwingUtilities.invokeAndWait {
            canvas = Canvas()
            frame = JFrame("Silage Windows playback test").apply {
                layout = BorderLayout()
                add(canvas, BorderLayout.CENTER)
                setSize(800, 500)
                isVisible = true
            }
            hwnd = Native.getComponentID(canvas)
        }
        try {
            assertTrue(hwnd != 0L)
            MpvPlayer(VideoOutput.Embedded(hwnd).mpvOptions() + mapOf("volume" to "0",
                "log-file" to Path.of(System.getenv("SILAGE_SMOKE_LOG")).toString())).use { player ->
                player.load(clip)
                val deadline = System.nanoTime() + 15_000_000_000L
                while ((player.getProperty("time-pos")?.toDoubleOrNull() ?: 0.0) < 1.0 && System.nanoTime() < deadline) Thread.sleep(100)
                val position = player.getProperty("time-pos")?.toDoubleOrNull() ?: 0.0
                assertTrue(position >= 1.0, "No playback progress: $position")
                assertTrue(player.getProperty("video-out-params")?.contains("640") == true,
                    "No rendered 640px video: ${player.getProperty("video-out-params")}")
                println("WINDOWS_SMOKE HWND=$hwnd time-pos=$position vo=${player.getProperty("current-vo")} video-out=${player.getProperty("video-out-params")}")
            }
        } finally { SwingUtilities.invokeAndWait { frame.dispose() } }
    }
}
