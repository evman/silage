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
        // Profiles 5, 7 and 8 as with the helper; the renderer does the Dolby processing on any GPU.
        assertTrue(experimentalDvEligible("DP-3", (properties() + ("current-tracks/video/dolby-vision-profile" to "8"))::get))
        for ((key, invalid) in listOf(
            "current-tracks/video/dolby-vision-profile" to "4",
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

    /** Silage looks for the patched KWin's lease object to decide how Dolby Vision is signalled. */
    @Test fun detectsWhetherKWinOffersTheDolbyVisionLease() = withBus { connect ->
        assertFalse(compositorDvAvailable(connect), "no KWin on the bus")
        connect().use { server ->
            server.requestBusName("org.kde.KWin")
            assertFalse(compositorDvAvailable(connect), "stock KWin")
            server.exportObject(Service())
            assertTrue(compositorDvAvailable(connect), "patched KWin")
        }
    }

    /** With the patched KWin there's no helper and no password prompt; developers can still force a path. */
    /**
     * Copy-back decoders (nvdec-copy, vaapi-copy) drop Dolby Vision metadata from decoded frames,
     * so a Profile 7 enhancement layer can't be confirmed and Dolby Vision never starts (The Wolf
     * of Wall Street on an RTX 5090). Direct VAAPI and NVDEC keep it; anything else falls back
     * to software. Profiles 5 and 8 keep the usual decoder choice.
     */
    @Test fun profile7UsesOnlyDecodersThatKeepDolbyVisionMetadata() {
        assertEquals("vaapi,nvdec", dvDecoders(profile = 7))
        assertTrue(dvDecoders(7)!!.split(",").none { it.endsWith("-copy") || it.startsWith("auto") })
        assertEquals(null, dvDecoders(profile = 8), "Profiles 5 and 8 keep hardware decoding")
        assertEquals(null, dvDecoders(profile = 5))
        assertEquals(null, dvDecoders(profile = null))
    }

    /**
     * A Dolby Vision title waits on its first frame until KWin has switched the signal and the
     * monitor has had time to resync (a few seconds of black), so the opening isn't missed.
     * It never waits long when the switch doesn't happen.
     */
    @Test fun aDolbyVisionTitleStartsOnceTheMonitorHasSwitched() {
        val start = 10_000L
        assertFalse(dvHoldEnds(now = start + 1_000, heldSince = start, activeSince = null, unavailable = false), "signal not on yet")
        assertFalse(dvHoldEnds(now = start + 2_000 + DV_SETTLE_MS - 1, heldSince = start, activeSince = start + 2_000, unavailable = false), "monitor resyncing")
        assertTrue(dvHoldEnds(now = start + 2_000 + DV_SETTLE_MS, heldSince = start, activeSince = start + 2_000, unavailable = false))
        assertTrue(dvHoldEnds(now = start + 500, heldSince = start, activeSince = null, unavailable = true), "KWin can't do it: play at once")
        assertTrue(dvHoldEnds(now = start + DV_HOLD_MAX_MS, heldSince = start, activeSince = null, unavailable = false), "never waits longer")
        assertTrue(dvHoldEnds(now = start + DV_HOLD_MAX_MS, heldSince = start, activeSince = start + DV_HOLD_MAX_MS - 100, unavailable = false))
        assertTrue(dvShouldHold(profile = 7, startPaused = false))
        assertTrue(dvShouldHold(profile = 8, startPaused = false))
        assertFalse(dvShouldHold(profile = 8, startPaused = true), "a paused restart stays paused")
        assertFalse(dvShouldHold(profile = null, startPaused = false), "not Dolby Vision")
        assertFalse(dvShouldHold(profile = 4, startPaused = false))
    }

    @Test fun choosesTheDolbyVisionPath() {
        assertEquals(DvPath.Compositor, dvPath(enabled = true, backend = null, compositor = true, osName = "Linux"))
        assertEquals(DvPath.Helper, dvPath(enabled = true, backend = null, compositor = false, osName = "Linux"))
        assertEquals(DvPath.None, dvPath(enabled = false, backend = null, compositor = true, osName = "Linux"))
        assertEquals(DvPath.Helper, dvPath(enabled = false, backend = "helper", compositor = true, osName = "Linux"))
        assertEquals(DvPath.Compositor, dvPath(enabled = false, backend = "compositor", compositor = false, osName = "Linux"))
        assertEquals(DvPath.None, dvPath(enabled = true, backend = null, compositor = true, osName = "Windows 11"))
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
                // Playback Info's "Dolby Vision" row says whether it's on, or why not.
                assertEquals("Signal active (via KWin)", session.description())
                assertNotNull(session.activeSinceMs, "when the signal came on, so playback can wait for the monitor")
                props.set(properties() + ("video-target-params/gamma" to "gamma2.2"))
                await { service.released == listOf("token-1") }
                assertEquals("Requires PQ HDR output", session.description())
                assertNull(session.activeSinceMs)
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
                assertTrue(session.unavailable)
            } finally { session.close() }
        }
    }
}
