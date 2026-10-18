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
            Text("Remember me on this Windows account", color = Silage.Text, fontSize = 18.sp)
        }
        Text("Saves an encrypted session, never your password. Turn off to forget saved sign-in without signing out now.",
            color = Silage.Text2, fontSize = 15.sp)
    }
}
