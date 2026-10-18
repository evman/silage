package dev.silage.app.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.browse.Pill
import dev.silage.app.ui.Artwork
import dev.silage.app.ui.Silage
import dev.silage.app.ui.isActive
import dev.silage.app.ui.rememberInitialFocus
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.siloserver.silo.model.notifications.NotificationRow
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.resolveArtworkUrl
import org.siloserver.silo.repository.NotificationsRepository

/** The notification inbox: new episodes and fulfilled requests, newest first. */
@Composable
fun NotificationsScreen(koin: Koin, onOpen: (contentId: String) -> Unit) {
    val repo = remember { koin.get<NotificationsRepository>() }
    val rows by repo.rows.collectAsState()
    val unread by repo.unreadCount.collectAsState()
    val error by repo.error.collectAsState()
    val cursor by repo.nextCursor.collectAsState()
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(rows.isNotEmpty()) }
    var server by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        server = runCatching { koin.get<TokenManager>().getServerUrl() }.getOrNull()
        repo.refresh()
        loaded = true
    }
    val list = rememberLazyListState()
    // Older pages load as the end comes into view.
    LaunchedEffect(list, cursor) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            val next = cursor
            if (next != null && last >= rows.size - 4) repo.loadMore(next)
        }
    }
    val first = rememberInitialFocus(rows.isNotEmpty())
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 64.dp, end = 64.dp, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.padding(top = 56.dp, bottom = 16.dp).widthIn(max = 1100.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Notifications", color = Silage.Text, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    Text(if (unread > 0) "$unread unread" else "You're all caught up", color = Silage.Text2, fontSize = 19.sp, modifier = Modifier.padding(top = 6.dp))
                }
                if (unread > 0) Pill("Mark all as read") { scope.launch { repo.markAllRead() } }
            }
        }
        when {
            rows.isEmpty() && !loaded -> item {
                Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Silage.Text) }
            }
            rows.isEmpty() -> item {
                Text(error ?: "Nothing yet. New episodes of shows you follow and requests that arrive will show up here.",
                    color = Silage.Text2, fontSize = 21.sp, modifier = Modifier.padding(top = 24.dp))
            }
            else -> items(rows, key = { it.id }) { row ->
                NotificationRowView(row, server, if (row.id == rows.first().id) first else null) {
                    if (!row.isRead) scope.launch { repo.markRead(row.id) }
                    row.openTarget()?.let(onOpen)
                }
            }
        }
    }
}

@Composable
private fun NotificationRowView(row: NotificationRow, server: String?, focus: FocusRequester?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    val text = remember(row) { row.text() }
    val poster = remember(row, server) {
        row.posterUrl.ifBlank { null }?.let { url -> server?.let { runCatching { resolveArtworkUrl(url, it) }.getOrNull() } ?: url.takeIf { it.startsWith("http") } }
    }
    Row(
        Modifier.widthIn(max = 1100.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .background(if (active) Color.White else Color.White.copy(alpha = if (row.isRead) 0.04f else 0.09f))
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(poster, row.posterThumbhash.ifBlank { null }, text.title, Modifier.width(72.dp).height(108.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.padding(start = 24.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text.title, color = if (active) Color.Black else Silage.Text, fontSize = 21.sp,
                fontWeight = if (row.isRead) FontWeight.Normal else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text.body, color = if (active) Color.Black.copy(alpha = 0.7f) else Silage.Text2, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(relativeTime(row.createdAt), color = if (active) Color.Black.copy(alpha = 0.55f) else Silage.Text3, fontSize = 15.sp)
        }
        if (!row.isRead) Box(Modifier.padding(start = 16.dp).size(12.dp).clip(CircleShape).background(Silage.Amber))
    }
}
