package dev.silage.app.playback

import dev.silage.app.playback.hud.*
import java.awt.Rectangle
import java.awt.Frame
import kotlin.test.*

class PlaybackFullscreenTest {
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
