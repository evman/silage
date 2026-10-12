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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HomeLayoutTest {
    @Test fun theBannerCollapsesOnceScrollingBegins() {
        assertFalse(homeCollapsed(firstVisibleIndex = 0, firstVisibleOffset = 0))
        assertTrue(homeCollapsed(firstVisibleIndex = 0, firstVisibleOffset = 1))
        assertTrue(homeCollapsed(firstVisibleIndex = 3, firstVisibleOffset = 0))
    }

    @Test fun collapsedIsHalfTheBanner() {
        assertEquals(HomeRowsTop.value / 2, HomeRowsTopCollapsed.value)
        assertTrue(HomeRowsTop.value <= 1080 * 0.40f, "rows start at ${HomeRowsTop.value} of 1080")
    }

    /** Rows sit at the full banner height at the top, glide up to half once scrolled, and never cover it. */
    @Test fun rowsGlideUpWhenScrollingAndNeverCoverTheBanner() = runComposeUiTest {
        mainClock.autoAdvance = false
        var scrollTo: (Int) -> Unit = {}
        var bannerBottom = 0f
        setContent {
            val state = rememberLazyListState()
            val scope = rememberCoroutineScope()
            scrollTo = { i -> scope.launch { state.scrollToItem(i) } }
            HomeLayout(marquee = { collapse -> Text("Marquee $collapse") }, listState = state, onBannerBottom = { bannerBottom = it }) {
                items(10) { i -> Box(Modifier.fillMaxWidth().height(400.dp).testTag("row")) { Text("row $i") } }
            }
        }
        fun px(d: Dp) = d.value * density.density
        fun visibleRowTops() = onAllNodesWithTag("row").fetchSemanticsNodes().filter { it.boundsInRoot.height > 0f }.map { it.boundsInRoot.top }
        mainClock.advanceTimeBy(1_000)
        assertTrue(visibleRowTops().min() >= px(HomeRowsTop) - 0.5f, "at rest rows start at the full banner height")

        runOnIdle { scrollTo(3) }
        mainClock.advanceTimeBy(32) // the scroll lands on the next frame and starts the animation
        // Mid-animation the rows are between the two heights and still below the banner.
        mainClock.advanceTimeBy(150)
        val mid = visibleRowTops().min()
        assertTrue(mid < px(HomeRowsTop) && mid > px(HomeRowsTopCollapsed), "mid-animation top=$mid")
        assertTrue(mid >= bannerBottom - 0.5f, "rows overlap the banner mid-animation: $mid < $bannerBottom")

        mainClock.advanceTimeBy(1_000)
        // The list keeps 16 dp of room at its top edge for a focused card's ring.
        val settled = visibleRowTops().min()
        val want = px(HomeRowsTopCollapsed + 16.dp)
        assertTrue(kotlin.math.abs(settled - want) < 1f, "settled at $settled, want $want")
    }
}
