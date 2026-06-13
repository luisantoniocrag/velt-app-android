package com.velt.ui.payments

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velt.R
import com.velt.VeltApp
import com.velt.data.remote.ApiResult
import com.velt.data.remote.PayerTransaction
import com.velt.data.remote.PayerWallet
import com.velt.sensor.PalmCapturePhase
import com.velt.sensor.VeltSensorBioService
import com.velt.sensor.capturePalmTemplate
import com.velt.ui.i18n.tr
import com.velt.ui.onboarding.DesignScaled
import com.velt.ui.onboarding.GhostButton
import com.velt.ui.onboarding.PrimaryButton
import com.velt.ui.theme.DmSans
import com.velt.ui.theme.Velt
import kotlinx.coroutines.launch

private sealed interface BalanceState {
    data object Idle : BalanceState
    data class Scanning(val phase: PalmCapturePhase, val hand: String?) : BalanceState
    data object Loading : BalanceState
    data class Loaded(val wallet: PayerWallet) : BalanceState
    data class Failed(val reason: String) : BalanceState
}

@Composable
fun BalanceScreen(deviceAddress: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = (context.applicationContext as VeltApp).container.paymentRepository
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<BalanceState>(BalanceState.Idle) }

    fun scan() {
        scope.launch {
            state = BalanceState.Scanning(PalmCapturePhase.CONNECTING, null)
            try {
                val template = capturePalmTemplate(
                    context = context,
                    deviceAddress = deviceAddress,
                    onPhase = { p ->
                        state = BalanceState.Scanning(p, (state as? BalanceState.Scanning)?.hand)
                    },
                    onHandMessage = { h ->
                        (state as? BalanceState.Scanning)?.let { state = it.copy(hand = h) }
                    },
                )
                state = BalanceState.Loading
                val (code, body) = VeltSensorBioService.verifyUser(template)
                val personId = if (code == 200) extractPersonId(body) else null
                if (personId == null) {
                    state = BalanceState.Failed(tr("Palm not recognized.", "Palma no reconocida."))
                    return@launch
                }
                state = when (val r = repo.payerWallet(personId)) {
                    is ApiResult.Success -> BalanceState.Loaded(r.data)
                    is ApiResult.Failure -> BalanceState.Failed(
                        if (r.code == "wallet_not_found")
                            tr("This palm has no wallet yet.", "Esta palma aún no tiene wallet.")
                        else r.message ?: tr("Couldn't load the balance.", "No se pudo cargar el saldo.")
                    )
                    is ApiResult.NetworkError -> BalanceState.Failed(
                        tr("No connection. Try again.", "Sin conexión. Inténtalo de nuevo.")
                    )
                }
            } catch (e: Exception) {
                state = BalanceState.Failed(e.message ?: tr("Sensor error", "Error con el sensor"))
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
            Text(
                tr("Scan to get balance", "Escanea para ver tu saldo"),
                fontSize = 13.sp, color = Velt.T2
            )
            Spacer(Modifier.height(24.dp))

            when (val s = state) {
                is BalanceState.Idle -> ScanPrompt(
                    hint = tr("Place your palm to see your wallet.", "Coloca tu palma para ver tu wallet."),
                    onScan = ::scan
                )
                is BalanceState.Scanning -> ScanStatus(
                    when (s.phase) {
                        PalmCapturePhase.CONNECTING -> tr("Connecting to the sensor...", "Conectando con el sensor...")
                        PalmCapturePhase.SCANNING -> s.hand ?: tr("Place your palm on the reader", "Coloca tu palma en el lector")
                        PalmCapturePhase.VERIFYING -> tr("Palm detected...", "Palma detectada...")
                    }
                )
                is BalanceState.Loading -> ScanStatus(tr("Loading your wallet...", "Cargando tu wallet..."))
                is BalanceState.Loaded -> WalletInfo(s.wallet, onRescan = ::scan)
                is BalanceState.Failed -> {
                    ScanPrompt(hint = s.reason, hintColor = Velt.Red, onScan = ::scan)
                }
            }

            Spacer(Modifier.weight(1f))
            GhostButton(text = tr("Back", "Volver"), onClick = onBack)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ScanPrompt(hint: String, hintColor: Color = Velt.T3, onScan: () -> Unit) {
    Box(
        modifier = Modifier
            .size(150.dp)
            .clip(CircleShape)
            .background(Velt.Cyan.copy(alpha = 0.05f))
            .border(1.5.dp, Velt.Cyan, CircleShape)
            .clickable { onScan() },
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(R.drawable.ic_palm_icon), null, tint = Velt.Cyan, modifier = Modifier.size(56.dp))
    }
    Spacer(Modifier.height(20.dp))
    Text(hint, fontSize = 13.sp, color = hintColor, textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))
    PrimaryButton(text = tr("Scan palm", "Escanear palma"), onClick = onScan)
}

@Composable
private fun ScanStatus(message: String) {
    Box(
        modifier = Modifier
            .size(150.dp)
            .clip(CircleShape)
            .background(Velt.Cyan.copy(alpha = 0.05f))
            .border(1.5.dp, Velt.CyanLight, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(R.drawable.ic_palm_icon), null, tint = Velt.CyanLight, modifier = Modifier.size(56.dp))
    }
    Spacer(Modifier.height(20.dp))
    Text(message, fontSize = 14.sp, color = Velt.T1, textAlign = TextAlign.Center)
}

@Composable
private fun WalletInfo(wallet: PayerWallet, onRescan: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Velt.Surf)
            .border(1.dp, Velt.Border, RoundedCornerShape(16.dp))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        wallet.ensName?.let {
            Text(it, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Velt.Cyan, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))
        }
        Text("$${wallet.usdcBalance}", fontSize = 40.sp, fontFamily = DmSans, fontWeight = FontWeight.ExtraLight, color = Velt.T1)
        Text("USDC", fontSize = 11.sp, color = Velt.T2)
        Spacer(Modifier.height(8.dp))
        Text(shortAddress(wallet.address), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Velt.T3)
    }

    Spacer(Modifier.height(16.dp))
    if (wallet.transactions.isEmpty()) {
        Text(tr("No transactions yet.", "Sin transacciones aún."), fontSize = 12.sp, color = Velt.T3)
    } else {
        Text(
            tr("Recent activity", "Actividad reciente").uppercase(),
            fontSize = 10.sp, color = Velt.T3, letterSpacing = 1.2.sp,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().height(180.dp)) {
            items(wallet.transactions) { tx -> TransactionRow(tx) }
        }
    }
    Spacer(Modifier.height(12.dp))
    PrimaryButton(text = tr("Scan again", "Escanear de nuevo"), onClick = onRescan)
}

@Composable
private fun TransactionRow(tx: PayerTransaction) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Velt.Card)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(tx.merchant ?: tr("Payment", "Pago"), fontSize = 13.sp, color = Velt.T1, maxLines = 1)
            Text(tx.status, fontSize = 10.sp, color = statusColor(tx.status))
        }
        Text(
            "-$${"%,.2f".format(tx.amount ?: 0.0)}",
            fontSize = 14.sp, fontFamily = DmSans, color = Velt.T1
        )
    }
}

private fun statusColor(status: String): Color = when (status) {
    "settled" -> Velt.Green
    "failed" -> Velt.Red
    "held" -> Velt.Amber
    else -> Velt.T2
}

private fun shortAddress(a: String): String = if (a.length <= 12) a else "${a.take(7)}…${a.takeLast(5)}"
