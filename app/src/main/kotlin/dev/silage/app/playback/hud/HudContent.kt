package dev.silage.app.playback.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import dev.silage.app.playback.SeekThumbnail
import org.siloserver.silo.model.catalog.TimeRange
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
import androidx.compose.material.icons.outlined.Bedtime
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

/** [Playback] groups subtitles, speed and sync; [Picture] groups quality and the video settings. */
enum class HudMenu { None, Audio, Playback, Subtitles, Speed, Sync, Picture, Quality, Video, Chapters, Info, Sleep, FindSubtitles, AiSubtitles, SubtitleLook, ThisShow }

/** One row of the player's Quality menu: a `quality_preference` [id], its name and bitrate. */
data class QualityRow(val id: String, val label: String, val detail: String?, val selected: Boolean)

data class HudUi(
    val meta: PlaybackMeta = PlaybackMeta(),
    val duration: Double = 0.0,
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
    /** "Play in 1080p?" or "Back to 4K", after repeated buffering. */
    val qualityOffer: QualityOffer? = null,
    val chapters: List<Chapter> = emptyList(),
    val video: VideoSettings = VideoSettings(),
    val videoError: String? = null,
    val fullscreen: Boolean = false,
    /** Find / AI subtitles are offered (the server's subtitle tools are wired in). */
    val hasSubtitleTools: Boolean = false,
    val finder: FinderUi = FinderUi(),
    /** The server's quality choices for this video; empty hides the Quality button. */
    val qualities: List<QualityRow> = emptyList(),
    /** Audio and subtitle delay in seconds (positive is later), from the Sync panel. */
    val audioDelay: Double = 0.0,
    val subtitleDelay: Double = 0.0,
    /** The sleep timer's choice, and when it pauses (shown in its menu). */
    val sleep: SleepChoice = SleepChoice.Off,
    val sleepDetail: String? = null,
    /** A short message from a shortcut ("Volume 70%"), shown for a moment even with the HUD hidden. */
    val toast: String? = null,
    /** The newest preview frame for the seek bar; null shows only the time. */
    val thumbnail: SeekThumbnail? = null,
    /** In a Watch Party: who is here, under the title. Null outside one. */
    val partyLine: String? = null,
    /** How subtitles look, changed live from the Playback menu. */
    val subtitleLook: dev.silage.app.playback.SubtitleStyle = dev.silage.app.playback.SubtitleStyle(),
    /** This show's own auto-skip choice; null follows Settings. */
    val showAutoSkip: Boolean? = null,
    /** The player can remember choices for this show. */
    val hasShowMemory: Boolean = false,
)

interface HudActions {
    fun uploadSubtitle() {}
    /** Takes the buffering offer on screen, or turns it down. */
    fun answerQualityOffer(accept: Boolean) {}
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
    /** Copies Playback Info to the clipboard. */
    fun copyInfo() {}
    /** Opens the Find subtitles panel (and searches). */
    fun findSubtitles() {}
    /** Opens the AI subtitles panel. */
    fun createSubtitles() {}
    fun pickHit(index: Int) {}
    fun pickAi(index: Int) {}
    /** Restarts playback at a quality from the Quality menu. */
    fun quality(id: String) {}
    /** Nudges the audio ([SyncControls.AudioIndex]) or subtitles later or earlier; [SyncControls.ResetIndex] puts both back. */
    fun sync(index: Int, step: Int) {}
    fun sleep(choice: SleepChoice) {}
    /** Steps a row of the subtitle look; the last row puts it back to the default. */
    fun subtitleLook(index: Int, step: Int) {}
    fun showAutoSkip(value: Boolean?) {}
    /** The pointer is over this time on the seek bar; null once it has left. */
    fun previewAt(seconds: Double?) {}
}

val SPEEDS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0)

private val Text = Color(0xFFEDEDED)
private val Dim = Color.White.copy(alpha = 0.78f)
private val Amber = Color(0xFFE8B86E)
private val Panel = Color(0xF2171717)
private val TrackColor = Color.White.copy(alpha = 0.2f)
private val BufferedColor = Color.White.copy(alpha = 0.22f)
private val HighlightColor = Color.White.copy(alpha = 0.26f)
private val HoverColor = Color.White.copy(alpha = 0.4f)

/** The player HUD, laid out for a 1920×1080 dp canvas (scaled by density). */
@Composable
fun HudContent(ui: HudUi, act: HudActions, clock: HudClock = remember { HudClock() }) {
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
                BottomHud(ui, act, clock, Modifier.align(Alignment.BottomCenter))
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
        ui.qualityOffer?.takeIf { ui.menu == HudMenu.None && ui.skip == null }?.let { offer ->
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 64.dp, bottom = if (ui.visible) 200.dp else 72.dp).widthIn(max = 460.dp),
                horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(offer.message, color = Text, fontSize = 17.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Panel).padding(horizontal = 14.dp, vertical = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    offer.decline?.let { label ->
                        Box(
                            Modifier.clip(RoundedCornerShape(10.dp)).background(Panel).clickable { act.answerQualityOffer(false) }
                                .padding(horizontal = 24.dp, vertical = 14.dp),
                        ) { Text(label, color = Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                    }
                    Box(
                        Modifier.focusRing(ui.focus == HudFocus.Skip, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(10.dp)).background(Color.White)
                            .clickable { act.answerQualityOffer(true) }.padding(horizontal = 28.dp, vertical = 14.dp),
                    ) { Text(offer.accept, color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        AnimatedVisibility(
            ui.menu != HudMenu.None && ui.menu != HudMenu.Info,
            Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 150.dp),
            enter = fadeIn(tween(150)) + slideInVertically { it / 8 },
            exit = fadeOut(tween(150)) + slideOutVertically { it / 8 },
        ) { Box(Modifier.pointerInput(Unit) { detectTapGestures { } }) { MenuPanel(ui, act, clock) } }
        if (ui.menu == HudMenu.Info) InfoPanel(ui.info, act::copyInfo, Modifier.align(Alignment.TopEnd).padding(40.dp))
        ui.toast?.let { message ->
            Box(Modifier.align(Alignment.TopCenter).padding(top = 64.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.78f))
                .padding(horizontal = 26.dp, vertical = 14.dp)) {
                Text(message, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun BottomHud(ui: HudUi, act: HudActions, clock: HudClock, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 48.dp).padding(bottom = 36.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            TimeLabel(clock, ui.duration, remaining = false)
            SeekBar(
                clock, ui.duration, focused = ui.focus == HudFocus.Seek, chapters = ui.chapters, thumbnail = ui.thumbnail,
                highlights = listOfNotNull(ui.meta.recap, ui.meta.intro, ui.meta.credits),
                onSeek = act::seekTo, onPreview = act::previewAt, modifier = Modifier.weight(1f),
            )
            TimeLabel(clock, ui.duration, remaining = true)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.36f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ui.meta.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ui.meta.line?.let {
                        Text(it.uppercase(), color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, letterSpacing = 2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ui.partyLine?.let { Text(it, color = Amber, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Disc(Icons.Filled.Replay10, primary = false, size = 52, focused = ui.focus == HudFocus.Back10) { act.seekBy(-10.0) }
                Disc(if (ui.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, primary = true, size = 68, focused = ui.focus == HudFocus.PlayPause, onClick = act::togglePause)
                Disc(Icons.Filled.Forward10, primary = false, size = 52, focused = ui.focus == HudFocus.Fwd10) { act.seekBy(10.0) }
            }
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                UtilityButton(Icons.AutoMirrored.Outlined.VolumeUp, label = "Audio", active = ui.menu == HudMenu.Audio, focused = ui.focus == HudFocus.Audio) { act.openMenu(HudMenu.Audio) }
                UtilityButton(Icons.Outlined.Subtitles, label = if (ui.partyLine != null) "Subtitles and sync" else "Subtitles, speed and sync",
                    active = ui.menu in PlaybackMenus, focused = ui.focus == HudFocus.Playback) { act.openMenu(HudMenu.Playback) }
                if (ui.chapters.isNotEmpty()) {
                    UtilityButton(Icons.Outlined.Bookmarks, label = "Chapters", active = ui.menu == HudMenu.Chapters, focused = ui.focus == HudFocus.Chapters) { act.openMenu(HudMenu.Chapters) }
                }
                UtilityButton(Icons.Outlined.Tune, label = "Video settings", active = ui.menu in PictureMenus, focused = ui.focus == HudFocus.Picture) { act.openMenu(pictureMenu(ui)) }
                UtilityButton(Icons.Outlined.Info, label = "Playback info", active = ui.menu == HudMenu.Info, focused = ui.focus == HudFocus.Info) { act.openMenu(HudMenu.Info) }
                if (ui.partyLine == null) {
                    UtilityButton(Icons.Outlined.Bedtime, label = "Sleep timer", active = ui.menu == HudMenu.Sleep || ui.sleep != SleepChoice.Off, focused = ui.focus == HudFocus.Sleep) { act.openMenu(HudMenu.Sleep) }
                }
                UtilityButton(if (ui.fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                    label = if (ui.fullscreen) "Exit fullscreen (F)" else "Enter fullscreen (F)", focused = ui.focus == HudFocus.Fullscreen, onClick = act::fullscreen)
            }
        }
    }
}

/** Elapsed or remaining time beside the seek bar: only this text recomposes as the clock ticks. */
@Composable
private fun TimeLabel(clock: HudClock, duration: Double, remaining: Boolean) {
    val text by remember(duration, remaining) {
        derivedStateOf { if (remaining) "-" + formatTime((duration - clock.position).coerceAtLeast(0.0)) else formatTime(clock.position) }
    }
    Text(
        text, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontFamily = FontFamily.Monospace,
        textAlign = if (remaining) TextAlign.Start else TextAlign.End, maxLines = 1, modifier = Modifier.width(88.dp),
    )
}

/**
 * The seek bar: a track split at the chapters, with the played part in amber
 * and marked intros and credits picked out; it thickens under the pointer, a
 * click jumps there and the thumb can be dragged. Hovering or dragging shows a
 * preview above the bar. All of it is one Canvas, and the changing values are
 * read while drawing or placing, so neither the clock nor the pointer recomposes
 * anything but the preview's text.
 */
@Composable
private fun SeekBar(
    clock: HudClock, duration: Double, focused: Boolean, chapters: List<Chapter>, thumbnail: SeekThumbnail?,
    highlights: List<TimeRange>, onSeek: (Double) -> Unit, onPreview: (Double?) -> Unit, modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableIntStateOf(1) }
    var hoverX by remember { mutableStateOf<Float?>(null) }
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    // Where a click or drag sent playback: the thumb waits there until the
    // player reports it (a network seek takes a moment) instead of jumping
    // back to the old position and forward again.
    var pending by remember { mutableStateOf<Pair<Double, Long>?>(null) }
    // The gesture handlers outlive recompositions: they read the latest values.
    val currentDuration by rememberUpdatedState(duration)
    val currentSeek by rememberUpdatedState(onSeek)
    val currentPreview by rememberUpdatedState(onPreview)
    fun fracAt(x: Float) = (x / widthPx).coerceIn(0f, 1f)
    fun seekTo(f: Float) {
        val d = currentDuration
        if (d <= 0) return
        val target = d * f
        pending = target to System.currentTimeMillis()
        currentSeek(target)
    }
    val engaged by remember { derivedStateOf { hoverX != null || dragFrac != null } }
    val dragging by remember { derivedStateOf { dragFrac != null } }
    val emphasized = engaged || focused
    val barHeight by animateDpAsState(if (emphasized) 8.dp else 4.dp, tween(120))
    val thumbSize by animateDpAsState(if (dragging) 22.dp else if (emphasized) 18.dp else 12.dp, tween(120))
    val edges = remember(chapters, duration) { chapters.map { it.start }.filter { it > 1.0 && it < duration - 1.0 } }
    Box(
        modifier.height(40.dp).onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        val x = if (e.type == PointerEventType.Exit) null else e.changes.firstOrNull()?.position?.x
                        hoverX = x
                        currentPreview(x?.let { currentDuration * fracAt(it) })
                    }
                }
            }
            .pointerInput(Unit) { detectTapGestures { o -> seekTo(fracAt(o.x)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragFrac = fracAt(o.x) },
                    onDragEnd = { dragFrac?.let { seekTo(it) }; dragFrac = null },
                    onDragCancel = { dragFrac = null },
                ) { change, _ ->
                    change.consume()
                    fracAt(change.position.x).let { dragFrac = it; currentPreview(currentDuration * it) }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = barHeight.toPx()
            val top = (size.height - h) / 2
            val corner = CornerRadius(h / 2)
            fun x(seconds: Double) = if (duration > 0) (seconds / duration).coerceIn(0.0, 1.0).toFloat() * w else 0f
            val waiting = pending?.takeIf { (target, at) -> abs(clock.position - target) >= 3.0 && System.currentTimeMillis() - at <= 8_000 }
            val played = dragFrac?.let { it * w } ?: waiting?.let { x(it.first) } ?: x(clock.position)
            val buffered = x(clock.bufferedTo)
            val hover = hoverX?.coerceIn(0f, w)
            val gap = 3.dp.toPx()
            fun fill(from: Float, to: Float, color: Color) {
                if (to > from) drawRoundRect(color, Offset(from, top), Size(to - from, h), corner)
            }
            var start = 0f
            (edges.map { x(it) } + w).forEach { edge ->
                val end = if (edge >= w) w else edge - gap
                fill(start, end, TrackColor)
                fill(start, minOf(end, buffered), BufferedColor)
                highlights.forEach { r -> fill(maxOf(start, x(r.start)), minOf(end, x(r.end)), HighlightColor) }
                if (hover != null && hover > played) fill(maxOf(start, played), minOf(end, hover), HoverColor)
                fill(start, minOf(end, played), Amber)
                start = edge
            }
            val radius = thumbSize.toPx() / 2
            val center = Offset(played.coerceIn(0f, w), size.height / 2)
            drawCircle(Color.Black.copy(alpha = 0.35f), radius + 2.dp.toPx(), center)
            drawCircle(Color.White, radius, center)
            if (focused) drawCircle(Amber, radius, center, style = Stroke(3.dp.toPx()))
        }
        val previewing by remember { derivedStateOf { hoverX != null || dragFrac != null } }
        if (previewing && duration > 0) {
            val seconds by remember(duration) {
                derivedStateOf { (duration * fracAt(dragFrac?.let { it * widthPx } ?: hoverX ?: 0f)).toLong() }
            }
            SeekPreview(seconds.toDouble(), thumbnail, chapters) { width ->
                ((dragFrac?.let { it * widthPx } ?: hoverX ?: 0f) - width / 2f).toInt().coerceIn(0, (widthPx - width).coerceAtLeast(0))
            }
        }
    }
}

/** The card above the seek bar: a frame when one is ready, the time and the chapter there. Placed by [xFor], from its width. */
@Composable
private fun SeekPreview(seconds: Double, thumbnail: SeekThumbnail?, chapters: List<Chapter>, xFor: (width: Int) -> Int) {
    val chapter = chapters.getOrNull(Chapter.indexAt(chapters, seconds))?.title
    Column(
        Modifier.layout { measurable, _ ->
            val card = measurable.measure(Constraints())
            layout(0, 0) { card.place(xFor(card.width), -card.height - 2.dp.roundToPx()) }
        }.clip(RoundedCornerShape(10.dp)).background(Color(0xF0101010)).border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        thumbnail?.let { Image(it.image, null, Modifier.width(320.dp).height(180.dp), contentScale = ContentScale.Crop) }
        Column(Modifier.widthIn(min = 84.dp, max = 320.dp).padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatTime(seconds), color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
            chapter?.let { Text(it, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
private fun UtilityButton(icon: ImageVector, modifier: Modifier = Modifier, size: Int = 48, active: Boolean = false, focused: Boolean = false, label: String = icon.name.substringAfterLast('.'), onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        modifier.semantics { contentDescription = label }.focusRing(focused, CircleShape).size(size.dp).clip(CircleShape)
            .background(if (hovered || focused) Color.White.copy(alpha = if (focused) 0.16f else 0.08f) else Color.Transparent)
            .hoverable(hover).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (hovered || active || focused) Color.White else Dim, modifier = Modifier.size((size * 0.5).dp))
        if (active) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(5.dp).clip(CircleShape).background(Amber))
    }
}

@Composable
private fun MenuPanel(ui: HudUi, act: HudActions, clock: HudClock) {
    if (ui.menu == HudMenu.Video) { VideoPanel(ui, act); return }
    if (ui.menu == HudMenu.FindSubtitles || ui.menu == HudMenu.AiSubtitles) { FinderPanel(ui, act); return }
    if (ui.menu == HudMenu.Quality) { QualityPanel(ui, act); return }
    if (ui.menu == HudMenu.Sync) { SyncPanel(ui, act); return }
    if (ui.menu == HudMenu.SubtitleLook) { SubtitleLookPanel(ui, act); return }
    Column(
        Modifier.width(400.dp).heightIn(max = 620.dp).clip(RoundedCornerShape(14.dp)).background(Panel)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            // Long lists (a file with many chapters) scroll inside the panel.
            .verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
    ) {
        val (title, rows) = when (ui.menu) {
            HudMenu.Audio -> "Audio" to ui.tracks.audio.map { t -> Triple(t.label, t.selected) { act.audio(t.id) } }
            HudMenu.Playback -> "Playback" to playbackEntries(ui).map { (menu, label) -> Triple(label, false) { act.openMenu(menu) } }
            HudMenu.Picture -> "Video" to pictureEntries(ui).map { (menu, label) -> Triple(label, false) { act.openMenu(menu) } }
            HudMenu.Subtitles -> "Subtitles" to (
                listOf(Triple("Off", ui.tracks.subtitles.none { it.selected }) { act.subtitle(null) }) +
                    ui.tracks.subtitles.map { t -> Triple(t.label, t.selected) { act.subtitle(t.id) } } +
                    subtitleExtras(ui.hasSubtitleTools, ui.finder).map { e ->
                        Triple(extraLabel(e, ui.finder), false) { when (e) { SubtitleExtra.Find -> act.findSubtitles(); SubtitleExtra.Upload -> act.uploadSubtitle(); SubtitleExtra.Ai -> act.createSubtitles() } }
                    }
                )
            HudMenu.ThisShow -> "Auto-skip in this show" to ShowAutoSkipChoices.map { (value, label) -> Triple(label, ui.showAutoSkip == value) { act.showAutoSkip(value) } }
            HudMenu.Speed -> "Speed" to SPEEDS.map { s -> Triple(if (s == 1.0) "Normal" else "${s}×", ui.speed == s) { act.speed(s) } }
            HudMenu.Sleep -> (ui.sleepDetail?.let { "Sleep timer · $it" } ?: "Sleep timer") to
                SleepChoice.entries.map { c -> Triple(c.label, ui.sleep == c) { act.sleep(c) } }
            HudMenu.Chapters -> {
                val current = Chapter.indexAt(ui.chapters, clock.position)
                "Chapters" to ui.chapters.mapIndexed { i, c -> Triple("${formatTime(c.start)}   ${c.title}", i == current) { act.chapter(i) } }
            }
            else -> "" to emptyList()
        }
        Text(title, color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, letterSpacing = 2.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        if (rows.isEmpty()) Text("None available", color = Dim, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp))
        rows.forEachIndexed { i, (label, selected, onClick) -> MenuRow(label, selected, highlighted = i == ui.menuIndex && ui.focus != null, onClick = onClick) }
    }
}

/** What the Subtitles button groups, each with its current value. Speed isn't offered in a Watch Party, which plays at the room's pace. */
internal fun playbackEntries(ui: HudUi): List<Pair<HudMenu, String>> = buildList {
    add(HudMenu.Subtitles to "Subtitles · ${ui.tracks.subtitles.firstOrNull { it.selected }?.label ?: "Off"}")
    if (ui.partyLine == null) add(HudMenu.Speed to "Speed · ${if (ui.speed == 1.0) "Normal" else "${ui.speed}×"}")
    add(HudMenu.Sync to "Sync · ${if (ui.audioDelay == 0.0 && ui.subtitleDelay == 0.0) "In sync" else "Adjusted"}")
    add(HudMenu.SubtitleLook to "Subtitle look · size, color, position")
    if (ui.hasShowMemory && ui.partyLine == null) add(HudMenu.ThisShow to "Auto-skip in this show · ${ShowAutoSkipChoices.first { it.first == ui.showAutoSkip }.second}")
}

/** What the Video button groups. */
internal fun pictureEntries(ui: HudUi): List<Pair<HudMenu, String>> = listOf(
    HudMenu.Quality to "Quality · ${ui.qualities.firstOrNull { it.selected }?.label ?: "Auto"}",
    HudMenu.Video to "Picture · fit, aspect and zoom",
)

/** The Video button's menu: with no qualities to choose from, there is nothing to group. */
internal fun pictureMenu(ui: HudUi): HudMenu = if (ui.qualities.isEmpty()) HudMenu.Video else HudMenu.Picture

private val PlaybackMenus = setOf(HudMenu.Playback, HudMenu.Subtitles, HudMenu.Speed, HudMenu.Sync, HudMenu.FindSubtitles, HudMenu.AiSubtitles, HudMenu.SubtitleLook, HudMenu.ThisShow)

/** The choices for a show's intros and recaps. */
internal val ShowAutoSkipChoices: List<Pair<Boolean?, String>> = listOf(null to "As in Settings", true to "Skip by themselves", false to "Show a Skip button")

/** Size, background, color and position, applied to the playing video as they change. */
@Composable
private fun SubtitleLookPanel(ui: HudUi, act: HudActions) {
    val look = dev.silage.app.playback.SubtitleLookRows
    Column(
        Modifier.width(400.dp).clip(RoundedCornerShape(14.dp)).background(Panel)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)).padding(vertical = 8.dp),
    ) {
        Text("SUBTITLE LOOK", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, letterSpacing = 2.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        look.labels(ui.subtitleLook).forEachIndexed { i, label ->
            MenuRow(label, false, highlighted = i == ui.menuIndex && ui.focus != null) { act.subtitleLook(i, 1) }
        }
        Text("Shows on the playing subtitles. Files with their own styling keep it, apart from the size.", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
    }
}
private val PictureMenus = setOf(HudMenu.Picture, HudMenu.Quality, HudMenu.Video)

/** The server's qualities for this video, each with its bitrate; picking one restarts playback where it is. */
@Composable
private fun QualityPanel(ui: HudUi, act: HudActions) {
    Column(
        Modifier.width(400.dp).heightIn(max = 620.dp).clip(RoundedCornerShape(14.dp)).background(Panel)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
    ) {
        Text("Quality", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, letterSpacing = 2.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        ui.qualities.forEachIndexed { i, q ->
            MenuRow(q.label, q.selected, highlighted = i == ui.menuIndex && ui.focus != null, detail = q.detail) { act.quality(q.id) }
        }
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
private fun InfoPanel(rows: List<Pair<String, String>>, onCopy: () -> Unit, modifier: Modifier) {
    Column(
        modifier.width(460.dp).clip(RoundedCornerShape(14.dp)).background(Panel)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PLAYBACK INFO", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            // Copy details (C): plain text for a bug report or a forum post.
            Text("Copy details  C", color = Text, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.10f))
                    .clickable(onClick = onCopy).padding(horizontal = 10.dp, vertical = 5.dp))
        }
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
