package org.siloserver.silo.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SiloCastPlaybackClockTest {
    @Test
    fun displayTimeInterpolatesWhilePlayingAndClampsToDuration() {
        val clock = SiloCastPlaybackClock()
        clock.ingest(state(isPlaying = true, currentTime = 10.0, duration = 20.0), nowMs = 1_000)

        assertEquals(13.0, clock.displayTime(nowMs = 4_000), 0.01)
        assertEquals(20.0, clock.displayTime(nowMs = 60_000), 0.01)
    }

    @Test
    fun optimisticSeekWinsUntilSnapshotCatchesUp() {
        val clock = SiloCastPlaybackClock()
        clock.ingest(state(currentTime = 10.0, duration = 3_000.0), nowMs = 1_000)
        clock.setOptimisticTime(1_200.0, nowMs = 1_000)
        clock.ingest(state(currentTime = 10.0, duration = 3_000.0), nowMs = 1_500)

        assertEquals(1_200.0, clock.displayTime(nowMs = 1_500), 0.01)

        clock.ingest(state(currentTime = 1_200.0, duration = 3_000.0), nowMs = 2_000)
        assertEquals(1_200.0, clock.displayTime(nowMs = 2_000), 0.01)
    }

    @Test
    fun optimisticPlayingWinsBriefly() {
        val clock = SiloCastPlaybackClock()
        clock.ingest(state(isPlaying = false), nowMs = 1_000)
        clock.setOptimisticPlaying(true, nowMs = 1_000)

        assertTrue(clock.isPlaying(nowMs = 1_500))
    }

    private fun state(
        isPlaying: Boolean = false,
        currentTime: Double = 0.0,
        duration: Double = 100.0,
    ) = SiloCastPlaybackState(
        contentId = "content",
        sessionId = null,
        title = "Title",
        subtitle = null,
        isPlaying = isPlaying,
        isLoading = false,
        isBuffering = false,
        currentTime = currentTime,
        duration = duration,
        audioTracks = emptyList(),
        subtitleTracks = emptyList(),
        selectedAudioTrackId = null,
        selectedSubtitleTrackId = null,
        qualityOptions = emptyList(),
        activeQualityId = "auto",
        isQualitySwitching = false,
        playbackSpeed = 1.0,
        videoGravity = "fit",
        hdrEnabled = false,
        supportsVideoGravity = false,
        supportsHDRToggle = false,
        subtitleSyncMs = null,
        subtitlePosition = null,
        supportsSubtitleDelay = false,
        supportsSubtitlePosition = false,
        volume = 1.0,
        isMuted = false,
        hasNextEpisode = false,
        nextEpisodeTitle = null,
        error = null,
    )
}
