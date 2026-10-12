package org.siloserver.silo.model.ebook

import org.siloserver.silo.model.catalog.FileVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EbookFormatSupportTest {
    @Test
    fun `epub pdf cbz txt and markdown are in app readable`() {
        listOf("epub", "pdf", "cbz", "txt", "md", "markdown").forEach { extension ->
            val support = ebookFormatSupport(extension)

            assertEquals(EbookReadMode.InApp, support.readMode)
            assertTrue(support.canDownloadOriginal)
            assertTrue(support.canReadInApp)
        }
    }

    @Test
    fun `fb2 and fbz are in app readable`() {
        listOf("fb2", "fbz").forEach { extension ->
            val support = ebookFormatSupport(extension)

            assertEquals(EbookReadMode.InApp, support.readMode)
            assertTrue(support.canDownloadOriginal)
            assertTrue(support.canReadInApp)
        }
    }

    @Test
    fun `mobi azw and cbr are external only originals`() {
        listOf("mobi", "azw", "azw3", "cbr").forEach { extension ->
            val support = ebookFormatSupport(extension)

            assertEquals(EbookReadMode.ExternalOnly, support.readMode)
            assertTrue(support.canDownloadOriginal)
            assertFalse(support.canReadInApp)
        }
    }

    @Test
    fun `unknown formats are unsupported by ebook flows`() {
        val support = ebookFormatSupport("jpg")

        assertEquals(EbookReadMode.Unsupported, support.readMode)
        assertFalse(support.canDownloadOriginal)
        assertFalse(support.canReadInApp)
    }

    @Test
    fun `file version support derives from container or file name`() {
        assertEquals(
            EbookReadMode.InApp,
            FileVersion(fileId = 1, fileName = "notes", container = "txt").ebookFormatSupport().readMode,
        )
        assertEquals(
            EbookReadMode.ExternalOnly,
            FileVersion(fileId = 2, fileName = "book.mobi", container = null).ebookFormatSupport().readMode,
        )
        assertEquals(
            EbookReadMode.Unsupported,
            FileVersion(fileId = 3, fileName = "cover.jpg", container = null).ebookFormatSupport().readMode,
        )
    }
}
