package dev.silage.app.watchparty

import org.siloserver.silo.common.i18n.text
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.detail.ActionButton
import dev.silage.app.nav.rememberEntryViewModel
import dev.silage.app.ui.Artwork
import dev.silage.app.ui.PrimaryButton
import dev.silage.app.ui.Silage
import dev.silage.app.ui.SilageField
import dev.silage.app.ui.rememberInitialFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.watchtogether.GuestControlPolicy
import org.siloserver.silo.model.watchtogether.RoomMember
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.WatchPartyEndReason
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.viewmodel.WatchPartyConnectionStatus
import org.siloserver.silo.viewmodel.WatchPartyHubViewModel
import org.siloserver.silo.viewmodel.WatchPartyItem
import org.siloserver.silo.viewmodel.WatchPartyLobbyViewModel
import org.siloserver.silo.viewmodel.WatchPartyPickerViewModel
import org.siloserver.silo.watchtogether.WatchPartyAvailability
import org.siloserver.silo.watchtogether.WatchPartyAvailabilityRepository
import org.siloserver.silo.watchtogether.WatchPartyPrimaryAction
import org.siloserver.silo.watchtogether.WatchPartySecondaryAction
import org.siloserver.silo.watchtogether.WatchPartySeatState
import org.siloserver.silo.watchtogether.canRemoveSuggestion
import org.siloserver.silo.watchtogether.roomVoteWinner
import org.siloserver.silo.watchtogether.watchPartyHostingOffered
import org.siloserver.silo.watchtogether.watchPartyInviteUrl
import org.siloserver.silo.watchtogether.watchPartyLobbyBanner
import org.siloserver.silo.watchtogether.watchPartyLobbyHero
import org.siloserver.silo.watchtogether.watchPartyLobbyHint
import org.siloserver.silo.watchtogether.watchPartyPresenceSummary
import org.siloserver.silo.watchtogether.watchPartyPrimaryAction
import org.siloserver.silo.watchtogether.watchPartySeatOrder
import org.siloserver.silo.watchtogether.watchPartySeatState
import org.siloserver.silo.watchtogether.watchPartySeatStatus
import org.siloserver.silo.watchtogether.watchPartySecondaryAction

/** Which title this device last opened the player for, so coming back from it doesn't reopen it. */
class PartyPlayerMemory {
    @Volatile private var opened: Pair<String, Long>? = null

    /** Whether the player should open for this room's title now; true once per title. */
    fun shouldOpen(roomId: String, revision: Long): Boolean {
        if (opened == roomId to revision) return false
        opened = roomId to revision
        return true
    }
}

/** Why a party this device was in stopped, in plain words. */
internal fun partyEndedText(reason: String): String = when (reason) {
    WatchPartyEndReason.HostLeft -> "The host ended the party."
    WatchPartyEndReason.Replaced -> "You joined this party on another device."
    WatchPartyEndReason.ConnectionLost -> "The connection to the party was lost. You can rejoin below."
    WatchPartyEndReason.Unauthorized -> "Sign in again to use Watch Party."
    else -> "That Watch Party has ended."
}

/** Why Watch Party can't be used here, or null when it can. */
internal fun partyUnavailableText(availability: WatchPartyAvailability?): String? = when (availability) {
    is WatchPartyAvailability.Unsupported -> "This server doesn't support Watch Party. Updating the server adds it."
    WatchPartyAvailability.NotAllowed -> "Your account isn't allowed to use Watch Party on this server."
    is WatchPartyAvailability.ProbeFailed -> "Couldn't check Watch Party on this server."
    else -> null
}

/** Host a party, join one with a code or link, or go back to the one you're in. */
@Composable
fun WatchPartyHubScreen(koin: Koin, stage: WatchPartyItem?, invite: org.siloserver.silo.watchtogether.WatchPartyInvite? = null, onLobby: (roomId: String) -> Unit) {
    val vm = rememberEntryViewModel {
        val tokens = koin.get<TokenManager>()
        WatchPartyHubViewModel(
            koin.get(), koin.get(), koin.get(), koin.get(),
            isCurrentServer = { url -> runBlocking(Dispatchers.Default) { tokens.getServerUrl() }.trimEnd('/').equals(url.trimEnd('/'), ignoreCase = true) },
        )
    }
    val state by vm.uiState.collectAsState()
    var code by rememberSaveable { mutableStateOf("") }
    val available = state.availability is WatchPartyAvailability.Available
    state.destination?.let { destination -> LaunchedEffect(destination) { vm.consumeDestination(); onLobby(destination.roomId) } }
    // From a title's page: host with it as soon as the server is known to allow that, once.
    var staged by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(stage, available, state.current) {
        if (stage != null && available && state.current == null && !staged) { staged = true; vm.hostWithItem(stage) }
    }
    val first = rememberInitialFocus(state.availability != null)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 64.dp, top = 72.dp, end = 64.dp, bottom = 64.dp).widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Watch Party", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Text("Watch in sync with friends on this server. Everyone plays from their own device.", color = Silage.Text2, fontSize = 19.sp)
        state.error?.let { PartyMessage(it.text()) }
        state.ended?.takeIf { state.error == null && state.current == null }?.let { PartyMessage(partyEndedText(it.reason)) }
        val problem = partyUnavailableText(state.availability)
        when {
            state.availability == null -> CircularProgressIndicator(color = Silage.Text)
            problem != null -> {
                PartyMessage(problem)
                PrimaryButton("Check again", modifier = Modifier.focusRequester(first).widthIn(max = 320.dp)) { vm.refresh(force = true) }
            }
            state.current != null -> PrimaryButton("Return to the party", modifier = Modifier.focusRequester(first).widthIn(max = 420.dp)) { vm.returnToParty() }
            // A clicked link only opens this page: joining takes a press, so a stray link can't put you in a room.
            invite != null -> {
                Text("You've been invited to a Watch Party.", color = Silage.Text, fontSize = 22.sp)
                PrimaryButton("Join the party", enabled = state.busy == null, modifier = Modifier.focusRequester(first).widthIn(max = 420.dp)) { vm.joinInvite(invite) }
            }
            else -> {
                if (state.createRetryable) PrimaryButton("Try again", modifier = Modifier.focusRequester(first).widthIn(max = 420.dp)) { vm.retryCreate() }
                else if (watchPartyHostingOffered(state.availability)) PrimaryButton("Host a party", enabled = state.busy == null, modifier = Modifier.focusRequester(first).widthIn(max = 420.dp)) { vm.host() }
                Spacer(Modifier.height(12.dp))
                SilageField(code, { code = it }, "Join with a code or invitation link", Modifier.widthIn(max = 560.dp), onDone = { if (code.isNotBlank()) vm.join(code) })
                PrimaryButton("Join", enabled = code.isNotBlank() && state.busy == null, modifier = Modifier.widthIn(max = 420.dp)) { vm.join(code) }
                state.recent?.let { recent ->
                    PartyRow(recent.title?.let { "Rejoin $it" } ?: "Rejoin party ${recent.code}", "Your last party") { vm.rejoinRecent() }
                }
            }
        }
    }
}

/** The room before and between titles: what's next, who's here, and the one thing to do now. */
@Composable
fun WatchPartyLobbyScreen(koin: Koin, roomId: String, onPlay: () -> Unit, onExit: () -> Unit) {
    val repository = remember { koin.get<WatchTogetherRepository>() }
    val availability = remember { koin.get<WatchPartyAvailabilityRepository>() }
    val catalog = remember { koin.get<CatalogRepository>() }
    val memory = remember { koin.get<PartyPlayerMemory>() }
    val vm = rememberEntryViewModel { WatchPartyLobbyViewModel(roomId, repository, koin.get(), availability) }
    val picker = rememberEntryViewModel {
        WatchPartyPickerViewModel(repository, availability) { q ->
            when (val r = catalog.browse(query = q, limit = 30)) {
                is ApiResult.Success -> ApiResult.Success(r.data.items)
                is ApiResult.Error -> r
                is ApiResult.NetworkError -> r
            }
        }
    }
    val state by vm.state.collectAsState()
    val destination by vm.destination.collectAsState()
    val pick by picker.state.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(vm) { vm.messages.collect { message = it.text() } }
    LaunchedEffect(picker) { picker.loadRows() }
    val room = state.room
    // Gone: the party ended, or this device left it.
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(room == null) { if (room != null) seen = true else if (seen) onExit() }
    // The room's phase alone opens the player, for everyone, once per title.
    LaunchedEffect(destination, room?.selectionRevision) {
        val r = room
        if (destination is org.siloserver.silo.watchtogether.WatchPartyDestination.Player && r != null && memory.shouldOpen(r.roomId, r.selectionRevision)) onPlay()
    }
    if (room == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Silage.Text) }
        return
    }
    val selection = room.selectedContentId
    // The room only names its title: look up what to show for it.
    var unavailable by remember(selection) { mutableStateOf(false) }
    val selected by produceState<StagedTitle?>(null, selection) {
        value = selection?.takeIf { it.isNotBlank() }?.let { id ->
            val result = catalog.getItemDetail(id)
            // Not found means this profile may not see it; a failed request says nothing either way.
            unavailable = result is ApiResult.Error && (result.code == 403 || result.code == 404)
            (result as? ApiResult.Success)?.data?.let { StagedTitle(it.title, it.posterUrl) }
        }
    }
    val reconnecting = state.connection is WatchPartyConnectionStatus.Reconnecting
    val primary = watchPartyPrimaryAction(room, state.features, state.suggestions, unavailable)
    val secondary = watchPartySecondaryAction(room, primary)
    val hero = watchPartyLobbyHero(room, state.suggestions, unavailable)
    val server by produceState("") { value = koin.get<TokenManager>().getServerUrl() }
    val invite = watchPartyInviteUrl(server, room.invitePath)
    val focus = rememberInitialFocus(true)
    fun choose(item: WatchPartyItem) { if (state.eligibility.canStage) vm.stage(item) else if (state.eligibility.canSuggest) vm.suggest(item) }

    Row(Modifier.fillMaxSize().padding(start = 64.dp, top = 92.dp, end = 48.dp, bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(hero.eyebrow.text().uppercase(), color = Silage.Amber, fontSize = 14.sp, letterSpacing = 2.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                selected?.takeIf { !hero.isVoteQuestion }?.let { t -> Artwork(t.posterUrl, null, t.title, Modifier.width(150.dp).height(225.dp).clip(RoundedCornerShape(10.dp))) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(hero.title?.text() ?: selected?.title ?: "…", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 46.sp)
                    hero.supporting?.let { Text(it.text(), color = Silage.Text2, fontSize = 18.sp) }
                }
            }
            watchPartyLobbyBanner(room, unavailable, reconnecting)?.let { PartyMessage(it.text()) }
            message?.let { PartyMessage(it) }
            when (primary) {
                is WatchPartyPrimaryAction.Start -> PrimaryButton(primary.title?.let { "Start $it" } ?: "Start", enabled = state.pending == null, modifier = Modifier.focusRequester(focus).widthIn(max = 420.dp)) {
                    if (room.selectionMode == RoomSelectionMode.Vote) roomVoteWinner(state.suggestions)?.let(vm::promote) else vm.start()
                }
                WatchPartyPrimaryAction.ReturnToPlayback -> PrimaryButton("Return to the video", modifier = Modifier.focusRequester(focus).widthIn(max = 420.dp), onClick = onPlay)
                is WatchPartyPrimaryAction.Ready -> PrimaryButton(if (primary.isReady) "You're ready · tap to undo" else "I'm ready", modifier = Modifier.focusRequester(focus).widthIn(max = 420.dp)) { vm.setLobbyReady(!primary.isReady) }
                WatchPartyPrimaryAction.ChooseTitle -> Text("Search on the right to choose a title.", color = Silage.Text, fontSize = 18.sp)
                WatchPartyPrimaryAction.WaitingForVotes -> Text("Waiting for votes.", color = Silage.Text, fontSize = 18.sp)
                WatchPartyPrimaryAction.None -> Unit
            }
            if (secondary == WatchPartySecondaryAction.ChangeTitle) Text("Choose another title on the right to change it.", color = Silage.Text2, fontSize = 15.sp)
            watchPartyLobbyHint(room, state.suggestions)?.let { Text(it.text(), color = Silage.Text2, fontSize = 15.sp) }

            PartyHeading("Invite")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(room.code, color = Color.White, fontSize = 32.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp)
                ActionButton(Icons.Outlined.ContentCopy, if (invite != null) "Copy link" else "Copy code") {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(invite ?: room.code), null)
                    message = if (invite != null) "Invitation link copied." else "Code copied."
                }
            }

            PartyHeading("Here now · ${watchPartyPresenceSummary(room.members, room.phase)}")
            watchPartySeatOrder(room.members).forEach { MemberSeat(it, room.phase) }

            if (state.eligibility.isHost) {
                PartyHeading("Settings")
                if (state.eligibility.canSwitchMode) PartyRow(
                    if (room.selectionMode == RoomSelectionMode.Vote) "Everyone votes" else "You choose the title", "Change how the title is picked",
                ) { vm.setMode(if (room.selectionMode == RoomSelectionMode.Vote) RoomSelectionMode.HostPick else RoomSelectionMode.Vote) }
                if (state.eligibility.canSetPolicy) PartyRow(
                    if (room.guestControlPolicy == GuestControlPolicy.GuestPlayPause) "Anyone can play and pause" else "Only you can play and pause", "Change who controls playback",
                ) { vm.setGuestPolicy(if (room.guestControlPolicy == GuestControlPolicy.GuestPlayPause) GuestControlPolicy.HostOnly else GuestControlPolicy.GuestPlayPause) }
            }
            Spacer(Modifier.height(4.dp))
            PartyRow("Leave the party", if (state.eligibility.isHost) "You can rejoin; it holds for a short while" else "You can rejoin with the code") { vm.leave() }
            if (state.eligibility.canEnd) PartyRow("End for everyone", "Closes the party") { vm.endForEveryone() }
        }

        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.eligibility.canStage || state.eligibility.canSuggest) {
                SilageField(pick.query, picker::onQuery, if (state.eligibility.canStage) "Find a title to watch" else "Suggest a title")
                pick.error?.let { PartyMessage(it.text()) }
                if (pick.searching) CircularProgressIndicator(Modifier.size(28.dp), color = Silage.Text, strokeWidth = 3.dp)
                pick.results.forEach { item -> TitleRow(item, null) { choose(item.toPartyItem()) } }
            }
            if (state.suggestions.isNotEmpty()) {
                PartyHeading("Suggestions")
                state.suggestions.forEach { s -> SuggestionRow(s, state.eligibility.canVote, canRemoveSuggestion(room, s), state.eligibility.canQueue, { vm.vote(s) }, { vm.remove(s) }, { vm.queue(s) }) }
            }
            if (pick.query.isBlank()) pick.rows?.let { rows ->
                if (rows.continueTogether.isNotEmpty()) {
                    PartyHeading("Continue together")
                    rows.continueTogether.forEach { e -> TitleRow(e.item, e.nextUp?.let { "S${it.seasonNumber} E${it.episodeNumber}" }) { choose(e.item.toPartyItem(e.nextUp?.contentId)) } }
                }
                if (rows.watchlistUnion.isNotEmpty()) {
                    PartyHeading("From everyone's watchlists")
                    rows.watchlistUnion.forEach { e -> TitleRow(e.item, e.nextUp?.let { "S${it.seasonNumber} E${it.episodeNumber}" }) { choose(e.item.toPartyItem(e.nextUp?.contentId)) } }
                }
            }
        }
    }
}

private data class StagedTitle(val title: String, val posterUrl: String?)

/** A row to stage or suggest; a show plays its next episode when the server names one. */
internal fun BrowseItem.toPartyItem(episodeId: String? = null) = WatchPartyItem(
    contentId = episodeId ?: contentId, contentType = if (episodeId != null) "episode" else type, title = title, posterUrl = posterUrl,
)

@Composable private fun PartyHeading(text: String) {
    Text(text.uppercase(), color = Silage.Text2, fontSize = 13.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 12.dp))
}

@Composable private fun PartyMessage(text: String) {
    Text(text, color = Silage.Text, fontSize = 16.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 16.dp, vertical = 10.dp))
}

/** A focusable row, for a remote, controller or mouse. */
@Composable
private fun PartyRow(title: String, detail: String? = null, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = if (focused) 0.18f else if (hovered) 0.10f else 0.05f))
            .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, color = Silage.Text2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun TitleRow(item: BrowseItem, detail: String?, onClick: () -> Unit) {
    PartyRow(item.title, detail ?: item.year.takeIf { it > 0 }?.toString(), leading = {
        Artwork(item.posterUrl, null, item.title, Modifier.width(40.dp).height(60.dp).clip(RoundedCornerShape(6.dp)))
    }, onClick = onClick)
}

@Composable
private fun SuggestionRow(s: Suggestion, canVote: Boolean, canRemove: Boolean, canQueue: Boolean, onVote: () -> Unit, onRemove: () -> Unit, onQueue: () -> Unit) {
    val votes = if (s.voteCount == 1) "1 vote" else "${s.voteCount} votes"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            canQueue -> PartyRow(s.title, "$votes · choose this title", onClick = onQueue)
            canVote -> PartyRow(s.title, if (s.votedByMe) "$votes · you voted, select to undo" else "$votes · select to vote", onClick = onVote)
            else -> PartyRow(s.title, votes) {}
        }
        if (canRemove) PartyRow("Remove ${s.title}", onClick = onRemove)
    }
}

@Composable
private fun MemberSeat(member: RoomMember, phase: RoomPhase) {
    val seat = watchPartySeatState(member, phase)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 3.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(
            when (seat) {
                WatchPartySeatState.Ready, WatchPartySeatState.Watching -> Color(0xFF6FD3A0)
                WatchPartySeatState.Away -> Color(0xFFF08A8A)
                else -> Silage.Amber
            },
        ))
        val role = listOfNotNull("host".takeIf { member.isHost }, "you".takeIf { member.isSelf }).joinToString(", ")
        Text(member.displayName.ifBlank { "Someone" } + if (role.isNotEmpty()) " ($role)" else "", color = Color.White, fontSize = 17.sp)
        watchPartySeatStatus(seat)?.let { Text(it.text(), color = Silage.Text2, fontSize = 14.sp) }
    }
}

/** The sidebar and title-page entry only show where a party could be hosted or joined. */
@Composable
fun rememberWatchPartyOffered(koin: Koin): Boolean {
    val availability = remember { koin.get<WatchPartyAvailabilityRepository>() }
    val current by availability.availability.collectAsState()
    LaunchedEffect(availability) { availability.refresh() }
    return current is WatchPartyAvailability.Available
}

/** An icon for the entry points. */
val WatchPartyIcon = Icons.Outlined.Groups
