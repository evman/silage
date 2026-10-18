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
    data class Playing(val contentId: String, val fromStart: Boolean = false, val fileId: Int? = null) : Route
}

private val authRoutes = setOf(Route.Connect, Route.SignIn, Route.Profiles)

/**
 * One visit to a page on the back stack. It holds what the page keeps while
 * you are elsewhere — loaded pages, view models, a coroutine scope — and
 * releases it all when the entry leaves the stack.
 */
class NavEntry internal constructor(val route: Route, val id: Long) {
    private val values = LinkedHashMap<String, Any>()
    private var scopeOrNull: kotlinx.coroutines.CoroutineScope? = null
    val viewModels = androidx.lifecycle.ViewModelStore()

    /** Work that should outlive recompositions but end with the entry. */
    val scope: kotlinx.coroutines.CoroutineScope
        @Synchronized get() = scopeOrNull ?: kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
        ).also { scopeOrNull = it }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun <T : Any> getOrPut(key: String, create: () -> T): T = values.getOrPut(key, create) as T

    @Synchronized
    internal fun close() {
        values.values.forEach { (it as? AutoCloseable)?.let { c -> runCatching { c.close() } } }
        values.clear()
        viewModels.clear()
        scopeOrNull?.coroutineContext?.get(kotlinx.coroutines.Job)?.cancel()
        scopeOrNull = null
    }
}

class AppNavigator(start: Route) {
    private var nextId = 0L
    private val stack = ArrayDeque(listOf(NavEntry(start, nextId++)))
    private val _entry = MutableStateFlow(stack.last())
    val entry: StateFlow<NavEntry> = _entry.asStateFlow()
    private val _route = MutableStateFlow(start)
    val route: StateFlow<Route> = _route.asStateFlow()

    /** Ids of the entries still on the stack (their saved UI state must be kept). */
    val liveIds: Set<Long> get() = stack.mapTo(HashSet()) { it.id }

    private fun publish() { _entry.value = stack.last(); _route.value = stack.last().route }

    fun go(route: Route) {
        if (stack.last().route == route) return
        stack.addLast(NavEntry(route, nextId++)); publish()
    }

    /** Explicit history reset (authentication/profile transitions); non-Home browse roots keep Home beneath them. */
    fun root(route: Route) {
        stack.forEach { it.close() }
        stack.clear()
        if (route != Route.Home && route !in authRoutes) stack.addLast(NavEntry(Route.Home, nextId++))
        stack.addLast(NavEntry(route, nextId++))
        publish()
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeLast().close()
        publish()
        return true
    }
}
