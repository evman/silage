package org.siloserver.silo.pairing

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PairingFrameTest {

    @Test
    fun singleFrameRoundTrips() {
        val payload = "hello".encodeToByteArray()
        val framed = PairingFrame.encode(payload)
        val buffer = PairingFrameBuffer()
        val out = buffer.append(framed)
        assertEquals(1, out.size)
        assertContentEquals(payload, out[0])
    }

    @Test
    fun twoFramesInOneChunk() {
        val a = "aa".encodeToByteArray()
        val b = "bbbb".encodeToByteArray()
        val chunk = PairingFrame.encode(a) + PairingFrame.encode(b)
        val buffer = PairingFrameBuffer()
        val out = buffer.append(chunk)
        assertEquals(2, out.size)
        assertContentEquals(a, out[0])
        assertContentEquals(b, out[1])
    }

    @Test
    fun frameSplitAcrossChunks() {
        val payload = "splitme".encodeToByteArray()
        val framed = PairingFrame.encode(payload)
        val buffer = PairingFrameBuffer()
        val first = buffer.append(framed.copyOfRange(0, 3))
        assertTrue(first.isEmpty(), "partial frame should yield nothing yet")
        val second = buffer.append(framed.copyOfRange(3, framed.size))
        assertEquals(1, second.size)
        assertContentEquals(payload, second[0])
    }

    @Test
    fun oversizeLengthThrows() {
        // 4-byte length header claiming 2 MiB, exceeding maxFrameBytes.
        val length = 2 shl 20
        val header = byteArrayOf(
            (length ushr 24 and 0xFF).toByte(),
            (length ushr 16 and 0xFF).toByte(),
            (length ushr 8 and 0xFF).toByte(),
            (length and 0xFF).toByte(),
        )
        val buffer = PairingFrameBuffer()
        assertFailsWith<PairingFrameTooLargeException> { buffer.append(header) }
    }

    @Test
    fun highBitLengthRejected() {
        // 4-byte length header with the high bit set (0x80000000). Read as a
        // signed Int this would go negative and slip past the max-frame check;
        // read unsigned it is ~2 GiB and must be rejected.
        val header = byteArrayOf(
            0x80.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
        )
        val buffer = PairingFrameBuffer()
        assertFailsWith<PairingFrameTooLargeException> { buffer.append(header) }
    }

    @Test
    fun encodeRejectsOversizePayload() {
        val payload = ByteArray(PairingFrame.maxFrameBytes + 1)
        assertFailsWith<PairingFrameTooLargeException> { PairingFrame.encode(payload) }
    }

    @Test
    fun messageThroughFrameRoundTrips() {
        val msg = PairingMessage.Hello(
            tvName = "TV",
            tvDeviceId = "id",
            state = PairingReceiverState.Setup,
            supportedVersions = listOf(1),
        )
        val payload = PairingMessageCodec.encode(msg).encodeToByteArray()
        val framed = PairingFrame.encode(payload)
        val buffer = PairingFrameBuffer()
        val out = buffer.append(framed)
        assertEquals(1, out.size)
        val decoded = PairingMessageCodec.decode(out[0].decodeToString())
        assertEquals(msg, decoded)
    }
}
