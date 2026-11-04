package dev.silage.app.input

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/** The kernel driver's button numbers for an Xbox-style pad (xpad and most others). */
private val XpadButtons = listOf(
    GamepadButton.A, GamepadButton.B, GamepadButton.X, GamepadButton.Y, GamepadButton.LeftBumper, GamepadButton.RightBumper,
    GamepadButton.View, GamepadButton.Menu,
)

/** The kernel driver's button numbers for DualSense and DualShock 4 (hid-playstation, hid-sony). */
private val SonyLinuxButtons = listOf(
    GamepadButton.A, GamepadButton.B, GamepadButton.Y, GamepadButton.X, GamepadButton.LeftBumper, GamepadButton.RightBumper,
    GamepadButton.LeftTrigger, GamepadButton.RightTrigger, GamepadButton.View, GamepadButton.Menu,
)

internal fun isSonyPadName(name: String): Boolean = Regex("dualsense|dualshock|wireless controller|sony", RegexOption.IGNORE_CASE).containsMatchIn(name)

/** One event from a joystick device: time (4), value (2), type (1), number (1). */
internal class JsDeviceState(sony: Boolean) {
    private val layout = if (sony) SonyLinuxButtons else XpadButtons
    private val buttons = mutableSetOf<GamepadButton>()
    private var stickX = 0f
    private var stickY = 0f
    private var hatX = 0
    private var hatY = 0

    @Synchronized
    fun apply(value: Short, type: Int, number: Int) {
        when (type and 0x7F) {
            1 -> layout.getOrNull(number)?.let { if (value.toInt() != 0) buttons += it else buttons -= it }
            2 -> when (number) {
                0 -> stickX = value / 32768f
                1 -> stickY = value / 32768f
                6 -> hatX = value.toInt()
                7 -> hatY = value.toInt()
            }
        }
    }

    @Synchronized
    fun state(): GamepadState = GamepadState(
        buttons = buttons + buildSet {
            if (hatX < 0) add(GamepadButton.Left)
            if (hatX > 0) add(GamepadButton.Right)
            if (hatY < 0) add(GamepadButton.Up)
            if (hatY > 0) add(GamepadButton.Down)
        },
        stickX = stickX, stickY = stickY,
    )
}

/**
 * Controllers on Linux, from the kernel's `/dev/input/js*` devices: a thread
 * per pad blocks on its device, and [poll] combines what they have seen.
 * A device this user can't read is skipped.
 */
internal class LinuxGamepads(private val inputDir: File = File("/dev/input"), private val sysDir: File = File("/sys/class/input")) : GamepadSource {
    private val pads = ConcurrentHashMap<String, JsDeviceState>()
    private var rescanAt = 0L

    override fun poll(): GamepadState? {
        val now = System.currentTimeMillis()
        if (now >= rescanAt) { rescan(); rescanAt = now + 2_000 }
        val states = pads.values.map { it.state() }
        if (states.isEmpty()) return null
        return GamepadState(
            buttons = states.flatMapTo(mutableSetOf()) { it.buttons },
            stickX = states.maxBy { kotlin.math.abs(it.stickX) }.stickX,
            stickY = states.maxBy { kotlin.math.abs(it.stickY) }.stickY,
        )
    }

    private fun rescan() {
        inputDir.listFiles { f -> f.name.matches(Regex("js\\d+")) }?.forEach { device ->
            if (pads.containsKey(device.name) || !device.canRead()) return@forEach
            val name = runCatching { File(sysDir, "${device.name}/device/name").readText() }.getOrDefault("")
            val pad = JsDeviceState(isSonyPadName(name))
            pads[device.name] = pad
            Thread({ read(device, pad) }, "silage-gamepad-${device.name}").apply { isDaemon = true; start() }
        }
    }

    private fun read(device: File, pad: JsDeviceState) {
        try {
            FileInputStream(device).use { input ->
                val bytes = ByteArray(8)
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                while (input.readNBytes(bytes, 0, 8) == 8) {
                    pad.apply(buffer.getShort(4), bytes[6].toInt() and 0xFF, bytes[7].toInt() and 0xFF)
                }
            }
        } catch (_: java.io.IOException) {
            // Unplugged; a later rescan finds it again if it returns.
        } finally {
            pads.remove(device.name)
        }
    }
}
