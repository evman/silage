package dev.silage.app.home

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.koin.core.Koin

@Composable
fun HomeListScreen(koin: Koin, onPlay: (String) -> Unit) { Text("Signed in. Home arrives in Task 6.") }
