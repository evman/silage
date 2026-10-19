package dev.silage.app.signin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import dev.silage.app.ui.Silage

@Composable
fun RememberSignInControl(remembered: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = remembered, onCheckedChange = onChange, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "Remember me" })
            Text(rememberSignInLabel(), color = Silage.Text, fontSize = 18.sp)
        }
        Text(rememberSignInDescription(),
            color = Silage.Text2, fontSize = 15.sp)
    }
}

internal fun rememberSignInLabel(osName: String = System.getProperty("os.name")): String =
    if (osName.startsWith("Windows")) "Remember me on this Windows account" else "Remember me on this Linux account"

private fun rememberSignInDescription(): String =
    if (System.getProperty("os.name").startsWith("Windows"))
        "Saves an encrypted session, never your password. Turn off to forget saved sign-in without signing out now."
    else "Saves your session using the desktop keyring when available, never your password. Turn off to forget saved sign-in without signing out now."

internal fun signInStorageError(): String =
    if (System.getProperty("os.name").startsWith("Windows")) org.siloserver.silo.platform.RememberedSessionStore.WRITE_ERROR
    else "Could not save sign-in. Check access to the desktop keyring and Silage data folder, or turn off Remember me and sign in for this session only."
