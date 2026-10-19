package dev.silage.app

import dev.silage.app.AppDirs.Kind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test fun windowsKeepsAllDataBesideTheExecutableRegardlessOfWorkingDirectory() {
        val dirs = windows()
        Kind.entries.forEach { kind ->
            assertEquals(app.resolve("userdata").resolve(kind.windowsName), dirs.resolve(kind))
        }
        assertFalse(Files.exists(app), "lookup should not create any files")
        assertFalse(Files.exists(working), "launch directory should be untouched")
    }

    @Test fun windowsSourceRunUsesTheDevelopmentDirectory() {
        for (launcher in listOf(null, "", " ")) {
            assertEquals(working.resolve("userdata/Data"), windows(launcher = launcher).resolve(Kind.DATA))
        }
    }

    @Test fun movingTheAppUsesTheMovedSettingsAndIgnoresAppDataAndLegacyFolders() {
        val original = app.resolve("userdata/Config")
        Files.createDirectories(original)
        Files.writeString(original.resolve("ui.json"), "portable settings")
        val local = home.resolve("AppData/Local/Silage/Config")
        val legacy = home.resolve(".config/silage")
        for (dir in listOf(local, legacy)) {
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("ui.json"), "other copy")
        }
        val moved = home.resolve("another drive/Moved Silage")
        Files.createDirectories(moved.parent)
        Files.move(app, moved)
        val dirs = windows(env = mapOf("LOCALAPPDATA" to local.parent.parent.toString()),
            launcher = moved.resolve("silage.exe").toString())

        assertEquals("portable settings", Files.readString(dirs.resolve(Kind.CONFIG).resolve("ui.json")))
        assertTrue(Files.exists(local.resolve("ui.json")))
        assertTrue(Files.exists(legacy.resolve("ui.json")))
        assertEquals(app.resolve("userdata/Config"), windows().resolve(Kind.CONFIG),
            "a fresh copy must not import settings from other installations")
    }

    @Test fun relativeLauncherPathIsRejectedRatherThanUsingTheWorkingDirectory() {
        assertFailsWith<IllegalArgumentException> {
            windows(launcher = "silage.exe").resolve(Kind.DATA)
        }
    }

    @Test fun singleFileLauncherKeepsDataBesideTheOuterExe() {
        val outer = home.resolve("portable app")
        val dirs = windows(env = mapOf("SILAGE_PORTABLE_HOME" to outer.toString()),
            launcher = outer.resolve(".silage-runtime/version/Silage/silage.exe").toString())
        Kind.entries.forEach { kind ->
            assertEquals(outer.resolve("userdata").resolve(kind.windowsName), dirs.resolve(kind))
        }
        assertFailsWith<IllegalArgumentException> {
            windows(env = mapOf("SILAGE_PORTABLE_HOME" to "relative")).resolve(Kind.DATA)
        }
    }

    @Test fun explicitXdgOverridesStillAllowIsolatedWindowsRuns() {
        val isolated = home.resolve("isolated-state")
        val dirs = windows(env = mapOf("XDG_STATE_HOME" to isolated.toString()))
        assertEquals(isolated.resolve("silage"), dirs.resolve(Kind.STATE))
        assertEquals(app.resolve("userdata/Data"), dirs.resolve(Kind.DATA))
    }

    @Test fun kdeDisplayConfigStaysInTheUserConfigDirOnEveryPlatform() {
        assertEquals(home.resolve(".config/kwinoutputconfig.json"), windows().kwinOutputConfig)
        val linux = AppDirs(osName = "Linux", env = mapOf("XDG_CONFIG_HOME" to "/cfg")::get, home = home)
        assertEquals(Path.of("/cfg/kwinoutputconfig.json"), linux.kwinOutputConfig)
    }
}
