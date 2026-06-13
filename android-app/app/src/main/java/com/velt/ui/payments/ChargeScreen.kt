package com.velt.ui.payments

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.velt.R
import com.velt.VeltApp
import com.velt.sensor.VeltSensorBioService
import com.velt.sensor.VeltSensorClient
import com.velt.sensor.VeltSensorConfig
import com.velt.sensor.VeltSensorRepository
import com.velt.ui.i18n.tr
import com.velt.ui.onboarding.CircularKeypad
import com.velt.ui.onboarding.DesignScaled
import com.velt.ui.onboarding.GhostButton
import com.velt.ui.onboarding.PrimaryButton
import com.velt.ui.theme.DmSans
import com.velt.ui.theme.Velt
import java.time.Instant
import java.util.Base64
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun ChargeScreen(
    deviceAddress: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as VeltApp
    val vm: ChargeViewModel = viewModel(factory = ChargeViewModel.factory(app.container.paymentRepository))

    DesignScaled {
        AnimatedContent(
            targetState = vm.state,
            transitionSpec = { fadeIn(tween(260)).togetherWith(fadeOut(tween(160))) },
            contentKey = { it::class },
            label = "charge-state",
            modifier = Modifier.fillMaxSize()
        ) { state ->
            when (state) {
                is ChargeState.LoadingMerchant -> CenteredSpinner(tr("Loading your merchant...", "Cargando tu comercio..."))
                is ChargeState.CreateMerchant -> CreateMerchantStep(vm)
                is ChargeState.EnterAmount -> EnterAmountStep(vm, onBack)
                is ChargeState.AwaitingPalm -> AwaitingPalmStep(vm, deviceAddress, state.paymentId)
                is ChargeState.Authorizing -> AuthorizingStep()
                is ChargeState.Held -> HeldStep(vm, state)
                is ChargeState.Settled -> SettledStep(vm, state, onBack)
                is ChargeState.Failed -> FailedStep(vm, state)
            }
        }
    }
}

// ── Pantalla 1 del mockup: cobro con keypad circular ──

private val QUICK_AMOUNTS = listOf(10L, 25L, 50L, 100L)

@Composable
private fun EnterAmountStep(vm: ChargeViewModel, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackCircle(onBack)
                Text(tr("Charge", "Cobrar"), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Velt.T1)
            }
            PalmBadge()
        }
        vm.merchant?.let { merchant ->
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(Velt.Green))
                Text(merchant.name, fontSize = 12.sp, color = Velt.T2)
                merchant.ensName?.let { ens ->
                    Text(ens, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Velt.T3)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel(tr("Total to charge", "Total a cobrar"), modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp))
        AmountRow(vm.amountCents, modifier = Modifier.align(Alignment.CenterHorizontally))

        Spacer(Modifier.height(16.dp))
        QuickAmounts(onPick = vm::addUsdc)

        vm.errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it, fontSize = 12.sp, color = Velt.Red,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(16.dp))
        KeypadSeparator()

        CircularKeypad(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 4.dp),
            onKey = vm::pressDigit,
            onDelete = vm::pressDelete
        )

        PrimaryButton(
            text = if (vm.amountCents > 0) tr("Charge ${formatUsd(vm.amountCents)}", "Cobrar ${formatUsd(vm.amountCents)}")
            else tr("Charge", "Cobrar"),
            enabled = vm.amountCents > 0
        ) { vm.startCharge() }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun BackCircle(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Velt.Surf)
            .border(1.dp, Velt.Border, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBack
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = tr("Back", "Atrás"),
            tint = Velt.T2,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun PalmBadge() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Velt.CyanDark)
            .border(1.dp, Velt.Cyan.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Icon(
            painterResource(R.drawable.ic_palm_icon),
            contentDescription = null,
            tint = Velt.Cyan,
            modifier = Modifier.size(15.dp)
        )
        Text("PALM", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Velt.Cyan)
    }
}

@Composable
private fun QuickAmounts(onPick: (Long) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        QUICK_AMOUNTS.forEach { usdc ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Velt.Card)
                    .border(1.dp, Velt.Border, RoundedCornerShape(12.dp))
                    .clickable { onPick(usdc) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("+$usdc", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Velt.T1)
            }
        }
    }
}

@Composable
private fun AmountRow(amountCents: Long, modifier: Modifier = Modifier) {
    val pesos = amountCents / 100
    val dec = "%02d".format(amountCents % 100)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$", fontSize = 22.sp, fontFamily = DmSans, fontWeight = FontWeight.ExtraLight,
            color = Velt.T3, modifier = Modifier.alignByBaseline()
        )
        Text(
            "%,d".format(pesos),
            fontSize = 62.sp, fontFamily = DmSans, fontWeight = FontWeight.ExtraLight,
            letterSpacing = (-2).sp,
            color = if (amountCents > 0) Velt.T1 else Velt.T3,
            modifier = Modifier.alignByBaseline()
        )
        Text(
            ".$dec",
            fontSize = 32.sp, fontFamily = DmSans, fontWeight = FontWeight.ExtraLight,
            letterSpacing = (-1).sp, color = Velt.Cyan,
            modifier = Modifier.alignByBaseline()
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "USDC",
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Velt.Cyan,
            modifier = Modifier.alignByBaseline()
        )
        BlinkingCursor()
    }
}

@Composable
private fun BlinkingCursor() {
    val transition = rememberInfiniteTransition(label = "cursor")
    val visible by transition.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "cursor-alpha"
    )
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .width(2.dp)
            .height(48.dp)
            .alpha(visible)
            .background(Velt.Cyan, RoundedCornerShape(1.dp))
    )
}

@Composable
private fun KeypadSeparator() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(Velt.Border))
        Box(Modifier.size(4.dp).clip(CircleShape).background(Velt.Border))
        Box(Modifier.weight(1f).height(1.dp).background(Velt.Border))
    }
}

// ── Pantalla 2 del mockup: esperando palma (scan ring) ──

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
            if (!started) throw Exception(tr("Couldn't connect to the Velt sensor", "No se pudo conectar con el sensor Velt"))

            phase = PalmPhase.SCANNING
            sensor.setLedWhiteBlink()

            val template = withTimeoutOrNull(VeltSensorConfig.CAPTURE_TIMEOUT_MS) {
                templateDeferred.await()
            } ?: throw Exception(tr("Timed out waiting for the palm", "Tiempo de espera agotado esperando la palma"))

            phase = PalmPhase.VERIFYING
            sensor.stopCapture()

            val (code, body) = withTimeoutOrNull(VeltSensorConfig.VERIFY_TIMEOUT_MS) {
                VeltSensorBioService.verifyUser(template)
            } ?: (-1 to "")

            val personId = if (code == 200) extractPersonId(body) else null
            if (personId == null) {
                vm.palmFailed(tr(
                    "Palm not recognized. The payer must be registered.",
                    "Palma no reconocida. El pagador debe estar registrado."
                ))
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
        modifier = Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SectionLabel(tr("Charging", "Cobrando"))
            Spacer(Modifier.height(6.dp))
            Text(
                formatUsd(vm.amountCents),
                fontSize = 44.sp, fontFamily = DmSans, fontWeight = FontWeight.ExtraLight,
                letterSpacing = (-1).sp, color = Velt.T1
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ScanRing(active = phase == PalmPhase.VERIFYING)
            Spacer(Modifier.height(16.dp))
            SweepLine()
            Spacer(Modifier.height(16.dp))
            Text(
                text = when (phase) {
                    PalmPhase.CONNECTING -> tr("Connecting to the sensor...", "Conectando con el sensor...")
                    PalmPhase.SCANNING -> tr("Place your palm on the reader", "Pon tu palma en el lector")
                    PalmPhase.VERIFYING -> tr("Palm detected...", "Palma detectada...")
                },
                fontSize = 17.sp, color = Velt.T1, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = when (phase) {
                    PalmPhase.CONNECTING -> tr("Velt reader via Bluetooth", "Lector Velt vía Bluetooth")
                    PalmPhase.SCANNING -> handMessage ?: tr("Hold your hand still for 1–2 seconds", "Mantén la mano quieta 1–2 segundos")
                    PalmPhase.VERIFYING -> tr("Reading biometric data", "Leyendo datos biométricos")
                },
                fontSize = 13.sp, color = Velt.T2, textAlign = TextAlign.Center
            )
        }

        CancelTextButton(tr("Cancel charge", "Cancelar cobro"), onClick = { vm.reset() })
    }
}

@Composable
private fun ScanRing(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "scan")
    val breathe by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "breathe"
    )
    Box(modifier = Modifier.size(154.dp), contentAlignment = Alignment.Center) {
        ExpandingRing(transition, delayMs = 900, baseAlpha = 0.3f)
        ExpandingRing(transition, delayMs = 1800, baseAlpha = 0.15f)
        Box(
            modifier = Modifier
                .size(154.dp)
                .border(1.5.dp, if (active) Velt.CyanLight else Velt.Cyan, CircleShape)
                .background(Velt.Cyan.copy(alpha = 0.04f * breathe), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .clip(CircleShape)
                    .background(Velt.Cyan.copy(alpha = 0.04f))
                    .border(1.dp, Velt.CyanDark, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(R.drawable.ic_palm_icon),
                    contentDescription = null,
                    tint = if (active) Velt.CyanLight else Velt.Cyan,
                    modifier = Modifier.size(54.dp)
                )
            }
        }
    }
}

@Composable
private fun ExpandingRing(
    transition: androidx.compose.animation.core.InfiniteTransition,
    delayMs: Int,
    baseAlpha: Float
) {
    val progress by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(3000, easing = LinearEasing),
            RepeatMode.Restart,
            initialStartOffset = StartOffset(delayMs)
        ),
        label = "ring-$delayMs"
    )
    Box(
        modifier = Modifier
            .size(154.dp)
            .scale(0.88f + progress * 0.35f)
            .alpha(baseAlpha * (1f - progress))
            .border(1.dp, Velt.Cyan, CircleShape)
    )
}

@Composable
private fun SweepLine() {
    val transition = rememberInfiniteTransition(label = "sweep")
    val sweep by transition.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse),
        label = "sweep-x"
    )
    Box(
        modifier = Modifier
            .offset { IntOffset((sweep * 30.dp.toPx()).roundToInt(), 0) }
            .alpha(1f - kotlin.math.abs(sweep) * 0.6f)
            .width(70.dp)
            .height(1.5.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Velt.Cyan, Color.Transparent)
                )
            )
    )
}

// ── Pantalla 3 del mockup: procesando (anillos giratorios) ──

@Composable
private fun AuthorizingStep() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        ProcessingRings()
        Spacer(Modifier.height(24.dp))
        Text(tr("Processing payment...", "Procesando pago..."), fontSize = 18.sp, color = Velt.T1)
        Spacer(Modifier.height(6.dp))
        Text(tr("Securing the funds in escrow", "Asegurando los fondos en escrow"), fontSize = 13.sp, color = Velt.T2)
    }
}

@Composable
private fun ProcessingRings() {
    val transition = rememberInfiniteTransition(label = "proc")
    val a1 by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(750, easing = LinearEasing)), label = "r1"
    )
    val a2 by transition.animateFloat(
        360f, 0f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "r2"
    )
    val a3 by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "r3"
    )
    Canvas(modifier = Modifier.size(96.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        fun ring(diameter: Float, stroke: Float, angle: Float, color: Color) {
            val radius = diameter / 2
            drawCircle(
                Color.White.copy(alpha = 0.05f),
                radius = radius, center = center, style = Stroke(stroke)
            )
            rotate(angle, center) {
                drawArc(
                    color = color,
                    startAngle = -90f, sweepAngle = 80f, useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = androidx.compose.ui.geometry.Size(diameter, diameter),
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
        }
        ring(96.dp.toPx(), 2.dp.toPx(), a1, Velt.Cyan)
        ring(72.dp.toPx(), 1.5.dp.toPx(), a2, Velt.CyanLight)
        ring(50.dp.toPx(), 1.dp.toPx(), a3, Velt.Cyan.copy(alpha = 0.4f))
    }
}

// ── Estado escrow (no existe en el mockup; misma familia, acento ámbar) ──

@Composable
private fun HeldStep(vm: ChargeViewModel, initial: ChargeState.Held) {
    val state = (vm.state as? ChargeState.Held) ?: initial
    val countdown = rememberCountdown(state.releaseAfterIso)
    Box(modifier = Modifier.fillMaxSize()) {
        ResultGlow(Velt.Amber)
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            StatusRow(color = Velt.Amber, label = tr("in escrow", "en escrow"), icon = Icons.Filled.Schedule)
            Spacer(Modifier.height(10.dp))
            Text(
                formatUsd(vm.amountCents),
                fontSize = 46.sp, fontFamily = DmSans, fontWeight = FontWeight.ExtraLight,
                letterSpacing = (-2).sp, color = Velt.T1
            )
            Text(tr("held from the payer", "retenido del pagador"), fontSize = 11.sp, color = Color.White.copy(alpha = 0.28f))
            countdown?.let {
                Spacer(Modifier.height(14.dp))
                Text(
                    if (it == "0:00") tr("Auto-releasing...", "Liberando automáticamente...")
                    else tr("Auto-release in $it", "Liberación automática en $it"),
                    fontSize = 14.sp, color = Velt.Amber
                )
            }
            state.escrowTxHash?.let { hash ->
                Spacer(Modifier.height(14.dp))
                CopyableHash(label = tr("Escrow tx", "Tx escrow"), hash = hash)
            }
            vm.errorMessage?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, fontSize = 12.sp, color = Velt.Red, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            if (state.confirming) {
                CircularProgressIndicator(color = Velt.Cyan, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(8.dp))
                Text(tr("Releasing the escrow...", "Liberando el escrow..."), fontSize = 13.sp, color = Velt.T2)
            } else {
                PrimaryButton(text = tr("Confirm delivery", "Confirmar entrega")) { vm.confirmDelivery() }
            }
        }
    }
}

// ── Pantalla 4 del mockup: Velt confirmed ──

@Composable
private fun SettledStep(vm: ChargeViewModel, state: ChargeState.Settled, onBack: () -> Unit) {
    val pop = remember { Animatable(0.5f) }
    LaunchedEffect(Unit) {
        pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 380f))
    }
    Box(modifier = Modifier.fillMaxSize()) {
        ResultGlow(Velt.Cyan)
        ResultWaves(Velt.Cyan)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .scale(pop.value)
                .alpha(((pop.value - 0.5f) * 2f).coerceIn(0f, 1f)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    painterResource(R.drawable.ic_palm_icon),
                    contentDescription = null,
                    tint = Velt.Green,
                    modifier = Modifier.size(64.dp)
                )
                VeltWordmark(fontSize = 44)
            }
            Spacer(Modifier.height(14.dp))
            StatusRow(color = Velt.Cyan, label = "confirmed", icon = Icons.Filled.Check)
            Spacer(Modifier.height(10.dp))
            Text(
                formatUsd(vm.amountCents),
                fontSize = 46.sp, fontFamily = DmSans, fontWeight = FontWeight.ExtraLight,
                letterSpacing = (-2).sp, color = Velt.T1
            )
            Text(tr("charged successfully", "cobrado exitosamente"), fontSize = 11.sp, color = Color.White.copy(alpha = 0.28f))
            state.payerEnsName?.let { ens ->
                Spacer(Modifier.height(12.dp))
                PayerCard(ens)
            }
            vm.payerBalanceUsdc?.let { balance ->
                Spacer(Modifier.height(8.dp))
                PayerBalanceRow(balanceUsdc = balance, chargedCents = vm.amountCents)
            }
            state.txHash?.let { hash ->
                Spacer(Modifier.height(12.dp))
                CopyableHash(label = tr("Settlement tx", "Tx liquidación"), hash = hash)
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(text = tr("New charge", "Nuevo cobro")) { vm.reset() }
            Spacer(Modifier.height(7.dp))
            GhostButton(text = tr("Back to home", "Volver al inicio"), onClick = onBack)
        }
    }
}

// ── Pantalla 5 del mockup: Velt failed ──

@Composable
private fun FailedStep(vm: ChargeViewModel, state: ChargeState.Failed) {
    val context = LocalContext.current
    val shake = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        shake.animateTo(
            0f,
            keyframes {
                durationMillis = 550
                (-10f) at 66
                10f at 132
                (-7f) at 200
                7f at 270
                (-4f) at 330
                4f at 400
                (-2f) at 470
                0f at 550
            }
        )
    }
    Box(modifier = Modifier.fillMaxSize()) {
        ResultGlow(Velt.Red)
        ResultWaves(Velt.Red)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .offset { IntOffset(shake.value.dp.toPx().roundToInt(), 0) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    painterResource(R.drawable.ic_palm_icon),
                    contentDescription = null,
                    tint = Velt.Red,
                    modifier = Modifier.size(64.dp)
                )
                VeltWordmark(fontSize = 44)
            }
            Spacer(Modifier.height(14.dp))
            StatusRow(color = Velt.Red, label = "failed", icon = Icons.Filled.Close)
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Velt.Surf)
                    .border(1.dp, Velt.Red.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        state.reason,
                        fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        if (state.canFundPayer) tr("Top up their balance or adjust the amount.", "Recarga su saldo o ajusta el monto.")
                        else tr("Adjust the amount or try again.", "Ajusta el monto o inténtalo de nuevo."),
                        fontSize = 13.sp, color = Velt.T2, textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (state.canFundPayer) {
                PrimaryButton(text = tr("Fund the payer's account", "Fondear cuenta del pagador")) {
                    vm.lastPersonId?.let { openFundingPage(context, it) }
                }
                Spacer(Modifier.height(7.dp))
            }
            RedButton(text = tr("Adjust amount", "Ajustar monto")) { vm.editAmount() }
            Spacer(Modifier.height(7.dp))
            GhostButton(text = tr("Retry", "Reintentar")) { vm.retryCharge() }
        }
    }
}

// ── Componentes compartidos de la familia visual ──

@Composable
fun VeltWordmark(fontSize: Int, modifier: Modifier = Modifier) {
    Text(
        text = buildAnnotatedString {
            append("Ve")
            withStyle(SpanStyle(color = Velt.Cyan)) { append("l") }
            append("t")
        },
        fontSize = fontSize.sp,
        fontFamily = DmSans,
        fontWeight = if (fontSize >= 40) FontWeight.Bold else FontWeight.SemiBold,
        letterSpacing = (-1).sp,
        color = Velt.T1,
        modifier = modifier
    )
}

@Composable
internal fun MerchantPill(name: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Velt.Surf)
            .border(1.dp, Velt.Border, RoundedCornerShape(20.dp))
            .padding(start = 7.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(Velt.Green))
        Text(name, fontSize = 11.sp, color = Velt.T2)
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
        color = Velt.T3,
        modifier = modifier
    )
}

@Composable
internal fun StatusRow(color: Color, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(color.copy(alpha = 0.2f)))
        Box(
            modifier = Modifier
                .size(16.dp)
                .border(1.5.dp, color.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(10.dp))
        }
        Text(
            label.uppercase(),
            fontSize = 12.sp, fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp, color = color
        )
        Box(Modifier.weight(1f).height(1.dp).background(color.copy(alpha = 0.2f)))
    }
}

// Saldo del pagador tras el cobro, con el "antes" = saldo posterior + el monto cobrado.
@Composable
private fun PayerBalanceRow(balanceUsdc: String, chargedCents: Long) {
    val after = balanceUsdc.toDoubleOrNull()
    val before = after?.let { it + chargedCents / 100.0 }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Velt.Surf)
            .border(1.dp, Velt.Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(tr("Payer balance", "Saldo del pagador").uppercase(), fontSize = 9.sp, letterSpacing = 1.sp, color = Velt.T3)
            before?.let {
                Text(
                    "$%,.2f → ".format(it),
                    fontSize = 11.sp, color = Velt.T3, fontFamily = DmSans
                )
            }
        }
        Text(
            "$$balanceUsdc",
            fontSize = 18.sp, fontFamily = DmSans, fontWeight = FontWeight.Light, color = Velt.Cyan
        )
    }
}

@Composable
private fun PayerCard(ensName: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Velt.Surf)
            .border(1.dp, Velt.Cyan.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Velt.CyanDark)
                .border(1.dp, Velt.Cyan.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = Velt.Cyan, modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(ensName, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Velt.T1, maxLines = 1)
            Text(tr("just now", "hace un momento"), fontSize = 11.sp, color = Velt.T2)
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Velt.Cyan.copy(alpha = 0.08f))
                .border(1.dp, Velt.Cyan.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(tr("verified", "verificado"), fontSize = 9.sp, color = Velt.Cyan)
        }
    }
}

@Composable
internal fun ResultGlow(color: Color) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.08f), Color.Transparent),
                    radius = 600f
                )
            )
    )
}

@Composable
internal fun ResultWaves(color: Color) {
    val waves = listOf(0L to 0.8f, 200L to 0.5f, 400L to 0.25f)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        waves.forEach { (delayMs, alpha) ->
            val anim = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                delay(delayMs)
                anim.animateTo(1f, tween(900))
            }
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .scale(1f + anim.value * 5f)
                    .alpha(alpha * (1f - anim.value))
                    .border(1.5.dp, color.copy(alpha = 0.6f), CircleShape)
            )
        }
    }
}

@Composable
private fun RedButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Velt.Red)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

@Composable
internal fun CancelTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 13.sp,
        color = Velt.T3,
        textDecoration = TextDecoration.Underline,
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    )
}

@Composable
internal fun CenteredSpinner(message: String) {
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
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        VeltWordmark(fontSize = 22)
        Spacer(Modifier.height(16.dp))
        Text(tr("Create your merchant", "Crea tu comercio"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Velt.T1)
        Spacer(Modifier.height(8.dp))
        Text(tr("You need a merchant to start charging.", "Necesitas un comercio para empezar a cobrar."), fontSize = 14.sp, color = Velt.T2)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            placeholder = { Text(tr("Merchant name", "Nombre del comercio"), color = Velt.T3) },
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
        PrimaryButton(text = tr("Create merchant", "Crear comercio"), enabled = name.isNotBlank()) {
            vm.createMerchant(name)
        }
    }
}

@Composable
internal fun CopyableHash(label: String, hash: String) {
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

internal fun formatUsd(amountCents: Long): String =
    "$%,d.%02d".format(amountCents / 100, amountCents % 100)

internal fun shortenHash(hash: String): String =
    if (hash.length <= 14) hash else "${hash.take(8)}…${hash.takeLast(6)}"
