@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.silage.app.spike

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.silage.app.splash.SplashScreen
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * `./gradlew :app:runSplashRender -Pout=DIR [-Pfps=30] [-Pwidth=960]`: every
 * frame of the startup animation, with the app becoming ready at 3.4 s so the
 * exit plays, until the screen is black again (for the README GIF).
 */
fun main(args: Array<String>) {
    val out = File(args.getOrNull(0) ?: "build/splash").apply { mkdirs() }
    val fps = args.getOrNull(1)?.toIntOrNull() ?: 30
    val width = args.getOrNull(2)?.toIntOrNull() ?: 960
    val height = width * 9 / 16
    val ready = mutableStateOf(false)
    var finished = false
    val scene = ImageComposeScene(width, height, Density(height / 1080f)) { SplashScreen(ready = ready.value) { finished = true } }
    val frameMs = 1000.0 / fps
    var i = 0
    var blackFrames = 0
    while (blackFrames < fps / 3 && i < fps * 20) {
        val ms = i * frameMs
        if (ms >= 3400) ready.value = true
        val image = scene.render((ms * 1_000_000).toLong())
        File(out, "f%05d.png".format(i)).writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        if (finished) blackFrames++
        i++
    }
    scene.close()
    println("wrote $i frames to ${out.absolutePath}")
}
