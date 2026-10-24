package dev.silage.app.detail

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.ui.ArrowRow
import dev.silage.app.ui.Artwork
import dev.silage.app.ui.Silage
import dev.silage.app.ui.isActive
import org.siloserver.silo.model.catalog.TrailerRailEntry
import org.siloserver.silo.model.catalog.trailerRailDurationLabel
import org.siloserver.silo.model.catalog.youtubeThumbnailUrl

/** The page mpv opens (through yt-dlp) to play a provider trailer. */
internal fun youtubeWatchUrl(siteKey: String): String =
    "https://www.youtube.com/watch?v=" + java.net.URLEncoder.encode(siteKey, "UTF-8")

/** The line under a card: its kind, then its length when the file's runtime is known. */
internal fun trailerCaption(entry: TrailerRailEntry): String =
    listOfNotNull(org.siloserver.silo.model.catalog.extraKindLabel(entry.kind), trailerRailDurationLabel(entry)).joinToString(" · ")

/**
 * "Trailers & More": provider trailers (YouTube) and the server's own extras, all played in Silage.
 */
@Composable
internal fun TrailerRail(entries: List<TrailerRailEntry>, onPlayLocal: (contentId: String, fileId: Int?) -> Unit, onPlayRemote: (TrailerRailEntry.Remote) -> Unit,
    /** A YouTube trailer's card is pointed at or focused, so its streams can be looked up ahead of a click. */
    onPointRemote: (TrailerRailEntry.Remote) -> Unit = {}) {
    Text("Trailers & More", color = Silage.Text2, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 64.dp, top = 56.dp, bottom = 18.dp))
    ArrowRow(contentPadding = PaddingValues(horizontal = 64.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), arrowOffsetY = 28.dp) {
        items(entries, key = { it.key }) { entry ->
            TrailerCard(entry, onPoint = { if (entry is TrailerRailEntry.Remote) onPointRemote(entry) }) {
                when (entry) {
                    is TrailerRailEntry.Remote -> onPlayRemote(entry)
                    is TrailerRailEntry.Local -> onPlayLocal(entry.extra.contentId, entry.extra.fileId)
                }
            }
        }
    }
}

@Composable
private fun TrailerCard(entry: TrailerRailEntry, onPoint: () -> Unit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    // Only a card that stays pointed at, not every card the pointer passes over.
    androidx.compose.runtime.LaunchedEffect(active) { if (active) { kotlinx.coroutines.delay(300); onPoint() } }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.width(320.dp).hoverable(interaction).pointerHoverIcon(PointerIcon.Hand).clickable(interaction, indication = null, onClick = onClick),
    ) {
        Box(Modifier.size(320.dp, 180.dp).then(if (active) Modifier.border(3.dp, Color.White, shape) else Modifier).clip(shape)) {
            val still = (entry as? TrailerRailEntry.Remote)?.let { youtubeThumbnailUrl(it.video.siteKey) }
            Artwork(still, null, entry.title, Modifier.size(320.dp, 180.dp))
            Box(Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = if (active) 0.85f else 0.6f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(34.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(entry.title, color = if (active) Color.White else Silage.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(trailerCaption(entry), color = Silage.Text3, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
