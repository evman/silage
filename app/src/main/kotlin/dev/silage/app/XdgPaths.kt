package dev.silage.app

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Linux follows the XDG base directories. The installed Windows app (run through the
 * jpackage launcher) keeps settings and data in `%APPDATA%\Silage` and state and caches in
 * `%LOCALAPPDATA%\Silage`; it never reads a `userdata` folder beside the executable.
 * Windows runs without a launcher (development) use `userdata` in the working directory,
 * or in `SILAGE_PORTABLE_HOME` when set. Explicit XDG overrides still allow isolated
 * developer/test runs on either OS.
 */
class AppDirs(
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val env: (String) -> String? = System::getenv,
    private val home: Path = Paths.get(System.getProperty("user.home")),
    private val launcherPath: String? = System.getProperty("jpackage.app-path"),
    private val workingDirectory: Path = Paths.get(System.getProperty("user.dir")),
) {
    enum class Kind(val xdgEnv: String, val xdgFallback: String, val windowsName: String) {
        CONFIG("XDG_CONFIG_HOME", ".config", "Config"),
        DATA("XDG_DATA_HOME", ".local/share", "Data"),
        STATE("XDG_STATE_HOME", ".local/state", "State"),
        CACHE("XDG_CACHE_HOME", ".cache", "Cache"),
    }

    private fun xdgOverride(kind: Kind): Path? = env(kind.xdgEnv)?.takeIf { it.isNotBlank() }?.let(Paths::get)

    /** Pure lookup: never creates or moves files, or imports another copy's settings. */
    fun resolve(kind: Kind): Path {
        xdgOverride(kind)?.let { return it.resolve("silage") }
        if (!osName.startsWith("Windows")) return home.resolve(kind.xdgFallback).resolve("silage")

        // jpackage supplies the absolute native launcher path of an installed app.
        val launcher = launcherPath?.takeIf { it.isNotBlank() }?.also {
            require(Paths.get(it).isAbsolute) { "jpackage.app-path must be absolute: $it" }
        }
        if (launcher != null) {
            val roaming = kind == Kind.CONFIG || kind == Kind.DATA
            val base = env(if (roaming) "APPDATA" else "LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(Paths::get)
                ?: home.resolve("AppData").resolve(if (roaming) "Roaming" else "Local")
            return base.resolve("Silage").resolve(kind.windowsName)
        }

        // Source runs have no launcher: use the development directory.
        val devDirectory = env("SILAGE_PORTABLE_HOME")?.takeIf { it.isNotBlank() }?.let {
            Paths.get(it).also { directory ->
                require(directory.isAbsolute) { "SILAGE_PORTABLE_HOME must be absolute" }
            }
        } ?: workingDirectory.toAbsolutePath()
        return devDirectory.resolve("userdata").resolve(kind.windowsName)
    }

    /** KDE Plasma's display settings, in the user's config dir (not Silage's). */
    val kwinOutputConfig: Path
        get() = (xdgOverride(Kind.CONFIG) ?: home.resolve(Kind.CONFIG.xdgFallback)).resolve("kwinoutputconfig.json")
}

object XdgPaths {
    private val dirs = AppDirs()

    val config: Path get() = dirs.resolve(AppDirs.Kind.CONFIG)
    val cache: Path get() = dirs.resolve(AppDirs.Kind.CACHE)
    val state: Path get() = dirs.resolve(AppDirs.Kind.STATE)
    val data: Path get() = dirs.resolve(AppDirs.Kind.DATA)

    /** KDE Plasma's display settings, in the user's config dir (not Silage's). */
    val kwinOutputConfig: Path get() = dirs.kwinOutputConfig
}
