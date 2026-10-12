package org.siloserver.silo.platform

/** Flag-compatible stand-in for android.util.Base64. */
object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val URL_SAFE = 8

    fun encodeToString(input: ByteArray, flags: Int): String {
        var encoder = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) encoder = encoder.withoutPadding()
        return encoder.encodeToString(input)
    }

    fun decode(input: String, flags: Int): ByteArray {
        val decoder = if (flags and URL_SAFE != 0) java.util.Base64.getUrlDecoder() else java.util.Base64.getDecoder()
        return decoder.decode(input)
    }
}
