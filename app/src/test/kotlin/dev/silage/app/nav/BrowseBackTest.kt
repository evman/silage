package dev.silage.app.nav

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class BrowseBackTest {
    @Test fun sidebarHomePreservesPreviousSearchVisit() = runComposeUiTest {
        val nav = AppNavigator(Route.Home)
        nav.go(Route.Search)
        val searchEntry = nav.entry.value
        setContent { MaterialTheme {
            dev.silage.app.shell.AppShellContent(nav, Route.Search, null, emptyList()) { Text("Results") }
        } }
        onNodeWithContentDescription("Home").performClick()
        assertEquals(Route.Home, nav.route.value)
        assertTrue(nav.back(), "Sidebar Home erased browsing history")
        assertSame(searchEntry, nav.entry.value, "Back should restore the actual Search visit, including saved state")
    }

    @Test fun escapeDoesNotPopPlayerRoute() = runComposeUiTest {
        val nav = AppNavigator(Route.Home)
        nav.go(Route.Playing("m"))
        setContent { KeyRoot(nav.route.value, nav::back) { Text("Player") } }
        onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Escape) }
        assertEquals(Route.Playing("m"), nav.route.value)
    }

    @Test fun escapeRestoresSearchFromDetails() = runComposeUiTest {
        val nav = AppNavigator(Route.Home)
        nav.go(Route.Search)
        val search = nav.entry.value
        nav.go(Route.Detail("m"))
        setContent { KeyRoot(nav.route.value, nav::back) { Text("Detail") } }
        onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Escape) }
        assertSame(search, nav.entry.value)
    }

    @Test fun visibleBackReturnsToLibrary() = runComposeUiTest {
        val nav = AppNavigator(Route.Home)
        nav.go(Route.Library(1, "Movies"))
        nav.go(Route.Detail("m"))
        setContent { MaterialTheme { BrowseNavigation(nav) { Text("Content") } } }
        onNodeWithText("Back").performClick()
        assertEquals(Route.Library(1, "Movies"), nav.route.value)
    }
    @Test fun altLeftReturnsToPreviousScreen() = runComposeUiTest {
        val nav = AppNavigator(Route.Home)
        nav.go(Route.Search)
        setContent { KeyRoot(Route.Search, nav::back) { Text("Search") } }
        onRoot().performKeyInput {
            keyDown(androidx.compose.ui.input.key.Key.AltLeft)
            pressKey(androidx.compose.ui.input.key.Key.DirectionLeft)
            keyUp(androidx.compose.ui.input.key.Key.AltLeft)
        }
        assertEquals(Route.Home, nav.route.value)
    }
    @Test fun repeatedDestinationDoesNotAddDuplicateHistory() {
        val nav = AppNavigator(Route.Home)
        nav.go(Route.Search)
        nav.go(Route.Search)
        nav.back()
        assertEquals(Route.Home, nav.route.value)
    }
}
