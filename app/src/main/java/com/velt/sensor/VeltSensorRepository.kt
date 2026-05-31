package com.velt.sensor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Orquesta la comunicación con el sensor Velt vía [VeltSensorClient].
 *
 * El flujo de verificación con el bioserver lo realiza la UI usando [events] +
 * [VeltSensorBioService.verifyUser].
 */
class VeltSensorRepository(
    ctx: Context,
    sppDeviceName: String? = VeltSensorConfig.SPP_DEVICE_NAME
) {
    companion object {
        private const val TAG = "VeltSensorRepository"
    }

    private val client = VeltSensorClient(context = ctx, sppDeviceName = sppDeviceName)

    val events = client.events

    /**
     * Inicia la sesión: conecta SPP y envía el comando capture para empezar a leer la palma.
     * @return true si la conexión y el comando fueron exitosos.
     */
    suspend fun startSession(): Boolean {
        // 0) Wake BLE: despierta el subsistema de captura del sensor. Best-effort: si falla,
        //    se continúa con SPP igualmente (los logs del GATT ayudan a diagnosticar).
        Log.d(TAG, "startSession: wake BLE...")
        val woke = try {
            client.connectBleAndWake().await()
        } catch (t: Throwable) {
            Log.e(TAG, "startSession: error en wake BLE", t)
            false
        }
        Log.d(TAG, "startSession: wake BLE = $woke")
        if (woke) delay(800) // dar tiempo a que arranque el sensor de imagen

        Log.d(TAG, "startSession: conectando SPP...")
        if (!client.connectClassicAndStartReader().await()) {
            Log.e(TAG, "startSession: fallo al conectar SPP")
            return false
        }
        delay(500) // estabilizar la conexión
        Log.d(TAG, "startSession: enviando comando capture...")
        return client.sendCapture()
    }

    suspend fun stopCapture() {
        client.sendIdle()
    }

    /** Pone el LED del sensor en blanco parpadeante (indicador "listo para escanear"). */
    suspend fun setLedWhiteBlink() {
        client.setColor(0xFFFFFF, blinkEnabled = true, blinkOnMs = 200, blinkOffMs = 200)
    }

    fun close() {
        client.close()
    }
}
