package org.siloserver.silo.network

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeRealtimeDecodeTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun subscribedFrameSignalsConnectedForRestReconcile() {
        // Snapshots are null for user_state/catalog — the `subscribed` frame is
        // the catch-up point, triggering one REST reconcile.
        val event = decodeHomeRealtimeFrame(
            json,
            """{"type":"subscribed","request_id":"1","channels":["user_state","catalog"]}""",
        )
        assertIs<HomeRealtimeEvent.Connected>(event)
    }

    @Test
    fun userStateChangedDecodesPayload() {
        val event = decodeHomeRealtimeFrame(
            json,
            """{"type":"event","channel":"user_state","event":"user_state.changed",""" +
                """"event_id":"e1","timestamp":"2026-07-07T10:00:00Z",""" +
                """"data":{"profile_id":"p1","content_id":"movie-9","change":"progress"}}""",
        )
        val change = assertIs<HomeRealtimeEvent.UserState>(event).change
        assertEquals("p1", change.profileId)
        assertEquals("movie-9", change.contentId)
        assertEquals("progress", change.change)
    }

    @Test
    fun anyCatalogEventSignalsCatalogChanged() {
        listOf("catalog.item.changed", "library.item_added", "metadata.updated").forEach { name ->
            val event = decodeHomeRealtimeFrame(
                json,
                """{"type":"event","channel":"catalog","event":"$name","data":null}""",
            )
            assertIs<HomeRealtimeEvent.CatalogChanged>(event, "event $name")
        }
    }

    @Test
    fun unrelatedFramesDecodeToNull() {
        assertNull(decodeHomeRealtimeFrame(json, """{"type":"hello","schema_version":1}"""))
        assertNull(
            decodeHomeRealtimeFrame(
                json,
                """{"type":"event","channel":"notifications","event":"notification.created","data":{}}""",
            ),
        )
        assertNull(decodeHomeRealtimeFrame(json, """{"type":"snapshot","channel":"user_state","data":null}"""))
        assertNull(decodeHomeRealtimeFrame(json, "not json"))
    }

    @Test
    fun refreshTriggerFiltersForeignProfiles() {
        val mine = HomeRealtimeEvent.UserState(
            UserStateChange(profileId = "p1", contentId = "c1", change = "progress"),
        )
        val other = HomeRealtimeEvent.UserState(
            UserStateChange(profileId = "p2", contentId = "c1", change = "progress"),
        )
        assertTrue(homeRefreshTrigger(mine, activeProfileId = "p1"))
        assertFalse(homeRefreshTrigger(other, activeProfileId = "p1"))
        // user_state events are account-scoped; without a known active profile
        // we refresh rather than risk staleness.
        assertTrue(homeRefreshTrigger(mine, activeProfileId = null))
        assertTrue(homeRefreshTrigger(HomeRealtimeEvent.CatalogChanged, activeProfileId = "p1"))
        assertTrue(homeRefreshTrigger(HomeRealtimeEvent.Connected, activeProfileId = "p1"))
        assertFalse(homeRefreshTrigger(HomeRealtimeEvent.Closed(null), activeProfileId = "p1"))
    }
}
