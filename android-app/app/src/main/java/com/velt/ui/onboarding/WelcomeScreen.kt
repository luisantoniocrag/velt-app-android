package com.velt.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velt.R
import com.velt.ui.theme.Velt
import kotlinx.coroutines.launch

@Composable
fun WelcomeScreen(strings: OnboardingStrings, onFinish: () -> Unit) {
    val checkScale = remember { Animatable(0.3f) }
    val checkAlpha = remember { Animatable(0f) }
    val titleAlpha = remember { Animatable(0f) }
    val subAlpha = remember { Animatable(0f) }
    val pillsAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            kotlinx.coroutines.delay(200)
            launch { checkAlpha.animateTo(1f, tween(150)) }
            checkScale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow))
        }
        launch { kotlinx.coroutines.delay(700); titleAlpha.animateTo(1f, tween(400)) }
        launch { kotlinx.coroutines.delay(900); subAlpha.animateTo(1f, tween(400)) }
        launch { kotlinx.coroutines.delay(1100); pillsAlpha.animateTo(1f, tween(400)) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Velt.Bg)
            .statusBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(Velt.Cyan.copy(alpha = 0.1f), Color.Transparent),
                            center = Offset(size.width / 2f, size.height * 0.4f),
                            radius = size.width * 0.9f
                        )
                    )
                }
        )
        ConfettiOverlay()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .scale(checkScale.value)
                    .alpha(checkAlpha.value)
                    .clip(CircleShape)
                    .background(Velt.Cyan.copy(alpha = 0.1f))
                    .border(2.dp, Velt.Cyan, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Check, null, tint = Velt.Cyan, modifier = Modifier.size(40.dp))
            }

            Spacer(Modifier.height(18.dp))
            Text(
                strings.welcomeTitle,
                fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Velt.T1,
                textAlign = TextAlign.Center, modifier = Modifier.alpha(titleAlpha.value)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                strings.welcomeSub,
                fontSize = 13.sp, color = Velt.T2, textAlign = TextAlign.Center, lineHeight = 21.sp,
                modifier = Modifier.alpha(subAlpha.value)
            )

            Spacer(Modifier.height(18.dp))
            Column(
                modifier = Modifier.fillMaxWidth().alpha(pillsAlpha.value),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WPill(painterResource(R.drawable.ic_palm_icon), strings.wp1, strings.wp1sub, trailingCheck = true)
                WPill(rememberVectorPainter(Icons.Filled.Badge), strings.wp2, "carlos.velt.eth", trailingCheck = true)
                WPill(rememberVectorPainter(Icons.Filled.AccountBalanceWallet), strings.wp3, strings.wp3sub, trailingCheck = false)
            }

            Spacer(Modifier.height(20.dp))
            PrimaryButton(strings.welcomeCta, modifier = Modifier.alpha(pillsAlpha.value), onClick = onFinish)
        }
    }
}

@Composable
private fun WPill(icon: Painter, title: String, sub: String, trailingCheck: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Velt.Surf)
            .border(1.dp, Velt.Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Velt.Cyan, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Velt.T1)
            Spacer(Modifier.height(1.dp))
            Text(sub, fontSize = 11.sp, color = Velt.T2)
        }
        if (trailingCheck) {
            Icon(Icons.Filled.Check, null, tint = Velt.Green, modifier = Modifier.size(16.dp))
        } else {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Velt.T3, modifier = Modifier.size(14.dp))
        }
    }
}
