package com.velt.ui.payments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velt.R
import com.velt.sensor.PalmCapturePhase
import com.velt.sensor.VeltSensorBioService
import com.velt.sensor.capturePalmTemplate
import com.velt.ui.i18n.tr
import com.velt.ui.onboarding.DesignScaled
import com.velt.ui.onboarding.GhostButton
import com.velt.ui.onboarding.PrimaryButton
import com.velt.ui.theme.Velt
import java.util.UUID
import kotlinx.coroutines.launch

private sealed interface EnrollState {
    data object Idle : EnrollState
    data class Scanning(val phase: PalmCapturePhase, val hand: String?) : EnrollState
    data object Enrolling : EnrollState
    data class Success(val personId: String) : EnrollState
    data class Failed(val reason: String) : EnrollState
}

@Composable
fun EnrollScreen(deviceAddress: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<EnrollState>(EnrollState.Idle) }

    fun enroll() {
        scope.launch {
            state = EnrollState.Scanning(PalmCapturePhase.CONNECTING, null)
            try {
                val template = capturePalmTemplate(
                    context = context,
                    deviceAddress = deviceAddress,
                    onPhase = { p -> state = EnrollState.Scanning(p, (state as? EnrollState.Scanning)?.hand) },
                    onHandMessage = { h -> (state as? EnrollState.Scanning)?.let { state = it.copy(hand = h) } },
                )
                state = EnrollState.Enrolling
                val personId = UUID.randomUUID().toString()
                // name y lastName: ids aleatorios (el demo no captura datos reales del usuario).
                val randomId = { UUID.randomUUID().toString().take(8) }
                val (code, body) = VeltSensorBioService.enrollUser(
                    personId, template, firstName = randomId(), lastName = randomId(),
                )
                state = if (code in 200..299) {
                    EnrollState.Success(personId)
                } else {
                    EnrollState.Failed(
                        if (code in 500..599 || code == -1)
                            tr("Bioserver unavailable (${code}). Try again later.", "Bioserver no disponible (${code}). Intenta más tarde.")
                        else tr("Enrollment failed (${code}).", "El registro falló (${code}).")
                    )
                }
            } catch (e: Exception) {
                state = EnrollState.Failed(e.message ?: tr("Sensor error", "Error con el sensor"))
            }
        }
    }

    DesignScaled {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(20.dp))
            VeltWordmark(fontSize = 24)
            Spacer(Modifier.height(4.dp))
            Text(tr("Enroll a palm", "Registrar una palma"), fontSize = 13.sp, color = Velt.T2)
            Spacer(Modifier.height(24.dp))

            when (val s = state) {
                is EnrollState.Idle -> {
                    PalmCircle(Velt.Cyan, onClick = ::enroll)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        tr("Place the new palm to register it.", "Coloca la palma nueva para registrarla."),
                        fontSize = 13.sp, color = Velt.T3, textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(text = tr("Scan & enroll", "Escanear y registrar"), onClick = ::enroll)
                }
                is EnrollState.Scanning -> StatusCircle(
                    when (s.phase) {
                        PalmCapturePhase.CONNECTING -> tr("Connecting to the sensor...", "Conectando con el sensor...")
                        PalmCapturePhase.SCANNING -> s.hand ?: tr("Place your palm on the reader", "Coloca tu palma en el lector")
                        PalmCapturePhase.VERIFYING -> tr("Capturing...", "Capturando...")
                    }
                )
                is EnrollState.Enrolling -> StatusCircle(tr("Registering the palm...", "Registrando la palma..."))
                is EnrollState.Success -> EnrollResult(personId = s.personId, onAgain = { state = EnrollState.Idle })
                is EnrollState.Failed -> {
                    StatusCircle(s.reason, color = Velt.Red)
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(text = tr("Try again", "Reintentar"), onClick = { state = EnrollState.Idle })
                }
            }

            Spacer(Modifier.weight(1f))
            GhostButton(text = tr("Back", "Volver"), onClick = onBack)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PalmCircle(color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(150.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.05f))
            .border(1.5.dp, color, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(R.drawable.ic_palm_icon), null, tint = color, modifier = Modifier.size(56.dp))
    }
}

@Composable
private fun StatusCircle(message: String, color: Color = Velt.CyanLight) {
    Box(
        modifier = Modifier.size(150.dp).clip(CircleShape).background(Velt.Cyan.copy(alpha = 0.05f))
            .border(1.5.dp, color, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(R.drawable.ic_palm_icon), null, tint = color, modifier = Modifier.size(56.dp))
    }
    Spacer(Modifier.height(20.dp))
    Text(message, fontSize = 14.sp, color = if (color == Velt.Red) Velt.Red else Velt.T1, textAlign = TextAlign.Center)
}

@Composable
private fun EnrollResult(personId: String, onAgain: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Box(
        modifier = Modifier.size(120.dp).clip(CircleShape).background(Velt.Green.copy(alpha = 0.08f))
            .border(1.5.dp, Velt.Green, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(R.drawable.ic_palm_icon), null, tint = Velt.Green, modifier = Modifier.size(48.dp))
    }
    Spacer(Modifier.height(16.dp))
    Text(tr("Palm enrolled ✓", "Palma registrada ✓"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Velt.Green)
    Spacer(Modifier.height(12.dp))
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Velt.Surf)
            .border(1.dp, Velt.Border, RoundedCornerShape(12.dp))
            .clickable { clipboard.setText(AnnotatedString(personId)) }
            .padding(14.dp)
    ) {
        Text(tr("personId (tap to copy)", "personId (toca para copiar)").uppercase(), fontSize = 9.sp, letterSpacing = 1.sp, color = Velt.T3)
        Spacer(Modifier.height(4.dp))
        Text(personId, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Velt.T1)
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton(text = tr("Enroll another", "Registrar otra"), onClick = onAgain)
}
