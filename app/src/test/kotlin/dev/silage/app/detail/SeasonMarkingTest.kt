package dev.silage.app.detail

import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.LeafItemUserData
import org.siloserver.silo.model.catalog.Season
import kotlin.test.Test
import kotlin.test.assertEquals

class SeasonMarkingTest {
    private val seasons = listOf(Season("s0", 0, isSpecials = true), Season("s2", 2), Season("s1", 1), Season("s3", 3))
    private fun ep(n: Int, watched: Boolean = false) = EpisodeListItem("e$n", 3, n, userData = LeafItemUserData(played = watched))
    private val episodes = listOf(ep(1, watched = true), ep(2), ep(3), ep(4))

    @Test fun upToHereMarksEarlierSeasonsWholeThenThisSeasonsUnwatchedEpisodes() {
        assertEquals(listOf("s1", "s2", "e2", "e3"), upToHereTargets(seasons, 3, episodes, "e3"))
        // The first season has nothing earlier; specials are never included.
        assertEquals(listOf("e2"), upToHereTargets(seasons, 1, episodes, "e2"))
        assertEquals(emptyList(), upToHereTargets(seasons, 3, episodes, "missing"))
    }

    @Test fun aSeasonIsAllWatchedOnlyWhenEveryEpisodeIs() {
        assertEquals(false, seasonAllWatched(episodes))
        assertEquals(true, seasonAllWatched(listOf(ep(1, true), ep(2, true))))
        assertEquals(false, seasonAllWatched(emptyList()))
    }
}

class MainTrailerTest {
    private fun remote(kind: String, key: String) = org.siloserver.silo.model.catalog.TrailerRailEntry.Remote(
        org.siloserver.silo.model.catalog.ItemVideo(kind = kind, site = "YouTube", siteKey = key))
    private fun local(kind: String) = org.siloserver.silo.model.catalog.TrailerRailEntry.Local(
        org.siloserver.silo.model.catalog.ItemExtra(contentId = "x", kind = kind))

    @Test fun theTrailerButtonPrefersATrailerThenAnyYouTubeVideo() {
        assertEquals("t", (mainTrailer(listOf(remote("featurette", "f"), remote("trailer", "t"))) as org.siloserver.silo.model.catalog.TrailerRailEntry.Remote).video.siteKey)
        assertEquals(local("trailer"), mainTrailer(listOf(local("featurette"), local("trailer"))))
        assertEquals("f", (mainTrailer(listOf(local("featurette"), remote("featurette", "f"))) as org.siloserver.silo.model.catalog.TrailerRailEntry.Remote).video.siteKey)
        assertEquals(null, mainTrailer(listOf(local("featurette"))))
    }
}

class RecentSearchesTest {
    @Test fun recentSearchesAreNewestFirstOnceEachAndCapped() {
        val list = dev.silage.app.browse.RecentSearches.add(listOf("Dune", "Alien"), " alien ")
        assertEquals(listOf("alien", "Dune"), list)
        assertEquals(listOf("Dune"), dev.silage.app.browse.RecentSearches.add(listOf("Dune"), "a"))
        val full = (1..20).fold(emptyList<String>()) { acc, n -> dev.silage.app.browse.RecentSearches.add(acc, "q$n") }
        assertEquals(dev.silage.app.browse.RecentSearches.Max, full.size)
        assertEquals("q20", full.first())
    }
}
