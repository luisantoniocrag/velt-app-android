package com.velt.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.velt.ui.theme.Velt

@Composable
fun ScanRing(
    modifier: Modifier = Modifier,
    ringColor: Color = Velt.Cyan,
    ringAlpha: Float = 1f,
    showPulses: Boolean = true,
    outerSize: androidx.compose.ui.unit.Dp = 148.dp,
    innerSize: androidx.compose.ui.unit.Dp = 108.dp,
    content: @Composable () -> Unit
) {
    val t = rememberInfiniteTransition(label = "scan")
    val breathe by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe"
    )
    val wave1 by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(3000, delayMillis = 900, easing = LinearEasing)), label = "w1"
    )
    val wave2 by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(3000, delayMillis = 1800, easing = LinearEasing)), label = "w2"
    )

    Box(
        modifier = modifier
            .size(outerSize)
            .drawBehind {
                if (showPulses) {
                    drawPulse(ringColor, wave1, baseInset = 16.dp.toPx(), maxAlpha = 0.3f)
                    drawPulse(ringColor, wave2, baseInset = 32.dp.toPx(), maxAlpha = 0.15f)
                }
                val r = size.minDimension / 2f
                val haloRadius = r * (1.08f + breathe * 0.30f)
                val ringFrac = (r / haloRadius).coerceIn(0f, 1f)
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to ringColor.copy(alpha = 0.012f * breathe),
                        ringFrac * 0.65f to ringColor.copy(alpha = 0.03f * breathe),
                        ringFrac to ringColor.copy(alpha = 0.07f * breathe),
                        1f to Color.Transparent,
                        center = center,
                        radius = haloRadius
                    ),
                    radius = haloRadius,
                    center = center
                )
            }
            .clip(CircleShape)
            .border(1.5.dp, ringColor.copy(alpha = ringAlpha), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(CircleShape)
                .background(Velt.Cyan.copy(alpha = 0.04f))
                .border(1.dp, Velt.CyanDark, CircleShape),
            contentAlignment = Alignment.Center
        ) { content() }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPulse(
    color: Color,
    progress: Float,
    baseInset: Float,
    maxAlpha: Float
) {
    val scale = 0.88f + progress * (1.07f - 0.88f)
    val alpha = maxAlpha * (1f - progress)
    val baseRadius = size.minDimension / 2f + baseInset
    drawCircle(
        color = color.copy(alpha = alpha),
        radius = baseRadius * scale,
        style = Stroke(width = 1.dp.toPx())
    )
}

@Composable
fun StepChips(current: Int, total: Int = 5, done: Boolean = false) {
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            val color by animateColorAsState(
                when {
                    done -> Velt.Cyan
                    i < current -> Velt.Cyan
                    i == current -> Velt.CyanLight
                    else -> Velt.Border
                },
                label = "chip"
            )
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}
