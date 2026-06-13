package com.velt

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.velt.ui.BluetoothScreen
import com.velt.ui.ConfigMenuScreen
import com.velt.ui.HomeScreen
import com.velt.ui.LedTestScreen
import com.velt.ui.PalmValidationScreen
import com.velt.ui.onboarding.OnboardingFlow
import com.velt.ui.payments.BalanceScreen
import com.velt.ui.payments.ChargeScreen
import com.velt.ui.payments.EnrollScreen
import com.velt.ui.payments.DepositResult
import com.velt.ui.i18n.tr
import com.velt.ui.payments.WithdrawScreen
import com.velt.ui.payments.parseDepositDeepLink
import com.velt.ui.theme.AppTheme
import com.velt.ui.theme.Velt
import kotlinx.coroutines.launch

private enum class Screen { ONBOARDING, HOME, CHARGE, WITHDRAW, BALANCE, ENROLL, CONFIG, BLUETOOTH, PALM, LED }

class MainActivity : ComponentActivity() {
    private val depositResult = mutableStateOf<DepositResult?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        depositResult.value = parseDepositDeepLink(intent)
        setContent {
            AppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppNavigation(modifier = Modifier.padding(innerPadding))
                    depositResult.value?.let { result ->
                        DepositResultDialog(result, onDismiss = { depositResult.value = null })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parseDepositDeepLink(intent)?.let { depositResult.value = it }
    }
}

@Composable
private fun DepositResultDialog(result: DepositResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Velt.Surf,
        title = {
            Text(
                if (result.ok) tr("Deposit started ✓", "Depósito iniciado ✓")
                else tr("Deposit failed", "Depósito fallido"),
                color = if (result.ok) Velt.T1 else Velt.Red
            )
        },
        text = {
            Column {
                Text(
                    if (result.ok) tr(
                        "The funds are on their way to the payer's account.",
                        "Los fondos están en camino a la cuenta del pagador."
                    )
                    else tr(
                        "The deposit couldn't be completed. Try again.",
                        "No se pudo completar el depósito. Inténtalo de nuevo."
                    ),
                    color = Velt.T2
                )
                result.transferId?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("Transfer: $it", "Transferencia: $it"),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Velt.T2
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("Got it", "Entendido"), color = Velt.Cyan)
            }
        }
    )
}

@Composable
private fun AppNavigation(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as VeltApp
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Sesión persistente: si ya hay tokens guardados, arranca en Home y salta el onboarding.
    var screen by remember {
        mutableStateOf(if (app.container.authRepository.isLoggedIn) Screen.HOME else Screen.ONBOARDING)
    }
    var returnScreen by remember { mutableStateOf(Screen.ONBOARDING) }
    var selectedAddress by remember { mutableStateOf<String?>(null) }
    var selectedName by remember { mutableStateOf<String?>(null) }

    val btPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    var pendingScreen by remember { mutableStateOf<Screen?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            pendingScreen?.let { screen = it }
        }
        pendingScreen = null
    }

    fun navigateWithBtPermissions(target: Screen) {
        if (screen != Screen.BLUETOOTH && screen != Screen.PALM) returnScreen = screen
        val granted = btPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            screen = target
        } else {
            pendingScreen = target
            permissionLauncher.launch(btPermissions)
        }
    }

    fun openFromDrawer(target: Screen) {
        scope.launch { drawerState.close() }
        navigateWithBtPermissions(target)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SensorTestDrawer(
                onBluetooth = { openFromDrawer(Screen.BLUETOOTH) },
                onPalm = { openFromDrawer(Screen.PALM) },
                onEnroll = { openFromDrawer(Screen.ENROLL) },
                onLed = { openFromDrawer(Screen.LED) }
            )
        }
    ) {
        Box(modifier = modifier) {
            when (screen) {
                Screen.ONBOARDING -> OnboardingFlow(
                    onFinish = { screen = Screen.HOME }
                )
                Screen.HOME -> HomeScreen(
                    onChargeClick = { navigateWithBtPermissions(Screen.CHARGE) },
                    onWithdrawClick = { screen = Screen.WITHDRAW },
                    onBalanceClick = { navigateWithBtPermissions(Screen.BALANCE) },
                    onEnrollClick = { navigateWithBtPermissions(Screen.ENROLL) },
                    onConfigClick = { screen = Screen.CONFIG }
                )
                Screen.CHARGE -> ChargeScreen(
                    deviceAddress = selectedAddress,
                    onBack = { screen = Screen.HOME }
                )
                Screen.WITHDRAW -> WithdrawScreen(
                    onBack = { screen = Screen.HOME }
                )
                Screen.BALANCE -> BalanceScreen(
                    deviceAddress = selectedAddress,
                    onBack = { screen = Screen.HOME }
                )
                Screen.ENROLL -> EnrollScreen(
                    deviceAddress = selectedAddress,
                    onBack = { screen = Screen.HOME }
                )
                Screen.CONFIG -> ConfigMenuScreen(
                    selectedDeviceName = selectedName,
                    onBluetoothClick = { navigateWithBtPermissions(Screen.BLUETOOTH) },
                    onPalmClick = { navigateWithBtPermissions(Screen.PALM) },
                    onEnrollClick = { navigateWithBtPermissions(Screen.ENROLL) },
                    onLogout = {
                        scope.launch {
                            app.container.authRepository.logout()
                            screen = Screen.ONBOARDING
                        }
                    },
                    onBack = { screen = Screen.HOME }
                )
                Screen.BLUETOOTH -> BluetoothScreen(
                    selectedAddress = selectedAddress,
                    onDeviceSelected = { address, name ->
                        selectedAddress = address
                        selectedName = name
                    },
                    onBack = { screen = returnScreen }
                )
                Screen.PALM -> PalmValidationScreen(
                    deviceAddress = selectedAddress,
                    onBack = { screen = returnScreen }
                )
                Screen.LED -> LedTestScreen(
                    deviceAddress = selectedAddress,
                    onBack = { screen = returnScreen }
                )
            }
        }
    }
}

@Composable
private fun SensorTestDrawer(
    onBluetooth: () -> Unit,
    onPalm: () -> Unit,
    onEnroll: () -> Unit,
    onLed: () -> Unit
) {
    ModalDrawerSheet(drawerContainerColor = Velt.Surf) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(24.dp))
            Text(tr("Tests", "Pruebas"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Velt.T1)
            Spacer(Modifier.height(2.dp))
            Text(
                tr("Sensor tools (not integrated)", "Herramientas del sensor (sin integrar)"),
                fontSize = 12.sp,
                color = Velt.T2
            )
            Spacer(Modifier.height(20.dp))

            val itemColors = NavigationDrawerItemDefaults.colors(
                unselectedContainerColor = Color.Transparent,
                selectedContainerColor = Velt.Card,
                unselectedTextColor = Velt.T1,
                unselectedIconColor = Velt.Cyan
            )

            NavigationDrawerItem(
                label = { Text(tr("Bluetooth devices", "Dispositivos Bluetooth")) },
                selected = false,
                icon = { Icon(Icons.Filled.Bluetooth, contentDescription = null) },
                colors = itemColors,
                onClick = onBluetooth
            )
            Spacer(Modifier.height(4.dp))
            NavigationDrawerItem(
                label = { Text(tr("Validate palm", "Validar palma")) },
                selected = false,
                icon = {
                    Icon(
                        painterResource(R.drawable.ic_palm_icon),
                        contentDescription = null,
                        modifier = Modifier.height(24.dp)
                    )
                },
                colors = itemColors,
                onClick = onPalm
            )
            Spacer(Modifier.height(4.dp))
            NavigationDrawerItem(
                label = { Text(tr("Enroll palm", "Registrar palma")) },
                selected = false,
                icon = {
                    Icon(
                        painterResource(R.drawable.ic_palm_icon),
                        contentDescription = null,
                        modifier = Modifier.height(24.dp)
                    )
                },
                colors = itemColors,
                onClick = onEnroll
            )
            Spacer(Modifier.height(4.dp))
            NavigationDrawerItem(
                label = { Text(tr("Test LED", "Probar LED")) },
                selected = false,
                icon = { Icon(Icons.Filled.Lightbulb, contentDescription = null) },
                colors = itemColors,
                onClick = onLed
            )
        }
    }
}
