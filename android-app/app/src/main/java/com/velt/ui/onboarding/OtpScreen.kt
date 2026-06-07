package com.velt.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    var otp by rememberSaveable { mutableStateOf("427") }

    ObScaffold(lang, onLang) {
        Column(modifier = Modifier.fillMaxSize()) {
            ObBackHeader(strings.otpTitle, onBack)
            OnboardingDots(current = 1)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 22.dp, end = 22.dp, top = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column {
                    Text(strings.otpH, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Velt.T1)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = Velt.T2)) { append(strings.otpSubPrefix + "\n") }
                            withStyle(SpanStyle(color = Velt.T1, fontWeight = FontWeight.Bold)) { append("+52 55 9876 5432") }
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
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = Velt.T2)) { append(strings.otpResend) }
                            withStyle(SpanStyle(color = Velt.Cyan)) { append(strings.otpResendLink) }
                        },
                        fontSize = 12.sp
                    )
                    Text("0:42", fontSize = 12.sp, color = Velt.T3)
                }

                CircularKeypad(
                    modifier = Modifier.weight(1f).align(Alignment.CenterHorizontally),
                    onKey = { if (otp.length < 6) otp += it },
                    onDelete = { otp = otp.dropLast(1) }
                )

                PrimaryButton(strings.otpVerify, modifier = Modifier.padding(bottom = 16.dp), onClick = onNext)
            }
        }
    }
}
