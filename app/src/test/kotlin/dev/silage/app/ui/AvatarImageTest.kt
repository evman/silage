package dev.silage.app.ui

import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.PlatformContext
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AvatarImageTest {
    @Test fun absolutePngAndCrossOriginRedirectNeverReceiveAccountCredentials() = runBlocking {
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var received = 0
        val image = java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val output = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "png", output)
        target.createContext("/image.png") { e ->
            assertNull(e.requestHeaders.getFirst("Authorization"))
            assertNull(e.requestHeaders.getFirst("X-Profile-Token"))
            assertNull(e.requestHeaders.getFirst("Cookie"))
            received++
            val bytes = output.toByteArray()
            e.responseHeaders.add("Content-Type", "image/png")
            e.sendResponseHeaders(200, bytes.size.toLong())
            e.responseBody.use { it.write(bytes) }
        }
        source.createContext("/redirect") { e ->
            assertNull(e.requestHeaders.getFirst("Authorization"))
            e.responseHeaders.add("Location", "http://127.0.0.1:${target.address.port}/image.png?dummy_token=fixture")
            e.sendResponseHeaders(302, -1); e.close()
        }
        target.start(); source.start()
        try {
            for (url in listOf("http://127.0.0.1:${target.address.port}/image.png", "http://127.0.0.1:${source.address.port}/redirect")) {
                val result = Images.loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(url).build())
                assertIs<SuccessResult>(result)
                assertEquals(8, result.image.width)
            }
            assertEquals(2, received)
        } finally { source.stop(0); target.stop(0) }
    }

    @Test fun serverSuppliedSvgAvatarDecodesWithoutCredentials() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        server.createContext("/avatar.svg") { exchange ->
            assertNull(exchange.requestHeaders.getFirst("Authorization"))
            assertNull(exchange.requestHeaders.getFirst("X-Profile-Token"))
            requests++
            val bytes = """<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><rect width="64" height="64" fill="#ff0000"/></svg>""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "image/svg+xml")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val url = org.siloserver.silo.network.resolveArtworkUrl("/avatar.svg?dummy_signature=fixture", "http://127.0.0.1:${server.address.port}")
            val result = Images.loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(url).build())
            assertIs<SuccessResult>(result, (result as? coil3.request.ErrorResult)?.throwable?.toString())
            assertEquals(64, result.image.width)
            assertEquals(1, requests)
        } finally { server.stop(0) }
    }
}
