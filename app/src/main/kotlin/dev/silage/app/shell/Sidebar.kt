package dev.silage.app.shell

import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.nav.Route
import dev.silage.app.ui.Artwork
import dev.silage.app.ui.Silage
import dev.silage.app.ui.isActive

val SidebarRail = 96.dp
private val SidebarOpen = 320.dp

data class SidebarProfile(val name: String, val avatarUrl: String?, val avatarKey: String?)

fun LibraryKind.icon(): ImageVector = when (this) {
    LibraryKind.Movies -> Icons.Outlined.Movie
    LibraryKind.Shows -> Icons.Outlined.Tv
    LibraryKind.Music -> Icons.Outlined.LibraryMusic
    LibraryKind.Other -> Icons.Outlined.VideoLibrary
}

/**
 * The left sidebar: an icon rail that widens with labels while focus or the
 * pointer is inside it, drawn over the page with a dark gradient.
 */
@Composable
fun Sidebar(
    current: Route,
    profile: SidebarProfile?,
    libraries: List<SidebarLibrary>,
    onGo: (Route) -> Unit,
    onProfile: () -> Unit,
    /** Unread notifications, shown as a badge on the bell. */
    unread: Int = 0,
    /** The server offers media requests to this profile. */
    requestsEnabled: Boolean = false,
    /** The server has an in-app inbox. */
    notificationsAvailable: Boolean = true,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    // After choosing a page the sidebar folds away at once, even with the pointer still over it,
    // so it never sits over the page just opened (search results, say); moving off re-arms it.
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(hovered, focused) { if (!hovered && !focused) dismissed = false }
    val open = (hovered || focused) && !dismissed
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val go: (Route) -> Unit = { target -> dismissed = true; focusManager.clearFocus(); onGo(target) }
    val width by animateDpAsState(if (open) SidebarOpen else SidebarRail, tween(180))

    Box(
        Modifier.fillMaxHeight().width(width + if (open) 160.dp else 0.dp)
            .background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = if (open) 0.97f else 0.92f),
                    (width / (width + if (open) 160.dp else 0.dp)).coerceIn(0.01f, 1f) to Color.Black.copy(alpha = if (open) 0.97f else 0.92f),
                    1f to Color.Transparent,
                ),
            )
            .onFocusChanged { focused = it.hasFocus },
    ) {
        // Only the rail itself reacts to the pointer: the soft edge beside it belongs to the page.
        Column(Modifier.width(width).fillMaxHeight().hoverable(hover).padding(vertical = 36.dp, horizontal = 16.dp)) {
            ProfileEntry(profile, open, onProfile)
            Spacer(Modifier.height(28.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Entry(Icons.Outlined.Search, "Search", current == Route.Search, open) { go(Route.Search) }
                Entry(Icons.Outlined.Home, "Home", current == Route.Home, open) { go(Route.Home) }
                Entry(Icons.Outlined.AutoAwesome, "For You", current == Route.ForYou, open) { go(Route.ForYou) }
                Entry(Icons.Outlined.CalendarMonth, "Calendar", current == Route.Calendar, open) { go(Route.Calendar) }
                if (requestsEnabled) Entry(Icons.Outlined.AddCircleOutline, "Requests", current == Route.Requests, open) { go(Route.Requests) }
                if (libraries.isNotEmpty()) Box(Modifier.padding(vertical = 10.dp, horizontal = 12.dp).fillMaxWidth().height(1.dp).background(Silage.Hairline))
                libraries.filter { it.visible }.forEach { lib ->
                    Entry(lib.kind.icon(), lib.name, current is Route.Library && current.id == lib.id, open) { go(Route.Library(lib.id, lib.name)) }
                }
                Box(Modifier.padding(vertical = 10.dp, horizontal = 12.dp).fillMaxWidth().height(1.dp).background(Silage.Hairline))
                Entry(Icons.Outlined.BookmarkBorder, "Watchlist", current == Route.Watchlist, open) { go(Route.Watchlist) }
                Entry(Icons.Outlined.FavoriteBorder, "Favorites", current == Route.Favorites, open) { go(Route.Favorites) }
                Entry(Icons.Outlined.History, "History", current == Route.History, open) { go(Route.History) }
                Entry(Icons.Outlined.CollectionsBookmark, "Collections", current == Route.Collections || current is Route.Collection, open) { go(Route.Collections) }
            }
            if (notificationsAvailable) {
                Entry(Icons.Outlined.Notifications, if (unread > 0) "Notifications ($unread)" else "Notifications", current == Route.Notifications, open,
                    badge = unread) { go(Route.Notifications) }
                Spacer(Modifier.height(6.dp))
            }
            Entry(Icons.Outlined.Settings, "Settings", current == Route.Settings, open) { go(Route.Settings) }
        }
    }
}

@Composable
private fun Entry(icon: ImageVector, label: String, selected: Boolean, open: Boolean, badge: Int = 0, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    val fg = if (active) Color.Black else if (selected) Color.White else Silage.Text2
    Box(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp))
            .background(if (active) Color.White else Color.Transparent)
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (selected && !active) Box(Modifier.align(Alignment.CenterStart).padding(start = 2.dp).width(4.dp).height(28.dp).clip(CircleShape).background(Silage.Amber))
        Row(Modifier.padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Icon(icon, label, tint = fg, modifier = Modifier.size(26.dp))
                if (badge > 0) Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp).heightIn(min = 18.dp).widthIn(min = 18.dp)
                        .clip(CircleShape).background(Silage.Amber).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(if (badge > 99) "99+" else badge.toString(), color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
            AnimatedVisibility(open, enter = fadeIn(tween(120)), exit = fadeOut(tween(80))) {
                Text(label, color = fg, fontSize = 19.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 20.dp, end = 12.dp))
            }
        }
    }
}

@Composable
private fun ProfileEntry(profile: SidebarProfile?, open: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(if (active) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand).clickable(interaction, indication = null, onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dev.silage.app.ui.ProfileAvatar(profile?.name ?: "", profile?.avatarUrl, Modifier.size(48.dp))
        AnimatedVisibility(open, enter = fadeIn(tween(120)), exit = fadeOut(tween(80))) {
            Text(profile?.name ?: "", color = Silage.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp))
        }
    }
}
