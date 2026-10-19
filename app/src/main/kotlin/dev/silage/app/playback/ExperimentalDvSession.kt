package dev.silage.app.playback

import dev.silage.player.PlayerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface

internal const val ExperimentalHdrPath = "/dev/silage/KWin/HdrMetadata1"

/** Private prototype protocol, provided by the separate KWin patch. */
@DBusInterfaceName("dev.silage.KWin.HdrMetadata1")
internal interface ExperimentalHdrService : DBusInterface {
    fun acquire(outputName: String): String
    fun renew(token: String): Boolean
    fun release(token: String): Boolean
}

/** Compositor prototype stays Profile 5 only; helper sources also require actual render passes. */
internal fun experimentalDvEligible(screen: String, property: (String) -> String?, profiles: Set<Int> = setOf(5)): Boolean =
    experimentalDvIneligibleReason(screen, property, profiles) == null

internal fun experimentalDvIneligibleReason(screen: String, property: (String) -> String?, profiles: Set<Int>): String? {
    val profile = property("current-tracks/video/dolby-vision-profile")?.toIntOrNull()
    if (profile !in profiles) return if (profile == null) "No Dolby Vision source" else "Profile $profile is not supported"
    if (property("video-target-params/gamma") != "pq" || property("video-target-params/primaries") != "bt.2020")
        return "Requires PQ HDR output"
    if (property("fullscreen") != "yes") return "Requires fullscreen playback"
    if (property("display-names").orEmpty().split(",") != listOf(screen)) return "Waiting for fullscreen on $screen"
    val passes = experimentalDvRenderPasses(property)
    if (profile == 7 && passes.none { it.contains("enhancement layer") } &&
        property("video-out-params/dolby-vision-residual") != "not-required")
        return "Profile 7 enhancement layer not active · using HDR10"
    val reshaping = passes.any { it.contains("reshaping") }
    return if (reshaping) null else "DV metadata is not being processed"
}

/** Redraw statistics can describe an older cached frame; only current fresh passes qualify. */
internal fun experimentalDvRenderPasses(property: (String) -> String?): List<String> = runCatching {
    val passes = Json.parseToJsonElement(property("vo-passes") ?: "{}").jsonObject
    (passes["fresh"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject["desc"]?.jsonPrimitive?.content }
}.getOrDefault(emptyList())

/**
 * Opt-in compositor-owned signal session. No sudo, device nodes, or register writes.
 * A separate bus connection makes process/connection loss release the compositor lease.
 */
internal class ExperimentalDvSession(
    private val screen: String,
    private val state: StateFlow<PlayerState>,
    private val property: (String) -> String?,
    connect: () -> DBusConnection = { DBusConnectionBuilder.forSessionBus().withShared(false).build() },
    private val pollMs: Long = 1_000,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var closed = false
    @Volatile private var connection: DBusConnection? = null

    init {
        scope.launch {
            var bus: DBusConnection? = null
            var service: ExperimentalHdrService? = null
            var token: String? = null
            try {
                while (currentCoroutineContext().isActive) {
                    val eligible = state.value.loaded && experimentalDvEligible(screen, property)
                    if (eligible) {
                        if (service == null) {
                            val opened = connect()
                            bus = opened
                            synchronized(lock) {
                                if (closed) { opened.close(); return@launch }
                                connection = opened
                            }
                            val registry = opened.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
                            val owner = registry.GetNameOwner("org.kde.KWin")
                            service = opened.getRemoteObject(owner, ExperimentalHdrPath, ExperimentalHdrService::class.java)
                        }
                        if (token == null) {
                            token = service.acquire(screen).takeIf { it.isNotEmpty() }
                                ?: error("Compositor declined the experimental output profile")
                        }
                        check(service.renew(token)) { "Compositor output session expired" }
                    } else if (token != null) {
                        check(service?.release(token) == true) { "Compositor restoration is pending" }
                        token = null
                    }
                    delay(pollMs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Experimental capability is optional; normal HDR playback continues.
                System.err.println("Silage: experimental DV compositor session unavailable; using normal HDR")
            } finally {
                synchronized(lock) { if (connection === bus) connection = null }
                runCatching { bus?.close() }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            runCatching { connection?.close() }
            connection = null
        }
        scope.cancel()
    }
}
