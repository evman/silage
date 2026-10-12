package dev.silage.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.ui.Silage
import dev.silage.app.ui.SilageLogo

/** Shown instead of the app when libmpv is missing or too old. */
@Composable
fun MpvMissingScreen(problem: MpvProblem) {
    Box(Modifier.fillMaxSize().background(Silage.Black), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 900.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Canvas(Modifier.size(140.dp)) { drawPath(SilageLogo.path(size), Color.White) }
            Text(problem.title, color = Silage.Text, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
            Text(problem.body, color = Silage.Text2, fontSize = 22.sp, textAlign = TextAlign.Center)
            Text("Install it, then start Silage again:", color = Silage.Text2, fontSize = 20.sp)
            SelectionContainer {
                Text(
                    problem.command, color = Color.White, fontSize = 24.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Silage.Elevated).padding(horizontal = 28.dp, vertical = 16.dp),
                )
            }
        }
    }
}
