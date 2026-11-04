package dev.silage.app.desktop

import dev.silage.app.downloads.extensionFrom
import dev.silage.app.downloads.offlineSubtitles
import dev.silage.app.downloads.resumeFrom
import dev.silage.app.downloads.safeName
import dev.silage.app.input.KeyboardKey
import dev.silage.app.input.KeyboardNav
import dev.silage.app.input.KeyboardRows
import dev.silage.app.input.typed
import dev.silage.app.playback.LanguagePrefs
import dev.silage.app.playback.SubtitleLookRows
import dev.silage.app.playback.SubtitleModes
import dev.silage.app.playback.SubtitleStyle
import dev.silage.app.playback.TaskbarProgress
import dev.silage.app.playback.autoSkipFor
import dev.silage.app.playback.forShow
import dev.silage.app.playback.hud.AutoSkip
import dev.silage.app.playback.showKey
import dev.silage.app.playback.taskbarProgressState
import dev.silage.app.playback.thumbnailButton
import dev.silage.app.ui.ShowPrefs
import dev.silage.player.PlayerState
import org.siloserver.silo.model.download.OfflineSubtitleFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewDesktopFeaturesTest {
    // On-screen keyboard
    @Test fun keyboardWrapsAndKeepsItsPlaceBetweenRows() {
        val nav = KeyboardNav(row = 1, column = 9)
        assertEquals(0, nav.move(0, 1).column)
        val bottom = KeyboardNav(row = 3, column = 9).move(1, 0)
        assertEquals(KeyboardKey.Done, bottom.key)
        assertEquals(KeyboardRows.lastIndex, KeyboardNav(row = 0).move(-1, 0).row)
    }

    @Test fun keyboardTypes() {
        val q = KeyboardRows[1][0]
        assertEquals("aq", typed("a", q, shifted = false))
        assertEquals("aQ", typed("a", q, shifted = true))
        assertEquals("a ", typed("a", KeyboardKey.Space, false))
        assertEquals("", typed("a", KeyboardKey.Delete, false))
        assertNull(typed("a", KeyboardKey.Done, false))
    }

    @Test fun keyboardHasWhatAnInviteLinkNeeds() {
        val all = KeyboardRows.flatten().filterIsInstance<KeyboardKey.Letter>().flatMap { listOf(it.lower, it.upper) }
        "silo://watch-party?server=https://a.b:80&token=Zq_1%".forEach { assertTrue(it in all, "missing $it") }
    }

    // Invite links
    @Test fun onlyPartyLinksCountAsArguments() {
        val link = "silo://watch-party?server=https%3A%2F%2Fa.example&token=tok"
        assertEquals(link, inviteLinkIn(arrayOf("--flag", link)))
        assertNull(inviteLinkIn(arrayOf("silo://invite?server=https://a&token=t")))
        assertNull(inviteLinkIn(arrayOf("https://a.example/rooms/join?token=t")))
    }

    @Test fun aRunningSilageTakesALinkOnlyWithTheSecret() {
        val file = Files.createTempDirectory("links").resolve("app-link")
        val channel = AppLinkChannel(file)
        val link = "silo://watch-party?server=https%3A%2F%2Fa.example&token=tok"
        assertFalse(channel.forward(link), "nothing is running yet")
        val got = java.util.concurrent.LinkedBlockingQueue<org.siloserver.silo.watchtogether.WatchPartyInvite>()
        channel.listen { got.add(it) }
        assertTrue(channel.forward(link))
        assertEquals("tok", (got.poll(2, java.util.concurrent.TimeUnit.SECONDS) as org.siloserver.silo.watchtogether.WatchPartyInvite.Link).token)
        val port = Files.readString(file).substringBefore(' ')
        Files.writeString(file, "$port wrong-secret")
        assertFalse(channel.forward(link))
        assertNull(got.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    // Per-show preferences
    @Test fun aShowsChoicesGoOnTopOfTheDefaults() {
        val base = LanguagePrefs(audio = "en", subtitleMode = SubtitleModes.Off)
        assertEquals(LanguagePrefs("ja", SubtitleModes.On, "en"), base.forShow(ShowPrefs("ja", SubtitleModes.On, "en")))
        assertEquals(base, base.forShow(ShowPrefs()))
        assertTrue(showKey("a", "s") != showKey("b", "s"), "profiles keep their own")
    }

    @Test fun aShowsAutoSkipLeavesCreditsToSettings() {
        val defaults = AutoSkip(intro = false, recap = false, credits = true)
        assertEquals(defaults, autoSkipFor(defaults, null))
        assertEquals(AutoSkip(true, true, true), autoSkipFor(defaults, true))
        assertEquals(AutoSkip(false, false, false), autoSkipFor(AutoSkip(true, true, false), false))
    }

    // Subtitle look
    @Test fun subtitleLookStepsAndWrapsAndResets() {
        val style = SubtitleStyle()
        val colored = SubtitleLookRows.stepped(style, 2, 1)
        assertTrue(colored.color != style.color)
        assertEquals(style.color, SubtitleLookRows.stepped(colored, 2, -1).color)
        var wrapped = style
        repeat(dev.silage.app.playback.SubtitleColor.entries.size) { wrapped = SubtitleLookRows.stepped(wrapped, 2, 1) }
        assertEquals(style, wrapped)
        assertEquals(SubtitleStyle(), SubtitleLookRows.stepped(colored, 4, 1))
        assertEquals(SubtitleLookRows.Count, SubtitleLookRows.labels(style).size)
    }

    // Downloads
    @Test fun serverIdsCantClimbOutOfTheDownloadsFolder() {
        assertEquals("______etc", safeName("../../etc").let { it })
        assertFalse(safeName("a/b\\c").contains('/') || safeName("a/b\\c").contains('\\'))
    }

    @Test fun downloadFileNamesAndResuming() {
        assertEquals("mkv", extensionFrom("""attachment; filename="Film (2020).MKV""""))
        assertEquals("mp4", extensionFrom("attachment; filename*=UTF-8''film.mp4"))
        assertNull(extensionFrom("attachment; filename=\"../../evil\""))
        assertNull(extensionFrom(null))
        assertEquals(50, resumeFrom(50, 100))
        assertEquals(0, resumeFrom(150, 100))
    }

    @Test fun offlineSubtitlesTurnOnTheWantedLanguage() {
        val saved = listOf(OfflineSubtitleFile("f.srt", "srt", "fr"), OfflineSubtitleFile("e.srt", "srt", "en", forced = true), OfflineSubtitleFile("e2.srt", "srt", "en"))
        assertEquals(listOf(false, false, true), offlineSubtitles(saved, LanguagePrefs(subtitleMode = SubtitleModes.On, subtitle = "en")).map { it.select })
        assertTrue(offlineSubtitles(saved, LanguagePrefs()).none { it.select })
    }

    // Taskbar
    @Test fun taskbarProgressFollowsThePlayer() {
        assertEquals(TaskbarProgress.None, taskbarProgressState(PlayerState()))
        assertEquals(TaskbarProgress.Normal, taskbarProgressState(PlayerState(loaded = true, durationSeconds = 10.0)))
        assertEquals(TaskbarProgress.Paused, taskbarProgressState(PlayerState(loaded = true, durationSeconds = 10.0, paused = true)))
    }

    @Test fun onlyThumbnailClicksCount() {
        assertEquals(2, thumbnailButton((0x1800L shl 16) or 2))
        assertNull(thumbnailButton(2))
    }
}
