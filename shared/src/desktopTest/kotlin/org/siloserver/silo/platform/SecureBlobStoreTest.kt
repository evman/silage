package org.siloserver.silo.platform

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeKeyring(var value: String? = null) : KeyringAccess {
    override fun get() = value
    override fun set(value: String) { this.value = value }
}

class ThrowingKeyring(private val failRead: Boolean = false, private val failWrite: Boolean = false) : KeyringAccess {
    var value: String? = null
    override fun get(): String? = if (failRead) throw IllegalStateException("wallet locked") else value
    override fun set(value: String) { if (failWrite) throw IllegalStateException("access denied"); this.value = value }
}

class SecureBlobStoreTest {
    @Test fun restartMustNotPreferStaleKeyringOverNewerFallback() {
        val path = Files.createTempDirectory("silage-restart-repro").resolve("session.json")
        val keyring = object : KeyringAccess {
            override fun get() = "old-session"
            override fun set(value: String) { error("simulated unavailable backend") }
        }
        val firstProcess = SecureBlobStore.create(path, keyring) {}
        assertTrue(firstProcess.write("new-session"))
        assertEquals("new-session", firstProcess.read())
        assertEquals("new-session", SecureBlobStore.create(path, keyring) {}.read())
    }

    @Test fun successfulKeyringRecoveryRetiresTheFallback() {
        val path = Files.createTempDirectory("silage-keyring-recovery").resolve("session.json")
        val keyring = FakeKeyring("stale")
        assertTrue(FileBlobStore(path).write("newer"))
        val store = SecureBlobStore.create(path, keyring) {}
        assertEquals("newer", keyring.value)
        assertFalse(Files.exists(path))
        assertTrue(store.write("latest"))
        assertEquals("latest", SecureBlobStore.create(path, keyring) {}.read())
    }

    private val dir = Files.createTempDirectory("silage-test")
    private val file = dir.resolve("data/session.json")

    @Test fun usesKeyringWhenAvailable() {
        val keyring = FakeKeyring()
        val store = SecureBlobStore.create(file, keyring) {}
        assertTrue(store.write("{\"a\":1}"))
        assertEquals("{\"a\":1}", keyring.value)
        assertFalse(Files.exists(file))
    }

    @Test fun fallsBackToOwnerOnlyFileWithWarning() {
        val logs = mutableListOf<String>()
        val store = SecureBlobStore.create(file, keyring = null) { logs += it }
        assertTrue(store.write("{}"))
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        } else {
            val view = Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView::class.java)
            assertTrue(view.acl.filter { it.type() == java.nio.file.attribute.AclEntryType.ALLOW }.all { it.principal() == view.owner })
        }
        assertTrue(logs.single().contains("no Secret Service"))
        assertEquals("{}", SecureBlobStore.create(file, keyring = null) {}.read())
    }

    @Test fun migratesFallbackFileIntoKeyring() {
        FileBlobStore(file).write("{\"old\":true}")
        val keyring = FakeKeyring()
        SecureBlobStore.create(file, keyring) {}
        assertEquals("{\"old\":true}", keyring.value)
        assertFalse(Files.exists(file))
    }

    @Test fun refusedKeyringWriteFallsBackToFile() {
        val logs = mutableListOf<String>()
        val store = SecureBlobStore.create(file, ThrowingKeyring(failWrite = true)) { logs += it }
        assertTrue(store.write("{\"t\":1}"), "write must still succeed")
        assertEquals("{\"t\":1}", FileBlobStore(file).read())
        assertEquals("{\"t\":1}", store.read())
        assertTrue(logs.any { it.contains("keyring refused") }, "logs=$logs")
    }

    @Test fun unreadableKeyringUsesFileInsteadOfLookingEmpty() {
        FileBlobStore(file).write("{\"saved\":true}")
        val store = SecureBlobStore.create(file, ThrowingKeyring(failRead = true)) {}
        assertEquals("{\"saved\":true}", store.read())
    }
}
