package org.siloserver.silo.model.catalog

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VersionChapterSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `VersionChapter round-trips snake_case fields`() {
        val source = """
            {
              "index": 2,
              "title": "Chapter 3",
              "start_seconds": 412.5,
              "end_seconds": 612.0,
              "source": "embedded",
              "thumbnail_url": "/api/v1/chapters/thumb/abc.jpg",
              "thumbnail_thumbhash": "abc123"
            }
        """.trimIndent()

        val ch = json.decodeFromString<VersionChapter>(source)

        assertEquals(2, ch.index)
        assertEquals("Chapter 3", ch.title)
        assertEquals(412.5, ch.startSeconds)
        assertEquals(612.0, ch.endSeconds)
        assertEquals("embedded", ch.source)
        assertEquals("/api/v1/chapters/thumb/abc.jpg", ch.thumbnailUrl)
        assertEquals("abc123", ch.thumbnailThumbhash)
    }

    @Test
    fun `FileVersion with chapters decodes`() {
        val source = """
            {
              "file_id": 7,
              "chapters": [
                {"index": 0, "title": "Open", "start_seconds": 0.0, "end_seconds": 120.0},
                {"index": 1, "title": "Act 1", "start_seconds": 120.0, "end_seconds": 800.0}
              ]
            }
        """.trimIndent()

        val v = json.decodeFromString<FileVersion>(source)
        assertNotNull(v.chapters)
        assertEquals(2, v.chapters!!.size)
        assertEquals("Open", v.chapters!![0].title)
        assertEquals(800.0, v.chapters!![1].endSeconds)
    }

    @Test
    fun `FileVersion without chapters decodes to null`() {
        val source = """{ "file_id": 7 }"""
        val v = json.decodeFromString<FileVersion>(source)
        assertNull(v.chapters)
    }
}
