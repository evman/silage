package dev.silage.app

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Linux follows the XDG base directories. Windows keeps its files in `userdata`
 * beside the packaged executable, so moving the app also moves its settings.
 * Explicit XDG overrides still allow isolated developer/test runs on either OS.
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

        // jpackage supplies the absolute native launcher path, independent of a shortcut's
        // "Start in" folder. Source runs have no launcher: use the development directory.
        val appDirectory = env("SILAGE_PORTABLE_HOME")?.takeIf { it.isNotBlank() }?.let {
            Paths.get(it).also { directory ->
                require(directory.isAbsolute) { "SILAGE_PORTABLE_HOME must be absolute" }
            }
        } ?: launcherPath?.takeIf { it.isNotBlank() }?.let {
            val executable = Paths.get(it)
            require(executable.isAbsolute) { "jpackage.app-path must be absolute: $it" }
            requireNotNull(executable.parent) { "jpackage.app-path must point to an executable" }
        } ?: workingDirectory.toAbsolutePath()
        return appDirectory.resolve("userdata").resolve(kind.windowsName)
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
