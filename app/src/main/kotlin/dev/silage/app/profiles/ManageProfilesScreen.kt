@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.silage.app.profiles

import org.siloserver.silo.common.i18n.text
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.browse.Pill
import dev.silage.app.nav.BackInput
import dev.silage.app.ui.ProfileAvatar
import dev.silage.app.ui.Silage
import dev.silage.app.ui.SilageField
import dev.silage.app.ui.TextLink
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.model.profile.CreateProfileRequest
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.model.profile.hasProfileNamed
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.apiv2.Patch
import org.siloserver.silo.network.apiv2.ProfileUpdate
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.ProfileRepository

/** The content-rating limits Silo's web app offers: each also admits the TV ratings beside it. */
internal val RatingLimits = listOf(null to "No limit", "G" to "G / TV-G", "PG" to "PG / TV-PG", "PG-13" to "PG-13 / TV-14", "R" to "R / TV-MA")

/** The safer limits a new kids profile starts from, to look over before saving. */
internal const val KidsRatingLimit = "PG"

/** What the editor holds for one profile. [pin] is new digits; blank keeps the old PIN. */
internal data class ProfileDraft(
    val name: String = "",
    val pin: String = "",
    val removePin: Boolean = false,
    val isChild: Boolean = false,
    val maxRating: String? = null,
    val restricted: Boolean = false,
    val libraries: Set<Int> = emptySet(),
)

internal fun Profile.toDraft() = ProfileDraft(
    name = name, isChild = isChild, maxRating = maxContentRating,
    restricted = libraryRestrictionsEnabled, libraries = allowedLibraryIds.orEmpty().toSet(),
)

/** Why the draft can't be saved; null when it can. */
internal fun draftProblem(draft: ProfileDraft, others: List<Profile>, id: String?): String? = when {
    draft.name.isBlank() -> "Give the profile a name."
    others.hasProfileNamed(draft.name, id) -> "Another profile already has that name."
    draft.pin.isNotEmpty() && (draft.pin.length != 4 || !draft.pin.all(Char::isDigit)) -> "A PIN is four digits."
    draft.restricted && draft.libraries.isEmpty() -> "Choose at least one library."
    else -> null
}

/** Only what changed, so a save never touches the other settings; a cleared PIN or limit goes as an explicit null. */
internal fun patchFor(before: ProfileDraft, after: ProfileDraft): ProfileUpdate = ProfileUpdate(
    name = if (after.name.trim() != before.name) Patch.Set(after.name.trim()) else Patch.Omit,
    pin = when {
        after.pin.isNotEmpty() -> Patch.Set(after.pin)
        after.removePin -> Patch.Clear
        else -> Patch.Omit
    },
    isChild = if (after.isChild != before.isChild) Patch.Set(after.isChild) else Patch.Omit,
    maxContentRating = when {
        after.maxRating == before.maxRating -> Patch.Omit
        after.maxRating == null -> Patch.Clear
        else -> Patch.Set(after.maxRating)
    },
    libraryRestrictionsEnabled = if (after.restricted != before.restricted) Patch.Set(after.restricted) else Patch.Omit,
    allowedLibraryIds = if (after.restricted && after.libraries != before.libraries) Patch.Set(after.libraries.sorted().map(Int::toString)) else Patch.Omit,
)

private fun ApiResult<*>.problem(): String? = when (this) {
    is ApiResult.Success -> null
    is ApiResult.Error -> message
    is ApiResult.NetworkError -> "Can't reach the server."
}

/**
 * Add, edit and remove the household's profiles. The server lets only the primary
 * profile do this, so it opens with that profile's PIN if it has one.
 */
@Composable
fun ManageProfilesScreen(koin: Koin, onDone: () -> Unit, onOwnProfileDeleted: () -> Unit = onDone) {
    val repository = remember { koin.get<ProfileRepository>() }
    val session = remember { repository.householdManagement }
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf<List<Profile>?>(null) }
    var libraries by remember { mutableStateOf(emptyList<UserLibrary>()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var started by remember { mutableStateOf(false) }
    var pinFor by remember { mutableStateOf<Profile?>(null) }
    var pin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    // Null is the list; an id edits that profile; "" is a new one.
    var editing by remember { mutableStateOf<String?>(null) }
    DisposableEffect(session) { onDispose { session.end() } }

    suspend fun reload() {
        when (val r = repository.listProfiles()) {
            is ApiResult.Success -> { profiles = r.data; problem = null }
            else -> problem = "Couldn't load profiles: ${r.problem()}"
        }
    }
    suspend fun begin(primary: Profile, token: String?) {
        session.begin(primary, token, koin.get<TokenManager>().snapshotCurrentScope())
        started = true
        pinFor = null
    }
    LaunchedEffect(attempt) {
        reload()
        (runCatching { koin.get<PersonalDataRepository>().listUserLibraries() }.getOrNull() as? ApiResult.Success)?.let { libraries = it.data }
        val primary = profiles?.firstOrNull { it.isPrimary } ?: return@LaunchedEffect
        if (primary.hasPin) pinFor = primary else begin(primary, null)
    }
    // Saving a limit can make the held PIN proof stale; the session then asks again.
    val again by session.reverify.collectAsState()
    val active by session.isActive.collectAsState()
    LaunchedEffect(active, started) { if (started && !active) onDone() }

    Box(Modifier.fillMaxSize().background(Silage.Black).onPreviewKeyEvent { e ->
        // Not Backspace: it deletes text in this page's fields.
        if (BackInput.isBack(e) && e.key != androidx.compose.ui.input.key.Key.Backspace && pinFor == null && again == null) { if (editing != null) editing = null else onDone(); true } else false
    }) {
        val askFor = again
        val first = pinFor
        when {
            askFor != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PinPad(ProfileRow(askFor.profile.id, askFor.profile.name, true, askFor.profile.avatarUrl, askFor.profile.avatar), pin, askFor.error?.text(),
                    onDigit = { if (pin.length < 8) pin += it }, onDelete = { pin = pin.dropLast(1) },
                    onSubmit = { session.submitPin(pin); pin = "" }, onCancel = { pin = ""; session.cancelReverify() })
            }
            first != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PinPad(ProfileRow(first.id, first.name, true, first.avatarUrl, first.avatar), pin, pinError,
                    onDigit = { if (pin.length < 8) pin += it }, onDelete = { pin = pin.dropLast(1) },
                    onSubmit = {
                        val entered = pin
                        pin = ""
                        scope.launch {
                            when (val r = repository.verifyPin(first.id, entered)) {
                                is ApiResult.Success -> if (r.data.valid && r.data.profileToken != null) { pinError = null; begin(first, r.data.profileToken) } else pinError = "Wrong PIN."
                                else -> pinError = "Couldn't check the PIN: ${r.problem()}"
                            }
                        }
                    },
                    onCancel = onDone)
            }
            profiles == null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
                if (problem == null) CircularProgressIndicator(color = Silage.Text)
                else { Text(problem!!, color = Silage.Text2, fontSize = 20.sp); TextLink("Retry") { attempt++ }; TextLink("Back", onDone) }
            }
            editing != null -> ProfileEditor(
                profile = profiles!!.firstOrNull { it.id == editing },
                all = profiles!!, libraries = libraries,
                repository = repository,
                onOwnProfileDeleted = onOwnProfileDeleted,
                onClose = { changed -> editing = null; if (changed) scope.launch { reload() } },
            )
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 96.dp, vertical = 64.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Manage profiles", color = Silage.Text, fontSize = 48.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                problem?.let { Text(it, color = Silage.Error, fontSize = 18.sp) }
                profiles!!.forEach { p ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.05f)).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        ProfileAvatar(p.name, p.avatarUrl, Modifier.size(56.dp), 24)
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = Silage.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            val details = listOfNotNull("Primary".takeIf { p.isPrimary }, "Kids".takeIf { p.isChild }, "PIN".takeIf { p.hasPin },
                                p.maxContentRating?.let { "$it max" }, "Limited libraries".takeIf { p.libraryRestrictionsEnabled })
                            if (details.isNotEmpty()) Text(details.joinToString(" · "), color = Silage.Text2, fontSize = 16.sp)
                        }
                        Pill("Edit") { editing = p.id }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 12.dp)) {
                    Pill("Add profile") { editing = "" }
                    Pill("Done", onClick = onDone)
                }
            }
        }
    }
}

@Composable
internal fun ProfileEditor(profile: Profile?, all: List<Profile>, libraries: List<UserLibrary>, repository: ProfileRepository, onOwnProfileDeleted: () -> Unit = {}, onClose: (changed: Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val before = remember(profile?.id) { profile?.toDraft() ?: ProfileDraft() }
    var draft by remember(profile?.id) { mutableStateOf(before) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun save() {
        if (busy) return
        problem = draftProblem(draft, all, profile?.id)
        if (problem != null) return
        busy = true
        scope.launch {
            val result = if (profile == null) repository.createProfile(CreateProfileRequest(
                name = draft.name.trim(), pin = draft.pin.ifEmpty { null }, isChild = draft.isChild.takeIf { it },
                maxContentRating = draft.maxRating,
                libraryRestrictionsEnabled = draft.restricted.takeIf { it }, allowedLibraryIds = draft.libraries.sorted().takeIf { draft.restricted },
            )) else repository.patchProfile(profile.id, patchFor(before, draft))
            busy = false
            when (val p = result.problem()) {
                null -> onClose(true)
                else -> problem = "Couldn't save: $p"
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 96.dp, vertical = 64.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Text(if (profile == null) "Add profile" else "Edit ${profile.name}", color = Silage.Text, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        SilageField(draft.name, { draft = draft.copy(name = it) }, "Name", Modifier.widthIn(max = 520.dp))

        Section("PIN", "Four digits, asked for when this profile is opened.")
        SilageField(draft.pin, { if (it.length <= 4 && it.all(Char::isDigit)) draft = draft.copy(pin = it, removePin = false) },
            if (profile?.hasPin == true) "New PIN (leave blank to keep it)" else "PIN (optional)", Modifier.widthIn(max = 520.dp), password = true)
        if (profile?.hasPin == true) Pill(if (draft.removePin) "PIN will be removed" else "Remove PIN", selected = draft.removePin) {
            draft = draft.copy(removePin = !draft.removePin, pin = "")
        }

        // The primary profile manages the others, so it isn't limited itself.
        if (profile?.isPrimary != true) {
            Section("Kids profile", "Starts with a safer rating limit. Look over the limits below before saving.")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Pill("On", selected = draft.isChild) { draft = draft.copy(isChild = true, maxRating = draft.maxRating ?: KidsRatingLimit) }
                Pill("Off", selected = !draft.isChild) { draft = draft.copy(isChild = false) }
            }
            Section("Content rating limit", "Hides anything rated above this.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                RatingLimits.forEach { (value, label) -> Pill(label, selected = draft.maxRating == value) { draft = draft.copy(maxRating = value) } }
            }
            Section("Libraries", "Limit which libraries this profile can browse.")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Pill("All libraries", selected = !draft.restricted) { draft = draft.copy(restricted = false) }
                Pill("Only these", selected = draft.restricted) { draft = draft.copy(restricted = true) }
            }
            if (draft.restricted) {
                if (libraries.isEmpty()) Text("Silage can't list the libraries from here. Pick a profile first, then open this from Settings → Account.", color = Silage.Text2, fontSize = 17.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    libraries.forEach { library ->
                        val on = library.id in draft.libraries
                        Pill(library.name, selected = on) { draft = draft.copy(libraries = if (on) draft.libraries - library.id else draft.libraries + library.id) }
                    }
                }
            }
        }
        problem?.let { Text(it, color = Silage.Error, fontSize = 18.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 10.dp)) {
            Pill(if (busy) "Saving…" else "Save", selected = true) { save() }
            Pill("Cancel") { onClose(false) }
            if (profile != null && !profile.isPrimary) Pill("Delete profile") { confirmDelete = true }
        }
    }
    if (confirmDelete && profile != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${profile.name}?") },
        text = { Text("This removes the profile with its watch history, lists and preferences. It can't be undone.") },
        confirmButton = {
            Pill("Delete") {
                confirmDelete = false
                scope.launch {
                    val own = repository.getActiveProfileId() == profile.id
                    when (val p = repository.deleteProfile(profile.id).problem()) {
                        null -> if (own) { repository.clearProfile(); onOwnProfileDeleted() } else onClose(true)
                        else -> problem = "Couldn't delete: $p"
                    }
                }
            }
        },
        dismissButton = { Pill("Keep it") { confirmDelete = false } },
    )
}

@Composable
private fun Section(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 10.dp)) {
        Text(title, color = Silage.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(detail, color = Silage.Text2, fontSize = 17.sp)
    }
}
