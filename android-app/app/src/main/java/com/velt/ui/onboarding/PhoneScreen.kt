package com.velt.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.text.font.FontWeight
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
    error: String? = null,
    onBack: () -> Unit,
    onNext: (display: String, e164: String) -> Unit
) {
    var raw by rememberSaveable { mutableStateOf("") }
    var country by remember { mutableStateOf(defaultCountry) }
    var showCountryPicker by remember { mutableStateOf(false) }

    if (showCountryPicker) {
        CountryPickerSheet(
            title = if (lang == Lang.ES) "Selecciona tu país" else "Select your country",
            searchPlaceholder = if (lang == Lang.ES) "Buscar país" else "Search country",
            onDismiss = { showCountryPicker = false },
            onSelect = {
                country = it
                showCountryPicker = false
            }
        )
    }

    ObScaffold(lang, onLang) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val keypadH = keypadAreaHeight(maxHeight)
            Column(modifier = Modifier.fillMaxSize()) {
                ObBackHeader(strings.phoneTitle, onBack)
                OnboardingDots(current = 0)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
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
                                .clickable { showCountryPicker = true }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(country.flag, fontSize = 18.sp)
                            Text(country.dial, fontSize = 14.sp, color = Velt.T2)
                            Icon(Icons.Filled.KeyboardArrowDown, null, tint = Velt.T3, modifier = Modifier.size(12.dp))
                        }
                        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Velt.Border))
                        BasicTextField(
                            value = formatMx(raw),
                            onValueChange = { raw = it.filter(Char::isDigit).take(10) },
                            textStyle = TextStyle(fontFamily = DmSans, fontSize = 16.sp, color = Velt.T1, letterSpacing = 0.8.sp),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Velt.Cyan),
                            readOnly = true,
                            singleLine = true,
                            modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                            decorationBox = { inner ->
                                if (raw.isEmpty()) Text("55 1234 5678", fontSize = 16.sp, color = Velt.T3)
                                inner()
                            }
                        )
                    }

                    if (error != null) {
                        Text(error, fontSize = 12.sp, color = Velt.Red, lineHeight = 16.sp)
                    }
                }

                Spacer(Modifier.weight(1f))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(keypadH)
                        .padding(horizontal = 22.dp)
                ) {
                    CircularKeypad(
                        modifier = Modifier.fillMaxSize(),
                        onKey = { if (raw.length < 10) raw += it },
                        onDelete = { raw = raw.dropLast(1) }
                    )
                }

                Spacer(Modifier.weight(1f))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 16.dp)
                ) {
                    PrimaryButton(
                        strings.phoneNext,
                        enabled = raw.length >= 7,
                        onClick = { onNext("${country.dial} ${formatMx(raw)}", "${country.dial}$raw") }
                    )
                }
            }
        }
    }
}
