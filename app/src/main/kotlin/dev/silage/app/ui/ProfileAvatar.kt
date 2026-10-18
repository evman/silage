package dev.silage.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Keep a visible initial underneath loading/absent/failed avatars, never a blank circle. */
@Composable
fun ProfileAvatar(name: String, url: String?, modifier: Modifier = Modifier, initialSize: Int = 22) {
    Box(modifier.clip(CircleShape).background(Silage.Elevated), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = Silage.Text,
            fontSize = initialSize.sp, fontWeight = FontWeight.SemiBold)
        if (!url.isNullOrBlank()) Artwork(url, null, name, Modifier.fillMaxSize(), showTitleTile = false)
    }
}
