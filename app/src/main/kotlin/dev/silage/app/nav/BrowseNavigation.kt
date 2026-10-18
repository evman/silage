package dev.silage.app.nav

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Browsing chrome stays outside the native player and authentication screens. */
@Composable
fun BrowseNavigation(nav: AppNavigator, content: @Composable () -> Unit) {
    val entry by nav.entry.collectAsState()
    val browsing = entry.route !is Route.Playing && entry.route !in setOf(Route.Connect, Route.SignIn, Route.Profiles)
    Column(Modifier.fillMaxSize()) {
        if (browsing && nav.liveIds.size > 1) {
            TextButton(onClick = { nav.back() }, modifier = Modifier.padding(start = 16.dp)) { Text("Back") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}
