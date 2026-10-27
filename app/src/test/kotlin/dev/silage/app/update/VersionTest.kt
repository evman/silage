package dev.silage.app.update

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionTest {
    @Test fun parsesTagsWithOrWithoutV() {
        assertEquals(Version(0, 5, 1), Version.parse("v0.5.1"))
        assertEquals(Version(0, 5, 1), Version.parse("0.5.1"))
        assertEquals(Version(12, 0, 30), Version.parse(" 12.0.30\n"))
        assertEquals("0.5.1", Version(0, 5, 1).toString())
    }

    @Test fun devAndGarbageAreNull() {
        for (s in listOf("0.5.0-dev", "0.5.0-rc.1", "", "v", "0.5", "1.2.3.4", "x1.2.3", "1.2.x", "V1.2.3", "99999999999.0.0")) {
            assertNull(Version.parse(s), s)
        }
    }

    @Test fun ordersNumerically() {
        assertTrue(Version.parse("0.10.0")!! > Version.parse("0.9.9")!!)
        assertTrue(Version(1, 0, 0) > Version(0, 99, 99))
        assertTrue(Version(0, 5, 2) > Version(0, 5, 1))
        assertEquals(0, Version(0, 5, 1).compareTo(Version.parse("v0.5.1")!!))
    }

    @Test fun installedVersionIsReadFromVersionTxt() {
        val dir = createTempDirectory("silage-version")
        try {
            val file = dir.resolve("version.txt")
            assertNull(readVersionFile(file))
            file.writeText("0.5.1\n")
            assertEquals(Version(0, 5, 1), readVersionFile(file))
            file.writeText("0.5.0-dev")
            assertNull(readVersionFile(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun versionTxtSitsInTheInstalledAppImage() {
        assertEquals(Path.of("/opt/silage/lib/app/version.txt"), installedVersionFile(InstallType.Deb))
        assertEquals(Path.of("/opt/silage/lib/app/version.txt"), installedVersionFile(InstallType.Rpm))
        assertEquals(Path.of("/opt/silage/lib/app/version.txt"), installedVersionFile(InstallType.Pacman))
        val win = Path.of("C:/Users/me/AppData/Local/Programs/Silage")
        assertEquals(win.resolve("app").resolve("version.txt"), installedVersionFile(InstallType.WindowsInstaller(win)))
        val tar = Path.of("/home/me/silage")
        assertEquals(tar.resolve("lib/app/version.txt"), installedVersionFile(InstallType.Tarball(tar, true)))
        assertNull(installedVersionFile(InstallType.Development))
    }
}
