package dev.silage.app.profiles

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun ProfilesScreen(presenter: ProfilesPresenter, onSelected: () -> Unit) {
    var state by remember { mutableStateOf<ProfilesState>(ProfilesState.Loading) }
    var profiles by remember { mutableStateOf(emptyList<ProfileRow>()) }
    var pinTarget by remember { mutableStateOf<ProfileRow?>(null) }
    var pin by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        state = presenter.load()
        (state as? ProfilesState.Loaded)?.let { profiles = it.profiles }
    }
    fun choose(p: ProfileRow, pinValue: String?) = scope.launch {
        state = presenter.choose(p, pinValue)
        when (val s = state) {
            ProfilesState.Selected -> onSelected()
            is ProfilesState.NeedsPin -> pinTarget = s.profile
            else -> Unit
        }
    }
    Column(Modifier.padding(96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Who's watching?", style = MaterialTheme.typography.headlineLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            profiles.forEach { p -> Button(onClick = { pin = ""; pinTarget = null; choose(p, null) }) { Text(p.name) } }
        }
        pinTarget?.let { target ->
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit) }, label = { Text("PIN for ${target.name}") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Button(onClick = { choose(target, pin) }) { Text("OK") }
        }
        when (val s = state) {
            is ProfilesState.PinError -> Text(s.message, color = MaterialTheme.colorScheme.error)
            is ProfilesState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
    }
}
