package dev.silage.app.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.ui.isActive

/** The pages that sit on top of another (a title, a person, a collection of a library): they offer a visible Back. */
internal fun showsBackButton(route: Route): Boolean =
    route is Route.Detail || route is Route.RequestDetail || route is Route.Person || route is Route.Collection

/** Of those, the ones drawn inside the sidebar shell, which need room above the page and to clear the rail. */
internal fun backButtonSharesShell(route: Route): Boolean = route is Route.Person || route is Route.Collection

/** A pill with an arrow and "Back": clearly a button, white while hovered or focused. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier.height(52.dp).clip(shape)
            .background(if (active) Color.White else Color.Black.copy(alpha = 0.6f))
            .border(1.dp, Color.White.copy(alpha = if (active) 0f else 0.25f), shape)
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(start = 14.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = if (active) Color.Black else Color.White, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(8.dp))
        Text("Back", color = if (active) Color.Black else Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    }
}
