package dev.silage.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.toBitmap

/**
 * Avatars already shown this session, by address. Every page draws its own sidebar; drawing a
 * known avatar straight from here, rather than loading it again, keeps it from flashing its
 * initial on each page change.
 */
internal object AvatarCache {
    private val images = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > 32
    }
    fun get(url: String): ImageBitmap? = synchronized(images) { images[url] }
    fun put(url: String, image: ImageBitmap) { synchronized(images) { images[url] = image } }
}

/**
 * The initial shown under an avatar: the name's whole first character, so a name starting with
 * an emoji or other character outside the basic range doesn't show half of it (drawn as "?").
 */
internal fun avatarInitial(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    return String(Character.toChars(trimmed.codePointAt(0))).uppercase()
}

/** [image] as a bitmap to keep: a drawn (SVG) avatar is drawn at least [MinSide] pixels across, so it stays sharp. */
private fun avatarBitmap(image: coil3.Image): ImageBitmap {
    if (image is coil3.BitmapImage) return image.bitmap.asComposeImageBitmap()
    val side = minOf(image.width, image.height).coerceAtLeast(1)
    val scale = (MinSide + side - 1) / side
    return image.toBitmap(image.width.coerceAtLeast(1) * scale.coerceAtLeast(1), image.height.coerceAtLeast(1) * scale.coerceAtLeast(1)).asComposeImageBitmap()
}

private const val MinSide = 192

/** Keep a visible initial underneath loading/absent/failed avatars, never a blank circle. */
@Composable
fun ProfileAvatar(name: String, url: String?, modifier: Modifier = Modifier, initialSize: Int = 22) {
    Box(modifier.clip(CircleShape).background(Silage.Elevated), contentAlignment = Alignment.Center) {
        val known = url?.takeIf { it.isNotBlank() }?.let(AvatarCache::get)
        if (known != null) {
            Image(known, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(avatarInitial(name), color = Silage.Text, fontSize = initialSize.sp, fontWeight = FontWeight.SemiBold)
            if (!url.isNullOrBlank()) {
                AsyncImage(model = url, contentDescription = name, imageLoader = Images.loader, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = { state -> runCatching { AvatarCache.put(url, avatarBitmap(state.result.image)) } })
            }
        }
    }
}
