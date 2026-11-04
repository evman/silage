package dev.silage.app.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KwinDvInstallTest {
    private fun status(distro: String = "arch", repo: Boolean = false, patched: Boolean = false, monitor: Boolean = true) =
        KwinDvStatus(distro, repo, patched, monitor)

    @Test fun readsTheHelpersStatus() {
        assertEquals(status(repo = true, patched = true), KwinDvStatus.parse("distro=arch\nrepo=yes\npatched=yes\nmonitor=yes\n"))
        assertEquals(status("fedora", monitor = false), KwinDvStatus.parse("distro=fedora\nrepo=no\npatched=no\nmonitor=no"))
        assertNull(KwinDvStatus.parse(""))
        assertNull(KwinDvStatus.parse("usage: silage-kwin-dv status|check|enable|disable"))
    }

    /** Settings offers the patched KWin only where it can work, and always a way back. */
    @Test fun whatSettingsShows() {
        assertEquals(KwinDvState.CanEnable, kwinDvState(status(), leaseAvailable = false))
        assertEquals(KwinDvState.On, kwinDvState(status(repo = true, patched = true), leaseAvailable = true))
        assertEquals(KwinDvState.OnWithoutUpdates, kwinDvState(status(patched = true), leaseAvailable = true), "installed by hand: offer the repository")
        assertEquals(KwinDvState.NeedsRelogin, kwinDvState(status(repo = true, patched = true), leaseAvailable = false))
        assertEquals(KwinDvState.Waiting, kwinDvState(status(repo = true), leaseAvailable = false), "KDE updated KWin first: the build follows")
        assertEquals(KwinDvState.Hidden, kwinDvState(status(monitor = false), leaseAvailable = false), "no verified monitor")
        assertEquals(KwinDvState.Hidden, kwinDvState(status("unsupported"), leaseAvailable = false))
        assertEquals(KwinDvState.Hidden, kwinDvState(null, leaseAvailable = false), "helper not installed")
        assertEquals(KwinDvState.On, kwinDvState(null, leaseAvailable = true), "a KWin with the patch, however it got there")
    }

    @Test fun theHelpersAnswerBecomesAMessage() {
        assertEquals(KwinDvResult.Done("Dolby Vision KWin installed. Log out and back in to start using it."),
            kwinDvResult(0, "Dolby Vision through KWin can be enabled.\nDolby Vision KWin installed. Log out and back in to start using it.\n"))
        assertEquals(KwinDvResult.Cancelled, kwinDvResult(126, ""), "password prompt dismissed")
        assertEquals(KwinDvResult.Cancelled, kwinDvResult(127, "Error executing command as another user: Not authorized"))
        assertEquals(KwinDvResult.Failed("There's no Dolby Vision build for your KWin yet."), kwinDvResult(4, "There's no Dolby Vision build for your KWin yet.\n"))
        assertEquals(KwinDvResult.Failed("Something went wrong (exit 1)."), kwinDvResult(1, ""))
    }

    @Test fun runsTheInstalledHelperThroughThePasswordPrompt() {
        assertEquals(listOf("pkexec", "/usr/libexec/silage-kwin-dv/silage-kwin-dv", "enable"), KwinDvInstall.command(enable = true))
        assertEquals(listOf("pkexec", "/usr/libexec/silage-kwin-dv/silage-kwin-dv", "disable"), KwinDvInstall.command(enable = false))
    }
}
