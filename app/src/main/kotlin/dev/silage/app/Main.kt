package dev.silage.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
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
import dev.silage.app.home.HomeScreen
import dev.silage.app.shell.AppShell
import dev.silage.app.shell.PendingPage
import dev.silage.app.nav.AppNavigator
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
    application {
        val windowState = rememberWindowState(placement = WindowPlacement.Fullscreen)
        Window(onCloseRequest = ::exitApplication, state = windowState, title = BuildInfo.NAME) {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, surface = Color.Black, primary = Color.White, onPrimary = Color.Black)) {
                // Layouts are specified at 1080p and scaled uniformly to the window (4K is 2×).
                BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
                    val scale = constraints.maxHeight / 1080f
                    CompositionLocalProvider(LocalDensity provides Density(scale, fontScale = 1f)) {
                        val focus = LocalFocusManager.current
                        val route by navigator.route.collectAsState()
                        Box(
                            Modifier.fillMaxSize().onKeyEvent { e ->
                                // Bubbled up unhandled: arrows move focus spatially, Esc/Backspace go back.
                                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (e.key) {
                                    Key.DirectionLeft -> focus.moveFocus(FocusDirection.Left)
                                    Key.DirectionRight -> focus.moveFocus(FocusDirection.Right)
                                    Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
                                    Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
                                    Key.Escape, Key.Backspace -> navigator.back()
                                    else -> false
                                }
                            },
                        ) { AppRoutes(koin, navigator, route) }
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
                        is ApiResult.Success -> ApiResult.Success(r.data.map { ProfileRow(it.id, it.name, it.hasPin) })
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
        is Route.Playing -> PlayingScreen(koin, route.contentId, onDone = { if (!nav.back()) nav.root(Route.Home) })
        Route.Home -> AppShell(nav, koin, route) {
            HomeScreen(koin, onOpen = { nav.go(Route.Detail(it.openTarget)) }, onPlay = { nav.go(Route.Playing(it.openTarget)) })
        }
        is Route.Library -> AppShell(nav, koin, route) {
            LibraryScreen(koin, route.id, route.name, onOpen = { nav.go(Route.Detail(it.openTarget)) }, onOpenCollection = { id, name -> nav.go(Route.Collection(id, name)) })
        }
        is Route.Collection -> AppShell(nav, koin, route) {
            CollectionScreen(koin, route.id, route.name, onOpen = { nav.go(Route.Detail(it.openTarget)) })
        }
        // Pages still to come in Plan 2 show their name inside the shell.
        else -> AppShell(nav, koin, route) { PendingPage(route) }
    }
}
