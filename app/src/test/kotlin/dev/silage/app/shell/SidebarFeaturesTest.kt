package dev.silage.app.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import dev.silage.app.nav.AppNavigator
import dev.silage.app.nav.Route
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SidebarFeaturesTest {
    @Test fun requestsAppearOnlyWhenTheServerOffersThem() = runComposeUiTest {
        val nav = AppNavigator(Route.Home)
        var enabled by androidx.compose.runtime.mutableStateOf(false)
        setContent { MaterialTheme { AppShellContent(nav, Route.Home, null, emptyList(), requestsEnabled = enabled) { Text("Home") } } }
        onAllNodesWithContentDescription("Requests").assertCountEquals(0)
        enabled = true
        onNodeWithContentDescription("Requests").performClick()
        assertEquals(Route.Requests, nav.route.value)
    }

    @Test fun theBellShowsUnreadAndOpensTheInbox() = runComposeUiTest {
        val nav = AppNavigator(Route.Home)
        setContent { MaterialTheme { AppShellContent(nav, Route.Home, null, emptyList(), unread = 3) { Text("Home") } } }
        onNodeWithText("3").assertExists()
        onNodeWithContentDescription("Notifications (3)").performClick()
        assertEquals(Route.Notifications, nav.route.value)
    }
}
