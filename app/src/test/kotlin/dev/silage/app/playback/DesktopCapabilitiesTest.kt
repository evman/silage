package dev.silage.app.playback

import org.siloserver.silo.model.playback.DELIVERY_CLASS_HLS
import org.siloserver.silo.model.playback.DELIVERY_CLASS_ORIGINAL_HTTP
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The server only plans routes through delivery classes the client lists as
 * enabled and supported (silo-server plan_v3.go deliveryAvailableV3); with
 * none listed every start ends in "adaptation_unavailable".
 */
class DesktopCapabilitiesTest {
    private val deliveries = DesktopCapabilities.context.deliveries

    @Test fun originalFileDeliveryIsOfferedForCommonContainers() {
        val original = deliveries[DELIVERY_CLASS_ORIGINAL_HTTP]
        assertTrue(original != null && original.enabled && original.supportedOnDevice, "original_http missing: $deliveries")
        assertTrue(original.containers.containsAll(listOf("mkv", "mp4", "webm")), "containers=${original.containers}")
        assertTrue("hevc" in original.videoCodecs)
    }

    @Test fun hlsDeliveryIsOfferedAsFallback() {
        val hls = deliveries[DELIVERY_CLASS_HLS]
        assertTrue(hls != null && hls.enabled && hls.supportedOnDevice, "hls missing: $deliveries")
    }

    @Test fun mpvRendersEveryEmbeddedSubtitleKind() {
        val subs = deliveries.getValue(DELIVERY_CLASS_ORIGINAL_HTTP).subtitles
        assertTrue(subs.embeddedBitmap && subs.assStyling && subs.nativeEmbedded.isNotEmpty())
    }
}
