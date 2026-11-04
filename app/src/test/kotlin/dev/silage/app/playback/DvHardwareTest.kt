package dev.silage.app.playback

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Dolby Vision setting only shows on hardware the helper accepts (an Arc A380 driving a PG32UCDM over DP). */
class DvHardwareTest {
    private val root: Path = Files.createTempDirectory("drm")
    private val pg32ucdm = ByteArray(256).also { e ->
        byteArrayOf(0, -1, -1, -1, -1, -1, -1, 0, 0x06, 0xb3.toByte(), 0xf2.toByte(), 0x32).copyInto(e)
    }
    private val otherMonitor = ByteArray(256).also { e -> byteArrayOf(0, -1, -1, -1, -1, -1, -1, 0, 0x1e, 0x6d, 0x01, 0x02).copyInto(e) }

    private fun gpu(card: String, vendor: String, device: String, driver: String) {
        val dev = root.resolve("devices/$card").createDirectories()
        dev.resolve("vendor").writeText("$vendor\n")
        dev.resolve("device").writeText("$device\n")
        val drv = root.resolve("drivers/$driver").createDirectories()
        Files.createSymbolicLink(dev.resolve("driver"), drv)
        val node = root.resolve(card).createDirectories()
        Files.createSymbolicLink(node.resolve("device"), dev)
    }

    private fun connector(card: String, name: String, edid: ByteArray, status: String = "connected") {
        val node = root.resolve("$card-$name").createDirectories()
        node.resolve("status").writeText("$status\n")
        node.resolve("edid").writeBytes(edid)
    }

    @Test fun anArcA380DrivingThePg32ucdmOverDisplayPortIsSupported() {
        gpu("card0", "0x8086", "0x56a5", "i915")
        connector("card0", "DP-6", pg32ucdm)
        assertTrue(DvHardware.supported(root))
    }

    @Test fun otherGpusMonitorsAndCablesAreNot() {
        gpu("card1", "0x10de", "0x2b85", "nvidia")
        connector("card1", "HDMI-A-1", pg32ucdm)
        gpu("card0", "0x8086", "0x56a5", "i915")
        connector("card0", "DP-4", otherMonitor)
        connector("card0", "HDMI-A-2", pg32ucdm)
        connector("card0", "DP-6", pg32ucdm, status = "disconnected")
        assertFalse(DvHardware.supported(root))
    }

    @Test fun noDisplayInformationMeansNotSupported() {
        assertFalse(DvHardware.supported(root.resolve("missing")))
    }
}
