package com.velt.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velt.ui.theme.Velt

@Composable
fun OtpScreen(
    strings: OnboardingStrings,
    lang: Lang,
    onLang: (Lang) -> Unit,
    phoneNumber: String,
    error: String? = null,
    onResend: () -> Unit = {},
    onBack: () -> Unit,
    onNext: (code: String) -> Unit
) {
    var otp by rememberSaveable { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var secondsLeft by remember { mutableIntStateOf(60) }
    val canResend = secondsLeft <= 0

    LaunchedEffect(attempt) {
        secondsLeft = 60
        while (secondsLeft > 0) {
            kotlinx.coroutines.delay(1000)
            secondsLeft--
        }
    }

    ObScaffold(lang, onLang) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val keypadH = keypadAreaHeight(maxHeight)
            Column(modifier = Modifier.fillMaxSize()) {
                ObBackHeader(strings.otpTitle, onBack)
                OnboardingDots(current = 1)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, end = 22.dp, top = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column {
                        Text(strings.otpH, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Velt.T1)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = Velt.T2)) { append(strings.otpSubPrefix + "\n") }
                                withStyle(SpanStyle(color = Velt.T1, fontWeight = FontWeight.Bold)) { append(phoneNumber) }
                            },
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    }

                    OtpBoxes(value = otp, cursor = otp.length)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(strings.otpResend.trimEnd(), fontSize = 12.sp, color = Velt.T2)
                            Text(
                                " " + strings.otpResendLink,
                                fontSize = 12.sp,
                                color = if (canResend) Velt.Cyan else Velt.T3,
                                modifier = Modifier.clickable(enabled = canResend) {
                                    otp = ""
                                    attempt++
                                    onResend()
                                }
                            )
                        }
                        Text(
                            "%d:%02d".format(secondsLeft / 60, secondsLeft % 60),
                            fontSize = 12.sp,
                            color = Velt.T3
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
                        onKey = { if (otp.length < 6) otp += it },
                        onDelete = { otp = otp.dropLast(1) }
                    )
                }

                Spacer(Modifier.weight(1f))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 16.dp)
                ) {
                    PrimaryButton(strings.otpVerify, enabled = otp.length == 6, onClick = { onNext(otp) })
                }
            }
        }
    }
}
