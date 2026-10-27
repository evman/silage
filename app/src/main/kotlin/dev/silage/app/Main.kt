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
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
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
import dev.silage.app.splash.SplashScreen
import dev.silage.app.ui.LogoIcon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.silage.app.nav.Route
import dev.silage.app.playback.PlayingScreen
import dev.silage.app.profiles.ProfileRow
import dev.silage.app.profiles.ProfilesPresenter
import dev.silage.app.profiles.ProfilesScreen
import dev.silage.app.signin.SignInPresenter
import dev.silage.app.signin.SignInScreen
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

fun main(args: Array<String>) {
    if ("--version" in args) {
        println("${BuildInfo.NAME} ${BuildInfo.VERSION}")
        return
    }
    val mpv = dev.silage.player.MpvRuntime.check()
    if ("--check" in args) {
        println(mpvCheckLine(mpv))
        kotlin.system.exitProcess(if (mpv is dev.silage.player.MpvStatus.Ok) 0 else 2)
    }
    // After the headless flags: this touches the windowing system.
    WmClass.set("silage")
    WindowsChrome.setAppUserModelId()
    val isWindows = System.getProperty("os.name").startsWith("Windows")
    mpvProblem(mpv)?.let { problem ->
        // Without a usable libmpv nothing can play: explain how to install it.
        application {
            Window(onCloseRequest = ::exitApplication, title = BuildInfo.NAME, icon = LogoIcon,
                state = rememberWindowState(placement = windowPlacement(borderless = false, saved = null))) {
                BorderlessStartup(window, isWindows, borderless = false)
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalDensity provides Density(constraints.maxHeight / 1080f, 1f)) {
                        KeyRoot(Route.Connect, onBack = { false }, onQuit = ::exitApplication) { MpvMissingScreen(problem) }
                    }
                }
            }
        }
        return
    }
    val koin = startKoin { modules(sharedModules() + desktopModule) }.koin
    val crashLog = CrashLog()
    // Windows opens as a normal window where it was left; Settings → Interface can open it
    // as a borderless fullscreen window instead (never AWT's exclusive mode).
    val uiPrefs = koin.get<dev.silage.app.ui.UiPrefs>()
    val borderless = isWindows && uiPrefs.fullscreenStart
    val savedWindow = if (isWindows) restorableWindow(runCatching { uiPrefs.window }.getOrNull(), screenBounds()) else null
    val windowMemory = WindowMemory(savedWindow) { uiPrefs.window = it }
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
        val windowState = rememberWindowState(
            placement = windowPlacement(borderless, savedWindow),
            position = savedWindow?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
            size = savedWindow?.let { DpSize(it.width.dp, it.height.dp) } ?: DpSize(1280.dp, 800.dp),
        )
        val closeSilage: () -> Unit = { if (isWindows) windowMemory.save(); dev.silage.app.update.UpdateApplier.onExit(); exitApplication() }
        var splashDone by remember { mutableStateOf(false) }
        Window(onCloseRequest = closeSilage, state = windowState, title = BuildInfo.NAME, icon = LogoIcon,
            undecorated = borderless, resizable = !borderless,
            onPreviewKeyEvent = { e -> !splashDone || (interfaceZoomKey(e)?.let { step -> uiPrefs.interfaceSize = step(uiPrefs.interfaceSize); true } ?: false) }) {
            BorderlessStartup(window, isWindows, borderless)
            val playbackFullscreen = remember(window) { dev.silage.app.playback.PlaybackFullscreen(window) }
            val dvAuthorization = remember {
                if (dev.silage.app.playback.dvHelperRequested(uiPrefs.experimentalDolbyVision)) dev.silage.app.playback.DvHelperAuthorization() else null
            }
            DisposableEffect(dvAuthorization) { onDispose { dvAuthorization?.close() } }
            val liveFeatures = remember { koin.get<dev.silage.app.shell.LiveFeatures>() }
            DisposableEffect(liveFeatures) { onDispose { liveFeatures.close() } }
            LaunchedEffect(window) { liveFeatures.appInForeground = { window.isActive } }
            if (isWindows) {
                LaunchedEffect(windowState) {
                    snapshotFlow { Triple(windowState.placement, windowState.position, windowState.size) }.collect { (placement, position, size) ->
                        val bounds = (position as? WindowPosition.Absolute)
                            ?.takeIf { !size.width.value.isNaN() && !size.height.value.isNaN() }
                            ?.let { java.awt.Rectangle(it.x.value.roundToInt(), it.y.value.roundToInt(), size.width.value.roundToInt(), size.height.value.roundToInt()) }
                        windowMemory.update(
                            maximized = placement == WindowPlacement.Maximized,
                            floatingBounds = bounds?.takeIf { placement == WindowPlacement.Floating },
                            // A borderless start fills the monitor: that is not the window's own size.
                            playbackFullscreen = playbackFullscreen.active || borderless,
                        )
                    }
                }
            }
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, surface = Color.Black, primary = Color.White, onPrimary = Color.Black)) {
                // Layouts are specified at 1080p and scaled uniformly to the window (4K is 2×).
                BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
                    // Settings → Interface → Size (Ctrl + / Ctrl − / Ctrl 0) shrinks the 1080p layout to fit more.
                    val prefsVersion by uiPrefs.changes.collectAsState()
                    val interfaceSize = remember(prefsVersion) { uiPrefs.interfaceSize }
                    val scale = constraints.maxHeight / 1080f * interfaceSize.factor
                    val windowDensity = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(scale, fontScale = 1f),
                        dev.silage.app.playback.LocalWindowDensity provides windowDensity,
                        dev.silage.app.playback.LocalPlaybackFullscreen provides playbackFullscreen,
                        dev.silage.app.playback.LocalDvHelperAuthorization provides dvAuthorization,
                        // Right-click menus (cards, the search box) in the app's dark style.
                        androidx.compose.foundation.LocalContextMenuRepresentation provides androidx.compose.foundation.DarkDefaultContextMenuRepresentation,
                    ) {
                        // The window opens on the startup animation while the start
                        // route (server, sign-in, profile) resolves underneath.
                        var navigator by remember { mutableStateOf<AppNavigator?>(null) }
                        LaunchedEffect(Unit) { navigator = AppNavigator(withContext(Dispatchers.IO) { startRoute(koin) }) }
                        // Native canvases and initial page focus must not bypass the intro.
                        navigator?.takeIf { splashDone }?.let { nav ->
                            // The mouse's Back button, Browser Back and a remote's Back go back, as in Explorer and
                            // browsers: as the Back key, so open dialogs close first and the player steps back.
                            DisposableEffect(window) {
                                val backInputs = dev.silage.app.nav.BackInput.install(window)
                                onDispose { backInputs.close() }
                            }
                            // A clicked notification toast: bring the window forward on the inbox.
                            LaunchedEffect(nav) {
                                liveFeatures.openInbox.collect {
                                    if (windowState.isMinimized) windowState.isMinimized = false
                                    window.toFront(); window.requestFocus()
                                    if (nav.route.value !is Route.Playing) nav.go(Route.Notifications)
                                }
                            }
                            val route by nav.route.collectAsState()
                            // The profile's interface settings (badges, spoilers…), loaded once signed in to a profile.
                            val profileUi = remember { koin.get<dev.silage.app.settings.ProfileUiStore>() }
                            val ui by profileUi.state.collectAsState()
                            val signedIn = route !in setOf(Route.Connect, Route.SignIn, Route.Profiles)
                            LaunchedEffect(signedIn) { if (signedIn) profileUi.load() else koin.get<dev.silage.app.shell.SidebarStore>().clear() }
                            CompositionLocalProvider(dev.silage.app.settings.LocalProfileUi provides ui) {
                            KeyRoot(route, onBack = nav::back, onQuit = closeSilage,
                                onFullscreen = if (isWindows) ({ playbackFullscreen.toggle() }) else null, onSearch = { if (route !in setOf(Route.Connect, Route.SignIn, Route.Profiles, Route.Search)) nav.go(Route.Search) }) { EntryHost(nav) { entry -> dev.silage.app.nav.BrowseNavigation(nav) { AppRoutes(koin, nav, entry.route) } } }
                            }
                        }
                        if (!splashDone) SplashScreen(ready = navigator != null) { splashDone = true }
                        if (System.getProperty("os.name").startsWith("Windows")) {
                            val storageError by koin.get<org.siloserver.silo.platform.RememberedSessionStore>().error.collectAsState()
                            storageError?.let { message ->
                                androidx.compose.material3.Text(message, color = Color.White,
                                    modifier = Modifier.background(Color(0xFF702626)))
                            }
                        }
                    }
                }
            }
        }
    }
      }
}

@Composable
private fun BorderlessStartup(window: java.awt.Frame, isWindows: Boolean, borderless: Boolean) {
    val fullscreen = remember(window) { dev.silage.app.playback.PlaybackFullscreen(window) }
    LaunchedEffect(window, isWindows, borderless) {
        if (isWindows && borderless) {
            while (!window.isShowing) delay(20)
            fullscreen.enter()
        } else if (isWindows) {
            // A normal window: the dark title bar, once the native window exists.
            withTimeoutOrNull(2000) { while (!window.isDisplayable) delay(20) }
            WindowsChrome.useDarkTitleBar(window)
        }
    }
    // Fullscreen (borderless start, or F11 / the player's button) refits to the monitor it is on:
    // moving it with Win+Shift+Arrow, or a display change, would otherwise leave the old size.
    if (isWindows) DisposableEffect(window) {
        val follow = dev.silage.app.playback.WindowsMonitors.followMonitor(window)
        // Moving to a monitor scaled differently: redraw at the new size (see ScreenScaleRefresh).
        val scale = ScreenScaleRefresh.follow(window)
        onDispose { follow.close(); scale.close() }
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
        if (dev == "favorites") return Route.Favorites
        if (dev == "history") return Route.History
        if (dev == "library") {
            val libs = koin.get<org.siloserver.silo.repository.PersonalDataRepository>().listUserLibraries()
            (libs as? ApiResult.Success)?.data?.firstOrNull()?.let { return Route.Library(it.id, it.name) }
        }
        return Route.Home
    }
    return Route.Profiles
}

/** Outlives the sign-in screen, for withdrawing an abandoned code on the server. */
private val signInBackground = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

/** Sign-in with a code approved on another device (SSO servers), mirroring the Android TV app. */
@Composable
private fun rememberDeviceSignIn(koin: Koin, auth: AuthRepository, onSignedIn: (profileSelected: Boolean) -> Unit): dev.silage.app.signin.DeviceSignInPresenter {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val signedIn by androidx.compose.runtime.rememberUpdatedState(onSignedIn)
    return androidx.compose.runtime.remember {
        val tokens = koin.get<TokenManager>()
        dev.silage.app.signin.DeviceSignInPresenter(
            scope = scope,
            cancelScope = signInBackground,
            newMachine = { url ->
                val device = runCatching { koin.get<org.siloserver.silo.network.DeviceMetadataProvider>().current() }.getOrNull()
                org.siloserver.silo.repository.DeviceSignInMachine(koin.get(), url, device?.name, device?.platform)
            },
            captureExpectation = { tokens.captureAccountSessionExpectation() },
            commit = { response, expected -> dev.silage.app.signin.commitDeviceApproval(tokens, { auth.onSessionCommitted() }, response, expected) },
            onSignedIn = { signedIn(it) },
        )
    }
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
            remembered = koin.get<dev.silage.app.signin.RememberSignInSettings>().enabled,
            onRememberChanged = koin.get<dev.silage.app.signin.RememberSignInSettings>()::setEnabled,
            onChangeServer = { nav.root(Route.Connect) },
            device = rememberDeviceSignIn(koin, auth) { profileSelected -> nav.root(if (profileSelected) Route.Home else Route.Profiles) },
            serverHost = koin.get<ServerRegistry>().activeEntry.value?.url?.let(org.siloserver.silo.model.auth.DeviceCodeFormat::host).orEmpty(),
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
        is Route.Playing -> PlayingScreen(koin, route.contentId, route.fromStart, route.fileId,
            trailer = route.trailer,
            onDone = { if (!nav.back()) nav.root(Route.Home) },
            shuffleId = route.shuffleId,
            onPlayNext = { next, shuffleId -> nav.replace(Route.Playing(next, shuffleId = shuffleId)) })
        Route.Home -> AppShell(nav, koin, route) {
            HomeScreen(koin, onOpen = { nav.go(Route.Detail(it.openTarget)) }, onPlay = { nav.go(Route.Playing(it.openTarget)) })
        }
        is Route.Library -> AppShell(nav, koin, route) {
            LibraryScreen(koin, route.id, route.name, onOpen = { nav.go(Route.Detail(it.openTarget)) }, onOpenCollection = { id, name -> nav.go(Route.Collection(id, name)) },
                onShuffle = { first, shuffleId -> nav.go(Route.Playing(first, shuffleId = shuffleId)) })
        }
        is Route.Collection -> AppShell(nav, koin, route) {
            CollectionScreen(koin, route.id, route.name, personal = route.personal, onOpen = { nav.go(Route.Detail(it.openTarget)) },
                onShuffle = { first, shuffleId -> nav.go(Route.Playing(first, shuffleId = shuffleId)) })
        }
        Route.ForYou -> AppShell(nav, koin, route) {
            dev.silage.app.home.ForYouScreen(koin, onOpen = { nav.go(Route.Detail(it.openTarget)) }, onPlay = { nav.go(Route.Playing(it.openTarget)) })
        }
        Route.Calendar -> AppShell(nav, koin, route) {
            dev.silage.app.calendar.CalendarScreen(koin, onOpen = { id -> nav.go(Route.Detail(id)) })
        }
        is Route.Detail -> DetailScreen(koin, route.contentId, initialSeason = route.season, focusEpisode = route.episode,
            onPlay = { id, fromStart, fileId -> nav.go(Route.Playing(id, fromStart, fileId)) },
            onPerson = { id, name -> nav.go(Route.Person(id, name)) },
            onShuffle = { first, shuffleId -> nav.go(Route.Playing(first, shuffleId = shuffleId)) },
            onTrailer = { trailer -> nav.go(Route.Playing("trailer:${trailer.url}", trailer = trailer)) },
            onOpen = { id -> nav.go(Route.Detail(id)) })
        is Route.Person -> AppShell(nav, koin, route) {
            dev.silage.app.browse.PersonScreen(koin, route.id, route.name, onOpen = { nav.go(Route.Detail(it.openTarget)) })
        }
        Route.Search -> AppShell(nav, koin, route) {
            SearchScreen(koin, onOpen = { nav.go(Route.Detail(it.openTarget)) }, onOpenRequest = { type, id -> nav.go(Route.RequestDetail(type, id)) })
        }
        Route.Requests -> AppShell(nav, koin, route) {
            dev.silage.app.requests.RequestsScreen(koin, onOpenLibrary = { nav.go(Route.Detail(it)) },
                onOpenRequest = { type, id -> nav.go(Route.RequestDetail(type, id)) }, onSearch = { nav.go(Route.Search) })
        }
        is Route.RequestDetail -> dev.silage.app.requests.RequestDetailScreen(koin, route.mediaType, route.tmdbId,
            onOpenLibrary = { nav.go(Route.Detail(it)) }, onOpenRequest = { type, id -> nav.go(Route.RequestDetail(type, id)) })
        Route.Notifications -> AppShell(nav, koin, route) {
            dev.silage.app.notifications.NotificationsScreen(koin, onOpen = { nav.go(Route.Detail(it)) })
        }
        Route.Collections -> AppShell(nav, koin, route) {
            dev.silage.app.browse.CollectionsScreen(koin, onOpenCollection = { id, name -> nav.go(Route.Collection(id, name, personal = true)) })
        }
        Route.Watchlist -> AppShell(nav, koin, route) { WatchlistScreen(koin, onOpen = { nav.go(Route.Detail(it.openTarget)) }) }
        Route.Favorites -> AppShell(nav, koin, route) { dev.silage.app.browse.FavoritesScreen(koin, onOpen = { nav.go(Route.Detail(it.openTarget)) }) }
        Route.History -> AppShell(nav, koin, route) { dev.silage.app.browse.HistoryScreen(koin, onOpen = { nav.go(Route.Detail(it.openTarget)) }) }
        Route.Settings -> AppShell(nav, koin, route) {
            SettingsScreen(koin, onSwitchProfile = { nav.root(Route.Profiles) }, onSwitchServer = { nav.root(Route.Connect) }, onSignedOut = { nav.root(Route.SignIn) })
        }
    }
}

/**
 * Ctrl + / Ctrl − / Ctrl 0 (as in browsers): how to change the interface size,
 * or null for any other key.
 */
internal fun interfaceZoomKey(e: androidx.compose.ui.input.key.KeyEvent): ((dev.silage.app.ui.InterfaceSize) -> dev.silage.app.ui.InterfaceSize)? {
    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || !e.isCtrlPressed) return null
    return when (e.key) {
        androidx.compose.ui.input.key.Key.Equals, androidx.compose.ui.input.key.Key.Plus, androidx.compose.ui.input.key.Key.NumPadAdd -> { s -> s.step(1) }
        androidx.compose.ui.input.key.Key.Minus, androidx.compose.ui.input.key.Key.NumPadSubtract -> { s -> s.step(-1) }
        androidx.compose.ui.input.key.Key.Zero, androidx.compose.ui.input.key.Key.NumPad0 -> { _ -> dev.silage.app.ui.InterfaceSize.Default }
        else -> null
    }
}
