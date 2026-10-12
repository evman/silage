package dev.silage.player

import java.io.File

/** Whether a usable libmpv is installed. */
sealed interface MpvStatus {
    data class Ok(val version: String) : MpvStatus
    data class Missing(val detail: String, val hint: String) : MpvStatus
    data class TooOld(val version: String, val hint: String) : MpvStatus
}

/**
 * Checks the installed libmpv before anything needs it: Silage uses mpv
 * 0.37+ features (the `keybind` command, `input-cursor-passthrough`).
 */
object MpvRuntime {
    private val MINIMUM = Triple(0, 37, 0)
    const val MINIMUM_TEXT = "0.37"

    fun check(): MpvStatus {
        val hint = installHint()
        val lib = try {
            LibMpv.load()
        } catch (e: Throwable) {
            return MpvStatus.Missing(e.message ?: e.toString(), hint)
        }
        val ctx = lib.mpv_create() ?: return MpvStatus.Missing("mpv_create failed", hint)
        return try {
            val p = lib.mpv_get_property_string(ctx, "mpv-version")
            val text = p?.let { try { it.getString(0, "UTF-8") } finally { lib.mpv_free(it) } } ?: "mpv (unknown version)"
            val v = parseVersion(text)
            if (v == null || meetsMinimum(v)) MpvStatus.Ok(text) else MpvStatus.TooOld(text, hint)
        } finally {
            lib.mpv_terminate_destroy(ctx)
        }
    }

    /** "mpv 0.41.0", "mpv v0.37.0-dirty" → (0, 41, 0). */
    fun parseVersion(text: String): Triple<Int, Int, Int>? {
        val m = Regex("""v?(\d+)\.(\d+)\.(\d+)""").find(text) ?: return null
        val (a, b, c) = m.destructured
        return Triple(a.toInt(), b.toInt(), c.toInt())
    }

    fun meetsMinimum(v: Triple<Int, Int, Int>): Boolean =
        compareValuesBy(v, MINIMUM, { it.first }, { it.second }, { it.third }) >= 0

    /** (ID, ID_LIKE) from /etc/os-release text. */
    fun osRelease(text: String): Pair<String?, String?> {
        fun field(key: String) = text.lineSequence().firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')?.trim()?.trim('"')
        return field("ID") to field("ID_LIKE")
    }

    /** The command that installs libmpv on this distro family. */
    fun installHint(id: String?, idLike: String?): String {
        val family = (listOfNotNull(id) + idLike.orEmpty().split(' ')).map { it.lowercase() }
        return when {
            family.any { it in setOf("arch", "manjaro", "endeavouros", "cachyos") } -> "sudo pacman -S mpv"
            family.any { it in setOf("debian", "ubuntu") } -> "sudo apt install libmpv2"
            family.any { it in setOf("fedora", "rhel", "centos") } -> "sudo dnf install mpv-libs"
            family.any { it in setOf("opensuse", "suse", "opensuse-tumbleweed") } -> "sudo zypper install libmpv2"
            else -> "install mpv $MINIMUM_TEXT or newer (with libmpv) from your distribution"
        }
    }

    private fun installHint(): String {
        val text = runCatching { File("/etc/os-release").readText() }.getOrDefault("")
        val (id, like) = osRelease(text)
        return installHint(id, like)
    }
}
