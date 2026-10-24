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
import dev.silage.app.playback.SubtitleColor
import dev.silage.app.playback.SubtitleStyle
import dev.silage.app.playback.UpNextCountdownSeconds
import org.siloserver.silo.model.settings.SubtitleBackgroundStylePreset
import org.siloserver.silo.model.settings.SubtitlePositionPreset
import org.siloserver.silo.model.settings.pointSize
import dev.silage.app.ui.isActive
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.PersonalDataRepository

private enum class Section(val label: String) { Interface("Interface"), Posters("Posters"), TitlePages("Title pages"), Playback("Playback"), Video("Video"), Subtitles("Subtitles"), Account("Account"), Devices("Devices"), About("About") }

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
                Section.Posters -> PostersSection(koin)
                Section.TitlePages -> TitlePagesSection(koin)
                Section.Playback -> PlaybackSection(koin)
                Section.Video -> VideoSection(koin)
                Section.Subtitles -> SubtitlesSection(koin)
                Section.Account -> AccountSection(koin, onSwitchProfile, onSwitchServer, onSignedOut)
                Section.Devices -> DevicesSection(koin)
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
internal fun Heading(text: String, sub: String? = null) {
    Text(text, color = Silage.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
    sub?.let { Text(it, color = Silage.Text2, fontSize = 18.sp, modifier = Modifier.padding(top = 6.dp)) }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun IconButton(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(if (active) Color.White else Color.White.copy(alpha = if (enabled) 0.10f else 0.04f))
            .hoverable(interaction).clickable(interaction, indication = null) { if (enabled) onClick() },
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
    val size = remember(version) { prefs.interfaceSize }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Heading("Size", "How big text and posters are. Ctrl + and Ctrl − change it anywhere; Ctrl 0 goes back to Medium.")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            dev.silage.app.ui.InterfaceSize.entries.forEach { s -> Pill(s.label, selected = size == s) { prefs.interfaceSize = s } }
        }
        if (System.getProperty("os.name").startsWith("Windows")) {
            Spacer(Modifier.height(40.dp))
            val fullscreenStart = remember(version) { prefs.fullscreenStart }
            Heading("Window", "How Silage opens. F11 (or the player's fullscreen button) switches to borderless fullscreen and back at any time. Takes effect the next time you open Silage.")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Pill("Normal window", selected = !fullscreenStart) { prefs.fullscreenStart = false }
                Pill("Fullscreen", selected = fullscreenStart) { prefs.fullscreenStart = true }
            }
        }
        Spacer(Modifier.height(40.dp))
        Heading("Sidebar", "Choose which libraries appear in the sidebar, and their order.")
        SidebarEditorList(all) { prefs.sidebar = it }
    }
}

/** Each library with show/hide and move up/down. */
@Composable
fun SidebarEditorList(all: List<dev.silage.app.shell.SidebarLibrary>, onChange: (List<dev.silage.app.ui.SidebarLibraryPref>) -> Unit) {
    Column {
        // Keyed by library so focus follows the library when it moves.
        all.forEachIndexed { i, lib -> key(lib.id) {
            Row(
                Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.05f)).padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(lib.kind.icon(), null, tint = if (lib.visible) Silage.Text else Silage.Text3, modifier = Modifier.size(28.dp))
                Text(lib.name, color = if (lib.visible) Silage.Text else Silage.Text3, fontSize = 21.sp, modifier = Modifier.padding(start = 20.dp).weight(1f))
                IconButton(if (lib.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, if (lib.visible) "Hide" else "Show") {
                    onChange(SidebarEditor.toggle(all, lib.id))
                }
                Spacer(Modifier.width(12.dp))
                IconButton(Icons.Filled.KeyboardArrowUp, "Move up", enabled = i > 0) { onChange(SidebarEditor.move(all, lib.id, -1)) }
                Spacer(Modifier.width(12.dp))
                IconButton(Icons.Filled.KeyboardArrowDown, "Move down", enabled = i < all.lastIndex) { onChange(SidebarEditor.move(all, lib.id, +1)) }
            }
        } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoSection(koin: Koin) {
    val prefs = remember { koin.get<UiPrefs>() }
    val version by prefs.changes.collectAsState()
    val mode = remember(version) { prefs.hdrMode }
    val experimentalDv = remember(version) { prefs.experimentalDolbyVision }
    val quality = remember(version) { prefs.quality }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Heading("Streaming quality", "Auto plays the original file whenever this PC can, and only asks the server to convert when it must. " +
            "Pick a resolution to always watch at that size or lower (the server converts anything bigger). The player's Quality button changes it for one video.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            dev.silage.app.playback.QualityPrefs.Preferences.forEach { (wire, label) ->
                Pill(label, selected = quality.preference == wire) { prefs.quality = quality.copy(preference = wire) }
            }
        }
        Spacer(Modifier.height(40.dp))
        Heading("Maximum bitrate", "The most the server may send. A file above it is converted to fit, so set this below your connection's speed if videos stop to buffer.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            dev.silage.app.playback.QualityPrefs.Limits.forEach { (kbps, label) ->
                Pill(label, selected = quality.maxBitrateKbps == kbps) { prefs.quality = quality.copy(maxBitrateKbps = kbps) }
            }
        }
        Spacer(Modifier.height(40.dp))
        Heading("HDR", "Auto plays HDR natively when HDR is turned on for the monitor Silage is on.")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Pill("Auto", selected = mode == "auto") { prefs.hdrMode = "auto" }
            Pill("Always", selected = mode == "on") { prefs.hdrMode = "on" }
            Pill("Never", selected = mode == "off") { prefs.hdrMode = "off" }
        }
        Spacer(Modifier.height(40.dp))
        if (System.getProperty("os.name").startsWith("Linux")) {
            Heading("Experimental Dolby Vision", "Display signaling for Intel Arc A380 and ASUS PG32UCDM over DisplayPort. Authorizes only the bundled helper at startup. Restart Silage after changing this setting. Full Dolby color accuracy is not verified.")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Pill("Disabled", selected = !experimentalDv) { prefs.experimentalDolbyVision = false }
                Pill("Enabled", selected = experimentalDv) { prefs.experimentalDolbyVision = true }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaybackSection(koin: Koin) {
    val prefs = remember { koin.get<UiPrefs>() }
    val version by prefs.changes.collectAsState()
    val autoplay = remember(version) { prefs.autoplayNext }
    val skipIntro = remember(version) { prefs.autoSkipIntro }
    val skipRecap = remember(version) { prefs.autoSkipRecap }
    val skipCredits = remember(version) { prefs.autoSkipCredits }
    val languages = remember(version) { prefs.languages }
    val device = remember(version) { prefs.audioDevice }
    val preferredVersion = remember(version) { prefs.preferredVersion }
    var devices by remember { mutableStateOf<List<dev.silage.app.playback.AudioDevice>?>(null) }
    LaunchedEffect(Unit) { devices = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dev.silage.app.playback.AudioDevices.list() } }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Heading("Next episode", "When an episode ends, play the next one after a $UpNextCountdownSeconds-second countdown.")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Pill("Automatically", selected = autoplay) { prefs.autoplayNext = true }
            Pill("Ask me", selected = !autoplay) { prefs.autoplayNext = false }
        }
        Spacer(Modifier.height(40.dp))
        Heading("Intros", "Skip intros by themselves when the server knows where they are.")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Pill("Skip automatically", selected = skipIntro) { prefs.autoSkipIntro = true }
            Pill("Show a Skip button", selected = !skipIntro) { prefs.autoSkipIntro = false }
        }
        Spacer(Modifier.height(40.dp))
        Heading("Recaps", "Skip \"Previously on…\" recaps by themselves when the server knows where they are.")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Pill("Skip automatically", selected = skipRecap) { prefs.autoSkipRecap = true }
            Pill("Show a Skip button", selected = !skipRecap) { prefs.autoSkipRecap = false }
        }
        Spacer(Modifier.height(40.dp))
        Heading("Credits", "When the credits start, go straight to the next episode (or past the credits of a film).")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Pill("Skip automatically", selected = skipCredits) { prefs.autoSkipCredits = true }
            Pill("Show a button", selected = !skipCredits) { prefs.autoSkipCredits = false }
        }
        Spacer(Modifier.height(40.dp))
        Heading("Audio language", "The audio track new videos start with, when the file has one in that language. The Audio menu in the player still switches.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Pill("Server default", selected = languages.audio == null) { prefs.languages = languages.copy(audio = null) }
            dev.silage.app.playback.SettingsLanguages.forEach { code ->
                Pill(dev.silage.app.playback.languageName(code), selected = languages.audio == code) { prefs.languages = languages.copy(audio = code) }
            }
        }
        Spacer(Modifier.height(40.dp))
        Heading("Audio output", "Where Silage plays sound. Applies from the next video.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Pill("System default", selected = device == null) { prefs.audioDevice = null }
            devices?.forEach { d -> Pill(d.description, selected = device == d.name) { prefs.audioDevice = d.name } }
            // A chosen device that isn't connected now: shown so it can be changed back.
            if (device != null && devices != null && devices!!.none { it.name == device }) {
                Pill("Saved device (not connected)", selected = true) { prefs.audioDevice = null }
            }
        }
        if (devices == null) Text("Looking for sound devices…", color = Silage.Text3, fontSize = 17.sp, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(40.dp))
        Heading("Preferred version", "When a title has several copies (4K and 1080p, say) and you haven't picked one with the version button.")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Pill("Best quality", selected = preferredVersion != "last") { prefs.preferredVersion = "best" }
            Pill("Last played", selected = preferredVersion == "last") { prefs.preferredVersion = "last" }
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun SubtitlesSection(koin: Koin) {
    val prefs = remember { koin.get<UiPrefs>() }
    val version by prefs.changes.collectAsState()
    val style = remember(version) { prefs.subtitleStyle }
    val languages = remember(version) { prefs.languages }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        DefaultSubtitles(languages) { prefs.languages = it }
        Spacer(Modifier.height(40.dp))
        Heading("Appearance", "How subtitles look on this PC. Changes apply from the next video you play.")
        SubtitlePreview(style)
        Spacer(Modifier.height(32.dp))
        Heading("Size")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SubtitleStyle.sizeLabels.forEach { (size, label) -> Pill(label, selected = style.size == size) { prefs.subtitleStyle = style.copy(size = size) } }
        }
        Spacer(Modifier.height(32.dp))
        Heading("Background")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SubtitleStyle.backgroundLabels.forEach { (bg, label) -> Pill(label, selected = style.background == bg) { prefs.subtitleStyle = style.copy(background = bg) } }
        }
        Spacer(Modifier.height(32.dp))
        Heading("Text color")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SubtitleColor.entries.forEach { c -> Pill(c.label, selected = style.color == c) { prefs.subtitleStyle = style.copy(color = c) } }
        }
        Spacer(Modifier.height(32.dp))
        Heading("Position")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SubtitleStyle.positionLabels.forEach { (pos, label) -> Pill(label, selected = style.position == pos) { prefs.subtitleStyle = style.copy(position = pos) } }
        }
        Spacer(Modifier.height(32.dp))
        Pill("Reset to default") { prefs.subtitleStyle = SubtitleStyle() }
        Spacer(Modifier.height(48.dp))
    }
}

/** Which subtitles new videos start with, and in what language. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DefaultSubtitles(languages: dev.silage.app.playback.LanguagePrefs, onChange: (dev.silage.app.playback.LanguagePrefs) -> Unit) {
    val modes = dev.silage.app.playback.SubtitleModes
    Heading("Default subtitles", "What new videos start with. Server default follows your Silo profile; the Subtitles menu in the player still switches.")
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Pill("Server default", selected = languages.subtitleMode == null) { onChange(languages.copy(subtitleMode = null)) }
        Pill("Off", selected = languages.subtitleMode == modes.Off) { onChange(languages.copy(subtitleMode = modes.Off)) }
        Pill("On", selected = languages.subtitleMode == modes.On) { onChange(languages.copy(subtitleMode = modes.On)) }
        Pill("Forced only", selected = languages.subtitleMode == modes.Forced) { onChange(languages.copy(subtitleMode = modes.Forced)) }
    }
    if (languages.subtitleMode == modes.On || languages.subtitleMode == modes.Forced) {
        Spacer(Modifier.height(32.dp))
        Heading("Subtitle language")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Pill("Same as audio", selected = languages.subtitle == null) { onChange(languages.copy(subtitle = null)) }
            dev.silage.app.playback.SettingsLanguages.forEach { code ->
                Pill(dev.silage.app.playback.languageName(code), selected = languages.subtitle == code) { onChange(languages.copy(subtitle = code)) }
            }
        }
    }
}

/** A 16:9 frame with a sample line drawn roughly as mpv will draw it. */
@Composable
private fun SubtitlePreview(style: SubtitleStyle) {
    val textColor = if (style.color == SubtitleColor.Yellow) Color(0xFFFFE14D) else Color.White
    val fontSize = (style.size.pointSize * 0.42).sp
    val shadow = when (style.background) {
        SubtitleBackgroundStylePreset.Shadow -> androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.85f), androidx.compose.ui.geometry.Offset(3f, 3f), 4f)
        SubtitleBackgroundStylePreset.Outline -> androidx.compose.ui.graphics.Shadow(Color.Black, androidx.compose.ui.geometry.Offset.Zero, 6f)
        else -> null
    }
    val align = when (style.position) {
        SubtitlePositionPreset.Top -> Alignment.TopCenter
        else -> Alignment.BottomCenter
    }
    val bottomPad = if (style.position == SubtitlePositionPreset.LowerThird) 64.dp else 18.dp
    Box(
        Modifier.widthIn(max = 640.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF3A4B5C), Color(0xFF1B2229), Color(0xFF0B0E11)))),
    ) {
        Text(
            "The quick brown fox jumps over the lazy dog.",
            color = textColor, fontSize = fontSize, fontWeight = FontWeight.Medium,
            style = androidx.compose.ui.text.TextStyle(shadow = shadow),
            modifier = Modifier.align(align).padding(top = 18.dp, bottom = bottomPad, start = 24.dp, end = 24.dp)
                .then(if (style.background == SubtitleBackgroundStylePreset.Box) Modifier.background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 8.dp, vertical = 2.dp) else Modifier),
        )
    }
}

@Composable
private fun AccountSection(koin: Koin, onSwitchProfile: () -> Unit, onSwitchServer: () -> Unit, onSignedOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    val rememberedSettings = remember { koin.get<dev.silage.app.signin.RememberSignInSettings>() }
    var remembered by remember { mutableStateOf(rememberedSettings.enabled) }
    var saveError by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading("Account")
        dev.silage.app.signin.RememberSignInControl(remembered) { next ->
            if (rememberedSettings.setEnabled(next)) { remembered = next; saveError = null }
            else saveError = dev.silage.app.signin.signInStorageError()
        }
        saveError?.let { Text(it, color = Silage.Error, fontSize = 18.sp) }
        Pill("Switch profile", onClick = onSwitchProfile)
        Pill("Switch server", onClick = onSwitchServer)
        Pill("Sign out") { scope.launch {
            if (signOutAccount(
                logout = { koin.get<AuthRepository>().logout() },
                hasSession = { koin.get<org.siloserver.silo.network.TokenManager>().getRefreshToken() != null },
            )) onSignedOut()
            else saveError = "Sign-out could not clear saved sign-in. Check access to the Silage data folder and try again."
        } }
    }
}

@Composable
internal fun AboutSection(koin: Koin) {
    var server by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { server = runCatching { koin.get<TokenManager>().getServerUrl() }.getOrDefault("") }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Heading("About")
        Row { Text("Version", color = Silage.Text2, fontSize = 20.sp, modifier = Modifier.width(200.dp)); Text("${BuildInfo.NAME} ${BuildInfo.VERSION}", color = Silage.Text, fontSize = 20.sp) }
        Row { Text("Server", color = Silage.Text2, fontSize = 20.sp, modifier = Modifier.width(200.dp)); Text(server, color = Silage.Text, fontSize = 20.sp) }
        Row { Text("Logs", color = Silage.Text2, fontSize = 20.sp, modifier = Modifier.width(200.dp)); Text(XdgPaths.state.toString(), color = Silage.Text, fontSize = 20.sp) }
        Spacer(Modifier.height(20.dp))
        Text("Silage is a Silo client. Silo is a trademark of Silo Media L.L.C.; Silage is not affiliated with it.", color = Silage.Text3, fontSize = 17.sp)
    }
}
