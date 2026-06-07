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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.velt.ui.BluetoothScreen
import com.velt.ui.ConfigMenuScreen
import com.velt.ui.HomeScreen
import com.velt.ui.PalmValidationScreen
import com.velt.ui.onboarding.OnboardingFlow
import com.velt.ui.theme.AppTheme

private enum class Screen { ONBOARDING, HOME, CONFIG, BLUETOOTH, PALM }

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
    var screen by remember { mutableStateOf(Screen.ONBOARDING) }
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
                onBack = { screen = Screen.CONFIG }
            )
            Screen.PALM -> PalmValidationScreen(
                deviceAddress = selectedAddress,
                onBack = { screen = Screen.CONFIG }
            )
        }
    }
}
