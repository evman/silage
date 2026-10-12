package org.siloserver.silo.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingMessageCodecTest {

    private fun roundTrip(msg: PairingMessage) {
        val encoded = PairingMessageCodec.encode(msg)
        val decoded = PairingMessageCodec.decode(encoded)
        assertEquals(msg, decoded, "round-trip should preserve message: $encoded")
    }

    @Test
    fun roundTripsAllVariants() {
        roundTrip(
            PairingMessage.Hello(
                tvName = "Living Room",
                tvDeviceId = "dev-123",
                state = PairingReceiverState.Setup,
                supportedVersions = listOf(1),
            ),
        )
        roundTrip(PairingMessage.PushServer(serverURL = "https://srv", serverName = "Home"))
        roundTrip(PairingMessage.PushServer(serverURL = "https://srv", serverName = null))
        roundTrip(
            PairingMessage.DeviceStarted(
                serverURL = "https://srv",
                userCode = "ABCD-1234",
                matchCode = "M7",
            ),
        )
        roundTrip(
            PairingMessage.ServerResult(
                serverURL = "https://srv",
                status = PairingServerStatus.SignedIn,
                error = null,
            ),
        )
        roundTrip(
            PairingMessage.ServerResult(
                serverURL = "https://srv",
                status = PairingServerStatus.Failed,
                error = "bad creds",
            ),
        )
        roundTrip(PairingMessage.Done)
        roundTrip(PairingMessage.Cancel(reason = "user cancelled"))
    }

    @Test
    fun helloExactWire() {
        val encoded = PairingMessageCodec.encode(
            PairingMessage.Hello(
                tvName = "Den",
                tvDeviceId = "id-9",
                state = PairingReceiverState.Login,
                supportedVersions = listOf(1),
            ),
        )
        assertEquals(
            "{\"v\":1,\"type\":\"hello\",\"tvName\":\"Den\",\"tvDeviceId\":\"id-9\"," +
                "\"state\":\"login\",\"supportedVersions\":[1]}",
            encoded,
        )
    }

    @Test
    fun pushServerExactWireWithName() {
        val encoded = PairingMessageCodec.encode(
            PairingMessage.PushServer(serverURL = "https://srv", serverName = "Home"),
        )
        assertEquals(
            "{\"v\":1,\"type\":\"pushServer\",\"serverURL\":\"https://srv\",\"serverName\":\"Home\"}",
            encoded,
        )
    }

    @Test
    fun pushServerOmitsNullName() {
        val encoded = PairingMessageCodec.encode(
            PairingMessage.PushServer(serverURL = "https://srv", serverName = null),
        )
        assertEquals(
            "{\"v\":1,\"type\":\"pushServer\",\"serverURL\":\"https://srv\"}",
            encoded,
        )
        assertFalse(encoded.contains("serverName"))
    }

    @Test
    fun serverResultExactWire() {
        val encoded = PairingMessageCodec.encode(
            PairingMessage.ServerResult(
                serverURL = "https://srv",
                status = PairingServerStatus.SignedIn,
                error = null,
            ),
        )
        assertEquals(
            "{\"v\":1,\"type\":\"serverResult\",\"serverURL\":\"https://srv\",\"status\":\"signedIn\"}",
            encoded,
        )
        assertFalse(encoded.contains("error"))
    }

    @Test
    fun serverResultIncludesErrorWhenPresent() {
        val encoded = PairingMessageCodec.encode(
            PairingMessage.ServerResult(
                serverURL = "https://srv",
                status = PairingServerStatus.Failed,
                error = "boom",
            ),
        )
        assertTrue(encoded.contains("\"status\":\"failed\""))
        assertTrue(encoded.contains("\"error\":\"boom\""))
    }
}
