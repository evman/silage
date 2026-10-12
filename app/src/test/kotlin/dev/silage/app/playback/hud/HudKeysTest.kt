package dev.silage.app.playback.hud

import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class HudKeysTest {
    @Test fun awtAndMpvKeyNamesMapToTheSameKeys() {
        assertEquals(HudKey.Back, HudKey.ofAwt(KeyEvent.VK_ESCAPE))
        assertEquals(HudKey.Back, HudKey.ofAwt(KeyEvent.VK_BACK_SPACE))
        assertEquals(HudKey.Back, HudKey.ofMpv("ESC"))
        assertEquals(HudKey.Back, HudKey.ofMpv("BS"))
        assertEquals(HudKey.PlayPause, HudKey.ofAwt(KeyEvent.VK_SPACE))
        assertEquals(HudKey.PlayPause, HudKey.ofMpv("SPACE"))
        assertEquals(HudKey.Left, HudKey.ofMpv("LEFT"))
        assertEquals(HudKey.Right, HudKey.ofAwt(KeyEvent.VK_RIGHT))
        assertEquals(HudKey.Info, HudKey.ofMpv("i"))
        assertEquals(null, HudKey.ofAwt(KeyEvent.VK_A))
    }

    @Test fun backClosesAnOpenMenuBeforeLeavingThePlayer() {
        assertEquals(HudAction.CloseMenu, HudKeys.action(HudKey.Back, menuOpen = true))
        assertEquals(HudAction.Exit, HudKeys.action(HudKey.Back, menuOpen = false))
    }

    @Test fun transportKeys() {
        assertEquals(HudAction.TogglePause, HudKeys.action(HudKey.PlayPause, false))
        assertEquals(HudAction.SeekBy(-10.0), HudKeys.action(HudKey.Left, false))
        assertEquals(HudAction.SeekBy(10.0), HudKeys.action(HudKey.Right, false))
        assertEquals(HudAction.ToggleInfo, HudKeys.action(HudKey.Info, false))
        assertEquals(HudAction.Show, HudKeys.action(HudKey.Up, false))
    }
}
