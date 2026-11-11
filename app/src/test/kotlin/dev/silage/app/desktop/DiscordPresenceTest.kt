package dev.silage.app.desktop

import dev.silage.player.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class DiscordPresenceTest {
    private val now = 1_000_000_000L
    private val playing = PlayerState(positionSeconds = 60.0, durationSeconds = 3600.0, paused = false, loaded = true)

    @Test fun filmShowsTitleYearAndTimes() {
        val a = presenceActivity(PresenceInfo("Arrival", "2016"), playing, now)!!
        assertEquals(3, a["type"]!!.jsonPrimitive.int)
        assertEquals("Arrival", a["details"]!!.jsonPrimitive.content)
        assertEquals("2016", a["state"]!!.jsonPrimitive.content)
        val t = a["timestamps"]!!.jsonObject
        assertEquals(now - 60_000, t["start"]!!.jsonPrimitive.long)
        assertEquals(now - 60_000 + 3_600_000, t["end"]!!.jsonPrimitive.long)
        assertEquals(DiscordLargeImage, a["assets"]!!.jsonObject["large_image"]!!.jsonPrimitive.content)
    }

    @Test fun episodeShowsSeriesThenNumbersAndName() {
        val a = presenceActivity(PresenceInfo("Pilot", "Severance · S1 E1", seriesTitle = "Severance"), playing, now)!!
        assertEquals("Severance", a["details"]!!.jsonPrimitive.content)
        assertEquals("S1 E1 · Pilot", a["state"]!!.jsonPrimitive.content)
    }

    @Test fun pausedDropsTimesAndSaysPaused() {
        val a = presenceActivity(PresenceInfo("Arrival", "2016", inParty = true), playing.copy(paused = true), now)!!
        assertNull(a["timestamps"])
        assertEquals("2016 · Watch Party · Paused", a["state"]!!.jsonPrimitive.content)
    }

    @Test fun nothingBeforeTheFileLoads() {
        assertNull(presenceActivity(PresenceInfo("Arrival", "2016"), PlayerState(), now))
    }

    @Test fun unknownDurationHasNoEnd() {
        assertNull(presenceTimes(playing.copy(durationSeconds = 0.0), now)!!.second)
    }

    @Test fun textFitsDiscordLimits() {
        assertEquals(128, discordText("x".repeat(300)).length)
        assertEquals(2, discordText("A").length)
    }

    @Test fun onlySeeksAndStateChangesAreSent() {
        val base = Sent(true, false, now)
        assertTrue(presenceChanged(null, base))
        assertFalse(presenceChanged(base, base.copy(startMs = now + 1_500)))
        assertTrue(presenceChanged(base, base.copy(startMs = now + 30_000)))
        assertTrue(presenceChanged(base, base.copy(paused = true)))
    }

    @Test fun framesAreLittleEndianOpcodeLengthJson() {
        val f = DiscordFrames.handshake("123")
        val b = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(DiscordFrames.HANDSHAKE, b.int)
        val len = b.int
        assertEquals(f.size - 8, len)
        val json = Json.parseToJsonElement(f.copyOfRange(8, f.size).decodeToString()).jsonObject
        assertEquals("123", json["client_id"]!!.jsonPrimitive.content)
        assertEquals(1, json["v"]!!.jsonPrimitive.int)
    }

    @Test fun pipePathsCoverWindowsAndLinuxSandboxes() {
        assertEquals("""\\.\pipe\discord-ipc-0""", discordPipePaths(true).first())
        val linux = discordPipePaths(false, mapOf("XDG_RUNTIME_DIR" to "/run/user/1000"))
        assertEquals("/run/user/1000/discord-ipc-0", linux.first())
        assertTrue("/run/user/1000/app/com.discordapp.Discord/discord-ipc-0" in linux)
        assertTrue("/run/user/1000/snap.discord/discord-ipc-0" in linux)
    }

    @Test fun handshakesSetsTheActivityAndClearsItOnClose() {
        val pipe = FakePipe()
        val presence = DiscordPresence(MutableStateFlow(playing), PresenceInfo("Arrival", "2016"), clientId = "42", connect = { pipe })
        val deadline = System.currentTimeMillis() + 5_000
        while (pipe.frames.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        presence.close()
        presence.awaitClosed()
        assertEquals(3, pipe.frames.size, "handshake, activity, clear")
        assertEquals(DiscordFrames.HANDSHAKE, pipe.frames[0].first)
        val set = pipe.frames[1].second["args"]!!.jsonObject["activity"]!!.jsonObject
        assertEquals("Arrival", set["details"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, pipe.frames[2].second["args"]!!.jsonObject["activity"])
        assertTrue(pipe.closed)
    }

    @Test fun coverArtReplacesTheIconAndKeepsSilageSmall() {
        val poster = "https://media.example.org/local/series/x/poster/w500.abc.webp"
        val a = presenceActivity(PresenceInfo("Arrival", "2016", artworkUrl = poster), playing, now)!!["assets"]!!.jsonObject
        assertEquals(poster, a["large_image"]!!.jsonPrimitive.content)
        assertEquals("Arrival", a["large_text"]!!.jsonPrimitive.content)
        assertEquals(DiscordLargeImage, a["small_image"]!!.jsonPrimitive.content)
    }

    @Test fun unusableCoverArtFallsBackToTheIcon() {
        val a = presenceActivity(PresenceInfo("Arrival", "2016", artworkUrl = "http://192.168.1.5:8080/p.webp"), playing, now)!!["assets"]!!.jsonObject
        assertEquals(DiscordLargeImage, a["large_image"]!!.jsonPrimitive.content)
        assertNull(a["small_image"])
    }

    @Test fun onlyPublicHttpsLinksGoToDiscord() {
        assertNotNull(discordImageUrl("https://media.example.org/p.webp"))
        listOf(
            null, "", "/relative/p.webp", "http://media.example.org/p.webp",
            "https://localhost/p.webp", "https://silo/p.webp", "https://nas.local/p.webp", "https://silo.lan/p.webp",
            "https://10.0.0.2/p.webp", "https://172.20.1.1/p.webp", "https://192.168.1.5/p.webp", "https://127.0.0.1/p.webp",
            "https://100.101.1.1/p.webp", "https://[fd00::1]/p.webp", "https://media.example.org/" + "x".repeat(400),
        ).forEach { assertNull(discordImageUrl(it), "$it") }
    }

    @Test fun mprisShowsAPosterFoundLater() {
        val session = LinuxMediaSession(MutableStateFlow(playing), {}, {}, { null }, connect = { error("no session bus in tests") })
        try {
            assertNull(session.metadata(playing)["mpris:artUrl"])
            session.showArtwork("https://m.example.org/p.webp")
            assertEquals("https://m.example.org/p.webp", session.metadata(playing)["mpris:artUrl"]!!.value)
        } finally { session.close() }
    }

    @Test fun aPosterFoundLaterUpdatesTheStatus() {
        val pipe = FakePipe()
        val poster = "https://media.example.org/poster.webp"
        val presence = DiscordPresence(MutableStateFlow(playing), PresenceInfo("Arrival", "2016"),
            artwork = { kotlinx.coroutines.delay(200); poster }, clientId = "42", connect = { pipe })
        val deadline = System.currentTimeMillis() + 10_000
        fun lastImage() = pipe.frames.lastOrNull()?.second?.get("args")?.jsonObject?.get("activity")
            ?.let { it as? JsonObject }?.get("assets")?.jsonObject?.get("large_image")?.jsonPrimitive?.content
        while (lastImage() != poster && System.currentTimeMillis() < deadline) Thread.sleep(20)
        presence.close()
        presence.awaitClosed()
        assertEquals(DiscordLargeImage, pipe.frames[1].second["args"]!!.jsonObject["activity"]!!.jsonObject["assets"]!!.jsonObject["large_image"]!!.jsonPrimitive.content)
        assertTrue(pipe.frames.any { f -> f.second["args"]?.jsonObject?.get("activity")?.let { it as? JsonObject }
            ?.get("assets")?.jsonObject?.get("large_image")?.jsonPrimitive?.content == poster }, "poster sent once found")
    }

    @Test fun noClientIdNeverConnects() {
        var tried = false
        DiscordPresence(MutableStateFlow(playing), PresenceInfo("Arrival", "2016"), clientId = "", connect = { tried = true; null }).close()
        Thread.sleep(50)
        assertFalse(tried)
    }

    @Test fun discordNotRunningIsQuiet() {
        val presence = DiscordPresence(MutableStateFlow(playing), PresenceInfo("Arrival", "2016"), clientId = "42", connect = { null })
        Thread.sleep(50)
        presence.close()
        presence.awaitClosed()
    }

    /** Answers every frame with an empty DISPATCH frame, as Discord does. */
    private class FakePipe : DiscordPipe {
        val frames = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, JsonObject>>())
        private var pending = ByteArray(0)
        @Volatile var closed = false
        override fun write(bytes: ByteArray) {
            val (op, len) = DiscordFrames.header(bytes.copyOfRange(0, 8))
            frames += op to Json.parseToJsonElement(bytes.copyOfRange(8, 8 + len).decodeToString()).jsonObject
            pending = DiscordFrames.encode(DiscordFrames.FRAME, "{}")
        }
        override fun readFully(n: Int): ByteArray = pending.copyOfRange(0, n).also { pending = pending.copyOfRange(n, pending.size) }
        override fun close() { closed = true }
    }
}
