package dev.silage.app.playback

import com.sun.jna.Native
import com.sun.jna.Memory
import dev.silage.app.playback.hud.*
import dev.silage.player.MpvPlayer
import java.awt.*
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.imageio.ImageIO
import java.nio.file.Path
import kotlin.test.*

class WindowsAdvancedVideoSmokeTest {
    @Test fun nativeHudAppliesVideoPropertiesAndBorderlessFullscreenRestoresWindow() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val clip = System.getenv("SILAGE_SMOKE_CLIP") ?: return
        lateinit var frame: JFrame; lateinit var canvas: Canvas
        val ready = java.util.concurrent.CompletableFuture<Canvas>()
        SwingUtilities.invokeAndWait {
            frame = androidx.compose.ui.awt.ComposeWindow().apply {
                title = "Silage fullscreen and video verification"
                setContent { VideoSurface(onReady = { c, _ -> ready.complete(c) }) }
                setBounds(120,140,900,560); isVisible = true
            }
        }
        canvas = ready.get(10, java.util.concurrent.TimeUnit.SECONDS)
        fun await(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 15_000_000_000L
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(50)
            assertTrue(condition(), message)
        }
        val u = WindowsWindowApi.instance; val hwnd = Native.getComponentPointer(frame)
        fun rect(): Rectangle = Memory(16).use { r ->
            assertTrue(u.GetWindowRect(hwnd,r)); Rectangle(r.getInt(0),r.getInt(4),r.getInt(8)-r.getInt(0),r.getInt(12)-r.getInt(4))
        }
        val root = if (Path.of("src/main").toFile().exists()) Path.of("..") else Path.of(".")
        fun evidence(name: String) {
            SwingUtilities.invokeAndWait { frame.toFront(); canvas.requestFocusInWindow() }
            Thread.sleep(300)
            ImageIO.write(Robot().createScreenCapture(rect()), "png", root.resolve(name).toFile())
        }
        fun postKey(code: Int) {
            SwingUtilities.invokeAndWait {
                Toolkit.getDefaultToolkit().systemEventQueue.postEvent(java.awt.event.KeyEvent(canvas,
                    java.awt.event.KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, code, java.awt.event.KeyEvent.CHAR_UNDEFINED))
            }
        }
        val fullscreen = PlaybackFullscreen(frame)
        val session = VideoSession()
        try {
            val initial = rect(); val style = u.GetWindowLongW(hwnd,-16)
            MpvPlayer(playbackMpvOptions(VideoOutput.Embedded(Native.getComponentID(canvas)), false, true)).use { mpv ->
                PlayerHud(mpv, PlaybackMeta("Fullscreen / live video controls"), fullscreenWindow = fullscreen, videoSession = session).use { hud ->
                    val detach = attachEmbeddedInput(canvas, hud)
                    try {
                    mpv.load(clip)
                    await("No native playback") { (mpv.getProperty("time-pos")?.toDoubleOrNull() ?: 0.0) >= 1.0 }
                    hud.open(HudMenu.Video)
                    // Cycle fit and aspect, then step zoom, via actual HUD keyboard actions.
                    val last = VideoControls.labels.lastIndex
                    for (i in 0..last) {
                        hud.key(HudKey.Right)
                        Thread.sleep(250)
                        println("ADVANCED_VIDEO_STEP $i settings=${session.settings} error=${session.error} native-aspect=${mpv.getProperty("video-aspect-override")} native-zoom=${mpv.getProperty("video-zoom")}")
                        if (i < last) hud.key(HudKey.Down)
                    }
                    await("HUD video properties not applied") { mpv.getProperty("video-zoom")?.toDoubleOrNull()?.let { kotlin.math.abs(it - 0.1) < 0.00001 } == true }
                    assertEquals("1.000000", mpv.getProperty("panscan"))
                    assertEquals(16.0/9, mpv.getProperty("video-aspect-override")!!.toDouble(), 0.00001)
                    assertEquals(0.1, mpv.getProperty("video-zoom")!!.toDouble(), 0.00001)
                    println("ADVANCED_VIDEO_SMOKE properties=" + session.settings.properties().keys.associateWith(mpv::getProperty))
                    evidence("video-controls-native.png")
                    val before = mpv.getProperty("time-pos")!!.toDouble()
                    hud.pointerAt(1f - 72f / 1080f * canvas.height / canvas.width, 1f - 70f / 1080f, true)
                    await("No fullscreen transition") { fullscreen.active }
                    await("Fullscreen is not monitor bounds") { rect() == frame.graphicsConfiguration.bounds }
                    assertEquals(0, u.GetWindowLongW(hwnd,-16) and 0x00CF0000)
                    assertEquals(hwnd,Native.getComponentPointer(frame))
                    assertNull(frame.graphicsConfiguration.device.fullScreenWindow)
                    lateinit var other: JFrame
                    SwingUtilities.invokeAndWait {
                        other = JFrame("Isolated fullscreen focus-loss probe").apply { setSize(240,120); isVisible = true; toFront() }
                    }
                    try {
                        Thread.sleep(300)
                        assertEquals(0, frame.extendedState and Frame.ICONIFIED)
                        assertEquals(frame.graphicsConfiguration.bounds, rect())
                    } finally { SwingUtilities.invokeAndWait { other.dispose() } }
                    Thread.sleep(800); evidence("video-fullscreen-native.png")
                    postKey(java.awt.event.KeyEvent.VK_F)
                    await("F did not restore fullscreen") { !fullscreen.active && rect() == initial }
                    assertEquals(style,u.GetWindowLongW(hwnd,-16))
                    hud.key(HudKey.Down); hud.key(HudKey.Enter)
                    await("Reset failed") { mpv.getProperty("video-zoom")?.toDoubleOrNull() == 0.0 }
                    assertEquals(VideoSettings(), session.settings)
                    assertNull(session.error)
                    repeat(VideoControls.ResetIndex - 1) { hud.key(HudKey.Up) } // reset row -> aspect row 1
                    for (expected in listOf(16.0/9, 4.0/3, 2.35, -2.0)) {
                        hud.key(HudKey.Right)
                        await("Aspect preset not applied: $expected") {
                            mpv.getProperty("video-aspect-override")?.toDoubleOrNull()?.let { kotlin.math.abs(it-expected) < 0.00001 } == true
                        }
                    }
                    assertNull(session.error)
                    evidence("video-restored-native.png")
                    await("Playback stopped after fullscreen") { (mpv.getProperty("time-pos")?.toDoubleOrNull() ?: 0.0) > before + 0.5 }
                    SwingUtilities.invokeAndWait { frame.extendedState = Frame.MAXIMIZED_BOTH }
                    Thread.sleep(500)
                    val maximized = rect()
                    postKey(java.awt.event.KeyEvent.VK_F); await("Maximized fullscreen failed") { fullscreen.active }
                    fullscreen.close() // same end-playback/disposal restoration seam
                    await("Maximized bounds not restored") { rect() == maximized }
                    assertEquals(Frame.MAXIMIZED_BOTH, frame.extendedState)
                    println("ADVANCED_VIDEO_SMOKE fullscreen=$initial monitor=${frame.graphicsConfiguration.bounds} restored=${rect()} hwnd-preserved=true exclusive=false time-pos=${mpv.getProperty("time-pos")}")
                    } finally { detach() }
                }
            }
        } finally { fullscreen.close(); SwingUtilities.invokeAndWait { frame.dispose() } }
    }
}
