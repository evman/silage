package org.siloserver.silo.model.ebook

import org.siloserver.silo.model.book.BookFormat
import org.siloserver.silo.model.catalog.FileVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EbookVersionSelectionTest {
    @Test
    fun choosesRequestedSupportedFileFirst() {
        val versions = listOf(
            FileVersion(fileId = 1, fileName = "book.pdf", container = "pdf"),
            FileVersion(fileId = 2, fileName = "book.epub", container = "epub"),
        )

        assertEquals(1, chooseEbookVersion(versions, requestedFileId = 1)?.fileId)
    }

    @Test
    fun prefersEpubThenPdfWhenNoRequest() {
        val versions = listOf(
            FileVersion(fileId = 1, fileName = "book.pdf", container = "pdf"),
            FileVersion(fileId = 2, fileName = "book.epub", container = "epub"),
        )

        assertEquals(2, chooseEbookVersion(versions, requestedFileId = null)?.fileId)
    }

    @Test
    fun fallsBackToReadableContainerOrExtension() {
        val versions = listOf(
            FileVersion(fileId = 1, fileName = "book.mobi", container = "mobi"),
            FileVersion(fileId = 2, fileName = "book.cbz", container = null),
        )

        assertEquals(2, chooseEbookVersion(versions, requestedFileId = null)?.fileId)
    }

    @Test
    fun choosesFictionBookWhenNoHigherPriorityReadableFormatExists() {
        val versions = listOf(
            FileVersion(fileId = 1, fileName = "book.mobi", container = "mobi"),
            FileVersion(fileId = 2, fileName = "book.fbz", container = "fbz"),
            FileVersion(fileId = 3, fileName = "book.fb2", container = "fb2"),
        )

        assertEquals(3, chooseEbookVersion(versions, requestedFileId = null)?.fileId)
    }

    @Test
    fun externalOnlyEbookFormatsAreNotChosenForInAppReading() {
        val versions = listOf(
            FileVersion(fileId = 1, fileName = "book.mobi", container = "mobi"),
            FileVersion(fileId = 2, fileName = "book.cbr", container = "cbr"),
            FileVersion(fileId = 3, fileName = "book.azw3", container = "azw3"),
        )

        assertNull(chooseEbookVersion(versions, requestedFileId = null))
        assertNull(chooseEbookVersion(versions, requestedFileId = 2))
    }

    @Test
    fun requestedExternalOnlyVersionIsAReaderTarget() {
        val versions = listOf(
            FileVersion(fileId = 1, fileName = "book.epub", container = "epub"),
            FileVersion(fileId = 2, fileName = "book.mobi", container = "mobi"),
        )

        val target = chooseReaderVersion(versions, requestedFileId = 2)

        assertEquals(2, target?.version?.fileId)
        assertEquals(BookFormat.Mobi, target?.format)
        assertEquals(EbookReadMode.ExternalOnly, target?.support?.readMode)
    }

    @Test
    fun readerTargetFallsBackToExternalOriginalWhenNoInAppFormatExists() {
        val versions = listOf(
            FileVersion(fileId = 1, fileName = "book.azw3", container = "azw3"),
            FileVersion(fileId = 2, fileName = "book.mobi", container = "mobi"),
        )

        val target = chooseReaderVersion(versions, requestedFileId = null)

        assertEquals(2, target?.version?.fileId)
        assertEquals(BookFormat.Mobi, target?.format)
        assertEquals(EbookReadMode.ExternalOnly, target?.support?.readMode)
    }

    @Test
    fun readerTargetStillPrefersInAppFormatsWhenAvailable() {
        val versions = listOf(
            FileVersion(fileId = 1, fileName = "book.mobi", container = "mobi"),
            FileVersion(fileId = 2, fileName = "book.epub", container = "epub"),
        )

        val target = chooseReaderVersion(versions, requestedFileId = null)

        assertEquals(2, target?.version?.fileId)
        assertEquals(BookFormat.Epub, target?.format)
        assertEquals(EbookReadMode.InApp, target?.support?.readMode)
    }

    @Test
    fun requestedUnsupportedVersionFallsThroughToReadableVersion() {
        val versions = listOf(
            FileVersion(fileId = 1, fileName = "cover.jpg", container = "jpg"),
            FileVersion(fileId = 2, fileName = "book.epub", container = "epub"),
        )

        assertEquals(2, chooseReaderVersion(versions, requestedFileId = 1)?.version?.fileId)
    }

    @Test
    fun requestedMissingReaderVersionFallsThroughToReadableVersion() {
        val versions = listOf(
            FileVersion(fileId = 2, fileName = "book.epub", container = "epub"),
            FileVersion(fileId = 3, fileName = "book.mobi", container = "mobi"),
        )

        assertEquals(2, chooseReaderVersion(versions, requestedFileId = 1)?.version?.fileId)
    }

    @Test
    fun returnsNullWhenNothingReadable() {
        val versions = listOf(FileVersion(fileId = 1, fileName = "cover.jpg", container = "jpg"))

        assertNull(chooseEbookVersion(versions, requestedFileId = null))
    }

    @Test
    fun derivesBookFormatFromContainerWhenFileNameHasNoExtension() {
        val version = FileVersion(fileId = 1, fileName = "read", container = "pdf")

        assertEquals(BookFormat.Pdf, version.bookFormatFromEbookVersion())
    }

    @Test
    fun fallsBackToFileNameWhenContainerIsMissing() {
        val version = FileVersion(fileId = 1, fileName = "book.epub", container = null)

        assertEquals(BookFormat.Epub, version.bookFormatFromEbookVersion())
    }

    @Test
    fun normalizesMarkdownContainerToMarkdownBookFormat() {
        val version = FileVersion(fileId = 1, fileName = "notes", container = "markdown")

        assertEquals("md", version.ebookFormatKey())
        assertEquals(BookFormat.Markdown, version.bookFormatFromEbookVersion())
    }

    @Test
    fun `fb2 zip filename routes through fictionbook zip reader`() {
        val version = FileVersion(fileId = 1, fileName = "book.fb2.zip", container = null)

        assertEquals("fbz", version.ebookFormatKey())
        assertEquals(BookFormat.Fbz, version.bookFormatFromEbookVersion())
    }

    @Test
    fun parsesPageProgressLocationsGracefully() {
        assertEquals(37, ebookPageNumberFromProgressLocation("page:37"))
        assertNull(ebookPageNumberFromProgressLocation("page:not-a-number"))
        assertNull(ebookPageNumberFromProgressLocation("chapter:1"))
        assertNull(ebookPageNumberFromProgressLocation(null))
    }

    @Test
    fun calculatesPageProgressFromKnownPageCount() {
        assertEquals(0.5, ebookProgressPercentForPage(page = 50, pageCount = 101, currentProgress = 0.0))
        assertEquals(1.0, ebookProgressPercentForPage(page = 150, pageCount = 101, currentProgress = 0.0))
        assertEquals(0.0, ebookProgressPercentForPage(page = -5, pageCount = 101, currentProgress = 0.3))
    }

    @Test
    fun preservesProgressWhenPageCountIsUnknown() {
        assertEquals(0.42, ebookProgressPercentForPage(page = 100, pageCount = null, currentProgress = 0.42))
        assertEquals(0.0, ebookProgressPercentForPage(page = 100, pageCount = 0, currentProgress = 0.0))
    }

    @Test
    fun `ebook format labels are user readable`() {
        assertEquals("EPUB", "epub".ebookFormatDisplayName())
        assertEquals("EPUB", ".EPUB".ebookFormatDisplayName())
        assertEquals("PDF", "pdf".ebookFormatDisplayName())
        assertEquals("Comic Book ZIP", "cbz".ebookFormatDisplayName())
        assertEquals("Comic Book RAR", "cbr".ebookFormatDisplayName())
        assertEquals("FictionBook", "fb2".ebookFormatDisplayName())
        assertEquals("FictionBook", "fbz".ebookFormatDisplayName())
        assertEquals("MOBI", "mobi".ebookFormatDisplayName())
        assertEquals("Kindle", "azw3".ebookFormatDisplayName())
        assertEquals("Kindle", "azw".ebookFormatDisplayName())
        assertEquals("Markdown", "md".ebookFormatDisplayName())
        assertEquals("Unknown", "".ebookFormatDisplayName())
        assertEquals("Unknown", "unknown".ebookFormatDisplayName())
        assertEquals("DOCX", "docx".ebookFormatDisplayName())
        assertEquals("DOCX", ".docx".ebookFormatDisplayName())
    }

    @Test
    fun `in app readable excludes external only formats`() {
        assertTrue("epub".isInAppReadableEbookFormat())
        assertTrue("pdf".isInAppReadableEbookFormat())
        assertTrue("cbz".isInAppReadableEbookFormat())
        assertTrue("txt".isInAppReadableEbookFormat())
        assertTrue("md".isInAppReadableEbookFormat())
        assertFalse("cbr".isInAppReadableEbookFormat())
        assertFalse("mobi".isInAppReadableEbookFormat())
        assertFalse("azw3".isInAppReadableEbookFormat())
        assertTrue("fb2".isInAppReadableEbookFormat())
        assertTrue("fbz".isInAppReadableEbookFormat())
    }

    @Test
    fun `kindle promotion makes mobi in-app epub when conversion available`() {
        val target = chooseReaderVersion(
            listOf(FileVersion(fileId = 9, fileName = "book.mobi", container = "mobi")),
            requestedFileId = null,
        )
        assertEquals(EbookReadMode.ExternalOnly, target?.support?.readMode)

        val promoted = target!!.promotedForKindleConversion(available = true)
        assertEquals(EbookReadMode.InApp, promoted.support.readMode)
        assertTrue(promoted.support.canReadInApp)
        assertEquals(BookFormat.Epub, promoted.format)
        assertEquals(9, promoted.version.fileId) // file id unchanged; server converts that file
    }

    @Test
    fun `kindle promotion is a no-op when conversion unavailable`() {
        val target = chooseReaderVersion(
            listOf(FileVersion(fileId = 1, fileName = "book.azw3", container = "azw3")),
            requestedFileId = null,
        )!!
        val promoted = target.promotedForKindleConversion(available = false)
        assertEquals(EbookReadMode.ExternalOnly, promoted.support.readMode)
        assertEquals(target.format, promoted.format)
    }

    @Test
    fun `kindle promotion does not touch non-kindle in-app formats`() {
        val target = chooseReaderVersion(
            listOf(FileVersion(fileId = 2, fileName = "book.epub", container = "epub")),
            requestedFileId = null,
        )!!
        val promoted = target.promotedForKindleConversion(available = true)
        assertEquals(BookFormat.Epub, promoted.format)
        assertEquals(EbookReadMode.InApp, promoted.support.readMode)
        assertEquals(2, promoted.version.fileId)
    }
}
