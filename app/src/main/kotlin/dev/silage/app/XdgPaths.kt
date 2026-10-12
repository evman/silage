package dev.silage.app

import java.nio.file.Path
import java.nio.file.Paths

object XdgPaths {
    private val home: Path = Paths.get(System.getProperty("user.home"))
    private fun dir(env: String, fallback: String): Path =
        (System.getenv(env)?.takeIf { it.isNotBlank() }?.let(Paths::get) ?: home.resolve(fallback)).resolve("silage")

    val config: Path get() = dir("XDG_CONFIG_HOME", ".config")
    val cache: Path get() = dir("XDG_CACHE_HOME", ".cache")
    val state: Path get() = dir("XDG_STATE_HOME", ".local/state")
    val data: Path get() = dir("XDG_DATA_HOME", ".local/share")

    /** KDE Plasma's display settings, in the user's config dir (not Silage's). */
    val kwinOutputConfig: Path get() = config.parent.resolve("kwinoutputconfig.json")
}
