package dev.silage.app.playback

import org.siloserver.silo.model.catalog.LeafItemUserData
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayStartTest {
    private val inProgress = LeafItemUserData(played = false, isInProgress = true, positionSeconds = 754.0, durationSeconds = 6000.0)

    @Test fun resumesAnInProgressItem() = assertEquals(754.0, playStart(inProgress, fromStart = false))
    @Test fun fromStartWins() = assertEquals(null, playStart(inProgress, fromStart = true))
    @Test fun watchedItemsStartOver() = assertEquals(null, playStart(inProgress.copy(played = true), false))
    @Test fun noUserDataStartsAtZero() = assertEquals(null, playStart(null, false))
    @Test fun zeroPositionStartsAtZero() = assertEquals(null, playStart(inProgress.copy(positionSeconds = 0.0), false))
}
