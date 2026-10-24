package dev.silage.app.playback.hud

import dev.silage.app.playback.hud.HudFocus.*
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class HudKeysTest {
    @Test fun awtAndMpvKeyNamesMapToTheSameKeys() {
        assertEquals(HudKey.Escape, HudKey.ofAwt(KeyEvent.VK_ESCAPE))
        assertEquals(HudKey.Back, HudKey.ofAwt(KeyEvent.VK_BACK_SPACE))
        assertEquals(HudKey.Escape, HudKey.ofMpv("ESC"))
        assertEquals(HudKey.PlayPause, HudKey.ofAwt(KeyEvent.VK_SPACE))
        assertEquals(HudKey.Left, HudKey.ofMpv("LEFT"))
        assertEquals(HudKey.Enter, HudKey.ofAwt(KeyEvent.VK_ENTER))
        assertEquals(HudKey.Info, HudKey.ofMpv("i"))
        assertEquals(null, HudKey.ofAwt(KeyEvent.VK_B))
    }

    @Test fun shortcutKeysActWhereverTheFocusIsWithoutRevealingTheHud() {
        assertEquals(HudKey.CycleAudio, HudKey.ofAwt(KeyEvent.VK_A))
        assertEquals(HudKey.CycleSubtitles, HudKey.ofMpv("s"))
        assertEquals(HudKey.Mute, HudKey.ofAwt(KeyEvent.VK_M))
        assertEquals(HudKey.VolumeUp, HudKey.ofMpv("+"))
        assertEquals(HudKey.VolumeDown, HudKey.ofAwt(KeyEvent.VK_MINUS))
        assertEquals(HudKey.FasterSpeed, HudKey.ofMpv("]"))
        assertEquals(HudKey.NextEpisode, HudKey.ofAwt(KeyEvent.VK_N))
        assertEquals(hidden to HudEffect.Volume(1), hidden.on(HudKey.VolumeUp))
        assertEquals(HudEffect.Speed(-1), shown.on(HudKey.SlowerSpeed).second)
        assertEquals(HudEffect.CycleSubtitles, shown.copy(menu = HudMenu.Audio).on(HudKey.CycleSubtitles).second)
        // Every mpv name the HUD binds maps to a key.
        HudKey.MPV_KEYS.forEach { assertEquals(true, HudKey.ofMpv(it) != null, it) }
    }

    @Test fun theSyncPanelAdjustsWithLeftAndRight() {
        val nav = HudNav(menu = HudMenu.Sync, menuIndex = SyncControls.SubtitleIndex, menuSize = SyncControls.RowCount)
        assertEquals(HudEffect.AdjustSync(SyncControls.SubtitleIndex, -1), nav.on(HudKey.Left).second)
        assertEquals(HudEffect.AdjustSync(SyncControls.SubtitleIndex, 1), nav.on(HudKey.Right).second)
        assertEquals(Sleep, shown.copy(focus = Sync).on(HudKey.Right).first.focus)
        assertEquals(Fullscreen, shown.copy(focus = Sleep).on(HudKey.Right).first.focus)
    }

    @Test fun syncSpeedsAndCyclesStepAsExpected() {
        assertEquals(0.3, SyncControls.adjust(0.2, 1)); assertEquals(-0.1, SyncControls.adjust(0.0, -1)); assertEquals(10.0, SyncControls.adjust(10.0, 1))
        assertEquals("In sync", SyncControls.label(0.0)); assertEquals("+0.3 s later", SyncControls.label(0.3)); assertEquals("1.2 s earlier", SyncControls.label(-1.2))
        assertEquals(1.25, nextSpeed(SPEEDS, 1.0, 1)); assertEquals(0.5, nextSpeed(SPEEDS, 0.5, -1)); assertEquals(2.0, nextSpeed(SPEEDS, 2.0, 1))
        assertEquals(5, nextInCycle(listOf<Int?>(null, 4, 5), 4)); assertEquals(null, nextInCycle(listOf<Int?>(null, 4, 5), 5)); assertEquals(4, nextInCycle(listOf<Int?>(null, 4, 5), null))
        assertEquals(1200.0, sleepEndPoint(1300.0, 1200.0)); assertEquals(1297.0, sleepEndPoint(1300.0, null)); assertEquals(null, sleepEndPoint(0.0, null))
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
        assertEquals(Video, shown.copy(focus = Info).on(HudKey.Right).first.focus)
        assertEquals(Fullscreen, shown.copy(focus = Fullscreen).on(HudKey.Right).first.focus)
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
