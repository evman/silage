package dev.silage.app.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import dev.silage.app.nav.Route
import dev.silage.app.ui.Silage

/** Interim stand-in for pages later Plan 2 tasks build. */
@Composable
fun PendingPage(route: Route) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(route.toString(), color = Silage.Text2, fontSize = 28.sp)
    }
}
