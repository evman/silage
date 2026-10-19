package org.siloserver.silo.platform

import kotlin.test.*

class LinuxSessionStoreTest {
    private class Memory : BlobStore {
        var raw: String? = null
        var writable = true
        override fun read() = raw
        override fun write(contents: String): Boolean { if (!writable) return false; raw = contents; return true }
    }

    @Test fun consentIsPerServerAndOptOutKeepsCurrentSessionOnly() {
        val disk = Memory()
        val live = PersistentPreferences(LinuxSessionStore(disk))
        assertTrue(live.edit().putBoolean("one.remember_sign_in", true)
            .putString("one.refresh_token", "dummy-one").putString("two.refresh_token", "dummy-two")
            .putString("server_registry.v1", "metadata").commit())
        var restart = PersistentPreferences(LinuxSessionStore(disk))
        assertEquals("dummy-one", restart.getString("one.refresh_token", null))
        assertNull(restart.getString("two.refresh_token", null))
        assertTrue(live.edit().putBoolean("one.remember_sign_in", false).commit())
        assertEquals("dummy-one", live.getString("one.refresh_token", null))
        restart = PersistentPreferences(LinuxSessionStore(disk))
        assertNull(restart.getString("one.refresh_token", null))
        assertEquals("metadata", restart.getString("server_registry.v1", null))
        assertFalse(disk.raw!!.contains("dummy"))
    }

    @Test fun oldLinuxSignInsMigrateButExplicitOptOutIsRespected() {
        val disk = Memory().apply { raw = """{"one.refresh_token":{"s":"dummy-one"},"two.refresh_token":{"s":"dummy-two"},"two.remember_sign_in":{"b":false}}""" }
        val restart = PersistentPreferences(LinuxSessionStore(disk))
        assertTrue(restart.getBoolean("one.remember_sign_in", false))
        assertEquals("dummy-one", restart.getString("one.refresh_token", null))
        assertNull(restart.getString("two.refresh_token", null))
        assertFalse(disk.raw!!.contains("dummy-two"))
    }

    @Test fun failedOptOutDoesNotPretendSavedTokensWereRemovedAndLogoutDoesNotResurrect() {
        val disk = Memory()
        val live = PersistentPreferences(LinuxSessionStore(disk))
        assertTrue(live.edit().putBoolean("one.remember_sign_in", true).putString("one.refresh_token", "dummy").commit())
        disk.writable = false
        assertFalse(live.edit().putBoolean("one.remember_sign_in", false).commit())
        assertTrue(live.getBoolean("one.remember_sign_in", false))
        disk.writable = true
        assertTrue(live.edit().remove("one.refresh_token").commit())
        assertNull(PersistentPreferences(LinuxSessionStore(disk)).getString("one.refresh_token", null))
    }
}
