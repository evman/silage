package dev.silage.app.signin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SignInScreen(presenter: SignInPresenter, onSignedIn: () -> Unit, onChangeServer: () -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<SignInState>(SignInState.Idle) }
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(96.dp).width(720.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Sign in", style = MaterialTheme.typography.headlineLarge)
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(enabled = state != SignInState.Working, onClick = {
            state = SignInState.Working
            scope.launch {
                state = presenter.submit(user, pass)
                if (state == SignInState.SignedIn) onSignedIn()
            }
        }) { Text("Sign in") }
        TextButton(onClick = onChangeServer) { Text("Change server") }
        (state as? SignInState.Error)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
    }
}
