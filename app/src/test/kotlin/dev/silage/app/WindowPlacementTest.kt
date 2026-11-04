package dev.silage.app

import androidx.compose.ui.window.WindowPlacement
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowPlacementTest {
    private val left = SavedWindow(100, 80, 1280, 800, maximized = false)

    @Test fun windowsOpensMaximizedOrWhereItWasLeft() {
        assertEquals(WindowPlacement.Maximized, windowPlacement(borderless = false, saved = null, osName = "Windows 11"))
        assertEquals(WindowPlacement.Maximized, windowPlacement(borderless = false, saved = left.copy(maximized = true), osName = "Windows 11"))
        assertEquals(WindowPlacement.Floating, windowPlacement(borderless = false, saved = left, osName = "Windows 11"))
    }

    @Test fun borderlessFullscreenIsAFloatingWindowFilledToTheMonitor() {
        assertEquals(WindowPlacement.Floating, windowPlacement(borderless = true, saved = null, osName = "Windows 11"))
        assertEquals(WindowPlacement.Floating, windowPlacement(borderless = true, saved = left.copy(maximized = true), osName = "Windows 11"))
    }

    /** Issue #7: Linux opens fullscreen unless Settings → Interface → Window says Normal window. */
    @Test fun linuxOpensFullscreenOrAsANormalWindow() {
        assertEquals(WindowPlacement.Fullscreen, windowPlacement(borderless = false, saved = null, osName = "Linux"))
        assertEquals(WindowPlacement.Maximized, windowPlacement(borderless = false, saved = null, osName = "Linux", linuxWindowed = true))
    }
}
