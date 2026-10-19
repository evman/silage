package dev.silage.app.detail

import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.EpisodeFile
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.VideoTrack
import kotlin.test.Test
import kotlin.test.assertEquals

class VersionOptionTest {
    private val uhd = FileVersion(
        fileId = 2, resolution = "2160p", codecVideo = "hevc", codecAudio = "truehd", hdr = true, fileSize = 58L * 1_000_000_000, bitrate = 60_000,
        videoTracks = listOf(VideoTrack(hdr = true, hdrFormat = "HDR10", height = 2160)), audioTracks = listOf(AudioTrack(codec = "truehd", channels = 8)),
    )
    private val hd = FileVersion(
        fileId = 1, resolution = "1080p", codecVideo = "h264", codecAudio = "eac3", fileSize = 14_200_000_000, bitrate = 16_000,
        audioTracks = listOf(AudioTrack(codec = "eac3", channels = 6)), editionRaw = "IMAX",
    )

    @Test fun labelsReadLikeTheChoiceTheyAre() {
        assertEquals("4K · HDR10", uhd.toOption().short)
        assertEquals("4K · HDR10 · HEVC · TrueHD 7.1 · 58 GB", uhd.toOption().detail)
        assertEquals("1080p", hd.toOption().short)
        assertEquals("IMAX · 1080p · H.264 · E-AC3 5.1 · 14 GB", hd.toOption().detail)
    }

    @Test fun dolbyVisionIsNamed() {
        val dv = uhd.copy(videoTracks = listOf(VideoTrack(hdr = true, dolbyVision = "dvhe.08", height = 2160)))
        assertEquals("4K · Dolby Vision", dv.toOption().short)
        assertEquals("4K · Dolby Vision · Profile 8 · HEVC · TrueHD 7.1 · 58 GB", dv.toOption().detail)
    }

    @Test fun structuredDolbyProfileIsShownWithoutCodecString() {
        val dv = uhd.copy(videoTracks = listOf(VideoTrack(dolbyVisionProfile = 5, height = 2160)))
        assertEquals("4K · Dolby Vision", dv.toOption().short)
        assertEquals("4K · Dolby Vision · Profile 5 · HEVC · TrueHD 7.1 · 58 GB", dv.toOption().detail)
    }

    @Test fun selectingTwoCopiesWithTheSameLabelHonorsTheActualFile() {
        val copies = listOf(
            uhd.copy(fileId = 20, videoTracks = listOf(VideoTrack(dolbyVisionProfile = 8, height = 2160))).toOption(),
            uhd.copy(fileId = 21, videoTracks = listOf(VideoTrack(dolbyVisionProfile = 5, height = 2160))).toOption(),
        )
        assertEquals(21, chooseVersion(copies, "4K · Dolby Vision", lastFileId = 20, preferredFileId = 21)?.fileId)
        // That file id belongs to the previous episode: retain the version kind instead.
        assertEquals(20, chooseVersion(copies, "4K · Dolby Vision", lastFileId = null, preferredFileId = 999)?.fileId)
    }

    @Test fun smallFilesShowADecimalAndSdIsNamed() {
        val sd = FileVersion(fileId = 3, resolution = "480p", codecVideo = "mpeg2video", fileSize = 2_400_000_000)
        assertEquals("SD", sd.toOption().short)
        assertEquals("SD · MPEG-2 · 2.4 GB", sd.toOption().detail)
    }

    @Test fun episodeFilesBecomeOptionsToo() {
        val o = EpisodeFile(fileId = 9, resolution = "2160p", codecVideo = "hevc", hdr = true, audioChannels = 6, fileSize = 8_000_000_000).toOption()
        assertEquals("4K · HDR", o.short)
        assertEquals("4K · HDR · HEVC · 5.1 · 8.0 GB", o.detail)
    }

    @Test fun bestFirstAndTheLastPlayedCopyIsTheDefault() {
        val options = listOf(hd.toOption(), uhd.toOption()).sortedBest()
        assertEquals(listOf(2, 1), options.map { it.fileId })
        assertEquals(1, defaultVersion(options, lastFileId = 1)?.fileId)
        assertEquals(2, defaultVersion(options, lastFileId = null)?.fileId)
        assertEquals(2, defaultVersion(options, lastFileId = 777)?.fileId)
        assertEquals(null, defaultVersion(emptyList(), null))
    }

    @Test fun otherEpisodesFollowTheKindOfCopyThePersonPicked() {
        val ep = listOf(
            EpisodeFile(fileId = 10, resolution = "1080p", fileSize = 2_000_000_000).toOption(),
            EpisodeFile(fileId = 11, resolution = "2160p", hdr = true, fileSize = 8_000_000_000).toOption(),
        )
        assertEquals(10, chooseVersion(ep, preferredShort = "1080p", lastFileId = null)?.fileId)
        assertEquals(11, chooseVersion(ep, preferredShort = "4K · HDR", lastFileId = 10)?.fileId)
        // No preference (or no match): last played, else best.
        assertEquals(10, chooseVersion(ep, preferredShort = null, lastFileId = 10)?.fileId)
        assertEquals(11, chooseVersion(ep, preferredShort = "720p", lastFileId = null)?.fileId)
    }
}
