package dev.silage.app.playback

import androidx.compose.ui.graphics.ImageBitmap
import dev.silage.app.playback.hud.hudPosition
import dev.silage.player.PlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SeekThumbnailsTest {
    @Test fun slotsAreTenSecondsOrLongerOnALongFilm() {
        assertEquals(30.0, thumbnailSlot(37.0, 1_200.0))
        assertEquals(0.0, thumbnailSlot(-5.0, 1_200.0))
        assertEquals(60.0, thumbnailSlot(89.0, 7_200.0))
        assertEquals(90.0, thumbnailSlot(90.0, 7_200.0))
    }

    @Test fun theCacheForgetsTheFrameUsedLongestAgo() {
        val cache = ThumbnailCache(capacity = 2)
        val image = ImageBitmap(1, 1)
        cache.put(SeekThumbnail(0.0, image))
        cache.put(SeekThumbnail(10.0, image))
        assertNotNull(cache[0.0])
        cache.put(SeekThumbnail(20.0, image))
        assertNull(cache[10.0])
        assertNotNull(cache[0.0])
    }

    @Test fun theHudPositionChangesOncePerSecondOnAFilm() {
        assertEquals(12.0, hudPosition(12.9, 7_200.0))
        assertEquals(hudPosition(100.1, 7_200.0), hudPosition(100.9, 7_200.0))
        // A short clip moves the bar visibly between seconds, so it updates finer.
        assertEquals(0.5, hudPosition(0.55, 30.0), 0.0001)
    }

    @Test fun mediaControlValues() {
        assertEquals(1, mediaPlaybackStatus(PlayerState(loaded = false)))
        assertEquals(3, mediaPlaybackStatus(PlayerState(loaded = true, paused = false)))
        assertEquals(4, mediaPlaybackStatus(PlayerState(loaded = true, paused = true)))
        assertEquals(15_000_000L, winRtTicks(1.5))
        assertEquals(0L, winRtTicks(-1.0))
    }
}
