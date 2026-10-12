package dev.silage.app.connect

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConnectPresenterTest {
    @Test fun connectsWithNormalizedUrl() = runTest {
        var connected: String? = null
        val p = ConnectPresenter(connect = { url -> connected = url; Result.success(Unit) })
        assertIs<ConnectState.Connected>(p.submit("nas:8096"))
        assertEquals("http://nas:8096", connected)
    }

    @Test fun invalidAddressNeverCallsServer() = runTest {
        val p = ConnectPresenter(connect = { error("must not be called") })
        val s = p.submit("ftp://x")
        assertIs<ConnectState.Error>(s)
        assertEquals("Enter an address like 192.168.1.5:8096 or https://media.example.com", s.message)
    }

    @Test fun unreachableServerShowsMessage() = runTest {
        val p = ConnectPresenter(connect = { Result.failure(java.net.ConnectException("refused")) })
        val s = p.submit("nas:8096")
        assertIs<ConnectState.Error>(s)
        assertEquals("Couldn't reach http://nas:8096. Check the address and that the server is running.", s.message)
    }
}
