package dev.silage.app.playback.hud

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Issue #3: the pointer must not sit on top of the playing video. */
class CursorAutoHideTest {
    @Test fun thePointerHidesWithTheHudAfterThreeQuietSeconds() {
        val cursor = CursorAutoHide(hideAfterMs = 3_000, now = 0)
        assertFalse(cursor.hidden(now = 1_000, hudVisible = true))
        assertFalse(cursor.hidden(now = 4_000, hudVisible = true))
        assertTrue(cursor.hidden(now = 4_000, hudVisible = false))
    }

    @Test fun movingTheMouseShowsItAgainEvenBeforeTheHudReturns() {
        val cursor = CursorAutoHide(hideAfterMs = 3_000, now = 0)
        cursor.moved(now = 5_000)
        assertFalse(cursor.hidden(now = 5_000, hudVisible = false))
        assertFalse(cursor.hidden(now = 7_999, hudVisible = false))
        assertTrue(cursor.hidden(now = 8_000, hudVisible = false))
    }

    @Test fun aHudShownByTheKeyboardOrAPauseKeepsThePointer() {
        val cursor = CursorAutoHide(hideAfterMs = 3_000, now = 0)
        assertFalse(cursor.hidden(now = 60_000, hudVisible = true))
    }
}

class CursorAutoHideStillPointerTest {
    /** Windows sends a mouse move when the cursor changes shape; one that goes nowhere is not movement. */
    @Test fun aMoveToTheSamePlaceDoesNotCount() {
        val cursor = CursorAutoHide(hideAfterMs = 3_000, now = 0)
        assertTrue(cursor.movedTo(10, 20, now = 1_000))
        assertFalse(cursor.movedTo(10, 20, now = 5_000))
        assertTrue(cursor.hidden(now = 5_000, hudVisible = false))
        assertTrue(cursor.movedTo(11, 20, now = 5_000))
        assertFalse(cursor.hidden(now = 5_000, hudVisible = false))
    }
}
