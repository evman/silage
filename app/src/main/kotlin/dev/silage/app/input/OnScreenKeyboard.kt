package dev.silage.app.input

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.ui.Silage
import kotlinx.coroutines.flow.MutableStateFlow
import javax.swing.SwingUtilities

/** A key of the on-screen keyboard. */
sealed interface KeyboardKey {
    data class Letter(val lower: Char, val upper: Char) : KeyboardKey
    data object Shift : KeyboardKey
    data object Space : KeyboardKey
    data object Delete : KeyboardKey
    data object Done : KeyboardKey
}

private fun letters(lower: String, upper: String) = lower.zip(upper) { l, u -> KeyboardKey.Letter(l, u) }

/** Shift also gives the symbols invitation links and server addresses need. */
val KeyboardRows: List<List<KeyboardKey>> = listOf(
    letters("1234567890", "!@#$%&*()_"),
    letters("qwertyuiop", "QWERTYUIOP"),
    letters("asdfghjkl-", "ASDFGHJKL="),
    letters("zxcvbnm.:/", "ZXCVBNM,;?"),
    listOf(KeyboardKey.Shift, KeyboardKey.Space, KeyboardKey.Delete, KeyboardKey.Done),
)

/** Where the keyboard's highlight is, and whether Shift is on. */
data class KeyboardNav(val row: Int = 1, val column: Int = 0, val shifted: Boolean = false) {
    val key: KeyboardKey get() = KeyboardRows[row][column]

    fun move(rows: Int, columns: Int): KeyboardNav {
        if (columns != 0) return copy(column = (column + columns).mod(KeyboardRows[row].size))
        val nextRow = (row + rows).mod(KeyboardRows.size)
        // The bottom row has fewer, wider keys: keep the highlight under the same part of the board.
        val fraction = (column + 0.5) / KeyboardRows[row].size
        return copy(row = nextRow, column = (fraction * KeyboardRows[nextRow].size).toInt().coerceAtMost(KeyboardRows[nextRow].lastIndex))
    }
}

/** What pressing [key] makes of [text]; null for a key that doesn't type. */
fun typed(text: String, key: KeyboardKey, shifted: Boolean): String? = when (key) {
    is KeyboardKey.Letter -> text + if (shifted) key.upper else key.lower
    KeyboardKey.Space -> "$text "
    KeyboardKey.Delete -> text.dropLast(1)
    KeyboardKey.Shift, KeyboardKey.Done -> null
}

/** A text box the keyboard can type into. */
class TextEntryField(
    val label: String,
    val password: Boolean,
    val value: () -> String,
    val onChange: (String) -> Unit,
    val onDone: () -> Unit,
)

/**
 * The on-screen keyboard's state. A text box registers while it has focus; a
 * controller's A then opens the keyboard on it, and its buttons go here until it
 * closes. Mouse and real keyboard keep working on the box as usual.
 */
object TextEntry {
    /** The box with the focus. */
    @Volatile var focused: TextEntryField? = null
    val open = MutableStateFlow<TextEntryField?>(null)
    val nav = MutableStateFlow(KeyboardNav())

    fun show(field: TextEntryField) { nav.value = KeyboardNav(); open.value = field }
    fun close() { open.value = null }

    fun press(key: KeyboardKey) {
        val field = open.value ?: return
        when (key) {
            KeyboardKey.Shift -> nav.value = nav.value.copy(shifted = !nav.value.shifted)
            KeyboardKey.Done -> { close(); field.onDone() }
            else -> typed(field.value(), key, nav.value.shifted)?.let(field.onChange)
        }
    }

    /** A controller button. True when it was used: an open keyboard takes them all, and A opens it on a focused box. */
    fun button(button: GamepadButton): Boolean {
        if (open.value == null) {
            val field = focused
            if (button != GamepadButton.A || field == null) return false
            SwingUtilities.invokeLater { show(field) }
            return true
        }
        SwingUtilities.invokeLater {
            when (button) {
                GamepadButton.A -> press(nav.value.key)
                GamepadButton.B -> close()
                GamepadButton.X -> press(KeyboardKey.Delete)
                GamepadButton.Y -> press(KeyboardKey.Space)
                GamepadButton.Menu -> press(KeyboardKey.Done)
                GamepadButton.LeftTrigger, GamepadButton.RightTrigger -> press(KeyboardKey.Shift)
                GamepadButton.Up -> nav.value = nav.value.move(-1, 0)
                GamepadButton.Down -> nav.value = nav.value.move(1, 0)
                GamepadButton.Left -> nav.value = nav.value.move(0, -1)
                GamepadButton.Right -> nav.value = nav.value.move(0, 1)
                else -> Unit
            }
        }
        return true
    }
}

/** Makes this text box available to the on-screen keyboard while it has focus. */
@Composable
fun Modifier.textEntry(value: String, onChange: (String) -> Unit, label: String, password: Boolean = false, onDone: () -> Unit = {}): Modifier {
    val currentValue by rememberUpdatedState(value)
    val currentChange by rememberUpdatedState(onChange)
    val currentDone by rememberUpdatedState(onDone)
    val field = remember(label, password) { TextEntryField(label, password, { currentValue }, { currentChange(it) }, { currentDone() }) }
    DisposableEffect(field) {
        onDispose {
            if (TextEntry.focused === field) TextEntry.focused = null
            if (TextEntry.open.value === field) TextEntry.close()
        }
    }
    return onFocusChanged { state ->
        if (state.isFocused) TextEntry.focused = field
        else if (TextEntry.focused === field) TextEntry.focused = null
    }
}

private fun KeyboardKey.label(shifted: Boolean): String = when (this) {
    is KeyboardKey.Letter -> (if (shifted) upper else lower).toString()
    KeyboardKey.Shift -> "Shift"
    KeyboardKey.Space -> "Space"
    KeyboardKey.Delete -> "Delete"
    KeyboardKey.Done -> "Done"
}

/** The keyboard over the page, when it's open. */
@Composable
fun OnScreenKeyboardHost() {
    val field by TextEntry.open.collectAsState()
    val nav by TextEntry.nav.collectAsState()
    val open = field ?: return
    // Clicks stay on the keyboard, away from the page behind it.
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.padding(bottom = 48.dp).widthIn(max = 1040.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Silage.Elevated).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(open.label, color = Silage.Text2, fontSize = 17.sp)
            val shown = open.value().let { if (open.password) "•".repeat(it.length) else it }
            Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                Text(shown.ifEmpty { " " }, color = Color.White, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            KeyboardRows.forEachIndexed { r, keys ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    keys.forEachIndexed { c, key ->
                        val highlighted = nav.row == r && nav.column == c
                        val on = key == KeyboardKey.Shift && nav.shifted
                        Box(
                            Modifier.weight(if (key == KeyboardKey.Space) 2f else 1f).height(58.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (highlighted) Color.White else Color.White.copy(alpha = 0.10f))
                                .border(2.dp, if (on) Silage.Amber else Color.Transparent, RoundedCornerShape(12.dp))
                                .pointerInput(key, nav.shifted) {
                                    detectTapGestures { TextEntry.nav.value = TextEntry.nav.value.copy(row = r, column = c); TextEntry.press(key) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(key.label(nav.shifted), color = if (highlighted) Color.Black else Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Text("A types  ·  X deletes  ·  Y space  ·  Triggers shift  ·  Menu done  ·  B closes", color = Silage.Text3, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
