package dev.silage.app.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.ui.Silage
import dev.silage.app.ui.TextLink

private val QrSize = 240.dp

/**
 * Sign in on another device: the QR of the code's activation link, the
 * address to type with the code, and a status line. No countdown: codes
 * renew in place.
 */
@Composable
fun DeviceCodePanel(ui: DeviceSignInUi, serverHost: String, onShowNewCode: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Or use your phone", color = Silage.Text2, fontSize = 20.sp)
        val code = ui.code
        if (code != null) {
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp)) {
                QrCode(code.qrContent, QrSize, description = "Sign-in QR code. Or go to ${code.activateText} and enter ${code.userCode}.")
            }
            Text("Go to ${code.activateText} and enter", color = Silage.Text2, fontSize = 17.sp, textAlign = TextAlign.Center)
            Text(code.userCode, color = Silage.Text, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp)
        } else {
            Box(
                Modifier.size(QrSize + 24.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                if (ui.status == DeviceSignInStatus.GettingCode) {
                    CircularProgressIndicator(color = Silage.Text3, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
                }
            }
        }
        Text(
            statusText(ui.status, serverHost),
            color = if (ui.status == DeviceSignInStatus.Opened || ui.status == DeviceSignInStatus.SignedIn) Silage.Amber else Silage.Text2,
            fontSize = 18.sp, textAlign = TextAlign.Center,
        )
        if (ui.canShowNewCode) TextLink("Show a new code", onShowNewCode)
    }
}

internal fun statusText(status: DeviceSignInStatus, serverHost: String): String = when (status) {
    DeviceSignInStatus.GettingCode -> "Getting a sign-in code…"
    DeviceSignInStatus.Waiting -> "Waiting for approval"
    DeviceSignInStatus.Opened -> "Continue on your phone"
    DeviceSignInStatus.NewCode -> "New code. Waiting for approval."
    DeviceSignInStatus.Unreachable -> "Can't reach $serverHost. Check your connection."
    DeviceSignInStatus.TooManyRequests -> "Too many sign-in attempts. Trying again shortly."
    DeviceSignInStatus.Paused -> "Sign-in paused."
    DeviceSignInStatus.Denied -> "Sign-in was declined."
    DeviceSignInStatus.Failed -> "Couldn't get a sign-in code."
    DeviceSignInStatus.CouldntFinish -> "Couldn't finish signing in."
    DeviceSignInStatus.SignedIn -> "Signed in"
}
