package dev.silage.app.playback.hud

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import dev.silage.app.playback.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class VideoHudTest {
    @Test fun labelledControlsAdjustLiveStateAndReset() = runComposeUiTest {
        var ui by mutableStateOf(HudUi())
        val actions = object : HudActions {
            override fun togglePause() {}
            override fun seekBy(seconds: Double) {}
            override fun seekTo(seconds: Double) {}
            override fun openMenu(menu: HudMenu) { ui = ui.copy(menu = menu) }
            override fun audio(id: Int) {}
            override fun subtitle(id: Int?) {}
            override fun speed(value: Double) {}
            override fun exit() {}
            override fun fullscreen() { ui = ui.copy(fullscreen = !ui.fullscreen) }
            override fun video(index: Int, step: Int) { ui = ui.copy(video = VideoControls.adjust(ui.video, index, step)) }
        }
        setContent { HudContent(ui, actions) }
        onNodeWithContentDescription("Enter fullscreen (F)").performClick()
        onNodeWithContentDescription("Exit fullscreen (F / Escape)").assertExists()
        onNodeWithContentDescription("Video settings").performClick()
        onNodeWithContentDescription("Increase Zoom").performClick()
        runOnIdle { assertEquals(0.1, ui.video.zoom) }
        // Picture adjustments (brightness etc.) were removed: only fit, aspect and zoom remain.
        onNodeWithContentDescription("Increase Brightness").assertDoesNotExist()
        onNodeWithText("Restore video defaults").performClick()
        runOnIdle { assertEquals(VideoSettings(), ui.video) }
    }
    @Test fun videoKeyboardStaysOpenAndUsesDirectionalAdjustment() {
        val nav = HudNav(menu = HudMenu.Video, menuIndex = 2, menuSize = VideoControls.RowCount)
        assertEquals(HudEffect.AdjustVideo(2, -1), nav.on(HudKey.Left).second)
        assertEquals(HudMenu.Video, nav.on(HudKey.Enter).first.menu)
        assertEquals(HudEffect.AdjustVideo(2, 1), nav.on(HudKey.Enter).second)
    }
}
