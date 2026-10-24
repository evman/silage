package dev.silage.app.nav

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.silage.app.shell.SidebarRail

/**
 * Browsing chrome stays outside the native player and authentication screens: a visible
 * Back button on the pages that sit on top of another one (Esc, Backspace and the mouse's
 * Back button do the same).
 */
@Composable
fun BrowseNavigation(nav: AppNavigator, content: @Composable () -> Unit) {
    val entry by nav.entry.collectAsState()
    val route = entry.route
    val showBack = nav.liveIds.size > 1 && showsBackButton(route)
    val sharesShell = showBack && backButtonSharesShell(route)
    Box(Modifier.fillMaxSize()) {
        // Pages with the sidebar get a row of their own, so the button never covers their title.
        Column(Modifier.fillMaxSize()) {
            if (sharesShell) Spacer(Modifier.height(84.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
        if (showBack) {
            BackButton({ nav.back() }, Modifier.align(Alignment.TopStart).padding(start = if (sharesShell) SidebarRail + 24.dp else 32.dp, top = 24.dp))
        }
    }
}
