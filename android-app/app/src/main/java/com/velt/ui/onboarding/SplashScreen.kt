package com.velt.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.velt.R
import androidx.compose.ui.unit.sp
import com.velt.ui.theme.Velt

@Composable
fun SplashScreen(
    strings: OnboardingStrings,
    lang: Lang,
    onLang: (Lang) -> Unit,
    onCreate: () -> Unit,
    onLogin: () -> Unit
) {
    ObScaffold(lang, onLang) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Velt.Cyan.copy(alpha = 0.12f), androidx.compose.ui.graphics.Color.Transparent),
                            center = Offset(size.width / 2f, size.height * 0.34f),
                            radius = size.width * 0.85f
                        ),
                        radius = size.width * 0.85f,
                        center = Offset(size.width / 2f, size.height * 0.34f)
                    )
                }
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            ScanRing(
                ringColor = Velt.Cyan,
                ringAlpha = 0.3f,
                showPulses = false,
                outerSize = 154.dp,
                innerSize = 108.dp
            ) {
                Icon(painterResource(R.drawable.ic_palm_icon), null, tint = Velt.Cyan, modifier = Modifier.size(56.dp))
            }

            Spacer(Modifier.height(28.dp))
            VeltLogo(fontSize = 30, showHandSuffix = true)
            Spacer(Modifier.height(10.dp))
            Text(
                strings.splashTitle,
                fontSize = 24.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = Velt.T1,
                textAlign = TextAlign.Center,
                lineHeight = 30.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                strings.splashSub,
                fontSize = 13.sp,
                color = Velt.T2,
                textAlign = TextAlign.Center,
                lineHeight = 21.sp
            )
            Spacer(Modifier.height(32.dp))
            PrimaryButton(strings.splashCta, onClick = onCreate)
            Spacer(Modifier.height(10.dp))
        }
    }
}
