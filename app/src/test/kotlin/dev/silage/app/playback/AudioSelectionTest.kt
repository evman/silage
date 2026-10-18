package dev.silage.app.playback

import dev.silage.app.playback.hud.*
import org.siloserver.silo.model.catalog.AudioTrack
import kotlin.test.*

class AudioSelectionTest {
    private val source = listOf(AudioTrack(index = 1, language = "eng", title = "Main"), AudioTrack(index = 5, language = "fra", title = "French"), AudioTrack(index = 9, title = "Commentary"))
    @Test fun packagedSingleTrackStillOffersEverySourceOrdinal() {
        val mounted = listOf(Track(1, "AAC", true))
        val rows = audioMenuTracks(source, mounted, direct = false, selectedOrdinal = 1)
        assertEquals(listOf(0, 1, 2), rows.map { it.id })
        assertEquals(listOf(false, true, false), rows.map { it.selected })
        assertTrue(rows[2].label.contains("Commentary"))
    }
    @Test fun originalFileMapsOrdinalToActualMpvIdNotFfmpegIndex() {
        val mounted = listOf(Track(2, "Main", false), Track(4, "French", true), Track(7, "Commentary", false))
        assertEquals(4, nativeAudioId(mounted, 1))
        assertNull(nativeAudioId(mounted, 3))
        assertEquals(mounted, audioMenuTracks(source, mounted, direct = true, selectedOrdinal = 0))
    }
}
