package com.velt.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velt.ui.theme.Velt

private fun Modifier.dashedBorder(radius: androidx.compose.ui.unit.Dp) = this.drawBehind {
    drawRoundRect(
        color = Velt.Border,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()),
        style = Stroke(
            width = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
        )
    )
}

@Composable
fun KycScreen(
    strings: OnboardingStrings,
    lang: Lang,
    onLang: (Lang) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    var selected by rememberSaveable { mutableIntStateOf(1) }

    ObScaffold(lang, onLang) {
        Column(modifier = Modifier.fillMaxSize()) {
            ObBackHeader(strings.kycTitle, onBack)
            OnboardingDots(current = 3)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column {
                    Text(strings.kycH, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Velt.T1)
                    Spacer(Modifier.height(5.dp))
                    Text(strings.kycSub, fontSize = 13.sp, color = Velt.T2, lineHeight = 19.sp)
                }

                Text(strings.kycChoose.uppercase(), fontSize = 11.sp, color = Velt.T2, letterSpacing = 1.sp)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KycOption(Icons.Filled.Badge, strings.kycOpt1, selected == 1, Modifier.weight(1f)) { selected = 1 }
                    KycOption(Icons.Filled.Book, strings.kycOpt2, selected == 2, Modifier.weight(1f)) { selected = 2 }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .dashedBorder(14.dp)
                        .clickable { onNext() }
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.PhotoCamera, null, tint = Velt.T3, modifier = Modifier.size(32.dp))
                    Text(strings.kycPhoto, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Velt.T1)
                    Text(strings.kycHint, fontSize = 12.sp, color = Velt.T3)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Velt.Cyan.copy(alpha = 0.1f))
                            .border(1.dp, Velt.Cyan.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(strings.kycCamera, fontSize = 11.sp, color = Velt.Cyan)
                    }
                }

                WarnBox(strings.kycPrivacy)

                PrimaryButton(strings.kycSkip, onClick = onNext)
            }
        }
    }
}

@Composable
private fun KycOption(icon: ImageVector, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) Velt.Cyan.copy(alpha = 0.05f) else Velt.Surf)
            .border(1.dp, if (on) Velt.Cyan else Velt.Border, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, tint = Velt.Cyan, modifier = Modifier.size(26.dp))
        Text(label, fontSize = 12.sp, color = Velt.T2)
    }
}
