package com.bobstream.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bobstream.app.ui.theme.NeonCyan
import com.bobstream.app.ui.theme.NeonPurple
import com.bobstream.app.ui.theme.PrimaryTeal

@Composable
fun BobStreamLogo(
    modifier: Modifier = Modifier,
    iconSize: Dp = 36.dp,
    showText: Boolean = true
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Canvas(modifier = Modifier.size(iconSize)) {
            val w = size.width
            val h = size.height

            val gradient = Brush.linearGradient(
                colors = listOf(NeonCyan, NeonPurple),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )

            // Outer dynamic stream wave
            val arcPath = Path().apply {
                moveTo(w * 0.2f, h * 0.3f)
                cubicTo(w * 0.45f, h * 0.1f, w * 0.75f, h * 0.2f, w * 0.85f, h * 0.45f)
                cubicTo(w * 0.95f, h * 0.7f, w * 0.75f, h * 0.9f, w * 0.4f, h * 0.88f)
            }
            drawPath(
                path = arcPath,
                brush = gradient,
                style = Stroke(width = w * 0.09f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )

            // Inner play triangle
            val trianglePath = Path().apply {
                moveTo(w * 0.42f, h * 0.35f)
                lineTo(w * 0.68f, h * 0.5f)
                lineTo(w * 0.42f, h * 0.65f)
                close()
            }
            drawPath(
                path = trianglePath,
                brush = Brush.horizontalGradient(listOf(NeonCyan, PrimaryTeal))
            )
        }

        if (showText) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "BOB",
                    color = com.bobstream.app.ui.theme.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.SansSerif
                )
                Text(
                    text = "STREAM",
                    color = NeonCyan,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
            }
        }
    }
}
