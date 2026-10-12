package dev.silage.app.connect

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConnectPresenterTest {
    private val saved = mutableListOf<String>()
    private fun presenter(probe: ProbeOutcome) = ConnectPresenter(probe = { probe }, save = { saved += it })

    @Test fun reachableServerIsSavedWithNormalizedUrl() = runTest {
        assertIs<ConnectState.Connected>(presenter(ProbeOutcome.Reachable).submit("nas:8096"))
        assertEquals(listOf("http://nas:8096"), saved)
    }

    @Test fun invalidAddressNeverCallsServer() = runTest {
        val p = ConnectPresenter(probe = { error("must not be called") }, save = { error("must not be called") })
        val s = p.submit("ftp://x")
        assertIs<ConnectState.Error>(s)
        assertEquals("Enter an address like 192.168.1.5:8096 or https://media.example.com", s.message)
    }

    @Test fun unreachableServerShowsMessageAndIsNotSaved() = runTest {
        val s = presenter(ProbeOutcome.Unreachable).submit("nas:8096")
        assertIs<ConnectState.Error>(s)
        assertEquals("Couldn't reach http://nas:8096. Check the address and that the server is running.", s.message)
        assertTrue(saved.isEmpty())
    }

    @Test fun oldServerIsRejected() = runTest {
        val s = presenter(ProbeOutcome.NeedsServerUpdate).submit("nas:8096")
        assertEquals(ConnectState.Error("This Silo server is too old for Silage. Update the server and try again."), s)
        assertTrue(saved.isEmpty())
    }
}
