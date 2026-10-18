package dev.silage.app

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/** Unexpected exceptions, with stack traces, in $XDG_STATE_HOME/silage/silage.log (spec §7). */
class CrashLog(private val file: Path = XdgPaths.state.resolve("silage.log")) {
    fun record(where: String, t: Throwable) {
        runCatching {
            val trace = SecretRedaction.text(StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString())
            Files.createDirectories(file.parent)
            Files.writeString(file, "${Instant.now()} [$where] $trace\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
        System.err.println(SecretRedaction.text("silage: unexpected error in $where: $t"))
    }
}
