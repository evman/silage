package dev.silage.player

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The upscaling options Silage sets are accepted by this libmpv and take effect. */
class MpvQualityOptionsTest {
    private val player = MpvPlayer(mapOf(
        "vo" to "null", "ao" to "null",
        "profile" to "high-quality", "scale" to "ewa_lanczossharp", "cscale" to "ewa_lanczossharp", "deband" to "yes",
    ))

    @AfterTest fun close() = player.close()

    @Test fun highQualityScalersAreActive() {
        assertEquals("ewa_lanczossharp", player.getProperty("scale"))
        assertEquals("ewa_lanczossharp", player.getProperty("cscale"))
        assertEquals("yes", player.getProperty("deband"))
    }
}
