package dev.silage.app.nav

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class KeyRootTest {
    /** Review #1: an empty page has nothing focusable; Esc must still go back. */
    @Test fun escapeGoesBackEvenWhenThePageHasNothingFocusable() = runComposeUiTest {
        var backs = 0
        setContent { KeyRoot(route = Route.Watchlist, onBack = { backs++; true }) { Text("Your watchlist is empty") } }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, backs)
    }

    @Test fun arrowsMoveIntoThePageFromTheRoot() = runComposeUiTest {
        setContent {
            KeyRoot(route = Route.Home, onBack = { false }) {
                Column { Box(Modifier.size(40.dp).testTag("a").focusable()) }
            }
        }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("a").assertIsFocused()
    }
}
