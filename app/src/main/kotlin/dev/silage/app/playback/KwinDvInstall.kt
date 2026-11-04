package dev.silage.app.playback

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The opt-in KWin with Silage's Dolby Vision patch, through the packaged helper
 * (packaging/kwin/silage-kwin-dv): what it reports, and installing or removing it
 * behind the system password prompt, as updates do.
 */
internal data class KwinDvStatus(val distro: String, val repo: Boolean, val patched: Boolean, val monitor: Boolean) {
    companion object {
        /** The helper's `status` lines (`distro=arch`, `repo=yes`, …); null when they aren't there. */
        fun parse(text: String): KwinDvStatus? {
            val values = text.lines().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val distro = values["distro"] ?: return null
            return KwinDvStatus(distro, values["repo"] == "yes", values["patched"] == "yes", values["monitor"] == "yes")
        }
    }
}

internal enum class KwinDvState {
    /** Not offered: no helper, another distribution, or no verified monitor. */
    Hidden,
    CanEnable,
    On,
    /** Installed by hand: works, but adding the repository keeps it current. */
    OnWithoutUpdates,
    /** Installed; KWin starts using it at the next login. */
    NeedsRelogin,
    /** KDE updated KWin before Silage's build of it: plain HDR until that arrives with an update. */
    Waiting,
}

internal fun kwinDvState(status: KwinDvStatus?, leaseAvailable: Boolean): KwinDvState = when {
    leaseAvailable -> if (status != null && status.distro != "unsupported" && !status.repo) KwinDvState.OnWithoutUpdates else KwinDvState.On
    status == null || status.distro == "unsupported" -> KwinDvState.Hidden
    status.patched -> KwinDvState.NeedsRelogin
    status.repo -> KwinDvState.Waiting
    status.monitor -> KwinDvState.CanEnable
    else -> KwinDvState.Hidden
}

internal sealed interface KwinDvResult {
    data class Done(val message: String) : KwinDvResult
    data object Cancelled : KwinDvResult
    data class Failed(val message: String) : KwinDvResult
}

/** pkexec's 126/127 mean the password prompt was dismissed or refused; otherwise the helper's last line says what happened. */
internal fun kwinDvResult(exitCode: Int, output: String): KwinDvResult {
    val last = output.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }
    return when {
        exitCode == 0 -> KwinDvResult.Done(last ?: "Done. Log out and back in to finish.")
        exitCode == 126 || exitCode == 127 -> KwinDvResult.Cancelled
        else -> KwinDvResult.Failed(last ?: "Something went wrong (exit $exitCode).")
    }
}

internal object KwinDvInstall {
    const val HELPER = "/usr/libexec/silage-kwin-dv/silage-kwin-dv"

    fun command(enable: Boolean): List<String> = listOf("pkexec", HELPER, if (enable) "enable" else "disable")

    /** What the helper reports here; null when Silage wasn't installed with it (e.g. the portable build). */
    fun status(): KwinDvStatus? {
        if (!File(HELPER).canExecute()) return null
        return runCatching {
            val process = ProcessBuilder(HELPER, "status").redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) KwinDvStatus.parse(text) else null
        }.getOrNull()
    }

    /** Installs (or removes) the patched KWin; blocks while the package manager runs. */
    fun apply(enable: Boolean): KwinDvResult = runCatching {
        val process = ProcessBuilder(command(enable)).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        kwinDvResult(process.waitFor(), output)
    }.getOrElse { KwinDvResult.Failed("Couldn't ask for your password: ${it.message}") }
}
