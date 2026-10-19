package dev.silage.app.nav

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.silage.app.ui.Silage

/** A modal confirmation with a safe default and focus kept on its two actions. */
@Composable
internal fun QuitConfirmation(onCancel: () -> Unit, onQuit: () -> Unit) {
    val cancel = remember { FocusRequester() }
    val quit = remember { FocusRequester() }
    var quitSelected by remember { mutableStateOf(false) }
    val buttonShape = RoundedCornerShape(12.dp)
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.Escape -> { onCancel(); true }
                Key.DirectionLeft, Key.DirectionUp -> { cancel.requestFocus(); true }
                Key.DirectionRight, Key.DirectionDown -> { quit.requestFocus(); true }
                Key.Tab -> { if (quitSelected) cancel.requestFocus() else quit.requestFocus(); true }
                else -> false
            }
        },
        containerColor = Silage.Elevated,
        titleContentColor = Silage.Text,
        textContentColor = Silage.Text2,
        title = { Text("Quit Silage?") },
        text = { Text("Close Silage and return to your desktop?") },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = Silage.Text),
                modifier = Modifier.focusRequester(cancel)
                    .onFocusChanged { if (it.isFocused) quitSelected = false }
                    .border(2.dp, if (!quitSelected) Silage.Amber else Color.Transparent, buttonShape),
            ) { Text("Cancel") }
            LaunchedEffect(Unit) { cancel.requestFocus() }
        },
        confirmButton = {
            TextButton(
                onClick = onQuit,
                colors = ButtonDefaults.textButtonColors(contentColor = Silage.Text),
                modifier = Modifier.focusRequester(quit)
                    .onFocusChanged { if (it.isFocused) quitSelected = true }
                    .border(2.dp, if (quitSelected) Silage.Amber else Color.Transparent, buttonShape),
            ) { Text("Quit") }
        },
    )
}
