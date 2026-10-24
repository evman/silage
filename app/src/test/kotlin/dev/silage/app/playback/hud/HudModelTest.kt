package dev.silage.app.playback.hud

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HudModelTest {
    @Test fun timesUseHoursOnlyWhenNeeded() {
        assertEquals("0:00", formatTime(0.0))
        assertEquals("0:07", formatTime(7.9))
        assertEquals("12:34", formatTime(754.0))
        assertEquals("1:45:00", formatTime(6300.0))
        assertEquals("0:00", formatTime(-3.0))
        assertEquals("0:00", formatTime(Double.NaN))
    }

    @Test fun hudHidesThreeSecondsAfterTheLastActivity() {
        val v = HudVisibility(hideAfterMs = 3_000)
        v.activity(now = 1_000)
        assertTrue(v.visible(now = 3_999, paused = false, menuOpen = false))
        assertFalse(v.visible(now = 4_000, paused = false, menuOpen = false))
    }

    @Test fun hudStaysWhilePausedOrAMenuIsOpen() {
        val v = HudVisibility(hideAfterMs = 3_000)
        v.activity(now = 0)
        assertTrue(v.visible(now = 60_000, paused = true, menuOpen = false))
        assertTrue(v.visible(now = 60_000, paused = false, menuOpen = true))
    }

    @Test fun hudStartsVisible() {
        assertTrue(HudVisibility(hideAfterMs = 3_000, shownAt = 0).visible(now = 100, paused = false, menuOpen = false))
    }

    private val trackList = """
        [{"id":1,"type":"video","codec":"hevc","selected":true},
         {"id":1,"type":"audio","lang":"eng","codec":"truehd","demux-channel-count":8,"default":true,"selected":true},
         {"id":2,"type":"audio","lang":"jpn","title":"Commentary","codec":"ac3","demux-channel-count":2},
         {"id":1,"type":"sub","lang":"eng","codec":"subrip","forced":true},
         {"id":2,"type":"sub","codec":"hdmv_pgs_subtitle","external":true,"selected":true}]
    """.trimIndent()

    @Test fun tracksParseFromMpvTrackList() {
        val t = Tracks.parse(trackList)
        assertEquals(listOf(1, 2), t.audio.map { it.id })
        assertEquals(listOf(1, 2), t.subtitles.map { it.id })
        assertEquals(1, t.audio.single { it.selected }.id)
        assertEquals(2, t.subtitles.single { it.selected }.id)
    }

    @Test fun trackLabelsArePlainLanguage() {
        val t = Tracks.parse(trackList)
        assertEquals("English · TrueHD 7.1", t.audio[0].label)
        assertEquals("Commentary · Japanese · AC3 Stereo", t.audio[1].label)
        assertEquals("English · Forced", t.subtitles[0].label)
        assertEquals("Track 2 · PGS · External", t.subtitles[1].label)
    }

    @Test fun languageIsNotRepeatedWhenTheTitleAlreadyNamesIt() {
        val t = Tracks.parse("""[{"id":3,"type":"sub","lang":"en","title":"English SDH","codec":"webvtt","external":true}]""")
        assertEquals("English SDH · External", t.subtitles[0].label)
    }

    @Test fun anExternalCopyOfAnEmbeddedSubtitleIsHidden() {
        val t = Tracks.parse("""
            [{"id":1,"type":"sub","lang":"eng","codec":"subrip"},
             {"id":2,"type":"sub","lang":"eng","title":"SDH","codec":"subrip"},
             {"id":3,"type":"sub","lang":"en","title":"English","codec":"webvtt","external":true},
             {"id":4,"type":"sub","lang":"en","title":"English SDH","codec":"webvtt","external":true},
             {"id":5,"type":"sub","lang":"fr","title":"French","codec":"webvtt","external":true}]
        """.trimIndent())
        assertEquals(listOf(1, 2, 5), t.subtitles.map { it.id })
    }

    @Test fun repeatedExternalSubtitlesShowOnceAndThePlayingOneStays() {
        val t = Tracks.parse("""
            [{"id":1,"type":"sub","lang":"en","codec":"subrip"},
             {"id":2,"type":"sub","lang":"en","title":"English","codec":"webvtt","external":true,"selected":true},
             {"id":3,"type":"sub","lang":"de","title":"German","codec":"webvtt","external":true},
             {"id":4,"type":"sub","lang":"de","title":"German","codec":"webvtt","external":true}]
        """.trimIndent())
        assertEquals(listOf(1, 2, 3), t.subtitles.map { it.id })
    }

    @Test fun badTrackListIsEmpty() {
        val t = Tracks.parse("{oops")
        assertTrue(t.audio.isEmpty() && t.subtitles.isEmpty())
    }

    @Test fun menuChoicesBecomeMpvCommands() {
        assertContentEquals(arrayOf("set", "aid", "2"), HudCommands.audio(2))
        assertContentEquals(arrayOf("set", "sid", "1"), HudCommands.subtitle(1))
        assertContentEquals(arrayOf("set", "sid", "no"), HudCommands.subtitle(null))
        assertContentEquals(arrayOf("set", "speed", "1.25"), HudCommands.speed(1.25))
        assertContentEquals(arrayOf("seek", "90.500", "absolute+exact"), HudCommands.seekTo(90.5))
    }

    private val intro = org.siloserver.silo.model.catalog.TimeRange(30.0, 95.0)
    private val credits = org.siloserver.silo.model.catalog.TimeRange(2900.0, 3000.0)

    @Test fun skipIntroShowsDuringTheIntroAndJumpsToItsEnd() {
        assertEquals(null, SkipPrompt.at(29.0, intro, credits))
        assertEquals(SkipPrompt("Skip Intro", 95.0), SkipPrompt.at(30.0, intro, credits))
        assertEquals(SkipPrompt("Skip Intro", 95.0), SkipPrompt.at(93.0, intro, credits))
        // Within the last second there's nothing left to skip.
        assertEquals(null, SkipPrompt.at(94.5, intro, credits))
    }

    @Test fun skipCreditsShowsDuringTheCredits() {
        assertEquals(SkipPrompt("Skip Credits", 3000.0, SkipPrompt.Kind.Credits), SkipPrompt.at(2950.0, intro, credits))
        assertEquals(null, SkipPrompt.at(500.0, null, null))
    }

    @Test fun recapsGetTheirOwnSkipButton() {
        val recap = org.siloserver.silo.model.catalog.TimeRange(0.0, 28.0)
        assertEquals(SkipPrompt("Skip Recap", 28.0, SkipPrompt.Kind.Recap), SkipPrompt.at(5.0, intro, credits, recap = recap))
        assertEquals(SkipPrompt.Kind.Intro, SkipPrompt.at(31.0, intro, credits, recap = recap)?.kind)
    }

    @Test fun autoSkipFollowsTheSettingsOncePerMarker() {
        val all = AutoSkip(intro = true, recap = true, credits = true)
        val introPrompt = SkipPrompt("Skip Intro", 95.0, SkipPrompt.Kind.Intro)
        val nextPrompt = SkipPrompt("Next Episode", 3000.0, SkipPrompt.Kind.NextEpisode)
        assertEquals(SkipPrompt.Kind.Intro, autoSkipAction(introPrompt, all, emptySet()))
        assertEquals(null, autoSkipAction(introPrompt, all, setOf(SkipPrompt.Kind.Intro)), "seeking back into the intro lets it play")
        assertEquals(null, autoSkipAction(introPrompt, AutoSkip(recap = true, credits = true), emptySet()))
        assertEquals(SkipPrompt.Kind.NextEpisode, autoSkipAction(nextPrompt, all, emptySet()))
        assertEquals(null, autoSkipAction(nextPrompt, all, setOf(SkipPrompt.Kind.Credits)))
        assertEquals(null, autoSkipAction(nextPrompt, AutoSkip(intro = true), emptySet()))
        assertEquals(null, autoSkipAction(null, all, emptySet()))
    }

    @Test fun creditsOfAnEpisodeWithANextOneOfferTheNextEpisode() {
        assertEquals(SkipPrompt("Next Episode", 3000.0, SkipPrompt.Kind.NextEpisode), SkipPrompt.at(2950.0, intro, credits, hasNextEpisode = true))
        assertEquals(SkipPrompt.Kind.Intro, SkipPrompt.at(40.0, intro, credits, hasNextEpisode = true)?.kind, "the intro is still just skipped")
    }

    @Test fun chaptersParseFromMpvAndTheCurrentOneFollowsPosition() {
        val chapters = Chapter.parse("""[{"title":"Opening","time":0.0},{"time":312.5},{"title":"","time":120.0}]""")
        assertEquals(listOf(Chapter("Opening", 0.0), Chapter("Chapter 3", 120.0), Chapter("Chapter 2", 312.5)), chapters)
        assertEquals(0, Chapter.indexAt(chapters, 10.0))
        assertEquals(1, Chapter.indexAt(chapters, 119.8), "within half a second of a start counts as that chapter")
        assertEquals(2, Chapter.indexAt(chapters, 4000.0))
        assertEquals(emptyList(), Chapter.parse(null))
        assertEquals(emptyList(), Chapter.parse("not json"))
    }

    @Test fun theChaptersButtonIsOnlyAFocusStopWhenThereAreChapters() {
        val without = HudNav(focus = HudFocus.Speed)
        assertEquals(HudFocus.Info, without.on(HudKey.Right).first.focus)
        val with = HudNav(focus = HudFocus.Speed, hasChapters = true)
        assertEquals(HudFocus.Chapters, with.on(HudKey.Right).first.focus)
        assertEquals(HudEffect.Activate(HudFocus.Chapters), with.copy(focus = HudFocus.Chapters).on(HudKey.Enter).second)
    }

    @Test fun theQualityButtonIsAFocusStopOnlyWhenTheServerOffersQualities() {
        assertEquals(HudFocus.Info, HudNav(focus = HudFocus.Speed).on(HudKey.Right).first.focus)
        val with = HudNav(focus = HudFocus.Speed, hasQuality = true)
        assertEquals(HudFocus.Quality, with.on(HudKey.Right).first.focus)
        assertEquals(HudEffect.Activate(HudFocus.Quality), with.copy(focus = HudFocus.Quality).on(HudKey.Enter).second)
        // From Quality the next stop is Chapters when there are chapters, else Info.
        assertEquals(HudFocus.Chapters, with.copy(focus = HudFocus.Quality, hasChapters = true).on(HudKey.Right).first.focus)
        assertEquals(HudFocus.Info, with.copy(focus = HudFocus.Quality).on(HudKey.Right).first.focus)
    }
}
