package dev.silage.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

/**
 * Focuses the returned requester the first time [ready] is true during this
 * visit to the page, and never again: a list that reloads (new sort, tab or
 * season) leaves focus where the user put it.
 */
@Composable
fun rememberInitialFocus(ready: Boolean): FocusRequester {
    val requester = remember { FocusRequester() }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(ready) {
        if (ready && !done) {
            runCatching { requester.requestFocus() }
            done = true
        }
    }
    return requester
}
