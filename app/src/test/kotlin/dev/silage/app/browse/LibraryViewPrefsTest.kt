package dev.silage.app.browse

import dev.silage.app.ui.SavedLibraryView
import dev.silage.app.ui.UiPrefs
import org.siloserver.silo.catalog.filter.CatalogFacet
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryViewPrefsTest {
    private fun prefs() = UiPrefs(Files.createTempDirectory("silage-ui").resolve("ui.json"))

    @Test fun savedSettingsForOneLibraryAndDefaultsForAnother() {
        val p = prefs()
        val chosen = LibraryFilters().sortBy(LibrarySortField.Title).flipOrder()
            .toggle(CatalogFacet.Genre, "Drama").toggle(CatalogFacet.WatchStatus, "unwatched")
            .toggle(DurationBucket.OverTwoHours)
            .let { it.copy(state = it.state.copy(matchAll = false)) }
        LibraryViewPrefs(p).save(1, chosen)
        val a = LibraryViewPrefs(p).initial(1)
        assertEquals(chosen, a)
        assertEquals(chosen.key, a.key)
        assertEquals(LibraryFilters(), LibraryViewPrefs(p).initial(2))
    }

    @Test fun changingTheSortSavesIt() {
        val p = prefs()
        val store = LibraryViewPrefs(p)
        store.save(5, store.initial(5).sortBy(LibrarySortField.Year))
        assertEquals("year", p.libraryView(5)?.sort)
        assertEquals("desc", p.libraryView(5)?.order)
        assertEquals(LibrarySortField.Year, LibraryViewPrefs(p).initial(5).sort)
    }

    @Test fun unknownValuesFallBackToDefaults() {
        assertEquals(LibraryFilters(), SavedLibraryView(sort = "no_such_sort", order = "asc").toLibraryFilters())
        val badOrder = SavedLibraryView(sort = "title", order = "sideways").toLibraryFilters()
        assertEquals("title", badOrder.state.sort)
        assertEquals("asc", badOrder.state.order)
        val mixed = SavedLibraryView(filters = mapOf("NoSuchFacet" to listOf("x"), "Genre" to listOf("Drama"), "Decade" to emptyList()),
            durations = listOf("Forever", "UnderHalfHour")).toLibraryFilters()
        assertEquals(mapOf(CatalogFacet.Genre to setOf("Drama")), mixed.state.selections)
        assertEquals(setOf(DurationBucket.UnderHalfHour), mixed.durations)
        assertEquals("added_at", mixed.state.sort)
        assertEquals(LibraryFilters(), (null as SavedLibraryView?).toLibraryFilters())
    }
}
