package org.siloserver.silo.network

import io.ktor.http.Url

data class HttpOrigin(
    val scheme: String,
    val host: String,
    val port: Int,
)

fun httpOrigin(raw: String): HttpOrigin? = runCatching {
    val normalized = raw.normalizedHttpOriginUrl()
    val scheme = normalized.httpSchemeOrNull() ?: return null
    val authorityHost = normalized.authorityHostOrNull(scheme) ?: return null

    val url = Url(normalized)
    if (url.user != null || url.password != null) return null
    if (url.protocol.name.lowercase() != scheme) return null

    val parsedHost = url.host
    if (!parsedHost.isValidHttpHost()) return null
    if (!authorityHost.equals(parsedHost, ignoreCase = true)) return null

    HttpOrigin(
        scheme = scheme,
        host = parsedHost.lowercase(),
        port = url.port,
    )
}.getOrNull()

private fun String.normalizedHttpOriginUrl(): String = when {
    startsWith("wss://", ignoreCase = true) -> "https://${drop(6)}"
    startsWith("ws://", ignoreCase = true) -> "http://${drop(5)}"
    else -> this
}

fun isSameHttpOrigin(serverUrl: String, requestUrl: String): Boolean {
    val serverOrigin = httpOrigin(serverUrl) ?: return false
    val requestOrigin = httpOrigin(requestUrl) ?: return false
    return serverOrigin == requestOrigin
}

private fun String.httpSchemeOrNull(): String? = when {
    startsWith("https://", ignoreCase = true) -> "https"
    startsWith("http://", ignoreCase = true) -> "http"
    else -> null
}

private fun String.authorityHostOrNull(scheme: String): String? {
    val prefix = "$scheme://"
    val authority = drop(prefix.length).takeWhile { it != '/' && it != '?' && it != '#' }
    if (authority.isBlank() || authority.any(Char::isUnsafeAuthorityCharacter)) return null
    if ('@' in authority) return null

    return if (authority.startsWith('[')) {
        authority.bracketedIpv6HostOrNull()
    } else {
        authority.unbracketedHostOrNull()
    }
}

private fun String.bracketedIpv6HostOrNull(): String? {
    val closingBracket = indexOf(']')
    if (closingBracket <= 1) return null
    if (indexOf('[', startIndex = 1) >= 0) return null
    if (indexOf(']', startIndex = closingBracket + 1) >= 0) return null

    val literal = substring(1, closingBracket)
    if (!literal.isValidIpv6Literal()) return null

    val suffix = substring(closingBracket + 1)
    if (suffix.isNotEmpty()) {
        if (!suffix.startsWith(':')) return null
        val port = suffix.drop(1)
        if (port.isEmpty() || !port.all(Char::isAsciiDigit)) return null
    }
    return substring(0, closingBracket + 1)
}

private fun String.unbracketedHostOrNull(): String? {
    if ('[' in this || ']' in this) return null

    val firstColon = indexOf(':')
    val lastColon = lastIndexOf(':')
    if (firstColon != lastColon) return null

    val host = if (lastColon >= 0) substring(0, lastColon) else this
    if (!host.isValidRawHttpHost()) return null

    if (lastColon >= 0) {
        val port = substring(lastColon + 1)
        if (port.isEmpty() || !port.all(Char::isAsciiDigit)) return null
    }
    return host
}

private fun String.isValidRawHttpHost(): Boolean =
    isNotBlank() &&
        !startsWith('.') &&
        !contains("..") &&
        all { character ->
            character.isLetterOrDigit() ||
                character == '.' ||
                character == '-' ||
                character == '_'
        }

private fun String.isValidIpv6Literal(): Boolean {
    if (isEmpty() || any { !it.isAsciiHexDigit() && it != ':' }) return false

    val compressionIndex = indexOf("::")
    if (compressionIndex != lastIndexOf("::")) return false

    if (compressionIndex < 0) {
        val groups = split(':')
        return groups.size == IPV6_GROUP_COUNT && groups.all(String::isValidIpv6Group)
    }

    val leftGroups = substring(0, compressionIndex).ipv6GroupsOrNull() ?: return false
    val rightGroups = substring(compressionIndex + 2).ipv6GroupsOrNull() ?: return false
    return leftGroups.size + rightGroups.size < IPV6_GROUP_COUNT
}

private fun String.ipv6GroupsOrNull(): List<String>? {
    if (isEmpty()) return emptyList()
    return split(':').takeIf { groups -> groups.all(String::isValidIpv6Group) }
}

private fun String.isValidIpv6Group(): Boolean =
    length in 1..IPV6_GROUP_HEX_LENGTH && all(Char::isAsciiHexDigit)

private fun Char.isUnsafeAuthorityCharacter(): Boolean =
    isWhitespace() ||
        code <= 0x1f ||
        code == 0x7f ||
        this == '\\'

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

private fun Char.isAsciiHexDigit(): Boolean =
    isAsciiDigit() || this in 'a'..'f' || this in 'A'..'F'

private fun String.isValidHttpHost(): Boolean =
    isNotBlank() &&
        none { character ->
            character.isWhitespace() ||
                character.code <= 0x1f ||
                character.code == 0x7f ||
                character in "/?#@\\"
        }

private const val IPV6_GROUP_COUNT = 8
private const val IPV6_GROUP_HEX_LENGTH = 4
