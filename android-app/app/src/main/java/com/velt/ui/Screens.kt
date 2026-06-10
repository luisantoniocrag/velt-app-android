package com.velt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velt.R
import com.velt.sensor.VeltSensorBioService
import com.velt.sensor.VeltSensorConfig
import com.velt.sensor.VeltSensorRepository
import com.velt.sensor.VeltSensorClient
import com.velt.ui.payments.extractPersonId
import com.velt.ui.payments.openFundingPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Base64

@Composable
fun HomeScreen(
    onChargeClick: () -> Unit,
    onConfigClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Velt",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Cobra con la palma de la mano",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 40.dp)
        )

        Button(
            onClick = onChargeClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            Icon(
                painterResource(R.drawable.ic_palm_icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.size(12.dp))
            Text("Cobrar", fontSize = 18.sp)
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = onConfigClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null)
            Spacer(Modifier.size(12.dp))
            Text("Configuración", fontSize = 18.sp)
        }
    }
}

@Composable
fun ConfigMenuScreen(
    selectedDeviceName: String?,
    onBluetoothClick: () -> Unit,
    onPalmClick: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Configuración", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        Button(
            onClick = onBluetoothClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            Icon(Icons.Filled.Bluetooth, contentDescription = null)
            Spacer(Modifier.size(12.dp))
            Text("Dispositivos Bluetooth", fontSize = 18.sp)
        }

        Button(
            onClick = onPalmClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            Icon(
                painterResource(R.drawable.ic_palm_icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.size(12.dp))
            Text("Validar palma", fontSize = 18.sp)
        }

        Text(
            text = "Sensor: ${selectedDeviceName ?: "automático (primer emparejado)"}",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.weight(1f))

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Volver")
        }
    }
}

private enum class PalmStage { CONNECTING, SCANNING, VERIFYING, RESULT, ERROR }

@Composable
fun PalmValidationScreen(
    deviceAddress: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var stage by remember { mutableStateOf(PalmStage.CONNECTING) }
    var statusMessage by remember { mutableStateOf("Conectando con el sensor...") }
    var handMessage by remember { mutableStateOf<String?>(null) }
    var httpStatus by remember { mutableStateOf<Int?>(null) }
    var responseBody by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var recognizedPersonId by remember { mutableStateOf<String?>(null) }
    var attemptKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(attemptKey) {
        stage = PalmStage.CONNECTING
        statusMessage = "Conectando con el sensor..."
        handMessage = null
        httpStatus = null
        responseBody = ""
        summary = null
        errorMessage = null
        recognizedPersonId = null

        val repo = VeltSensorRepository(
            ctx = context.applicationContext,
            sppDeviceName = deviceAddress ?: VeltSensorConfig.SPP_DEVICE_NAME
        )

        // Suscribirse a los eventos ANTES de iniciar la sesión: el lector SPP empieza a emitir
        // en cuanto conecta el socket, y con replay=0 cualquier evento (incluido el propio
        // "capture" o un error del sensor) emitido antes de suscribirse se perdería.
        val templateDeferred = CompletableDeferred<String>()
        val collector = launch {
            repo.events.collect { event ->
                when (event) {
                    is VeltSensorClient.Event.Capture -> {
                        val b64 = try {
                            Base64.getEncoder().encodeToString(event.biometric)
                        } catch (e: Exception) {
                            android.util.Base64.encodeToString(event.biometric, android.util.Base64.NO_WRAP)
                        }
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
            ) {
                repo.startSession()
            } ?: false
            if (!started) throw Exception("No se pudo conectar con el dispositivo Velt")

            stage = PalmStage.SCANNING
            statusMessage = "Coloca tu palma sobre el sensor"
            repo.setLedWhiteBlink()

            val template = withTimeoutOrNull(VeltSensorConfig.CAPTURE_TIMEOUT_MS) {
                templateDeferred.await()
            } ?: throw Exception("Tiempo de espera agotado esperando la palma")

            stage = PalmStage.VERIFYING
            statusMessage = "Verificando con el bioserver..."
            repo.stopCapture()

            val (code, body) = withTimeoutOrNull(VeltSensorConfig.VERIFY_TIMEOUT_MS) {
                VeltSensorBioService.verifyUser(template)
            } ?: (-1 to "Tiempo de espera agotado con el bioserver")

            httpStatus = code
            responseBody = body
            summary = buildSummary(code, body)
            recognizedPersonId = if (code == 200) extractPersonId(body) else null

            stage = PalmStage.RESULT
        } catch (e: Exception) {
            errorMessage = e.message ?: "Error desconocido"
            stage = PalmStage.ERROR
        } finally {
            collector.cancel()
            repo.close()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("Validación de palma", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            contentAlignment = Alignment.Center
        ) {
            when (stage) {
                PalmStage.CONNECTING, PalmStage.SCANNING, PalmStage.VERIFYING ->
                    CircularProgressIndicator(modifier = Modifier.size(72.dp), strokeWidth = 6.dp)
                PalmStage.RESULT ->
                    Text(if (httpStatus == 200) "✅" else "⚠️", fontSize = 64.sp)
                PalmStage.ERROR ->
                    Text("❌", fontSize = 64.sp)
            }
        }

        Text(
            text = when (stage) {
                PalmStage.CONNECTING, PalmStage.SCANNING, PalmStage.VERIFYING -> statusMessage
                PalmStage.RESULT -> "Respuesta recibida del bioserver"
                PalmStage.ERROR -> errorMessage ?: "Error"
            },
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )

        if (stage == PalmStage.SCANNING && handMessage != null) {
            Text(handMessage!!, color = Color(0xFF2196F3), fontWeight = FontWeight.Bold)
        }

        if (stage == PalmStage.RESULT) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("HTTP Status: ${httpStatus ?: "-"}", fontWeight = FontWeight.Bold)
                    summary?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Respuesta:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        text = responseBody.ifBlank { "(vacío)" },
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (stage == PalmStage.RESULT && recognizedPersonId != null) {
            Button(
                onClick = { openFundingPage(context, recognizedPersonId!!) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Fondear cuenta")
            }
        }

        if (stage == PalmStage.RESULT || stage == PalmStage.ERROR) {
            Button(
                onClick = { attemptKey++ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reintentar")
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Volver")
        }
    }
}

private fun buildSummary(code: Int, body: String): String {
    if (code == -1) return "Error de red: $body"
    if (code != 200) return "El bioserver respondió con error (HTTP $code)"
    if (body.isBlank()) return "Respuesta vacía"
    return try {
        val json = JSONObject(body)
        val success = json.optBoolean("success", false) ||
            json.has("subjectId") || json.has("personId")
        if (success) {
            val personId = json.optString("personId", "")
            val subjectId = json.optString("subjectId", "")
            val id = personId.ifEmpty { subjectId }
            "Palma reconocida" + if (id.isNotEmpty()) " — ID: $id" else ""
        } else {
            "Palma no reconocida: " + json.optString("message", "sin coincidencia")
        }
    } catch (e: Exception) {
        "Respuesta no-JSON recibida"
    }
}
