package dev.silage.app.playback

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Whether this PC has the hardware the experimental Dolby Vision helper accepts:
 * exactly one Intel Arc A380 on i915, driving an ASUS PG32UCDM over a connected
 * DisplayPort output. Read-only sysfs checks, mirroring the helper's own, so
 * Settings only offers the option where it can work.
 */
object DvHardware {
    private val ARC_A380 = "0x8086" to "0x56a5"
    /** EDID manufacturer and product bytes of the validated ASUS PG32UCDM. */
    private val PG32UCDM = byteArrayOf(0x06, 0xb3.toByte(), 0xf2.toByte(), 0x32)

    fun supported(drm: Path = Paths.get("/sys/class/drm")): Boolean = runCatching {
        val cards = Files.list(drm).use { s -> s.toList() }.filter { Regex("card\\d+").matches(it.fileName.toString()) }
        val arc = cards.filter { card ->
            val gpu = card.resolve("device").toRealPath()
            (read(gpu.resolve("vendor")) to read(gpu.resolve("device"))) == ARC_A380 &&
                gpu.resolve("driver").toRealPath().fileName.toString() == "i915"
        }
        val card = arc.singleOrNull() ?: return false
        Files.list(drm).use { s -> s.toList() }.any { node ->
            node.fileName.toString().startsWith("${card.fileName}-DP-") &&
                read(node.resolve("status")) == "connected" &&
                Files.readAllBytes(node.resolve("edid")).let { it.size >= 12 && it.copyOfRange(8, 12).contentEquals(PG32UCDM) }
        }
    }.getOrDefault(false)

    private fun read(file: Path) = Files.readString(file).trim()
}
