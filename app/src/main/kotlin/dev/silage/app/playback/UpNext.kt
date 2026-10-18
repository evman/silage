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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
 * episode, Play now / Back, and (with autoplay on) a countdown that plays it.
 */
@Composable
fun UpNextScreen(
    next: EpisodeListItem,
    seriesTitle: String?,
    autoplay: Boolean,
    onPlay: () -> Unit,
    onBack: () -> Unit,
) {
    var remaining by remember(next.contentId) { mutableStateOf(UpNextCountdownSeconds) }
    val currentOnPlay by rememberUpdatedState(onPlay)
    if (autoplay) {
        LaunchedEffect(next.contentId) {
            while (remaining > 0) { delay(1_000); remaining-- }
            currentOnPlay()
        }
    }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && (e.key == Key.Escape || e.key == Key.Backspace)) { onBack(); true } else false
            },
    ) {
        Artwork(next.stillUrl, next.stillThumbhash, "", Modifier.fillMaxWidth(0.62f).fillMaxHeight().align(Alignment.CenterEnd), showTitleTile = false)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black, 0.40f to Color.Black, 0.75f to Color.Black.copy(alpha = 0.35f), 1f to Color.Transparent)))
        Column(Modifier.align(Alignment.CenterStart).padding(start = 96.dp).widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("UP NEXT", color = Silage.Amber, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
            seriesTitle?.let { Text(it, color = Silage.Text2, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(
                "S${next.seasonNumber} · E${next.episodeNumber}  ${next.title?.takeIf { it.isNotBlank() } ?: "Episode ${next.episodeNumber}"}",
                color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            next.overview?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Silage.Text, fontSize = 19.sp, lineHeight = 28.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                UpNextButton(if (autoplay) "Play now ($remaining)" else "Play now", primary = true, Modifier.focusRequester(playFocus), onPlay)
                UpNextButton("Back", primary = false, onClick = onBack)
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
