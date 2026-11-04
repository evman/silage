package dev.silage.app.nav

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Browsing chrome stays outside the native player and authentication screens: a visible
 * Back button (Esc, Backspace and the mouse's Back button do the same). Pages with the
 * sidebar draw theirs in the shell, beneath the opening sidebar.
 */
@Composable
fun BrowseNavigation(nav: AppNavigator, content: @Composable () -> Unit) {
    val entry by nav.entry.collectAsState()
    val route = entry.route
    val showBack = nav.liveIds.size > 1 && showsBackButton(route) && !backButtonSharesShell(route)
    Box(Modifier.fillMaxSize()) {
        content()
        if (showBack) BackButton({ nav.back() }, Modifier.align(Alignment.TopStart).padding(start = 32.dp, top = 24.dp))
    }
}
