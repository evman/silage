package dev.silage.app.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import dev.silage.app.nav.AppNavigator
import dev.silage.app.nav.Route
import dev.silage.app.update.UpdateState
import dev.silage.app.update.Version
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SidebarUpdateTest {
    private val v = Version(0, 5, 1)

    @Test fun sidebarUpdateForEachState() {
        assertEquals("Update ready" to "Restart to update to 0.5.1", sidebarUpdateFor(UpdateState.Ready(v, Path.of("/tmp/x.deb"))))
        assertEquals("Update ready" to "Restart to update to 0.5.1", sidebarUpdateFor(UpdateState.Ready(v, null)))
        assertEquals("Update ready" to "Restart to finish updating to 0.5.1", sidebarUpdateFor(UpdateState.RestartToFinish(v)))
        assertEquals("Update available" to "Silage 0.5.1", sidebarUpdateFor(UpdateState.Available(v, "https://example.org")))
        assertNull(sidebarUpdateFor(UpdateState.Idle))
        assertNull(sidebarUpdateFor(UpdateState.Checking))
        assertNull(sidebarUpdateFor(UpdateState.Downloading(v, 0.4f)))
        assertNull(sidebarUpdateFor(UpdateState.Failed("Couldn't check for updates")))
    }

    private fun ComposeUiTest.shell(update: SidebarUpdate?) {
        val nav = AppNavigator(Route.Home)
        setContent { MaterialTheme { AppShellContent(nav, Route.Home, null, emptyList(), update = update) { Text("Home") } } }
    }

    private fun ComposeUiTest.entry() = onNode(hasClickAction() and hasContentDescription("Update ready"))

    @Test fun updateEntrySitsAboveSettingsAndSelectsWithEnter() = runComposeUiTest {
        var selected = 0
        shell(SidebarUpdate("Update ready", "Restart to update to 0.5.1") { selected++ })
        val update = onNodeWithContentDescription("Update ready").getUnclippedBoundsInRoot()
        val settings = onNodeWithContentDescription("Settings").getUnclippedBoundsInRoot()
        val notifications = onNodeWithContentDescription("Notifications").getUnclippedBoundsInRoot()
        assertTrue(update.bottom <= settings.top, "the entry is above Settings")
        assertTrue(update.top >= notifications.bottom, "the entry is below the bell")
        entry().performSemanticsAction(SemanticsActions.RequestFocus)
        entry().assertIsFocused()
        // Focus opens the sidebar: the label and the subtitle show.
        onNodeWithText("Update ready").assertIsDisplayed()
        onNodeWithText("Restart to update to 0.5.1").assertIsDisplayed()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, selected)
    }

    @Test fun noEntryWithoutAnUpdate() = runComposeUiTest {
        shell(null)
        onAllNodesWithContentDescription("Update ready").assertCountEquals(0)
        onAllNodesWithContentDescription("Update available").assertCountEquals(0)
        onAllNodesWithTag(UpdateDotTag, useUnmergedTree = true).assertCountEquals(0)
        onNodeWithContentDescription("Settings").assertExists()
    }

    @Test fun collapsedEntryShowsOnlyTheIconWithADot() = runComposeUiTest {
        shell(SidebarUpdate("Update ready", "Restart to update to 0.5.1") {})
        onNodeWithContentDescription("Update ready").assertIsDisplayed()
        onNodeWithTag(UpdateDotTag, useUnmergedTree = true).assertIsDisplayed()
        onAllNodesWithText("Update ready").assertCountEquals(0)
        onAllNodesWithText("Restart to update to 0.5.1").assertCountEquals(0)
        // Opened, the dot gives way to the words.
        entry().performSemanticsAction(SemanticsActions.RequestFocus)
        waitForIdle()
        onAllNodesWithTag(UpdateDotTag, useUnmergedTree = true).assertCountEquals(0)
        onNodeWithText("Restart to update to 0.5.1").assertIsDisplayed()
    }
}
