package dev.silage.app.connect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerAddressTest {
    @Test fun addsHttpWhenSchemeMissing() = assertEquals("http://192.168.1.5:8096", ServerAddress.normalize("192.168.1.5:8096"))
    @Test fun keepsHttps() = assertEquals("https://media.example.com", ServerAddress.normalize("https://media.example.com/"))
    @Test fun lowercasesSchemeAndHost() = assertEquals("https://media.example.com:443", ServerAddress.normalize("HTTPS://Media.Example.com:443"))
    @Test fun trimsWhitespaceAndTrailingSlashes() = assertEquals("http://nas:8096/silo", ServerAddress.normalize("  nas:8096/silo//  "))
    @Test fun rejectsBlank() = assertNull(ServerAddress.normalize("   "))
    @Test fun rejectsGarbage() = assertNull(ServerAddress.normalize("http://"))
    @Test fun rejectsOtherSchemes() = assertNull(ServerAddress.normalize("ftp://nas"))
}
