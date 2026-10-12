package dev.silage.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import dev.silage.app.XdgPaths
import okio.Path.Companion.toPath
import java.awt.image.BufferedImage

sealed interface ArtworkFallback {
    data class Image(val url: String) : ArtworkFallback
    data class Blur(val thumbhash: String) : ArtworkFallback
    data class TitleTile(val title: String) : ArtworkFallback
}

/** What a card shows: the image when there is one, else its blur, else a titled tile. */
fun artworkFallback(url: String?, thumbhash: String?, title: String): ArtworkFallback = when {
    !url.isNullOrBlank() -> ArtworkFallback.Image(url)
    !thumbhash.isNullOrBlank() -> ArtworkFallback.Blur(thumbhash)
    else -> ArtworkFallback.TitleTile(title)
}

/** The app-wide image loader: memory cache plus a 512 MB disk cache. Image URLs carry no credentials. */
object Images {
    val loader: ImageLoader by lazy {
        ImageLoader.Builder(PlatformContext.INSTANCE)
            .components { add(KtorNetworkFetcherFactory()) }
            .memoryCache { MemoryCache.Builder().maxSizeBytes(384L shl 20).build() }
            .diskCache { DiskCache.Builder().directory(XdgPaths.cache.resolve("images").toString().toPath()).maxSizeBytes(512L shl 20).build() }
            .crossfade(180)
            .build()
    }

    private val blurs = object : LinkedHashMap<String, ImageBitmap?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>) = size > 512
    }

    fun blur(hash: String): ImageBitmap? = synchronized(blurs) {
        blurs.getOrPut(hash) {
            Thumbhash.decode(hash)?.let { t ->
                BufferedImage(t.width, t.height, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, t.width, t.height, t.argb, 0, t.width) }
                    .toComposeImageBitmap()
            }
        }
    }
}

/**
 * Artwork with a blur-up: the thumbhash (or a titled tile) underneath, the
 * real image crossfading in on top once it loads.
 */
@Composable
fun Artwork(
    url: String?,
    thumbhash: String?,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    showTitleTile: Boolean = true,
) {
    Box(modifier.background(Silage.Elevated)) {
        val blur = remember(thumbhash) { thumbhash?.takeIf { it.isNotBlank() }?.let(Images::blur) }
        when {
            blur != null -> Image(blur, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            showTitleTile && url.isNullOrBlank() -> Text(
                title, color = Silage.Text2, fontSize = 18.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        }
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = title, imageLoader = Images.loader, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}
