package dev.silage.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Where Silage keeps its files.
 *
 * Linux follows the XDG base directories (`~/.config/silage`, `~/.local/state/silage`, …).
 * Windows uses `%LOCALAPPDATA%\Silage\{Config,Data,State,Cache}`: none of it should roam
 * (device id, DPAPI-protected session, logs, image cache). An explicit `XDG_*_HOME`
 * still wins on every platform, which keeps isolated test runs isolated.
 *
 * Earlier Windows builds used the XDG fallbacks under the user's home folder.
 * [migrateLegacyWindowsDirs] moves those once at startup; until a move succeeds the
 * old folder keeps being used, so a failed move never loses a remembered sign-in.
 */
class AppDirs(
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val env: (String) -> String? = System::getenv,
    private val home: Path = Paths.get(System.getProperty("user.home")),
    private val exists: (Path) -> Boolean = { Files.exists(it) },
) {
    enum class Kind(val xdgEnv: String, val xdgFallback: String, val windowsName: String) {
        CONFIG("XDG_CONFIG_HOME", ".config", "Config"),
        DATA("XDG_DATA_HOME", ".local/share", "Data"),
        STATE("XDG_STATE_HOME", ".local/state", "State"),
        CACHE("XDG_CACHE_HOME", ".cache", "Cache"),
    }

    sealed interface Migration {
        val kind: Kind
        data class Moved(override val kind: Kind, val from: Path, val to: Path) : Migration
        data class Failed(override val kind: Kind, val from: Path, val to: Path, val error: Throwable) : Migration
    }

    private val isWindows get() = osName.startsWith("Windows")
    private fun xdgOverride(kind: Kind): Path? = env(kind.xdgEnv)?.takeIf { it.isNotBlank() }?.let(Paths::get)

    /** The pre-AppData location: `<XDG fallback under home>/silage`. */
    fun legacy(kind: Kind): Path = home.resolve(kind.xdgFallback).resolve("silage")

    /** Where this kind lives on Windows once migrated. */
    fun windows(kind: Kind): Path {
        val local = env("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(Paths::get)
            ?: home.resolve("AppData").resolve("Local")
        return local.resolve("Silage").resolve(kind.windowsName)
    }

    /** Pure lookup: never creates or moves anything. */
    fun resolve(kind: Kind): Path {
        xdgOverride(kind)?.let { return it.resolve("silage") }
        if (!isWindows) return legacy(kind)
        val target = windows(kind)
        val old = legacy(kind)
        return if (!exists(target) && exists(old)) old else target
    }

    /**
     * Moves each legacy Windows folder to AppData when the new one doesn't exist yet.
     * A same-volume rename keeps the owner-only ACLs on session files; anything that
     * can't be renamed is left in place (and keeps being used by [resolve]).
     */
    fun migrateLegacyWindowsDirs(
        move: (Path, Path) -> Unit = { from, to -> renameDirectory(from, to) },
    ): List<Migration> {
        if (!isWindows) return emptyList()
        return Kind.entries.mapNotNull { kind ->
            if (xdgOverride(kind) != null) return@mapNotNull null
            val from = legacy(kind)
            val to = windows(kind)
            if (exists(to) || !exists(from)) return@mapNotNull null
            try {
                move(from, to)
                Migration.Moved(kind, from, to)
            } catch (t: Throwable) {
                Migration.Failed(kind, from, to, t)
            }
        }
    }

    /** KDE Plasma's display settings, in the user's config dir (not Silage's). */
    val kwinOutputConfig: Path
        get() = (xdgOverride(Kind.CONFIG) ?: home.resolve(Kind.CONFIG.xdgFallback)).resolve("kwinoutputconfig.json")

    private companion object {
        fun renameDirectory(from: Path, to: Path) {
            Files.createDirectories(to.parent)
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(from, to)
            }
        }
    }
}

object XdgPaths {
    private val dirs = AppDirs()

    val config: Path get() = dirs.resolve(AppDirs.Kind.CONFIG)
    val cache: Path get() = dirs.resolve(AppDirs.Kind.CACHE)
    val state: Path get() = dirs.resolve(AppDirs.Kind.STATE)
    val data: Path get() = dirs.resolve(AppDirs.Kind.DATA)

    /** KDE Plasma's display settings, in the user's config dir (not Silage's). */
    val kwinOutputConfig: Path get() = dirs.kwinOutputConfig

    fun migrateLegacyWindowsDirs(): List<AppDirs.Migration> = dirs.migrateLegacyWindowsDirs()
}
