package com.velt.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velt.R
import com.velt.ui.theme.Velt

@Composable
fun PalmScreen(
    strings: OnboardingStrings,
    lang: Lang,
    onLang: (Lang) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    var scans by rememberSaveable { mutableIntStateOf(0) }
    val done = scans >= 5
    val accent = if (done) Velt.Green else Velt.Cyan

    fun scan() { if (scans < 5) scans++ else onNext() }

    ObScaffold(lang, onLang) {
        Column(modifier = Modifier.fillMaxSize()) {
            ObBackHeader(strings.palmTitle, onBack)
            OnboardingDots(current = 4)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (done) strings.palmDoneMsg else strings.palmH,
                        fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Velt.T1, textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        if (done) strings.palmDoneSub else strings.palmSub,
                        fontSize = 13.sp, color = Velt.T2, textAlign = TextAlign.Center, lineHeight = 19.sp
                    )
                }

                PalmChips(scans)

                val handColor by animateColorAsState(accent, label = "hand")
                ScanRing(
                    modifier = Modifier.clickable { scan() },
                    ringColor = accent,
                    showPulses = !done
                ) {
                    Icon(painterResource(R.drawable.ic_palm_icon), null, tint = handColor, modifier = Modifier.size(52.dp))
                }

                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(strings.palmReadings, fontSize = 12.sp, color = Velt.T2)
                        Text("$scans / 5", fontSize = 12.sp, color = Velt.T2)
                    }
                    ProgressBar(scans / 5f)
                }

                if (!done) {
                    Text(strings.palmHint, fontSize = 12.sp, color = Velt.T3, textAlign = TextAlign.Center)
                }

                Spacer(Modifier.weight(1f))

                PrimaryButton(
                    text = if (done) strings.palmDone else strings.palmScanBtn,
                    containerColor = if (done) Velt.Green else Velt.Cyan,
                    onClick = { scan() }
                )
            }
        }
    }
}

@Composable
private fun PalmChips(scans: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(5) { i ->
            val color by animateColorAsState(
                when {
                    i == 0 && scans == 0 -> Velt.Cyan
                    i < scans -> Velt.Cyan
                    i == scans -> Velt.CyanLight
                    else -> Velt.Border
                },
                label = "pchip"
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

@Composable
private fun ProgressBar(fraction: Float) {
    val animated by animateFloatAsState(fraction, label = "pbar")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Velt.Border)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .clip(RoundedCornerShape(2.dp))
                .background(Velt.Cyan)
        )
    }
}
