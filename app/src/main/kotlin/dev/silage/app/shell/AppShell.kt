package dev.silage.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.silage.app.nav.AppNavigator
import dev.silage.app.nav.Route
import dev.silage.app.ui.Silage
import dev.silage.app.ui.UiPrefs
import org.koin.core.Koin
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.ProfileRepository

/** Root pages: the sidebar over the page, which starts right of the rail. */
@Composable
fun AppShell(nav: AppNavigator, koin: Koin, current: Route, content: @Composable () -> Unit) {
    val prefs = remember { koin.get<UiPrefs>() }
    val prefsVersion by prefs.changes.collectAsState()
    var serverLibraries by remember { mutableStateOf(emptyList<org.siloserver.silo.model.personal.UserLibrary>()) }
    var profile by remember { mutableStateOf<SidebarProfile?>(null) }
    LaunchedEffect(Unit) {
        val personal = koin.get<PersonalDataRepository>()
        (personal.listUserLibraries() as? ApiResult.Success)?.let { serverLibraries = personal.withoutHidden(it.data) }
        koin.get<ProfileRepository>().getActiveProfile()?.let { profile = SidebarProfile(it.name, it.avatarUrl, it.avatar) }
    }
    val libraries = remember(serverLibraries, prefsVersion) { SidebarEntries.libraries(serverLibraries, prefs.sidebar) }
    LaunchedEffect(Unit) { koin.get<LiveFeatures>().start() }
    val inbox = remember { koin.get<org.siloserver.silo.repository.NotificationsRepository>() }
    val unread by inbox.unreadCount.collectAsState()
    // Older servers, or ones with the inbox switched off, get no bell.
    val capability by inbox.capability.collectAsState()
    val requestsEnabled by remember { koin.get<org.siloserver.silo.model.feature.RequestsFeatureStore>() }.isEnabled.collectAsState()

    AppShellContent(nav, current, profile, libraries, unread, requestsEnabled, notificationsAvailable = capability?.inApp?.enabled != false, content = content)
}

/** Stateless shell seam: navigation chrome can be exercised without a server. */
@Composable
internal fun AppShellContent(nav: AppNavigator, current: Route, profile: SidebarProfile?, libraries: List<SidebarLibrary>,
    unread: Int = 0, requestsEnabled: Boolean = false, notificationsAvailable: Boolean = true, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Silage.Black)) {
        Box(Modifier.fillMaxSize().padding(start = SidebarRail)) { content() }
        Sidebar(current, profile, libraries, onGo = nav::go, onProfile = { nav.root(Route.Profiles) }, unread = unread, requestsEnabled = requestsEnabled, notificationsAvailable = notificationsAvailable)
    }
}
