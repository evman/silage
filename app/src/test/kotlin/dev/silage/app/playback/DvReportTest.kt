package dev.silage.app.playback

import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `silage --dv-report` and Settings → Video's "Send hardware report": what we need to add
 * Dolby Vision support for someone's GPU and monitor, never their monitor's serial number.
 */
class DvReportTest {
    private val root: Path = Files.createTempDirectory("drm")

    /** An ASUS PG32UCDM-like EDID: serial 0x01020304 and serial text "SER4242", a Dolby Vision and an HDR block. */
    private fun edid(): ByteArray {
        val base = ByteArray(128)
        byteArrayOf(0, -1, -1, -1, -1, -1, -1, 0, 0x06, 0xb3.toByte(), 0xf2.toByte(), 0x32, 1, 2, 3, 4).copyInto(base)
        fun descriptor(at: Int, tag: Int, text: String) {
            base[at + 3] = tag.toByte()
            (text + "\n").toByteArray().copyInto(base, at + 5)
        }
        descriptor(54, 0xFF, "SER4242")
        descriptor(72, 0xFC, "PG32UCDM")
        base[126] = 1
        val cta = ByteArray(128)
        cta[0] = 2; cta[1] = 3
        val dolby = byteArrayOf(0xEB.toByte()) + hex("0146d0004c025d89606f87")
        val hdr = byteArrayOf(0xE3.toByte(), 0x06, 0x05, 0x01)
        (dolby + hdr).copyInto(cta, 4)
        cta[2] = (4 + dolby.size + hdr.size).toByte()
        return base + cta
    }

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun gpu(card: String, vendor: String, device: String, driver: String) {
        val dev = root.resolve("devices/$card").createDirectories()
        dev.resolve("vendor").writeText("$vendor\n"); dev.resolve("device").writeText("$device\n")
        Files.createSymbolicLink(dev.resolve("driver"), root.resolve("drivers/$driver").createDirectories())
        Files.createSymbolicLink(root.resolve(card).createDirectories().resolve("device"), dev)
    }

    private fun connector(name: String, status: String, edid: ByteArray) {
        val node = root.resolve(name).createDirectories()
        node.resolve("status").writeText("$status\n"); node.resolve("edid").writeBytes(edid)
    }

    private fun report(): String {
        gpu("card1", "0x10de", "0x2b85", "nvidia")
        connector("card1-HDMI-A-1", "connected", edid())
        connector("card1-DP-2", "disconnected", ByteArray(0))
        return DvReport.build(root, silageVersion = "0.5.2", kernel = "6.18.1", desktop = "KDE",
            driverVersion = { if (it == "nvidia") "615.78.08" else null })
    }

    @Test fun describesTheGpuAndTheMonitorsDolbyVisionSupport() {
        val text = report()
        assertContains(text, "Silage 0.5.2")
        assertContains(text, "10de:2b85")
        assertContains(text, "nvidia 615.78.08")
        assertContains(text, "card1-HDMI-A-1")
        assertContains(text, "PG32UCDM")
        assertContains(text, "AUS")
        assertContains(text, "0146d0004c025d89606f87", message = "the Dolby Vision block, exactly")
        assertContains(text, "060501", message = "the HDR block")
        assertFalse("card1-DP-2" in text, "only connected displays")
    }

    @Test fun neverIncludesTheMonitorsSerialNumber() {
        val text = report()
        assertFalse("SER4242" in text)
        assertFalse("01020304" in text && "serial" in text.lowercase())
        assertFalse("04030201" in text)
    }

    @Test fun opensAPrefilledGitHubIssue() {
        val text = report()
        val url = DvReport.issueUrl(text)
        assertTrue(url.startsWith("https://github.com/evman/silage/issues/new?"))
        val body = URLDecoder.decode(url.substringAfter("body="), Charsets.UTF_8)
        assertContains(body, "0146d0004c025d89606f87")
    }
}
