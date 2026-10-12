package dev.silage.app.playback.hud

import dev.silage.app.playback.hud.HudFocus.*
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class HudKeysTest {
    @Test fun awtAndMpvKeyNamesMapToTheSameKeys() {
        assertEquals(HudKey.Back, HudKey.ofAwt(KeyEvent.VK_ESCAPE))
        assertEquals(HudKey.Back, HudKey.ofAwt(KeyEvent.VK_BACK_SPACE))
        assertEquals(HudKey.Back, HudKey.ofMpv("ESC"))
        assertEquals(HudKey.PlayPause, HudKey.ofAwt(KeyEvent.VK_SPACE))
        assertEquals(HudKey.Left, HudKey.ofMpv("LEFT"))
        assertEquals(HudKey.Enter, HudKey.ofAwt(KeyEvent.VK_ENTER))
        assertEquals(HudKey.Info, HudKey.ofMpv("i"))
        assertEquals(null, HudKey.ofAwt(KeyEvent.VK_A))
    }

    private val hidden = HudNav(visible = false)
    private val shown = HudNav(visible = true, focus = PlayPause)

    @Test fun hiddenLeftRightSkipAndShowTheSeekBar() {
        assertEquals(HudNav(visible = true, focus = Seek) to HudEffect.SeekBy(-10.0), hidden.on(HudKey.Left))
        assertEquals(HudNav(visible = true, focus = Seek) to HudEffect.SeekBy(10.0), hidden.on(HudKey.Right))
    }

    @Test fun hiddenOtherArrowsAndEnterRevealTheHudOnPlayPause() {
        assertEquals(shown to HudEffect.None, hidden.on(HudKey.Up))
        assertEquals(shown to HudEffect.None, hidden.on(HudKey.Enter))
    }

    @Test fun hiddenRevealFocusesSkipWhenOffered() {
        assertEquals(HudNav(visible = true, focus = Skip, hasSkip = true) to HudEffect.None, hidden.copy(hasSkip = true).on(HudKey.Down))
    }

    @Test fun spaceAlwaysTogglesPause() {
        assertEquals(HudEffect.TogglePause, hidden.on(HudKey.PlayPause).second)
        assertEquals(HudEffect.TogglePause, shown.on(HudKey.PlayPause).second)
    }

    @Test fun leftRightWalkTheButtonRowAndStopAtTheEnds() {
        assertEquals(Fwd10, shown.on(HudKey.Right).first.focus)
        assertEquals(Back10, shown.on(HudKey.Left).first.focus)
        assertEquals(Back10, shown.copy(focus = Back10).on(HudKey.Left).first.focus)
        assertEquals(Info, shown.copy(focus = Info).on(HudKey.Right).first.focus)
        assertEquals(Audio, shown.copy(focus = Fwd10).on(HudKey.Right).first.focus)
    }

    @Test fun upDownMoveBetweenRows() {
        assertEquals(Seek, shown.on(HudKey.Up).first.focus)
        assertEquals(PlayPause, shown.copy(focus = Seek).on(HudKey.Down).first.focus)
        assertEquals(Seek, shown.copy(focus = Seek).on(HudKey.Up).first.focus)
        assertEquals(Skip, shown.copy(focus = Seek, hasSkip = true).on(HudKey.Up).first.focus)
        assertEquals(Seek, shown.copy(focus = Skip, hasSkip = true).on(HudKey.Down).first.focus)
    }

    @Test fun onTheSeekBarLeftRightScrub() {
        assertEquals(HudEffect.SeekBy(10.0), shown.copy(focus = Seek).on(HudKey.Right).second)
    }

    @Test fun enterActivatesTheFocusedControl() {
        assertEquals(HudEffect.Activate(Audio), shown.copy(focus = Audio).on(HudKey.Enter).second)
        assertEquals(HudEffect.TogglePause, shown.copy(focus = Seek).on(HudKey.Enter).second)
    }

    @Test fun menusNavigateWithUpDownAndEnterSelects() {
        val menu = shown.copy(focus = Audio, menu = HudMenu.Audio, menuIndex = 0, menuSize = 3)
        assertEquals(1, menu.on(HudKey.Down).first.menuIndex)
        assertEquals(0, menu.on(HudKey.Up).first.menuIndex)
        assertEquals(2, menu.copy(menuIndex = 2).on(HudKey.Down).first.menuIndex)
        val (after, effect) = menu.copy(menuIndex = 1).on(HudKey.Enter)
        assertEquals(HudEffect.SelectRow(HudMenu.Audio, 1), effect)
        assertEquals(HudMenu.None, after.menu)
        assertEquals(Audio, after.focus)
    }

    @Test fun backPeelsMenuThenHudThenLeaves() {
        val menu = shown.copy(focus = Audio, menu = HudMenu.Audio, menuSize = 2)
        val (closed, effect) = menu.on(HudKey.Back)
        assertEquals(HudMenu.None, closed.menu)
        assertEquals(Audio, closed.focus)
        assertEquals(true, closed.visible)
        assertEquals(HudEffect.None, effect)
        assertEquals(hidden.copy(focus = PlayPause) to HudEffect.None, shown.on(HudKey.Back))
        assertEquals(HudEffect.Exit, hidden.on(HudKey.Back).second)
    }

    @Test fun infoKeyTogglesTheInfoPanel() {
        assertEquals(HudMenu.Info, shown.on(HudKey.Info).first.menu)
        assertEquals(HudMenu.None, shown.copy(menu = HudMenu.Info).on(HudKey.Info).first.menu)
    }
}
