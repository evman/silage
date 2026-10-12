package dev.silage.app.playback.hud

import kotlin.test.Test
import kotlin.test.assertEquals

class InfoRowsTest {
    @Test fun describesStreamDecoderHdrAndBuffer() {
        val props = mapOf(
            "video-format" to "hevc", "video-params/w" to "3840", "video-params/h" to "2160",
            "video-params/gamma" to "pq", "video-params/primaries" to "bt.2020",
            "video-target-params/gamma" to "pq",
            "hwdec-current" to "vaapi", "video-bitrate" to "48500000",
            "audio-codec-name" to "truehd", "audio-params/channel-count" to "8",
            "frame-drop-count" to "1", "decoder-frame-drop-count" to "2",
            "demuxer-cache-duration" to "184.2", "cache-speed" to "52428800",
        )
        val rows = InfoRows.build { props[it] }.toMap()
        assertEquals("HEVC 3840×2160", rows["Video"])
        assertEquals("HDR10 (PQ, BT.2020)", rows["Dynamic range"])
        assertEquals("HDR passthrough", rows["Display"])
        assertEquals("VA-API (hardware)", rows["Decoder"])
        assertEquals("48.5 Mbps", rows["Bitrate"])
        assertEquals("TRUEHD 8ch", rows["Audio"])
        assertEquals("3", rows["Dropped frames"])
        assertEquals("3:04 ahead · 50.0 MB/s", rows["Buffer"])
    }

    @Test fun sdrOnSdrDisplayAndSoftwareDecode() {
        val rows = InfoRows.build { mapOf("video-params/gamma" to "bt.1886", "video-target-params/gamma" to "srgb", "hwdec-current" to "no")[it] }.toMap()
        assertEquals("SDR", rows["Dynamic range"])
        assertEquals("SDR", rows["Display"])
        assertEquals("Software", rows["Decoder"])
    }

    @Test fun hdrOnSdrDisplayIsToneMapped() {
        val rows = InfoRows.build { mapOf("video-params/gamma" to "hlg", "video-target-params/gamma" to "srgb")[it] }.toMap()
        assertEquals("HLG", rows["Dynamic range"])
        assertEquals("Tone-mapped to SDR", rows["Display"])
    }
}
