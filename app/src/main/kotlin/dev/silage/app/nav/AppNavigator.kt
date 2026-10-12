package dev.silage.app.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface Route {
    data object Connect : Route
    data object SignIn : Route
    data object Profiles : Route
    data object Home : Route
    data class Playing(val contentId: String) : Route
}

class AppNavigator(start: Route) {
    private val _route = MutableStateFlow(start)
    val route: StateFlow<Route> = _route.asStateFlow()
    fun go(route: Route) { _route.value = route }
}
