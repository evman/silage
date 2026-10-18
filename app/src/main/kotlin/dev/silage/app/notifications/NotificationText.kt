package dev.silage.app.notifications

import org.siloserver.silo.model.notifications.NotificationRow
import org.siloserver.silo.model.notifications.NotificationType
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a notification says, for the inbox and for Windows toasts. */
data class NotificationText(val title: String, val body: String)

fun NotificationRow.text(): NotificationText {
    val show = seriesTitle.ifBlank { "A title" }
    val code = listOfNotNull(seasonNumber?.let { "S$it" }, episodeNumber?.let { "E$it" }).joinToString(" ")
    val episode = listOf(code, episodeTitle).filter { it.isNotBlank() }.joinToString(" · ")
    return when (type) {
        NotificationType.EpisodeAvailable -> NotificationText("New episode of $show", episode.ifBlank { "Ready to watch" })
        NotificationType.RequestFulfilled -> NotificationText("$show is ready to watch", episode.ifBlank { "Your request was added to the library" })
        NotificationType.Unknown -> NotificationText(show, episode.ifBlank { "Something new on the server" })
    }
}

/**
 * The content to open for a notification: the series (where the new episode
 * is next up), else the episode itself.
 */
fun NotificationRow.openTarget(): String? = seriesId?.takeIf { it.isNotBlank() } ?: episodeId?.takeIf { it.isNotBlank() }

/** "Just now", "5 min ago", "3 h ago", "Yesterday", "4 days ago", then a date. */
fun relativeTime(createdAt: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val at = runCatching { Instant.parse(createdAt) }.recoverCatching { java.time.OffsetDateTime.parse(createdAt).toInstant() }.getOrNull()
        ?: return ""
    val age = Duration.between(at, now)
    return when {
        age.toMinutes() < 1 -> "Just now"
        age.toMinutes() < 60 -> "${age.toMinutes()} min ago"
        age.toHours() < 24 -> "${age.toHours()} h ago"
        age.toDays() < 2 -> "Yesterday"
        age.toDays() < 7 -> "${age.toDays()} days ago"
        else -> DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(zone).format(at)
    }
}

/**
 * Which rows deserve a toast: unread rows that arrived above everything seen
 * before (realtime adds new ones at the top; loading older pages appends).
 * [previous] is null until the first refresh has given a baseline.
 */
fun freshArrivals(previous: List<NotificationRow>?, current: List<NotificationRow>): List<NotificationRow> {
    if (previous == null) return emptyList()
    val seen = previous.mapTo(HashSet()) { it.id }
    return current.takeWhile { it.id !in seen }.filter { !it.isRead }
}
