package dev.silage.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.BuildInfo
import dev.silage.app.XdgPaths
import dev.silage.app.browse.Pill
import dev.silage.app.shell.SidebarEntries
import dev.silage.app.shell.icon
import dev.silage.app.ui.Silage
import dev.silage.app.ui.UiPrefs
import dev.silage.app.ui.isActive
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.PersonalDataRepository

private enum class Section(val label: String) { Interface("Interface"), Video("Video"), Account("Account"), About("About") }

/** Settings: sections on the left, the chosen section's rows on the right. */
@Composable
fun SettingsScreen(koin: Koin, onSwitchProfile: () -> Unit, onSwitchServer: () -> Unit, onSignedOut: () -> Unit) {
    var section by remember { mutableStateOf(Section.Interface) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Row(Modifier.fillMaxSize().padding(start = 64.dp, top = 56.dp, end = 64.dp)) {
        Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settings", color = Silage.Text, fontSize = 48.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 28.dp))
            Section.entries.forEachIndexed { i, s ->
                SectionEntry(s.label, s == section, if (i == 0) Modifier.focusRequester(first) else Modifier) { section = s }
            }
        }
        Box(Modifier.padding(start = 56.dp, top = 112.dp).weight(1f)) {
            when (section) {
                Section.Interface -> InterfaceSection(koin)
                Section.Video -> VideoSection(koin)
                Section.Account -> AccountSection(koin, onSwitchProfile, onSwitchServer, onSignedOut)
                Section.About -> AboutSection(koin)
            }
        }
    }
}

@Composable
private fun SectionEntry(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    Box(
        modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(30.dp))
            .background(if (active) Color.White else if (selected) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick).padding(horizontal = 26.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(label, color = if (active) Color.Black else if (selected) Color.White else Silage.Text2, fontSize = 21.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) }
}

@Composable
private fun Heading(text: String, sub: String? = null) {
    Text(text, color = Silage.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
    sub?.let { Text(it, color = Silage.Text2, fontSize = 18.sp, modifier = Modifier.padding(top = 6.dp)) }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun IconButton(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive() && enabled
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(if (active) Color.White else Color.White.copy(alpha = if (enabled) 0.10f else 0.04f))
            .hoverable(interaction).clickable(interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = if (active) Color.Black else if (enabled) Color.White else Silage.Text3, modifier = Modifier.size(28.dp)) }
}

@Composable
private fun InterfaceSection(koin: Koin) {
    val prefs = remember { koin.get<UiPrefs>() }
    val version by prefs.changes.collectAsState()
    var server by remember { mutableStateOf(emptyList<UserLibrary>()) }
    LaunchedEffect(Unit) {
        val personal = koin.get<PersonalDataRepository>()
        (personal.listUserLibraries() as? ApiResult.Success)?.let { server = personal.withoutHidden(it.data) }
    }
    val all = remember(server, version) { SidebarEntries.libraries(server, prefs.sidebar) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Heading("Sidebar", "Choose which libraries appear in the sidebar, and their order.")
        all.forEachIndexed { i, lib ->
            Row(
                Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.05f)).padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(lib.kind.icon(), null, tint = if (lib.visible) Silage.Text else Silage.Text3, modifier = Modifier.size(28.dp))
                Text(lib.name, color = if (lib.visible) Silage.Text else Silage.Text3, fontSize = 21.sp, modifier = Modifier.padding(start = 20.dp).weight(1f))
                IconButton(if (lib.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, if (lib.visible) "Hide" else "Show") {
                    prefs.sidebar = SidebarEditor.toggle(all, lib.id)
                }
                Spacer(Modifier.width(12.dp))
                IconButton(Icons.Filled.KeyboardArrowUp, "Move up", enabled = i > 0) { prefs.sidebar = SidebarEditor.move(all, lib.id, -1) }
                Spacer(Modifier.width(12.dp))
                IconButton(Icons.Filled.KeyboardArrowDown, "Move down", enabled = i < all.lastIndex) { prefs.sidebar = SidebarEditor.move(all, lib.id, +1) }
            }
        }
    }
}

@Composable
private fun VideoSection(koin: Koin) {
    val prefs = remember { koin.get<UiPrefs>() }
    val version by prefs.changes.collectAsState()
    val mode = remember(version) { prefs.hdrMode }
    Column {
        Heading("HDR", "Auto plays HDR natively when HDR is turned on for the monitor Silage is on.")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Pill("Auto", selected = mode == "auto") { prefs.hdrMode = "auto" }
            Pill("Always", selected = mode == "on") { prefs.hdrMode = "on" }
            Pill("Never", selected = mode == "off") { prefs.hdrMode = "off" }
        }
    }
}

@Composable
private fun AccountSection(koin: Koin, onSwitchProfile: () -> Unit, onSwitchServer: () -> Unit, onSignedOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading("Account")
        Pill("Switch profile", onClick = onSwitchProfile)
        Pill("Switch server", onClick = onSwitchServer)
        Pill("Sign out") { scope.launch { runCatching { koin.get<AuthRepository>().logout() }; onSignedOut() } }
    }
}

@Composable
private fun AboutSection(koin: Koin) {
    var server by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { server = runCatching { koin.get<TokenManager>().getServerUrl() }.getOrDefault("") }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Heading("About")
        Row { Text("Version", color = Silage.Text2, fontSize = 20.sp, modifier = Modifier.width(200.dp)); Text("${BuildInfo.NAME} ${BuildInfo.VERSION}", color = Silage.Text, fontSize = 20.sp) }
        Row { Text("Server", color = Silage.Text2, fontSize = 20.sp, modifier = Modifier.width(200.dp)); Text(server, color = Silage.Text, fontSize = 20.sp) }
        Row { Text("Logs", color = Silage.Text2, fontSize = 20.sp, modifier = Modifier.width(200.dp)); Text(XdgPaths.state.toString(), color = Silage.Text, fontSize = 20.sp) }
        Spacer(Modifier.height(20.dp))
        Text("Silage is a Silo client for Linux. Silo is a trademark of Silo Media L.L.C.; Silage is not affiliated with it.", color = Silage.Text3, fontSize = 17.sp)
    }
}
