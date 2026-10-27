package dev.silage.app.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import dev.silage.app.nav.BackInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.ui.Artwork
import dev.silage.app.ui.Silage
import dev.silage.app.ui.focusCard
import dev.silage.app.ui.isActive
import kotlinx.coroutines.delay
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.playback.nextEpisodeAfter

/** Seconds "Up next" counts down before playing the next episode by itself. */
const val UpNextCountdownSeconds = 10

/**
 * The episode after the one [meta] describes: later in its season, else the
 * start of the next season. Specials (season 0) never roll into season 1.
 * [episodes] loads one season, or null when it can't.
 */
suspend fun findNextEpisode(
    meta: PlaybackMeta,
    episodes: suspend (seriesId: String, season: Int) -> List<EpisodeListItem>?,
): EpisodeListItem? {
    val series = meta.seriesId ?: return null
    val season = meta.seasonNumber ?: return null
    val episode = meta.episodeNumber ?: return null
    nextEpisodeAfter(episodes(series, season).orEmpty(), season, episode)?.let { return it }
    if (season == 0) return null
    return nextEpisodeAfter(episodes(series, season + 1).orEmpty(), season, episode)
}

/**
 * Shown when an episode plays to its end and another follows: the next
 * episode, Play now / Back and (with autoplay on) a countdown that plays it.
 */
@Composable
fun UpNextScreen(
    next: EpisodeListItem,
    seriesTitle: String?,
    autoplay: Boolean,
    onPlay: () -> Unit,
    onBack: () -> Unit,
) = UpNextLayout(
    countdownKey = next.contentId,
    kicker = seriesTitle,
    title = "S${next.seasonNumber} · E${next.episodeNumber}  ${next.title?.takeIf { it.isNotBlank() } ?: "Episode ${next.episodeNumber}"}",
    overview = next.overview,
    stillUrl = next.stillUrl,
    stillThumbhash = next.stillThumbhash,
    autoplay = autoplay,
    onPlay = onPlay,
    secondary = listOf("Back" to onBack),
    onBack = onBack,
)

/**
 * A shuffle's post-roll: the next random pick with Play now (counting down
 * when autoplay is on), Pick another, and Stop shuffling. Back stops too.
 */
@Composable
fun ShuffleUpNextScreen(
    pick: org.siloserver.silo.playback.ShuffleNextPick,
    scopeLabel: String?,
    autoplay: Boolean,
    onPlay: () -> Unit,
    onPickAnother: () -> Unit,
    onStop: () -> Unit,
) = UpNextLayout(
    countdownKey = pick.contentId,
    heading = "SHUFFLE · UP NEXT",
    kicker = listOfNotNull(scopeLabel, pick.seriesTitle?.takeIf { pick.isEpisode }).distinct().joinToString(" · ").ifBlank { null },
    title = if (pick.isEpisode) "S${pick.seasonNumber} · E${pick.episodeNumber}  ${pick.title}" else pick.title,
    // The overview of an unwatched episode would spoil it.
    overview = pick.overview?.takeIf { !pick.isUnwatched },
    stillUrl = pick.stillUrl,
    stillThumbhash = pick.stillThumbhash,
    autoplay = autoplay,
    onPlay = onPlay,
    secondary = listOf("Pick another" to onPickAnother, "Stop shuffling" to onStop),
    onBack = onStop,
)

@Composable
private fun UpNextLayout(
    countdownKey: String,
    heading: String = "UP NEXT",
    kicker: String?,
    title: String,
    overview: String?,
    stillUrl: String?,
    stillThumbhash: String?,
    autoplay: Boolean,
    onPlay: () -> Unit,
    secondary: List<Pair<String, () -> Unit>>,
    onBack: () -> Unit,
) {
    // A new pick (Pick another) restarts the countdown.
    var remaining by remember(countdownKey) { mutableStateOf(UpNextCountdownSeconds) }
    val currentOnPlay by rememberUpdatedState(onPlay)
    if (autoplay) {
        LaunchedEffect(countdownKey) {
            while (remaining > 0) { delay(1_000); remaining-- }
            currentOnPlay()
        }
    }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (BackInput.isBack(e)) { onBack(); true } else false
            },
    ) {
        Artwork(stillUrl, stillThumbhash, "", Modifier.fillMaxWidth(0.62f).fillMaxHeight().align(Alignment.CenterEnd), showTitleTile = false)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black, 0.40f to Color.Black, 0.75f to Color.Black.copy(alpha = 0.35f), 1f to Color.Transparent)))
        Column(Modifier.align(Alignment.CenterStart).padding(start = 96.dp).widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(heading, color = Silage.Amber, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
            kicker?.let { Text(it, color = Silage.Text2, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(title, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            overview?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Silage.Text, fontSize = 19.sp, lineHeight = 28.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                UpNextButton(if (autoplay) "Play now ($remaining)" else "Play now", primary = true, Modifier.focusRequester(playFocus), onPlay)
                secondary.forEach { (label, action) -> UpNextButton(label, primary = false, onClick = action) }
            }
        }
    }
}

@Composable
private fun UpNextButton(label: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    val shape = RoundedCornerShape(30.dp)
    Box(
        modifier.height(60.dp).focusCard(active, shape).clip(shape)
            .background(if (active || primary) Color.White else Color.White.copy(alpha = 0.14f))
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active || primary) Color.Black else Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}
