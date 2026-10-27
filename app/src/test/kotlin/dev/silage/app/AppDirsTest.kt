package dev.silage.app

import dev.silage.app.AppDirs.Kind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AppDirsTest {
    private val home: Path = Files.createTempDirectory("silage-home")
    private val app = home.resolve("USB drive/Silage")
    private val working = home.resolve("unrelated-launch-directory")

    @AfterTest fun cleanup() { home.toFile().deleteRecursively() }

    private fun windows(
        env: Map<String, String> = emptyMap(),
        launcher: String? = app.resolve("silage.exe").toString(),
    ) = AppDirs(osName = "Windows 11", env = env::get, home = home,
        launcherPath = launcher, workingDirectory = working)

    @Test fun linuxKeepsXdgFallbacksAndOverrides() {
        val linux = AppDirs(osName = "Linux", env = mapOf("XDG_STATE_HOME" to "/srv/state")::get, home = home)
        assertEquals(home.resolve(".config/silage"), linux.resolve(Kind.CONFIG))
        assertEquals(home.resolve(".local/share/silage"), linux.resolve(Kind.DATA))
        assertEquals(Path.of("/srv/state/silage"), linux.resolve(Kind.STATE))
    }

    @Test fun windowsInstalledAppKeepsSettingsInRoamingAppData() {
        val roaming = home.resolve("Users/me/AppData/Roaming")
        val dirs = windows(env = mapOf("APPDATA" to roaming.toString()))
        assertEquals(roaming.resolve("Silage/Config"), dirs.resolve(Kind.CONFIG))
        assertEquals(roaming.resolve("Silage/Data"), dirs.resolve(Kind.DATA))
        assertFalse(Files.exists(app), "lookup should not create any files")
        assertFalse(Files.exists(working), "launch directory should be untouched")
    }

    @Test fun windowsCachesAndStateStayLocal() {
        val local = home.resolve("Users/me/AppData/Local")
        val dirs = windows(env = mapOf("LOCALAPPDATA" to local.toString(), "APPDATA" to "/elsewhere"))
        assertEquals(local.resolve("Silage/State"), dirs.resolve(Kind.STATE))
        assertEquals(local.resolve("Silage/Cache"), dirs.resolve(Kind.CACHE))
    }

    @Test fun windowsFallsBackToTheProfileWhenAppDataIsUnset() {
        for (env in listOf(emptyMap(), mapOf("APPDATA" to " ", "LOCALAPPDATA" to ""))) {
            val dirs = windows(env = env)
            assertEquals(home.resolve("AppData/Roaming/Silage/Config"), dirs.resolve(Kind.CONFIG))
            assertEquals(home.resolve("AppData/Roaming/Silage/Data"), dirs.resolve(Kind.DATA))
            assertEquals(home.resolve("AppData/Local/Silage/State"), dirs.resolve(Kind.STATE))
            assertEquals(home.resolve("AppData/Local/Silage/Cache"), dirs.resolve(Kind.CACHE))
        }
    }

    @Test fun windowsNeverReadsTheOldPortableUserdata() {
        Files.createDirectories(app.resolve("userdata/Config"))
        Files.writeString(app.resolve("userdata/Config/ui.json"), "portable settings")
        val dirs = windows(env = mapOf("SILAGE_PORTABLE_HOME" to app.toString()))
        Kind.entries.forEach { kind ->
            assertFalse(dirs.resolve(kind).startsWith(app), "$kind must not live beside the launcher")
        }
        assertFalse(Files.exists(dirs.resolve(Kind.CONFIG).resolve("ui.json")))
    }

    @Test fun windowsSourceRunUsesTheDevelopmentDirectory() {
        for (launcher in listOf(null, "", " ")) {
            assertEquals(working.resolve("userdata/Data"), windows(launcher = launcher).resolve(Kind.DATA))
        }
    }

    @Test fun relativeLauncherPathIsRejectedRatherThanUsingTheWorkingDirectory() {
        assertFailsWith<IllegalArgumentException> {
            windows(launcher = "silage.exe").resolve(Kind.DATA)
        }
    }

    @Test fun explicitXdgOverridesStillAllowIsolatedWindowsRuns() {
        val isolated = home.resolve("isolated-state")
        val dirs = windows(env = mapOf("XDG_STATE_HOME" to isolated.toString()))
        assertEquals(isolated.resolve("silage"), dirs.resolve(Kind.STATE))
        assertEquals(home.resolve("AppData/Roaming/Silage/Data"), dirs.resolve(Kind.DATA))
    }

    @Test fun kdeDisplayConfigStaysInTheUserConfigDirOnEveryPlatform() {
        assertEquals(home.resolve(".config/kwinoutputconfig.json"), windows().kwinOutputConfig)
        val linux = AppDirs(osName = "Linux", env = mapOf("XDG_CONFIG_HOME" to "/cfg")::get, home = home)
        assertEquals(Path.of("/cfg/kwinoutputconfig.json"), linux.kwinOutputConfig)
    }
}
