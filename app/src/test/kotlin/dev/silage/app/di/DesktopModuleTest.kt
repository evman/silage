package dev.silage.app.di

import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.siloserver.silo.di.sharedModules
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.platform.BlobStore
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.PlaybackRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.viewmodel.HomeViewModel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopModuleTest {
    @AfterTest fun tearDown() = stopKoin()

    @Test fun graphResolves() {
        val memory = object : BlobStore {
            var s: String? = null
            override fun read() = s
            override fun write(contents: String): Boolean { s = contents; return true }
        }
        val koin = startKoin {
            modules(sharedModules() + desktopModule + module { single<BlobStore> { memory } })
        }.koin
        koin.get<AuthRepository>()
        koin.get<ProfileRepository>()
        koin.get<PlaybackRepository>()
        koin.get<ServerRegistry>()
        koin.get<HomeViewModel>()
        assertEquals("EncryptedTokenManagerImpl", koin.get<TokenManager>()::class.simpleName)
    }
}
