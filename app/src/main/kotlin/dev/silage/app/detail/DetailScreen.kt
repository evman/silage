package dev.silage.app.detail

import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
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
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.input.key.onPreviewKeyEvent
import dev.silage.app.nav.BackInput
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
import dev.silage.app.browse.toCard
import dev.silage.app.home.ErrorState
import dev.silage.app.ui.ArrowRow
import dev.silage.app.ui.Artwork
import dev.silage.app.ui.Silage
import dev.silage.app.ui.focusCard
import dev.silage.app.ui.isActive
import dev.silage.app.ui.rememberInitialFocus
import org.koin.core.Koin
import androidx.compose.runtime.saveable.rememberSaveable
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.Season
import org.siloserver.silo.model.catalog.initialSeasonDisplayPlan
import org.siloserver.silo.model.catalog.trailerRailEntries
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository

/** Movie, episode or series detail. [onPlay] gets a content id, whether to start over, and the chosen copy. */
@Composable
fun DetailScreen(koin: Koin, contentId: String, onPlay: (String, Boolean, Int?) -> Unit, onPerson: (Long, String) -> Unit = { _, _ -> },
    onShuffle: (contentId: String, shuffleId: String) -> Unit = { _, _ -> },
    onTrailer: (dev.silage.app.nav.Trailer) -> Unit = {},
    /** Opens another title's page (from More like this). */
    onOpen: (contentId: String) -> Unit = {},
    /** A show's season to open on, and an episode of it to scroll to and mark ("Go to show"). */
    initialSeason: Int? = null,
    focusEpisode: String? = null) {
    val catalog = remember { koin.get<CatalogRepository>() }
    var item by remember(contentId) { mutableStateOf<ItemDetail?>(null) }
    var error by remember(contentId) { mutableStateOf<String?>(null) }
    var seasons by remember(contentId) { mutableStateOf(emptyList<Season>()) }
    var season by rememberSaveable(contentId) { mutableStateOf(initialSeason) }
    var episodes by remember(contentId) { mutableStateOf(emptyList<EpisodeListItem>()) }
    var attempt by remember { mutableStateOf(0) }
    // Bumped after marking an episode watched, to reload the season's episodes.
    var episodesVersion by remember { mutableStateOf(0) }

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
                // A season asked for (or picked earlier) the show doesn't list falls back to the usual one.
                if (season == null || plan.seasons.none { it.seasonNumber == season }) season = plan.selectedSeasonNumber
            }
        }
    }
    LaunchedEffect(contentId, season, episodesVersion) {
        val s = season ?: return@LaunchedEffect
        (catalog.getEpisodes(contentId, s, fresh = episodesVersion > 0) as? ApiResult.Success)?.data?.episodes?.let { episodes = it }
    }

    val d = item
    val profileUi = dev.silage.app.settings.LocalProfileUi.current
    when {
        error != null && d == null -> ErrorState(error!!) { attempt++ }
        d == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Silage.Text) }
        else -> DetailContent(koin, d.toDetailModel(profileUi.spoilers, profileUi.showAdvisoryAge), d, seasons, season, episodes, onSeason = { season = it; episodes = emptyList() }, onPlay = onPlay, contentId = contentId,
            onPerson = onPerson, onEpisodesChanged = { episodesVersion++ }, onShuffle = onShuffle, onTrailer = onTrailer, onOpen = onOpen, focusEpisode = focusEpisode)
    }
}

@Composable
private fun DetailContent(
    koin: Koin,
    m: DetailModel,
    item: ItemDetail,
    seasons: List<Season>,
    season: Int?,
    episodes: List<EpisodeListItem>,
    onSeason: (Int) -> Unit,
    onPlay: (String, Boolean, Int?) -> Unit,
    contentId: String,
    onPerson: (Long, String) -> Unit,
    onEpisodesChanged: () -> Unit,
    onShuffle: (contentId: String, shuffleId: String) -> Unit,
    onTrailer: (dev.silage.app.nav.Trailer) -> Unit,
    onOpen: (contentId: String) -> Unit,
    focusEpisode: String?,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // "Go to show": once the season's episodes are in, scroll so the episode before it sits at the top.
    var scrolledToEpisode by rememberSaveable(contentId) { mutableStateOf(false) }
    LaunchedEffect(episodes, focusEpisode) {
        if (focusEpisode == null || scrolledToEpisode) return@LaunchedEffect
        val i = episodes.indexOfFirst { it.contentId == focusEpisode }
        if (i < 0) return@LaunchedEffect
        scrolledToEpisode = true
        listState.animateScrollToItem(episodeScrollIndex(i))
    }
    // Titles like this one, as Silo's web client shows them: loaded after the page itself.
    var similar by remember(contentId) { mutableStateOf(emptyList<dev.silage.app.browse.CardModel>()) }
    LaunchedEffect(contentId) {
        if (!showsMoreLikeThis(item.type)) return@LaunchedEffect
        val recommendations = koin.get<org.siloserver.silo.repository.RecommendationRepository>()
        val owner = runCatching { recommendations.captureSimilarAuthority() }.getOrNull() ?: return@LaunchedEffect
        runCatching {
            recommendations.loadSimilarCards(contentId, owner, stillCurrent = { true }) { items -> similar = moreLikeThis(items, contentId) }
        }
    }
    // A YouTube trailer waiting for its helper programs to be downloaded (first time only).
    var settingUpTrailer by remember { mutableStateOf<dev.silage.app.nav.Trailer?>(null) }
    // Only a series shuffles from its page (movies have nothing to pick between).
    val shuffle = if (m.isSeries) dev.silage.app.playback.rememberShuffleStart(koin, org.siloserver.silo.model.shuffle.ShuffleScopeKind.SERIES, contentId, onShuffle) else null
    val actions = remember { ItemActions(koin.get(), koin.get(), koin.get()) }
    val scope = rememberCoroutineScope()
    val watchlist = remember(contentId) { OptimisticToggle(null) { actions.setWatchlist(contentId, it) } }
    val favorite = remember(contentId) { OptimisticToggle(null) { actions.setFavorite(contentId, it) } }
    // Series are marked watched episode by episode; movies and episodes as a whole.
    val watched = remember(contentId) { OptimisticToggle(if (m.isSeries) null else item.userData?.played == true) { actions.setWatched(contentId, it) } }
    LaunchedEffect(contentId) {
        watchlist.load(actions.inWatchlist(contentId))
        favorite.load(actions.isFavorite(contentId))
    }
    // The copy to play (in practice, which library): last played, else best.
    // For a series, the kind picked (e.g. "4K · HDR") carries to other episodes.
    var preferredShort by rememberSaveable(contentId) { mutableStateOf<String?>(null) }
    var preferredFileId by rememberSaveable(contentId) { mutableStateOf<Int?>(null) }
    var picking by remember { mutableStateOf(System.getenv("SILAGE_DEV_OPEN_VERSIONS") == "1") }
    // The info dialog: details, full cast and crew, and the file that would play.
    var showingInfo by remember { mutableStateOf(false) }
    // A "Mark season watched" write in flight.
    var markingSeason by remember { mutableStateOf(false) }
    val next = if (m.isSeries) nextEpisode(episodes) else null
    val options = remember(item, next) {
        (if (m.isSeries) next?.files?.map { it.toOption() } else item.versions.map { it.toOption() }).orEmpty().sortedBest()
    }
    val lastFile = if (m.isSeries) next?.userData?.lastFileId else item.userData?.lastFileId
    // The copy last played wins only under Settings → Video → Preferred version: Last played.
    val preferLast = remember { koin.get<dev.silage.app.ui.UiPrefs>().preferredVersion == "last" }
    val selected = chooseVersion(options, preferredShort, lastFile.takeIf { preferLast }, preferredFileId)
    fun episodeFile(e: EpisodeListItem): Int? = chooseVersion(e.files.map { it.toOption() }, preferredShort, e.userData?.lastFileId?.takeIf { preferLast }, preferredFileId)?.fileId
    // A series' Play button appears once its episodes load; focus it then, once per visit
    // (picking another season keeps focus on the season pills).
    // Not when opened at an episode: focusing Play would scroll back to the top.
    val playFocus = rememberInitialFocus(focusEpisode == null && (!m.isSeries || next != null))

    Box(Modifier.fillMaxSize().background(Silage.Black)) {
        Artwork(m.backdropUrl, m.backdropThumbhash, "", Modifier.fillMaxWidth().fillMaxHeight(0.85f).align(Alignment.TopEnd).then(if (m.blurBackdrop) Modifier.blur(40.dp) else Modifier), showTitleTile = false)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black, 0.25f to Color.Black.copy(alpha = 0.92f), 0.65f to Color.Black.copy(alpha = 0.25f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 0.82f to Color.Black)))

        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
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
                    // The buttons may be wider than the text column above them: squeezed, a chip's label wrapped letter by letter.
                    Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (m.isSeries) {
                            if (next != null) {
                                val resume = next.userData.resumePosition() != null
                                ActionButton(Icons.Filled.PlayArrow, "${if (resume) "Resume" else "Play"} S${next.seasonNumber} E${next.episodeNumber}", Modifier.focusRequester(playFocus)) { onPlay(next.contentId, false, selected?.fileId) }
                            }
                        } else {
                            ActionButton(Icons.Filled.PlayArrow, m.playLabel, Modifier.focusRequester(playFocus)) { onPlay(contentId, false, selected?.fileId) }
                            if (m.canPlayFromStart) ActionButton(Icons.Filled.Replay, "Play from beginning") { onPlay(contentId, true, selected?.fileId) }
                        }
                        if (options.size > 1 && selected != null) ActionButton(Icons.Outlined.Layers, "Versions (${options.size})") { picking = true }
                        shuffle?.let { start -> ActionButton(Icons.Filled.Shuffle, "Shuffle", onClick = start) }
                        ActionButton(Icons.Outlined.Info, "Info") { showingInfo = true }
                        watchlist.value?.let { on ->
                            ToggleChip(if (on) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, if (on) "On your watchlist" else "Add to watchlist", on) { scope.launch { watchlist.toggle() } }
                        }
                        favorite.value?.let { on ->
                            ToggleChip(if (on) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if (on) "Favorite" else "Add to favorites", on) { scope.launch { favorite.toggle() } }
                        }
                        watched.value?.let { on ->
                            ToggleChip(if (on) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle, if (on) "Watched" else "Mark as watched", on) { scope.launch { watched.toggle() } }
                        }
                    }
                    if (options.size == 1 && selected != null) {
                        Text("Available version: ${selected.detail}", color = Silage.Text3, fontSize = 16.sp)
                    }
                    if (watchlist.failed || favorite.failed || watched.failed) {
                        Text("Couldn't save that change on the server. Try again.", color = Silage.Error, fontSize = 17.sp)
                    }
                }
            }
            if (m.isSeries && seasons.isNotEmpty()) {
                item {
                    ArrowRow(contentPadding = PaddingValues(horizontal = 64.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 56.dp, bottom = 20.dp)) {
                        items(seasons, key = { it.contentId }) { s ->
                            Pill(s.title ?: if (s.seasonNumber == 0) "Specials" else "Season ${s.seasonNumber}", selected = s.seasonNumber == season) { onSeason(s.seasonNumber) }
                        }
                        // The whole season at once (the server marks every episode of it).
                        val current = seasons.firstOrNull { it.seasonNumber == season }
                        if (current != null && episodes.isNotEmpty()) item(key = "mark-season") {
                            val allWatched = seasonAllWatched(episodes)
                            Pill(if (markingSeason) "Saving…" else if (allWatched) "Mark season unwatched" else "Mark season watched") {
                                if (!markingSeason) scope.launch {
                                    markingSeason = true
                                    if (actions.setWatched(current.contentId, !allWatched)) onEpisodesChanged()
                                    markingSeason = false
                                }
                            }
                        }
                    }
                }
                items(episodes, key = { it.contentId }) { e ->
                    // Right-click: catch up to this episode (earlier seasons too), or flip just this one.
                    androidx.compose.foundation.ContextMenuArea(items = {
                        val watchedNow = e.userData?.played == true
                        listOf(
                            androidx.compose.foundation.ContextMenuItem("Mark watched up to here") {
                                scope.launch {
                                    val targets = upToHereTargets(seasons, season, episodes, e.contentId)
                                    var changed = false
                                    targets.forEach { id -> if (actions.setWatched(id, true)) changed = true }
                                    if (changed) onEpisodesChanged()
                                }
                            },
                            androidx.compose.foundation.ContextMenuItem(if (watchedNow) "Mark as unwatched" else "Mark as watched") {
                                scope.launch { if (actions.setWatched(e.contentId, !watchedNow)) onEpisodesChanged() }
                            },
                        )
                    }) {
                        EpisodeRowView(e.toRow(dev.silage.app.settings.LocalProfileUi.current.spoilers), highlighted = e.contentId == focusEpisode, onToggleWatched = { now -> actions.setWatched(e.contentId, now).also { if (it) onEpisodesChanged() } }) {
                            onPlay(e.contentId, false, episodeFile(e))
                        }
                    }
                }
            }
            val trailers = trailerRailEntries(item)
            if (trailers.isNotEmpty()) {
                item {
                    TrailerRail(trailers, onPlayLocal = { id, file -> onPlay(id, false, file) },
                        onPlayRemote = { entry ->
                        val trailer = dev.silage.app.nav.Trailer(youtubeWatchUrl(entry.video.siteKey), entry.title)
                        if (dev.silage.app.playback.TrailerTools.ready()) onTrailer(trailer) else settingUpTrailer = trailer
                    })
                }
            }
            if (m.cast.isNotEmpty()) {
                item {
                    Text("Cast", color = Silage.Text2, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 64.dp, top = 56.dp, bottom = 18.dp))
                    ArrowRow(contentPadding = PaddingValues(horizontal = 64.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), arrowOffsetY = 20.dp) {
                        items(m.cast) { c -> CastView(c, onOpen = c.personId?.let { id -> { onPerson(id, c.name) } }) }
                    }
                }
            }
            if (similar.isNotEmpty()) {
                item {
                    Box(Modifier.padding(top = 56.dp)) {
                        dev.silage.app.browse.CardRow("More like this", similar, onOpen = { onOpen(it.openTarget) }, onFocus = {})
                    }
                }
            }
        }
        if (showingInfo) {
            val file = item.versions.firstOrNull { it.fileId == selected?.fileId } ?: item.versions.firstOrNull()
            TitleInfoDialog(m.title, remember(item, file) { titleInfoSections(item, file) }, onDismiss = { showingInfo = false })
        }
        settingUpTrailer?.let { trailer ->
            TrailerSetupDialog(onReady = { settingUpTrailer = null; onTrailer(trailer) }, onCancel = { settingUpTrailer = null })
        }
        if (selected != null) {
            VersionPicker(options, selected.fileId, onPick = {
                preferredFileId = it.fileId; preferredShort = it.short; picking = false
            }, onDismiss = { picking = false }, visible = picking, returnFocus = playFocus)
        }
    }
}

@Composable
internal fun ActionButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    Row(
        modifier.height(60.dp).clip(RoundedCornerShape(30.dp))
            .background(if (active) Color.White else Color.White.copy(alpha = 0.14f))
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand).clickable(interaction, indication = null, onClick = onClick)
            .padding(start = 22.dp, end = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (active) Color.Black else Color.White, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = if (active) Color.Black else Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

/**
 * A round icon button for an on/off setting (watchlist, favorite, watched).
 * Its label slides out while it's hovered or focused; [on] fills it amber-on-white.
 */
@Composable
private fun ToggleChip(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    Row(
        Modifier.height(60.dp).clip(RoundedCornerShape(30.dp))
            .background(if (active) Color.White else Color.White.copy(alpha = 0.14f))
            .semantics { contentDescription = label }
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick)
            .animateContentSize()
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = when { active -> Color.Black; on -> Silage.Amber; else -> Color.White }, modifier = Modifier.size(30.dp))
        if (active) {
            Spacer(Modifier.width(10.dp))
            Text(label, color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, modifier = Modifier.padding(end = 8.dp))
        }
    }
}

@Composable
private fun EpisodeRowView(e: EpisodeRow, highlighted: Boolean = false, onToggleWatched: suspend (Boolean) -> Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.padding(horizontal = 48.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (active) Color.White.copy(alpha = 0.10f) else if (highlighted) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            // The episode "Go to show" came from.
            .then(if (highlighted) Modifier.border(2.dp, Silage.Amber.copy(alpha = 0.8f), RoundedCornerShape(16.dp)) else Modifier)
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(320.dp, 180.dp).then(if (active) Modifier.border(3.dp, Color.White, shape) else Modifier).clip(shape)) {
            Artwork(e.stillUrl, e.stillThumbhash, e.title, Modifier.fillMaxSize().then(if (e.blurStill) Modifier.blur(28.dp) else Modifier))
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
        val watched = remember(e.id, e.watched) { OptimisticToggle(e.watched, onToggleWatched) }
        val scope = rememberCoroutineScope()
        val on = watched.value == true
        Spacer(Modifier.width(16.dp))
        ToggleChip(if (on) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle, if (on) "Mark as unwatched" else "Mark as watched", on) {
            scope.launch { watched.toggle() }
        }
    }
}

/** A cast member; with [onOpen], clicking opens their other titles. */
@Composable
private fun CastView(c: CastCard, onOpen: (() -> Unit)?) {
    val interaction = remember { MutableInteractionSource() }
    val active = onOpen != null && interaction.isActive()
    Column(
        Modifier.width(140.dp).then(
            if (onOpen != null) Modifier.hoverable(interaction).clickable(interaction, indication = null, onClick = onOpen) else Modifier,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(120.dp).focusCard(active, CircleShape).clip(CircleShape)) { Artwork(c.photoUrl, c.photoThumbhash, c.name, Modifier.fillMaxSize()) }
        Spacer(Modifier.height(10.dp))
        Text(c.name, color = Silage.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        c.role?.let { Text(it, color = Silage.Text3, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** The list of copies to choose from, over a dim scrim; Esc or Back closes it. */
@Composable
internal fun VersionPicker(options: List<VersionOption>, selectedId: Int, onPick: (VersionOption) -> Unit, onDismiss: () -> Unit,
    visible: Boolean, returnFocus: FocusRequester) {
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) opened = true
        else if (opened) { returnFocus.requestFocus(); opened = false }
    }
    if (!visible) return
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).onPreviewKeyEvent { e ->
            if (BackInput.isBack(e)) { onDismiss(); true } else false
        },
        contentAlignment = Alignment.Center,
    ) {
        // Clicking the dimmed page behind the list closes it.
        Box(Modifier.fillMaxSize().pointerInput(onDismiss) { detectTapGestures { onDismiss() } })
        Column(
            Modifier.width(820.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xF2171717))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                // A click on the list's own edges must not fall through to the page behind.
                .pointerInput(Unit) { detectTapGestures { } }.padding(vertical = 14.dp),
        ) {
            Text("VERSION", color = Silage.Text3, fontSize = 15.sp, letterSpacing = 2.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp))
            options.forEach { o ->
                val interaction = remember { MutableInteractionSource() }
                val active = interaction.isActive()
                val isSelected = o.fileId == selectedId
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .then(if (isSelected) Modifier.focusRequester(first) else Modifier)
                        .background(if (active) Color.White else if (isSelected) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                        .hoverable(interaction).clickable(interaction, indication = null) { onPick(o) }
                        .padding(horizontal = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (isSelected) Silage.Amber else Color.Transparent))
                    Spacer(Modifier.width(20.dp))
                    Text(o.detail, color = if (active) Color.Black else if (isSelected) Color.White else Silage.Text2, fontSize = 20.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Movies and shows get a More like this row; episodes and other kinds don't. */
internal fun showsMoreLikeThis(type: String?): Boolean = type?.lowercase() in setOf("movie", "series", "show")

/** The server's similar titles as poster cards, without the title whose page this is. */
internal fun moreLikeThis(items: List<org.siloserver.silo.model.catalog.BrowseItem>, contentId: String): List<dev.silage.app.browse.CardModel> =
    items.filter { it.contentId != contentId }.distinctBy { it.contentId }.map { it.toCard() }

/**
 * The page's list position to scroll to for the season's episode [episodeIndex]: the episode before
 * it, so what came before and after shows too; the season buttons for the first episode. The page
 * lists its heading, then the season buttons, then the episodes.
 */
internal fun episodeScrollIndex(episodeIndex: Int): Int = if (episodeIndex <= 0) 1 else 1 + episodeIndex

