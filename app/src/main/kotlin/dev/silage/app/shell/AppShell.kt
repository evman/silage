package dev.silage.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.border
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.nav.AppNavigator
import dev.silage.app.nav.Route
import dev.silage.app.ui.Silage
import dev.silage.app.ui.UiPrefs
import dev.silage.app.update.UpdateNotice
import dev.silage.app.update.UpdateOffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.Koin
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.ProfileRepository

/** Root pages: the sidebar over the page, which starts right of the rail. */
@Composable
fun AppShell(nav: AppNavigator, koin: Koin, current: Route, content: @Composable () -> Unit) {
    val prefs = remember { koin.get<UiPrefs>() }
    val prefsVersion by prefs.changes.collectAsState()
    // Shown at once from the last page's sidebar, then refreshed.
    val store = remember { koin.get<SidebarStore>() }
    val profile by store.profile.collectAsState()
    val cachedLibraries by store.libraries.collectAsState()
    val serverLibraries = cachedLibraries.orEmpty()
    LaunchedEffect(Unit) {
        val personal = koin.get<PersonalDataRepository>()
        (personal.listUserLibraries() as? ApiResult.Success)?.let { store.setLibraries(personal.withoutHidden(it.data)) }
        koin.get<ProfileRepository>().getActiveProfile()?.let { store.setProfile(SidebarProfile(it.name, it.avatarUrl, it.avatar)) }
    }
    val libraries = remember(serverLibraries, prefsVersion) { SidebarEntries.libraries(serverLibraries, prefs.sidebar) }
    LaunchedEffect(Unit) { koin.get<LiveFeatures>().start() }
    val inbox = remember { koin.get<org.siloserver.silo.repository.NotificationsRepository>() }
    val unread by inbox.unreadCount.collectAsState()
    // Older servers, or ones with the inbox switched off, get no bell.
    val capability by inbox.capability.collectAsState()
    val requestsEnabled by remember { koin.get<org.siloserver.silo.model.feature.RequestsFeatureStore>() }.isEnabled.collectAsState()

    // The update entry, resolved off the UI thread: finding out how Silage was installed runs commands.
    val updates by produceState<UpdateOffer?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { koin.getOrNull<UpdateOffer>() }.getOrNull() }
    }
    val shownUpdate = updates?.shown?.collectAsState()?.value
    val updateNotice = updates?.notice?.collectAsState()?.value
    val update = shownUpdate?.let { state ->
        sidebarUpdateFor(state)?.let { (title, subtitle) -> SidebarUpdate(title, subtitle) { updates?.select() } }
    }

    // Right-click menus on the page's cards, with a short confirmation for their actions.
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) { if (notice != null) { kotlinx.coroutines.delay(2_500); notice = null } }
    val scope = rememberCoroutineScope()
    val menu = remember(current) {
        dev.silage.app.browse.CardMenu(
            play = { nav.go(Route.Playing(it)) }, playFromStart = { nav.go(Route.Playing(it, fromStart = true)) }, open = { nav.go(Route.Detail(it)) },
            actions = dev.silage.app.detail.ItemActions(koin.get(), koin.get(), koin.get()), scope = scope,
            notify = { notice = it }, onWatchlistPage = current == Route.Watchlist,
            goToShow = { series, season, episode -> nav.go(Route.Detail(series, season, episode)) },
        )
    }

    CompositionLocalProvider(dev.silage.app.browse.LocalCardMenu provides menu) {
        AppShellContent(nav, current, profile, libraries, unread, requestsEnabled, notificationsAvailable = capability?.inApp?.enabled != false,
            notice = notice, update = update, content = content)
        updateNotice?.let { UpdateNoticeDialog(it) { updates?.dismissNotice() } }
    }
}

/** Stateless shell seam: navigation chrome can be exercised without a server. */
@Composable
internal fun AppShellContent(nav: AppNavigator, current: Route, profile: SidebarProfile?, libraries: List<SidebarLibrary>,
    unread: Int = 0, requestsEnabled: Boolean = false, notificationsAvailable: Boolean = true, notice: String? = null,
    update: SidebarUpdate? = null, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Silage.Black)) {
        Box(Modifier.fillMaxSize().padding(start = SidebarRail)) { content() }
        Sidebar(current, profile, libraries, onGo = nav::go, onProfile = { nav.root(Route.Profiles) }, unread = unread, requestsEnabled = requestsEnabled, notificationsAvailable = notificationsAvailable, update = update)
        androidx.compose.animation.AnimatedVisibility(notice != null, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 48.dp),
            enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            // Keeps the last text while fading out.
            var shown by remember { mutableStateOf("") }
            if (notice != null) shown = notice
            androidx.compose.material3.Text(shown, color = androidx.compose.ui.graphics.Color.Black, fontSize = 18.sp,
                modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).background(androidx.compose.ui.graphics.Color.White)
                    .padding(horizontal = 24.dp, vertical = 12.dp))
        }
    }
}

/** Why selecting the update entry didn't restart Silage: a message, and the command to run when there is one. */
@Composable
private fun UpdateNoticeDialog(notice: UpdateNotice, onDismiss: () -> Unit) {
    val ok = remember { androidx.compose.ui.focus.FocusRequester() }
    var okFocused by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Silage.Elevated,
        titleContentColor = Silage.Text,
        textContentColor = Silage.Text2,
        title = { androidx.compose.material3.Text("Update Silage") },
        text = {
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.Text(notice.message)
                notice.command?.let { command ->
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        androidx.compose.material3.Text(command, color = Silage.Text, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.padding(top = 16.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .background(Silage.Black).padding(12.dp))
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = Silage.Text),
                modifier = Modifier.focusRequester(ok).onFocusChanged { okFocused = it.isFocused }
                    .border(2.dp, if (okFocused) Silage.Amber else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
            ) { androidx.compose.material3.Text("OK") }
            LaunchedEffect(Unit) { runCatching { ok.requestFocus() } }
        },
    )
}
