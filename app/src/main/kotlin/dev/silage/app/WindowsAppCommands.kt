package dev.silage.app

import com.sun.jna.CallbackReference
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary

/** Decoding WM_APPCOMMAND, which Windows sends for keys like Browser Back that Java has no key for. */
internal object AppCommand {
    const val WM_APPCOMMAND = 0x0319
    const val APPCOMMAND_BROWSER_BACKWARD = 1
    const val FAPPCOMMAND_KEY = 0
    const val FAPPCOMMAND_OEM = 0x1000
    const val FAPPCOMMAND_MOUSE = 0x8000
    private const val FAPPCOMMAND_MASK = 0xF000

    private fun highWord(lParam: Long): Int = ((lParam ushr 16) and 0xFFFF).toInt()

    /** GET_APPCOMMAND_LPARAM: HIWORD(lParam) & ~FAPPCOMMAND_MASK. */
    fun command(lParam: Long): Int = highWord(lParam) and FAPPCOMMAND_MASK.inv()

    /** GET_DEVICE_LPARAM: which device sent it (key, OEM remote, mouse). */
    fun device(lParam: Long): Int = highWord(lParam) and FAPPCOMMAND_MASK

    /**
     * Browser Back from a keyboard or remote. The mouse's Back button is
     * left out: Java already sees it as mouse button 4.
     */
    fun isBrowserBack(lParam: Long): Boolean =
        command(lParam) == APPCOMMAND_BROWSER_BACKWARD && device(lParam) != FAPPCOMMAND_MOUSE
}

/**
 * Catches Browser Back (WM_APPCOMMAND) for the Silage window on Windows, by
 * putting a window procedure in front of Java's; everything else goes on to
 * Java's unchanged. A remote's Back button (issue #3) arrives this way when
 * Java leaves the key unhandled. [install] is a no-op elsewhere, and any
 * failure leaves the window as it was.
 */
internal class WindowsAppCommands private constructor(private val hwnd: Pointer, private val onBack: () -> Unit) : AutoCloseable {
    internal interface WndProc : StdCallLibrary.StdCallCallback {
        fun callback(hwnd: Pointer?, msg: Int, wParam: Pointer?, lParam: Pointer?): Pointer?
    }

    internal interface User32 : StdCallLibrary {
        fun SetWindowLongPtrW(hwnd: Pointer, index: Int, proc: WndProc): Pointer?
        fun SetWindowLongPtrW(hwnd: Pointer, index: Int, proc: Pointer?): Pointer?
        fun GetWindowLongPtrW(hwnd: Pointer, index: Int): Pointer?
        fun CallWindowProcW(prev: Pointer?, hwnd: Pointer?, msg: Int, wParam: Pointer?, lParam: Pointer?): Pointer?
    }

    private val user32 = Native.load("user32", User32::class.java)
    @Volatile private var previous: Pointer? = null

    // Called by Windows on Java's toolkit thread, for every message the window gets.
    private val proc = object : WndProc {
        override fun callback(hwnd: Pointer?, msg: Int, wParam: Pointer?, lParam: Pointer?): Pointer? {
            try {
                if (msg == AppCommand.WM_APPCOMMAND && AppCommand.isBrowserBack(Pointer.nativeValue(lParam))) {
                    onBack()
                    return TRUE
                }
            } catch (t: Throwable) {
                System.err.println("Silage: Browser Back handling failed: $t")
            }
            return user32.CallWindowProcW(previous, hwnd, msg, wParam, lParam)
        }
    }

    private fun attach(): Boolean {
        // Kept for good: Windows may still call it after close if another hook was put in front.
        retained += proc
        previous = user32.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, proc)
        return previous != null
    }

    override fun close() {
        runCatching {
            val prev = previous ?: return
            // Only undo it while it is still ours, or the procedure in front of it would be cut off.
            if (user32.GetWindowLongPtrW(hwnd, GWLP_WNDPROC) == CallbackReference.getFunctionPointer(proc)) {
                user32.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, prev)
            }
        }.onFailure { System.err.println("Silage: couldn't remove the Browser Back handler: $it") }
    }

    companion object {
        private const val GWLP_WNDPROC = -4
        private val TRUE = Pointer(1)
        private val retained = java.util.Collections.synchronizedList(mutableListOf<WndProc>())

        /** Starts catching Browser Back for [window] (displayable, on the event thread). */
        fun install(window: java.awt.Window, osName: String = System.getProperty("os.name").orEmpty(), onBack: () -> Unit): AutoCloseable {
            if (!osName.startsWith("Windows")) return AutoCloseable {}
            return runCatching {
                val hwnd = Native.getComponentPointer(window) ?: error("the window has no handle yet")
                WindowsAppCommands(hwnd, onBack).takeIf { it.attach() } ?: error("SetWindowLongPtrW failed (${Native.getLastError()})")
            }.getOrElse {
                System.err.println("Silage: Browser Back from remotes unavailable: ${it.message}")
                AutoCloseable {}
            }
        }
    }
}
