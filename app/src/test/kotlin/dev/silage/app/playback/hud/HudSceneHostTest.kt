package dev.silage.app.playback.hud

import androidx.compose.material3.Text
import androidx.compose.ui.unit.Density
import kotlin.test.Test

class HudSceneHostTest {
    /**
     * "ComposeScene is already closed" on leaving the player: the player is
     * closed twice (once when the server session stops, again in cleanup), and
     * a second close of the HUD's scene threw into the UI.
     */
    @Test fun closeIsSafeToCallTwice() {
        val host = HudSceneHost(64, 64, Density(1f)) { Text("x") }
        host.render(0)
        host.close()
        host.close()
    }
}
