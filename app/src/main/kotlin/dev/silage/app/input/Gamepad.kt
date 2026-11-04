package dev.silage.app.input

import kotlin.math.abs

/** Controller buttons by position, whatever they are labelled: [A] is the bottom face button (Cross). */
enum class GamepadButton { A, B, X, Y, LeftBumper, RightBumper, LeftTrigger, RightTrigger, View, Menu, Up, Down, Left, Right }

/** What a controller is doing now; the stick runs from -1 to 1, with up negative. */
data class GamepadState(val buttons: Set<GamepadButton> = emptySet(), val stickX: Float = 0f, val stickY: Float = 0f)

/** A source of controllers: the combined state, or null while none is connected. */
fun interface GamepadSource {
    fun poll(): GamepadState?
}

/**
 * Turns polled states into presses: one when a button goes down, then repeats
 * for the ones that make sense held. The left stick acts as the D-pad, along
 * whichever axis it is pushed furthest.
 */
class GamepadPresses(private val repeatDelayMs: Long = 400, private val repeatEveryMs: Long = 120) {
    private val nextRepeat = mutableMapOf<GamepadButton, Long>()

    fun update(state: GamepadState, now: Long): List<GamepadButton> {
        val held = state.buttons + stickDirection(state)
        nextRepeat.keys.retainAll(held)
        return buildList {
            for (button in held) {
                val due = nextRepeat[button]
                when {
                    due == null -> { add(button); nextRepeat[button] = if (button in Repeating) now + repeatDelayMs else Long.MAX_VALUE }
                    now >= due -> { add(button); nextRepeat[button] = now + repeatEveryMs }
                }
            }
        }
    }

    private fun stickDirection(s: GamepadState): Set<GamepadButton> = when {
        maxOf(abs(s.stickX), abs(s.stickY)) < StickThreshold -> emptySet()
        abs(s.stickX) > abs(s.stickY) -> setOf(if (s.stickX < 0) GamepadButton.Left else GamepadButton.Right)
        else -> setOf(if (s.stickY < 0) GamepadButton.Up else GamepadButton.Down)
    }

    private companion object {
        const val StickThreshold = 0.6f
        val Repeating = setOf(
            GamepadButton.Up, GamepadButton.Down, GamepadButton.Left, GamepadButton.Right,
            GamepadButton.LeftBumper, GamepadButton.RightBumper, GamepadButton.LeftTrigger, GamepadButton.RightTrigger,
        )
    }
}

/** The D-pad from a hat angle in hundredths of a degree (0 is up, clockwise), including diagonals. */
internal fun hatButtons(angle: Int): Set<GamepadButton> = buildSet {
    if (angle !in 0..35999) return@buildSet
    if (angle >= 31500 || angle <= 4500) add(GamepadButton.Up)
    if (angle in 4500..13500) add(GamepadButton.Right)
    if (angle in 13500..22500) add(GamepadButton.Down)
    if (angle in 22500..31500) add(GamepadButton.Left)
}
