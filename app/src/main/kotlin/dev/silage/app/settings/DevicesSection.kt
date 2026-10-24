package dev.silage.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.browse.Pill
import dev.silage.app.ui.Silage
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.apiv2.LoginSessionV2
import org.siloserver.silo.network.apiv2.LoginSessionsV2Api
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How a sign-in reads in the list: its device, and what is known about where and when. */
data class DeviceRow(val id: String, val name: String, val detail: String, val current: Boolean)

/** The rows for [sessions], this device first, then the most recently used. */
fun deviceRows(sessions: List<LoginSessionV2>, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): List<DeviceRow> =
    sessions.sortedWith(compareByDescending<LoginSessionV2> { it.current }.thenByDescending { parse(it.lastSeenAt ?: it.createdAt) ?: Instant.EPOCH })
        .map { s ->
            DeviceRow(
                id = s.id,
                name = s.deviceName.takeIf { it.isNotBlank() } ?: "Unknown device",
                detail = listOfNotNull(
                    s.devicePlatform?.takeIf { it.isNotBlank() && !s.deviceName.contains(it, ignoreCase = true) },
                    s.ipAddress.takeIf { it.isNotBlank() },
                    if (s.current) "Active now" else parse(s.lastSeenAt)?.let { "Last active ${ago(it, now)}" },
                    parse(s.createdAt)?.let { "Signed in ${DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(zone).format(it)}" },
                ).joinToString(" · "),
                current = s.current,
            )
        }

private fun parse(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

/** "just now", "5 minutes ago", "3 hours ago", "2 days ago". */
internal fun ago(then: Instant, now: Instant): String {
    val d = Duration.between(then, now).coerceAtLeast(Duration.ZERO)
    fun plural(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"} ago"
    return when {
        d.toMinutes() < 1 -> "just now"
        d.toHours() < 1 -> plural(d.toMinutes(), "minute")
        d.toDays() < 1 -> plural(d.toHours(), "hour")
        d.toDays() < 60 -> plural(d.toDays(), "day")
        else -> plural(d.toDays() / 30, "month")
    }
}

/** Settings → Devices: every device signed in to this account, each of which (but this one) can be signed out. */
@Composable
internal fun DevicesSection(koin: Koin) {
    val api = remember { koin.get<LoginSessionsV2Api>() }
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<DeviceRow>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    suspend fun reload() {
        when (val r = api.list()) {
            is ApiResult.Success -> { rows = deviceRows(r.data); problem = null }
            is ApiResult.Error -> problem = "Couldn't load your devices: ${r.message.ifBlank { "the server said no" }}"
            is ApiResult.NetworkError -> problem = "Can't reach the server."
        }
    }
    LaunchedEffect(Unit) { reload() }
    fun signOut(ids: List<String>) {
        if (ids.isEmpty() || busy != null) return
        busy = ids.first()
        scope.launch {
            val failed = ids.count { api.signOut(it) !is ApiResult.Success }
            busy = null
            reload()
            if (failed > 0) problem = if (ids.size == 1) "Couldn't sign that device out. Try again." else "Couldn't sign $failed of those devices out. Try again."
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Heading("Devices", "Everywhere your account is signed in. Sign out a device you no longer use or don't recognise; it has to sign in again to use Silo.")
        problem?.let { Text(it, color = Silage.Error, fontSize = 18.sp, modifier = Modifier.padding(bottom = 18.dp)) }
        val list = rows
        when {
            list == null && problem == null -> CircularProgressIndicator(color = Silage.Text)
            list != null && list.isEmpty() -> Text("No signed-in devices.", color = Silage.Text2, fontSize = 18.sp)
            list != null -> {
                val others = list.filterNot { it.current }
                if (others.size > 1) {
                    Pill(if (busy != null) "Signing out…" else "Sign out all other devices") { signOut(others.map { it.id }) }
                    Spacer(Modifier.height(24.dp))
                }
                list.forEach { row ->
                    Row(
                        Modifier.widthIn(max = 1000.dp).fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.05f)).padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(row.name, color = Silage.Text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                                if (row.current) {
                                    Text("This device", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Silage.Amber).padding(horizontal = 10.dp, vertical = 3.dp))
                                }
                            }
                            if (row.detail.isNotBlank()) Text(row.detail, color = Silage.Text2, fontSize = 16.sp)
                        }
                        if (!row.current) {
                            Spacer(Modifier.width(16.dp))
                            Pill(if (busy == row.id) "Signing out…" else "Sign out") { signOut(listOf(row.id)) }
                        }
                    }
                }
            }
        }
    }
}
