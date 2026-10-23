package dev.silage.app.browse

import org.siloserver.silo.catalog.filter.CatalogFacet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LibraryFiltersTest {
    @Test fun sortsUseTheirOwnDefaultOrderAndFlip() {
        val byTitle = LibraryFilters().sortBy(LibrarySortField.Title)
        assertEquals("title", byTitle.state.sort)
        assertEquals("asc", byTitle.state.order)
        assertEquals("desc", byTitle.flipOrder().state.order)
        assertEquals("runtime", LibraryFilters().sortBy(LibrarySortField.Duration).state.sort)
    }

    @Test fun facetsAndDurationsBecomeQueryGroups() {
        val f = LibraryFilters()
            .toggle(CatalogFacet.Genre, "Drama").toggle(CatalogFacet.Genre, "Comedy")
            .toggle(CatalogFacet.Resolution, "2160p")
            .toggle(DurationBucket.UnderHalfHour).toggle(DurationBucket.OneToTwoHours)
        assertEquals(3, f.activeCount)
        val groups = f.groups()
        assertEquals(listOf("genre", "genre"), groups.first { g -> g.rules.any { it.field == "genre" } }.rules.map { it.field })
        assertTrue(groups.any { g -> g.rules.any { it.field == "resolution" && it.value == "2160p" } })
        val duration = groups.last()
        assertEquals("any", duration.match)
        assertEquals(listOf("lt", "between"), duration.rules.map { it.op })
        assertEquals(listOf("60", "120"), duration.rules[1].values)
        assertEquals("all", f.match)
    }

    @Test fun everyChoiceChangesTheKeyAndClearingKeepsTheSort() {
        val base = LibraryFilters().sortBy(LibrarySortField.Year)
        val filtered = base.toggle(CatalogFacet.Decade, "1990")
        assertNotEquals(base.key, filtered.key)
        assertNotEquals(filtered.key, filtered.toggle(DurationBucket.OverTwoHours).key)
        val cleared = filtered.toggle(DurationBucket.OverTwoHours).reset()
        assertEquals(0, cleared.activeCount)
        assertEquals("year", cleared.state.sort)
        assertEquals(base.key, cleared.key)
    }

    @Test fun languageValuesReadAsNames() {
        assertEquals("English", facetLabel(CatalogFacet.AudioLanguage, "eng", "eng"))
        assertEquals("Drama", facetLabel(CatalogFacet.Genre, "Drama", "Drama"))
    }
}
