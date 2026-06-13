package com.velt.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.velt.R
import com.velt.sensor.PalmCapturePhase
import com.velt.sensor.capturePalmTemplate
import com.velt.ui.theme.Velt
import kotlinx.coroutines.launch

private val btPermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/**
 * Login principal por palma: captura el template del sensor Velt y lo entrega a [onCaptured]
 * (que lo verifica contra el backend). Pide permisos Bluetooth en el primer escaneo.
 */
@Composable
fun PalmLoginScreen(
    strings: OnboardingStrings,
    lang: Lang,
    onLang: (Lang) -> Unit,
    verifyError: String?,
    onBack: () -> Unit,
    onCaptured: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var capturing by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(PalmCapturePhase.CONNECTING) }
    var handMessage by remember { mutableStateOf<String?>(null) }
    var captureError by remember { mutableStateOf<String?>(null) }

    fun runCapture() {
        if (capturing) return
        capturing = true
        captureError = null
        handMessage = null
        scope.launch {
            try {
                val template = capturePalmTemplate(
                    context = context,
                    deviceAddress = null,
                    onPhase = { phase = it },
                    onHandMessage = { handMessage = it },
                )
                onCaptured(template)
            } catch (e: Exception) {
                captureError = e.message ?: "Error con el sensor"
            } finally {
                capturing = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> if (result.values.all { it }) runCapture() }

    fun scan() {
        val granted = btPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) runCapture() else permissionLauncher.launch(btPermissions)
    }

    val accent = if (capturing) Velt.CyanLight else Velt.Cyan
    val handColor by animateColorAsState(accent, label = "hand")

    ObScaffold(lang, onLang) {
        Column(modifier = Modifier.fillMaxSize()) {
            ObBackHeader(strings.palmTitle, onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        strings.palmH,
                        fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Velt.T1,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        strings.palmSub,
                        fontSize = 13.sp, color = Velt.T2, textAlign = TextAlign.Center, lineHeight = 19.sp
                    )
                }

                Spacer(Modifier.height(8.dp))
                ScanRing(ringColor = accent, showPulses = capturing) {
                    Icon(
                        painterResource(R.drawable.ic_palm_icon), null,
                        tint = handColor, modifier = Modifier.size(52.dp)
                    )
                }

                Text(
                    text = when {
                        !capturing -> strings.palmHint
                        phase == PalmCapturePhase.CONNECTING -> if (lang == Lang.ES) "Conectando con el sensor..." else "Connecting to the sensor..."
                        phase == PalmCapturePhase.SCANNING -> handMessage
                            ?: (if (lang == Lang.ES) "Coloca tu palma en el lector" else "Place your palm on the reader")
                        else -> if (lang == Lang.ES) "Palma detectada, verificando..." else "Palm detected, verifying..."
                    },
                    fontSize = 12.sp, color = Velt.T3, textAlign = TextAlign.Center
                )

                (captureError ?: verifyError)?.let {
                    Text(it, fontSize = 12.sp, color = Velt.Red, textAlign = TextAlign.Center)
                }

                Spacer(Modifier.weight(1f))

                PrimaryButton(
                    text = if (capturing) (if (lang == Lang.ES) "Escaneando..." else "Scanning...") else strings.palmScanBtn,
                    containerColor = Velt.Cyan,
                    onClick = { scan() }
                )
            }
        }
    }
}
