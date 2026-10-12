package dev.silage.app.nav

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class EntryHostTest {
    /** Review #3: Back returns to the page as it was (search text, tab, scroll are rememberSaveable). */
    @Test fun savedPageStateIsRestoredOnBack() = runComposeUiTest {
        val nav = AppNavigator(Route.Search)
        var type: (String) -> Unit = {}
        setContent {
            EntryHost(nav) { entry ->
                when (entry.route) {
                    Route.Search -> {
                        var query by rememberSaveable { mutableStateOf("") }
                        type = { query = it }
                        Text("query=$query")
                    }
                    else -> Text("detail")
                }
            }
        }
        runOnIdle { type("dune") }
        onNodeWithText("query=dune").assertExists()
        runOnIdle { nav.go(Route.Detail("m1")) }
        onNodeWithText("detail").assertExists()
        runOnIdle { nav.back() }
        onNodeWithText("query=dune").assertExists()
    }

    @Test fun aNewVisitStartsFresh() = runComposeUiTest {
        val nav = AppNavigator(Route.Home)
        var type: (String) -> Unit = {}
        setContent {
            EntryHost(nav) { entry ->
                if (entry.route == Route.Search) {
                    var query by rememberSaveable { mutableStateOf("") }
                    type = { query = it }
                    Text("query=$query")
                } else {
                    Text("home")
                }
            }
        }
        runOnIdle { nav.root(Route.Search) }
        runOnIdle { type("dune") }
        runOnIdle { nav.root(Route.Home) }
        runOnIdle { nav.root(Route.Search) }
        onNodeWithText("query=").assertExists()
    }

    class Probe : androidx.lifecycle.ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    /** Review #4: one view model per visit, reused across Back, cleared when the page is popped. */
    @Test fun entryViewModelIsReusedAndClearedWithItsEntry() = runComposeUiTest {
        val nav = AppNavigator(Route.Home)
        val made = mutableListOf<Probe>()
        setContent {
            EntryHost(nav) { entry ->
                if (entry.route is Route.Detail) {
                    rememberEntryViewModel { Probe().also { made += it } }
                    Text("detail")
                } else Text("home")
            }
        }
        runOnIdle { nav.go(Route.Detail("a")) }
        runOnIdle { nav.go(Route.Playing("a")) }
        runOnIdle { nav.back() }
        onNodeWithText("detail").assertExists()
        kotlin.test.assertEquals(1, made.size)
        runOnIdle { nav.back() }
        runOnIdle { kotlin.test.assertTrue(made.single().cleared) }
    }
}
