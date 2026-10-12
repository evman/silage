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

class SecureBlobStoreTest {
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
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
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
}
