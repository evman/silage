package dev.silage.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.silage.app.connect.ConnectPresenter
import dev.silage.app.connect.ConnectScreen
import dev.silage.app.connect.ProbeOutcome
import dev.silage.app.di.desktopModule
import dev.silage.app.browse.CollectionScreen
import dev.silage.app.browse.LibraryScreen
import dev.silage.app.browse.SearchScreen
import dev.silage.app.browse.WatchlistScreen
import dev.silage.app.detail.DetailScreen
import dev.silage.app.home.HomeScreen
import dev.silage.app.shell.AppShell
import dev.silage.app.settings.SettingsScreen
import dev.silage.app.nav.AppNavigator
import dev.silage.app.nav.EntryHost
import dev.silage.app.nav.KeyRoot
import dev.silage.app.nav.Route
import dev.silage.app.playback.PlayingScreen
import dev.silage.app.profiles.ProfileRow
import dev.silage.app.profiles.ProfilesPresenter
import dev.silage.app.profiles.ProfilesScreen
import dev.silage.app.signin.SignInPresenter
import dev.silage.app.signin.SignInScreen
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.siloserver.silo.di.sharedModules
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.apiv2.ApiV2Probe
import org.siloserver.silo.network.apiv2.ApiV2ProbeResult
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.ProfileRepository

fun main() {
    val koin = startKoin { modules(sharedModules() + desktopModule) }.koin
    val navigator = AppNavigator(runBlocking { startRoute(koin) })
    val crashLog = CrashLog()
    // Developer hook for reproducing exits: post a real Esc key press after N seconds.
    System.getenv("SILAGE_DEV_BACK_AFTER")?.toLongOrNull()?.let { secs ->
        Thread {
            Thread.sleep(secs * 1000)
            javax.swing.SwingUtilities.invokeLater {
                val target = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                    ?: java.awt.Window.getWindows().firstOrNull { it.isShowing } ?: return@invokeLater
                java.awt.Toolkit.getDefaultToolkit().systemEventQueue.postEvent(
                    java.awt.event.KeyEvent(target, java.awt.event.KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_ESCAPE, java.awt.event.KeyEvent.CHAR_UNDEFINED))
            }
        }.apply { isDaemon = true }.start()
    }
    application {
      // Unexpected UI exceptions are logged with their stack trace instead of a raw error dialog (spec §7).
      @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
      CompositionLocalProvider(
          androidx.compose.ui.window.LocalWindowExceptionHandlerFactory provides androidx.compose.ui.window.WindowExceptionHandlerFactory {
              androidx.compose.ui.window.WindowExceptionHandler { t -> crashLog.record("window", t) }
          },
      ) {
        val windowState = rememberWindowState(placement = WindowPlacement.Fullscreen)
        Window(onCloseRequest = ::exitApplication, state = windowState, title = BuildInfo.NAME) {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, surface = Color.Black, primary = Color.White, onPrimary = Color.Black)) {
                // Layouts are specified at 1080p and scaled uniformly to the window (4K is 2×).
                BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
                    val scale = constraints.maxHeight / 1080f
                    val windowDensity = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(scale, fontScale = 1f),
                        dev.silage.app.playback.LocalWindowDensity provides windowDensity,
                    ) {
                        val route by navigator.route.collectAsState()
                        KeyRoot(route, onBack = navigator::back) { EntryHost(navigator) { entry -> AppRoutes(koin, navigator, entry.route) } }
                    }
                }
            }
        }
    }
      }
}

private suspend fun startRoute(koin: Koin): Route {
    val registry = koin.get<ServerRegistry>()
    if (registry.activeEntry.value == null) return Route.Connect
    if (koin.get<TokenManager>().getRefreshToken() == null) return Route.SignIn
    // Developer switch for screenshots/tests: skip the profile picker when one is already active.
    val dev = System.getenv("SILAGE_DEV_START")
    if (dev != null && koin.get<ProfileRepository>().getActiveProfileId() != null) {
        if (dev.startsWith("play:q:")) {
            val hit = koin.get<org.siloserver.silo.repository.CatalogRepository>().browse(query = dev.removePrefix("play:q:"), limit = 1)
            (hit as? ApiResult.Success)?.data?.items?.firstOrNull()?.let { return Route.Playing(it.contentId) }
        }
        if (dev.startsWith("detail:q:")) {
            val hit = koin.get<org.siloserver.silo.repository.CatalogRepository>().browse(query = dev.removePrefix("detail:q:"), limit = 1)
            (hit as? ApiResult.Success)?.data?.items?.firstOrNull()?.let { return Route.Detail(it.contentId) }
        }
        if (dev.startsWith("detail:")) return Route.Detail(dev.removePrefix("detail:"))
        if (dev == "settings") return Route.Settings
        if (dev == "profiles") return Route.Profiles
        if (dev == "signin") return Route.SignIn
        if (dev == "connect") return Route.Connect
        if (dev == "search") return Route.Search
        if (dev == "watchlist") return Route.Watchlist
        if (dev == "library") {
            val libs = koin.get<org.siloserver.silo.repository.PersonalDataRepository>().listUserLibraries()
            (libs as? ApiResult.Success)?.data?.firstOrNull()?.let { return Route.Library(it.id, it.name) }
        }
        return Route.Home
    }
    return Route.Profiles
}

@Composable
private fun AppRoutes(koin: Koin, nav: AppNavigator, route: Route) {
    val auth = koin.get<AuthRepository>()
    val profiles = koin.get<ProfileRepository>()
    when (route) {
        Route.Connect -> ConnectScreen(
            ConnectPresenter(
                probe = { url ->
                    when (koin.get<ApiV2Probe>().probeFresh(url)) {
                        is ApiV2ProbeResult.V2 -> ProbeOutcome.Reachable
                        ApiV2ProbeResult.UpdateServer -> ProbeOutcome.NeedsServerUpdate
                        is ApiV2ProbeResult.Failure -> ProbeOutcome.Unreachable
                    }
                },
                save = { url -> auth.setServerUrl(url) },
            ),
            onConnected = { nav.root(Route.SignIn) },
        )
        Route.SignIn -> SignInScreen(
            SignInPresenter { u, p -> auth.login(u, p) },
            onSignedIn = { nav.root(Route.Profiles) },
            onChangeServer = { nav.root(Route.Connect) },
        )
        Route.Profiles -> ProfilesScreen(
            ProfilesPresenter(
                list = {
                    when (val r = profiles.listProfiles()) {
                        is ApiResult.Success -> ApiResult.Success(r.data.map { ProfileRow(it.id, it.name, it.hasPin, it.avatarUrl, it.avatar) })
                        is ApiResult.Error -> r
                        is ApiResult.NetworkError -> r
                    }
                },
                verifyPin = { id, pin ->
                    when (val r = profiles.verifyPin(id, pin)) {
                        is ApiResult.Success -> ApiResult.Success(r.data.profileToken.takeIf { r.data.valid })
                        is ApiResult.Error -> r
                        is ApiResult.NetworkError -> r
                    }
                },
                select = { id, token -> profiles.selectProfile(id, token) },
            ),
            onSelected = { nav.root(Route.Home) },
            onChangeServer = { nav.root(Route.Connect) },
        )
        is Route.Playing -> PlayingScreen(koin, route.contentId, route.fromStart, route.fileId, onDone = { if (!nav.back()) nav.root(Route.Home) })
        Route.Home -> AppShell(nav, koin, route) {
            HomeScreen(koin, onOpen = { nav.go(Route.Detail(it.openTarget)) }, onPlay = { nav.go(Route.Playing(it.openTarget)) })
        }
        is Route.Library -> AppShell(nav, koin, route) {
            LibraryScreen(koin, route.id, route.name, onOpen = { nav.go(Route.Detail(it.openTarget)) }, onOpenCollection = { id, name -> nav.go(Route.Collection(id, name)) })
        }
        is Route.Collection -> AppShell(nav, koin, route) {
            CollectionScreen(koin, route.id, route.name, onOpen = { nav.go(Route.Detail(it.openTarget)) })
        }
        is Route.Detail -> DetailScreen(koin, route.contentId, onPlay = { id, fromStart, fileId -> nav.go(Route.Playing(id, fromStart, fileId)) })
        Route.Search -> AppShell(nav, koin, route) { SearchScreen(koin, onOpen = { nav.go(Route.Detail(it.openTarget)) }) }
        Route.Watchlist -> AppShell(nav, koin, route) { WatchlistScreen(koin, onOpen = { nav.go(Route.Detail(it.openTarget)) }) }
        Route.Settings -> AppShell(nav, koin, route) {
            SettingsScreen(koin, onSwitchProfile = { nav.root(Route.Profiles) }, onSwitchServer = { nav.root(Route.Connect) }, onSignedOut = { nav.root(Route.SignIn) })
        }
    }
}
