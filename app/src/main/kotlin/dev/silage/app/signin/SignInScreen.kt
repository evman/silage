package dev.silage.app.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
    /** Sign in with a code on another device, beside the password form; none for password only. */
    device: DeviceSignInPresenter? = null,
    /** The server's host, for the code panel's "Can't reach …". */
    serverHost: String = "",
) {
    var rememberSignIn by remember { mutableStateOf(remembered) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<SignInState>(SignInState.Idle) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    // The code lives as long as the screen: leaving withdraws it on the server.
    DisposableEffect(device) {
        device?.start()
        onDispose { device?.stop() }
    }
    val deviceUi = device?.ui?.collectAsState()?.value
    fun submit() {
        if (state == SignInState.Working) return
        state = SignInState.Working
        scope.launch {
            state = presenter.submit(user, pass)
            if (state == SignInState.SignedIn) {
                // The password won: the code on screen must not be approvable any more.
                device?.stop()
                onSignedIn()
            }
        }
    }
    val form: @Composable ColumnScope.() -> Unit = {
        Wordmark()
        Text("Sign in to your server", color = Silage.Text2, fontSize = 20.sp)
        Spacer(Modifier.height(28.dp))
        SilageField(user, { user = it }, "Username", Modifier.fillMaxWidth().focusRequester(first))
        SilageField(pass, { pass = it }, "Password", Modifier.fillMaxWidth(), password = true, onDone = ::submit)
        RememberSignInControl(rememberSignIn, enabled = state != SignInState.Working) { next ->
            if (onRememberChanged(next)) { rememberSignIn = next; state = SignInState.Idle }
            else state = SignInState.Error(dev.silage.app.signin.signInStorageError())
        }
        PrimaryButton(if (state == SignInState.Working) "Signing in…" else "Sign in", enabled = state != SignInState.Working, onClick = ::submit)
        (state as? SignInState.Error)?.let { Text(it.message, color = Silage.Error.copy(red = 1f), fontSize = 18.sp, textAlign = TextAlign.Center) }
        TextLink("Change server", onChangeServer)
    }
    if (deviceUi == null || deviceUi.hidden) {
        FormFrame(form)
        return
    }
    Box(Modifier.fillMaxSize().background(Silage.Black), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(64.dp)) {
            Column(Modifier.width(520.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp), content = form)
            Box(Modifier.width(1.dp).height(420.dp).background(Silage.Hairline))
            DeviceCodePanel(deviceUi, serverHost, onShowNewCode = device::showNewCode, modifier = Modifier.width(360.dp))
        }
    }
}
