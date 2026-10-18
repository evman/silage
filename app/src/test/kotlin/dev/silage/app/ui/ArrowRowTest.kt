package dev.silage.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ArrowRowTest {
    @Test fun arrowsShowOnHoverOnlyWhereThereIsMoreAndPageTheRow() = runComposeUiTest {
        val state = LazyListState()
        setContent {
            Box(Modifier.size(800.dp, 200.dp)) {
                ArrowRow(Modifier.testTag("row"), state = state) {
                    items(30) { Box(Modifier.size(150.dp, 150.dp)) }
                }
            }
        }
        onNodeWithContentDescription("Scroll right").assertDoesNotExist()

        onNodeWithTag("row").performMouseInput { moveTo(center) }
        onNodeWithContentDescription("Scroll left").assertDoesNotExist() // nothing before the first card
        val forward = onNodeWithContentDescription("Scroll right")
        forward.assertExists()
        assertTrue(runCatching { forward.assert(isFocusable()) }.isFailure, "arrows must not be keyboard focus stops")

        forward.performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        runOnIdle { assertTrue(state.firstVisibleItemIndex >= 3, "one click pages ~80% of 800 dp: index ${state.firstVisibleItemIndex}") }
        onNodeWithContentDescription("Scroll left").assertExists()

        onNodeWithContentDescription("Scroll left").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        runOnIdle { assertEquals(0, state.firstVisibleItemIndex) }
    }
}
