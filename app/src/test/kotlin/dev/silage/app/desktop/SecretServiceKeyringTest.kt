package dev.silage.app.desktop

import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.siloserver.silo.platform.SecureBlobStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Silage's keyring code against a real Secret Service: GNOME Keyring, unlocked,
 * on a private session bus with a throwaway home. Mocks are what let a broken
 * keyring ship unnoticed from 0.2.0 to 0.5.0, so this runs the real daemon.
 *
 * Without gnome-keyring-daemon the test is skipped, except where
 * SILAGE_REQUIRE_KEYRING_TEST=1 (CI), so it can't silently stop running there.
 * SILAGE_GNOME_KEYRING_DAEMON / SILAGE_GNOME_KEYRING_LIBS point at an unpacked copy.
 */
class SecretServiceKeyringTest {
    private class Desktop(val address: String, private val processes: List<Process>, val home: Path) : AutoCloseable {
        fun connect(): DBusConnection = DBusConnectionBuilder.forAddress(address).withShared(false).build()
        override fun close() = processes.reversed().forEach { it.destroy(); it.waitFor(5, TimeUnit.SECONDS) }
    }

    private fun daemonPath(): String? = System.getenv("SILAGE_GNOME_KEYRING_DAEMON")
        ?: System.getenv("PATH").orEmpty().split(':').map { Path.of(it, "gnome-keyring-daemon") }.firstOrNull { Files.isExecutable(it) }?.toString()

    /**
     * A bare private bus: no service directories, so nothing (such as the
     * desktop's own keyring, with the real home folder) is ever auto-started on it.
     */
    private fun bus(): Pair<Process, String> {
        val config = Files.createTempFile("bus", ".conf")
        config.writeText("""
            <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
             "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
            <busconfig><type>session</type><listen>unix:tmpdir=/tmp</listen><auth>EXTERNAL</auth>
              <policy context="default"><allow send_destination="*" eavesdrop="true"/><allow eavesdrop="true"/><allow own="*"/></policy>
            </busconfig>
        """.trimIndent())
        val daemon = ProcessBuilder("dbus-daemon", "--config-file=$config", "--nofork", "--print-address=1").start()
        return daemon to daemon.inputStream.bufferedReader().readLine()
    }

    /** A private bus with an unlocked keyring, or null (skip) where none can run. */
    private fun withKeyring(test: (Desktop) -> Unit) {
        if (!System.getProperty("os.name").startsWith("Linux")) return
        val keyring = daemonPath() ?: run {
            if (System.getenv("SILAGE_REQUIRE_KEYRING_TEST") == "1") fail("gnome-keyring-daemon is required for the keyring test")
            return
        }
        val home = Files.createTempDirectory("keyring-home")
        val runtime = Files.createDirectories(home.resolve("run"))
        runtime.toFile().setReadable(false, false); runtime.toFile().setReadable(true, true)
        val (bus, address) = bus()
        val env = ProcessBuilder(keyring, "--foreground", "--unlock", "--components=secrets").apply {
            environment().clear()
            environment().putAll(mapOf("PATH" to "/usr/bin:/bin", "HOME" to home.toString(), "XDG_RUNTIME_DIR" to runtime.toString(),
                "XDG_DATA_HOME" to home.resolve("data").toString(), "DBUS_SESSION_BUS_ADDRESS" to address))
            System.getenv("SILAGE_GNOME_KEYRING_LIBS")?.let { environment()["LD_LIBRARY_PATH"] = it }
            redirectErrorStream(true)
        }.start()
        env.outputStream.use { it.write("test".toByteArray()) } // the login keyring's password: unlocks it
        Desktop(address, listOf(bus, env), home).use { desktop ->
            val deadline = System.nanoTime() + 10_000_000_000
            while (SecretServiceKeyring.system(desktop::connect) == null) {
                check(System.nanoTime() < deadline) { "gnome-keyring did not start" }
                Thread.sleep(50)
            }
            test(desktop)
        }
    }

    @Test fun savesReadsAndReplacesTheSignInInARealKeyring() = withKeyring { desktop ->
        val keyring = assertNotNull(SecretServiceKeyring.system(desktop::connect))
        assertNull(keyring.get(), "nothing saved yet")
        keyring.set("""{"access_token":"first"}""")
        assertEquals("""{"access_token":"first"}""", keyring.get())
        keyring.set("second ✓")
        assertEquals("second ✓", keyring.get())
        // Another process (a new connection) sees the same single entry.
        assertEquals("second ✓", assertNotNull(SecretServiceKeyring.system(desktop::connect)).get())
        assertEquals(1, keyring.count(), "replacing never leaves duplicates behind")
    }

    @Test fun anEarlierFileSaveMovesIntoTheKeyring() = withKeyring { desktop ->
        val file = desktop.home.resolve("session.json")
        file.writeText("from-the-file")
        val store = SecureBlobStore.create(file, SecretServiceKeyring.system(desktop::connect)) {}
        assertEquals("from-the-file", store.read())
        assertTrue(store.write("from-the-file"))
        assertTrue(store.inKeyring)
        assertFalse(file.exists(), "the plain file is retired once the keyring holds the sign-in")
        assertEquals("from-the-file", SecretServiceKeyring.system(desktop::connect)!!.get())
    }

    @Test fun aLockedKeyringFailsInsteadOfLookingEmpty() = withKeyring { desktop ->
        val keyring = assertNotNull(SecretServiceKeyring.system(desktop::connect, promptTimeoutMs = 2_000))
        keyring.set("secret")
        keyring.lockAll()
        // No one can answer the unlock prompt here: an error, never "signed out".
        assertFailsWith<Exception> { keyring.get() }
    }

    @Test fun aBusWithoutAKeyringIsReportedAsNone() {
        if (!System.getProperty("os.name").startsWith("Linux")) return
        val (bus, address) = bus()
        try {
            assertNull(SecretServiceKeyring.system({ DBusConnectionBuilder.forAddress(address).withShared(false).build() }))
        } finally { bus.destroy(); bus.waitFor() }
    }
}
