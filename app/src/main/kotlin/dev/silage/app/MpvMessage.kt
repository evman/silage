package dev.silage.app

import dev.silage.player.MpvRuntime
import dev.silage.player.MpvStatus

/** What to tell the user when mpv can't be used. */
data class MpvProblem(val title: String, val body: String, val command: String)

fun mpvProblem(status: MpvStatus): MpvProblem? = when (status) {
    is MpvStatus.Ok -> null
    is MpvStatus.Missing -> MpvProblem(
        "Silage needs mpv",
        "Silage plays video with mpv ${MpvRuntime.MINIMUM_TEXT} or newer, which isn't installed.",
        status.hint,
    )
    is MpvStatus.TooOld -> MpvProblem(
        "Your mpv is too old",
        "Silage needs mpv ${MpvRuntime.MINIMUM_TEXT} or newer; this system has ${status.version}.",
        status.hint,
    )
}

/** `silage --check` output. */
fun mpvCheckLine(status: MpvStatus): String = when (val p = mpvProblem(status)) {
    null -> "mpv OK: ${(status as MpvStatus.Ok).version}"
    else -> "${p.body}\nInstall it with: ${p.command}"
}
