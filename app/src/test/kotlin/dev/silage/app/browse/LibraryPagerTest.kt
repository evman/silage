package dev.silage.app.browse

import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryPagerTest {
    private fun card(id: String) = CardModel(id, id, null, null, null, false, null, false, null, null, null, null, "", id)
    private fun page(ids: List<String>, more: Boolean, next: String?): ApiResult<Page<String>> =
        ApiResult.Success(Page(ids.map(::card), more, next))

    @Test fun appendsPagesUntilTheEnd() = runTest {
        val pages = ArrayDeque(listOf(page(listOf("a", "b"), true, "c1"), page(listOf("c"), false, null)))
        val asked = mutableListOf<String?>()
        val p = LibraryPager<String> { asked += it; pages.removeFirst() }
        p.loadMore(); p.loadMore(); p.loadMore()
        assertEquals(listOf("a", "b", "c"), p.state.value.items.map { it.id })
        assertTrue(p.state.value.endReached)
        assertEquals(listOf(null, "c1"), asked)
    }

    @Test fun duplicateItemsAcrossPagesAreDropped() = runTest {
        val pages = ArrayDeque(listOf(page(listOf("a", "b"), true, "x"), page(listOf("b", "c"), false, null)))
        val p = LibraryPager<String> { pages.removeFirst() }
        p.loadMore(); p.loadMore()
        assertEquals(listOf("a", "b", "c"), p.state.value.items.map { it.id })
    }

    @Test fun aFailedPageKeepsItemsAndRetriesTheSameContinuation() = runTest {
        val results = ArrayDeque(listOf(page(listOf("a"), true, "k"), ApiResult.NetworkError(Exception("x")), page(listOf("b"), false, null)))
        val asked = mutableListOf<String?>()
        val p = LibraryPager<String> { asked += it; results.removeFirst() }
        p.loadMore(); p.loadMore()
        assertEquals(listOf("a"), p.state.value.items.map { it.id })
        assertNotNull(p.state.value.error)
        p.retry()
        assertEquals(listOf("a", "b"), p.state.value.items.map { it.id })
        assertNull(p.state.value.error)
        assertEquals("k", asked[2])
    }

    @Test fun emptyLibraryEndsImmediately() = runTest {
        val p = LibraryPager<String> { page(emptyList(), false, null) }
        p.loadMore()
        assertTrue(p.state.value.endReached && p.state.value.items.isEmpty() && !p.state.value.loading)
    }

    @Test fun morePagesWithoutAContinuationStopInsteadOfLooping() = runTest {
        var calls = 0
        val p = LibraryPager<String> { calls++; page(listOf("a$calls"), true, null) }
        p.loadMore(); p.loadMore()
        assertEquals(1, calls)
        assertTrue(p.state.value.endReached)
    }
}
