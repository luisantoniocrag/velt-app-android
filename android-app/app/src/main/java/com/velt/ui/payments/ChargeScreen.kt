package com.velt.ui.payments

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.velt.VeltApp
import com.velt.sensor.VeltSensorBioService
import com.velt.sensor.VeltSensorClient
import com.velt.sensor.VeltSensorConfig
import com.velt.sensor.VeltSensorRepository
import com.velt.ui.onboarding.CircularKeypad
import com.velt.ui.onboarding.GhostButton
import com.velt.ui.onboarding.PrimaryButton
import com.velt.ui.theme.Velt
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Base64

@Composable
fun ChargeScreen(
    deviceAddress: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as VeltApp
    val vm: ChargeViewModel = viewModel(factory = ChargeViewModel.factory(app.container.paymentRepository))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Velt.Bg)
            .padding(24.dp)
    ) {
        ChargeHeader(vm)
        Spacer(Modifier.height(16.dp))

        AnimatedContent(
            targetState = vm.state,
            transitionSpec = {
                fadeIn(tween(260)).togetherWith(fadeOut(tween(160)))
            },
            contentKey = { it::class },
            label = "charge-state",
            modifier = Modifier.weight(1f)
        ) { state ->
            when (state) {
                is ChargeState.LoadingMerchant -> CenteredSpinner("Cargando tu comercio...")
                is ChargeState.CreateMerchant -> CreateMerchantStep(vm)
                is ChargeState.EnterAmount -> EnterAmountStep(vm)
                is ChargeState.AwaitingPalm -> AwaitingPalmStep(vm, deviceAddress, state.paymentId)
                is ChargeState.Authorizing -> AuthorizingStep(vm)
                is ChargeState.Held -> HeldStep(vm, state)
                is ChargeState.Settled -> SettledStep(vm, state)
                is ChargeState.Failed -> FailedStep(vm, state)
            }
        }

        Spacer(Modifier.height(12.dp))
        GhostButton(text = "Volver", onClick = onBack)
    }
}

@Composable
private fun ChargeHeader(vm: ChargeViewModel) {
    Column {
        Text("Cobrar", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Velt.T1)
        vm.merchant?.let { merchant ->
            Text(merchant.name, fontSize = 14.sp, color = Velt.T2)
        }
    }
}

@Composable
private fun CenteredSpinner(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = Velt.Cyan, strokeWidth = 4.dp)
        Spacer(Modifier.height(16.dp))
        Text(message, fontSize = 15.sp, color = Velt.T2)
    }
}

@Composable
private fun CreateMerchantStep(vm: ChargeViewModel) {
    var name by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Crea tu comercio", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Velt.T1)
        Spacer(Modifier.height(8.dp))
        Text(
            "Necesitas un comercio para empezar a cobrar.",
            fontSize = 14.sp,
            color = Velt.T2
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            placeholder = { Text("Nombre del comercio", color = Velt.T3) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Velt.Cyan,
                unfocusedBorderColor = Velt.Border,
                focusedTextColor = Velt.T1,
                unfocusedTextColor = Velt.T1,
                cursorColor = Velt.Cyan
            ),
            modifier = Modifier.fillMaxWidth()
        )
        vm.errorMessage?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontSize = 13.sp, color = Velt.Red)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(text = "Crear comercio", enabled = name.isNotBlank()) {
            vm.createMerchant(name)
        }
    }
}

@Composable
private fun EnterAmountStep(vm: ChargeViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(1f))
        Text(
            text = formatUsd(vm.amountCents),
            fontSize = 44.sp,
            fontWeight = FontWeight.Light,
            fontFamily = FontFamily.Monospace,
            color = if (vm.amountCents > 0) Velt.T1 else Velt.T3,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
        )
        Text(
            "USDC",
            fontSize = 13.sp,
            color = Velt.T2,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        vm.errorMessage?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontSize = 13.sp, color = Velt.Red, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.weight(1f))
        CircularKeypad(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
            onKey = vm::pressDigit,
            onDelete = vm::pressDelete
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            text = if (vm.amountCents > 0) "Cobrar ${formatUsd(vm.amountCents)}" else "Cobrar",
            enabled = vm.amountCents > 0
        ) {
            vm.startCharge()
        }
    }
}

private enum class PalmPhase { CONNECTING, SCANNING, VERIFYING }

@Composable
private fun AwaitingPalmStep(vm: ChargeViewModel, deviceAddress: String?, paymentId: String) {
    val context = LocalContext.current
    var phase by remember { mutableStateOf(PalmPhase.CONNECTING) }
    var handMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(paymentId) {
        val sensor = VeltSensorRepository(
            ctx = context.applicationContext,
            sppDeviceName = deviceAddress ?: VeltSensorConfig.SPP_DEVICE_NAME
        )
        val templateDeferred = CompletableDeferred<String>()
        val collector = launch {
            sensor.events.collect { event ->
                when (event) {
                    is VeltSensorClient.Event.Capture -> {
                        val b64 = Base64.getEncoder().encodeToString(event.biometric)
                        if (!templateDeferred.isCompleted) templateDeferred.complete(b64)
                    }
                    is VeltSensorClient.Event.Position -> {
                        handMessage = VeltSensorClient.HandPositionStatus.fromState(event.state).message()
                    }
                    is VeltSensorClient.Event.Error -> {
                        if (!templateDeferred.isCompleted) {
                            templateDeferred.completeExceptionally(Exception(event.message))
                        }
                    }
                    else -> Unit
                }
            }
        }

        try {
            val started = withTimeoutOrNull(
                VeltSensorConfig.BLE_CONNECT_TIMEOUT_MS + VeltSensorConfig.SPP_CONNECT_TIMEOUT_MS
            ) { sensor.startSession() } ?: false
            if (!started) throw Exception("No se pudo conectar con el sensor Velt")

            phase = PalmPhase.SCANNING
            sensor.setLedWhiteBlink()

            val template = withTimeoutOrNull(VeltSensorConfig.CAPTURE_TIMEOUT_MS) {
                templateDeferred.await()
            } ?: throw Exception("Tiempo de espera agotado esperando la palma")

            phase = PalmPhase.VERIFYING
            sensor.stopCapture()

            val (code, body) = withTimeoutOrNull(VeltSensorConfig.VERIFY_TIMEOUT_MS) {
                VeltSensorBioService.verifyUser(template)
            } ?: (-1 to "")

            val personId = if (code == 200) extractPersonId(body) else null
            if (personId == null) {
                vm.palmFailed("Palma no reconocida. El pagador debe estar registrado.")
            } else {
                vm.authorize(personId)
            }
        } catch (e: Exception) {
            vm.palmFailed(e.message ?: "Error con el sensor")
        } finally {
            collector.cancel()
            sensor.close()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AmountBadge(vm.amountCents)
        Spacer(Modifier.height(40.dp))
        PulsingDot()
        Spacer(Modifier.height(24.dp))
        Text(
            text = when (phase) {
                PalmPhase.CONNECTING -> "Conectando con el sensor..."
                PalmPhase.SCANNING -> "Coloca tu palma sobre el sensor"
                PalmPhase.VERIFYING -> "Identificando al pagador..."
            },
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = Velt.T1,
            textAlign = TextAlign.Center
        )
        if (phase == PalmPhase.SCANNING && handMessage != null) {
            Spacer(Modifier.height(10.dp))
            Text(handMessage!!, fontSize = 14.sp, color = Velt.Cyan, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun AuthorizingStep(vm: ChargeViewModel) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AmountBadge(vm.amountCents)
        Spacer(Modifier.height(40.dp))
        PulsingDot()
        Spacer(Modifier.height(24.dp))
        Text(
            "Autorizando pago...",
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = Velt.T1
        )
        Spacer(Modifier.height(8.dp))
        Text("Firmando la retención en escrow", fontSize = 13.sp, color = Velt.T2)
    }
}

@Composable
private fun HeldStep(vm: ChargeViewModel, initial: ChargeState.Held) {
    val state = (vm.state as? ChargeState.Held) ?: initial
    val countdown = rememberCountdown(state.releaseAfterIso)
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, Velt.Amber.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                .background(Velt.Surf)
                .padding(horizontal = 24.dp, vertical = 28.dp)
                .fillMaxWidth(0.92f)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("RETENIDO EN ESCROW", fontSize = 12.sp, color = Velt.Amber, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                Text(
                    formatUsd(vm.amountCents),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.Monospace,
                    color = Velt.T1
                )
                Text("USDC", fontSize = 12.sp, color = Velt.T2)
                state.escrowTxHash?.let { hash ->
                    Spacer(Modifier.height(18.dp))
                    CopyableHash(label = "Tx escrow", hash = hash)
                }
                countdown?.let {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        if (it == "0:00") "Liberando automáticamente..." else "Liberación automática en $it",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Velt.Amber
                    )
                }
            }
        }
        vm.errorMessage?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, fontSize = 13.sp, color = Velt.Red)
        }
        Spacer(Modifier.height(28.dp))
        if (state.confirming) {
            CircularProgressIndicator(color = Velt.Cyan, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text("Liberando el escrow...", fontSize = 13.sp, color = Velt.T2)
        } else {
            PrimaryButton(text = "Confirmar entrega") { vm.confirmDelivery() }
        }
    }
}

@Composable
private fun SettledStep(vm: ChargeViewModel, state: ChargeState.Settled) {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 380f),
        label = "check-pop"
    )
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(Velt.CyanDark),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Velt.Cyan, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Pago liquidado", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Velt.T1)
        Spacer(Modifier.height(6.dp))
        Text(
            formatUsd(vm.amountCents) + " USDC",
            fontSize = 18.sp,
            fontFamily = FontFamily.Monospace,
            color = Velt.T1
        )
        state.payerEnsName?.let { ens ->
            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Velt.Card)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Pagado por", fontSize = 11.sp, color = Velt.T2)
                    Text(
                        ens,
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = Velt.Cyan
                    )
                }
            }
        }
        state.txHash?.let { hash ->
            Spacer(Modifier.height(18.dp))
            CopyableHash(label = "Tx liquidación", hash = hash)
        }
        Spacer(Modifier.height(32.dp))
        PrimaryButton(text = "Nuevo cobro") { vm.reset() }
    }
}

@Composable
private fun FailedStep(vm: ChargeViewModel, state: ChargeState.Failed) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(Velt.Red.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text("✕", fontSize = 40.sp, color = Velt.Red)
        }
        Spacer(Modifier.height(20.dp))
        Text("Pago fallido", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Velt.T1)
        Spacer(Modifier.height(8.dp))
        Text(state.reason, fontSize = 14.sp, color = Velt.T2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        PrimaryButton(text = "Intentar de nuevo") { vm.reset() }
    }
}

@Composable
private fun AmountBadge(amountCents: Long) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Velt.Card)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(
            formatUsd(amountCents) + " USDC",
            fontSize = 18.sp,
            fontFamily = FontFamily.Monospace,
            color = Velt.T1
        )
    }
}

@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse-scale"
    )
    Box(
        modifier = Modifier
            .size(56.dp)
            .scale(pulse)
            .alpha(2f - pulse)
            .clip(CircleShape)
            .background(Velt.Cyan.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Velt.Cyan)
        )
    }
}

@Composable
private fun CopyableHash(label: String, hash: String) {
    val clipboard = LocalClipboardManager.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { clipboard.setText(AnnotatedString(hash)) }
            .background(Velt.Card)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text("$label  ", fontSize = 12.sp, color = Velt.T2)
        Text(
            shortenHash(hash),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = Velt.T1
        )
        Spacer(Modifier.size(8.dp))
        Icon(
            Icons.Filled.ContentCopy,
            contentDescription = "Copiar",
            tint = Velt.T2,
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun rememberCountdown(releaseAfterIso: String?): String? {
    if (releaseAfterIso == null) return null
    var remainingMs by remember(releaseAfterIso) { mutableLongStateOf(remainingUntil(releaseAfterIso)) }
    LaunchedEffect(releaseAfterIso) {
        while (true) {
            remainingMs = remainingUntil(releaseAfterIso)
            if (remainingMs <= 0L) break
            delay(1_000)
        }
    }
    val totalSeconds = remainingMs / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun remainingUntil(iso: String): Long = runCatching {
    (Instant.parse(iso).toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0L)
}.getOrDefault(0L)

private fun formatUsd(amountCents: Long): String =
    "$%,d.%02d".format(amountCents / 100, amountCents % 100)

private fun shortenHash(hash: String): String =
    if (hash.length <= 14) hash else "${hash.take(8)}…${hash.takeLast(6)}"

private fun extractPersonId(body: String): String? = runCatching {
    val json = JSONObject(body)
    val personId = json.optString("personId", "")
    val subjectId = json.optString("subjectId", "")
    personId.ifEmpty { subjectId }.ifEmpty { null }
}.getOrNull()
