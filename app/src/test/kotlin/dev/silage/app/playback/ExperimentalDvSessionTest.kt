package dev.silage.app.playback

import dev.silage.player.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class ExperimentalDvSessionTest {
    private fun properties() = mapOf(
        "current-tracks/video/dolby-vision-profile" to "5",
        "video-target-params/gamma" to "pq",
        "video-target-params/primaries" to "bt.2020",
        "fullscreen" to "yes", "display-names" to "DP-3",
        "vo-passes" to """{"fresh":[{"desc":"color decoding, reshaping"}]}""",
    )

    @Test fun onlyValidatedRendererCanRequestTheSignal() {
        assertTrue(experimentalDvEligible("DP-3", properties()::get))
        for ((key, invalid) in listOf(
            "current-tracks/video/dolby-vision-profile" to "8",
            "video-target-params/gamma" to "gamma2.2",
            "video-target-params/primaries" to "bt.709",
            "fullscreen" to "no", "display-names" to "DP-30",
            "vo-passes" to """{"fresh":[{"desc":"color decoding"}]}""",
            "vo-passes" to "broken JSON",
        )) assertFalse(experimentalDvEligible("DP-3", (properties() + (key to invalid))::get), key)
    }

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
            check(System.nanoTime() < deadline) { "Experimental HDR session did not respond" }
            Thread.sleep(20)
        }
    }

    class Service : ExperimentalHdrService {
        val acquisitions = CopyOnWriteArrayList<String>()
        val released = CopyOnWriteArrayList<String>()
        val renewals = AtomicInteger()
        override fun getObjectPath() = ExperimentalHdrPath
        override fun acquire(outputName: String): String {
            acquisitions.add(outputName)
            return "token-${acquisitions.size}"
        }
        override fun renew(token: String): Boolean { renewals.incrementAndGet(); return true }
        override fun release(token: String): Boolean { released.add(token); return true }
    }

    @Test fun playbackRenewsAndReleasesWhenRenderingChangesOrFileEnds() = withBus { connect ->
        connect().use { server ->
            val service = Service()
            server.exportObject(service)
            server.requestBusName("org.kde.KWin")
            val registry = server.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
            val owner = AtomicReference<String>()
            val props = AtomicReference(properties())
            val state = MutableStateFlow(PlayerState(loaded = true))
            val session = ExperimentalDvSession("DP-3", state, { props.get()[it] },
                connect = { connect().also { owner.set(it.uniqueName) } }, pollMs = 20)
            try {
                await { service.acquisitions.size == 1 && service.renewals.get() >= 2 }
                assertEquals("DP-3", service.acquisitions.single())
                props.set(properties() + ("video-target-params/gamma" to "gamma2.2"))
                await { service.released == listOf("token-1") }
                props.set(properties())
                await { service.acquisitions.size == 2 }
                state.value = PlayerState(loaded = false)
                await { service.released == listOf("token-1", "token-2") }
            } finally { session.close() }
            await { !registry.NameHasOwner(owner.get()) }
        }
    }

    @Test fun closingWhileConnectionOpensCannotLeaveAnOutputSession() = withBus { connect ->
        connect().use { server ->
            val service = Service()
            server.exportObject(service)
            server.requestBusName("org.kde.KWin")
            val registry = server.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
            val entered = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val owner = AtomicReference<String>()
            val session = ExperimentalDvSession("DP-3", MutableStateFlow(PlayerState(loaded = true)), properties()::get,
                connect = {
                    entered.countDown()
                    check(finish.await(5, TimeUnit.SECONDS))
                    connect().also { owner.set(it.uniqueName) }
                }, pollMs = 20)
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                session.close()
            } finally { finish.countDown(); session.close() }
            await { owner.get() != null && !registry.NameHasOwner(owner.get()) }
            assertTrue(service.acquisitions.isEmpty())
        }
    }

    @Test fun unavailableCompositorClosesItsConnectionAndKeepsPlayerState() = withBus { connect ->
        connect().use { observer ->
            val registry = observer.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
            val owner = AtomicReference<String>()
            val expected = PlayerState(positionSeconds = 42.0, loaded = true)
            val state = MutableStateFlow(expected)
            val session = ExperimentalDvSession("DP-3", state, properties()::get,
                connect = { connect().also { owner.set(it.uniqueName) } }, pollMs = 20)
            try {
                await { owner.get() != null && !registry.NameHasOwner(owner.get()) }
                assertEquals(expected, state.value)
            } finally { session.close() }
        }
    }
}
