package org.siloserver.silo.pairing

/**
 * Length-prefixed framing for the pairing channel: each payload is sent as a
 * 4-byte big-endian unsigned length followed by that many bytes of JSON. Pure
 * and dependency-free (commonMain, no java.*) so it is unit-testable in
 * isolation and byte-compatible with the silo-apple `PairingFrame`.
 */
object PairingFrame {
    /**
     * Largest single frame we will encode or accept (1 MiB). Guards against a
     * peer claiming an absurd length.
     */
    const val maxFrameBytes = 1 shl 20

    /** Prefix [payload] with its big-endian UInt32 length. */
    fun encode(payload: ByteArray): ByteArray {
        if (payload.size > maxFrameBytes) {
            throw PairingFrameTooLargeException(payload.size)
        }
        val length = payload.size
        val out = ByteArray(4 + payload.size)
        out[0] = (length ushr 24 and 0xFF).toByte()
        out[1] = (length ushr 16 and 0xFF).toByte()
        out[2] = (length ushr 8 and 0xFF).toByte()
        out[3] = (length and 0xFF).toByte()
        payload.copyInto(out, destinationOffset = 4)
        return out
    }
}

/** Thrown when a frame's declared or actual length exceeds [PairingFrame.maxFrameBytes]. */
class PairingFrameTooLargeException(val length: Long) :
    Exception("Pairing frame too large: $length bytes (max ${PairingFrame.maxFrameBytes})") {
    constructor(length: Int) : this(length.toLong())
}

/**
 * Accumulates bytes from a stream and yields complete frame payloads as they
 * arrive. Not thread-safe; confine to one connection's receive loop.
 */
class PairingFrameBuffer {
    private var buffer = ByteArray(0)

    /**
     * Append newly-received bytes and return every complete payload now
     * available (possibly zero, possibly several). Throws
     * [PairingFrameTooLargeException] if a declared length exceeds the max.
     */
    fun append(bytes: ByteArray): List<ByteArray> {
        buffer += bytes
        val payloads = mutableListOf<ByteArray>()
        var offset = 0
        while (true) {
            if (buffer.size - offset < 4) break
            // Read the 4-byte big-endian length as an UNSIGNED 32-bit value (into
            // a Long via `and 0xFFFFFFFFL`). A signed Int would let a high-bit
            // length (e.g. 0x80000000) go negative and slip past the max check
            // instead of being rejected — matches the iOS PairingFrame.
            val length = readBigEndianLength(buffer, offset)
            if (length > PairingFrame.maxFrameBytes) {
                throw PairingFrameTooLargeException(length)
            }
            // `length` is now known to be <= maxFrameBytes (1 MiB), so the
            // Int conversion below is safe.
            val frameLen = length.toInt()
            if (buffer.size - offset < 4 + frameLen) break
            val start = offset + 4
            payloads.add(buffer.copyOfRange(start, start + frameLen))
            offset = start + frameLen
        }
        buffer = if (offset == 0) buffer else buffer.copyOfRange(offset, buffer.size)
        return payloads
    }

    private fun readBigEndianLength(src: ByteArray, offset: Int): Long =
        (((src[offset].toLong() and 0xFF) shl 24) or
            ((src[offset + 1].toLong() and 0xFF) shl 16) or
            ((src[offset + 2].toLong() and 0xFF) shl 8) or
            (src[offset + 3].toLong() and 0xFF)) and 0xFFFFFFFFL
}
