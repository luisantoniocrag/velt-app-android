package com.velt.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

private data class Key(val digit: String, val letters: String? = null)

@Composable
fun CircularKeypad(
    modifier: Modifier = Modifier,
    onKey: (String) -> Unit,
    onDelete: () -> Unit
) {
    val rows = listOf(
        listOf(Key("1"), Key("2", "ABC"), Key("3", "DEF")),
        listOf(Key("4", "GHI"), Key("5", "JKL"), Key("6", "MNO")),
        listOf(Key("7", "PRS"), Key("8", "TUV"), Key("9", "WXY"))
    )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { key ->
                    KeypadCell(onTap = { onKey(key.digit) }) {
                        Text(key.digit, fontSize = 26.sp, fontWeight = FontWeight.Light, color = Velt.T1)
                        key.letters?.let {
                            Text(it, fontSize = 7.5.sp, color = Velt.T3, letterSpacing = 1.sp)
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KeypadCell(width = 154.dp, shape = RoundedCornerShape(36.dp), onTap = { onKey("0") }) {
                Text("0", fontSize = 26.sp, fontWeight = FontWeight.Light, color = Velt.T1)
            }
            KeypadCell(onTap = onDelete) {
                Icon(
                    Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "Borrar",
                    tint = Velt.Cyan,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun KeypadCell(
    width: androidx.compose.ui.unit.Dp = 72.dp,
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
            .height(72.dp)
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
