package dev.silage.app.update

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class InstallTypeTest {
    private val localAppData = "C:\\Users\\me\\AppData\\Local"
    private val windowsEnv = { name: String -> if (name == "LOCALAPPDATA") localAppData else null }
    private val noEnv = { _: String -> null }
    private val linuxLauncher = "/opt/silage/bin/silage"

    private fun detect(
        osName: String = "Linux",
        version: String = "0.5.0",
        launcherPath: String? = linuxLauncher,
        env: (String) -> String? = noEnv,
        owner: (Path) -> PackageOwner? = { null },
        isWritable: (Path) -> Boolean = { true },
    ) = detectInstallType(osName, version, launcherPath, env, owner, isWritable)

    @Test fun windowsInstalledAppIsTheInstaller() {
        val launcher = "$localAppData\\Programs\\Silage\\silage.exe"
        assertEquals(
            InstallType.WindowsInstaller(Path.of("$localAppData\\Programs\\Silage")),
            detect(osName = "Windows 11", launcherPath = launcher, env = windowsEnv),
        )
    }

    @Test fun windowsElsewhereIsDevelopment() {
        assertEquals(
            InstallType.Development,
            detect(osName = "Windows 11", launcherPath = "D:\\Silage\\silage.exe", env = windowsEnv),
        )
        // A folder that merely starts with the same name is not the install folder.
        assertEquals(
            InstallType.Development,
            detect(osName = "Windows 10", launcherPath = "$localAppData\\Programs\\Silage2\\silage.exe", env = windowsEnv),
        )
        assertEquals(
            InstallType.Development,
            detect(osName = "Windows 11", launcherPath = "$localAppData\\Programs\\Silage\\silage.exe", env = noEnv),
        )
    }

    @Test fun debRpmAndPacmanOwnersMapToTheirTypes() {
        val asked = mutableListOf<Path>()
        fun ownedBy(owner: PackageOwner) = detect(owner = { asked.add(it); owner })
        assertEquals(InstallType.Deb, ownedBy(PackageOwner.Dpkg))
        assertEquals(InstallType.Rpm, ownedBy(PackageOwner.Rpm))
        assertEquals(InstallType.Pacman, ownedBy(PackageOwner.Pacman))
        assertEquals(List(3) { Path.of(linuxLauncher) }, asked)
    }

    @Test fun unownedLinuxInstallIsATarball() {
        val checked = mutableListOf<Path>()
        val launcher = "/home/me/.local/lib/silage/bin/silage"
        assertEquals(
            InstallType.Tarball(Path.of("/home/me/.local/lib/silage"), writable = true),
            detect(launcherPath = launcher, isWritable = { checked.add(it); true }),
        )
        assertEquals(listOf(Path.of("/home/me/.local/lib/silage")), checked)
    }

    @Test fun devRunsNeverUpdate() {
        val owned = { _: Path -> PackageOwner.Dpkg }
        assertEquals(InstallType.Development, detect(version = "0.5.0-dev", owner = owned))
        assertEquals(InstallType.Development, detect(launcherPath = null, owner = owned))
        val windowsLauncher = "$localAppData\\Programs\\Silage\\silage.exe"
        assertEquals(
            InstallType.Development,
            detect(osName = "Windows 11", version = "0.5.0-dev", launcherPath = windowsLauncher, env = windowsEnv),
        )
        assertEquals(InstallType.Development, detect(osName = "Windows 11", launcherPath = null, env = windowsEnv))
    }

    @Test fun readOnlyTarballCanOnlyNotify() {
        assertEquals(
            InstallType.Tarball(Path.of("/opt/silage"), writable = false),
            detect(isWritable = { false }),
        )
    }
}
