package com.velt.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velt.ui.theme.DmSans
import com.velt.ui.theme.Velt

private fun formatMx(raw: String): String = when {
    raw.length <= 2 -> raw
    raw.length <= 6 -> raw.substring(0, 2) + " " + raw.substring(2)
    else -> raw.substring(0, 2) + " " + raw.substring(2, 6) + " " + raw.substring(6)
}

@Composable
fun PhoneScreen(
    strings: OnboardingStrings,
    lang: Lang,
    onLang: (Lang) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    var raw by rememberSaveable { mutableStateOf("5598765432") }

    ObScaffold(lang, onLang) {
        Column(modifier = Modifier.fillMaxSize()) {
            ObBackHeader(strings.phoneTitle, onBack)
            OnboardingDots(current = 0)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 22.dp, end = 22.dp, top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column {
                    Text(strings.phoneH, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Velt.T1)
                    Spacer(Modifier.height(5.dp))
                    Text(strings.phoneSub, fontSize = 13.sp, color = Velt.T2, lineHeight = 19.sp)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Velt.Surf)
                        .border(1.dp, Velt.Border, RoundedCornerShape(14.dp)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("🇲🇽", fontSize = 18.sp)
                        Text("+52", fontSize = 14.sp, color = Velt.T2)
                        Icon(Icons.Filled.KeyboardArrowDown, null, tint = Velt.T3, modifier = Modifier.size(12.dp))
                    }
                    Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Velt.Border))
                    BasicTextField(
                        value = formatMx(raw),
                        onValueChange = { raw = it.filter(Char::isDigit).take(10) },
                        textStyle = TextStyle(fontFamily = DmSans, fontSize = 16.sp, color = Velt.T1, letterSpacing = 0.8.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Velt.Cyan),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                        decorationBox = { inner ->
                            if (raw.isEmpty()) Text("55 1234 5678", fontSize = 16.sp, color = Velt.T3)
                            inner()
                        }
                    )
                }

                Text(
                    strings.phoneKpadLbl,
                    fontSize = 11.sp,
                    color = Velt.T3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                CircularKeypad(
                    modifier = Modifier.weight(1f).align(Alignment.CenterHorizontally),
                    onKey = { if (raw.length < 10) raw += it },
                    onDelete = { raw = raw.dropLast(1) }
                )

                PrimaryButton(strings.phoneNext, onClick = onNext)

                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = Velt.T3)) { append(strings.phoneTerms) }
                        withStyle(SpanStyle(color = Velt.Cyan)) { append(strings.phoneTermsLink) }
                    },
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                )
            }
        }
    }
}
