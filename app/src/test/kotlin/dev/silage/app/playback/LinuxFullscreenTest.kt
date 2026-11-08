package dev.silage.app.playback

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F11 on Linux: KWin's own fullscreen (the EWMH _NET_WM_STATE_FULLSCREEN request, as browsers and
 * video players use), not AWT's exclusive fullscreen, which KDE doesn't let a window leave.
 */
class LinuxFullscreenTest {
    @Test fun theRequestAddsOrRemovesFullscreenAsAnApplication() {
        // [action (1 add, 0 remove), property, second property (none), source (1 = application)]
        assertContentEquals(longArrayOf(1, 477, 0, 1), ewmhFullscreenData(on = true, fullscreenAtom = 477))
        assertContentEquals(longArrayOf(0, 477, 0, 1), ewmhFullscreenData(on = false, fullscreenAtom = 477))
    }

    @Test fun asksTheWindowManagerAndRemembersTheState() {
        val sent = mutableListOf<Boolean>()
        val fallback = mutableListOf<Boolean>()
        val fullscreen = LinuxFullscreen(request = { sent += it; true }, fallback = { fallback += it })
        assertFalse(fullscreen.fullscreen)
        fullscreen.fullscreen = true
        assertTrue(fullscreen.fullscreen)
        fullscreen.fullscreen = false
        assertFalse(fullscreen.fullscreen)
        assertEquals(listOf(true, false), sent)
        assertEquals(emptyList(), fallback, "Compose's exclusive fullscreen is never used when the request works")
    }

    @Test fun fallsBackWhenTheRequestCantBeSent() {
        val fallback = mutableListOf<Boolean>()
        val fullscreen = LinuxFullscreen(request = { false }, fallback = { fallback += it })
        fullscreen.fullscreen = true
        assertEquals(listOf(true), fallback)
        assertTrue(fullscreen.fullscreen)
    }
}
