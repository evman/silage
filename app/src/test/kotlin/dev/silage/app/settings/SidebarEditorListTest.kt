package dev.silage.app.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import dev.silage.app.shell.LibraryKind
import dev.silage.app.shell.SidebarLibrary
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SidebarEditorListTest {
    private val libs = listOf(
        SidebarLibrary(1, "Movies", LibraryKind.Movies, true),
        SidebarLibrary(2, "Shows", LibraryKind.Shows, true),
        SidebarLibrary(3, "Music", LibraryKind.Music, true),
    )

    /** Review #6: repeated Move down must keep moving the same library, and focus must stay on its button. */
    @Test fun repeatedMoveDownKeepsMovingTheSameLibrary() = runComposeUiTest {
        var all by mutableStateOf(libs)
        setContent { SidebarEditorList(all) { prefs -> all = prefs.map { p -> libs.first { it.id == p.id }.copy(visible = p.visible) } } }
        onAllNodesWithContentDescription("Move down")[0].performSemanticsAction(SemanticsActions.RequestFocus)
        repeat(3) { onRoot().performKeyInput { pressKey(Key.Enter) }; waitForIdle() }
        assertEquals(listOf(2, 3, 1), all.map { it.id })
        onAllNodesWithContentDescription("Move down")[2].assertIsFocused()
    }
}
