package dev.silage.app.playback.hud

import dev.silage.app.detail.hdCopyOf
import kotlinx.coroutines.runBlocking
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.VideoTrack
import org.siloserver.silo.model.catalog.WatchDetail
import org.siloserver.silo.model.playback.PlaybackStartRequestV3
import org.siloserver.silo.network.ApiResult
import dev.silage.app.playback.PlaybackLauncher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BufferingCopyTest {
    private fun copy(id: Int, width: Int, height: Int, size: Long = 0, duration: Double = 7200.0, edition: String? = null) = FileVersion(
        fileId = id, fileSize = size, duration = duration, editionRaw = edition, videoTracks = listOf(VideoTrack(width = width, height = height)),
    )
    private val uhd = copy(1, 3840, 2160, size = 60_000_000_000)

    @Test fun findsTheSameTitlesHdCopy() {
        assertEquals(2, hdCopyOf(listOf(uhd, copy(2, 1920, 1080, size = 12_000_000_000)), 1)?.fileId)
    }

    @Test fun onlyFrom4k() {
        val hd = copy(2, 1920, 1080)
        assertNull(hdCopyOf(listOf(uhd, hd), 2))
        assertNull(hdCopyOf(listOf(hd, copy(3, 1920, 1080)), 2))
    }

    @Test fun nothingLowerThan1080pAndNoConvertingAsAFallback() {
        assertNull(hdCopyOf(listOf(uhd), 1))
        assertNull(hdCopyOf(listOf(uhd, copy(2, 1280, 720)), 1))
    }

    @Test fun wideFilmsAreClassedByWidth() {
        val wide4k = copy(1, 3840, 1600, size = 50_000_000_000)
        assertEquals(2, hdCopyOf(listOf(wide4k, copy(2, 1920, 800, size = 9_000_000_000)), 1)?.fileId)
        assertNull(hdCopyOf(listOf(copy(5, 3840, 1600), wide4k), 1))
    }

    @Test fun aDifferentCutWouldLoseThePlace() {
        assertNull(hdCopyOf(listOf(uhd, copy(2, 1920, 1080, duration = 8000.0)), 1))
        assertNull(hdCopyOf(listOf(uhd, copy(2, 1920, 1080, edition = "Director's Cut")), 1))
        assertEquals(2, hdCopyOf(listOf(uhd, copy(2, 1920, 1080, duration = 7230.0)), 1)?.fileId)
    }

    @Test fun takesTheSmallestAndNeverABiggerFile() {
        val remux = copy(2, 1920, 1080, size = 30_000_000_000)
        val encode = copy(3, 1920, 1080, size = 8_000_000_000)
        assertEquals(3, hdCopyOf(listOf(uhd, remux, encode), 1)?.fileId)
        assertNull(hdCopyOf(listOf(copy(1, 3840, 2160, size = 5_000_000_000), remux), 1))
    }

    @Test fun audioFollowsTheLanguageNotThePosition() {
        val from = listOf(AudioTrack(language = "en"), AudioTrack(language = "fr"))
        val to = listOf(AudioTrack(language = "fr"), AudioTrack(language = "en"))
        assertEquals(0, sameLanguageAudio(from, 1, to))
        assertNull(sameLanguageAudio(from, 1, listOf(AudioTrack(language = "de"))))
        assertNull(sameLanguageAudio(from, null, to))
    }

    @Test fun subtitlesFollowTheLanguageOrStayOff() {
        val tracks = listOf(Track(4, "English", false, "en"), Track(5, "French", true, "fr"))
        assertEquals(CarriedSubtitle("fr"), carriedSubtitle(tracks))
        assertEquals(CarriedSubtitle(null, off = true), carriedSubtitle(listOf(Track(4, "English", false, "en"))))
        assertNull(carriedSubtitle(listOf(Track(4, "Unknown", true))))
        assertEquals("5", carriedSubtitleId(CarriedSubtitle("FR"), tracks))
        assertEquals("no", carriedSubtitleId(CarriedSubtitle(null, off = true), tracks))
        assertNull(carriedSubtitleId(CarriedSubtitle("de"), tracks))
        assertNull(carriedSubtitleId(null, tracks))
    }

    @Test fun theSwitchAsksForExactlyThatFileAtThatMoment() = runBlocking {
        val sent = mutableListOf<PlaybackStartRequestV3>()
        val launcher = PlaybackLauncher(
            detail = { ApiResult.Success(WatchDetail(contentId = it, type = "movie", title = "M", versions = listOf(uhd, copy(2, 1920, 1080)))) },
            start = { sent += it; ApiResult.Error(500, "x", "stop here") },
            profileId = { "p" },
            serverUrl = { "http://nas" },
        )
        launcher.launch("m", fileId = 2, positionSeconds = 1234.5, fixedFile = true)
        val request = sent.single()
        assertEquals(2, request.fileId)
        assertEquals(1234.5, request.startPosition)
        assertEquals(false, request.allowAlternateVersions)
    }
}
