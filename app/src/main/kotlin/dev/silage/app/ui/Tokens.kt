package dev.silage.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Silage's design tokens (spec §5): OLED black, near-white text, Silo's amber accent. */
object Silage {
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF0A0A0A)
    val Elevated = Color(0xFF15171C)
    val Text = Color(0xFFEDEDED)
    val Text2 = Color(0xFFEDEDED).copy(alpha = 0.60f)
    val Text3 = Color(0xFFEDEDED).copy(alpha = 0.38f)
    val Hairline = Color.White.copy(alpha = 0.12f)
    val Amber = Color(0xFFE8B86E)
    val Error = Color(0xFFB00020)
}

/** Whether a component is focused (keyboard) or hovered (mouse): both get the focus look. */
@Composable
fun MutableInteractionSource.isActive(): Boolean {
    val focused by collectIsFocusedAsState()
    val hovered by collectIsHoveredAsState()
    return focused || hovered
}

/** The focused-card look: scale to 1.06 and a 3 dp white ring. */
@Composable
fun Modifier.focusCard(active: Boolean, shape: Shape): Modifier {
    val scale by animateFloatAsState(if (active) 1.06f else 1f, tween(150))
    return this.scale(scale).then(if (active) Modifier.border(3.dp, Color.White, shape) else Modifier)
}
