package dev.silage.app.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.browse.Pill
import dev.silage.app.home.ErrorState
import dev.silage.app.ui.Artwork
import dev.silage.app.ui.Silage
import dev.silage.app.ui.focusCard
import dev.silage.app.ui.isActive
import org.koin.core.Koin
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.Season
import org.siloserver.silo.model.catalog.initialSeasonDisplayPlan
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository

/** Movie, episode or series detail. [onPlay] gets a content id and whether to start over. */
@Composable
fun DetailScreen(koin: Koin, contentId: String, onPlay: (String, Boolean) -> Unit) {
    val catalog = remember { koin.get<CatalogRepository>() }
    var item by remember(contentId) { mutableStateOf<ItemDetail?>(null) }
    var error by remember(contentId) { mutableStateOf<String?>(null) }
    var seasons by remember(contentId) { mutableStateOf(emptyList<Season>()) }
    var season by remember(contentId) { mutableStateOf<Int?>(null) }
    var episodes by remember(contentId) { mutableStateOf(emptyList<EpisodeListItem>()) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(contentId, attempt) {
        error = null
        when (val r = catalog.getItemDetail(contentId)) {
            is ApiResult.Success -> item = r.data
            is ApiResult.Error -> error = r.message
            is ApiResult.NetworkError -> error = "Can't reach the server."
        }
        val d = item ?: return@LaunchedEffect
        if (d.type == "series" || d.type == "show") {
            (catalog.getSeasons(contentId) as? ApiResult.Success)?.data?.seasons?.let { list ->
                val plan = list.initialSeasonDisplayPlan(null)
                seasons = plan.seasons
                season = plan.selectedSeasonNumber
            }
        }
    }
    LaunchedEffect(contentId, season) {
        val s = season ?: return@LaunchedEffect
        (catalog.getEpisodes(contentId, s) as? ApiResult.Success)?.data?.episodes?.let { episodes = it }
    }

    val d = item
    when {
        error != null && d == null -> ErrorState(error!!) { attempt++ }
        d == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Silage.Text) }
        else -> DetailContent(d.toDetailModel(), seasons, season, episodes, onSeason = { season = it; episodes = emptyList() }, onPlay = { id, start -> onPlay(id, start) }, contentId = contentId)
    }
}

@Composable
private fun DetailContent(
    m: DetailModel,
    seasons: List<Season>,
    season: Int?,
    episodes: List<EpisodeListItem>,
    onSeason: (Int) -> Unit,
    onPlay: (String, Boolean) -> Unit,
    contentId: String,
) {
    val playFocus = remember { FocusRequester() }
    val next = if (m.isSeries) nextEpisode(episodes) else null
    // A series' Play button appears once its episodes load; focus it then.
    LaunchedEffect(!m.isSeries || next != null) { if (!m.isSeries || next != null) runCatching { playFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Silage.Black)) {
        Artwork(m.backdropUrl, m.backdropThumbhash, "", Modifier.fillMaxWidth().fillMaxHeight(0.85f).align(Alignment.TopEnd), showTitleTile = false)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black, 0.25f to Color.Black.copy(alpha = 0.92f), 0.65f to Color.Black.copy(alpha = 0.25f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 0.82f to Color.Black)))

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Column(Modifier.padding(start = 64.dp, top = 120.dp, end = 64.dp).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (m.logoUrl != null) {
                        Artwork(m.logoUrl, null, m.title, Modifier.height(170.dp).fillMaxWidth(0.7f), contentScale = ContentScale.Fit, showTitleTile = false, alignment = Alignment.CenterStart)
                    } else {
                        Text(m.title, color = Color.White, fontSize = 60.sp, fontWeight = FontWeight.Bold, lineHeight = 64.sp)
                    }
                    if (m.metaLine.isNotBlank()) Text(m.metaLine, color = Silage.Text2, fontSize = 20.sp)
                    m.tagline?.let { Text(it, color = Silage.Text, fontSize = 20.sp, fontStyle = FontStyle.Italic) }
                    m.overview?.let { Text(it, color = Silage.Text, fontSize = 19.sp, lineHeight = 29.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (m.isSeries) {
                            if (next != null) {
                                val resume = next.userData.resumePosition() != null
                                ActionButton(Icons.Filled.PlayArrow, "${if (resume) "Resume" else "Play"} S${next.seasonNumber} E${next.episodeNumber}", Modifier.focusRequester(playFocus)) { onPlay(next.contentId, false) }
                            }
                        } else {
                            ActionButton(Icons.Filled.PlayArrow, m.playLabel, Modifier.focusRequester(playFocus)) { onPlay(contentId, false) }
                            if (m.canPlayFromStart) ActionButton(Icons.Filled.Replay, "Play from beginning") { onPlay(contentId, true) }
                        }
                    }
                }
            }
            if (m.isSeries && seasons.isNotEmpty()) {
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 64.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 56.dp, bottom = 20.dp)) {
                        items(seasons, key = { it.contentId }) { s ->
                            Pill(s.title ?: if (s.seasonNumber == 0) "Specials" else "Season ${s.seasonNumber}", selected = s.seasonNumber == season) { onSeason(s.seasonNumber) }
                        }
                    }
                }
                items(episodes, key = { it.contentId }) { e -> EpisodeRowView(e.toRow()) { onPlay(e.contentId, false) } }
            }
            if (m.cast.isNotEmpty()) {
                item {
                    Text("Cast", color = Silage.Text2, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 64.dp, top = 56.dp, bottom = 18.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 64.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        items(m.cast) { c -> CastView(c) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    Row(
        modifier.height(60.dp).clip(RoundedCornerShape(30.dp))
            .background(if (active) Color.White else Color.White.copy(alpha = 0.14f))
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick)
            .padding(start = 22.dp, end = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (active) Color.Black else Color.White, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = if (active) Color.Black else Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EpisodeRowView(e: EpisodeRow, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.padding(horizontal = 48.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (active) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(320.dp, 180.dp).then(if (active) Modifier.border(3.dp, Color.White, shape) else Modifier).clip(shape)) {
            Artwork(e.stillUrl, e.stillThumbhash, e.title, Modifier.fillMaxSize())
            e.progress?.let { p ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.Black.copy(alpha = 0.55f))) {
                    Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(Silage.Amber))
                }
            }
            if (e.watched) {
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, null, tint = Silage.Amber, modifier = Modifier.size(18.dp))
                }
            }
        }
        Column(Modifier.padding(start = 28.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${e.number}. ${e.title}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            e.runtime?.let { Text(it, color = Silage.Text3, fontSize = 16.sp) }
            e.overview?.let { Text(it, color = Silage.Text2, fontSize = 17.sp, lineHeight = 25.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun CastView(c: CastCard) {
    Column(Modifier.width(140.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(120.dp).clip(CircleShape)) { Artwork(c.photoUrl, c.photoThumbhash, c.name, Modifier.fillMaxSize()) }
        Spacer(Modifier.height(10.dp))
        Text(c.name, color = Silage.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        c.role?.let { Text(it, color = Silage.Text3, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
