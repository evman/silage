package dev.silage.app

import dev.silage.app.ui.UiPrefs
import java.awt.Rectangle
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowMemoryTest {
    private val screen = listOf(Rectangle(0, 0, 2560, 1440))

    @Test fun restoresBoundsThatAreOnAScreen() {
        val saved = SavedWindow(100, 120, 1280, 800, maximized = false)
        assertEquals(saved, restorableWindow(saved, screen))
    }

    @Test fun ignoresBoundsWhoseTitleBarIsOffEveryScreen() {
        // e.g. left on a second monitor that has since been unplugged
        assertNull(restorableWindow(SavedWindow(3000, 100, 1280, 800, false), screen))
        assertNull(restorableWindow(SavedWindow(100, 1430, 1280, 800, false), screen), "title bar below the screen")
        val secondMonitor = screen + Rectangle(2560, 0, 1920, 1080)
        assertEquals(3000, restorableWindow(SavedWindow(3000, 100, 1280, 800, false), secondMonitor)?.x)
    }

    @Test fun ignoresTinyOrMissingBounds() {
        assertNull(restorableWindow(null, screen))
        assertNull(restorableWindow(SavedWindow(10, 10, 200, 150, false), screen))
    }

    @Test fun remembersTheLastNormalBoundsAndWhetherItWasMaximized() {
        var stored: SavedWindow? = null
        val memory = WindowMemory(null) { stored = it }
        memory.update(maximized = false, floatingBounds = Rectangle(50, 60, 1300, 820), playbackFullscreen = false)
        memory.update(maximized = true, floatingBounds = null, playbackFullscreen = false)
        memory.save()
        assertEquals(SavedWindow(50, 60, 1300, 820, maximized = true), stored, "restores to the normal size when un-maximized")
    }

    @Test fun playbackFullscreenIsNeverRemembered() {
        var stored: SavedWindow? = null
        val memory = WindowMemory(SavedWindow(50, 60, 1300, 820, maximized = false)) { stored = it }
        memory.update(maximized = false, floatingBounds = Rectangle(0, 0, 2560, 1440), playbackFullscreen = true)
        memory.save()
        assertEquals(SavedWindow(50, 60, 1300, 820, maximized = false), stored)
    }

    @Test fun nothingIsSavedBeforeAnyNormalBoundsAreKnown() {
        var stored: SavedWindow? = null
        WindowMemory(null) { stored = it }.apply { update(true, null, false); save() }
        assertNull(stored, "first run stays maximized: the default")
    }

    @Test fun boundsRoundTripThroughUiPrefs() {
        val f = Files.createTempDirectory("silage-ui").resolve("ui.json")
        UiPrefs(f).window = SavedWindow(1, 2, 1280, 800, maximized = true)
        assertEquals(SavedWindow(1, 2, 1280, 800, maximized = true), UiPrefs(f).window)
        assertEquals("auto", UiPrefs(f).hdrMode, "other preferences unaffected")
    }

    @Test fun darkTitleBarAppliesToARealWindowOnWindows() {
        assertFalse(WindowsChrome.useDarkTitleBar(javax.swing.JFrame(), osName = "Linux"), "no-op off Windows")
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val frame = javax.swing.JFrame("Silage title bar test").apply { setSize(320, 200); isVisible = true }
        try {
            assertTrue(WindowsChrome.useDarkTitleBar(frame))
        } finally {
            frame.dispose()
        }
    }
}
