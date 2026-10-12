package org.siloserver.silo.network

import kotlinx.coroutines.test.runTest
import org.siloserver.silo.platform.MemoryBlobStore
import org.siloserver.silo.platform.PersistentPreferences
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPersistenceTest {
    @Test fun activeServerAndTokensSurviveRestart() = runTest {
        val blob = MemoryBlobStore()

        val prefs1 = PersistentPreferences(blob)
        val barrier1 = DefaultIdentityTransitionBarrier()
        val registry1 = AndroidServerRegistry(prefs1, barrier1)
        val id = registry1.addOrUpdate("https://media.example:8096")
        registry1.switchTo(id)
        val tokens1 = EncryptedTokenManagerImpl(prefs1, registry1, barrier1)
        tokens1.switchActiveServer(id)
        tokens1.saveTokens("access-1", "refresh-1", expiresIn = 3600)

        // Simulated app restart: fresh objects over the same stored blob.
        val prefs2 = PersistentPreferences(blob)
        val barrier2 = DefaultIdentityTransitionBarrier()
        val registry2 = AndroidServerRegistry(prefs2, barrier2)
        val tokens2 = EncryptedTokenManagerImpl(prefs2, registry2, barrier2)

        assertEquals(id, registry2.activeServerId.value)
        assertEquals("https://media.example:8096", registry2.activeEntry.value?.url)
        assertEquals("refresh-1", tokens2.getRefreshToken())
    }
}
