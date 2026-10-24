package dev.silage.app.settings

import org.siloserver.silo.network.apiv2.LoginSessionV2
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class DevicesTest {
    private val now = Instant.parse("2026-10-24T18:00:00Z")

    @Test fun thisDeviceComesFirstThenTheMostRecentlyUsed() {
        val rows = deviceRows(listOf(
            LoginSessionV2("old", "Bedroom TV", "10.0.0.5", "2026-01-02T03:04:05Z", lastSeenAt = "2026-10-20T18:00:00Z", devicePlatform = "tvOS"),
            LoginSessionV2("me", "Silage on DESKTOP-1", "10.0.0.2", "2026-10-01T10:00:00Z", current = true, devicePlatform = "Windows"),
            LoginSessionV2("new", "", "", "2026-10-24T17:00:00Z", lastSeenAt = "2026-10-24T17:55:00Z"),
        ), now, ZoneOffset.UTC)
        assertEquals(listOf("me", "new", "old"), rows.map { it.id })
        assertEquals("Windows · 10.0.0.2 · Active now · Signed in 1 Oct 2026", rows[0].detail)
        assertEquals("Unknown device", rows[1].name)
        assertEquals("Last active 5 minutes ago · Signed in 24 Oct 2026", rows[1].detail)
        assertEquals("tvOS · 10.0.0.5 · Last active 4 days ago · Signed in 2 Jan 2026", rows[2].detail)
    }

    @Test fun agoReadsNaturally() {
        assertEquals("just now", ago(now.minusSeconds(20), now))
        assertEquals("1 minute ago", ago(now.minusSeconds(60), now))
        assertEquals("3 hours ago", ago(now.minusSeconds(3 * 3600 + 5), now))
        assertEquals("2 months ago", ago(now.minusSeconds(65L * 86400), now))
    }
}
