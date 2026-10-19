package org.siloserver.silo.platform

import kotlinx.coroutines.runBlocking
import org.siloserver.silo.network.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Every stage is a genuinely new JVM. No network client or real storage is used. */
class RememberRestartProcessTest {
    @Test fun enabledDisabledLogoutInvalidationAndReplacementAcrossProcessRestarts() {
        val dir = Files.createTempDirectory("silage-restart-process")
        try {
            for (stage in listOf("enable", "restore", "disable", "signed-out", "enable", "logout", "signed-out",
                "enable", "invalidate", "signed-out", "enable", "replace", "replacement")) {
                val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString()
                val process = ProcessBuilder(java, "-cp", System.getProperty("silage.test.classpath"),
                    RememberRestartProcess::class.java.name, dir.toString(), stage).redirectErrorStream(true).start()
                assertTrue(process.waitFor(40, TimeUnit.SECONDS), "child JVM timed out at $stage")
                val output = process.inputStream.bufferedReader().readText()
                // Child output is restricted to status, never the fake secret values.
                assertEquals(0, process.exitValue(), "restart stage $stage failed: $output")
                assertTrue(output.contains("REMEMBER_RESTART $stage OK"))
                println("REMEMBER_RESTART $stage OK")
            }
            val file = dir.resolve("session.json")
            if (System.getProperty("os.name").startsWith("Windows")) {
                val view = Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView::class.java)
                assertTrue(view.acl.filter { it.type() == java.nio.file.attribute.AclEntryType.ALLOW }.all { it.principal() == view.owner })
                assertFalse(Files.readString(file).contains("dummy-"))
            } else {
                assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file))
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}

object RememberRestartProcess {
    @JvmStatic fun main(args: Array<String>) {
        try {
            runBlocking {
                val path = Path.of(args[0]).resolve("session.json")
                val store: BlobStore = if (System.getProperty("os.name").startsWith("Windows")) RememberedSessionStore(path)
                    else LinuxSessionStore(FileBlobStore(path))
                val prefs = PersistentPreferences(store)
                val barrier = DefaultIdentityTransitionBarrier()
                val registry = AndroidServerRegistry(prefs, barrier)
                val id = registry.addOrUpdate("http://127.0.0.1:9")
                registry.switchTo(id)
                val tokens = EncryptedTokenManagerImpl(prefs, registry, barrier)
                when (args[1]) {
                    "enable" -> {
                        check(prefs.edit().putBoolean("$id.remember_sign_in", true).commit())
                        tokens.replaceAccountSession(id, null, "dummy-access", "dummy-refresh", 3600,
                            profileId = "dummy-profile", profileToken = "dummy-profile-token")
                    }
                    "restore" -> {
                        check(tokens.getRefreshToken() == "dummy-refresh")
                        check(tokens.getAccessToken() == "dummy-access")
                        check(tokens.getProfileId() == "dummy-profile")
                        check(tokens.getProfileToken() == "dummy-profile-token")
                        check(tokens.snapshotDurableLoginAuthority() != null)
                        check(tokens.loginSessionId(id) != null)
                    }
                    "disable" -> {
                        check(tokens.getRefreshToken() != null)
                        check(prefs.edit().putBoolean("$id.remember_sign_in", false).commit())
                        check(tokens.getRefreshToken() == "dummy-refresh")
                    }
                    "signed-out" -> check(tokens.getRefreshToken() == null)
                    "logout" -> tokens.signOutCurrentServer()
                    "invalidate" -> tokens.invalidateSession()
                    "replace" -> {
                        val previous = tokens.loginSessionId(id)
                        tokens.replaceAccountSession(id, null, "dummy-other-access", "dummy-other-refresh", 3600)
                        check(previous != tokens.loginSessionId(id))
                    }
                    "replacement" -> check(tokens.getRefreshToken() == "dummy-other-refresh")
                    else -> error("unknown stage")
                }
                if (store is RememberedSessionStore) check(store.error.value == null)
            }
            println("REMEMBER_RESTART ${args[1]} OK")
            kotlin.system.exitProcess(0)
        } catch (_: Throwable) {
            // Avoid exception messages or stack traces ever exposing session values.
            System.err.println("REMEMBER_RESTART failed")
            kotlin.system.exitProcess(1)
        }
    }
}
