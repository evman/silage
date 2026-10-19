package dev.silage.app.profiles

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.ui.Artwork
import dev.silage.app.ui.Silage
import dev.silage.app.ui.TextLink
import dev.silage.app.ui.isActive
import kotlinx.coroutines.launch

@Composable
fun ProfilesScreen(presenter: ProfilesPresenter, onSelected: () -> Unit, onChangeServer: () -> Unit) {
    var state by remember { mutableStateOf<ProfilesState>(ProfilesState.Loading) }
    var profiles by remember { mutableStateOf(emptyList<ProfileRow>()) }
    var pinTarget by remember { mutableStateOf<ProfileRow?>(null) }
    var pin by remember { mutableStateOf("") }
    var loadAttempt by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val firstAvatar = remember { FocusRequester() }
    // Set when the only profile was picked on its own, so a failure or a
    // cancelled PIN shows the picker instead of trying again.
    var autoTried by remember { mutableStateOf(false) }
    LaunchedEffect(loadAttempt) {
        state = ProfilesState.Loading
        val loaded = presenter.load()
        (loaded as? ProfilesState.Loaded)?.let { profiles = it.profiles }
        val sole = presenter.soleProfile(loaded)
        if (sole != null && !autoTried) {
            // One profile: no "Who's watching?" (a PIN profile goes straight to its PIN pad).
            autoTried = true
            state = presenter.choose(sole, null)
            when (val s = state) {
                ProfilesState.Selected -> onSelected()
                is ProfilesState.NeedsPin -> { pinTarget = s.profile; pin = "" }
                else -> Unit
            }
        } else {
            state = loaded
        }
    }
    LaunchedEffect(profiles.isNotEmpty(), pinTarget) { if (profiles.isNotEmpty() && pinTarget == null) runCatching { firstAvatar.requestFocus() } }
    fun choose(p: ProfileRow, pinValue: String?) = scope.launch {
        state = presenter.choose(p, pinValue)
        when (val s = state) {
            ProfilesState.Selected -> onSelected()
            is ProfilesState.NeedsPin -> { pinTarget = s.profile; pin = "" }
            is ProfilesState.PinError -> pin = ""
            else -> Unit
        }
    }

    Box(Modifier.fillMaxSize().background(Silage.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(40.dp)) {
            val target = pinTarget
            if (target == null) {
                Text("Who's watching?", color = Silage.Text, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                when (val s = state) {
                    ProfilesState.Loading -> CircularProgressIndicator(color = Silage.Text)
                    is ProfilesState.Error -> {
                        Text(s.message, color = Silage.Text2, fontSize = 20.sp)
                        TextLink("Retry") { loadAttempt++ }
                    }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                        profiles.forEachIndexed { i, p ->
                            AvatarTile(p, if (i == 0) Modifier.focusRequester(firstAvatar) else Modifier) { choose(p, null) }
                        }
                    }
                }
                TextLink("Change server", onChangeServer)
            } else {
                PinPad(target, pin, (state as? ProfilesState.PinError)?.message,
                    onDigit = { if (pin.length < 8) pin += it },
                    onDelete = { pin = pin.dropLast(1) },
                    onSubmit = { choose(target, pin) },
                    onCancel = { pinTarget = null; pin = ""; state = ProfilesState.Loaded(profiles) })
            }
        }
    }
}

@Composable
private fun Avatar(p: ProfileRow, size: Int) {
    dev.silage.app.ui.ProfileAvatar(p.name, p.avatarUrl, Modifier.size(size.dp), (size * 0.42).toInt())
}

@Composable
private fun AvatarTile(p: ProfileRow, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    val scale by animateFloatAsState(if (active) 1.1f else 1f, tween(150))
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier.scale(scale).then(if (active) Modifier.border(4.dp, Color.White, CircleShape) else Modifier).padding(6.dp)
                .clip(CircleShape).hoverable(interaction).clickable(interaction, indication = null, onClick = onClick),
        ) { Avatar(p, 160) }
        Spacer(Modifier.height(18.dp))
        Text(p.name, color = if (active) Color.White else Silage.Text2, fontSize = 22.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun PinPad(target: ProfileRow, pin: String, error: String?, onDigit: (Char) -> Unit, onDelete: () -> Unit, onSubmit: () -> Unit, onCancel: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(target.id) { runCatching { first.requestFocus() } }
    Column(
        // Physical digits, Backspace and Enter work too.
        Modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val c = e.utf16CodePoint.toChar()
            when {
                c.isDigit() -> { onDigit(c); true }
                e.key == Key.Backspace -> { onDelete(); true }
                e.key == Key.Escape -> { onCancel(); true }
                else -> false
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Avatar(target, 112)
        Text("Enter ${target.name}'s PIN", color = Silage.Text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(maxOf(4, pin.length)) { i ->
                Box(Modifier.size(20.dp).clip(CircleShape).background(if (i < pin.length) Color.White else Color.White.copy(alpha = 0.18f)))
            }
        }
        error?.let { Text(it, color = Silage.Error.copy(red = 1f), fontSize = 18.sp) }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            keys.chunked(3).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEachIndexed { c, k ->
                        PadKey(k, if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier) {
                            when (k) { "⌫" -> onDelete(); "OK" -> onSubmit(); else -> onDigit(k[0]) }
                        }
                    }
                }
            }
        }
        TextLink("Back", onCancel)
    }
}

@Composable
private fun PadKey(label: String, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    Box(
        modifier.size(88.dp).clip(RoundedCornerShape(44.dp)).background(if (active) Color.White else Color.White.copy(alpha = 0.10f))
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (active) Color.Black else Color.White, fontSize = 28.sp, fontWeight = FontWeight.Medium) }
}
