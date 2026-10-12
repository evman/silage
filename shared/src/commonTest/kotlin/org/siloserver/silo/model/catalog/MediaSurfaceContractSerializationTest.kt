package org.siloserver.silo.model.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class MediaSurfaceContractSerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun decodesCurrentAudiobookExtension() {
        val detail = json.decodeFromString<ItemDetail>(
            """
            {
              "content_id": "aud-1",
              "type": "audiobook",
              "title": "The Long Listen",
              "versions": [
                {
                  "file_id": 11,
                  "file_name": "long-listen.m4b",
                  "container": "m4b",
                  "duration": 3600,
                  "chapters": [
                    { "index": 0, "title": "Opening", "start_seconds": 0, "end_seconds": 120 }
                  ]
                }
              ],
              "audiobook": {
                "authors": [
                  { "person_id": "p1", "name": "Ada Author", "photo_url": "/img/ada.jpg", "photo_thumbhash": "abc" }
                ],
                "narrators": [
                  { "person_id": "p2", "name": "Nia Narrator" }
                ],
                "publisher": "Silo Press",
                "total_duration_seconds": 3600,
                "series": {
                  "name": "Silo Stories",
                  "entries": [
                    { "content_id": "aud-1", "title": "The Long Listen", "year": 2026, "poster_url": "/p.jpg", "series_index": 1.0 }
                  ]
                },
                "other_narrations": [
                  { "content_id": "aud-2", "title": "The Long Listen", "year": 2025, "narrators": ["Other Voice"] }
                ],
                "related": {
                  "also_by_author": [
                    { "content_id": "aud-3", "title": "Another Listen", "year": 2024 }
                  ],
                  "similar": [
                    { "content_id": "aud-4", "title": "Close Enough", "year": 2023 }
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals("audiobook", detail.type)
        assertNotNull(detail.audiobook)
        assertEquals("Ada Author", detail.audiobook?.authorNames)
        assertEquals("Nia Narrator", detail.audiobook?.narratorNames)
        assertEquals(3600, detail.audiobook?.totalDurationSeconds)
        assertEquals("Silo Stories", detail.audiobook?.series?.name)
        assertEquals(11, detail.versions.single().fileId)
    }

    @Test
    fun decodesCurrentEbookExtensionWithoutLegacyBook() {
        val detail = json.decodeFromString<ItemDetail>(
            """
            {
              "content_id": "ebook-1",
              "type": "ebook",
              "title": "Readable Things",
              "versions": [
                { "file_id": 44, "file_name": "readable.epub", "container": "epub", "file_size": 1000 }
              ],
              "ebook": {
                "authors": [
                  { "person_id": "p7", "name": "Eve Writer" }
                ],
                "publisher": "Silo Press",
                "series": {
                  "name": "Readable Set",
                  "entries": [
                    { "content_id": "ebook-1", "title": "Readable Things", "year": 2026, "series_index": 2.0 }
                  ]
                },
                "related": {
                  "also_by_author": [],
                  "similar": [
                    { "content_id": "ebook-2", "title": "More Readable Things", "year": 2026 }
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals("ebook", detail.type)
        assertNotNull(detail.ebook)
        assertNull(detail.book)
        assertEquals("Eve Writer", detail.ebook?.authorNames)
        assertEquals("Silo Press", detail.ebook?.publisher)
        assertEquals(44, detail.versions.single().fileId)
    }

    @Test
    fun decodesUserRatingOnItemDetail() {
        val detail = json.decodeFromString<ItemDetail>(
            """
            { "content_id": "m1", "type": "movie", "title": "Rated Movie", "user_rating": 4 }
            """.trimIndent(),
        )
        assertEquals(4, detail.userRating)
    }

    @Test
    fun userRatingDefaultsToNullWhenAbsent() {
        val detail = json.decodeFromString<ItemDetail>(
            """
            { "content_id": "m2", "type": "movie", "title": "Unrated Movie" }
            """.trimIndent(),
        )
        assertNull(detail.userRating)
    }
}
