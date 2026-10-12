package dev.silage.app.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.core.Koin
import org.siloserver.silo.viewmodel.HomeViewModel

/** Plain Home rows; Plan 2 replaces this with the Skyline Home. */
@Composable
fun HomeListScreen(koin: Koin, onPlay: (String) -> Unit) {
    val vm = remember { koin.get<HomeViewModel>() }
    LaunchedEffect(vm) { vm.loadSections() }
    val state by vm.uiState.collectAsState()
    if (state.sections.isEmpty()) {
        Column(Modifier.padding(96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                state.isLoading -> Text("Loading…")
                state.error != null -> {
                    Text("Couldn't load Home: ${state.error}", color = MaterialTheme.colorScheme.error)
                    Button(onClick = { vm.refresh() }) { Text("Retry") }
                }
                else -> {
                    Text("Nothing on Home yet.")
                    Button(onClick = { vm.refresh() }) { Text("Refresh") }
                }
            }
        }
        return
    }
    LazyColumn(Modifier.padding(64.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        items(state.sections, key = { it.id }) { section ->
            Text(section.title, style = MaterialTheme.typography.titleLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(section.items, key = { it.contentId }) { item ->
                    Card(Modifier.width(220.dp).clickable { onPlay(item.contentId) }) {
                        Text(item.title, Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}
