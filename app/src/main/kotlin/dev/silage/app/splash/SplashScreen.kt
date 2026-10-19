package dev.silage.app.splash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.silage.app.ui.SilageLogo

/**
 * The startup animation: the logo fades in soft and grey with a white halo,
 * brightens to crisp white, and an amber glow breathes behind it until
 * [ready] and at least four seconds have elapsed; then the splash fades
 * away over the app and calls [onFinished].
 */
@Composable
fun SplashScreen(ready: Boolean, onFinished: () -> Unit) {
    val timeline = remember { SplashTimeline() }
    val isReady by rememberUpdatedState(ready)
    var frame by remember { mutableStateOf(timeline.frame(0, null)) }
    LaunchedEffect(Unit) {
        val t0 = withFrameMillis { it }
        var readyAt: Long? = null
        while (true) {
            val now = withFrameMillis { it } - t0
            if (isReady && readyAt == null) readyAt = now
            frame = timeline.frame(now, readyAt)
            if (frame.finished) break
        }
        onFinished()
    }

    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = frame.screenAlpha }.background(Color.Black)) {
        // The layers are drawn in a box larger than the logo so the glows have room to spread.
        val box = maxHeight * 0.72f
        // Logo and name are centered as a group: the logo sits a little above center.
        Box(Modifier.align(Alignment.Center).offset(y = -maxHeight * 0.07f).size(box).graphicsLayer { scaleX = frame.scale; scaleY = frame.scale }) {
            // 1 while the logo is soft grey, 0 once it is crisp white.
            val soft = ((1 - frame.brightness) / 0.28f).coerceIn(0f, 1f)
            // A deeper orange than the UI's amber accent, as in the concept video.
            val amber = Glow.copy(alpha = (frame.amber * frame.logoAlpha).coerceIn(0f, 1f))
            val halo = Color.White.copy(alpha = (0.15f + 0.35f * soft) * frame.logoAlpha * (1 - 0.5f * frame.amber))
            val logo = lerp(Color.White, Color(0xFFB8BCC2), soft).copy(alpha = frame.logoAlpha)
            // A wide soft bloom plus a tighter one, as in the concept.
            Canvas(Modifier.fillMaxSize().blur(box * 0.2f, BlurredEdgeTreatment.Unbounded)) { drawLogo(amber, grow = 1.08f) }
            Canvas(Modifier.fillMaxSize().blur(box * 0.2f, BlurredEdgeTreatment.Unbounded)) { drawLogo(amber, grow = 1.08f) }
            Canvas(Modifier.fillMaxSize().blur(box * 0.07f, BlurredEdgeTreatment.Unbounded)) { drawLogo(amber.copy(alpha = amber.alpha * 0.9f)) }
            Canvas(Modifier.fillMaxSize().blur(box * 0.035f, BlurredEdgeTreatment.Unbounded)) { drawLogo(halo) }
            Canvas(Modifier.fillMaxSize()) {
                // Keep the play cutout dark, with a hint of the glow's warmth.
                drawLogo(Color.Black.copy(alpha = 0.78f * frame.logoAlpha), play = true)
                drawLogo(logo)
            }
        }
        Name(frame, Modifier.align(Alignment.Center).offset(y = maxHeight * 0.21f))
    }
}

private val Glow = Color(0xFFE0883A)

/** "SILAGE" and the tagline, under the logo. */
@Composable
private fun Name(frame: SplashFrame, modifier: Modifier) {
    Column(
        modifier.graphicsLayer { alpha = frame.textAlpha; translationY = frame.textRise * 24.dp.toPx() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // letterSpacing leaves trailing space after the last letter; shift by half of it to look centered.
        Text("SILAGE", color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Light, letterSpacing = 22.sp, modifier = Modifier.offset(x = 11.dp))
        Spacer(Modifier.height(14.dp))
        Text("An unofficial Silo client", color = Color.White.copy(alpha = 0.55f), fontSize = 18.sp, fontWeight = FontWeight.Light, letterSpacing = 3.sp)
    }
}

/** The logo (or just its play triangle) at 55% of the canvas (times [grow]), centered. */
private fun DrawScope.drawLogo(color: Color, play: Boolean = false, grow: Float = 1f) {
    val s = Size(size.width * 0.55f * grow, size.height * 0.55f * grow)
    translate((size.width - s.width) / 2, (size.height - s.height) / 2) {
        drawPath(if (play) SilageLogo.playPath(s) else SilageLogo.path(s), color)
    }
}
