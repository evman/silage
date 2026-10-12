package dev.silage.app.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class InitialFocusTest {
    /** Review #7: reloading the list (new sort/season) must not pull focus off the control the user is on. */
    @Test fun focusesOnceThenLeavesTheUserWhereTheyAre() = runComposeUiTest {
        var ready by mutableStateOf(false)
        setContent {
            val first = rememberInitialFocus(ready)
            Column {
                Box(Modifier.size(40.dp).testTag("sort").focusable())
                if (ready) Box(Modifier.size(40.dp).testTag("card").focusRequester(first).focusable())
            }
        }
        runOnIdle { ready = true }
        onNodeWithTag("card").assertIsFocused()
        onNodeWithTag("sort").performSemanticsAction(SemanticsActions.RequestFocus)
        runOnIdle { ready = false }   // the user picked another sort: the list empties…
        runOnIdle { ready = true }    // …and reloads
        onNodeWithTag("sort").assertIsFocused()
    }
}
