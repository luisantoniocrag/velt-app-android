package com.velt.ui.payments

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.velt.VeltApp
import com.velt.ui.onboarding.DesignScaled
import com.velt.ui.onboarding.GhostButton
import com.velt.ui.onboarding.PrimaryButton
import com.velt.ui.theme.DmSans
import com.velt.ui.theme.Velt

private val EVM_ADDRESS = Regex("^0x[a-fA-F0-9]{40}$")

@Composable
fun WithdrawScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as VeltApp
    val vm: WithdrawViewModel = viewModel(factory = WithdrawViewModel.factory(app.container.paymentRepository))

    DesignScaled {
        AnimatedContent(
            targetState = vm.state,
            transitionSpec = { fadeIn(tween(260)).togetherWith(fadeOut(tween(160))) },
            contentKey = { it::class },
            label = "withdraw-state",
            modifier = Modifier.fillMaxSize()
        ) { state ->
            when (state) {
                is WithdrawState.Loading -> CenteredSpinner("Cargando tu saldo...")
                is WithdrawState.EnterDetails -> EnterDetailsStep(vm, onBack)
                is WithdrawState.Processing -> ProcessingStep()
                is WithdrawState.Settled -> WithdrawSettledStep(vm, state, onBack)
                is WithdrawState.Failed -> WithdrawFailedStep(vm, state, onBack)
            }
        }
    }
}

@Composable
private fun EnterDetailsStep(vm: WithdrawViewModel, onBack: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }

    val amount = amountText.toDoubleOrNull() ?: 0.0
    val addressValid = EVM_ADDRESS.matches(address.trim())
    val amountValid = amount > 0.0 && amount <= vm.balance
    val canWithdraw = vm.custodial && addressValid && amountValid

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            VeltWordmark(fontSize = 22)
            vm.merchant?.let { MerchantPill(it.name) }
        }
        vm.merchant?.ensName?.let { ens ->
            Text(
                ens,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Velt.T2,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }

        Spacer(Modifier.height(26.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Velt.Surf)
                .border(1.dp, Velt.Border, RoundedCornerShape(16.dp))
                .padding(vertical = 18.dp)
        ) {
            SectionLabel("Saldo disponible")
            Spacer(Modifier.height(6.dp))
            Text(
                formatUsdc(vm.balance),
                fontSize = 40.sp, fontFamily = DmSans, fontWeight = FontWeight.ExtraLight,
                letterSpacing = (-1).sp, color = Velt.T1
            )
            Text("USDC", fontSize = 11.sp, color = Velt.T2)
        }

        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            singleLine = true,
            placeholder = { Text("Dirección destino (0x...)", color = Velt.T3) },
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            isError = address.isNotBlank() && !addressValid,
            colors = withdrawFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = amountText,
            onValueChange = { amountText = it.replace(",", ".") },
            singleLine = true,
            placeholder = { Text("Monto USDC", color = Velt.T3) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = amountText.isNotBlank() && !amountValid,
            trailingIcon = {
                Text(
                    "MAX",
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Velt.Cyan,
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .clickable { amountText = trimAmount(vm.balance) }
                )
            },
            colors = withdrawFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )

        if (!vm.custodial) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Esta cuenta es externa: Velt no custodia su llave y no puede retirar por ti.",
                fontSize = 12.sp, color = Velt.Amber
            )
        }
        vm.errorMessage?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontSize = 12.sp, color = Velt.Red)
        }

        Spacer(Modifier.weight(1f))
        PrimaryButton(
            text = if (amountValid) "Retirar ${formatUsdc(amount)}" else "Retirar",
            enabled = canWithdraw
        ) { vm.startWithdraw(address, amount) }
        Spacer(Modifier.height(8.dp))
        CancelTextButton("Volver", onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun ProcessingStep() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = Velt.Cyan, strokeWidth = 4.dp)
        Spacer(Modifier.height(24.dp))
        Text("Procesando retiro...", fontSize = 18.sp, color = Velt.T1)
        Spacer(Modifier.height(6.dp))
        Text("Enviando USDC a la dirección destino", fontSize = 13.sp, color = Velt.T2)
    }
}

@Composable
private fun WithdrawSettledStep(vm: WithdrawViewModel, state: WithdrawState.Settled, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        ResultGlow(Velt.Green)
        ResultWaves(Velt.Green)
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            StatusRow(color = Velt.Green, label = "enviado", icon = Icons.Filled.Check)
            Spacer(Modifier.height(10.dp))
            Text(
                formatUsdc(vm.lastAmount),
                fontSize = 46.sp, fontFamily = DmSans, fontWeight = FontWeight.ExtraLight,
                letterSpacing = (-2).sp, color = Velt.T1
            )
            Text("retirado de tu comercio", fontSize = 11.sp, color = Color.White.copy(alpha = 0.28f))
            state.txHash?.let { hash ->
                Spacer(Modifier.height(14.dp))
                CopyableHash(label = "Tx retiro", hash = hash)
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton(text = "Nuevo retiro") { vm.reset() }
            Spacer(Modifier.height(7.dp))
            GhostButton(text = "Volver al inicio", onClick = onBack)
        }
    }
}

@Composable
private fun WithdrawFailedStep(vm: WithdrawViewModel, state: WithdrawState.Failed, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        ResultGlow(Velt.Red)
        ResultWaves(Velt.Red)
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
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
                Text(
                    state.reason,
                    fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(text = "Reintentar") { vm.reset() }
            Spacer(Modifier.height(7.dp))
            GhostButton(text = "Volver al inicio", onClick = onBack)
        }
    }
}

@Composable
private fun withdrawFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Velt.Cyan,
    unfocusedBorderColor = Velt.Border,
    errorBorderColor = Velt.Red,
    focusedTextColor = Velt.T1,
    unfocusedTextColor = Velt.T1,
    errorTextColor = Velt.T1,
    cursorColor = Velt.Cyan
)

private fun formatUsdc(amount: Double): String = "$%,.2f".format(amount)

private fun trimAmount(amount: Double): String =
    "%.2f".format(amount).trimEnd('0').trimEnd('.')
