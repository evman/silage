package dev.silage.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUserModelIdTest {
    /** The installer's shortcuts carry the same ID, so the taskbar groups the window under them. */
    @Test fun appUserModelIdIsSetOnlyOnWindows() {
        val calls = mutableListOf<String>()
        val fakeShell32: (String) -> Int = { id -> calls += id; 0 }

        assertFalse(WindowsChrome.setAppUserModelId(osName = "Linux", setId = fakeShell32))
        assertEquals(emptyList(), calls)

        assertTrue(WindowsChrome.setAppUserModelId(osName = "Windows 11", setId = fakeShell32))
        assertEquals(listOf("dev.silage.Silage"), calls)
    }

    @Test fun aFailingCallIsReportedNotThrown() {
        assertFalse(WindowsChrome.setAppUserModelId(osName = "Windows 10", setId = { 0x80070057.toInt() }))
        assertFalse(WindowsChrome.setAppUserModelId(osName = "Windows 10", setId = { throw UnsatisfiedLinkError("no shell32") }))
    }
}
