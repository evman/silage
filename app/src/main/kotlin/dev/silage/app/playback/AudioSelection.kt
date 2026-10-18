package dev.silage.app.playback

import dev.silage.app.playback.hud.Track
import org.siloserver.silo.model.catalog.AudioTrack

/** Source indices are FFmpeg indices; the API choice is an audio-list ordinal. */
fun audioMenuTracks(source: List<AudioTrack>, mounted: List<Track>, direct: Boolean, selectedOrdinal: Int?): List<Track> {
    if (direct || source.isEmpty()) return mounted
    return source.mapIndexed { ordinal, track ->
        val label = listOfNotNull(track.title, track.language, track.codec?.uppercase(), track.channels?.let { "${it}ch" })
            .joinToString(" · ").ifBlank { "Track ${ordinal + 1}" }
        Track(ordinal, label, ordinal == selectedOrdinal)
    }
}

fun nativeAudioId(mounted: List<Track>, ordinal: Int): Int? = mounted.getOrNull(ordinal)?.id
