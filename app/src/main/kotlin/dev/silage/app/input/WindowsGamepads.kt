package dev.silage.app.input

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary

/** The XInput state word's bits ([XINPUT_GAMEPAD]). */
internal fun xinputButtons(word: Int): Set<GamepadButton> = XInputBits.filterKeys { word and it != 0 }.values.toSet()

private val XInputBits = mapOf(
    0x0001 to GamepadButton.Up, 0x0002 to GamepadButton.Down, 0x0004 to GamepadButton.Left, 0x0008 to GamepadButton.Right,
    0x0010 to GamepadButton.Menu, 0x0020 to GamepadButton.View,
    0x0100 to GamepadButton.LeftBumper, 0x0200 to GamepadButton.RightBumper,
    0x1000 to GamepadButton.A, 0x2000 to GamepadButton.B, 0x4000 to GamepadButton.X, 0x8000 to GamepadButton.Y,
)

/** Button numbers on DualSense and DualShock 4 controllers, which Windows lists as plain joysticks. */
private val SonyButtons = listOf(
    GamepadButton.X, GamepadButton.A, GamepadButton.B, GamepadButton.Y, GamepadButton.LeftBumper, GamepadButton.RightBumper,
    GamepadButton.LeftTrigger, GamepadButton.RightTrigger, GamepadButton.View, GamepadButton.Menu,
)

/** Button numbers on other plain joysticks, which mostly follow the Xbox layout. */
private val GenericButtons = listOf(
    GamepadButton.A, GamepadButton.B, GamepadButton.X, GamepadButton.Y, GamepadButton.LeftBumper, GamepadButton.RightBumper,
    GamepadButton.View, GamepadButton.Menu,
)

internal const val SonyVendorId = 0x054C

/** A plain joystick's pressed buttons, by its vendor's layout. */
internal fun joystickButtons(mask: Int, vendorId: Int): Set<GamepadButton> =
    (if (vendorId == SonyVendorId) SonyButtons else GenericButtons).filterIndexedTo(mutableSetOf()) { i, _ -> mask and (1 shl i) != 0 }

/**
 * Controllers on Windows: XInput for Xbox-style pads, and the older joystick
 * API for everything else, including PlayStation controllers. A pad that shows
 * up in both is read only through XInput. Asking about an empty slot is slow,
 * so slots are searched [RescanMs] apart and only found ones are read between.
 */
internal class WindowsGamepads : GamepadSource {
    private interface XInput : StdCallLibrary {
        fun XInputGetState(user: Int, state: Pointer): Int
    }

    private interface WinMm : StdCallLibrary {
        fun joyGetNumDevs(): Int
        fun joyGetPosEx(id: Int, info: Pointer): Int
        fun joyGetDevCapsW(id: Int, caps: Pointer, size: Int): Int
    }

    private val xinput = runCatching { Native.load("xinput1_4", XInput::class.java) }.getOrNull()
    private val winmm = runCatching { Native.load("winmm", WinMm::class.java) }.getOrNull()
    private val xinputState = Memory(16)
    private val joyInfo = Memory(52)
    private val joyCaps = Memory(728)

    private var xinputPads = emptyList<Int>()
    private var joysticks = emptyList<Pair<Int, Int>>()
    private var rescanAt = 0L

    override fun poll(): GamepadState? {
        val now = System.currentTimeMillis()
        if (now >= rescanAt) { rescan(); rescanAt = now + RescanMs }
        val states = xinputPads.mapNotNull(::readXInput).ifEmpty { joysticks.mapNotNull { (id, vendor) -> readJoystick(id, vendor) } }
        if (states.isEmpty()) return null
        return GamepadState(
            buttons = states.flatMapTo(mutableSetOf()) { it.buttons },
            stickX = states.maxBy { kotlin.math.abs(it.stickX) }.stickX,
            stickY = states.maxBy { kotlin.math.abs(it.stickY) }.stickY,
        )
    }

    private fun rescan() {
        xinputPads = (0..3).filter { xinput?.XInputGetState(it, xinputState) == 0 }
        val mm = winmm
        joysticks = if (mm == null || xinputPads.isNotEmpty()) emptyList()
        else (0 until mm.joyGetNumDevs().coerceAtMost(16)).mapNotNull { id ->
            if (mm.joyGetDevCapsW(id, joyCaps, joyCaps.size().toInt()) != 0 || !queryJoystick(id)) null
            else id to (joyCaps.getShort(0).toInt() and 0xFFFF)
        }
    }

    private fun readXInput(user: Int): GamepadState? {
        if (xinput?.XInputGetState(user, xinputState) != 0) return null
        // XINPUT_STATE: packet number (4), buttons (2), triggers (1 + 1), then the sticks (2 each).
        val triggers = buildSet {
            if (xinputState.getByte(6).toInt() and 0xFF > TriggerThreshold) add(GamepadButton.LeftTrigger)
            if (xinputState.getByte(7).toInt() and 0xFF > TriggerThreshold) add(GamepadButton.RightTrigger)
        }
        return GamepadState(
            buttons = xinputButtons(xinputState.getShort(4).toInt() and 0xFFFF) + triggers,
            stickX = xinputState.getShort(8) / 32768f,
            // XInput's up is positive.
            stickY = -xinputState.getShort(10) / 32768f,
        )
    }

    private fun queryJoystick(id: Int): Boolean {
        // JOYINFOEX: size, flags, then x, y, z, r, u, v, buttons, button number, pov (4 each).
        joyInfo.setInt(0, joyInfo.size().toInt())
        joyInfo.setInt(4, JOY_RETURNALL)
        return winmm?.joyGetPosEx(id, joyInfo) == 0
    }

    private fun readJoystick(id: Int, vendor: Int): GamepadState? {
        if (!queryJoystick(id)) return null
        return GamepadState(
            buttons = joystickButtons(joyInfo.getInt(32), vendor) + hatButtons(joyInfo.getInt(40)),
            stickX = axis(joyInfo.getInt(8)),
            stickY = axis(joyInfo.getInt(12)),
        )
    }

    private fun axis(raw: Int): Float = (raw - 32767.5f) / 32767.5f

    private companion object {
        const val RescanMs = 2_000L
        const val TriggerThreshold = 100
        const val JOY_RETURNALL = 0xFF
    }
}
