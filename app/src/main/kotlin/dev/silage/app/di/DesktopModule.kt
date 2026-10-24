package dev.silage.app.di

import dev.silage.app.XdgPaths
import org.koin.dsl.module
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.DeviceMetadataProvider
import org.siloserver.silo.network.DurableLoginAuthorityProvider
import org.siloserver.silo.network.EncryptedTokenManagerImpl
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.apiv2.PlaybackV2Api
import org.siloserver.silo.platform.BlobStore
import org.siloserver.silo.platform.FilePlaybackJournalStore
import org.siloserver.silo.platform.PersistentPreferences
import org.siloserver.silo.platform.SecureBlobStore
import org.siloserver.silo.platform.SharedPreferences
import org.siloserver.silo.platform.UnavailableMembershipPort
import org.siloserver.silo.repository.port.MembershipPort
import org.siloserver.silo.repository.PlaybackJournalStore
import org.siloserver.silo.repository.SequencedPlayback
import org.siloserver.silo.viewmodel.HomeViewModel
import java.util.UUID

/**
 * Desktop bindings. Load AFTER sharedModules(): same-type bindings here
 * replace upstream's in-memory defaults (TokenManagerImpl), exactly as the
 * Android TV app's module does.
 */
val desktopModule = module {
    single { org.siloserver.silo.platform.RememberedSessionStore(XdgPaths.data.resolve("session.json")) }
    single<BlobStore> {
        if (System.getProperty("os.name").startsWith("Windows")) get<org.siloserver.silo.platform.RememberedSessionStore>()
        else org.siloserver.silo.platform.LinuxSessionStore(SecureBlobStore.create(XdgPaths.data.resolve("session.json")))
    }
    single { dev.silage.app.signin.RememberSignInSettings(get(), get()) }
    single<SharedPreferences> { PersistentPreferences(get()) }
    single<ServerRegistry> { AndroidServerRegistry(get(), get()) }
    single { EncryptedTokenManagerImpl(get(), get(), get()) }
    single<TokenManager> { get<EncryptedTokenManagerImpl>() }
    single<DurableLoginAuthorityProvider> { get<EncryptedTokenManagerImpl>() }
    single<DeviceMetadataProvider> { DesktopDeviceMetadataProvider(XdgPaths.config.resolve("device-id")) }
    // Playback session plumbing, mirroring android-shared's playerInfraModule.
    single<PlaybackJournalStore> { FilePlaybackJournalStore(XdgPaths.state.resolve("playback-v2")) }
    single { PlaybackV2Api(get(), get()) }
    single { SequencedPlayback(get(), get(), get(), get()) { UUID.randomUUID().toString() } }
    // Plan 2 replaces this with a durable outbox when favorite/watchlist UI arrives.
    single<MembershipPort> { UnavailableMembershipPort }
    factory { HomeViewModel(get(), get()) }
    single { dev.silage.app.ui.UiPrefs(XdgPaths.config.resolve("ui.json")) }
    single { dev.silage.app.settings.ProfileUiStore(get()) }
    single { dev.silage.app.shell.LiveFeatures(get(), get(), get()) { where, t -> dev.silage.app.CrashLog().record(where, t) } }
}
