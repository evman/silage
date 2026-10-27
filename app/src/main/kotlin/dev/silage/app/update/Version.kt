package dev.silage.app.update

import java.nio.file.Files
import java.nio.file.Path

/** A release version, `major.minor.patch`; development and prerelease builds have none. */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version): Int = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val pattern = Regex("""v?(\d+)\.(\d+)\.(\d+)""")

        /** "v0.5.1" or "0.5.1"; null for "0.5.0-dev", prereleases and anything else. */
        fun parse(s: String): Version? {
            val (major, minor, patch) = pattern.matchEntire(s.trim())?.destructured ?: return null
            return Version(major.toIntOrNull() ?: return null, minor.toIntOrNull() ?: return null, patch.toIntOrNull() ?: return null)
        }
    }
}

/**
 * The `version.txt` the build writes into the app image (see app/build.gradle.kts), where an
 * installed copy of Silage keeps it; a package manager or installer replaces it with the app.
 */
fun installedVersionFile(type: InstallType): Path? = when (type) {
    is InstallType.WindowsInstaller -> type.installDir.resolve("app").resolve("version.txt")
    InstallType.Deb, InstallType.Rpm, InstallType.Pacman -> Path.of("/opt/silage/lib/app/version.txt")
    is InstallType.Tarball -> type.installDir.resolve("lib").resolve("app").resolve("version.txt")
    InstallType.Development -> null
}

/** The version in [file]; null when it is missing, unreadable or not a release version. */
fun readVersionFile(file: Path): Version? =
    try {
        Version.parse(Files.readString(file))
    } catch (_: Exception) {
        null
    }
