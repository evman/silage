package dev.silage.app.signin

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
import dev.silage.app.ui.TextLink
import dev.silage.app.ui.Wordmark
import kotlinx.coroutines.launch

@Composable
fun SignInScreen(
    presenter: SignInPresenter,
    onSignedIn: () -> Unit,
    onChangeServer: () -> Unit,
    remembered: Boolean = false,
    onRememberChanged: (Boolean) -> Boolean = { true },
) {
    var rememberSignIn by remember { mutableStateOf(remembered) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<SignInState>(SignInState.Idle) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    fun submit() {
        if (state == SignInState.Working) return
        state = SignInState.Working
        scope.launch {
            state = presenter.submit(user, pass)
            if (state == SignInState.SignedIn) onSignedIn()
        }
    }
    FormFrame {
        Wordmark()
        Text("Sign in to your server", color = Silage.Text2, fontSize = 20.sp)
        Spacer(Modifier.height(28.dp))
        SilageField(user, { user = it }, "Username", Modifier.fillMaxWidth().focusRequester(first))
        SilageField(pass, { pass = it }, "Password", Modifier.fillMaxWidth(), password = true, onDone = ::submit)
        RememberSignInControl(rememberSignIn, enabled = state != SignInState.Working) { next ->
            if (onRememberChanged(next)) { rememberSignIn = next; state = SignInState.Idle }
            else state = SignInState.Error(org.siloserver.silo.platform.RememberedSessionStore.WRITE_ERROR)
        }
        PrimaryButton(if (state == SignInState.Working) "Signing in…" else "Sign in", enabled = state != SignInState.Working, onClick = ::submit)
        (state as? SignInState.Error)?.let { Text(it.message, color = Silage.Error.copy(red = 1f), fontSize = 18.sp, textAlign = TextAlign.Center) }
        TextLink("Change server", onChangeServer)
    }
}
