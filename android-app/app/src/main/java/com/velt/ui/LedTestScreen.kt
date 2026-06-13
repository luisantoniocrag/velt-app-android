package com.velt.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.velt.ui.i18n.tr
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velt.sensor.VeltSensorClient
import com.velt.sensor.VeltSensorConfig
import com.velt.sensor.VeltSensorRepository
import com.velt.ui.theme.Velt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

private enum class LedStage { CONNECTING, READY, ERROR }

private data class LedPreset(val label: String, val r: Int, val g: Int, val b: Int)

private val ledPresets = listOf(
    LedPreset("Cian", 0, 212, 200),
    LedPreset("Rojo", 255, 0, 0),
    LedPreset("Verde", 34, 212, 94),
    LedPreset("Azul", 59, 130, 246),
    LedPreset("Blanco", 255, 255, 255),
    LedPreset("Apagado", 0, 0, 0)
)

@Composable
fun LedTestScreen(deviceAddress: String? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember {
        VeltSensorRepository(
            ctx = context.applicationContext,
            sppDeviceName = deviceAddress ?: VeltSensorConfig.SPP_DEVICE_NAME
        )
    }

    var stage by remember { mutableStateOf(LedStage.CONNECTING) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var r by remember { mutableIntStateOf(0) }
    var g by remember { mutableIntStateOf(212) }
    var b by remember { mutableIntStateOf(200) }
    var blink by remember { mutableStateOf(false) }
    var blinkOn by remember { mutableIntStateOf(300) }
    var blinkOff by remember { mutableIntStateOf(300) }
    var eventLog by remember { mutableStateOf(listOf<String>()) }

    val colorInt = (r shl 16) or (g shl 8) or b

    DisposableEffect(Unit) { onDispose { repo.close() } }

    LaunchedEffect(Unit) {
        repo.events.collect { e ->
            val line = when (e) {
                is VeltSensorClient.Event.Ack -> "ACK ${e.cmd.trim()} = ${e.state}"
                is VeltSensorClient.Event.Error -> "ERR ${e.message}"
                is VeltSensorClient.Event.Position -> "position STATE=${e.state}"
                is VeltSensorClient.Event.Capture -> "capture (${e.biometric.size}B)"
                is VeltSensorClient.Event.Unknown -> "raw ${e.raw.take(60)}"
            }
            eventLog = (eventLog + line).takeLast(8)
        }
    }

    LaunchedEffect(Unit) {
        stage = LedStage.CONNECTING
        val connected = withTimeoutOrNull(
            VeltSensorConfig.BLE_CONNECT_TIMEOUT_MS + VeltSensorConfig.SPP_CONNECT_TIMEOUT_MS
        ) {
            repo.connect()
        } ?: false
        if (connected) {
            stage = LedStage.READY
        } else {
            errorMessage = tr("Couldn't connect to the Velt device", "No se pudo conectar con el dispositivo Velt")
            stage = LedStage.ERROR
        }
    }

    fun send() {
        scope.launch { repo.setLed(colorInt, blink, blinkOn, blinkOff) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Velt.Bg)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(tr("LED test", "Prueba de LED"), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Velt.T1)

        when (stage) {
            LedStage.CONNECTING -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp, color = Velt.Cyan)
                Text(tr("Connecting to the sensor...", "Conectando con el sensor..."), color = Velt.T2)
            }
            LedStage.ERROR -> Text(errorMessage ?: tr("Error", "Error"), color = Velt.Red)
            LedStage.READY -> Text(tr("Connected", "Conectado"), color = Velt.Green)
        }

        if (stage == LedStage.READY) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(red = r, green = g, blue = b))
                    .border(1.dp, Velt.Border, RoundedCornerShape(16.dp))
            )

            ColorSlider("R", r, Color(0xFFFF5A5A), onChange = { r = it }, onCommit = { send() })
            ColorSlider("G", g, Color(0xFF22D45E), onChange = { g = it }, onCommit = { send() })
            ColorSlider("B", b, Color(0xFF3B82F6), onChange = { b = it }, onCommit = { send() })

            PresetRow(onPick = { p -> r = p.r; g = p.g; b = p.b; send() })

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("Blink (blink_en)", "Parpadeo (blink_en)"), color = Velt.T1, fontSize = 15.sp)
                Switch(checked = blink, onCheckedChange = { blink = it; send() })
            }

            if (blink) {
                MsSlider("blink_on", blinkOn, onChange = { blinkOn = it }, onCommit = { send() })
                MsSlider("blink_off", blinkOff, onChange = { blinkOff = it }, onCommit = { send() })
            }

            JsonPreview(colorInt = colorInt, blink = blink, blinkOn = blinkOn, blinkOff = blinkOff)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { scope.launch { repo.stopCapture() } },
                    modifier = Modifier.weight(1f)
                ) { Text("idle") }
                OutlinedButton(
                    onClick = { send() },
                    modifier = Modifier.weight(1f)
                ) { Text(tr("Resend color", "Reenviar color")) }
            }

            EventLog(eventLog)
        }

        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(tr("Back", "Volver"))
        }
    }
}

@Composable
private fun EventLog(lines: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Velt.Surf)
            .border(1.dp, Velt.Border, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text(tr("Sensor events", "Eventos del sensor"), color = Velt.T2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        if (lines.isEmpty()) {
            Text(tr("(no events)", "(sin eventos)"), color = Velt.T3, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        } else {
            lines.forEach { line ->
                val color = when {
                    line.startsWith("ERR") -> Velt.Red
                    line.startsWith("ACK") -> Velt.Green
                    else -> Velt.T2
                }
                Text(line, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ColorSlider(label: String, value: Int, accent: Color, onChange: (Int) -> Unit, onCommit: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Velt.T2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(value.toString(), color = Velt.T1, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            onValueChangeFinished = onCommit,
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = Velt.Card
            )
        )
    }
}

@Composable
private fun MsSlider(label: String, value: Int, onChange: (Int) -> Unit, onCommit: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Velt.T2, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Text("$value ms", color = Velt.T1, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange((it / 50).toInt() * 50) },
            onValueChangeFinished = onCommit,
            valueRange = 0f..2000f,
            colors = SliderDefaults.colors(
                thumbColor = Velt.Cyan,
                activeTrackColor = Velt.Cyan,
                inactiveTrackColor = Velt.Card
            )
        )
    }
}

@Composable
private fun PresetRow(onPick: (LedPreset) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ledPresets.forEach { p ->
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(red = p.r, green = p.g, blue = p.b))
                    .border(1.dp, Velt.Border, CircleShape)
                    .clickable { onPick(p) }
            )
        }
    }
}

@Composable
private fun JsonPreview(colorInt: Int, blink: Boolean, blinkOn: Int, blinkOff: Int) {
    val hex = String.format(Locale.US, "#%06X", colorInt)
    val json = buildString {
        append("{\n")
        append("  \"cmd\": \"setcolor\",\n")
        append("  \"color\": $colorInt,        // $hex\n")
        append("  \"blink_en\": ${if (blink) 1 else 0},\n")
        append("  \"blink_on\": $blinkOn,\n")
        append("  \"blink_off\": $blinkOff\n")
        append("}")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Velt.Surf)
            .border(1.dp, Velt.Border, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text("Comando enviado", color = Velt.T2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(json, color = Velt.Cyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}
