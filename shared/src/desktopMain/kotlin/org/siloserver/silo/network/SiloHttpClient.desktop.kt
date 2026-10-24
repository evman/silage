package org.siloserver.silo.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header

actual fun createPlatformHttpClient(retryOnConnectionFailure: Boolean): HttpClient =
    HttpClient(OkHttp) {
        engine {
            config { retryOnConnectionFailure(retryOnConnectionFailure) }
        }
        // Named like the other Silo apps do, so the server's list of signed-in devices
        // (Settings → Devices) says "Silage on DESKTOP-1234" rather than a library's User-Agent.
        defaultRequest {
            DesktopDevice.headers.forEach { (name, value) -> header(name, value) }
        }
    }

/** This PC as the server's device list shows it. Nothing here authorizes anything. */
internal object DesktopDevice {
    private val computer: String by lazy {
        val found = System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
            ?: System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
            ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
        // Header values must be plain ASCII.
        found?.filter { it.code in 32..126 }?.ifBlank { null } ?: "this PC"
    }

    val name: String get() = "Silage on $computer"

    val platform: String get() = System.getProperty("os.name").orEmpty().let {
        when {
            it.startsWith("Windows") -> "Windows"
            it.startsWith("Linux") -> "Linux"
            it.startsWith("Mac") -> "macOS"
            else -> it.ifBlank { "Desktop" }
        }
    }

    /** Stable for this PC and user, without storing anything. */
    val id: String by lazy {
        java.util.UUID.nameUUIDFromBytes("silage:$computer:${System.getProperty("user.name").orEmpty()}".toByteArray()).toString()
    }

    val headers: List<Pair<String, String>> get() = listOf(
        "X-Silo-Device-Name" to name,
        "X-Silo-Device-Platform" to platform,
        "X-Silo-Device-Id" to id,
    )
}
