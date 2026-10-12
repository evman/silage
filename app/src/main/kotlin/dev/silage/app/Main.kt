package dev.silage.app

import androidx.compose.foundation.layout.fillMaxSize
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
import dev.silage.app.home.HomeListScreen
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
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, surface = Color.Black)) {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    val route by navigator.route.collectAsState()
                    AppRoutes(koin, navigator, route)
                }
            }
        }
    }
}

private suspend fun startRoute(koin: Koin): Route {
    val registry = koin.get<ServerRegistry>()
    if (registry.activeEntry.value == null) return Route.Connect
    if (koin.get<TokenManager>().getRefreshToken() == null) return Route.SignIn
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
        // Until the sidebar shell (Plan 2 Task 5), every browsing route shows the Home list.
        else -> HomeListScreen(koin, onPlay = { nav.go(Route.Playing(it)) })
    }
}
