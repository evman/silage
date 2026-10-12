package dev.silage.app.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface Route {
    data object Connect : Route
    data object SignIn : Route
    data object Profiles : Route
    data object Home : Route
    data object Search : Route
    data object Watchlist : Route
    data object Settings : Route
    data class Library(val id: Int, val name: String) : Route
    data class Collection(val id: String, val name: String) : Route
    data class Detail(val contentId: String) : Route
    data class Playing(val contentId: String, val fromStart: Boolean = false) : Route
}

private val authRoutes = setOf(Route.Connect, Route.SignIn, Route.Profiles)

class AppNavigator(start: Route) {
    private val stack = ArrayDeque(listOf(start))
    private val _route = MutableStateFlow(start)
    val route: StateFlow<Route> = _route.asStateFlow()

    fun go(route: Route) { stack.addLast(route); _route.value = route }

    /** A sidebar destination or an auth step: replaces the stack (sidebar pages keep Home beneath them). */
    fun root(route: Route) {
        stack.clear()
        if (route != Route.Home && route !in authRoutes) stack.addLast(Route.Home)
        stack.addLast(route)
        _route.value = route
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeLast()
        _route.value = stack.last()
        return true
    }
}
