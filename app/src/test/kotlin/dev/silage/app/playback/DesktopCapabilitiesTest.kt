package dev.silage.app.playback

import org.siloserver.silo.model.playback.DELIVERY_CLASS_HLS
import org.siloserver.silo.model.playback.DELIVERY_CLASS_ORIGINAL_HTTP
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * Under the default "exact" tier the server wants per-codec decoder
     * entries and transcoded everything without them
     * (evidence_insufficient_for_direct). mpv decodes every listed codec
     * through FFmpeg, so the flat lists are the honest claim.
     */
    @Test fun codecListsAreDeclaredSoTheServerDirectPlays() {
        assertEquals("declared", DesktopCapabilities.codecs.videoEvidence)
        assertEquals("declared", DesktopCapabilities.codecs.audioEvidence)
    }

    /** mpv shows HDR natively (passthrough) or tone-maps locally; never ask the server to. */
    @Test fun hdrIsHandledByThePlayerNotTheServer() {
        val hdr = DesktopCapabilities.codecs.hdrDetails
        assertTrue(hdr != null && hdr.hdr10 && hdr.hlg && hdr.hdr10Plus, "hdr=$hdr")
        assertTrue(hdr!!.dolbyVisionProfiles.containsAll(listOf(5, 7, 8)), "dv=${hdr.dolbyVisionProfiles}")
    }

    /** UHD Blu-ray remuxes (Dolby Vision 7) play as the original file, the player keeping the HDR10 base layer. */
    @Test fun dolbyVision7IsHandledByThePlayer() {
        val original = deliveries.getValue(DELIVERY_CLASS_ORIGINAL_HTTP)
        val dv7 = original.transformations.single()
        assertEquals(org.siloserver.silo.model.playback.CLIENT_DV7_TO_HDR10, dv7.name)
        assertEquals(org.siloserver.silo.model.playback.PlaybackTransformationExecutor.CLIENT, dv7.executor)
        assertTrue(original.hdrDetails?.dolbyVisionProfiles?.contains(7) == true)
    }
}
