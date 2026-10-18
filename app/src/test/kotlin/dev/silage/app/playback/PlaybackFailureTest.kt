package dev.silage.app.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PlaybackFailureTest {
    @Test fun failureTextIsVisibleOnTheAppsBlackBackgroundAndBackWorks() = runComposeUiTest {
        var wentBack = false
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, surface = Color.Black,
                primary = Color.White, onPrimary = Color.Black)) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    PlaybackFailure("Playback failed: loading failed") { wentBack = true }
                }
            }
        }
        val pixels = onNodeWithText("Playback failed: loading failed").captureToImage().toPixelMap()
        var brightPixels = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val c = pixels[x, y]
            if (c.red > 0.5f && c.green > 0.5f && c.blue > 0.5f) brightPixels++
        }
        assertTrue(brightPixels > 20, "The error message must render visibly, not black text on black")
        onNodeWithText("Back").performClick()
        assertTrue(wentBack)
    }
}
