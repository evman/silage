package dev.silage.app.nav

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.input.key.Key

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import dev.silage.app.detail.TitleInfoDialog
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BackInputTest {
    @Test fun escapeBackspaceAndTheRemotesBackKeysAllMeanBack() {
        listOf(Key.Escape, Key.Backspace, Key.Back, Key.NavigatePrevious).forEach { assertTrue(BackInput.isBackKey(it), it.toString()) }
        listOf(Key.Enter, Key.DirectionLeft, Key.Q).forEach { assertFalse(BackInput.isBackKey(it), it.toString()) }
    }

    @Test fun theSyntheticBackKeyReadsAsComposesBackKey() {
        val awt = BackInput.backKeyEvent(java.awt.Canvas(), KeyEvent.KEY_PRESSED)
        // Compose reads an AWT key as Key(keyCode, keyLocation).
        assertEquals(Key.Back, Key(awt.keyCode, awt.keyLocation))
        assertEquals(KeyEvent.KEY_PRESSED, awt.id)
    }

    /** Browser Back has no AWT key code: Windows' raw virtual key and X11's (evdev) key code are both 166. */
    @Test fun browserBackIsRecognisedFromTheRawKeyCode() {
        val params = "KEY_PRESSED,keyCode=0,keyText=Unknown keyCode: 0x0,keyChar=Undefined keyChar,keyLocation=KEY_LOCATION_STANDARD," +
            "rawCode=166,primaryLevelUnicode=0,scancode=0,extendedKeyCode=0x0"
        assertEquals(166L, BackInput.rawCodeOf(params))
        assertEquals(null, BackInput.rawCodeOf("KEY_PRESSED,keyCode=8"))
        assertTrue(BackInput.isBrowserBack(KeyEvent.VK_UNDEFINED, 166L))
        assertFalse(BackInput.isBrowserBack(KeyEvent.VK_A, 166L))
        assertFalse(BackInput.isBrowserBack(KeyEvent.VK_UNDEFINED, 38L))
        assertFalse(BackInput.isBrowserBack(KeyEvent.VK_UNDEFINED, null))
    }

    @Test fun theMouseBackButtonIsBackOnPress() {
        assertTrue(BackInput.isMouseBack(MouseEvent.MOUSE_PRESSED, 4))
        assertFalse(BackInput.isMouseBack(MouseEvent.MOUSE_RELEASED, 4))
        assertFalse(BackInput.isMouseBack(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1))
    }

    /** One Browser Back press could reach Silage both as a key and as WM_APPCOMMAND: act once. */
    @Test fun aSecondSourceForTheSamePressIsIgnored() {
        val once = BackSources(windowMs = 400)
        assertTrue(once.accept(BackSources.Source.Key, now = 1_000))
        assertFalse(once.accept(BackSources.Source.AppCommand, now = 1_100))
        assertTrue(once.accept(BackSources.Source.Key, now = 1_200))
        assertTrue(once.accept(BackSources.Source.AppCommand, now = 2_000))
        assertTrue(once.accept(BackSources.Source.AppCommand, now = 2_050))
    }

    @Test fun everyBackKeyGoesBackFromTheRootWithoutOfferingToQuit() = runComposeUiTest {
        var backs = 0
        setContent { MaterialTheme { KeyRoot(route = Route.Watchlist, onBack = { backs++; false }) { Text("Watchlist") } } }
        waitForIdle()
        listOf(Key.Back, Key.NavigatePrevious, Key.Backspace).forEach { key ->
            onRoot().performKeyInput { pressKey(key) }
        }
        assertEquals(3, backs)
        onNodeWithText("Quit Silage?").assertDoesNotExist()
    }

    @Test fun everyBackKeyClosesADialog() = runComposeUiTest {
        var dismissed = 0
        setContent { MaterialTheme { TitleInfoDialog("Film", emptyList(), onDismiss = { dismissed++ }) } }
        waitForIdle()
        listOf(Key.Back, Key.NavigatePrevious, Key.Escape, Key.Backspace).forEachIndexed { i, key ->
            onRoot().performKeyInput { pressKey(key) }
            assertEquals(i + 1, dismissed, key.toString())
        }
    }

    @Test fun theRemotesBackKeyCancelsTheQuitConfirmation() = runComposeUiTest {
        var cancels = 0
        setContent { MaterialTheme { QuitConfirmation(onCancel = { cancels++ }, onQuit = {}) } }
        onNodeWithText("Cancel").performKeyInput { pressKey(Key.Back) }
        assertEquals(1, cancels)
    }
}
