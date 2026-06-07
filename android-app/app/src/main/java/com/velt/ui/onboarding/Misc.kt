package com.velt.ui.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velt.ui.theme.Velt

@Composable
fun OtpBoxes(value: String, cursor: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        repeat(6) { i ->
            val ch = value.getOrNull(i)?.toString()
            val filled = ch != null
            val active = i == cursor && !filled
            val borderColor = when {
                filled -> Velt.Cyan
                active -> Velt.CyanLight
                else -> Velt.Border
            }
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(50.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Velt.Surf)
                    .border(1.5.dp, borderColor, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = ch ?: if (active) "_" else "",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (filled) Velt.Cyan else Velt.T1
                )
            }
        }
    }
}

@Composable
fun WarnBox(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Velt.Amber.copy(alpha = 0.06f))
            .border(1.dp, Velt.Amber.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Filled.Lock, null, tint = Velt.Amber, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Text(text, fontSize = 11.sp, color = Velt.T2, lineHeight = 15.sp)
    }
}

private data class Confetto(
    val xFraction: Float,
    val color: Color,
    val durationMs: Int,
    val delayMs: Int,
    val w: Int,
    val h: Int
)

private val confetti = listOf(
    Confetto(0.08f, Color(0xFF00D4C8), 2500, 0, 6, 6),
    Confetto(0.20f, Color(0xFF22D45E), 3000, 300, 4, 8),
    Confetto(0.35f, Color(0xFFF5A623), 2800, 100, 5, 5),
    Confetto(0.50f, Color(0xFF00D4C8), 3200, 500, 6, 4),
    Confetto(0.65f, Color(0xFF22D45E), 2600, 200, 4, 4),
    Confetto(0.78f, Color(0xFF8B5CF6), 3000, 400, 5, 5),
    Confetto(0.90f, Color(0xFFFF6B6B), 2700, 150, 6, 3)
)

@Composable
fun ConfettiOverlay() {
    val t = rememberInfiniteTransition(label = "confetti")
    Box(modifier = Modifier.fillMaxSize()) {
        confetti.forEach { c ->
            val progress by t.animateFloat(
                0f, 1f,
                infiniteRepeatable(tween(c.durationMs, delayMillis = c.delayMs, easing = LinearEasing), RepeatMode.Restart),
                label = "cf"
            )
            Box(
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            val x = (constraints.maxWidth * c.xFraction).toInt()
                            val y = (-20 + progress * (constraints.maxHeight + 40)).toInt()
                            placeable.place(x, y)
                        }
                    }
                    .rotate(progress * 360f)
                    .size(c.w.dp, c.h.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(c.color.copy(alpha = 1f - progress))
            )
        }
    }
}
