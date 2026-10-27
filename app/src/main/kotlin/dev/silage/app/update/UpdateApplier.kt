package dev.silage.app.update

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory

sealed interface ApplyResult {
    /** The update is installing and Silage is quitting (or about to) to start the new version. */
    data object Restarting : ApplyResult

    /** The person dismissed or failed the password dialog; the update stays ready. */
    data object Cancelled : ApplyResult

    /** No graphical way to ask for a password: show [command] for the person to run themselves. */
    data class NeedsCommand(val command: String) : ApplyResult
    data class Failed(val message: String) : ApplyResult
}

/**
 * Applies a verified update file for this [type]. Everything outside the process is injected:
 * [run] runs a command and returns its exit code, [startDetached] starts one that outlives Silage,
 * [which] finds an executable, [relaunch] starts the Silage launcher detached and [exit] quits.
 */
class UpdateApplier(
    private val type: InstallType,
    private val run: (List<String>) -> Int,
    private val startDetached: (List<String>) -> Unit,
    private val which: (String) -> Path?,
    private val relaunch: () -> Unit,
    private val exit: () -> Unit,
) {
    /** Set once an installer has been started, so quitting afterwards doesn't start another. */
    @Volatile private var installerStarted = false

    /** The command that installs [file]; empty for a tarball, which Silage swaps in itself. */
    fun commandFor(file: Path): List<String> = when (type) {
        is InstallType.WindowsInstaller -> listOf(file.toString()) + WINDOWS_SILENT + "/RELAUNCH"
        InstallType.Deb -> listOf("pkexec", "apt-get", "install", "-y", file.toString())
        InstallType.Rpm -> listOf("pkexec", "dnf", "install", "-y", file.toString())
        InstallType.Pacman -> listOf("pkexec", "pacman", "-U", "--noconfirm", file.toString())
        is InstallType.Tarball, InstallType.Development -> emptyList()
    }

    fun apply(file: Path): ApplyResult = when (type) {
        is InstallType.WindowsInstaller -> applyWindows(file)
        InstallType.Deb, InstallType.Rpm, InstallType.Pacman -> applyPackage(file)
        is InstallType.Tarball -> if (type.writable) swapTarball(type.installDir, file) else ApplyResult.Failed("Silage's folder isn't writable.")
        InstallType.Development -> ApplyResult.Failed("This copy of Silage doesn't update itself.")
    }

    /** A package manager already updated the files: just start the new version. */
    fun restartOnly(): ApplyResult {
        relaunch()
        exit()
        return ApplyResult.Restarting
    }

    /** On quit, a ready update installs silently (and Silage isn't started again). Windows only. */
    fun onExit(state: UpdateState) {
        if (type !is InstallType.WindowsInstaller || installerStarted) return
        val file = (state as? UpdateState.Ready)?.file ?: return
        try {
            installerStarted = true
            startDetached(commandFor(file) - "/RELAUNCH")
        } catch (_: IOException) {
            // Quitting must not fail; the update stays ready for the next start.
        }
    }

    private fun applyWindows(file: Path): ApplyResult {
        // CloseApplications=force closes Silage if it is still running, so quit right away.
        if (installerStarted) return ApplyResult.Restarting
        try {
            installerStarted = true
            startDetached(commandFor(file))
        } catch (e: IOException) {
            installerStarted = false
            return ApplyResult.Failed("Couldn't start the installer: ${e.message}")
        }
        exit()
        return ApplyResult.Restarting
    }

    private fun applyPackage(file: Path): ApplyResult {
        val command = commandFor(file)
        if (which("pkexec") == null) return ApplyResult.NeedsCommand(("sudo " + command.drop(1).joinToString(" ")))
        return when (val code = try { run(command) } catch (e: IOException) { return ApplyResult.Failed("Couldn't run pkexec: ${e.message}") }) {
            0 -> { relaunch(); exit(); ApplyResult.Restarting }
            126, 127 -> ApplyResult.Cancelled
            else -> ApplyResult.Failed("The install failed (exit code $code).")
        }
    }

    /**
     * Extracts [tarball] next to [installDir], then swaps by rename so the launcher path stays the same.
     * The old copy stays as `<dir>.old` until the next start (see [removeOldInstall]).
     */
    private fun swapTarball(installDir: Path, tarball: Path): ApplyResult {
        val staging = installDir.resolveSibling(installDir.fileName.toString() + ".new")
        val old = oldInstall(installDir)
        try {
            deleteRecursively(staging)
            Files.createDirectories(staging)
            val code = run(listOf("tar", "-xzf", tarball.toString(), "-C", staging.toString()))
            if (code != 0) return ApplyResult.Failed("Couldn't unpack the update (tar exit code $code).")
            // The tarball holds silage-<version>/silage/, the app image.
            val image = Files.list(staging).use { dirs -> dirs.filter { it.isDirectory() }.toList() }
                .map { it.resolve("silage") }.firstOrNull { it.isDirectory() }
                ?: return ApplyResult.Failed("The update doesn't contain the app.")
            deleteRecursively(old)
            Files.move(installDir, old)
            try {
                Files.move(image, installDir)
            } catch (e: IOException) {
                Files.move(old, installDir) // Put the working copy back.
                throw e
            }
        } catch (e: IOException) {
            return ApplyResult.Failed("Couldn't replace the installed files: ${e.message}")
        } finally {
            deleteRecursively(staging)
        }
        relaunch()
        exit()
        return ApplyResult.Restarting
    }

    companion object {
        private val WINDOWS_SILENT = listOf("/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART")

        private fun oldInstall(installDir: Path) = installDir.resolveSibling(installDir.fileName.toString() + ".old")

        /** Deletes the previous copy a tarball swap left behind; call once the new version has started. */
        fun removeOldInstall(type: InstallType) {
            if (type is InstallType.Tarball) deleteRecursively(oldInstall(type.installDir))
        }

        private fun deleteRecursively(path: Path) {
            if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
            try {
                Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            } catch (_: IOException) {
                // Best effort: a leftover folder is harmless.
            }
        }

        /** The real process plumbing for [UpdateApplier]. */
        fun forThisProcess(type: InstallType, exit: () -> Unit): UpdateApplier {
            fun launch(command: List<String>) {
                ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.PIPE)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().outputStream.close()
            }
            return UpdateApplier(
                type = type,
                run = { command ->
                    val process = ProcessBuilder(command).inheritIO().start()
                    process.waitFor()
                },
                startDetached = ::launch,
                which = { name ->
                    System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator)
                        .filter { it.isNotBlank() }.map { Path.of(it, name) }.firstOrNull { Files.isExecutable(it) }
                },
                relaunch = { System.getProperty("jpackage.app-path")?.let { launch(listOf(it)) } },
                exit = exit,
            )
        }
    }
}
