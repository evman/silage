package org.siloserver.silo.platform

import java.nio.file.Files
import kotlin.test.*

class RememberedSessionStoreTest {
    @Test fun legacyPlaintextIsRetiredOnReadWithoutRestoringUnconsentedTokens() {
        val dir = Files.createTempDirectory("silage-legacy")
        try {
            val path = dir.resolve("session.json")
            assertTrue(FileBlobStore(path).write("""{"server_url":{"s":"http://127.0.0.1:9"},"fake.refresh_token":{"s":"dummy-legacy-secret"}}"""))
            val prefs = PersistentPreferences(RememberedSessionStore(path))
            assertNull(prefs.getString("fake.refresh_token", null))
            assertEquals("http://127.0.0.1:9", prefs.getString("server_url", null))
            assertFalse(Files.readString(path).contains("dummy-legacy-secret"))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun corruptCiphertextKeepsServerAndPreferenceButNeverRestoresAuthentication() {
        val path = Files.createTempDirectory("silage-corrupt").resolve("session.json")
        val prefs = PersistentPreferences(RememberedSessionStore(path))
        assertTrue(prefs.edit().putBoolean("fake.remember_sign_in", true)
            .putString("server_registry.v1", "safe-metadata")
            .putString("fake.refresh_token", "dummy-secret").commit())
        val envelope = kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(path)) as kotlinx.serialization.json.JsonObject
        Files.writeString(path, kotlinx.serialization.json.JsonObject(envelope + ("protected" to kotlinx.serialization.json.JsonPrimitive("broken"))).toString())
        val store = RememberedSessionStore(path)
        val restarted = PersistentPreferences(store)
        assertEquals("safe-metadata", restarted.getString("server_registry.v1", null))
        assertTrue(restarted.getBoolean("fake.remember_sign_in", false))
        assertNull(restarted.getString("fake.refresh_token", null))
        assertNotNull(store.error.value)
    }

    @Test fun disabledNeverPersistsTokensAndDisableKeepsLiveSession() {
        val path = Files.createTempDirectory("silage-disable").resolve("session.json")
        val prefs = PersistentPreferences(RememberedSessionStore(path))
        assertTrue(prefs.edit().putString("fake.refresh_token", "dummy-secret").commit())
        assertNull(PersistentPreferences(RememberedSessionStore(path)).getString("fake.refresh_token", null))
        assertTrue(prefs.edit().putBoolean("fake.remember_sign_in", true).commit())
        assertEquals("dummy-secret", PersistentPreferences(RememberedSessionStore(path)).getString("fake.refresh_token", null))
        assertTrue(prefs.edit().putBoolean("fake.remember_sign_in", false).commit())
        assertEquals("dummy-secret", prefs.getString("fake.refresh_token", null))
        assertNull(PersistentPreferences(RememberedSessionStore(path)).getString("fake.refresh_token", null))
        assertFalse(Files.readString(path).contains("protected"))
    }

    @Test fun unavailableEncryptionFailsWithoutPlaintextFallbackAndCanBeDisabled() {
        val path = Files.createTempDirectory("silage-unavailable").resolve("session.json")
        val unavailable = object : SessionCipher {
            override fun protect(bytes: ByteArray): ByteArray = error("dummy-secret must not be surfaced")
            override fun unprotect(bytes: ByteArray): ByteArray = error("unavailable")
        }
        val store = RememberedSessionStore(path, unavailable)
        val prefs = PersistentPreferences(store)
        assertTrue(prefs.edit().putString("fake.refresh_token", "dummy-secret").commit())
        val before = Files.readString(path)
        assertFalse(prefs.edit().putBoolean("fake.remember_sign_in", true).commit())
        assertEquals(before, Files.readString(path))
        assertFalse(store.error.value.orEmpty().contains("dummy-secret"))
        assertTrue(prefs.edit().putBoolean("fake.remember_sign_in", false).commit())
        assertNull(store.error.value)
    }

    @Test fun serverScopesAndLogoutDoNotResurrectRetainedSecrets() {
        val path = Files.createTempDirectory("silage-logout").resolve("session.json")
        val prefs = PersistentPreferences(RememberedSessionStore(path))
        assertTrue(prefs.edit().putBoolean("one.remember_sign_in", true)
            .putString("one.refresh_token", "dummy-one")
            .putString("two.refresh_token", "dummy-two").commit())
        var restart = PersistentPreferences(RememberedSessionStore(path))
        assertEquals("dummy-one", restart.getString("one.refresh_token", null))
        assertNull(restart.getString("two.refresh_token", null))
        assertTrue(prefs.edit().remove("one.refresh_token").commit())
        restart = PersistentPreferences(RememberedSessionStore(path))
        assertNull(restart.getString("one.refresh_token", null))
        assertFalse(Files.readString(path).contains("protected"))
    }

    @Test fun enabledSurvivesReconstructionWithRealWindowsEncryption() {
        val path = Files.createTempDirectory("silage-remember").resolve("session.json")
        val prefs = PersistentPreferences(RememberedSessionStore(path))
        assertTrue(prefs.edit().putBoolean("fake.remember_sign_in", true)
            .putString("fake.refresh_token", "dummy-refresh-secret-not-real").commit())
        assertFalse(Files.readString(path).contains("dummy-refresh-secret-not-real"))
        val restarted = PersistentPreferences(RememberedSessionStore(path))
        assertEquals("dummy-refresh-secret-not-real", restarted.getString("fake.refresh_token", null))
        assertTrue(restarted.getBoolean("fake.remember_sign_in", false))
    }
}
