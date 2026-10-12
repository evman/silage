@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.silage.app.spike

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.translate
import dev.silage.app.ui.SilageLogo
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/** `./gradlew :app:runLogoRender -Pout=DIR`: the logo on black (1440) and the app icon (512, 256, 128). */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/logo").apply { mkdirs() }
    fun render(size: Int, file: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        val scene = ImageComposeScene(size, size, Density(1f), content = content)
        val png = scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
        File(out, file).writeBytes(png)
        scene.close()
    }
    render(1440, "logo-1440.png") {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // Same placement as the design frame: the path is drawn in 1440 units.
            Canvas(Modifier.fillMaxSize()) {
                val p = SilageLogo.path(androidx.compose.ui.geometry.Size(654f, 888f))
                translate(392f, 260f) { drawPath(p, Color.White) }
            }
        }
    }
    for (s in listOf(512, 256, 128)) render(s, "icon-$s.png") {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape((s * 0.22f).dp)).background(Color(0xFF0B0C0F)).padding((s * 0.17f).dp)) {
            Canvas(Modifier.fillMaxSize()) { drawPath(SilageLogo.path(size), Color.White) }
        }
    }
    println("wrote ${out.absolutePath}")
}
