@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.silage.app.spike

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.silage.app.splash.SplashScreen
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/** `./gradlew :app:runSplashRender -Pout=DIR`: frames of the startup animation at fixed times (still loading). */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/splash").apply { mkdirs() }
    val scene = ImageComposeScene(960, 540, Density(0.5f)) { SplashScreen(ready = false) {} }
    var t = 0L
    for (ms in listOf(0L, 500, 1000, 1400, 1800, 2200, 2600, 3200, 3800, 4400, 5000, 5600)) {
        while (t < ms) { t += 16; scene.render(t * 1_000_000) }
        File(out, "f%05d.png".format(ms)).writeBytes(scene.render(t * 1_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
    scene.close()
    println("wrote ${out.absolutePath}")
}
