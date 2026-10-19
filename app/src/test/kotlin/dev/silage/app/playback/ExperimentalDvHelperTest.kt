package dev.silage.app.playback

import dev.silage.player.PlayerState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ExperimentalDvHelperTest {
    private fun properties() = mapOf(
        "current-tracks/video/dolby-vision-profile" to "5",
        "video-target-params/gamma" to "pq", "video-target-params/primaries" to "bt.2020",
        "fullscreen" to "yes", "display-names" to "DP-3",
        "vo-passes" to """{"fresh":[{"desc":"color decoding, reshaping"}]}""",
    )

    private class Connection : DvHelperConnection {
        val requests = CopyOnWriteArrayList<String>()
        val authenticated = CompletableDeferred<Unit>()
        val renewals = AtomicInteger()
        @Volatile var closed = false
        override suspend fun ready() { authenticated.await() }
        override suspend fun enable(screen: String) { check(!closed); requests.add("enable $screen") }
        override fun renew() { check(!closed); renewals.incrementAndGet() }
        override suspend fun release() { requests.add("release") }
        override fun close() { closed = true }
    }

    private fun await(message: String = "Helper lifecycle did not respond", condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), message)
    }

    @Test fun rendererChangesAndEndOfFileRestoreWithoutReauthorizing() {
        val connection = Connection().apply { authenticated.complete(Unit) }
        val state = MutableStateFlow(PlayerState(loaded = true))
        val props = AtomicReference(properties())
        val session = ExperimentalDvHelper("DP-3", state, { props.get()[it] }, { connection }, pollMs = 20)
        try {
            await { session.status.value == DvHelperStatus.Active && connection.renewals.get() >= 2 }
            props.set(properties() + ("fullscreen" to "no"))
            await { connection.requests == listOf("enable DP-3", "release") }
            props.set(properties())
            await { connection.requests == listOf("enable DP-3", "release", "enable DP-3") }
            state.value = PlayerState(loaded = false)
            await { connection.requests == listOf("enable DP-3", "release", "enable DP-3", "release") }
        } finally { session.close() }
        assertTrue(connection.closed)
        assertEquals(DvHelperStatus.Closed, session.status.value)
    }

    @Test fun rendererIsRecheckedAfterSlowAuthorization() {
        val connection = Connection()
        val state = MutableStateFlow(PlayerState(loaded = true))
        val props = AtomicReference(properties())
        val session = ExperimentalDvHelper("DP-3", state, { props.get()[it] }, { connection }, pollMs = 20)
        try {
            await { session.status.value == DvHelperStatus.Authorizing }
            props.set(properties() + ("current-tracks/video/dolby-vision-profile" to "9"))
            connection.authenticated.complete(Unit)
            await { connection.renewals.get() >= 2 }
            assertTrue(connection.requests.isEmpty())
            assertEquals(DvHelperStatus.Idle, session.status.value)
        } finally { session.close() }
    }

    @Test fun startupAuthorizesEvenBeforeSupportedContentWithoutEnablingASignal() {
        val connections = AtomicInteger()
        val connection = Connection().apply { authenticated.complete(Unit) }
        val props = properties() + ("current-tracks/video/dolby-vision-profile" to "9")
        val session = ExperimentalDvHelper("DP-3", MutableStateFlow(PlayerState(loaded = true)), props::get,
            { connections.incrementAndGet(); connection }, pollMs = 10)
        try {
            await { connection.renewals.get() >= 3 }
            assertEquals(1, connections.get())
            assertTrue(connection.requests.isEmpty())
            assertEquals(DvHelperStatus.Idle, session.status.value)
        } finally { session.close() }
    }

    @Test fun startupAuthorizationAndIdleHeartbeatsAreReusedAcrossMovies() {
        val connections = AtomicInteger()
        val connection = Connection().apply { authenticated.complete(Unit) }
        val owner = DvHelperAuthorization({ connections.incrementAndGet(); connection }, pollMs = 10)
        try {
            await { connection.renewals.get() >= 3 }
            assertTrue(connection.requests.isEmpty(), "Browsing must never activate the signal")
            repeat(2) { movie ->
                val session = ExperimentalDvHelper("DP-3", MutableStateFlow(PlayerState(loaded = true)), properties()::get,
                    authorization = owner)
                try {
                    await { session.status.value == DvHelperStatus.Active }
                    assertEquals(List(movie + 1) { listOf("enable DP-3", "release") }.flatten().dropLast(1), connection.requests)
                } finally { session.close() }
                assertEquals(DvHelperStatus.Closed, session.status.value)
                assertEquals("release", connection.requests.last())
                assertFalse(connection.closed, "Ending a movie must preserve app authorization")
                val before = connection.renewals.get()
                await { connection.renewals.get() > before + 2 }
            }
            assertEquals(1, connections.get())
        } finally { owner.close() }
        assertTrue(connection.closed)
    }

    @Test fun canceledStartupAuthorizationDoesNotPromptAgainWhenMoviesOpen() {
        val attempts = AtomicInteger()
        val owner = DvHelperAuthorization({ attempts.incrementAndGet(); error("Authorization dismissed") }, pollMs = 10)
        try {
            await { owner.status.value == DvHelperStatus.Unavailable }
            repeat(2) {
                ExperimentalDvHelper("DP-3", MutableStateFlow(PlayerState(loaded = true)), properties()::get,
                    authorization = owner).use { session ->
                    assertEquals(DvHelperStatus.Unavailable, session.status.value)
                }
            }
            assertEquals(1, attempts.get())
        } finally { owner.close() }
    }

    @Test fun profile8RequiresActualRpuProcessingAndOneFullscreenHdrOutput() {
        val props = properties() + ("current-tracks/video/dolby-vision-profile" to "8")
        assertTrue(experimentalDvEligible("DP-3", props::get, DvHelperProfiles))
        assertFalse(experimentalDvEligible("DP-3", props::get), "The separate compositor prototype remains Profile 5 only")
        for ((key, invalid) in listOf(
            "vo-passes" to """{"fresh":[{"desc":"color decoding"}]}""",
            "display-names" to "DP-1,DP-2,DP-3",
            "video-target-params/gamma" to "gamma2.2",
            "fullscreen" to "no",
        )) assertFalse(experimentalDvEligible("DP-3", (props + (key to invalid))::get, DvHelperProfiles), key)
    }

    @Test fun profile8SessionStartsAndReleasesWhenItsMetadataDisappears() {
        val connection = Connection().apply { authenticated.complete(Unit) }
        val props = AtomicReference(properties() + ("current-tracks/video/dolby-vision-profile" to "8"))
        val session = ExperimentalDvHelper("DP-3", MutableStateFlow(PlayerState(loaded = true)), { props.get()[it] },
            { connection }, pollMs = 20)
        try {
            await { session.status.value == DvHelperStatus.Active }
            assertEquals("Signal active (experimental)", session.description())
            props.set(props.get() + ("vo-passes" to """{"fresh":[{"desc":"color decoding"}]}"""))
            await { session.status.value == DvHelperStatus.Idle }
            assertEquals(listOf("enable DP-3", "release"), connection.requests)
            assertEquals("DV metadata is not being processed", session.description())
        } finally { session.close() }
    }

    @Test fun profile7RequiresEnhancementLayerProcessingAndDoesNotTrustStaleRedrawPasses() {
        val base = properties() + ("current-tracks/video/dolby-vision-profile" to "7")
        assertFalse(experimentalDvEligible("DP-3", base::get, DvHelperProfiles))
        assertEquals("Profile 7 enhancement layer not active · using HDR10",
            experimentalDvIneligibleReason("DP-3", base::get, DvHelperProfiles))
        val paired = base + ("vo-passes" to """{"fresh":[{"desc":"color decoding, reshaping, enhancement layer"}]}""")
        assertTrue(experimentalDvEligible("DP-3", paired::get, DvHelperProfiles))
        val stale = base + ("vo-passes" to """{"fresh":[{"desc":"color decoding"}],"redraw":[{"desc":"reshaping, enhancement layer"}]}""")
        assertFalse(experimentalDvEligible("DP-3", stale::get, DvHelperProfiles))
    }

    @Test fun profile7MelUsesDecodedResidualStatusInsteadOfRequiringAnUnusedGpuPass() {
        val mel = properties() + mapOf(
            "current-tracks/video/dolby-vision-profile" to "7",
            "video-out-params/dolby-vision-residual" to "not-required",
        )
        assertTrue(experimentalDvEligible("DP-3", mel::get, DvHelperProfiles))
        val fel = mel + ("video-out-params/dolby-vision-residual" to "required")
        assertFalse(experimentalDvEligible("DP-3", fel::get, DvHelperProfiles))
        val stripped = mel + ("vo-passes" to """{"fresh":[{"desc":"color decoding"}]}""")
        assertFalse(experimentalDvEligible("DP-3", stripped::get, DvHelperProfiles))
    }

    @Test fun declinedAuthorizationLeavesPlaybackStateIntact() {
        val expected = PlayerState(loaded = true, positionSeconds = 42.0)
        val state = MutableStateFlow(expected)
        val session = ExperimentalDvHelper("DP-3", state, properties()::get,
            { error("Authorization dismissed") }, pollMs = 10)
        try {
            await { session.status.value == DvHelperStatus.Unavailable }
            assertEquals(expected, state.value)
        } finally { session.close() }
    }

    @Test fun closeDuringProcessLaunchClosesLateConnection() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val connection = Connection()
        val session = ExperimentalDvHelper("DP-3", MutableStateFlow(PlayerState(loaded = true)), properties()::get,
            connect = { entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)); connection }, pollMs = 10)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            session.close()
        } finally { finish.countDown(); session.close() }
        await { connection.closed }
        assertTrue(connection.requests.isEmpty())
    }

    @Test fun processProtocolAcknowledgesActivationAndEof() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Linux")) return@runBlocking
        val script = """
            import json, sys
            def emit(event):
                print(json.dumps({'version': 1, 'event': event}), flush=True)
            emit('ready')
            for line in sys.stdin:
                command = line.strip()
                if command.startswith('enable '): emit('active')
                elif command == 'release': emit('restored')
            emit('restored')
        """.trimIndent()
        val process = ProcessBuilder("/usr/bin/python3", "-I", "-c", script).start()
        val connection = DvHelperProcess(process)
        try {
            connection.ready()
            connection.renew()
            connection.enable("DP-3")
            connection.release()
        } finally { connection.close() }
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Closing the client must send EOF to the helper")
        assertEquals(0, process.exitValue())
    }
}
