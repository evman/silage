package dev.silage.app

import androidx.compose.ui.window.WindowPlacement
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowPlacementTest {
    @Test fun windowsUsesNormalWindowPlacementForBorderlessFullscreen() {
        assertEquals(WindowPlacement.Floating, initialWindowPlacement("Windows 11"))
        assertEquals(WindowPlacement.Fullscreen, initialWindowPlacement("Linux"))
    }
}
