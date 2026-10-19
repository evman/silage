package dev.silage.app.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import dev.silage.app.nav.KeyRoot
import dev.silage.app.nav.Route
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class VersionPickerTest {
    @Test fun mouseSelectionAndKeyboardDismissRestorePlayFocusAndArrowNavigation() = runComposeUiTest {
        val options = listOf(VersionOption(1, "4K DV", "4K DV Profile 5", 2), VersionOption(2, "4K DV", "4K DV Profile 7", 1))
        var visible by mutableStateOf(true)
        var selected by mutableStateOf(1)
        var played: Int? = null
        var backs = 0
        setContent {
            val play = remember { FocusRequester() }
            KeyRoot(Route.Home, onBack = { backs++; true }) {
                Box {
                    Row {
                        Button(onClick = { played = selected }, modifier = Modifier.focusRequester(play)) { Text("Play") }
                        Button(onClick = { visible = true }) { Text("Versions") }
                    }
                    VersionPicker(options, selected, onPick = { selected = it.fileId; visible = false },
                        onDismiss = { visible = false }, visible = visible, returnFocus = play)
                }
            }
        }
        onNodeWithText("4K DV Profile 7").performMouseInput { click() }
        runOnIdle { assertEquals(false, visible, "The version row must receive the mouse click"); assertEquals(2, selected) }
        onNodeWithText("Play").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithText("Versions").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        onNodeWithText("4K DV Profile 7").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        onNodeWithText("Play").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(2, played); assertEquals(0, backs) }
    }
}
