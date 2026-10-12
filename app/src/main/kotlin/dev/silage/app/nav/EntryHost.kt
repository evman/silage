package dev.silage.app.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

val LocalNavEntry = staticCompositionLocalOf<NavEntry> { error("no NavEntry") }

/**
 * Shows the current back-stack entry. Each entry's rememberSaveable state
 * (search text, tabs, scroll positions, last focused card) is kept while the
 * entry is on the stack, so Back returns to the page as it was, and dropped
 * once the entry leaves the stack.
 */
@Composable
fun EntryHost(nav: AppNavigator, content: @Composable (NavEntry) -> Unit) {
    val entry by nav.entry.collectAsState()
    val holder = rememberSaveableStateHolder()
    val seen = remember { HashSet<Long>() }
    LaunchedEffect(entry) {
        seen += entry.id
        val live = nav.liveIds
        seen.filter { it !in live }.forEach { holder.removeState(it); seen -= it }
    }
    holder.SaveableStateProvider(entry.id) {
        CompositionLocalProvider(LocalNavEntry provides entry) { content(entry) }
    }
}

/** An object kept for as long as the current page's back-stack entry lives. */
@Composable
fun <T : Any> rememberEntry(key: String, create: () -> T): T = LocalNavEntry.current.getOrPut(key, create)

/** A view model owned by the current back-stack entry: one per visit, cleared when the entry is popped. */
@Composable
inline fun <reified VM : androidx.lifecycle.ViewModel> rememberEntryViewModel(noinline create: () -> VM): VM {
    val entry = LocalNavEntry.current
    return remember(entry) {
        androidx.lifecycle.ViewModelProvider.create(
            entry.viewModels,
            viewModelFactory { initializer { create() } },
        )[VM::class]
    }
}
