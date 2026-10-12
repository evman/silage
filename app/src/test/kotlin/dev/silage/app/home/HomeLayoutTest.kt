package dev.silage.app.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HomeLayoutTest {
    /** Rows scrolled down must stay below the marquee, never over its title and description. */
    @Test fun scrolledRowsNeverCoverTheMarquee() = runComposeUiTest {
        var scrollTo: (Int) -> Unit = {}
        setContent {
            val state = rememberLazyListState()
            val scope = rememberCoroutineScope()
            scrollTo = { i -> scope.launch { state.scrollToItem(i) } }
            HomeLayout(
                marquee = { Text("Marquee") },
                listState = state,
            ) {
                items(10) { i -> Box(Modifier.fillMaxWidth().height(400.dp).testTag("row")) { Text("row $i") } }
            }
        }
        runOnIdle { scrollTo(4) }
        waitForIdle()
        val top = with(Density(density.density)) { HomeRowsTop.toPx() }
        val rows = onAllNodesWithTag("row").fetchSemanticsNodes().filter { it.boundsInRoot.height > 0f }
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.boundsInRoot.top >= top - 0.5f }, "a row reaches into the marquee: ${rows.map { it.boundsInRoot }}")
    }
}
