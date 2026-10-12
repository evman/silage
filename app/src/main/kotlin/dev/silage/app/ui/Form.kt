package dev.silage.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The Silage wordmark: plain letter-spaced text (no logo marks). */
@Composable
fun Wordmark() {
    Text("SILAGE", color = Silage.Text, fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = 14.sp)
}

/** A large single-line field with a white focus ring. */
@Composable
fun SilageField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    onDone: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier) {
        Text(label, color = Silage.Text2, fontSize = 17.sp, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        Box(
            Modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f))
                .border(3.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(16.dp)).padding(horizontal = 22.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value, onChange, singleLine = true, interactionSource = interaction,
                textStyle = TextStyle(color = Color.White, fontSize = 23.sp),
                cursorBrush = SolidColor(Silage.Amber),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The one primary action on a screen: white when focused or hovered. */
@Composable
fun PrimaryButton(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive() && enabled
    Box(
        modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(32.dp))
            .background(if (active) Color.White else Color.White.copy(alpha = if (enabled) 0.16f else 0.06f))
            .hoverable(interaction).clickable(interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (active) Color.Black else if (enabled) Color.White else Silage.Text3, fontSize = 21.sp, fontWeight = FontWeight.SemiBold) }
}

/** A quiet secondary action. */
@Composable
fun TextLink(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    Box(
        Modifier.clip(RoundedCornerShape(24.dp)).background(if (active) Color.White else Color.Transparent)
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick).padding(horizontal = 22.dp, vertical = 12.dp),
    ) { Text(label, color = if (active) Color.Black else Silage.Text2, fontSize = 18.sp) }
}

/** A centered column on black, the frame for first-run screens. */
@Composable
fun FormFrame(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Silage.Black), contentAlignment = Alignment.Center) {
        Column(Modifier.width(600.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp), content = content)
    }
}
