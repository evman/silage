package dev.silage.app.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.settings.ProfileUi
import dev.silage.app.ui.Silage
import dev.silage.app.ui.isActive
import org.siloserver.silo.overlays.CardOverlayPrefs
import org.siloserver.silo.overlays.OverlayData
import org.siloserver.silo.overlays.OverlayDef
import org.siloserver.silo.overlays.OverlayIconId
import org.siloserver.silo.overlays.OverlayPosition
import org.siloserver.silo.overlays.OverlayRegistry
import org.siloserver.silo.overlays.PresetId

/** One badge to draw: what it says, its icon and colour, per the profile's choices. */
data class Badge(val text: String, val icon: OverlayIconId?, val accent: String?)

/**
 * The badges for one corner of a poster, in the profile's order. Only badges the profile turned on
 * and whose data the title has are returned, so a title without a rating shows no empty rating badge.
 */
fun badgesAt(position: OverlayPosition, prefs: CardOverlayPrefs, data: OverlayData?): List<Badge> {
    if (data == null) return emptyList()
    return OverlayRegistry.enabled(position, prefs).mapNotNull { def ->
        val text = def.getValue(data)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val cfg = prefs.items[def.id]
        Badge(text, iconFor(def, cfg?.showIcon, prefs.preset, data), cfg?.accentColor ?: def.defaultAccent)
    }
}

private fun iconFor(def: OverlayDef, override: Boolean?, preset: PresetId, data: OverlayData): OverlayIconId? {
    val wanted = override ?: (preset == PresetId.Pill || def.iconOnly)
    if (!wanted && !def.iconOnly) return null
    return def.getIcon?.invoke(data) ?: def.iconId
}

/** `#rrggbb` as a colour; null for anything else. */
fun parseHex(hex: String?): Color? {
    if (hex == null || !Regex("^#[0-9a-fA-F]{6}$").matches(hex)) return null
    return Color(0xFF000000L or hex.substring(1).toLong(16))
}

/** Black or white, whichever reads on [background]. */
internal fun textOn(background: Color): Color =
    if (0.299f * background.red + 0.587f * background.green + 0.114f * background.blue > 0.6f) Color.Black else Color.White

private fun iconVector(id: OverlayIconId): ImageVector? = when (id) {
    OverlayIconId.Star -> Icons.Filled.Star
    OverlayIconId.Clock -> Icons.Outlined.Schedule
    OverlayIconId.Tv, OverlayIconId.Monitor -> Icons.Outlined.Tv
    OverlayIconId.Film -> Icons.Outlined.Movie
    OverlayIconId.Award, OverlayIconId.Ribbon -> Icons.Outlined.EmojiEvents
    OverlayIconId.Subtitles -> Icons.Outlined.Subtitles
    OverlayIconId.Languages -> Icons.Outlined.Language
    OverlayIconId.Building -> Icons.Outlined.Business
    OverlayIconId.Shield -> Icons.Outlined.Info
    OverlayIconId.Layout -> Icons.Outlined.Layers
    OverlayIconId.Volume -> Icons.Outlined.VolumeUp
    OverlayIconId.Calendar -> Icons.Outlined.CalendarMonth
    OverlayIconId.Globe -> Icons.Outlined.Public
    // Brand marks (HDR10, Dolby Vision, Atmos…) have no stock glyph; their text says it.
    else -> null
}

@Composable
private fun BadgeChip(badge: Badge, preset: PresetId) {
    val accent = parseHex(badge.accent)
    val big = preset == PresetId.Pill
    val shape = when (preset) {
        PresetId.Square -> RoundedCornerShape(2.dp)
        PresetId.Pill -> RoundedCornerShape(50)
        PresetId.Minimal -> RoundedCornerShape(0.dp)
        else -> RoundedCornerShape(6.dp)
    }
    val fill: Color = when (preset) {
        PresetId.Minimal -> Color.Transparent
        PresetId.Vibrant -> accent ?: Silage.Amber
        PresetId.Square -> Color.Black.copy(alpha = 0.78f)
        else -> Color.Black.copy(alpha = 0.62f)
    }
    val ink = if (preset == PresetId.Vibrant) textOn(fill) else if (preset == PresetId.Minimal) (accent ?: Color.White) else Color.White
    val tint = if (preset == PresetId.Vibrant || preset == PresetId.Minimal) ink else accent ?: Color.White
    val size = when (preset) { PresetId.Minimal -> 12.sp; PresetId.Pill -> 15.sp; else -> 13.sp }
    val pad = when (preset) { PresetId.Minimal -> PaddingValues(0.dp); PresetId.Pill -> PaddingValues(horizontal = 11.dp, vertical = 5.dp); else -> PaddingValues(horizontal = 7.dp, vertical = 3.dp) }
    Row(
        Modifier.clip(shape).background(fill)
            .then(if (preset == PresetId.Classic) Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), shape) else Modifier)
            .padding(pad),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (big) 6.dp else 4.dp),
    ) {
        badge.icon?.let(::iconVector)?.let { Icon(it, null, tint = tint, modifier = Modifier.size(if (big) 18.dp else 14.dp)) }
        Text(badge.text, color = ink, fontSize = size, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

/**
 * The profile's badges on the corners of a poster. [watchedMarkShown] and [hasProgressBar] keep the
 * top-right and bottom corners clear of the watched tick and the progress bar.
 */
@Composable
fun BadgeLayer(card: CardModel, ui: ProfileUi, watchedMarkShown: Boolean, hasProgressBar: Boolean, modifier: Modifier = Modifier) {
    if (!ui.badgesEnabled || card.badges == null) return
    val prefs = ui.badges
    val corners = remember(prefs, card.badges) { OverlayPosition.entries.associateWith { badgesAt(it, prefs, card.badges) } }
    if (corners.values.all { it.isEmpty() }) return
    Box(modifier.fillMaxSize().padding(8.dp)) {
        for ((position, list) in corners) {
            if (list.isEmpty()) continue
            val bottom = position == OverlayPosition.BottomLeft || position == OverlayPosition.BottomRight
            val right = position == OverlayPosition.TopRight || position == OverlayPosition.BottomRight
            Column(
                Modifier.align(if (bottom) (if (right) Alignment.BottomEnd else Alignment.BottomStart) else (if (right) Alignment.TopEnd else Alignment.TopStart))
                    .padding(top = if (position == OverlayPosition.TopRight && watchedMarkShown) 34.dp else 0.dp, bottom = if (bottom && hasProgressBar) 6.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = if (right) Alignment.End else Alignment.Start,
            ) { list.forEach { BadgeChip(it, prefs.preset) } }
        }
    }
}

/** What a poster's shortcut buttons do; the page's menu does the saving. */
class QuickActionHandlers(
    val toggleFavorite: suspend (id: String, on: Boolean) -> Boolean,
    val toggleWatched: suspend (id: String, on: Boolean) -> Boolean,
)

@Composable
fun QuickActionButton(icon: ImageVector, label: String, on: Boolean, size: Dp = 34.dp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (active) Color.White else Color.Black.copy(alpha = 0.72f))
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand).clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (active) Color.Black else if (on) Silage.Amber else Color.White, modifier = Modifier.size(size * 0.58f)) }
}

/** The favorite and watched shortcut buttons, shown while the poster is hovered or focused. */
@Composable
fun QuickActionRow(
    mode: dev.silage.app.settings.QuickActions, favorite: Boolean, watched: Boolean,
    onFavorite: () -> Unit, onWatched: () -> Unit, modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (mode.showsFavorite) QuickActionButton(if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if (favorite) "Remove from favorites" else "Add to favorites", favorite, onClick = onFavorite)
        if (mode.showsWatched) QuickActionButton(if (watched) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle, if (watched) "Mark as unwatched" else "Mark as watched", watched, onClick = onWatched)
    }
}
