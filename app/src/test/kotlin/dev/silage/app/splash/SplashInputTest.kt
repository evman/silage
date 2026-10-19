package dev.silage.app.splash

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SplashInputTest {
    @Test fun introConsumesPointerGesturesInsteadOfClickingThrough() = runComposeUiTest {
        mainClock.autoAdvance = false
        var clicks = 0
        setContent {
            Box {
                Box(Modifier.fillMaxSize().clickable { clicks++ })
                SplashScreen(ready = false, onFinished = {})
            }
        }
        onRoot().performMouseInput { click() }
        mainClock.advanceTimeBy(2_000)
        onRoot().performMouseInput { click() }
        runOnIdle { assertEquals(0, clicks) }
    }
}
