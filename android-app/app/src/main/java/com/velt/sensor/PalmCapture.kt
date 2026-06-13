package com.velt.sensor

import android.content.Context
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class PalmCapturePhase { CONNECTING, SCANNING, VERIFYING }

/**
 * Captura un template biométrico del sensor Velt y lo devuelve en base64. Misma secuencia que
 * el cobro (wake BLE → SPP → capture), extraída para reutilizarse en el onboarding (login por
 * palma) y en la validación de pago. Lanza si el sensor no conecta o se agota el tiempo.
 */
suspend fun capturePalmTemplate(
    context: Context,
    deviceAddress: String?,
    onPhase: (PalmCapturePhase) -> Unit = {},
    onHandMessage: (String?) -> Unit = {},
): String = coroutineScope {
    val sensor = VeltSensorRepository(
        ctx = context.applicationContext,
        sppDeviceName = deviceAddress ?: VeltSensorConfig.SPP_DEVICE_NAME,
    )
    val templateDeferred = CompletableDeferred<String>()
    val collector = launch {
        sensor.events.collect { event ->
            when (event) {
                is VeltSensorClient.Event.Capture -> {
                    val b64 = Base64.getEncoder().encodeToString(event.biometric)
                    if (!templateDeferred.isCompleted) templateDeferred.complete(b64)
                }
                is VeltSensorClient.Event.Position ->
                    onHandMessage(VeltSensorClient.HandPositionStatus.fromState(event.state).message())
                is VeltSensorClient.Event.Error ->
                    if (!templateDeferred.isCompleted) {
                        templateDeferred.completeExceptionally(Exception(event.message))
                    }
                else -> Unit
            }
        }
    }

    try {
        onPhase(PalmCapturePhase.CONNECTING)
        val started = withTimeoutOrNull(
            VeltSensorConfig.BLE_CONNECT_TIMEOUT_MS + VeltSensorConfig.SPP_CONNECT_TIMEOUT_MS,
        ) { sensor.startSession() } ?: false
        if (!started) throw Exception("No se pudo conectar con el sensor Velt")

        onPhase(PalmCapturePhase.SCANNING)
        sensor.setLedWhiteBlink()

        val template = withTimeoutOrNull(VeltSensorConfig.CAPTURE_TIMEOUT_MS) {
            templateDeferred.await()
        } ?: throw Exception("Tiempo de espera agotado esperando la palma")

        onPhase(PalmCapturePhase.VERIFYING)
        sensor.stopCapture()
        template
    } finally {
        collector.cancel()
        sensor.close()
    }
}
