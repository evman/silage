package dev.silage.app.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppNavigatorTest {
    @Test fun playerReturnsToThePageItCameFrom() {
        val nav = AppNavigator(Route.Home)
        nav.root(Route.Library(1, "Movies"))
        nav.go(Route.Detail("m1"))
        nav.go(Route.Playing("m1"))
        assertTrue(nav.back())
        assertEquals(Route.Detail("m1"), nav.route.value)
        assertTrue(nav.back())
        assertEquals(Route.Library(1, "Movies"), nav.route.value)
    }

    @Test fun sidebarPagesBackOutToHomeAndHomeStays() {
        val nav = AppNavigator(Route.Home)
        nav.root(Route.Settings)
        assertTrue(nav.back())
        assertEquals(Route.Home, nav.route.value)
        assertFalse(nav.back())
        assertEquals(Route.Home, nav.route.value)
    }

    @Test fun authRoutesClearTheStack() {
        val nav = AppNavigator(Route.Home)
        nav.go(Route.Detail("x"))
        nav.root(Route.Profiles)
        assertFalse(nav.back())
    }

    /** Review #3: a page's state lives with its back-stack entry, so Back returns to it intact. */
    @Test fun entryStateSurvivesAVisitToAnotherPage() {
        val nav = AppNavigator(Route.Home)
        nav.root(Route.Library(1, "Movies"))
        val pager = Any()
        nav.entry.value.getOrPut("pager") { pager }
        nav.go(Route.Detail("m1"))
        assertTrue(nav.back())
        assertTrue(nav.entry.value.getOrPut("pager") { Any() } === pager)
    }

    /** Review #4: what a popped page held is released (view models cleared, closeables closed). */
    @Test fun poppedAndReplacedEntriesAreClosed() {
        val nav = AppNavigator(Route.Home)
        var detailClosed = false
        var homeClosed = false
        nav.entry.value.getOrPut("home") { AutoCloseable { homeClosed = true } }
        nav.go(Route.Detail("x"))
        nav.entry.value.getOrPut("res") { AutoCloseable { detailClosed = true } }
        nav.back()
        assertTrue(detailClosed)
        assertFalse(homeClosed)
        nav.root(Route.Settings)
        assertTrue(homeClosed)
    }

    @Test fun eachVisitIsANewEntry() {
        val nav = AppNavigator(Route.Home)
        nav.go(Route.Detail("a"))
        val first = nav.entry.value.id
        nav.back()
        nav.go(Route.Detail("a"))
        assertTrue(nav.entry.value.id != first)
    }
}
