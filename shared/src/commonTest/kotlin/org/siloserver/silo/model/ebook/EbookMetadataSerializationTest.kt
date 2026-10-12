package org.siloserver.silo.model.ebook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class EbookMetadataSerializationTest {
    // Mirrors SiloJson (SiloHttpClientImpl.kt). Note that
    // coerceInputValues only substitutes defaults for null/invalid input
    // when a default exists — it cannot repair missing non-nullable
    // Strings, which is why MediaRelatedItem needs explicit defaults.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun decodesRelatedContentWithIncompleteEntries() {
        val related = json.decodeFromString<MediaRelatedContent>(
            """
            {
              "also_by_author": [
                { "content_id": "b-1", "title": "Complete Entry", "year": 2024 },
                { "year": 2023 }
              ],
              "similar": [
                { "content_id": null, "title": null }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(2, related.alsoByAuthor.size)
        assertEquals("b-1", related.alsoByAuthor[0].contentId)
        assertEquals("", related.alsoByAuthor[1].contentId)
        assertEquals("", related.alsoByAuthor[1].title)
        assertEquals(2023, related.alsoByAuthor[1].year)
        assertEquals("", related.similar.single().contentId)
        assertEquals("", related.similar.single().title)
    }

    @Test
    fun decodesSeriesGroupWithIncompleteEntry() {
        val series = json.decodeFromString<MediaSeriesGroup>(
            """
            { "name": "Silo Stories", "entries": [ { "series_index": 2.0 } ] }
            """.trimIndent(),
        )

        assertEquals("Silo Stories", series.name)
        assertEquals("", series.entries.single().contentId)
        assertEquals(2.0, series.entries.single().seriesIndex)
    }
}
