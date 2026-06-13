package com.velt.sensor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

class VeltSensorRepository(
    ctx: Context,
    sppDeviceName: String? = VeltSensorConfig.SPP_DEVICE_NAME
) {
    companion object {
        private const val TAG = "VeltSensorRepository"
    }

    private val client = VeltSensorClient(context = ctx, sppDeviceName = sppDeviceName)

    val events = client.events

    suspend fun startSession(): Boolean = coroutineScope {
        // Wake BLE y conexión SPP arrancan en paralelo. Antes eran secuenciales: el wake
        // bloqueaba hasta 10s ANTES de empezar el SPP, así que el arranque tardaba la suma de
        // ambos. Ahora corren a la vez y el tiempo total es el del más lento. El `capture`
        // sigue después de ambos: sin el wake la cámara del sensor no enciende.
        Log.d(TAG, "startSession: wake BLE + SPP en paralelo...")
        val wakeDeferred = client.connectBleAndWake()
        val sppDeferred = client.connectClassicAndStartReader()

        val woke = try {
            wakeDeferred.await()
        } catch (t: Throwable) {
            Log.e(TAG, "startSession: error en wake BLE", t)
            false
        }
        Log.d(TAG, "startSession: wake BLE = $woke")

        if (!sppDeferred.await()) {
            Log.e(TAG, "startSession: fallo al conectar SPP")
            return@coroutineScope false
        }

        // Margen para que el subsistema termine de despertar tras el wake antes del capture.
        delay(250)
        Log.d(TAG, "startSession: enviando comando capture...")
        client.sendCapture()
    }

    suspend fun connect(): Boolean {
        Log.d(TAG, "connect: wake BLE...")
        val woke = try {
            client.connectBleAndWake().await()
        } catch (t: Throwable) {
            Log.e(TAG, "connect: error en wake BLE", t)
            false
        }
        if (woke) delay(800)
        Log.d(TAG, "connect: conectando SPP...")
        return client.connectClassicAndStartReader().await()
    }

    suspend fun stopCapture() {
        client.sendIdle()
    }

    suspend fun setLed(rgbInt: Int, blink: Boolean, onMs: Int = 300, offMs: Int = 300) {
        client.setColor(rgbInt, blinkEnabled = blink, blinkOnMs = onMs, blinkOffMs = offMs)
    }

    suspend fun setLedWhiteBlink() {
        client.setColor(0xFFFFFF, blinkEnabled = true, blinkOnMs = 200, blinkOffMs = 200)
    }

    fun close() {
        client.close()
    }
}
