package dev.silage.app.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

@DBusInterfaceName("org.freedesktop.Notifications")
internal interface NotificationService : DBusInterface {
    fun GetCapabilities(): List<String>
    fun Notify(app: String, replaces: UInt32, icon: String, title: String, body: String,
               actions: List<String>, hints: Map<String, Variant<*>>, timeout: Int): UInt32
    fun CloseNotification(id: UInt32)
    class ActionInvoked(path: String, val id: UInt32, val action: String) : DBusSignal(path, id, action)
}

/** Freedesktop notifications work on Wayland without an X11 system tray. */
internal class LinuxNotifications(
    private val onClick: () -> Unit,
    private val serviceName: String = "org.freedesktop.Notifications",
    private val connect: () -> DBusConnection = { DBusConnectionBuilder.forSessionBus().withShared(false).build() },
) : AutoCloseable {
    private sealed interface Request {
        data class Show(val title: String, val body: String) : Request
        data object Hide : Request
    }
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var notification: UInt32? = null

    init {
        scope.launch {
            var bus: DBusConnection? = null
            var service: NotificationService? = null
            try {
                for (request in requests) {
                    try {
                        when (request) {
                            is Request.Show -> {
                                if (service == null) {
                                    val connected = connect()
                                    bus = connected
                                    val remote = connected.getRemoteObject(serviceName, "/org/freedesktop/Notifications", NotificationService::class.java)
                                    remote.GetCapabilities() // also activates a lazily started desktop service
                                    val owner = connected.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java).GetNameOwner(serviceName)
                                    connected.addSigHandler(NotificationService.ActionInvoked::class.java, owner, remote) { signal ->
                                        if (signal.id == notification && signal.action == "default") onClick()
                                    }
                                    service = remote
                                }
                                notification = service.Notify("Silage", notification ?: UInt32(0), "dev.silage.Silage",
                                    request.title, notificationBody(request.body), listOf("default", "Open inbox"),
                                    mapOf("desktop-entry" to Variant("dev.silage.Silage"), "transient" to Variant(true)), 15_000)
                            }
                            Request.Hide -> {
                                notification?.let { service?.CloseNotification(it) }
                                notification = null
                            }
                        }
                    } catch (_: Exception) {
                        notification = null
                        service = null
                        runCatching { bus?.close() }
                        bus = null
                        System.err.println("Silage: desktop notifications unavailable on this session bus")
                    }
                }
            } finally {
                runCatching { notification?.let { service?.CloseNotification(it) } }
                notification = null
                runCatching { bus?.close() }
                scope.cancel()
            }
        }
    }

    fun show(title: String, body: String) { requests.trySend(Request.Show(title, body)) }
    fun hide() { requests.trySend(Request.Hide) }
    override fun close() { requests.close() }
}

/** Server notification text is plain text, even on desktops that support markup. */
internal fun notificationBody(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
