package dev.silage.app

import androidx.compose.ui.window.WindowPlacement
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowPlacementTest {
    @Test fun windowsAvoidsExclusiveFullscreenWhichMinimizesOnFocusLoss() {
        assertEquals(WindowPlacement.Maximized, initialWindowPlacement("Windows 11"))
        assertEquals(WindowPlacement.Fullscreen, initialWindowPlacement("Linux"))
    }
}
