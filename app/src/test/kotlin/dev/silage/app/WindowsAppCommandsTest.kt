package dev.silage.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsAppCommandsTest {
    /** WM_APPCOMMAND's lParam: the command and the device in the high word, the key state in the low word. */
    private fun lParam(command: Int, device: Int, keys: Int = 0): Long = (((command or device).toLong() and 0xFFFF) shl 16) or keys.toLong()

    @Test fun theCommandIsTheHighWordWithoutTheDeviceBits() {
        assertEquals(1, AppCommand.command(lParam(1, AppCommand.FAPPCOMMAND_KEY)))
        assertEquals(1, AppCommand.command(lParam(1, AppCommand.FAPPCOMMAND_OEM, keys = 0x0008)))
        assertEquals(1, AppCommand.command(lParam(1, AppCommand.FAPPCOMMAND_MOUSE)))
        assertEquals(2, AppCommand.command(lParam(2, AppCommand.FAPPCOMMAND_KEY)))
        assertEquals(AppCommand.FAPPCOMMAND_MOUSE, AppCommand.device(lParam(1, AppCommand.FAPPCOMMAND_MOUSE)))
        // A 64-bit lParam whose upper half is set (sign extension) decodes the same.
        assertEquals(1, AppCommand.command(lParam(1, AppCommand.FAPPCOMMAND_KEY) or (-1L shl 32)))
    }

    @Test fun browserBackFromAKeyboardOrRemoteIsBack() {
        assertTrue(AppCommand.isBrowserBack(lParam(AppCommand.APPCOMMAND_BROWSER_BACKWARD, AppCommand.FAPPCOMMAND_KEY)))
        assertTrue(AppCommand.isBrowserBack(lParam(AppCommand.APPCOMMAND_BROWSER_BACKWARD, AppCommand.FAPPCOMMAND_OEM)))
        // Browser Forward, Play/Pause… are left to Windows.
        assertFalse(AppCommand.isBrowserBack(lParam(2, AppCommand.FAPPCOMMAND_KEY)))
        assertFalse(AppCommand.isBrowserBack(lParam(14, AppCommand.FAPPCOMMAND_KEY)))
    }

    /** The mouse's Back button already arrives as mouse button 4; its WM_APPCOMMAND would go back twice. */
    @Test fun browserBackFromTheMouseIsLeftToTheMouseButton() {
        assertFalse(AppCommand.isBrowserBack(lParam(AppCommand.APPCOMMAND_BROWSER_BACKWARD, AppCommand.FAPPCOMMAND_MOUSE)))
    }

    @Test fun nothingIsHookedOutsideWindows() {
        val window = java.awt.Frame()
        try {
            var backs = 0
            WindowsAppCommands.install(window, osName = "Linux") { backs++ }.close()
            assertEquals(0, backs)
            assertEquals(null, WindowsAppCommands.install(window, osName = "Linux") {}.let { it as? WindowsAppCommands })
        } finally { window.dispose() }
    }
}
