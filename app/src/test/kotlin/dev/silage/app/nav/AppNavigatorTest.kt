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
}
