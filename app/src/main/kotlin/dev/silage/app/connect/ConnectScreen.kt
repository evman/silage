package dev.silage.app.connect

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.ui.FormFrame
import dev.silage.app.ui.PrimaryButton
import dev.silage.app.ui.Silage
import dev.silage.app.ui.SilageField
import dev.silage.app.ui.Wordmark
import kotlinx.coroutines.launch

@Composable
fun ConnectScreen(presenter: ConnectPresenter, onConnected: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<ConnectState>(ConnectState.Idle) }
    val scope = rememberCoroutineScope()
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    fun submit() {
        if (state == ConnectState.Connecting) return
        state = ConnectState.Connecting
        scope.launch {
            state = presenter.submit(address)
            if (state == ConnectState.Connected) onConnected()
        }
    }
    FormFrame {
        Wordmark()
        Text("A Silo client for Linux", color = Silage.Text2, fontSize = 20.sp)
        Spacer(Modifier.height(28.dp))
        SilageField(address, { address = it }, "Server address", Modifier.fillMaxWidth().focusRequester(field), onDone = ::submit)
        PrimaryButton(if (state == ConnectState.Connecting) "Connecting…" else "Connect", enabled = state != ConnectState.Connecting, onClick = ::submit)
        (state as? ConnectState.Error)?.let { Text(it.message, color = Silage.Error.copy(red = 1f), fontSize = 18.sp, textAlign = TextAlign.Center) }
    }
}
