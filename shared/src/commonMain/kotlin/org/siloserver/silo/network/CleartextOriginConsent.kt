package org.siloserver.silo.network

/**
 * Exact-origin approval boundary for credential-bearing cleartext traffic.
 *
 * Implementations persist only a digest of the canonical origin. HTTPS never
 * requires an approval entry.
 */
interface CleartextOriginConsent {
    suspend fun isApproved(origin: String): Boolean
}

class CleartextOriginNotApprovedException(origin: String) :
    IllegalStateException("Cleartext HTTP origin has not been approved: ${canonicalHttpOrigin(origin) ?: "<invalid>"}")

suspend fun CleartextOriginConsent.requiresApproval(url: String): Boolean {
    val origin = httpOrigin(url)
    return when {
        origin?.scheme == "https" -> false
        origin?.scheme == "http" -> !isApproved(checkNotNull(canonicalHttpOrigin(url)))
        url.trim().startsWith("http:", ignoreCase = true) ||
            url.trim().startsWith("ws:", ignoreCase = true) -> true
        else -> false
    }
}

fun canonicalHttpOrigin(raw: String): String? {
    val origin = httpOrigin(raw) ?: return null
    val host = if (':' in origin.host) "[${origin.host.trim('[', ']')}]" else origin.host
    val defaultPort = if (origin.scheme == "https") 443 else 80
    val port = if (origin.port == defaultPort) "" else ":${origin.port}"
    return "${origin.scheme}://$host$port"
}
