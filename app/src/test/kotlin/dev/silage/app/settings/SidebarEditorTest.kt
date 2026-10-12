package dev.silage.app.settings

import dev.silage.app.shell.LibraryKind
import dev.silage.app.shell.SidebarLibrary
import dev.silage.app.ui.SidebarLibraryPref
import kotlin.test.Test
import kotlin.test.assertEquals

class SidebarEditorTest {
    private val all = listOf(
        SidebarLibrary(1, "Movies", LibraryKind.Movies, true),
        SidebarLibrary(2, "Shows", LibraryKind.Shows, true),
        SidebarLibrary(3, "Music", LibraryKind.Music, false),
    )

    @Test fun toggleFlipsOnlyThatLibraryAndKeepsEveryOneInOrder() {
        assertEquals(
            listOf(SidebarLibraryPref(1, true), SidebarLibraryPref(2, false), SidebarLibraryPref(3, false)),
            SidebarEditor.toggle(all, 2),
        )
        assertEquals(true, SidebarEditor.toggle(all, 3).last().visible)
    }

    @Test fun moveSwapsNeighbours() {
        assertEquals(listOf(2, 1, 3), SidebarEditor.move(all, 2, -1).map { it.id })
        assertEquals(listOf(1, 3, 2), SidebarEditor.move(all, 2, +1).map { it.id })
    }

    @Test fun movingPastTheEndsIsANoOp() {
        assertEquals(listOf(1, 2, 3), SidebarEditor.move(all, 1, -1).map { it.id })
        assertEquals(listOf(1, 2, 3), SidebarEditor.move(all, 3, +1).map { it.id })
    }

    @Test fun visibilitySurvivesAMove() {
        val moved = SidebarEditor.move(all, 3, -1)
        assertEquals(listOf(1, 3, 2), moved.map { it.id })
        assertEquals(listOf(true, false, true), moved.map { it.visible })
    }
}
