package dev.silage.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class AvatarRenderTest {
    @Test fun svgActuallyRendersRedPixelsInsteadOfBlankCircle() = runComposeUiTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/avatar") { exchange ->
            val bytes = """<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><rect width="64" height="64" fill="red"/></svg>""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "image/svg+xml")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            setContent { MaterialTheme { ProfileAvatar("Nova", "http://127.0.0.1:${server.address.port}/avatar", Modifier.size(160.dp).testTag("avatar"), 64) } }
            waitUntil(timeoutMillis = 10000) {
                val pixels = onNodeWithTag("avatar").captureToImage().toPixelMap()
                val center = pixels[pixels.width / 2, pixels.height / 2]
                center.red > 0.9f && center.green < 0.1f
            }
            val pixels = onNodeWithTag("avatar").captureToImage().toPixelMap()
            val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                val c = pixels[x,y]
                image.setRGB(x,y, (255 shl 24) or ((c.red * 255).toInt() shl 16) or ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt())
            }
            val root = if (Files.exists(Path.of("src/main"))) Path.of("..") else Path.of(".")
            ImageIO.write(image, "png", root.resolve("avatar-render-evidence.png").toFile())
        } finally { server.stop(0) }
    }
}
