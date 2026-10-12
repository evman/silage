package org.siloserver.silo.model.navigation

import org.siloserver.silo.model.personal.UserLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaModeTest {
    @Test
    fun mapsKnownVideoTypesToVideo() {
        val modes = listOf("movie", "movies", "series", "show", "shows", "tv", "video")
            .mapNotNull(::mobileMediaModeForLibraryType)
            .toSet()

        assertEquals(setOf(MediaMode.Video), modes)
    }

    @Test
    fun mobileMapsAudiobooksToAudio() {
        val libraries = listOf(
            userLibrary(id = 1, type = "audiobook"),
            userLibrary(id = 2, type = "audiobooks"),
        )

        assertEquals(
            listOf(MediaMode.Audio),
            libraries.mobileMediaModeCapabilities().modes,
        )
    }

    @Test
    fun tvMapsAudiobooksToAudio() {
        val libraries = listOf(
            userLibrary(id = 1, type = "audiobook"),
            userLibrary(id = 2, type = "audiobooks"),
        )

        assertEquals(
            listOf(MediaMode.Audio),
            libraries.tvMediaModeCapabilities().modes,
        )
    }

    @Test
    fun mobileMapsMusicLikeTypesToAudio() {
        val libraries = listOf("music", "album", "albums", "artist", "artists", "audio")
            .mapIndexed { index, type -> userLibrary(id = index + 1, type = type) }

        assertEquals(
            listOf(MediaMode.Audio),
            libraries.mobileMediaModeCapabilities().modes,
        )
    }

    @Test
    fun mobileMapsKnownReadingTypesToReading() {
        val libraries = listOf("ebook", "ebooks", "book", "books", "comic", "comics", "manga", "reading")
            .mapIndexed { index, type -> userLibrary(id = index + 1, type = type) }

        assertEquals(
            listOf(MediaMode.Reading),
            libraries.mobileMediaModeCapabilities().modes,
        )
    }

    @Test
    fun tvExcludesEbookComicAndMangaReadingTypesFromVisibleModes() {
        val libraries = listOf("ebook", "ebooks", "book", "books", "comic", "comics", "manga", "reading")
            .mapIndexed { index, type -> userLibrary(id = index + 1, type = type) }
        val capabilities = libraries.tvMediaModeCapabilities()

        assertEquals(emptyList(), capabilities.modes)
        assertEquals(false, capabilities.hasReading)
        assertEquals(emptyList(), capabilities.tvModes())
    }

    @Test
    fun identifiesReadingLibraryTypes() {
        val readingTypes = listOf("audiobook", "audiobooks", "ebook", "ebooks", "book", "books", "comic", "comics", "manga", "reading")

        assertEquals(readingTypes, readingTypes.filter(::isReadingLibraryType))
    }

    @Test
    fun filtersReadingLibrariesOnly() {
        val libraries = listOf(
            userLibrary(id = 1, type = "movies"),
            userLibrary(id = 2, type = "audiobooks"),
            userLibrary(id = 3, type = "ebooks"),
            userLibrary(id = 4, type = "music"),
        )

        assertEquals(listOf(2, 3), libraries.readingLibraries().map { it.id })
    }

    @Test
    fun classifiesEbookLikeLibraryTypes() {
        val ebookTypes = listOf("ebook", "ebooks", "book", "books", "comic", "comics", "manga", "reading")

        assertEquals(ebookTypes, ebookTypes.filter(::isEbookLikeLibraryType))
        assertEquals(emptyList(), listOf("audiobook", "audiobooks", "music", "movies").filter(::isEbookLikeLibraryType))
    }

    @Test
    fun classifiesAudiobookLikeLibraryTypes() {
        val audiobookTypes = listOf("audiobook", "audiobooks")

        assertEquals(audiobookTypes, audiobookTypes.filter(::isAudiobookLikeLibraryType))
        assertEquals(emptyList(), listOf("ebook", "music", "movies", "reading").filter(::isAudiobookLikeLibraryType))
    }

    @Test
    fun filtersReadingLibrariesByFormat() {
        val libraries = listOf(
            userLibrary(id = 1, type = "movies"),
            userLibrary(id = 2, type = "audiobooks"),
            userLibrary(id = 3, type = "ebooks"),
            userLibrary(id = 4, type = "manga"),
            userLibrary(id = 5, type = "music"),
        )

        assertEquals(listOf(2, 3, 4), libraries.filterByReadingFormat(ReadingFormatFilter.All).map { it.id })
        assertEquals(listOf(3, 4), libraries.filterByReadingFormat(ReadingFormatFilter.Ebooks).map { it.id })
        assertEquals(listOf(2), libraries.filterByReadingFormat(ReadingFormatFilter.Audiobooks).map { it.id })
    }

    @Test
    fun derivesAvailableReadingFormatsFromLibraries() {
        val mixedLibraries = listOf(
            userLibrary(id = 1, type = "ebooks"),
            userLibrary(id = 2, type = "audiobooks"),
        )
        val ebookLibraries = listOf(userLibrary(id = 3, type = "manga"))

        assertEquals(
            listOf(ReadingFormatFilter.All, ReadingFormatFilter.Ebooks, ReadingFormatFilter.Audiobooks),
            mixedLibraries.availableReadingFormatFilters(),
        )
        assertEquals(
            listOf(ReadingFormatFilter.Ebooks),
            ebookLibraries.availableReadingFormatFilters(),
        )
    }

    @Test
    fun ignoresBlankAndUnknownTypes() {
        val modes = listOf("", " ", "podcast", "photo").mapNotNull(::mobileMediaModeForLibraryType)

        assertEquals(emptyList(), modes)
    }

    @Test
    fun derivesCapabilitiesInStableOrder() {
        val libraries = listOf(
            userLibrary(id = 1, type = "ebooks"),
            userLibrary(id = 2, type = "movies"),
            userLibrary(id = 3, type = "audiobook"),
        )

        assertEquals(
            listOf(MediaMode.Video, MediaMode.Audio, MediaMode.Reading),
            libraries.mobileMediaModeCapabilities().modes,
        )
    }

    @Test
    fun tvCapabilitiesKeepAudiobooksAndExcludeReading() {
        val libraries = listOf(
            userLibrary(id = 1, type = "ebooks"),
            userLibrary(id = 2, type = "audiobooks"),
        )

        assertEquals(
            listOf(MediaMode.Audio),
            libraries.tvMediaModeCapabilities().tvModes(),
        )
    }

    private fun userLibrary(id: Int, type: String): UserLibrary =
        UserLibrary(id = id, name = "Library $id", type = type)
}

/**
 * A server library of type "mixed" holds movies and series in one folder.
 * Leaving it out of the video types did not degrade it — it erased it: the type
 * mapped to no [MediaMode], so the library never reached navigation, search or
 * browse on either platform and nothing indicated anything was missing.
 * silo-apple hit the same thing (#93).
 */
class MixedLibraryModeTest {

    @Test
    fun `a mixed library is a video library on both platforms`() {
        assertEquals(MediaMode.Video, mobileMediaModeForLibraryType("mixed"))
        assertEquals(MediaMode.Video, tvMediaModeForLibraryType("mixed"))
    }

    @Test
    fun `casing and padding from the server do not hide it`() {
        assertEquals(MediaMode.Video, mobileMediaModeForLibraryType(" Mixed "))
        assertEquals(MediaMode.Video, tvMediaModeForLibraryType("MIXED"))
    }

    /**
     * Types the client genuinely has no home for must still map to nothing, or
     * this fix turns "unknown" into "video" and puts photos in the film grid.
     */
    @Test
    fun `still refuses types with no home`() {
        assertNull(mobileMediaModeForLibraryType("photo"))
        assertNull(mobileMediaModeForLibraryType("podcast"))
    }
}
