package dev.silage.app

import dev.silage.app.AppDirs.Kind
import dev.silage.app.AppDirs.Migration
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppDirsTest {
    private val home: Path = Files.createTempDirectory("silage-home")
    private val local: Path = home.resolve("AppData").resolve("Local")

    private fun windows(env: Map<String, String> = mapOf("LOCALAPPDATA" to local.toString())) =
        AppDirs(osName = "Windows 11", env = env::get, home = home)

    private fun writeLegacy(kind: Kind, name: String, text: String): Path {
        val dir = home.resolve(kind.xdgFallback).resolve("silage")
        Files.createDirectories(dir)
        return Files.writeString(dir.resolve(name), text)
    }

    @Test fun linuxKeepsXdgFallbacksAndOverrides() {
        val linux = AppDirs(osName = "Linux", env = mapOf("XDG_STATE_HOME" to "/srv/state")::get, home = home)
        assertEquals(home.resolve(".config/silage"), linux.resolve(Kind.CONFIG))
        assertEquals(home.resolve(".local/share/silage"), linux.resolve(Kind.DATA))
        assertEquals(Path.of("/srv/state/silage"), linux.resolve(Kind.STATE))
        assertEquals(emptyList(), linux.migrateLegacyWindowsDirs())
    }

    @Test fun freshWindowsInstallUsesLocalAppData() {
        val dirs = windows()
        assertEquals(local.resolve("Silage/Config"), dirs.resolve(Kind.CONFIG))
        assertEquals(local.resolve("Silage/Data"), dirs.resolve(Kind.DATA))
        assertEquals(local.resolve("Silage/State"), dirs.resolve(Kind.STATE))
        assertEquals(local.resolve("Silage/Cache"), dirs.resolve(Kind.CACHE))
        assertEquals(emptyList(), dirs.migrateLegacyWindowsDirs(), "nothing to move")
    }

    @Test fun withoutLocalAppDataFallsBackToTheStandardLocation() {
        assertEquals(home.resolve("AppData/Local/Silage/Data"), windows(env = emptyMap()).resolve(Kind.DATA))
    }

    @Test fun legacyFolderKeepsBeingUsedUntilItIsMoved() {
        writeLegacy(Kind.DATA, "session.json", "{}")
        assertEquals(home.resolve(".local/share/silage"), windows().resolve(Kind.DATA))
    }

    @Test fun migrationMovesLegacyFoldersWithTheirContents() {
        writeLegacy(Kind.DATA, "session.json", "encrypted-session")
        writeLegacy(Kind.CONFIG, "device-id", "device-123")
        writeLegacy(Kind.STATE, "silage.log", "old log")
        val dirs = windows()

        val moves = dirs.migrateLegacyWindowsDirs()

        assertEquals(setOf(Kind.DATA, Kind.CONFIG, Kind.STATE), moves.map { it.kind }.toSet())
        moves.forEach { assertIs<Migration.Moved>(it) }
        assertEquals("encrypted-session", Files.readString(local.resolve("Silage/Data/session.json")))
        assertEquals("device-123", Files.readString(local.resolve("Silage/Config/device-id")))
        assertEquals("old log", Files.readString(local.resolve("Silage/State/silage.log")))
        assertFalse(Files.exists(home.resolve(".local/share/silage")), "legacy folder should be gone")
        assertEquals(local.resolve("Silage/Data"), dirs.resolve(Kind.DATA))
        assertEquals(emptyList(), dirs.migrateLegacyWindowsDirs(), "second launch does nothing")
    }

    @Test fun aFailedMoveLeavesTheOldFolderInUse() {
        val session = writeLegacy(Kind.DATA, "session.json", "encrypted-session")
        val dirs = windows()

        val moves = dirs.migrateLegacyWindowsDirs { _, _ -> throw java.io.IOException("in use") }

        assertIs<Migration.Failed>(moves.single())
        assertEquals("encrypted-session", Files.readString(session))
        assertEquals(home.resolve(".local/share/silage"), dirs.resolve(Kind.DATA))
    }

    @Test fun anExistingAppDataFolderIsNeverOverwritten() {
        writeLegacy(Kind.CONFIG, "ui.json", "old")
        Files.createDirectories(local.resolve("Silage/Config"))
        Files.writeString(local.resolve("Silage/Config/ui.json"), "new")
        val dirs = windows()

        assertEquals(emptyList(), dirs.migrateLegacyWindowsDirs())
        assertEquals("new", Files.readString(local.resolve("Silage/Config/ui.json")))
        assertEquals("old", Files.readString(home.resolve(".config/silage/ui.json")), "legacy left alone")
        assertEquals(local.resolve("Silage/Config"), dirs.resolve(Kind.CONFIG))
    }

    @Test fun explicitXdgOverridesWinOnWindowsAndAreNotMigrated() {
        writeLegacy(Kind.STATE, "silage.log", "old log")
        val isolated = home.resolve("isolated-state")
        val dirs = windows(env = mapOf("LOCALAPPDATA" to local.toString(), "XDG_STATE_HOME" to isolated.toString()))

        assertEquals(isolated.resolve("silage"), dirs.resolve(Kind.STATE))
        assertEquals(emptyList(), dirs.migrateLegacyWindowsDirs().filter { it.kind == Kind.STATE })
        assertTrue(Files.exists(home.resolve(".local/state/silage/silage.log")))
    }

    @Test fun kdeDisplayConfigStaysInTheUserConfigDirOnEveryPlatform() {
        assertEquals(home.resolve(".config/kwinoutputconfig.json"), windows().kwinOutputConfig)
        val linux = AppDirs(osName = "Linux", env = mapOf("XDG_CONFIG_HOME" to "/cfg")::get, home = home)
        assertEquals(Path.of("/cfg/kwinoutputconfig.json"), linux.kwinOutputConfig)
    }
}
