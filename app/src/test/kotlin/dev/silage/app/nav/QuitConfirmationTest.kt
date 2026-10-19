package dev.silage.app.nav

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class QuitConfirmationTest {
    @Test fun escapeUnwindsHistoryBeforeOfferingToQuitAndMouseCanConfirm() = runComposeUiTest {
        val nav = AppNavigator(Route.Home)
        nav.go(Route.Search)
        nav.go(Route.Detail("movie"))
        var quits = 0
        setContent { MaterialTheme {
            val route by nav.route.collectAsState()
            KeyRoot(route, nav::back, onQuit = { quits++ }) { Text(route.toString()) }
        } }
        onRoot().performKeyInput { pressKey(Key.Escape) }
        assertEquals(Route.Search, nav.route.value)
        onNodeWithText("Quit Silage?").assertDoesNotExist()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        assertEquals(Route.Home, nav.route.value)
        onNodeWithText("Quit Silage?").assertDoesNotExist()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        onNodeWithText("Quit Silage?").assertIsDisplayed()
        onNodeWithText("Cancel").assertIsFocused()
        assertEquals(0, quits)
        onNodeWithText("Quit").performClick()
        assertEquals(1, quits)
    }

    @Test fun enterDefaultsToCancelAndAnotherEscapeAlsoCancels() = runComposeUiTest {
        var quits = 0
        setContent { MaterialTheme {
            KeyRoot(Route.Connect, onBack = { false }, onQuit = { quits++ }) { Text("Connect") }
        } }
        onRoot().performKeyInput { pressKey(Key.Escape) }
        onNodeWithText("Cancel").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        onNodeWithText("Quit Silage?").assertDoesNotExist()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        onNodeWithText("Cancel").performKeyInput { pressKey(Key.Escape) }
        onNodeWithText("Quit Silage?").assertDoesNotExist()
        assertEquals(0, quits)
    }

    @Test fun arrowsChooseQuitAndEnterConfirms() = runComposeUiTest {
        var quits = 0
        setContent { MaterialTheme { QuitConfirmation(onCancel = {}, onQuit = { quits++ }) } }
        onNodeWithText("Cancel").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithText("Quit").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, quits)
    }

    @Test fun tabStaysWithinTheDialogAndMouseCanCancel() = runComposeUiTest {
        var cancels = 0
        var quits = 0
        setContent { MaterialTheme { QuitConfirmation(onCancel = { cancels++ }, onQuit = { quits++ }) } }
        onNodeWithText("Cancel").performKeyInput { pressKey(Key.Tab) }
        onNodeWithText("Quit").assertIsFocused().performKeyInput { pressKey(Key.Tab) }
        onNodeWithText("Cancel").assertIsFocused().performKeyInput {
            keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft)
        }
        onNodeWithText("Quit").assertIsFocused()
        onNodeWithText("Cancel").performClick()
        assertEquals(1, cancels)
        assertEquals(0, quits)
    }

    @Test fun escapeCancelsOnceAndSpaceActivatesOnlyTheFocusedButton() = runComposeUiTest {
        var cancels = 0
        var quits = 0
        setContent { MaterialTheme { QuitConfirmation(onCancel = { cancels++ }, onQuit = { quits++ }) } }
        onNodeWithText("Cancel").performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, cancels)
        onNodeWithText("Cancel").performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(2, cancels)
        onNodeWithText("Cancel").performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithText("Quit").performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(1, quits)
    }

    @Test fun childEscapeHandlersRunBeforeQuitAndBackspaceDoesNotOfferQuit() = runComposeUiTest {
        var localBacks = 0
        var quits = 0
        setContent { MaterialTheme {
            KeyRoot(Route.Profiles, onBack = { false }, onQuit = { quits++ }) {
                BasicTextField("", {}, modifier = Modifier.testTag("pin").onPreviewKeyEvent {
                    if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { localBacks++; true } else false
                })
            }
        } }
        onNodeWithTag("pin").performClick().performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, localBacks)
        onNodeWithText("Quit Silage?").assertDoesNotExist()
        onNodeWithTag("pin").performKeyInput { pressKey(Key.Backspace) }
        onNodeWithText("Quit Silage?").assertDoesNotExist()
        assertEquals(0, quits)
    }

    @Test fun escapeFromAFocusedTextFieldOffersQuitWithoutChangingItsText() = runComposeUiTest {
        val text = "https://example.test"
        setContent { MaterialTheme {
            KeyRoot(Route.Connect, onBack = { false }) {
                BasicTextField(text, {}, modifier = Modifier.testTag("server"))
            }
        } }
        onNodeWithTag("server").performClick().performKeyInput { pressKey(Key.Escape) }
        onNodeWithText("Quit Silage?").assertIsDisplayed()
        onNodeWithText("Cancel").performClick()
        onNodeWithTag("server").assertTextEquals(text)
    }

    @Test fun playerKeepsItsOwnEscapeHandling() = runComposeUiTest {
        var quits = 0
        setContent { KeyRoot(Route.Playing("movie"), onBack = { false }, onQuit = { quits++ }) { Text("Player") } }
        onRoot().performKeyInput { pressKey(Key.Escape) }
        onNodeWithText("Quit Silage?").assertDoesNotExist()
        assertEquals(0, quits)
    }
}
