package dev.silage.app.home

import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import kotlin.test.Test
import kotlin.test.assertEquals

class RemovedShowsTest {
    private fun ep(id: String, series: String?) = SectionItem(contentId = id, type = "episode", title = id, seriesId = series)
    private val rows = listOf(
        ResolvedSection("a", "continue_watching", "Continue", items = listOf(ep("e1", "s1"), ep("e2", "s2"))),
        ResolvedSection("b", "next_up", "Next Up", items = listOf(ep("e3", "s1"), ep("e4", "s3"))),
        ResolvedSection("c", "recently_added", "New", items = listOf(ep("e5", "s1"))),
    )

    @Test fun aRemovedShowLeavesBothProgressRowsButNotOtherRows() {
        val shown = withoutRemovedShows(rows, setOf("s1"))
        assertEquals(listOf("e2"), shown[0].items.map { it.contentId })
        assertEquals(listOf("e4"), shown[1].items.map { it.contentId })
        assertEquals(listOf("e5"), shown[2].items.map { it.contentId })
    }

    @Test fun anItemWithoutASeriesIsRemovedByItsOwnId() {
        val shown = withoutRemovedShows(listOf(ResolvedSection("a", "continue_watching", "C", items = listOf(ep("m1", null), ep("m2", null)))), setOf("m1"))
        assertEquals(listOf("m2"), shown[0].items.map { it.contentId })
    }

    @Test fun nothingRemovedChangesNothing() {
        assertEquals(rows, withoutRemovedShows(rows, emptySet()))
    }
}
