package dev.silage.app.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.browse.Pill
import dev.silage.app.home.ErrorState
import dev.silage.app.nav.rememberEntryViewModel
import dev.silage.app.ui.ArrowRow
import dev.silage.app.ui.Artwork
import dev.silage.app.ui.Silage
import dev.silage.app.ui.UiPrefs
import dev.silage.app.ui.focusCard
import dev.silage.app.ui.isActive
import org.koin.core.Koin
import org.siloserver.silo.model.calendar.CalendarBadge
import org.siloserver.silo.model.calendar.CalendarFilter
import org.siloserver.silo.model.calendar.CalendarItem
import org.siloserver.silo.viewmodel.CalendarFilterStore
import org.siloserver.silo.viewmodel.CalendarViewModel
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The calendar's Following / Trending / All choice, kept in ui.json across launches. */
class UiPrefsCalendarFilterStore(private val prefs: UiPrefs) : CalendarFilterStore {
    override fun read(): String? = prefs.calendarFilter
    override fun write(filter: String) { prefs.calendarFilter = filter }
}

/** "Today · Monday 13 October", "Tomorrow · …", or just "Wednesday 15 October". */
fun calendarDayLabel(date: String, today: String, locale: Locale = Locale.ENGLISH): String {
    val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    val name = day.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale))
    val relative = runCatching { LocalDate.parse(today) }.getOrNull()?.let { t ->
        when (day) {
            t -> "Today"
            t.plusDays(1) -> "Tomorrow"
            t.minusDays(1) -> "Yesterday"
            else -> null
        }
    }
    return relative?.let { "$it · $name" } ?: name
}

/** "13 – 19 October 2026", or "29 September – 5 October 2026" across months. */
fun calendarWeekLabel(start: String, end: String, locale: Locale = Locale.ENGLISH): String {
    val s = runCatching { LocalDate.parse(start) }.getOrNull() ?: return ""
    val e = runCatching { LocalDate.parse(end) }.getOrNull() ?: return ""
    val full = DateTimeFormatter.ofPattern("d MMMM yyyy", locale)
    return when {
        s.year != e.year -> "${s.format(full)} – ${e.format(full)}"
        s.month != e.month -> "${s.format(DateTimeFormatter.ofPattern("d MMMM", locale))} – ${e.format(full)}"
        else -> "${s.dayOfMonth} – ${e.format(full)}"
    }
}

/** The item's air time in this computer's time zone ("9:00 PM"), when the server gives one. */
fun calendarAirTime(item: CalendarItem, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.ENGLISH): String? =
    item.airAt?.let { at ->
        runCatching { OffsetDateTime.parse(at).atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("h:mm a", locale)) }.getOrNull()
    } ?: item.airTime?.takeIf { it.isNotBlank() }

private fun CalendarItem.caption(): String? = listOfNotNull(
    if (isEpisode) listOfNotNull(seasonNumber?.let { "S$it" }, episodeNumber?.let { "E$it" }).joinToString(" ").ifBlank { null } else null,
    calendarAirTime(this),
).joinToString(" · ").ifBlank { null }

private fun CalendarItem.badgeLabel(): String? = when {
    CalendarBadge.SeriesPremiere in badges -> "Series premiere"
    CalendarBadge.SeasonPremiere in badges -> "Season premiere"
    CalendarBadge.Finale in badges -> "Finale"
    else -> null
}

/** What's airing this week, day by day, from the shows and movies in the library. */
@Composable
fun CalendarScreen(koin: Koin, onOpen: (String) -> Unit) {
    val vm = rememberEntryViewModel {
        CalendarViewModel(koin.get(), ZoneId.systemDefault().id, { LocalDate.now().toString() }, UiPrefsCalendarFilterStore(koin.get()))
    }
    val state by vm.uiState.collectAsState()
    val days = state.weekDates.filter { state.itemsFor(it).isNotEmpty() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        item {
            Column(Modifier.padding(start = 64.dp, top = 56.dp, end = 64.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Calendar", color = Silage.Text, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Pill("Following", selected = state.filter == CalendarFilter.Following) { vm.setFilter(CalendarFilter.Following) }
                    Pill("Trending", selected = state.filter == CalendarFilter.Trending) { vm.setFilter(CalendarFilter.Trending) }
                    Pill("All", selected = state.filter == CalendarFilter.All) { vm.setFilter(CalendarFilter.All) }
                    Spacer(Modifier.width(36.dp))
                    WeekButton(Icons.Filled.ChevronLeft, "Previous week") { vm.prevWeek() }
                    Pill("This week", selected = state.isCurrentWeek) { vm.goToToday() }
                    WeekButton(Icons.Filled.ChevronRight, "Next week") { vm.nextWeek() }
                    Text(calendarWeekLabel(state.weekStart, state.weekEnd), color = Silage.Text2, fontSize = 20.sp, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        when {
            days.isEmpty() && state.isLoading -> item {
                Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Silage.Text) }
            }
            days.isEmpty() && state.error != null -> item { Box(Modifier.fillMaxWidth().height(400.dp)) { ErrorState(state.error!!) { vm.refresh() } } }
            days.isEmpty() -> item {
                Text(
                    if (state.filter == CalendarFilter.Following) "Nothing airing this week from shows you follow." else "Nothing airing this week.",
                    color = Silage.Text2, fontSize = 22.sp, modifier = Modifier.padding(start = 64.dp, top = 40.dp),
                )
            }
            else -> items(days, key = { it }) { date ->
                Column {
                    Text(calendarDayLabel(date, state.today), color = if (date == state.today) Silage.Amber else Silage.Text2,
                        fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 64.dp, bottom = 14.dp))
                    ArrowRow(contentPadding = PaddingValues(horizontal = 64.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), arrowOffsetY = 26.dp) {
                        items(state.itemsFor(date), key = { it.contentId }) { item -> CalendarCard(item) { onOpen(item.detailContentId) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(if (active) Color.White else Color.White.copy(alpha = 0.10f))
            .semantics { contentDescription = label }
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (active) Color.Black else Color.White, modifier = Modifier.size(30.dp)) }
}

@Composable
private fun CalendarCard(item: CalendarItem, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(200.dp)) {
        Box(
            Modifier.size(200.dp, 300.dp).focusCard(active, shape).clip(shape)
                .hoverable(interaction).clickable(interaction, indication = null, onClick = onOpen),
        ) {
            Artwork(item.posterUrl, item.posterThumbhash, item.title, Modifier.fillMaxSize())
            item.badgeLabel()?.let {
                Text(it, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Silage.Amber).padding(horizontal = 8.dp, vertical = 4.dp))
            }
            if (item.watched) {
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, null, tint = Silage.Amber, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(item.title, color = if (active) Color.White else Silage.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.caption()?.let { Text(it, color = Silage.Text3, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        item.episodeTitle?.takeIf { item.isEpisode && it.isNotBlank() }?.let { Text(it, color = Silage.Text3, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
