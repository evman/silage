package dev.silage.app.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.ui.Silage
import dev.silage.app.ui.isActive
import org.siloserver.silo.network.apiv2.CatalogLetterV2

/** The bar's letters: `#` holds digits, symbols and other scripts. */
internal val LetterBarLetters: List<String> = listOf("#") + ('A'..'Z').map { it.toString() }

/** A–Z down the page's right edge. A letter with no titles is shown dimmed and does nothing. */
@Composable
internal fun LetterBar(letters: List<CatalogLetterV2>, current: String?, modifier: Modifier = Modifier, onJump: (CatalogLetterV2) -> Unit) {
    val byLetter = remember(letters) { letters.associateBy { it.letter } }
    Column(modifier.width(30.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        LetterBarLetters.forEach { letter ->
            val entry = byLetter[letter]
            val interaction = remember(letter) { MutableInteractionSource() }
            val active = interaction.isActive() && entry != null
            Box(
                Modifier.weight(1f, fill = false).heightIn(max = 26.dp).fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).clip(CircleShape)
                    .background(if (active) Color.White else Color.Transparent)
                    .then(if (entry != null) Modifier.hoverable(interaction).clickable(interaction, indication = null) { onJump(entry) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(letter, fontSize = 13.sp, fontWeight = if (letter == current) FontWeight.Bold else FontWeight.Medium,
                    color = when {
                        active -> Color.Black
                        entry == null -> Silage.Text3.copy(alpha = 0.4f)
                        letter == current -> Silage.Amber
                        else -> Silage.Text2
                    })
            }
        }
    }
}
