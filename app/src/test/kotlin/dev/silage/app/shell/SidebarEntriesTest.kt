package dev.silage.app.shell

import dev.silage.app.ui.SidebarLibraryPref
import org.siloserver.silo.model.personal.UserLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SidebarEntriesTest {
    private val movies = UserLibrary(1, "Movies", "movie", 0, null)
    private val shows = UserLibrary(2, "TV Shows", "series", 1, null)
    private val music = UserLibrary(3, "Music", "music", 2, null)

    @Test fun serverOrderWhenThereAreNoPrefs() {
        val e = SidebarEntries.libraries(listOf(shows, music, movies), emptyList())
        assertEquals(listOf(1, 2, 3), e.map { it.id })
        assertEquals(listOf(LibraryKind.Movies, LibraryKind.Shows, LibraryKind.Music), e.map { it.kind })
        assertTrue(e.all { it.visible })
    }

    @Test fun prefsReorderAndHide() {
        val e = SidebarEntries.libraries(listOf(movies, shows, music), listOf(SidebarLibraryPref(2, true), SidebarLibraryPref(1, false)))
        assertEquals(listOf(2, 1, 3), e.map { it.id })
        assertEquals(listOf(true, false, true), e.map { it.visible })
    }

    @Test fun staleLibrariesAreDroppedAndNewOnesAppendedVisible() {
        val e = SidebarEntries.libraries(listOf(movies, music), listOf(SidebarLibraryPref(9, true), SidebarLibraryPref(1, true)))
        assertEquals(listOf(1, 3), e.map { it.id })
        assertTrue(e.last().visible)
    }

    @Test fun libraryTypesMapToIcons() {
        assertEquals(LibraryKind.Shows, SidebarEntries.kindOf("show"))
        assertEquals(LibraryKind.Shows, SidebarEntries.kindOf("TV"))
        assertEquals(LibraryKind.Movies, SidebarEntries.kindOf("movies"))
        assertEquals(LibraryKind.Other, SidebarEntries.kindOf("audiobook"))
    }
}
