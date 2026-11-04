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

    /**
     * Dolby Vision vendor blocks (EDID, CTA extended tag 1, Dolby OUI) of monitors whose
     * trigger values are known; must match the patched KWin's table. ASUS PG32UCDM first.
     * More are added from hardware reports (`silage --dv-report`).
     */
    val VERIFIED_DV_BLOCKS = listOf("0146d0004c025d89606f87")

    /** A connected display (any GPU, DisplayPort or HDMI) is a verified Dolby Vision monitor. */
    fun verifiedMonitorConnected(drm: Path = Paths.get("/sys/class/drm")): Boolean = runCatching {
        Files.list(drm).use { s -> s.toList() }.any { node ->
            node.fileName.toString().matches(Regex("card\\d+-.+")) &&
                runCatching { read(node.resolve("status")) == "connected" }.getOrDefault(false) &&
                runCatching { dolbyBlocks(Files.readAllBytes(node.resolve("edid"))).any { it in VERIFIED_DV_BLOCKS } }.getOrDefault(false)
        }
    }.getOrDefault(false)

    /** Every CTA extended-tag block's payload, as hex, that carries Dolby's OUI. */
    private fun dolbyBlocks(edid: ByteArray): List<String> = buildList {
        fun u(i: Int) = edid[i].toInt() and 0xFF
        for (start in 128 until edid.size - 127 step 128) {
            if (u(start) != 0x02) continue
            val end = start + u(start + 2).coerceIn(4, 127)
            var i = start + 4
            while (i < end) {
                val len = u(i) and 31
                if ((u(i) shr 5) == 7 && len >= 4 && i + len < edid.size && u(i + 1) == 1 &&
                    u(i + 2) == 0x46 && u(i + 3) == 0xd0 && u(i + 4) == 0) {
                    add(edid.copyOfRange(i + 1, i + 1 + len).joinToString("") { "%02x".format(it) })
                }
                i += 1 + len
            }
        }
    }

    private fun read(file: Path) = Files.readString(file).trim()
}
