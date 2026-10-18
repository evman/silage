package dev.silage.app.playback

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import org.siloserver.silo.playback.ShuffleNextPick
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ShuffleUpNextTest {
    private fun pick(unwatched: Boolean) = ShuffleNextPick(
        contentId = "e7", isEpisode = true, title = "The Fly", seriesTitle = "Breaking Bad", seasonNumber = 3, episodeNumber = 10,
        stillUrl = null, stillThumbhash = null, overview = "Walt hunts a fly.", runtimeMinutes = 47, isUnwatched = unwatched,
    )

    @Test fun offersPlayPickAnotherAndStopAndHidesAnUnwatchedEpisodesOverview() = runComposeUiTest {
        val calls = mutableListOf<String>()
        setContent {
            ShuffleUpNextScreen(pick(unwatched = true), "Breaking Bad", autoplay = false,
                onPlay = { calls += "play" }, onPickAnother = { calls += "another" }, onStop = { calls += "stop" })
        }
        onNodeWithText("S3 · E10  The Fly").assertExists()
        onNodeWithText("Walt hunts a fly.").assertDoesNotExist()
        onNodeWithText("Play now").performClick()
        onNodeWithText("Pick another").performClick()
        onNodeWithText("Stop shuffling").performClick()
        assertEquals(listOf("play", "another", "stop"), calls)
    }

    @Test fun aWatchedPicksOverviewIsShown() = runComposeUiTest {
        setContent { ShuffleUpNextScreen(pick(unwatched = false), null, autoplay = false, onPlay = {}, onPickAnother = {}, onStop = {}) }
        onNodeWithText("Walt hunts a fly.").assertExists()
    }
}
