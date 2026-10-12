@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.silage.app.spike

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sun.jna.Memory
import dev.silage.player.MpvPlayer
import dev.silage.player.PlayerEvent
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

private const val W = 1920
private const val H = 1080
private const val SECONDS = 20

@Composable
private fun SpikeBar(progress: Float) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .padding(horizontal = 96.dp, vertical = 64.dp),
        ) {
            Text("Overlay spike — Compose → mpv", color = Color(0xFFEDEDED), fontSize = 40.sp)
            Box(Modifier.padding(top = 24.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(progress).height(8.dp).background(Color(0xFFEDEDED)))
            }
        }
    }
}

/**
 * Plays a video fullscreen with HDR-capable settings and animates a
 * Compose-drawn control bar over it at 60 Hz for 20 s, then prints timing.
 * Usage: ./gradlew :app:runOverlaySpike -Pvideo=/path/to/4k-hdr.mkv
 */
fun main(args: Array<String>) = runBlocking {
    val video = args.firstOrNull() ?: error("pass -Pvideo=/path/to/file")
    val player = MpvPlayer(
        mapOf(
            "vo" to "gpu-next", "hwdec" to "auto-safe", "target-colorspace-hint" to "yes",
            "fullscreen" to "yes", "force-window" to "yes", "keep-open" to "yes",
        ),
    )
    player.load(video)
    withTimeout(15_000) { player.state.first { it.loaded } }

    val progress = mutableFloatStateOf(0f)
    val scene = ImageComposeScene(W, H, Density(1f)) { SpikeBar(progress.floatValue) }
    val bitmap = Bitmap().apply { allocPixels(ImageInfo(W, H, ColorType.BGRA_8888, ColorAlphaType.PREMUL)) }
    val native = Memory(W.toLong() * H * 4)
    val frameMs = ArrayList<Double>()

    val start = System.nanoTime()
    val frameNanos = 1_000_000_000L / 60
    var frame = 0L
    while (System.nanoTime() - start < SECONDS * 1_000_000_000L) {
        val t0 = System.nanoTime()
        progress.floatValue = ((t0 - start) / (SECONDS * 1e9)).toFloat()
        scene.render(t0).readPixels(bitmap)
        val bytes = bitmap.readPixels() ?: error("readPixels failed")
        native.write(0, bytes, 0, bytes.size)
        val dw = player.getProperty("osd-width")?.toIntOrNull()?.takeIf { it > 0 } ?: W
        val dh = player.getProperty("osd-height")?.toIntOrNull()?.takeIf { it > 0 } ?: H
        player.addOverlay(0, com.sun.jna.Pointer.nativeValue(native), W, H, dw, dh)
        frameMs += (System.nanoTime() - t0) / 1e6
        frame++
        val next = start + frame * frameNanos
        val sleep = next - System.nanoTime()
        if (sleep > 0) Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
    }
    frameMs.sort()
    val elapsed = (System.nanoTime() - start) / 1e9
    println("frames=%d fps=%.1f p50=%.1fms p95=%.1fms max=%.1fms".format(
        frameMs.size, frameMs.size / elapsed, frameMs[frameMs.size / 2], frameMs[(frameMs.size * 0.95).toInt()], frameMs.last()))
    println("video: " + player.getProperty("video-params/primaries") + " / " + player.getProperty("video-params/gamma") +
        "  hwdec=" + player.getProperty("hwdec-current") + "  dropped=" + player.getProperty("frame-drop-count"))
    scene.close(); player.close()
}
