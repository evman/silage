package dev.silage.app.playback.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.playback.VideoControls

@Composable
internal fun VideoPanel(ui: HudUi, act: HudActions) {
    Column(Modifier.width(490.dp).heightIn(max = 680.dp)
        .background(Color(0xF2171717), RoundedCornerShape(14.dp))
        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
        .verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("VIDEO SETTINGS", color = Color.White, fontSize = 18.sp)
        Text("Current playback only · changes apply immediately", color = Color.LightGray, fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        VideoControls.labels.zip(VideoControls.values(ui.video)).forEachIndexed { i, (label, value) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 58.dp)
                .background(if (ui.focus != null && ui.menuIndex == i) Color.White.copy(alpha = 0.16f) else Color.Transparent),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = Color.White, fontSize = 17.sp)
                    Text(value, color = Color(0xFFE8B86E), fontSize = 14.sp)
                }
                VideoStep("−", "Decrease $label") { act.video(i, -1) }
                Spacer(Modifier.width(8.dp))
                VideoStep("+", "Increase $label") { act.video(i, 1) }
            }
        }
        Text("Zoom 50–200%", color = Color.LightGray, fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 8.dp))
        Text("Restore video defaults", color = Color.White, fontSize = 17.sp,
            modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = if (ui.focus != null && ui.menuIndex == VideoControls.ResetIndex) 0.24f else 0.10f), RoundedCornerShape(8.dp))
                .clickable { act.video(VideoControls.ResetIndex, 1) }.padding(14.dp))
        ui.videoError?.let { Text(it, color = Color(0xFFFFB4AB), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
        Text("↑↓ select · ←→ adjust · Enter next/reset · Escape close", color = Color.LightGray,
            fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun VideoStep(text: String, label: String, action: () -> Unit) {
    Box(Modifier.size(44.dp).semantics { contentDescription = label }
        .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp)).clickable(onClick = action),
        contentAlignment = Alignment.Center) { Text(text, color = Color.White, fontSize = 22.sp) }
}

/** Audio and subtitle timing for this video: each tap moves one 0.1 s later (+) or earlier (−). */
@Composable
internal fun SyncPanel(ui: HudUi, act: HudActions) {
    Column(Modifier.width(490.dp)
        .background(Color(0xF2171717), RoundedCornerShape(14.dp))
        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
        .padding(20.dp)) {
        Text("SYNC", color = Color.White, fontSize = 18.sp)
        Text("This video only · + plays it later, − earlier", color = Color.LightGray, fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        listOf(ui.audioDelay, ui.subtitleDelay).forEachIndexed { i, value ->
            val label = SyncControls.labels[i]
            Row(Modifier.fillMaxWidth().heightIn(min = 58.dp)
                .background(if (ui.focus != null && ui.menuIndex == i) Color.White.copy(alpha = 0.16f) else Color.Transparent),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = Color.White, fontSize = 17.sp)
                    Text(SyncControls.label(value), color = Color(0xFFE8B86E), fontSize = 14.sp)
                }
                VideoStep("−", "$label earlier") { act.sync(i, -1) }
                Spacer(Modifier.width(8.dp))
                VideoStep("+", "$label later") { act.sync(i, 1) }
            }
        }
        Text("Reset both", color = Color.White, fontSize = 17.sp,
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth()
                .background(Color.White.copy(alpha = if (ui.focus != null && ui.menuIndex == SyncControls.ResetIndex) 0.24f else 0.10f), RoundedCornerShape(8.dp))
                .clickable { act.sync(SyncControls.ResetIndex, 0) }.padding(14.dp))
        Text("↑↓ select · ←→ adjust · Escape close", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
    }
}
