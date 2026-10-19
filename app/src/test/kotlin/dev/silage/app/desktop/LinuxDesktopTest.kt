package dev.silage.app.desktop

import dev.silage.app.playback.hud.MediaKey
import dev.silage.player.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** A private real session bus: no media keys or notifications reach the user's desktop. */
class LinuxDesktopTest {
    private fun withBus(test: (connect: () -> DBusConnection) -> Unit) {
        if (!System.getProperty("os.name").startsWith("Linux")) return
        val daemon = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1").start()
        try {
            val address = daemon.inputStream.bufferedReader().readLine()
            test { DBusConnectionBuilder.forAddress(address).withShared(false).build() }
        } finally { daemon.destroy(); daemon.waitFor() }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!runCatching(condition).getOrDefault(false)) {
            check(System.nanoTime() < deadline) { "Desktop integration did not respond" }
            Thread.sleep(20)
        }
    }

    @Test fun desktopMediaControlsDriveTheActivePlayerAndReleaseTheBusOnClose() = withBus { connect ->
        connect().use { client ->
            val state = MutableStateFlow(PlayerState(12.0, 120.0, loaded = true))
            val keys = CopyOnWriteArrayList<MediaKey>()
            val commands = CopyOnWriteArrayList<List<String>>()
            val name = "org.mpris.MediaPlayer2.silage.test"
            val registry = client.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
            val session = LinuxMediaSession(state, { keys.add(it) }, { commands.add(it.toList()) },
                { if (it == "volume") "60" else "1.0" }, name, connect)
            try {
                await { registry.NameHasOwner(name) }
                val controls = client.getRemoteObject(name, MprisPath, MprisPlayback::class.java)
                val props = client.getRemoteObject(name, MprisPath, Properties::class.java)
                controls.PlayPause(); controls.Next(); controls.Previous(); controls.Stop()
                assertEquals(listOf(MediaKey.PlayPause, MediaKey.Next, MediaKey.Previous, MediaKey.Stop), keys.toList())
                controls.Pause(); controls.Play(); controls.Seek(-10_000_000)
                val metadata = props.GetAll(MprisPlayer).getValue("Metadata").value as Map<*, *>
                val track = (metadata["mpris:trackid"] as Variant<*>).value as DBusPath
                controls.SetPosition(DBusPath("/stale"), 20_000_000)
                controls.SetPosition(track, -1)
                controls.SetPosition(track, 200_000_000)
                controls.SetPosition(track, 30_000_000)
                assertEquals(listOf(listOf("set", "pause", "yes"), listOf("set", "pause", "no"),
                    listOf("seek", "-10.0", "relative+exact"), listOf("seek", "30.0", "absolute+exact")), commands.toList())
                assertEquals("Playing", props.Get<String>(MprisPlayer, "PlaybackStatus"))
                assertEquals(12_000_000L, props.Get<Long>(MprisPlayer, "Position"))
                assertEquals(0.6, props.Get<Double>(MprisPlayer, "Volume"))
                val signals = CopyOnWriteArrayList<Properties.PropertiesChanged>()
                client.addSigHandler(Properties.PropertiesChanged::class.java) { signals.add(it) }.use {
                    state.value = state.value.copy(paused = true)
                    await { signals.any { it.propertiesChanged["PlaybackStatus"]?.value == "Paused" } }
                }
                props.Set(MprisPlayer, "Volume", 0.5)
                assertEquals(listOf("set", "volume", "50.0"), commands.last())
            } finally { session.close() }
            await { !registry.NameHasOwner(name) }
        }
    }

    class Notifications : NotificationService {
        val shown = CopyOnWriteArrayList<List<Any>>()
        val closed = CopyOnWriteArrayList<UInt32>()
        override fun getObjectPath() = "/org/freedesktop/Notifications"
        override fun GetCapabilities() = listOf("actions", "body")
        override fun Notify(app: String, replaces: UInt32, icon: String, title: String, body: String,
            actions: List<String>, hints: Map<String, Variant<*>>, timeout: Int): UInt32 {
            shown.add(listOf(app, replaces, icon, title, body, actions, hints, timeout))
            return UInt32(42)
        }
        override fun CloseNotification(id: UInt32) { closed.add(id) }
    }

    @Test fun nativeNotificationEscapesServerTextOpensInboxAndCleansUp() = withBus { connect ->
        connect().use { server ->
            val service = Notifications()
            server.exportObject(service)
            server.requestBusName("org.freedesktop.Notifications")
            val clicks = AtomicInteger()
            val notifications = LinuxNotifications({ clicks.incrementAndGet(); Unit }, connect = connect)
            try {
                notifications.show("Available", "A & B <new>")
                await { service.shown.size == 1 }
                assertEquals("A &amp; B &lt;new&gt;", service.shown[0][4])
                assertEquals(listOf("default", "Open inbox"), service.shown[0][5])
                server.sendMessage(NotificationService.ActionInvoked(service.objectPath, UInt32(99), "default"))
                server.sendMessage(NotificationService.ActionInvoked(service.objectPath, UInt32(42), "default"))
                await { clicks.get() == 1 }
                notifications.show("Second", "Updated")
                await { service.shown.size == 2 }
                assertEquals(UInt32(42), service.shown[1][1])
            } finally { notifications.close() }
            await { service.closed.contains(UInt32(42)) }
        }
    }
}
