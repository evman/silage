package dev.silage.app.playback

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SubtitleCommandsTest {
    @Test fun subAddSelectsOnlyTheWantedTrack() {
        assertContentEquals(
            arrayOf("sub-add", "http://nas/s/1.vtt", "select", "English SDH", "en"),
            SubtitleCommands.add(SubtitleLoad("http://nas/s/1.vtt", "English SDH", "en", select = true)),
        )
        assertContentEquals(
            arrayOf("sub-add", "http://nas/s/2.srt", "auto", "Subtitle 3"),
            SubtitleCommands.add(SubtitleLoad("http://nas/s/2.srt", "Subtitle 3", null, select = false)),
        )
    }

    @Test fun builtInSubtitleIsFoundByItsStreamIndex() {
        val tracks = """[{"id":1,"type":"video","ff-index":0},{"id":1,"type":"audio","ff-index":1},
            {"id":1,"type":"sub","ff-index":2},{"id":2,"type":"sub","ff-index":3}]"""
        assertEquals(2, SubtitleCommands.sidForStream(tracks, 3))
        assertEquals(null, SubtitleCommands.sidForStream(tracks, 9))
        assertEquals(null, SubtitleCommands.sidForStream("garbage", 3))
    }
}
