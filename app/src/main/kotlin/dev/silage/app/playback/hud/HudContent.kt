package dev.silage.app.playback.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlin.math.abs
import androidx.compose.runtime.rememberUpdatedState
import dev.silage.app.playback.PlaybackMeta
import dev.silage.app.playback.VideoSettings
import dev.silage.app.playback.VideoControls
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

enum class HudMenu { None, Audio, Subtitles, Speed, Chapters, Info, Video, FindSubtitles, AiSubtitles }

data class HudUi(
    val meta: PlaybackMeta = PlaybackMeta(),
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val bufferedTo: Double = 0.0,
    val paused: Boolean = false,
    val buffering: Boolean = false,
    val visible: Boolean = true,
    val menu: HudMenu = HudMenu.None,
    val menuIndex: Int = 0,
    /** The keyboard focus ring, or null while the mouse is in use. */
    val focus: HudFocus? = null,
    val tracks: Tracks = Tracks.Empty,
    val speed: Double = 1.0,
    val info: List<Pair<String, String>> = emptyList(),
    val skip: SkipPrompt? = null,
    val chapters: List<Chapter> = emptyList(),
    val video: VideoSettings = VideoSettings(),
    val videoError: String? = null,
    val fullscreen: Boolean = false,
    /** Find / AI subtitles are offered (the server's subtitle tools are wired in). */
    val hasSubtitleTools: Boolean = false,
    val finder: FinderUi = FinderUi(),
)

interface HudActions {
    fun togglePause()
    fun seekBy(seconds: Double)
    fun seekTo(seconds: Double)
    fun openMenu(menu: HudMenu)
    fun audio(id: Int)
    fun subtitle(id: Int?)
    fun speed(value: Double)
    fun exit()
    fun fullscreen() {}
    fun video(index: Int, step: Int) {}
    fun chapter(index: Int) {}
    /** Ends this episode and moves on to the next one (the credits' "Next Episode"). */
    fun nextEpisode() {}
    /** Opens the Find subtitles panel (and searches). */
    fun findSubtitles() {}
    /** Opens the AI subtitles panel. */
    fun createSubtitles() {}
    fun pickHit(index: Int) {}
    fun pickAi(index: Int) {}
}

val SPEEDS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

private val Text = Color(0xFFEDEDED)
private val Dim = Color.White.copy(alpha = 0.78f)
private val Amber = Color(0xFFE8B86E)
private val Panel = Color(0xF2171717)

/** The player HUD, laid out for a 1920×1080 dp canvas (scaled by density). */
@Composable
fun HudContent(ui: HudUi, act: HudActions) {
    Box(Modifier.fillMaxSize()) {
        // Clicking the picture away from an open menu closes it (the controls above still work).
        if (ui.menu != HudMenu.None) Box(Modifier.fillMaxSize().pointerInput(ui.menu) { detectTapGestures { act.openMenu(ui.menu) } })
        AnimatedVisibility(ui.visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(300))) {
            Box(Modifier.fillMaxSize()) {
                // Top scrim with the back button, bottom scrim behind the HUD.
                Box(Modifier.fillMaxWidth().height(200.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))))
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(320.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.56f), Color.Black.copy(alpha = 0.82f)))),
                )
                UtilityButton(Icons.AutoMirrored.Filled.ArrowBack, Modifier.padding(40.dp), size = 52, onClick = act::exit)
                BottomHud(ui, act, Modifier.align(Alignment.BottomCenter))
            }
        }
        if (ui.buffering) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(64.dp), color = Text, strokeWidth = 4.dp)
        }
        ui.skip?.takeIf { ui.menu == HudMenu.None }?.let { skip ->
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = 64.dp, bottom = if (ui.visible) 200.dp else 72.dp)
                    .focusRing(ui.focus == HudFocus.Skip, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(10.dp)).background(Color.White)
                    .clickable { if (skip.kind == SkipPrompt.Kind.NextEpisode) act.nextEpisode() else act.seekTo(skip.target) }
                    .padding(horizontal = 28.dp, vertical = 14.dp),
            ) { Text(skip.label, color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        }
        AnimatedVisibility(
            ui.menu != HudMenu.None && ui.menu != HudMenu.Info,
            Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 150.dp),
            enter = fadeIn(tween(150)) + slideInVertically { it / 8 },
            exit = fadeOut(tween(150)) + slideOutVertically { it / 8 },
        ) { Box(Modifier.pointerInput(Unit) { detectTapGestures { } }) { MenuPanel(ui, act) } }
        if (ui.menu == HudMenu.Info) InfoPanel(ui.info, Modifier.align(Alignment.TopEnd).padding(40.dp))
    }
}

@Composable
private fun BottomHud(ui: HudUi, act: HudActions, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 48.dp).padding(bottom = 36.dp)) {
        SeekBar(ui.position, ui.duration, ui.bufferedTo, focused = ui.focus == HudFocus.Seek, onSeek = act::seekTo, markers = listOfNotNull(ui.meta.intro, ui.meta.credits).map { it.start })
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.36f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ui.meta.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ui.meta.line?.let {
                        Text(it.uppercase(), color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, letterSpacing = 2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("·", color = Color.White.copy(alpha = 0.25f), fontSize = 13.sp)
                    }
                    Text(
                        "${formatTime(ui.position)}  /  ${formatTime(ui.duration)}",
                        color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                    )
                }
            }
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Disc(Icons.Filled.Replay10, primary = false, size = 52, focused = ui.focus == HudFocus.Back10) { act.seekBy(-10.0) }
                Disc(if (ui.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, primary = true, size = 68, focused = ui.focus == HudFocus.PlayPause, onClick = act::togglePause)
                Disc(Icons.Filled.Forward10, primary = false, size = 52, focused = ui.focus == HudFocus.Fwd10) { act.seekBy(10.0) }
            }
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                UtilityButton(Icons.AutoMirrored.Outlined.VolumeUp, active = ui.menu == HudMenu.Audio, focused = ui.focus == HudFocus.Audio) { act.openMenu(HudMenu.Audio) }
                UtilityButton(Icons.Outlined.Subtitles, active = ui.menu == HudMenu.Subtitles, focused = ui.focus == HudFocus.Subtitles) { act.openMenu(HudMenu.Subtitles) }
                UtilityButton(Icons.Outlined.Speed, active = ui.menu == HudMenu.Speed, focused = ui.focus == HudFocus.Speed) { act.openMenu(HudMenu.Speed) }
                if (ui.chapters.isNotEmpty()) {
                    UtilityButton(Icons.Outlined.Bookmarks, label = "Chapters", active = ui.menu == HudMenu.Chapters, focused = ui.focus == HudFocus.Chapters) { act.openMenu(HudMenu.Chapters) }
                }
                Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(28.dp).background(Color.White.copy(alpha = 0.14f)))
                UtilityButton(Icons.Outlined.Info, active = ui.menu == HudMenu.Info, focused = ui.focus == HudFocus.Info) { act.openMenu(HudMenu.Info) }
                UtilityButton(Icons.Outlined.Tune, label = "Video settings", caption = "Video", active = ui.menu == HudMenu.Video, focused = ui.focus == HudFocus.Video) { act.openMenu(HudMenu.Video) }
                UtilityButton(if (ui.fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                    label = if (ui.fullscreen) "Exit fullscreen (F)" else "Enter fullscreen (F)", caption = "Fullscreen", focused = ui.focus == HudFocus.Fullscreen, onClick = act::fullscreen)
            }
        }
    }
}

/**
 * The seek bar: a white thumb on the played part that grows under the
 * pointer and can be dragged; a click jumps there; hovering or dragging
 * shows the time at that point above the bar.
 */
@Composable
private fun SeekBar(position: Double, duration: Double, bufferedTo: Double, focused: Boolean, onSeek: (Double) -> Unit, markers: List<Double>) {
    var widthPx by remember { mutableIntStateOf(1) }
    var hoverX by remember { mutableStateOf<Float?>(null) }
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    // Where a click or drag sent playback: the thumb waits there until the
    // player reports it (a network seek takes a moment) instead of jumping
    // back to the old position and forward again.
    var pending by remember { mutableStateOf<Pair<Double, Long>?>(null) }
    val waiting = pending?.takeIf { (target, at) -> abs(position - target) >= 3.0 && System.currentTimeMillis() - at <= 8_000 }
    // The gesture handlers outlive recompositions: they read the latest values.
    val currentDuration by rememberUpdatedState(duration)
    val currentSeek by rememberUpdatedState(onSeek)
    fun fracAt(x: Float) = (x / widthPx).coerceIn(0f, 1f)
    fun frac(v: Double) = if (duration > 0) (v / duration).coerceIn(0.0, 1.0).toFloat() else 0f
    fun seekTo(f: Float) {
        val d = currentDuration
        if (d <= 0) return
        val target = d * f
        pending = target to System.currentTimeMillis()
        currentSeek(target)
    }
    val shown = dragFrac ?: waiting?.let { frac(it.first) } ?: frac(position)
    val active = hoverX != null || dragFrac != null || focused
    val barHeight by animateDpAsState(if (active) 8.dp else 5.dp, tween(120))
    val thumb by animateDpAsState(if (dragFrac != null) 24.dp else if (active) 20.dp else 14.dp, tween(120))
    Box(
        Modifier.fillMaxWidth().height(40.dp).onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        hoverX = if (e.type == PointerEventType.Exit) null else e.changes.firstOrNull()?.position?.x
                    }
                }
            }
            .pointerInput(Unit) { detectTapGestures { o -> seekTo(fracAt(o.x)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragFrac = fracAt(o.x) },
                    onDragEnd = { dragFrac?.let { seekTo(it) }; dragFrac = null },
                    onDragCancel = { dragFrac = null },
                ) { change, _ -> change.consume(); dragFrac = fracAt(change.position.x) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(barHeight).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)))
        Box(Modifier.fillMaxWidth(frac(bufferedTo)).height(barHeight).clip(CircleShape).background(Color.White.copy(alpha = 0.32f)))
        Box(Modifier.fillMaxWidth(shown).height(barHeight).clip(CircleShape).background(Amber))
        markers.forEach { m ->
            Box(Modifier.fillMaxWidth(frac(m)).height(14.dp)) {
                Box(Modifier.align(Alignment.CenterEnd).width(2.dp).height(14.dp).background(Color.White.copy(alpha = 0.55f)))
            }
        }
        // The thumb, centred on the end of the played part.
        Box(Modifier.fillMaxWidth(shown).height(thumb)) {
            Box(
                Modifier.align(Alignment.CenterEnd).offset(x = thumb / 2).size(thumb)
                    .shadow(6.dp, CircleShape).clip(CircleShape).background(Color.White)
                    .then(if (focused) Modifier.border(3.dp, Amber, CircleShape) else Modifier),
            )
        }
        val bubbleAt = dragFrac?.let { it * widthPx } ?: hoverX
        if (bubbleAt != null && duration > 0) {
            Box(
                Modifier.offset {
                    val w = 84.dp.roundToPx()
                    IntOffset((bubbleAt - w / 2f).roundToInt().coerceIn(0, (widthPx - w).coerceAtLeast(0)), -46.dp.roundToPx())
                }.width(84.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.85f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp)).padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) { Text(formatTime(duration * fracAt(bubbleAt)), color = Color.White, fontSize = 15.sp, fontFamily = FontFamily.Monospace) }
        }
    }
}

@Composable
private fun Disc(icon: ImageVector, primary: Boolean, size: Int, focused: Boolean, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg = if (primary) Color.White else Color.White.copy(alpha = if (hovered) 0.22f else 0.12f)
    Box(
        Modifier.focusRing(focused, CircleShape).size(size.dp).clip(CircleShape).background(bg).hoverable(hover).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (primary) Color.Black else Color.White, modifier = Modifier.size((size * 0.5).dp)) }
}

@Composable
private fun UtilityButton(icon: ImageVector, modifier: Modifier = Modifier, size: Int = 48, active: Boolean = false, focused: Boolean = false, label: String = icon.name.substringAfterLast('.'), caption: String? = null, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        modifier.semantics { contentDescription = label }.focusRing(focused, CircleShape).size(size.dp).clip(CircleShape)
            .background(if (hovered || focused) Color.White.copy(alpha = if (focused) 0.16f else 0.08f) else Color.Transparent)
            .hoverable(hover).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (hovered || active || focused) Color.White else Dim, modifier = Modifier.size((size * 0.5).dp))
        caption?.let { Text(it, color = Dim, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomCenter).offset(y = 12.dp)) }
        if (active) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(5.dp).clip(CircleShape).background(Amber))
    }
}

@Composable
private fun MenuPanel(ui: HudUi, act: HudActions) {
    if (ui.menu == HudMenu.Video) { VideoPanel(ui, act); return }
    if (ui.menu == HudMenu.FindSubtitles || ui.menu == HudMenu.AiSubtitles) { FinderPanel(ui, act); return }
    Column(
        Modifier.width(400.dp).heightIn(max = 620.dp).clip(RoundedCornerShape(14.dp)).background(Panel)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            // Long lists (a file with many chapters) scroll inside the panel.
            .verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
    ) {
        val (title, rows) = when (ui.menu) {
            HudMenu.Audio -> "Audio" to ui.tracks.audio.map { t -> Triple(t.label, t.selected) { act.audio(t.id) } }
            HudMenu.Subtitles -> "Subtitles" to (
                listOf(Triple("Off", ui.tracks.subtitles.none { it.selected }) { act.subtitle(null) }) +
                    ui.tracks.subtitles.map { t -> Triple(t.label, t.selected) { act.subtitle(t.id) } } +
                    subtitleExtras(ui.hasSubtitleTools, ui.finder).map { e ->
                        Triple(extraLabel(e, ui.finder), false) { if (e == SubtitleExtra.Find) act.findSubtitles() else act.createSubtitles() }
                    }
                )
            HudMenu.Speed -> "Speed" to SPEEDS.map { s -> Triple(if (s == 1.0) "Normal" else "${s}×", ui.speed == s) { act.speed(s) } }
            HudMenu.Chapters -> {
                val current = Chapter.indexAt(ui.chapters, ui.position)
                "Chapters" to ui.chapters.mapIndexed { i, c -> Triple("${formatTime(c.start)}   ${c.title}", i == current) { act.chapter(i) } }
            }
            else -> "" to emptyList()
        }
        Text(title, color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, letterSpacing = 2.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        if (rows.isEmpty()) Text("None available", color = Dim, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp))
        rows.forEachIndexed { i, (label, selected, onClick) -> MenuRow(label, selected, highlighted = i == ui.menuIndex && ui.focus != null, onClick = onClick) }
    }
}

/** Find subtitles (provider results) or Create with AI (the options), with a status line. */
@Composable
private fun FinderPanel(ui: HudUi, act: HudActions) {
    val f = ui.finder
    val find = ui.menu == HudMenu.FindSubtitles
    Column(
        Modifier.width(680.dp).heightIn(max = 620.dp).clip(RoundedCornerShape(14.dp)).background(Panel)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
    ) {
        Text(if (find) "FIND SUBTITLES" else "CREATE SUBTITLES WITH AI", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, letterSpacing = 2.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        val status = f.status ?: if (!find && f.aiProgress != null) f.aiProgress else null
        if (status != null) Row(Modifier.padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (f.busy) { CircularProgressIndicator(Modifier.size(18.dp), color = Text, strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)) }
            Text(status, color = Dim, fontSize = 16.sp)
        }
        if (find) f.hits.forEachIndexed { i, h -> MenuRow(h.label, false, highlighted = i == ui.menuIndex && ui.focus != null, detail = h.detail) { act.pickHit(i) } }
        else if (f.aiProgress == null) f.aiOptions.forEachIndexed { i, o -> MenuRow(o.label, false, highlighted = i == ui.menuIndex && ui.focus != null) { act.pickAi(i) } }
    }
}

@Composable
private fun MenuRow(label: String, selected: Boolean, highlighted: Boolean, detail: String? = null, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .background(
                when {
                    highlighted -> Color.White.copy(alpha = 0.22f)
                    selected -> Color.White.copy(alpha = 0.10f)
                    hovered -> Color.White.copy(alpha = 0.06f)
                    else -> Color.Transparent
                },
            )
            .hoverable(hover).clickable(onClick = onClick).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (selected) Amber else Color.Transparent))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.padding(vertical = if (detail != null) 8.dp else 0.dp)) {
            Text(label, color = if (selected || highlighted) Color.White else Dim, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun InfoPanel(rows: List<Pair<String, String>>, modifier: Modifier) {
    Column(
        modifier.width(460.dp).clip(RoundedCornerShape(14.dp)).background(Panel)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("PLAYBACK INFO", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, letterSpacing = 2.sp)
        rows.forEach { (k, v) ->
            Row {
                Text(k, color = Color.White.copy(alpha = 0.55f), fontSize = 15.sp, modifier = Modifier.width(150.dp))
                Text(v, color = Text, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** The white keyboard-focus ring (Silo's focus-visible outline, thicker for a TV). */
private fun Modifier.focusRing(focused: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier =
    if (focused) this.border(3.dp, Color.White, shape).padding(5.dp) else this.padding(5.dp)
