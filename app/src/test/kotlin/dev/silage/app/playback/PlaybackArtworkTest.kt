package dev.silage.app.playback

import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.catalog.ItemDetail
import kotlin.test.*

class PlaybackArtworkTest {
    private fun detail(poster: String?, still: Boolean? = null, series: String? = null, season: String? = null) =
        ItemDetail(contentId = "c", type = "episode", title = "Pilot",
            posterUrl = poster, posterIsEpisodeStill = still, seriesPosterUrl = series, seasonPosterUrl = season)

    @Test fun episodesPreferTheSeasonThenSeriesPosterAndNeverAStill() {
        assertEquals(listOf("https://m.example.org/season.webp", "https://m.example.org/series.webp"),
            posterCandidates(detail("https://m.example.org/still.webp", true, "https://m.example.org/series.webp", "https://m.example.org/season.webp")))
        assertEquals(emptyList(), posterCandidates(detail("https://m.example.org/still.webp", true)))
        assertEquals(listOf("https://m.example.org/film.webp"), posterCandidates(detail("https://m.example.org/film.webp")))
    }

    @Test fun relativeOrOddLinksAreLeftOut() {
        assertEquals(emptyList(), posterCandidates(detail("/local/poster.webp", series = "file:///x.png")))
        assertEquals(listOf("http://nas:8096/p.webp"), posterCandidates(detail("http://nas:8096/p.webp")))
    }

    @Test fun ownArtworkSkipsTheLookup() = runTest {
        var looked = false
        val r = playbackPosters(PlaybackMeta(artworkUrl = "https://m.example.org/own.webp", contentId = "c")) { looked = true; null }
        assertEquals(listOf("https://m.example.org/own.webp"), r)
        assertFalse(looked)
    }

    @Test fun otherwiseTheFullDetailsAreAsked() = runTest {
        val r = playbackPosters(PlaybackMeta(contentId = "ep-1")) { id -> assertEquals("ep-1", id); detail(null, series = "https://m.example.org/s.webp") }
        assertEquals(listOf("https://m.example.org/s.webp"), r)
    }

    @Test fun noIdOrAFailedLookupGivesNothing() = runTest {
        assertEquals(emptyList(), playbackPosters(PlaybackMeta(title = "Trailer", trailer = true)) { fail("no lookup without an id") })
        assertEquals(emptyList(), playbackPosters(PlaybackMeta(contentId = "c")) { error("offline") })
    }
}

class OverlayPictureTest {
    private fun webp(): ByteArray {
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(20, 30)
        surface.canvas.clear(0xFF3366CC.toInt())
        return surface.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.WEBP)!!.bytes
    }

    @Test fun webpPostersBecomePngForWindows() {
        val png = pngPicture(webp())!!
        assertContentEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), png.copyOfRange(0, 4))
        val back = org.jetbrains.skia.Image.makeFromEncoded(png)
        assertEquals(20, back.width); assertEquals(30, back.height)
    }

    @Test fun unreadableOrMissingPicturesGiveNothing() {
        assertNull(pngPicture("not a picture".encodeToByteArray()))
        assertNull(overlayPicture("https://m.example.org/p.webp") { null })
        assertNull(overlayPicture("https://m.example.org/p.webp") { error("offline") })
        assertNotNull(overlayPicture("https://m.example.org/p.webp") { webp() })
    }
}
