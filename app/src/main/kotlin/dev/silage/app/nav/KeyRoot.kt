package dev.silage.app.nav

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Window-level keys: arrows move focus spatially, Esc/Backspace go back.
 * At the root, Escape offers a confirmation before quitting.
 * The root takes focus on every route change (pages then claim their own),
 * so a page with nothing focusable — an empty grid, an error — never
 * swallows every key; from the root, an arrow enters the page.
 */
@Composable
fun KeyRoot(route: Route, onBack: () -> Boolean, onSearch: () -> Unit = {}, onQuit: () -> Unit = {}, onFullscreen: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val focus = LocalFocusManager.current
    val self = remember { FocusRequester() }
    var rootFocused by remember { mutableStateOf(false) }
    var confirmQuit by remember(route) { mutableStateOf(false) }
    LaunchedEffect(route) { runCatching { self.requestFocus() } }
    Box(
        Modifier.fillMaxSize()
            .focusRequester(self)
            .onFocusChanged { rootFocused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { e ->
                if (route is Route.Playing || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.isAltPressed && e.key == Key.DirectionLeft -> onBack()
                    // Ctrl+F: search, as in Windows apps.
                    e.isCtrlPressed && e.key == Key.F -> { onSearch(); true }
                    // F11: borderless fullscreen on or off (Windows).
                    e.key == Key.F11 && onFullscreen != null -> { onFullscreen(); true }
                    else -> false
                }
            }
            .onKeyEvent { e ->
                if (route is Route.Playing || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                fun move(d: FocusDirection) = focus.moveFocus(if (rootFocused) FocusDirection.Next else d)
                when (e.key) {
                    Key.DirectionLeft -> move(FocusDirection.Left)
                    Key.DirectionRight -> move(FocusDirection.Right)
                    Key.DirectionUp -> move(FocusDirection.Up)
                    Key.DirectionDown -> move(FocusDirection.Down)
                    Key.Escape -> { if (!onBack()) confirmQuit = true; true }
                    Key.Backspace -> onBack()
                    else -> false
                }
            },
    ) { content() }
    if (confirmQuit) {
        QuitConfirmation(
            onCancel = { confirmQuit = false },
            onQuit = { confirmQuit = false; onQuit() },
        )
    }
}
