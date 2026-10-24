package dev.silage.app.detail

import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.CastMember
import org.siloserver.silo.model.catalog.CrewMember
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.catalog.VideoTrack
import kotlin.test.Test
import kotlin.test.assertEquals

class TitleInfoTest {
    private val item = ItemDetail(contentId = "m", type = "movie", title = "Ju-on", runtime = 92, genres = listOf("Horror"),
        studios = listOf("Pioneer"), cast = listOf(CastMember("B", "Kayako", order = 1), CastMember("A", "Rika", order = 0)),
        crew = listOf(CrewMember("Shimizu", "Director"), CrewMember("Shimizu", "Writer"), CrewMember("Ichise", "Producer")))
    private val file = FileVersion(fileId = 1, fileName = "Ju-on.mkv", container = "mkv", fileSize = 58_300_000_000, duration = 5522.0, bitrate = 95_900,
        videoTracks = listOf(VideoTrack(codec = "hevc", width = 3840, height = 2160, frameRate = 23.976, bitDepth = 10, dolbyVisionProfile = 7, hdr = true, hdrFormat = "HDR10")),
        audioTracks = listOf(AudioTrack(codec = "dts", channels = 6, language = "ja", isDefault = true, bitrate = 3_000_000)),
        subtitleTracks = listOf(SubtitleTrack(codec = "subrip", language = "en", forced = true)))

    @Test fun detailsCastAndCrewAreListedAndEmptySectionsLeftOut() {
        val sections = titleInfoSections(item, null).associateBy { it.title }
        assertEquals(setOf("Details", "Cast", "Crew"), sections.keys)
        assertEquals(listOf("Runtime" to "1h 32m", "Genres" to "Horror", "Studios" to "Pioneer"), sections.getValue("Details").rows)
        assertEquals(listOf("A" to "Rika", "B" to "Kayako"), sections.getValue("Cast").rows)
        assertEquals(listOf("Director" to "Shimizu", "Writer" to "Shimizu", "Producer" to "Ichise"), sections.getValue("Crew").rows)
    }

    @Test fun theFileAndItsTracksAreDescribed() {
        val sections = titleInfoSections(item, file).associateBy { it.title }
        val f = sections.getValue("File").rows.toMap()
        assertEquals("58.3 GB", f["Size"]); assertEquals("MKV", f["Container"]); assertEquals("95.9 Mbps", f["Bitrate"]); assertEquals("1h 32m 2s", f["Duration"])
        val v = sections.getValue("Video").rows.toMap()
        assertEquals("3840×2160", v["Resolution"]); assertEquals("23.976 fps", v["Frame rate"]); assertEquals("10-bit", v["Bit depth"])
        assertEquals("Dolby Vision (Profile 7) · HDR10", v["Dynamic range"])
        assertEquals(listOf("Japanese" to "DTS · 5.1 · 3 Mbps · Default"), sections.getValue("Audio").rows)
        assertEquals(listOf("English" to "SRT · Forced"), sections.getValue("Subtitles").rows)
    }

    @Test fun sizesAndRatesReadNaturally() {
        assertEquals("712 MB", formatBytes(712_000_000)); assertEquals(null, formatBytes(0)); assertEquals("1.5 GB", formatBytes(1_500_000_000))
        assertEquals(640, trackKbps(640_000)); assertEquals(640, trackKbps(640))
        assertEquals("48m 10s", formatDuration(2890.0))
    }
}
