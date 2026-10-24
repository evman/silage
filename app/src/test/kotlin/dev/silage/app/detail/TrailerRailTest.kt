package dev.silage.app.detail

import org.siloserver.silo.model.catalog.ItemExtra
import org.siloserver.silo.model.catalog.ItemVideo
import org.siloserver.silo.model.catalog.TrailerRailEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class TrailerRailTest {
    @Test fun youtubeTrailersOpenTheWatchPage() {
        assertEquals("https://www.youtube.com/watch?v=abc_DEF-123", youtubeWatchUrl("abc_DEF-123"))
        // A key can never add its own parameters to the address.
        assertEquals("https://www.youtube.com/watch?v=a%26b%3Dc", youtubeWatchUrl("a&b=c"))
    }

    @Test fun captionsGiveTheKindAndTheRuntimeOfLocalFiles() {
        val remote = TrailerRailEntry.Remote(ItemVideo(kind = "trailer", site = "YouTube", siteKey = "k", name = "Official Trailer"))
        val local = TrailerRailEntry.Local(ItemExtra(contentId = "x1", kind = "behind_the_scenes", title = "Making of", durationSeconds = 754))
        assertEquals("Trailer", trailerCaption(remote))
        assertEquals("Behind the Scenes · 12:34", trailerCaption(local))
        assertEquals("Official Trailer", remote.title)
        assertEquals("Making of", local.title)
    }
}
