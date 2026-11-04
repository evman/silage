package dev.silage.app.input

import dev.silage.app.nav.BackInput
import dev.silage.app.playback.hud.HudEffect
import dev.silage.app.playback.hud.HudKey
import dev.silage.app.playback.hud.HudNav
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GamepadTest {
    private fun held(vararg b: GamepadButton) = GamepadState(buttons = b.toSet())

    @Test fun aButtonPressesOnceWhileHeld() {
        val presses = GamepadPresses()
        assertEquals(listOf(GamepadButton.A), presses.update(held(GamepadButton.A), 0))
        assertEquals(emptyList(), presses.update(held(GamepadButton.A), 5_000))
        assertEquals(emptyList(), presses.update(GamepadState(), 5_016))
        assertEquals(listOf(GamepadButton.A), presses.update(held(GamepadButton.A), 5_032))
    }

    @Test fun theDpadRepeatsAfterADelay() {
        val presses = GamepadPresses(repeatDelayMs = 400, repeatEveryMs = 100)
        assertEquals(listOf(GamepadButton.Down), presses.update(held(GamepadButton.Down), 0))
        assertEquals(emptyList(), presses.update(held(GamepadButton.Down), 399))
        assertEquals(listOf(GamepadButton.Down), presses.update(held(GamepadButton.Down), 400))
        assertEquals(emptyList(), presses.update(held(GamepadButton.Down), 450))
        assertEquals(listOf(GamepadButton.Down), presses.update(held(GamepadButton.Down), 500))
    }

    @Test fun theStickActsAsTheDpadAlongItsStrongerAxis() {
        val presses = GamepadPresses()
        assertEquals(emptyList(), presses.update(GamepadState(stickX = 0.3f, stickY = 0.2f), 0))
        assertEquals(listOf(GamepadButton.Right), presses.update(GamepadState(stickX = 0.9f, stickY = 0.2f), 16))
        assertEquals(emptyList(), presses.update(GamepadState(), 32))
        assertEquals(listOf(GamepadButton.Up), presses.update(GamepadState(stickX = 0.2f, stickY = -0.9f), 48))
    }

    @Test fun hatAnglesIncludeDiagonalsAndCentre() {
        assertEquals(setOf(GamepadButton.Up), hatButtons(0))
        assertEquals(setOf(GamepadButton.Up, GamepadButton.Right), hatButtons(4500))
        assertEquals(setOf(GamepadButton.Right), hatButtons(9000))
        assertEquals(setOf(GamepadButton.Down), hatButtons(18000))
        assertEquals(setOf(GamepadButton.Left), hatButtons(27000))
        assertEquals(setOf(GamepadButton.Up, GamepadButton.Left), hatButtons(31500))
        assertEquals(emptySet(), hatButtons(65535))
    }

    @Test fun xinputBitsAreNamed() {
        assertEquals(setOf(GamepadButton.A, GamepadButton.Up), xinputButtons(0x1001))
        assertEquals(setOf(GamepadButton.Menu, GamepadButton.RightBumper), xinputButtons(0x0210))
    }

    @Test fun playstationPadsPutCrossSecondOnWindows() {
        assertEquals(setOf(GamepadButton.A), joystickButtons(0b10, SonyVendorId))
        assertEquals(setOf(GamepadButton.X), joystickButtons(0b1, SonyVendorId))
        assertEquals(setOf(GamepadButton.Menu), joystickButtons(1 shl 9, SonyVendorId))
        assertEquals(setOf(GamepadButton.A), joystickButtons(0b1, vendorId = 0x045E))
    }

    @Test fun linuxDevicesFollowTheirDriversLayout() {
        val sony = JsDeviceState(sony = true)
        sony.apply(1, type = 1, number = 2)
        sony.apply(32767, type = 2, number = 6)
        assertEquals(setOf(GamepadButton.Y, GamepadButton.Right), sony.state().buttons)
        sony.apply(0, type = 1, number = 2)
        sony.apply(0, type = 2, number = 6)
        assertEquals(emptySet(), sony.state().buttons)
        val xbox = JsDeviceState(sony = false)
        xbox.apply(1, type = 1, number = 2)
        assertEquals(setOf(GamepadButton.X), xbox.state().buttons)
        assertTrue(isSonyPadName("DualSense Wireless Controller"))
        assertFalse(isSonyPadName("Microsoft X-Box 360 pad"))
    }

    @Test fun buttonsMapToThePlayerAndThePages() {
        assertEquals(HudKey.Enter, playerKeyFor(GamepadButton.A))
        assertEquals(HudKey.Back, playerKeyFor(GamepadButton.B))
        assertEquals(BrowseKey(KeyEvent.VK_ENTER), browseKeyFor(GamepadButton.A))
        assertEquals(BrowseKey(BackInput.AWT_BACK), browseKeyFor(GamepadButton.B))
        assertEquals(null, browseKeyFor(GamepadButton.LeftTrigger))
    }

    @Test fun bumpersSkipWhereverTheFocusIsAndShowTheBar() {
        val (nav, effect) = HudNav(visible = false).on(playerKeyFor(GamepadButton.RightBumper))
        assertEquals(HudEffect.SeekBy(10.0), effect)
        assertTrue(nav.visible)
        assertEquals(HudEffect.SeekBy(-10.0), HudNav().on(playerKeyFor(GamepadButton.LeftBumper)).second)
    }
}
