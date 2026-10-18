package dev.silage.app.notifications

import org.siloserver.silo.model.notifications.NotificationRow
import org.siloserver.silo.model.notifications.NotificationType
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationTextTest {
    private fun row(id: String = "n1", type: String = NotificationType.EpisodeAvailableWire, read: Boolean = false,
        series: String? = "s1", episode: String? = "e1") = NotificationRow(
        id = id, rawType = type, profileId = "p", seriesId = series, episodeId = episode, seriesTitle = "Severance",
        episodeTitle = "Hello, Ms. Cobel", seasonNumber = 2, episodeNumber = 1, createdAt = "2026-10-18T10:00:00Z",
        readAt = if (read) "2026-10-18T11:00:00Z" else null,
    )

    @Test fun newEpisode() {
        assertEquals(NotificationText("New episode of Severance", "S2 E1 · Hello, Ms. Cobel"), row().text())
    }

    @Test fun fulfilledRequest() {
        val movie = row(type = NotificationType.RequestFulfilledWire).copy(seasonNumber = null, episodeNumber = null, episodeTitle = "")
        assertEquals(NotificationText("Severance is ready to watch", "Your request was added to the library"), movie.text())
    }

    @Test fun opensTheSeriesElseTheEpisode() {
        assertEquals("s1", row().openTarget())
        assertEquals("e1", row(series = "").openTarget())
        assertNull(row(series = null, episode = null).openTarget())
    }

    @Test fun relativeTimes() {
        val now = Instant.parse("2026-10-18T12:00:00Z")
        assertEquals("Just now", relativeTime("2026-10-18T11:59:30Z", now))
        assertEquals("5 min ago", relativeTime("2026-10-18T11:55:00Z", now))
        assertEquals("2 h ago", relativeTime("2026-10-18T10:00:00+00:00", now))
        assertEquals("Yesterday", relativeTime("2026-10-17T09:00:00Z", now))
        assertEquals("3 days ago", relativeTime("2026-10-15T12:00:00Z", now))
        assertEquals("1 Oct 2026", relativeTime("2026-10-01T12:00:00Z", now, ZoneOffset.UTC))
        assertEquals("", relativeTime("not a date", now))
    }

    @Test fun onlyNewUnreadRowsAtTheTopAreFresh() {
        val old = listOf(row("a"), row("b"))
        assertEquals(emptyList(), freshArrivals(null, listOf(row("x")) + old))
        assertEquals(listOf("x"), freshArrivals(old, listOf(row("x"), row("y", read = true)) + old).map { it.id })
        // An older page appended at the end is not new.
        assertEquals(emptyList(), freshArrivals(old, old + row("z")))
        assertEquals(listOf("x"), freshArrivals(emptyList(), listOf(row("x"))).map { it.id })
    }
}
