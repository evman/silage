package dev.silage.app.connect

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun ConnectScreen(presenter: ConnectPresenter, onConnected: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<ConnectState>(ConnectState.Idle) }
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(96.dp).width(720.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Connect to your Silo server", style = MaterialTheme.typography.headlineLarge)
        OutlinedTextField(address, { address = it }, label = { Text("Server address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = state != ConnectState.Connecting, onClick = {
            state = ConnectState.Connecting
            scope.launch {
                state = presenter.submit(address)
                if (state == ConnectState.Connected) onConnected()
            }
        }) { Text("Connect") }
        (state as? ConnectState.Error)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
    }
}
