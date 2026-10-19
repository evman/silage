package dev.silage.app.desktop

import dev.silage.app.playback.hud.MediaKey
import dev.silage.player.PlayerState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.TypeRef
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.annotations.DBusProperty.Access
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.errors.NotSupported
import org.freedesktop.dbus.errors.PropertyReadOnly
import org.freedesktop.dbus.errors.UnknownProperty
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

internal const val MprisRoot = "org.mpris.MediaPlayer2"
internal const val MprisPlayer = "org.mpris.MediaPlayer2.Player"
internal const val MprisPath = "/org/mpris/MediaPlayer2"
internal interface MetadataType : TypeRef<Map<String, Variant<*>>>
internal interface StringListType : TypeRef<List<String>>

@DBusInterfaceName(MprisRoot)
@DBusProperty(name = "CanQuit", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanRaise", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "HasTrackList", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "Identity", type = String::class, access = Access.READ)
@DBusProperty(name = "DesktopEntry", type = String::class, access = Access.READ)
@DBusProperty(name = "SupportedUriSchemes", type = StringListType::class, access = Access.READ)
@DBusProperty(name = "SupportedMimeTypes", type = StringListType::class, access = Access.READ)
internal interface MprisApplication : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName(MprisPlayer)
@DBusProperty(name = "PlaybackStatus", type = String::class, access = Access.READ)
@DBusProperty(name = "Rate", type = Double::class)
@DBusProperty(name = "MinimumRate", type = Double::class, access = Access.READ)
@DBusProperty(name = "MaximumRate", type = Double::class, access = Access.READ)
@DBusProperty(name = "Volume", type = Double::class)
@DBusProperty(name = "Position", type = Long::class, access = Access.READ)
@DBusProperty(name = "Metadata", type = MetadataType::class, access = Access.READ)
@DBusProperty(name = "CanGoNext", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanGoPrevious", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanPlay", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanPause", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanSeek", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanControl", type = Boolean::class, access = Access.READ)
internal interface MprisPlayback : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)
    class Seeked(path: String, val position: Long) : DBusSignal(path, position)
}

/** The desktop's media keys, including on Wayland. Registered only while playing. */
internal class LinuxMediaSession(
    private val state: StateFlow<PlayerState>,
    private val onKey: (MediaKey) -> Unit,
    private val command: (Array<out String>) -> Unit,
    private val property: (String) -> String?,
    private val busName: String = "org.mpris.MediaPlayer2.silage.instance${ProcessHandle.current().pid()}",
    connect: () -> DBusConnection = { DBusConnectionBuilder.forSessionBus().withShared(false).build() },
    private val title: String = "Silage",
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var closed = false
    @Volatile private var connection: DBusConnection? = null
    // A unique track per session rejects delayed seek requests from the previous item.
    private val track = DBusPath("/org/mpris/MediaPlayer2/track/t${java.util.UUID.randomUUID().toString().replace("-", "")}")
    private val endpoint = Endpoint()

    init {
        scope.launch {
            var bus: DBusConnection? = null
            try {
                bus = connect()
                synchronized(lock) {
                    if (closed) { bus.close(); return@launch }
                    bus.exportObject(endpoint)
                    bus.requestBusName(busName)
                    connection = bus
                }
                var previous: PlayerState? = null
                state.collect { next ->
                    val changed = mutableMapOf<String, Variant<*>>()
                    if (previous?.paused != next.paused || previous?.loaded != next.loaded)
                        changed["PlaybackStatus"] = Variant(status(next))
                    if (previous?.durationSeconds != next.durationSeconds) changed["Metadata"] = Variant(metadata(next), "a{sv}")
                    if (changed.isNotEmpty()) bus.sendMessage(Properties.PropertiesChanged(MprisPath, MprisPlayer, changed, emptyList()))
                    if (previous?.loaded == true && next.loaded &&
                        kotlin.math.abs(next.positionSeconds - previous!!.positionSeconds) > 1.5)
                        bus.sendMessage(MprisPlayback.Seeked(MprisPath, micros(next.positionSeconds)))
                    previous = next
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { System.err.println("Silage: desktop media controls unavailable on this session bus") }
            finally {
                synchronized(lock) { if (connection === bus) connection = null }
                runCatching { bus?.close() }
            }
        }
    }

    override fun close() {
        synchronized(lock) { closed = true; runCatching { connection?.close() }; connection = null }
        scope.cancel()
    }

    private fun status(s: PlayerState) = if (!s.loaded) "Stopped" else if (s.paused) "Paused" else "Playing"
    private fun metadata(s: PlayerState): Map<String, Variant<*>> = mapOf(
        "mpris:trackid" to Variant(track), "mpris:length" to Variant(micros(s.durationSeconds)),
        "xesam:title" to Variant(title),
    )
    private fun micros(seconds: Double): Long = (seconds.coerceAtLeast(0.0) * 1_000_000).toLong()

    inner class Endpoint : MprisApplication, MprisPlayback, Properties {
        override fun getObjectPath() = MprisPath
        override fun Raise() { javax.swing.SwingUtilities.invokeLater {
            java.awt.Window.getWindows().firstOrNull { it.isShowing && it.type == java.awt.Window.Type.NORMAL }?.let { it.toFront(); it.requestFocus() }
        } }
        override fun Quit() { throw NotSupported("Use Escape in Silage to confirm quitting") }
        override fun Next() = onKey(MediaKey.Next)
        override fun Previous() = onKey(MediaKey.Previous)
        override fun PlayPause() = onKey(MediaKey.PlayPause)
        override fun Stop() = onKey(MediaKey.Stop)
        override fun Pause() { command(arrayOf("set", "pause", "yes")) }
        override fun Play() { command(arrayOf("set", "pause", "no")) }
        override fun Seek(offset: Long) { command(arrayOf("seek", (offset / 1_000_000.0).toString(), "relative+exact")) }
        override fun SetPosition(trackId: DBusPath, position: Long) {
            if (trackId == track && position >= 0 && position <= micros(state.value.durationSeconds))
                command(arrayOf("seek", (position / 1_000_000.0).toString(), "absolute+exact"))
        }
        override fun OpenUri(uri: String) { throw NotSupported("Choose a title in Silage") }
        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
            (GetAll(interfaceName)[propertyName]?.value ?: throw UnknownProperty(propertyName)) as A
        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
            if (interfaceName != MprisPlayer) throw PropertyReadOnly(propertyName)
            val raw = if (value is Variant<*>) value.value else value
            val number = (raw as? Number)?.toDouble()?.takeIf { it.isFinite() }
            when (propertyName) {
                "Volume" -> number?.let { command(arrayOf("set", "volume", (it.coerceIn(0.0, 1.0) * 100).toString())) }
                "Rate" -> number?.takeIf { it in 0.25..4.0 }?.let { command(arrayOf("set", "speed", it.toString())) }
                else -> throw PropertyReadOnly(propertyName)
            }
            // Invalidate so the desktop rereads the actual value accepted by mpv.
            connection?.sendMessage(Properties.PropertiesChanged(MprisPath, MprisPlayer, emptyMap(), listOf(propertyName)))
        }
        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
            MprisRoot -> mapOf(
                "CanQuit" to Variant(false), "CanRaise" to Variant(true), "HasTrackList" to Variant(false),
                "Identity" to Variant("Silage"), "DesktopEntry" to Variant("dev.silage.Silage"),
                "SupportedUriSchemes" to Variant(emptyList<String>(), "as"), "SupportedMimeTypes" to Variant(emptyList<String>(), "as"),
            )
            MprisPlayer -> mapOf(
                "PlaybackStatus" to Variant(status(state.value)), "Metadata" to Variant(metadata(state.value), "a{sv}"),
                "Position" to Variant(micros(state.value.positionSeconds)),
                "Rate" to Variant(property("speed")?.toDoubleOrNull() ?: 1.0),
                "MinimumRate" to Variant(0.25), "MaximumRate" to Variant(4.0),
                "Volume" to Variant((property("volume")?.toDoubleOrNull() ?: 100.0) / 100.0),
            ) + listOf("CanGoNext", "CanGoPrevious", "CanPlay", "CanPause", "CanSeek", "CanControl").associateWith { Variant(true) }
            else -> throw NotSupported(interfaceName)
        }
    }
}
