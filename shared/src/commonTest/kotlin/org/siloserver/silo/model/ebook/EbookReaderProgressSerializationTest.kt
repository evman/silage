package org.siloserver.silo.model.ebook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class EbookReaderProgressSerializationTest {

    // Mirrors SiloJson (SiloHttpClientImpl.kt).
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * GET /api/v1/ebooks/{id}/progress returns `{}` when no progress has been
     * saved (book never opened). Before the fix this threw MissingFieldException
     * because contentId/fileId/location were non-nullable. After the fix, all
     * three must default to null so an unread book starts from the beginning.
     */
    @Test
    fun decodesEmptyProgressObjectWithoutThrowingMissingFieldException() {
        val progress = json.decodeFromString<EbookReaderProgress>("{}")

        assertNull(progress.contentId, "contentId should be null when missing from response")
        assertNull(progress.fileId, "fileId should be null when missing from response")
        assertNull(progress.location, "location should be null when missing from response")
        assertEquals(0.0, progress.progress, "progress should default to 0.0")
        assertNull(progress.updatedAt, "updatedAt should be null when missing from response")
    }

    /**
     * A fully-populated progress response must still round-trip correctly so
     * existing resume-from-position behaviour is not broken.
     */
    @Test
    fun decodesFullProgressObject() {
        val raw = """
            {
              "content_id": "ebook-123",
              "file_id": 42,
              "location": "epubcfi(/6/4[chap01ref]!/4[body01]/10[para05]/2/1:3)",
              "progress": 0.37,
              "updated_at": "2024-01-15T10:30:00Z"
            }
        """.trimIndent()

        val progress = json.decodeFromString<EbookReaderProgress>(raw)

        assertEquals("ebook-123", progress.contentId)
        assertEquals(42, progress.fileId)
        assertEquals("epubcfi(/6/4[chap01ref]!/4[body01]/10[para05]/2/1:3)", progress.location)
        assertEquals(0.37, progress.progress)
        assertEquals("2024-01-15T10:30:00Z", progress.updatedAt)
    }
}
