package dev.silage.app.playback

import androidx.compose.runtime.Composable
import org.koin.core.Koin

@Composable
fun PlayingScreen(koin: Koin, contentId: String, onDone: () -> Unit) = onDone()
