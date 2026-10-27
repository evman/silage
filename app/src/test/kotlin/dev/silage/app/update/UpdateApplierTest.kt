package dev.silage.app.update

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdateApplierTest {
    private val file = Path.of("/cache/silage_0.6.0_amd64.deb")
    private val ran = mutableListOf<List<String>>()
    private val detached = mutableListOf<List<String>>()
    private var relaunched = 0
    private var exited = 0

    private fun applier(
        type: InstallType,
        exitCode: Int = 0,
        pkexec: Boolean = true,
        run: ((List<String>) -> Int)? = null,
    ) = UpdateApplier(
        type = type,
        run = run ?: { command -> ran += command; exitCode },
        startDetached = { detached += it },
        which = { name -> if (name == "pkexec" && pkexec) Path.of("/usr/bin/pkexec") else null },
        relaunch = { relaunched++ },
        exit = { exited++ },
    )

    private val windows = InstallType.WindowsInstaller(Path.of("C:\\Users\\me\\AppData\\Local\\Programs\\Silage"))
    private val setup = Path.of("C:\\cache\\Silage-0.6.0-Setup.exe")

    @Test fun windowsStartsTheSilentInstallerAndExits() {
        val result = applier(windows).apply(setup)
        assertEquals(ApplyResult.Restarting, result)
        assertEquals(listOf(listOf(setup.toString(), "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/RELAUNCH")), detached)
        assertEquals(1, exited)
        assertEquals(0, relaunched) // the installer relaunches Silage itself
    }

    @Test fun windowsQuitWithAReadyUpdateInstallsWithoutRelaunch() {
        val applier = applier(windows)
        applier.onExit(UpdateState.Ready(Version.parse("0.6.0")!!, setup))
        assertEquals(listOf(listOf(setup.toString(), "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART")), detached)
        assertEquals(0, exited)
        applier.onExit(UpdateState.Ready(Version.parse("0.6.0")!!, null))
        applier.onExit(UpdateState.Idle)
        assertEquals(1, detached.size)
    }

    @Test fun quittingAfterApplyDoesNotStartASecondInstaller() {
        val applier = applier(windows)
        applier.apply(setup)
        applier.onExit(UpdateState.Ready(Version.parse("0.6.0")!!, setup))
        assertEquals(1, detached.size)
    }

    @Test fun quittingOutsideWindowsNeverInstalls() {
        applier(InstallType.Deb).onExit(UpdateState.Ready(Version.parse("0.6.0")!!, file))
        assertTrue(detached.isEmpty() && ran.isEmpty())
    }

    @Test fun debRpmAndPacmanUsePkexecWithTheirPackageManager() {
        assertEquals(listOf("pkexec", "apt-get", "install", "-y", file.toString()), applier(InstallType.Deb).commandFor(file))
        assertEquals(listOf("pkexec", "dnf", "install", "-y", file.toString()), applier(InstallType.Rpm).commandFor(file))
        assertEquals(listOf("pkexec", "pacman", "-U", "--noconfirm", file.toString()), applier(InstallType.Pacman).commandFor(file))
    }

    @Test fun successRelaunches() {
        assertEquals(ApplyResult.Restarting, applier(InstallType.Deb).apply(file))
        assertEquals(listOf(listOf("pkexec", "apt-get", "install", "-y", file.toString())), ran)
        assertEquals(1, relaunched)
        assertEquals(1, exited)
    }

    @Test fun cancelledPasswordKeepsTheUpdate() {
        assertEquals(ApplyResult.Cancelled, applier(InstallType.Rpm, exitCode = 126).apply(file))
        assertEquals(0, relaunched)
        assertEquals(0, exited)
    }

    // Linux package-manager commands; Windows paths would be quoted for their backslashes.
    @EnabledOnOs(OS.LINUX)
    @Test fun noPolkitAgentShowsTheCommand() {
        // pkexec exits 127 when there's no authentication agent (game mode, kiosks, bare window managers).
        assertEquals(ApplyResult.NeedsCommand("sudo dnf install -y $file"), applier(InstallType.Rpm, exitCode = 127).apply(file))
        assertEquals(0, relaunched)
        assertEquals(0, exited)
    }

    // Linux package-manager commands; Windows paths would be quoted for their backslashes.
    @EnabledOnOs(OS.LINUX)
    @Test fun shownCommandQuotesPathsWithSpaces() {
        val spaced = Path.of("/home/me/My Cache/it's/silage_0.6.0_amd64.deb")
        assertEquals(
            ApplyResult.NeedsCommand("sudo apt-get install -y '/home/me/My Cache/it'\\''s/silage_0.6.0_amd64.deb'"),
            applier(InstallType.Deb, pkexec = false).apply(spaced),
        )
        assertEquals(
            ApplyResult.NeedsCommand("sudo apt-get install -y '/home/me/My Cache/it'\\''s/silage_0.6.0_amd64.deb'"),
            applier(InstallType.Deb, exitCode = 127).apply(spaced),
        )
    }

    // Linux package-manager commands; Windows paths would be quoted for their backslashes.
    @EnabledOnOs(OS.LINUX)
    @Test fun withoutPkexecShowsTheCommand() {
        assertEquals(ApplyResult.NeedsCommand("sudo apt-get install -y $file"), applier(InstallType.Deb, pkexec = false).apply(file))
        assertEquals(ApplyResult.NeedsCommand("sudo dnf install -y $file"), applier(InstallType.Rpm, pkexec = false).apply(file))
        assertEquals(ApplyResult.NeedsCommand("sudo pacman -U --noconfirm $file"), applier(InstallType.Pacman, pkexec = false).apply(file))
        assertTrue(ran.isEmpty())
        assertEquals(0, exited)
    }

    @Test fun failedInstallReportsAndDoesNotRelaunch() {
        val result = applier(InstallType.Deb, exitCode = 1).apply(file)
        assertIs<ApplyResult.Failed>(result)
        assertEquals(0, relaunched)
        assertEquals(0, exited)
    }

    @Test fun writableTarballSwapsAndRelaunches() {
        val root = createTempDirectory("applier")
        val installDir = root.resolve("lib/silage").createDirectories()
        installDir.resolve("version.txt").writeText("old")
        val tarball = root.resolve("silage-0.6.0-linux-x86_64.tar.gz").also { it.writeText("tar") }
        // The fake tar lays out what the release tarball holds: silage-<v>/silage/ is the app image.
        val fakeTar: (List<String>) -> Int = { command ->
            ran += command
            val target = Path.of(command[command.indexOf("-C") + 1])
            target.resolve("silage-0.6.0/silage").createDirectories().resolve("version.txt").writeText("new")
            0
        }
        val result = applier(InstallType.Tarball(installDir, writable = true), run = fakeTar).apply(tarball)
        assertEquals(ApplyResult.Restarting, result)
        assertEquals("new", installDir.resolve("version.txt").readText())
        assertEquals("old", installDir.resolveSibling("silage.old").resolve("version.txt").readText())
        assertEquals(1, relaunched)
        assertEquals(1, exited)
        assertEquals(listOf("silage", "silage.old"), Files.list(installDir.parent).use { s -> s.map { it.fileName.toString() }.sorted().toList() })
    }

    @Test fun tarballSwapReplacesAnOlderLeftover() {
        val root = createTempDirectory("applier")
        val installDir = root.resolve("silage").createDirectories().also { it.resolve("v").writeText("current") }
        root.resolve("silage.old").createDirectories().resolve("v").writeText("ancient")
        val fakeTar: (List<String>) -> Int = { command ->
            Path.of(command[command.indexOf("-C") + 1]).resolve("silage-0.6.0/silage").createDirectories().resolve("v").writeText("new")
            0
        }
        applier(InstallType.Tarball(installDir, true), run = fakeTar).apply(root.resolve("a.tar.gz"))
        assertEquals("current", root.resolve("silage.old/v").readText())
        assertEquals("new", installDir.resolve("v").readText())
    }

    @Test fun failedExtractionLeavesTheInstallUntouched() {
        val root = createTempDirectory("applier")
        val installDir = root.resolve("silage").createDirectories().also { it.resolve("v").writeText("current") }
        val result = applier(InstallType.Tarball(installDir, true), run = { 2 }).apply(root.resolve("a.tar.gz"))
        assertIs<ApplyResult.Failed>(result)
        assertEquals("current", installDir.resolve("v").readText())
        assertFalse(root.resolve("silage.old").exists())
        assertEquals(0, exited)
    }

    @Test fun cleanupNeverThrows() {
        // A path that can't be walked (here a file where a folder is expected, below a regular file)
        // must not escape removeOldInstall, or it takes Updater.start() down with it.
        val root = createTempDirectory("applier")
        val installDir = root.resolve("silage").createDirectories()
        val old = installDir.resolveSibling("silage.old").createDirectories()
        val locked = old.resolve("locked").createDirectories().also { it.resolve("f").writeText("x") }
        locked.toFile().setExecutable(false)
        locked.toFile().setReadable(false)
        try {
            UpdateApplier.removeOldInstall(InstallType.Tarball(installDir, true))
        } finally {
            locked.toFile().setReadable(true)
            locked.toFile().setExecutable(true)
        }
        assertTrue(installDir.exists())
    }

    @Test fun cleanupRemovesTheOldInstall() {
        val installDir = createTempDirectory("applier").resolve("silage").createDirectories()
        val old = installDir.resolveSibling("silage.old").createDirectories().also { it.resolve("v").writeText("x") }
        UpdateApplier.removeOldInstall(InstallType.Tarball(installDir, true))
        assertFalse(old.exists())
        assertTrue(installDir.exists())
    }

    @Test fun readOnlyTarballAndDevelopmentCannotApply() {
        assertIs<ApplyResult.Failed>(applier(InstallType.Tarball(Path.of("/opt/silage"), false)).apply(file))
        assertIs<ApplyResult.Failed>(applier(InstallType.Development).apply(file))
    }

    @Test fun restartToFinishOnlyRelaunches() {
        assertEquals(ApplyResult.Restarting, applier(InstallType.Deb).restartOnly())
        assertEquals(1, relaunched)
        assertEquals(1, exited)
        assertTrue(ran.isEmpty() && detached.isEmpty())
    }
}
