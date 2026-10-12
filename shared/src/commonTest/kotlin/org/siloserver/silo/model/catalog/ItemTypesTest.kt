package org.siloserver.silo.model.catalog

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItemTypesTest {
    @Test
    fun identifiesAudiobookItemTypes() {
        assertTrue(isAudiobookItemType("audiobook"))
        assertTrue(isAudiobookItemType("AUDIOBOOK"))
        assertTrue(isAudiobookItemType(" audiobook "))

        assertFalse(isAudiobookItemType("audiobooks"))
        assertFalse(isAudiobookItemType("book"))
        assertFalse(isAudiobookItemType("movie"))
        assertFalse(isAudiobookItemType(""))
        assertFalse(isAudiobookItemType(null))
    }

    @Test
    fun identifiesEpisodeItemTypes() {
        assertTrue(isEpisodeItemType("episode"))
        assertTrue(isEpisodeItemType("EPISODE"))
        assertTrue(isEpisodeItemType("Episode"))
        assertTrue(isEpisodeItemType(" episode "))
        assertTrue(isEpisodeItemType(" Episode "))

        assertFalse(isEpisodeItemType("episodes"))
        assertFalse(isEpisodeItemType("movie"))
        assertFalse(isEpisodeItemType(""))
        assertFalse(isEpisodeItemType(null))
    }

    @Test
    fun identifiesBookLikeItemTypes() {
        listOf("book", "ebook", "comic", "manga").forEach { type ->
            assertTrue(isBookLikeItemType(type), "$type should be book-like")
            assertTrue(isBookLikeItemType(type.uppercase()), "${type.uppercase()} should be book-like")
        }

        // Item types are singular, unlike the plural library-type taxonomy.
        assertFalse(isBookLikeItemType("books"))
        assertFalse(isBookLikeItemType("ebooks"))
        assertFalse(isBookLikeItemType("audiobook"))
        assertFalse(isBookLikeItemType("movie"))
        assertFalse(isBookLikeItemType(null))
    }
}
