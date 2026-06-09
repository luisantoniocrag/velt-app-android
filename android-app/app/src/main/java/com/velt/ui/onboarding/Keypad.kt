package com.velt.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velt.ui.theme.Velt
import kotlinx.coroutines.launch

fun keypadAreaHeight(available: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    (available - 330.dp).coerceIn(180.dp, 360.dp)

@Composable
fun CircularKeypad(
    modifier: Modifier = Modifier,
    onKey: (String) -> Unit,
    onDelete: () -> Unit
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9")
    )
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val minHGap = 8.dp
        val vGap = 10.dp
        val byWidth = (maxWidth - minHGap * 2) / 3
        val byHeight = (maxHeight - vGap * 3) / 4
        val cell = minOf(byWidth, byHeight, 120.dp).coerceAtLeast(40.dp)
        val zeroWidth = (maxWidth + cell) / 2
        val digitSize = (cell.value * 0.42f).sp
        val deleteSize = cell * 0.4f

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(vGap)
        ) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    row.forEach { digit ->
                        KeypadCell(width = cell, height = cell, onTap = { onKey(digit) }) {
                            Text(digit, fontSize = digitSize, fontWeight = FontWeight.Light, color = Velt.T1)
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                KeypadCell(
                    width = zeroWidth,
                    height = cell,
                    shape = RoundedCornerShape(cell / 2),
                    onTap = { onKey("0") }
                ) {
                    Text("0", fontSize = digitSize, fontWeight = FontWeight.Light, color = Velt.T1)
                }
                KeypadCell(width = cell, height = cell, onTap = onDelete) {
                    Icon(
                        Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Borrar",
                        tint = Velt.Cyan,
                        modifier = Modifier.size(deleteSize)
                    )
                }
            }
        }
    }
}

@Composable
private fun KeypadCell(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    shape: Shape = CircleShape,
    onTap: () -> Unit,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScopeKp()
    var pressed by remember { mutableStateOf(false) }
    var bloomCenter by remember { mutableStateOf(Offset.Zero) }
    val bloom = remember { Animatable(1f) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.92f else 1f, label = "ck")

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .scale(scale)
            .clip(shape)
            .background(if (pressed) Color(0xFF1F2240) else Velt.Card)
            .drawBehind {
                if (bloom.value < 1f) {
                    val radius = 40.dp.toPx() * (0.001f + bloom.value * 2.5f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Velt.Cyan.copy(alpha = 0.2f * (1f - bloom.value)),
                                Color.Transparent
                            ),
                            center = bloomCenter,
                            radius = radius
                        ),
                        radius = radius,
                        center = bloomCenter
                    )
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        pressed = true
                        bloomCenter = offset
                        scope.launch {
                            bloom.snapTo(0f)
                            bloom.animateTo(1f, tween(500))
                        }
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onTap() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
private fun rememberCoroutineScopeKp() = androidx.compose.runtime.rememberCoroutineScope()
