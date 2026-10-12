package org.siloserver.silo.model.calendar

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarModelsSerializationTest {

    // Mirrors SiloJson (network/SiloHttpClientImpl.kt) so decode
    // behavior in tests matches production wire handling exactly.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun `decodes calendar response with episode and movie items`() {
        val payload = """
            {
              "events": [
                {
                  "date": "2026-06-08",
                  "items": [
                    {
                      "content_id": "ep-101",
                      "type": "episode",
                      "title": "Severance",
                      "episode_title": "Cold Harbor",
                      "series_id": "series-7",
                      "season_number": 2,
                      "episode_number": 10,
                      "air_date": "2026-06-08",
                      "air_time": "21:00",
                      "air_at": "2026-06-09T01:00:00Z",
                      "air_timezone": "America/New_York",
                      "local_air_date": "2026-06-08",
                      "poster_url": "/posters/severance.jpg",
                      "poster_thumbhash": "1QcSHQRnh493V4dIh4eXh1h4kJUI",
                      "watched": false,
                      "badges": ["finale"]
                    }
                  ]
                },
                {
                  "date": "2026-06-10",
                  "items": [
                    {
                      "content_id": "movie-9",
                      "type": "movie",
                      "title": "Dune Part Three",
                      "air_date": "2026-06-10",
                      "local_air_date": "2026-06-10",
                      "watched": true
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString(CalendarResponse.serializer(), payload)

        assertEquals(2, response.events.size)

        val episode = response.events[0].items.single()
        assertEquals("2026-06-08", response.events[0].date)
        assertEquals("ep-101", episode.contentId)
        assertEquals(CalendarItemType.Episode, episode.type)
        assertTrue(episode.isEpisode)
        assertEquals("Cold Harbor", episode.episodeTitle)
        assertEquals(2, episode.seasonNumber)
        assertEquals(10, episode.episodeNumber)
        assertEquals("2026-06-08", episode.airDate)
        assertEquals("2026-06-08", episode.localAirDate)
        assertEquals("21:00", episode.airTime)
        assertEquals("America/New_York", episode.airTimezone)
        assertEquals("1QcSHQRnh493V4dIh4eXh1h4kJUI", episode.posterThumbhash)
        assertFalse(episode.watched)
        assertEquals(listOf(CalendarBadge.Finale), episode.badges)
        // Episodes route to the series detail page.
        assertEquals("series-7", episode.detailContentId)

        val movie = response.events[1].items.single()
        assertFalse(movie.isEpisode)
        assertTrue(movie.watched)
        assertTrue(movie.badges.isEmpty())
        assertNull(movie.posterUrl)
        assertEquals("2026-06-10", movie.airDate)
        assertEquals("2026-06-10", movie.localAirDate)
        // Movies route to their own content id.
        assertEquals("movie-9", movie.detailContentId)
    }

    @Test
    fun `capitalized Episode type still routes detailContentId to seriesId`() {
        val payload = """
            {
              "content_id": "ep-200",
              "type": "Episode",
              "title": "Capitalized Type",
              "series_id": "series-99",
              "air_date": "2026-06-12",
              "local_air_date": "2026-06-12"
            }
        """.trimIndent()

        val item = json.decodeFromString(CalendarItem.serializer(), payload)

        assertTrue(item.isEpisode)
        assertEquals("series-99", item.detailContentId)
    }

    @Test
    fun `episode without series id falls back to its own content id for routing`() {
        val payload = """
            {
              "content_id": "ep-55",
              "type": "episode",
              "title": "Orphan Episode",
              "air_date": "2026-06-11",
              "local_air_date": "2026-06-11"
            }
        """.trimIndent()

        val item = json.decodeFromString(CalendarItem.serializer(), payload)

        assertEquals("ep-55", item.detailContentId)
        assertFalse(item.watched)
    }
}
