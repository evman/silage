package org.siloserver.silo.model.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpisodeTranslationMergeTest {
    @Test fun translationResponsePreservesWatchedProgressAndFiles() {
        val current = EpisodeListItem("episode", 1, 1, overview = "Original",
            userData = LeafItemUserData(played = true, positionSeconds = 42.0),
            files = listOf(EpisodeFile(17)))
        val stale = current.copy(overview = "Localized", userData = LeafItemUserData(played = false),
            files = emptyList(), machineTranslatedFields = listOf("overview"))
        val merged = current.withTranslatedDescription(stale)
        assertEquals(current.userData, merged.userData)
        assertEquals(current.files, merged.files)
        assertEquals("Localized", merged.overview)
        assertEquals(listOf("overview"), merged.machineTranslatedFields)
    }

    @Test fun sameTextStillUpdatesProvenanceAndClearsPendingLanguage() {
        val detail = ItemDetail("episode", "episode", title = "Episode", overview = "Same",
            pendingTranslationLanguage = "de", userData = LeafItemUserData(played = true))
        val row = EpisodeListItem("episode", 1, 1, overview = "Same",
            machineTranslatedFields = listOf("overview"))
        val merged = detail.withEpisodeDescription(row)
        assertEquals(listOf("overview"), merged.machineTranslatedFields)
        assertNull(merged.pendingTranslationLanguage)
        assertEquals(detail.userData, merged.userData)
    }

    @Test fun lateDetailUsesCurrentTranslationWithoutReplacingPlaybackMetadata() {
        val late = ItemDetail("episode", "episode", title = "Episode", overview = "Original",
            pendingTranslationLanguage = "de", userData = LeafItemUserData(positionSeconds = 23.0))
        val row = EpisodeListItem("episode", 1, 1, overview = "Localized",
            machineTranslatedFields = listOf("overview"))
        val merged = late.withEpisodeDescription(row)
        assertEquals("Localized", merged.overview)
        assertEquals(late.userData, merged.userData)
        assertNull(merged.pendingTranslationLanguage)
        assertEquals(late, late.withEpisodeDescription(row.copy(contentId = "other")))
    }
    @Test fun pendingRowCannotReplaceCompletedDetail() {
        val detail = ItemDetail("episode", "episode", title = "Episode", overview = "Localized",
            machineTranslatedFields = listOf("overview"))
        val stale = EpisodeListItem("episode", 1, 1, overview = "Original", pendingTranslationLanguage = "de")
        assertEquals(detail, detail.withEpisodeDescription(stale))
    }

}
