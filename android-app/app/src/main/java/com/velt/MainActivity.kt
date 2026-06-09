package com.velt

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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.velt.ui.theme.AppTheme
import com.velt.ui.theme.Velt
import kotlinx.coroutines.launch

private enum class Screen { ONBOARDING, HOME, CONFIG, BLUETOOTH, PALM, LED }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppNavigation(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun AppNavigation(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var screen by remember { mutableStateOf(Screen.ONBOARDING) }
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
                    onConfigClick = { screen = Screen.CONFIG }
                )
                Screen.CONFIG -> ConfigMenuScreen(
                    selectedDeviceName = selectedName,
                    onBluetoothClick = { navigateWithBtPermissions(Screen.BLUETOOTH) },
                    onPalmClick = { navigateWithBtPermissions(Screen.PALM) },
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
    onLed: () -> Unit
) {
    ModalDrawerSheet(drawerContainerColor = Velt.Surf) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("Pruebas", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Velt.T1)
            Spacer(Modifier.height(2.dp))
            Text(
                "Herramientas del sensor (sin integrar)",
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
                label = { Text("Dispositivos Bluetooth") },
                selected = false,
                icon = { Icon(Icons.Filled.Bluetooth, contentDescription = null) },
                colors = itemColors,
                onClick = onBluetooth
            )
            Spacer(Modifier.height(4.dp))
            NavigationDrawerItem(
                label = { Text("Validar palma") },
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
                label = { Text("Probar LED") },
                selected = false,
                icon = { Icon(Icons.Filled.Lightbulb, contentDescription = null) },
                colors = itemColors,
                onClick = onLed
            )
        }
    }
}
