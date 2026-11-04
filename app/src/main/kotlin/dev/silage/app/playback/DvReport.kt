package dev.silage.app.playback

import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The Dolby Vision hardware report (`silage --dv-report`, Settings → Video): the GPUs and
 * their drivers, and each connected display's maker, model and raw Dolby Vision / HDR
 * capability blocks — what's needed to add support for that hardware. Read-only sysfs;
 * the monitor's serial number (EDID bytes 12–15 and its serial text) is never included.
 * It goes nowhere by itself: [issueUrl] opens a pre-filled GitHub issue to submit.
 */
object DvReport {
    fun build(
        drm: Path = Paths.get("/sys/class/drm"),
        silageVersion: String,
        kernel: String = System.getProperty("os.version").orEmpty(),
        desktop: String = listOfNotNull(System.getenv("XDG_CURRENT_DESKTOP"), System.getenv("XDG_SESSION_TYPE")).joinToString(" "),
        driverVersion: (driver: String) -> String? = ::installedDriverVersion,
    ): String = buildString {
        appendLine("### Silage Dolby Vision hardware report")
        appendLine()
        appendLine("Silage $silageVersion · Linux $kernel · ${desktop.ifBlank { "unknown desktop" }}")
        val nodes = runCatching { Files.list(drm).use { it.toList() }.sortedBy { it.fileName.toString() } }.getOrDefault(emptyList())
        appendLine()
        appendLine("**GPUs**")
        val cards = nodes.filter { Regex("card\\d+").matches(it.fileName.toString()) }
        if (cards.isEmpty()) appendLine("- none found")
        cards.forEach { card ->
            runCatching {
                val gpu = card.resolve("device").toRealPath()
                val ids = "${read(gpu.resolve("vendor")).removePrefix("0x")}:${read(gpu.resolve("device")).removePrefix("0x")}"
                val driver = gpu.resolve("driver").toRealPath().fileName.toString()
                appendLine("- ${card.fileName}: `$ids` ($driver${driverVersion(driver)?.let { " $it" } ?: ""})")
            }
        }
        appendLine()
        appendLine("**Connected displays**")
        var displays = 0
        nodes.filter { it.fileName.toString().matches(Regex("card\\d+-.+")) }.forEach { node ->
            runCatching {
                if (read(node.resolve("status")) != "connected") return@runCatching
                val edid = Files.readAllBytes(node.resolve("edid"))
                if (edid.size < 128) return@runCatching
                displays++
                val monitor = Edid(edid)
                appendLine("- ${node.fileName}: ${monitor.name ?: "unnamed"} (`${monitor.maker} ${monitor.product}`)")
                appendLine("  - Dolby Vision block: ${monitor.dolbyVision?.let { "`$it`" } ?: "none"}")
                appendLine("  - HDR block: ${monitor.hdr?.let { "`$it`" } ?: "none"}")
            }
        }
        if (displays == 0) appendLine("- none found")
    }

    /** A new GitHub issue with [report] filled in; nothing is sent until the person submits it. */
    fun issueUrl(report: String): String {
        fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
        val body = "$report\n**Which display or connection is this about, and what happens with Dolby Vision?**\n\n"
        return "https://github.com/evman/silage/issues/new?title=${enc("Dolby Vision hardware report")}&body=${enc(body)}"
    }

    private fun read(file: Path) = Files.readString(file).trim()

    private fun installedDriverVersion(driver: String): String? = runCatching {
        if (driver == "nvidia") Regex("\\b(\\d+\\.\\d+(\\.\\d+)?)\\b").find(Files.readString(Paths.get("/proc/driver/nvidia/version")))?.value
        else Files.readString(Paths.get("/sys/module/$driver/version")).trim().ifBlank { null }
    }.getOrNull()

    /** The parts of an EDID the report uses; deliberately no serial number. */
    private class Edid(private val bytes: ByteArray) {
        private fun u(i: Int) = bytes[i].toInt() and 0xFF
        val maker: String = (u(8) shl 8 or u(9)).let { m -> listOf(10, 5, 0).map { 'A' + ((m shr it) and 31) - 1 }.joinToString("") }
        val product: String = "%04x".format(u(11) shl 8 or u(10))
        val name: String? = (54 until 126 step 18).firstOrNull { u(it) == 0 && u(it + 1) == 0 && u(it + 3) == 0xFC }
            ?.let { String(bytes, it + 5, 13, Charsets.US_ASCII).substringBefore('\n').trim().ifBlank { null } }
        private val blocks: List<ByteArray> = buildList {
            for (start in 128 until bytes.size - 127 step 128) {
                if (u(start) != 0x02) continue
                val end = start + u(start + 2).coerceIn(4, 127)
                var i = start + 4
                while (i < end) {
                    val len = u(i) and 31
                    if ((u(i) shr 5) == 7 && len > 0 && i + len < bytes.size) add(bytes.copyOfRange(i + 1, i + 1 + len))
                    i += 1 + len
                }
            }
        }
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        val dolbyVision: String? = blocks.firstOrNull { it.size >= 4 && it[0] == 1.toByte() && it[1] == 0x46.toByte() && it[2] == 0xd0.toByte() && it[3] == 0.toByte() }?.hex()
        val hdr: String? = blocks.firstOrNull { it[0] == 6.toByte() }?.hex()
    }
}
