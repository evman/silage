package dev.silage.app.playback

import dev.silage.app.playback.hud.*
import java.awt.Rectangle
import java.awt.Frame
import com.sun.jna.Memory
import com.sun.jna.Native
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.*

class PlaybackFullscreenTest {
    @Test fun windowsBorderlessStartupCoversTheMonitorAndSurvivesLeavingPlayback() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        SwingUtilities.invokeAndWait {
            val frame = JFrame("Silage fullscreen test").apply {
                isUndecorated = true
                isResizable = false
                setBounds(120, 140, 800, 500)
                isVisible = true
            }
            try {
                val startup = PlaybackFullscreen(frame)
                startup.enter()
                val api = WindowsWindowApi.instance
                val hwnd = Native.getComponentPointer(frame)
                val monitor = Memory(40).also { it.clear(); it.setInt(0, 40) }
                assertTrue(api.GetMonitorInfoW(api.MonitorFromWindow(hwnd, 2), monitor))
                fun assertFullscreen() {
                    val rect = Memory(16)
                    assertTrue(api.GetWindowRect(hwnd, rect))
                    assertEquals((0..3).map { monitor.getInt(4L + it * 4) }, (0..3).map { rect.getInt(it * 4L) })
                    assertEquals(0, api.GetWindowLongW(hwnd, -16) and 0x00CF0000, "no native window controls or frame")
                    assertNull(frame.graphicsConfiguration.device.fullScreenWindow, "normal window, no exclusive fullscreen")
                }
                assertFullscreen()
                PlaybackFullscreen(frame).apply { enter(); leave() }
                assertFullscreen()
            } finally {
                frame.dispose()
            }
        }
    }

    @Test fun restoresExactWindowStateAndDoesNotOverwriteSnapshot() {
        val original = WindowSnapshot(Rectangle(120, 140, 800, 500), Frame.MAXIMIZED_BOTH, 123)
        val state = FullscreenState()
        assertEquals(original, state.enter(original))
        assertEquals(original, state.enter(WindowSnapshot(Rectangle(0,0,1920,1080), 0, 0)))
        assertTrue(state.active)
        assertEquals(original, state.exit())
        assertFalse(state.active)
        assertNull(state.exit())
    }
    @Test fun escapeExitsFullscreenBeforeMenuOrPlaybackAndFAlwaysToggles() {
        val nav = HudNav(menu = HudMenu.Audio, visible = false, fullscreen = true)
        assertEquals(HudEffect.ToggleFullscreen, nav.on(HudKey.Fullscreen).second)
        val (next, effect) = nav.on(HudKey.Escape)
        assertEquals(HudEffect.LeaveFullscreen, effect)
        assertEquals(HudMenu.Audio, next.menu)
        assertEquals(HudEffect.Exit, nav.copy(menu = HudMenu.None).on(HudKey.Back).second)
        assertEquals(HudEffect.Exit, nav.copy(fullscreen = false, menu = HudMenu.None).on(HudKey.Back).second)
        assertEquals(HudKey.Fullscreen, HudKey.ofAwt(java.awt.event.KeyEvent.VK_F))
        assertEquals(HudKey.Fullscreen, HudKey.ofMpv("f"))
    }
}
