package com.velt.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velt.R
import com.velt.ui.theme.Velt

@Composable
fun LanguagePill(lang: Lang, onLang: (Lang) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(20.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Velt.Surf.copy(alpha = 0.9f))
            .border(0.5.dp, Velt.Border, RoundedCornerShape(20.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LangChip("ES", lang == Lang.ES) { onLang(Lang.ES) }
        LangChip("EN", lang == Lang.EN) { onLang(Lang.EN) }
    }
}

@Composable
private fun LangChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(20.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (on) Velt.Cyan else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (on) Velt.OnCyan else Velt.T3
        )
    }
}

@Composable
fun ObBackHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Velt.Surf)
                .border(1.dp, Velt.Border, CircleShape)
                .clickable { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Atrás",
                tint = Velt.T2,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Velt.T1)
    }
}

@Composable
fun OnboardingDots(current: Int, total: Int = 5) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        repeat(total) { i ->
            val (w, color) = when {
                i == current -> 20.dp to Velt.Cyan
                i < current -> 10.dp to Velt.Cyan.copy(alpha = 0.4f)
                else -> 10.dp to Velt.Border
            }
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .width(w)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

@Composable
fun VeltLogo(fontSize: Int = 22, showHandSuffix: Boolean = false, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = Velt.T1)) { append("Ve") }
                withStyle(SpanStyle(color = Velt.Cyan)) { append("l") }
                withStyle(SpanStyle(color = Velt.T1)) { append("t") }
            },
            fontSize = fontSize.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (showHandSuffix) {
            Icon(
                painterResource(R.drawable.ic_palm_icon),
                contentDescription = null,
                tint = Velt.Cyan,
                modifier = Modifier
                    .padding(start = 3.dp, top = 4.dp)
                    .size(11.dp)
            )
        }
    }
}
