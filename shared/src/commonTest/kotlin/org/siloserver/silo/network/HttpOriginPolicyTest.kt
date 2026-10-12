package org.siloserver.silo.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class HttpOriginPolicyTest {
    @Test
    fun cleartextApprovalUsesCanonicalExactOriginAndHttpsNeedsNoApproval() = runTest {
        val approved = setOf("http://silo.example", "http://[2001:db8::1]:8090")
        val consent = object : CleartextOriginConsent {
            override suspend fun isApproved(origin: String): Boolean =
                canonicalHttpOrigin(origin) in approved
        }

        assertFalse(consent.requiresApproval("HTTP://SILO.EXAMPLE:80/path"))
        assertFalse(consent.requiresApproval("http://[2001:db8::1]:8090/path"))
        assertTrue(consent.requiresApproval("http://silo.example:8090/path"))
        assertTrue(consent.requiresApproval("http://user:secret@silo.example/path"))
        assertTrue(consent.requiresApproval("""http:\\silo.example\private"""))
        assertTrue(consent.requiresApproval("HtTp:/silo.example/private"))
        assertFalse(consent.requiresApproval("https://silo.example"))
    }

    @Test
    fun webSocketSchemesUseTheirCorrespondingHttpOriginsForConsent() = runTest {
        val consent = object : CleartextOriginConsent {
            override suspend fun isApproved(origin: String): Boolean =
                origin == "http://silo.example"
        }

        assertFalse(consent.requiresApproval("ws://SILO.EXAMPLE:80/rooms/r/ws?room_token=secret"))
        assertTrue(consent.requiresApproval("ws://silo.example:8090/rooms/r/ws"))
        assertFalse(consent.requiresApproval("wss://silo.example/rooms/r/ws"))
        assertEquals("http://silo.example", canonicalHttpOrigin("ws://SILO.EXAMPLE:80/rooms/r/ws"))
        assertEquals("https://silo.example", canonicalHttpOrigin("wss://SILO.EXAMPLE:443/rooms/r/ws"))
        assertTrue(isSameHttpOrigin("http://silo.example", "ws://silo.example/rooms/r/ws"))
        assertTrue(isSameHttpOrigin("https://silo.example", "wss://silo.example/rooms/r/ws"))
    }

    @Test
    fun defaultPortsAndCaseNormalize() {
        assertTrue(isSameHttpOrigin("HTTPS://Silo.Example", "https://silo.example:443/a"))
        assertTrue(isSameHttpOrigin("http://silo.example", "http://SILO.EXAMPLE:80/a"))
        assertEquals(
            HttpOrigin(scheme = "https", host = "silo.example", port = 443),
            httpOrigin("HTTPS://Silo.Example"),
        )
    }

    @Test
    fun pathQueryAndFragmentDoNotAffectOrigin() {
        assertTrue(
            isSameHttpOrigin(
                "https://silo.example/base?server=true#settings",
                "https://SILO.EXAMPLE/library/items?offset=10#details",
            ),
        )
    }

    @Test
    fun matchingNonDefaultPortsArePreserved() {
        assertEquals(
            HttpOrigin(scheme = "https", host = "silo.example", port = 8443),
            httpOrigin("https://silo.example:8443/a"),
        )
        assertTrue(
            isSameHttpOrigin(
                "https://silo.example:8443",
                "https://SILO.EXAMPLE:8443/a",
            ),
        )
    }

    @Test
    fun schemeHostPortAndUserInfoMismatchesFailClosed() {
        assertFalse(isSameHttpOrigin("https://silo.example", "http://silo.example/a"))
        assertFalse(isSameHttpOrigin("https://silo.example", "https://cdn.silo.example/a"))
        assertFalse(isSameHttpOrigin("https://silo.example", "https://silo.example:444/a"))
        assertNull(httpOrigin("https://user@silo.example/a"))
        assertNull(httpOrigin("file:///tmp/a"))
    }

    @Test
    fun everyUserInfoFormIsRejected() {
        assertNull(httpOrigin("https://user:password@silo.example/a"))
        assertNull(httpOrigin("https://:password@silo.example/a"))
        assertNull(httpOrigin("https://@silo.example/a"))
        assertNull(httpOrigin("https://user:@silo.example/a"))
    }

    @Test
    fun relativeAndMissingAuthorityUrlsAreRejected() {
        listOf(
            "",
            "   ",
            "/relative/path",
            "silo.example/path",
            "https://",
            "https:///path",
            "https://?query",
            "https://#fragment",
            "https://:443/path",
        ).forEach { raw ->
            assertNull(httpOrigin(raw), "Expected a missing authority to be rejected: '$raw'")
        }
    }

    @Test
    fun malformedHostsAndPortsAreRejected() {
        assertNull(httpOrigin("https://silo example/path"))
        assertNull(httpOrigin("https://silo.example:65536/path"))
        assertNull(httpOrigin("https://silo.example:-1/path"))
    }

    @Test
    fun backslashAuthorityConfusionFailsClosed() {
        listOf(
            "https://\\attacker.example",
            "https://attacker.example\\path",
            "https://localhost\\@attacker.example",
            "https://attacker.example\u0001.evil",
        ).forEach { raw ->
            assertNull(httpOrigin(raw), "Expected a confused authority to be rejected: '$raw'")
        }
        assertFalse(
            isSameHttpOrigin(
                serverUrl = "https://localhost",
                requestUrl = "https://\\attacker.example",
            ),
        )
    }

    @Test
    fun malformedBracketedAuthoritiesFailClosed() {
        listOf(
            "https://[]/",
            "https://[x]/",
            "https://[::1/",
            "https://[[::1]]/",
            "https://[:::1]/",
            "https://[::1]extra/",
        ).forEach { raw ->
            assertNull(httpOrigin(raw), "Expected an invalid bracketed host to be rejected: '$raw'")
        }
    }

    @Test
    fun validatedIpv6AuthoritiesArePreserved() {
        assertEquals(
            HttpOrigin(scheme = "https", host = "[::1]", port = 443),
            httpOrigin("HTTPS://[::1]"),
        )
        assertTrue(isSameHttpOrigin("https://[::1]", "https://[::1]:443/a"))
        assertTrue(
            isSameHttpOrigin(
                "http://[2001:db8:0:1::1]:8080",
                "http://[2001:DB8:0:1::1]:8080/a",
            ),
        )
    }

    @Test
    fun invalidOriginsNeverCompareEqual() {
        assertFalse(isSameHttpOrigin("", ""))
        assertFalse(isSameHttpOrigin("/server", "/request"))
        assertFalse(
            isSameHttpOrigin(
                "https://user@silo.example",
                "https://user@silo.example/path",
            ),
        )
    }
}
