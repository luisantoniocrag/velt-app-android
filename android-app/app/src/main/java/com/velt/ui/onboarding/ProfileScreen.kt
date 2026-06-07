package com.velt.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velt.ui.theme.DmSans
import com.velt.ui.theme.Velt

@Composable
fun ProfileScreen(
    strings: OnboardingStrings,
    lang: Lang,
    onLang: (Lang) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("Carlos") }
    var last by rememberSaveable { mutableStateOf("Mendoza") }
    var addr by rememberSaveable { mutableStateOf("carlos") }

    ObScaffold(lang, onLang) {
        Column(modifier = Modifier.fillMaxSize()) {
            ObBackHeader(strings.profileTitle, onBack)
            OnboardingDots(current = 2)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column {
                    Text(strings.profileH, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Velt.T1)
                    Spacer(Modifier.height(4.dp))
                    Text(strings.profileSub, fontSize = 13.sp, color = Velt.T2)
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Velt.CyanDark, androidx.compose.ui.graphics.Color(0xFF001A2E))))
                                .border(2.dp, Velt.Cyan.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(name.take(1).uppercase().ifEmpty { "C" }, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Velt.Cyan)
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Velt.Cyan),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.PhotoCamera, null, tint = Velt.OnCyan, modifier = Modifier.size(11.dp))
                        }
                    }
                    Text(strings.profilePhoto, fontSize = 12.sp, color = Velt.Cyan)
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InputField(strings.profName, name, strings.profName) { name = it }
                    InputField(strings.profLast, last, strings.profLast) { last = it }
                    Column {
                        Text(strings.profAddr, fontSize = 11.sp, color = Velt.T2)
                        Spacer(Modifier.height(5.dp))
                        Box {
                            BasicTextField(
                                value = addr,
                                onValueChange = { addr = it },
                                textStyle = TextStyle(fontFamily = DmSans, fontSize = 15.sp, color = Velt.T1),
                                cursorBrush = SolidColor(Velt.Cyan),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Velt.Surf)
                                    .border(1.dp, Velt.Border, RoundedCornerShape(12.dp))
                                    .padding(start = 14.dp, end = 80.dp, top = 13.dp, bottom = 13.dp)
                            )
                            Text(
                                ".velt.eth",
                                fontSize = 13.sp,
                                color = Velt.T3,
                                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("✓ ${addr.ifEmpty { "carlos" }}.velt.eth ${strings.profAvailSuffix}", fontSize = 11.sp, color = Velt.Green)
                    }
                }

                Spacer(Modifier.height(4.dp))
                PrimaryButton(strings.profileNext, onClick = onNext)
            }
        }
    }
}

@Composable
private fun InputField(label: String, value: String, placeholder: String, onValueChange: (String) -> Unit) {
    Column {
        Text(label, fontSize = 11.sp, color = Velt.T2)
        Spacer(Modifier.height(5.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(fontFamily = DmSans, fontSize = 15.sp, color = Velt.T1),
            cursorBrush = SolidColor(Velt.Cyan),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Velt.Surf)
                .border(1.dp, Velt.Border, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, fontSize = 15.sp, color = Velt.T3)
                inner()
            }
        )
    }
}
