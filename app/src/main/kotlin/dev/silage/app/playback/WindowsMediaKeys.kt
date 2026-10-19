package dev.silage.app.playback

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import dev.silage.app.playback.hud.MediaKey

/**
 * The keyboard's media keys (Play/Pause, Next, Previous, Stop) while a video
 * plays, as Windows hot keys on a thread of their own. They are registered
 * only for the life of the player, so music apps get them back afterwards;
 * a key another app already holds is simply not available. No-op elsewhere.
 */
internal class WindowsMediaKeys(private val onKey: (MediaKey) -> Unit) : AutoCloseable {
    internal interface User32 : StdCallLibrary {
        fun RegisterHotKey(hwnd: Pointer?, id: Int, modifiers: Int, vk: Int): Boolean
        fun UnregisterHotKey(hwnd: Pointer?, id: Int): Boolean
        fun GetMessageW(msg: Pointer, hwnd: Pointer?, min: Int, max: Int): Int
        fun PostThreadMessageW(threadId: Int, msg: Int, wParam: Pointer?, lParam: Pointer?): Boolean
    }

    internal interface Kernel32 : StdCallLibrary {
        fun GetCurrentThreadId(): Int
    }

    @Volatile private var threadId = 0
    private val thread: Thread? = if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) null else
        Thread(::run, "silage-media-keys").apply { isDaemon = true; start() }

    private fun run() {
        runCatching {
            val user32 = Native.load("user32", User32::class.java)
            threadId = Native.load("kernel32", Kernel32::class.java).GetCurrentThreadId()
            val registered = KEYS.filter { (id, vk) -> user32.RegisterHotKey(null, id, MOD_NOREPEAT, vk) }.map { it.first }
            try {
                // MSG on 64-bit Windows: hwnd (8), message (4 + 4 padding), wParam (8), ...
                val msg = Memory(64)
                while (user32.GetMessageW(msg, null, 0, 0) > 0) {
                    if (msg.getInt(8) == WM_HOTKEY) KEY_OF[msg.getLong(16).toInt()]?.let { runCatching { onKey(it) } }
                }
            } finally {
                registered.forEach { user32.UnregisterHotKey(null, it) }
            }
        }
    }

    override fun close() {
        val t = thread ?: return
        // Ends GetMessageW; a thread that hasn't started its loop yet gets the quit when it does.
        repeat(20) {
            if (threadId != 0) {
                runCatching { Native.load("user32", User32::class.java).PostThreadMessageW(threadId, WM_QUIT, null, null) }
                t.join(1_000)
                return
            }
            Thread.sleep(10)
        }
    }

    companion object {
        private const val WM_HOTKEY = 0x0312
        private const val WM_QUIT = 0x0012
        private const val MOD_NOREPEAT = 0x4000
        /** Hot key id to virtual key: VK_MEDIA_PLAY_PAUSE, _NEXT_TRACK, _PREV_TRACK, _STOP. */
        private val KEYS = listOf(1 to 0xB3, 2 to 0xB0, 3 to 0xB1, 4 to 0xB2)
        private val KEY_OF = mapOf(1 to MediaKey.PlayPause, 2 to MediaKey.Next, 3 to MediaKey.Previous, 4 to MediaKey.Stop)
    }
}
