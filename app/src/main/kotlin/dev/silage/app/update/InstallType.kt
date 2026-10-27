package dev.silage.app.update

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** How this copy of Silage was installed, which decides how (and whether) it updates. */
sealed interface InstallType {
    /** The per-user setup in %LOCALAPPDATA%\Programs\Silage: updates by running a newer setup. */
    data class WindowsInstaller(val installDir: Path) : InstallType

    /** The system package manager updates these, through the Silage repository. */
    data object Deb : InstallType
    data object Rpm : InstallType
    data object Pacman : InstallType

    /** The portable build; Silage can replace it only when [installDir] is [writable]. */
    data class Tarball(val installDir: Path, val writable: Boolean) : InstallType

    /** Run from a build tree, a dev build or an unknown location: never updates. */
    data object Development : InstallType
}

enum class PackageOwner { Dpkg, Rpm, Pacman }

/**
 * Works out the [InstallType] from the OS, this build's [version] and the jpackage launcher
 * ([launcherPath], from `jpackage.app-path`; null when not run from a packaged launcher).
 * [owner] says which package manager owns a file and [isWritable] whether a folder is writable,
 * so this runs no commands itself (see [systemPackageOwner]).
 */
fun detectInstallType(
    osName: String,
    version: String,
    launcherPath: String?,
    env: (String) -> String?,
    owner: (Path) -> PackageOwner?,
    isWritable: (Path) -> Boolean,
): InstallType {
    if (launcherPath == null || version.endsWith("-dev")) return InstallType.Development
    return when {
        osName.startsWith("Windows") -> {
            val localAppData = env("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: return InstallType.Development
            val installDir = localAppData.trimEnd('\\', '/') + "\\Programs\\Silage"
            fun normalized(path: String) = path.replace('/', '\\').lowercase()
            if (normalized(launcherPath).startsWith(normalized(installDir) + "\\")) {
                InstallType.WindowsInstaller(Path.of(installDir))
            } else {
                InstallType.Development
            }
        }
        osName.startsWith("Linux") -> {
            val launcher = Path.of(launcherPath)
            when (owner(launcher)) {
                PackageOwner.Dpkg -> InstallType.Deb
                PackageOwner.Rpm -> InstallType.Rpm
                PackageOwner.Pacman -> InstallType.Pacman
                null -> {
                    // The launcher is <installDir>/bin/silage.
                    val installDir = launcher.parent?.parent ?: launcher.parent ?: launcher
                    InstallType.Tarball(installDir, isWritable(installDir))
                }
            }
        }
        else -> InstallType.Development
    }
}

private const val OWNER_TIMEOUT_SECONDS = 5L

/** The package manager that owns [path]: dpkg -S, rpm -qf, then pacman -Qo, the first that succeeds. */
fun systemPackageOwner(path: Path): PackageOwner? {
    val queries = listOf(
        PackageOwner.Dpkg to listOf("dpkg", "-S"),
        PackageOwner.Rpm to listOf("rpm", "-qf"),
        PackageOwner.Pacman to listOf("pacman", "-Qo"),
    )
    return queries.firstOrNull { (_, command) -> succeeds(command + path.toString()) }?.first
}

private fun succeeds(command: List<String>): Boolean {
    val process = try {
        ProcessBuilder(command)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    } catch (_: IOException) {
        return false // Not installed.
    }
    process.outputStream.close()
    return try {
        if (process.waitFor(OWNER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.exitValue() == 0
        } else {
            process.destroyForcibly()
            false
        }
    } catch (_: InterruptedException) {
        process.destroyForcibly()
        Thread.currentThread().interrupt()
        false
    }
}
