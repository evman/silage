package dev.silage.app.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.ui.Silage
import org.siloserver.silo.catalog.filter.CatalogFacet
import org.siloserver.silo.catalog.filter.CatalogFilterQueryBuilder
import org.siloserver.silo.catalog.filter.CatalogFilterState
import org.siloserver.silo.catalog.filter.facetOptionPairs
import org.siloserver.silo.model.catalog.CatalogFiltersResponse
import org.siloserver.silo.model.catalog.CatalogQueryGroup
import org.siloserver.silo.model.catalog.CatalogQueryRule

/** The Silo web app's sort choices for a video library. */
enum class LibrarySortField(val key: String, val label: String, val defaultOrder: String) {
    Added("added_at", "Date Added", "desc"),
    Title("title", "Title", "asc"),
    Release("release_date", "Release Date", "desc"),
    Year("year", "Year", "desc"),
    LatestEpisode("latest_episode_added", "Latest Episode Added", "desc"),
    ContentRating("content_rating", "Content Rating", "asc"),
    Duration("runtime", "Duration", "desc"),
    Imdb("rating_imdb", "IMDb Rating", "desc"),
    Tmdb("rating_tmdb", "TMDB Rating", "desc"),
    Resolution("resolution", "Resolution", "desc"),
    Bitrate("bitrate", "Bitrate", "desc"),
    Progress("progress", "Progress", "desc"),
    Viewed("date_viewed", "Date Viewed", "desc"),
    Plays("plays", "Plays", "desc"),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: Added
    }
}

/** Duration ranges, as the web app's Duration rule (minutes). */
enum class DurationBucket(val label: String, val rule: CatalogQueryRule) {
    UnderHalfHour("Under 30 min", CatalogQueryRule("runtime", "lt", "30")),
    HalfToOneHour("30–60 min", CatalogQueryRule("runtime", "between", "", listOf("30", "59"))),
    OneToTwoHours("1–2 hours", CatalogQueryRule("runtime", "between", "", listOf("60", "120"))),
    OverTwoHours("Over 2 hours", CatalogQueryRule("runtime", "gt", "120")),
}

/** A library page's sort and filters: Silo's facets plus Duration. */
data class LibraryFilters(
    val state: CatalogFilterState = CatalogFilterState(),
    val durations: Set<DurationBucket> = emptySet(),
) {
    val sort: LibrarySortField get() = LibrarySortField.of(state.sort)
    val activeCount: Int get() = state.activeFacetCount + if (durations.isEmpty()) 0 else 1
    val canReset: Boolean get() = activeCount > 0 || !state.matchAll

    /** Changes with every choice, so each combination has its own pager. */
    val key: String get() = buildString {
        append(state.sort).append(':').append(state.order).append(':').append(state.matchAll)
        CatalogFacet.entries.forEach { f -> state.valuesFor(f).sorted().takeIf { it.isNotEmpty() }?.let { append(':').append(f.name).append('=').append(it.joinToString(",")) } }
        if (durations.isNotEmpty()) append(":dur=").append(durations.sortedBy { it.ordinal }.joinToString(",") { it.name })
    }

    fun groups(): List<CatalogQueryGroup> = CatalogFilterQueryBuilder.buildGroups(state) +
        if (durations.isEmpty()) emptyList() else listOf(CatalogQueryGroup("any", durations.sortedBy { it.ordinal }.map { it.rule }))

    val match: String get() = CatalogFilterQueryBuilder.matchParam(state)

    fun sortBy(field: LibrarySortField) = copy(state = state.copy(sort = field.key, order = field.defaultOrder))
    fun flipOrder() = copy(state = state.copy(order = if (state.order == "asc") "desc" else "asc"))
    fun toggle(facet: CatalogFacet, value: String) = copy(state = state.toggle(facet, value))
    fun toggle(bucket: DurationBucket) = copy(durations = if (bucket in durations) durations - bucket else durations + bucket)
    fun reset() = copy(state = state.resetFilters(), durations = emptySet())
}

/** This sort and these filters as saved in ui.json. */
fun LibraryFilters.toSaved() = dev.silage.app.ui.SavedLibraryView(
    sort = state.sort, order = state.order, matchAll = state.matchAll,
    filters = state.selections.filterValues { it.isNotEmpty() }.entries.associate { (f, v) -> f.name to v.sorted() },
    durations = durations.sortedBy { it.ordinal }.map { it.name },
)

/** Saved choices back as filters; a sort, order, facet or duration this version doesn't know falls back to the default. */
fun dev.silage.app.ui.SavedLibraryView?.toLibraryFilters(): LibraryFilters {
    if (this == null) return LibraryFilters()
    val field = LibrarySortField.entries.firstOrNull { it.key == sort }
    val base = LibraryFilters()
    val sorted = if (field == null) base else base.sortBy(field).let { f -> if (order in setOf("asc", "desc")) f.copy(state = f.state.copy(order = order!!)) else f }
    val selections = filters.mapNotNull { (name, values) ->
        CatalogFacet.entries.firstOrNull { it.name == name }?.let { f -> values.toSet().takeIf { it.isNotEmpty() }?.let { f to it } }
    }.toMap()
    return sorted.copy(
        state = sorted.state.copy(selections = selections, matchAll = matchAll),
        durations = durations.mapNotNull { n -> DurationBucket.entries.firstOrNull { it.name == n } }.toSet(),
    )
}

/** Each library page remembers its own sort and filters on this device. */
class LibraryViewPrefs(private val prefs: dev.silage.app.ui.UiPrefs) {
    /** What [libraryId]'s page opens with: the last saved choices, else the defaults. */
    fun initial(libraryId: Int): LibraryFilters = prefs.libraryView(libraryId).toLibraryFilters()

    fun save(libraryId: Int, filters: LibraryFilters) {
        val saved = filters.toSaved()
        if (prefs.libraryView(libraryId) != saved) prefs.setLibraryView(libraryId, saved)
    }
}

/** The facets the filter panel offers, in the order it shows them. */
internal val PanelFacets = listOf(
    CatalogFacet.WatchStatus, CatalogFacet.Genre, CatalogFacet.Decade, CatalogFacet.Resolution, CatalogFacet.DynamicRange,
    CatalogFacet.ContentRating, CatalogFacet.AudioLanguage, CatalogFacet.SubtitleLanguage, CatalogFacet.OriginalLanguage,
    CatalogFacet.Studio, CatalogFacet.Network, CatalogFacet.Country,
)

private val languageFacets = setOf(CatalogFacet.AudioLanguage, CatalogFacet.SubtitleLanguage, CatalogFacet.OriginalLanguage)

/** A facet value as people read it: language codes become names. */
internal fun facetLabel(facet: CatalogFacet, value: String, label: String): String =
    if (facet in languageFacets) dev.silage.app.playback.languageCode(value)?.let { dev.silage.app.playback.languageName(it) } ?: label else label

/** "Sort: Date Added" with a menu of every sort, and an arrow pill that flips the order. */
@Composable
fun SortControls(filters: LibraryFilters, onChange: (LibraryFilters) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill("Sort: ${filters.sort.label}", selected = open) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LibrarySortField.entries.forEach { field ->
                DropdownMenuItem(
                    text = { Text(field.label, fontSize = 18.sp, fontWeight = if (field == filters.sort) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (field == filters.sort) Color.White else Silage.Text2) },
                    onClick = { open = false; onChange(filters.sortBy(field)) },
                )
            }
        }
    }
    Pill(if (filters.state.order == "asc") "↑ Ascending" else "↓ Descending") { onChange(filters.flipOrder()) }
}

/** Every filter as rows of pills, under the library's title. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterPanel(filters: LibraryFilters, vocabulary: CatalogFiltersResponse?, onChange: (LibraryFilters) -> Unit) {
    var expanded by remember { mutableStateOf(emptySet<CatalogFacet>()) }
    Column(Modifier.widthIn(max = 1500.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Match all filters", selected = filters.state.matchAll) { onChange(filters.copy(state = filters.state.copy(matchAll = true))) }
            Pill("Match any filter", selected = !filters.state.matchAll) { onChange(filters.copy(state = filters.state.copy(matchAll = false))) }
            if (filters.canReset) Pill("Clear filters") { onChange(filters.reset()) }
        }
        FacetRow("Duration") {
            DurationBucket.entries.forEach { b -> Pill(b.label, selected = b in filters.durations) { onChange(filters.toggle(b)) } }
        }
        PanelFacets.forEach { facet ->
            val options = facetOptionPairs(facet, vocabulary)
            if (options.isNotEmpty()) {
                val chosen = filters.state.valuesFor(facet)
                val limit = 18
                val showAll = facet in expanded || options.size <= limit + 2
                val shown = if (showAll) options else options.take(limit) + options.drop(limit).filter { it.first in chosen }
                FacetRow(facet.label) {
                    shown.forEach { (value, label) ->
                        Pill(facetLabel(facet, value, label), selected = value in chosen) { onChange(filters.toggle(facet, value)) }
                    }
                    if (!showAll) Pill("More… (${options.size - limit})") { expanded = expanded + facet }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacetRow(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = Silage.Text2, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}
