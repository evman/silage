package dev.silage.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** How much of the visible width one arrow click scrolls. */
internal const val ArrowScrollFraction = 0.8f

/**
 * A horizontal row with mouse-clickable ‹ › buttons at its edges, shown while
 * the pointer is over the row and only when there is more to see that way.
 * The buttons aren't focusable, so keyboard and remote navigation is unchanged.
 * [arrowOffsetY] moves the buttons up from the row's centre (e.g. to centre
 * them on the artwork rather than artwork plus caption).
 */
@Composable
fun ArrowRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    arrowOffsetY: Dp = 0.dp,
    content: LazyListScope.() -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val scope = rememberCoroutineScope()
    fun page(direction: Int) {
        scope.launch { state.animateScrollBy(direction * state.layoutInfo.viewportSize.width * ArrowScrollFraction) }
    }
    Box(modifier.hoverable(hover)) {
        LazyRow(state = state, contentPadding = contentPadding, horizontalArrangement = horizontalArrangement, content = content)
        val arrowModifier = Modifier.padding(bottom = arrowOffsetY * 2)
        AnimatedVisibility(hovered && state.canScrollBackward, Modifier.align(Alignment.CenterStart).then(arrowModifier), enter = fadeIn(), exit = fadeOut()) {
            RowArrowButton(forward = false) { page(-1) }
        }
        AnimatedVisibility(hovered && state.canScrollForward, Modifier.align(Alignment.CenterEnd).then(arrowModifier), enter = fadeIn(), exit = fadeOut()) {
            RowArrowButton(forward = true) { page(1) }
        }
    }
}

@Composable
private fun RowArrowButton(forward: Boolean, onPress: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val label = if (forward) "Scroll right" else "Scroll left"
    Box(
        Modifier.padding(horizontal = 12.dp).size(56.dp).clip(CircleShape)
            .background(if (hovered) Color.White else Color.Black.copy(alpha = 0.72f))
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand)
            // A tap handler rather than clickable(): clickable would make the arrow a focus stop.
            .pointerInput(onPress) { detectTapGestures { onPress() } }
            .semantics { contentDescription = label; role = Role.Button; onClick(label) { onPress(); true } },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (forward) Icons.Filled.ChevronRight else Icons.Filled.ChevronLeft, null,
            tint = if (hovered) Color.Black else Color.White, modifier = Modifier.size(36.dp))
    }
}
