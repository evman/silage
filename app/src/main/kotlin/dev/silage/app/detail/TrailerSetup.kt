package dev.silage.app.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import dev.silage.app.nav.BackInput
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.playback.TrailerTools
import dev.silage.app.ui.Silage
import dev.silage.app.ui.isActive
import kotlinx.coroutines.launch

/**
 * Asked the first time a YouTube trailer is played: it needs two small helper programs, which
 * Silage downloads from their official releases. [onReady] runs once they are in place.
 */
@Composable
internal fun TrailerSetupDialog(onReady: () -> Unit, onCancel: () -> Unit) {
    var working by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    fun cancel() { if (working == null) onCancel() }
    fun download() {
        failure = null; working = "Starting…"
        scope.launch {
            val problem = TrailerTools.install { working = it }
            working = null
            if (problem == null) onReady() else failure = problem
        }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).onPreviewKeyEvent { e ->
            if (BackInput.isBack(e)) { cancel(); true } else false
        },
        contentAlignment = Alignment.Center,
    ) {
        // Clicking the dimmed page behind closes it.
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { cancel() } })
        Column(
            Modifier.width(760.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xF2171717))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                .pointerInput(Unit) { detectTapGestures { } }.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Play trailers in Silage", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "YouTube trailers need two small helper programs: yt-dlp (about 20 MB) and Deno (about 45 MB). " +
                    "Silage downloads them once, from their official GitHub releases, checks them against the checksums published " +
                    "there, and keeps them in its own data folder.",
                color = Silage.Text2, fontSize = 19.sp, lineHeight = 28.sp,
            )
            failure?.let { Text(it, color = Silage.Error, fontSize = 18.sp) }
            val busy = working
            if (busy != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(32.dp).width(32.dp), color = Color.White, strokeWidth = 3.dp)
                    Text(busy, color = Color.White, fontSize = 19.sp)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DialogButton(if (failure == null) "Download" else "Try again", Modifier.focusRequester(first)) { download() }
                    DialogButton("Not now") { cancel() }
                }
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    Box(
        modifier.height(52.dp).clip(RoundedCornerShape(26.dp))
            .background(if (active) Color.White else Color.White.copy(alpha = 0.14f))
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick).padding(horizontal = 30.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (active) Color.Black else Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold) }
}
