package org.siloserver.silo.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class Base64Test {
    @Test fun urlSafeNoPaddingRoundTrip() {
        val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        val encoded = Base64.encodeToString("https://a.b:8096/?x=~~".toByteArray(), flags)
        assertEquals(false, encoded.contains('=') || encoded.contains('+') || encoded.contains('/'))
        assertEquals("https://a.b:8096/?x=~~", Base64.decode(encoded, flags).decodeToString())
    }
}
