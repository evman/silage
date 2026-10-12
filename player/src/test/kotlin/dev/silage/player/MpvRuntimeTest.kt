package dev.silage.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvRuntimeTest {
    /** Ubuntu's libmpv2 and Fedora's mpv-libs ship only libmpv.so.2 (the bare .so is in -dev packages). */
    @Test fun loadsTheSonameFirstAndFallsBackToTheDevName() {
        val tried = mutableListOf<String>()
        val got = LibMpv.loadFirst(listOf("libmpv.so.2", "mpv")) { name ->
            tried += name
            if (name == "mpv") "dev" else throw UnsatisfiedLinkError("no $name")
        }
        assertEquals("dev", got)
        assertEquals(listOf("libmpv.so.2", "mpv"), tried)
    }

    @Test fun whenNothingLoadsTheErrorNamesEveryAttempt() {
        val e = assertFailsWith<UnsatisfiedLinkError> {
            LibMpv.loadFirst(listOf("libmpv.so.2", "mpv")) { throw UnsatisfiedLinkError("no $it") }
        }
        assertTrue("libmpv.so.2" in e.message!! && "mpv" in e.message!!, e.message)
    }

    @Test fun versionsParseFromMpvVersionStrings() {
        assertEquals(Triple(0, 41, 0), MpvRuntime.parseVersion("mpv 0.41.0"))
        assertEquals(Triple(0, 37, 0), MpvRuntime.parseVersion("mpv v0.37.0-dirty"))
        assertEquals(Triple(0, 38, 0), MpvRuntime.parseVersion("mpv 0.38.0-UNKNOWN"))
        assertEquals(Triple(0, 40, 0), MpvRuntime.parseVersion("mpv v0.40.0-462-gabcdef"))
        assertNull(MpvRuntime.parseVersion("garbage"))
    }

    @Test fun needsAtLeast037() {
        assertTrue(MpvRuntime.meetsMinimum(Triple(0, 37, 0)))
        assertTrue(MpvRuntime.meetsMinimum(Triple(1, 0, 0)))
        assertFalse(MpvRuntime.meetsMinimum(Triple(0, 36, 9)))
    }

    @Test fun installHintsNameTheRightPackage() {
        assertEquals("sudo pacman -S mpv", MpvRuntime.installHint("arch", null))
        assertEquals("sudo pacman -S mpv", MpvRuntime.installHint("cachyos", "arch"))
        assertEquals("sudo apt install libmpv2", MpvRuntime.installHint("ubuntu", "debian"))
        assertEquals("sudo apt install libmpv2", MpvRuntime.installHint("linuxmint", "ubuntu debian"))
        assertEquals("sudo dnf install mpv-libs", MpvRuntime.installHint("fedora", null))
        assertEquals("sudo dnf install mpv-libs", MpvRuntime.installHint("nobara", "fedora"))
        assertTrue("mpv" in MpvRuntime.installHint("gentoo", null))
        assertTrue("mpv" in MpvRuntime.installHint(null, null))
    }

    @Test fun osReleaseIsParsed() {
        val text = "NAME=\"Ubuntu\"\nID=ubuntu\nID_LIKE=debian\nVERSION_ID=\"24.04\"\n"
        assertEquals("ubuntu" to "debian", MpvRuntime.osRelease(text))
        assertEquals("linuxmint" to "ubuntu debian", MpvRuntime.osRelease("ID=linuxmint\nID_LIKE=\"ubuntu debian\""))
    }

    @Test fun theInstalledMpvIsReportedUsable() {
        val status = MpvRuntime.check()
        assertTrue(status is MpvStatus.Ok, "status=$status")
    }
}
