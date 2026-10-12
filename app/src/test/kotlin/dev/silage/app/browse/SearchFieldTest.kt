package dev.silage.app.browse

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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

@OptIn(ExperimentalTestApi::class)
class SearchFieldTest {
    private fun ui(key: Key) = runComposeUiTest {
        setContent {
            val f = remember { FocusRequester() }
            LaunchedEffect(Unit) { f.requestFocus() }
            Column {
                SearchField("dune", {}, f)
                Box(Modifier.size(200.dp).testTag("result").focusable())
            }
        }
        waitForIdle()
        onRoot().performKeyInput { pressKey(key) }
        onNodeWithTag("result").assertIsFocused()
    }

    /** Review #2: a single-line text field eats the arrows; Down must reach the results. */
    @Test fun downLeavesTheFieldForTheResults() = ui(Key.DirectionDown)

    @Test fun enterLeavesTheFieldForTheResults() = ui(Key.Enter)
}
