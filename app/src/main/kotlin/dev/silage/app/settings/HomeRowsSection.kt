package dev.silage.app.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.browse.Pill
import dev.silage.app.server.HomeRow
import dev.silage.app.server.SavedHomeRow
import dev.silage.app.server.SiloExtras
import dev.silage.app.ui.Silage
import dev.silage.app.ui.SilageField
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.Koin
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.SettingsRepository

/** The server's "Show new episodes first" setting. */
internal const val NewEpisodesFirstKey = "ui.next_up_new_releases_first"

/** [rows] with the row at [index] moved by [step], or unchanged at either end. */
internal fun <T> moved(rows: List<T>, index: Int, step: Int): List<T> {
    val target = index + step
    if (index !in rows.indices || target !in rows.indices) return rows
    return rows.toMutableList().also { it.add(target, it.removeAt(index)) }
}

/** Settings → Home screen: this profile's rows, on every device it signs in to. */
@Composable
internal fun HomeRowsSection(koin: Koin) {
    val extras = remember { koin.get<SiloExtras>() }
    val settings = remember { koin.get<SettingsRepository>() }
    val scope = rememberCoroutineScope()
    val saving = remember { Mutex() }
    var rows by remember { mutableStateOf<List<HomeRow>?>(null) }
    // What the server last said, which a save compares itself to.
    var baseline by remember { mutableStateOf(emptyList<HomeRow>()) }
    var saved by remember { mutableStateOf(emptyList<SavedHomeRow>()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var attempt by remember { mutableStateOf(0) }
    var newFirst by remember { mutableStateOf<Boolean?>(null) }

    suspend fun reload() {
        extras.homeRows().onSuccess { (r, s) -> rows = r; baseline = r; saved = s; problem = null }
            .onFailure { problem = "Couldn't read your Home rows: ${it.message}" }
    }
    LaunchedEffect(attempt) { reload() }
    LaunchedEffect(Unit) {
        val values = runCatching { settings.getEffectiveValues(keys = listOf(NewEpisodesFirstKey)) }.getOrNull()
        if (values is ApiResult.Success) newFirst = values.data[NewEpisodesFirstKey]?.value?.jsonPrimitive?.booleanOrNull ?: false
    }
    /** Shows [next] now; a failed save goes back to what the server has. */
    fun change(next: List<HomeRow>) {
        rows = next
        scope.launch {
            saving.withLock {
                extras.saveHomeRows(next, baseline, saved).onSuccess { reload() }
                    .onFailure { problem = "Couldn't save that: ${it.message}"; reload() }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Heading("Home rows", "Show, hide, order and rename the rows on Home for this profile. They follow you to Silo's other apps.")
        problem?.let { Text(it, color = Silage.Error, fontSize = 17.sp) }
        val current = rows
        if (current == null) {
            if (problem != null) Pill("Retry") { attempt++ }
            return@Column
        }
        current.forEachIndexed { i, row ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(row.title, color = if (row.hidden) Silage.Text3 else Silage.Text, fontSize = 19.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Pill("Up") { change(moved(current, i, -1)) }
                    Pill("Down") { change(moved(current, i, 1)) }
                    Pill("Rename") { renaming = row.id; name = row.title }
                    // A fixed width, so the buttons line up down the page.
                    Box(Modifier.width(112.dp), contentAlignment = Alignment.CenterEnd) {
                        Pill(if (row.hidden) "Hidden" else "Shown", selected = !row.hidden) { change(current.map { if (it.id == row.id) it.copy(hidden = !it.hidden) else it }) }
                    }
                }
                if (renaming == row.id) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    fun finish(title: String) {
                        renaming = null
                        val clean = title.trim().ifEmpty { row.defaultTitle.ifEmpty { row.title } }
                        if (clean != row.title) change(current.map { if (it.id == row.id) it.copy(title = clean) else it })
                    }
                    SilageField(name, { name = it }, "Row name", Modifier.weight(1f), onDone = { finish(name) })
                    Pill("Save") { finish(name) }
                    if (row.defaultTitle.isNotEmpty() && row.title != row.defaultTitle) Pill("Use the original name") { finish(row.defaultTitle) }
                    Pill("Cancel") { renaming = null }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Pill("Reset to the server's rows") {
            scope.launch {
                saving.withLock {
                    extras.resetHomeRows().onFailure { problem = "Couldn't reset: ${it.message}" }
                    reload()
                }
            }
        }
        newFirst?.let { on ->
            Spacer(Modifier.height(26.dp))
            Heading("Show new episodes first", "Move a show you're caught up on to the front of Continue Watching and Next Up when a new episode or season arrives.")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(true to "On", false to "Off").forEach { (value, label) ->
                    Pill(label, selected = on == value) {
                        newFirst = value
                        scope.launch {
                            if (runCatching { settings.setProfileValue(NewEpisodesFirstKey, JsonPrimitive(value)) }.getOrNull() !is ApiResult.Success) {
                                newFirst = on
                                problem = "Couldn't save that setting."
                            }
                        }
                    }
                }
            }
        }
        Text("Changes show the next time Home loads.", color = Silage.Text3, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
    }
}
