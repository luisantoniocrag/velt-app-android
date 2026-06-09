package com.velt.sensor

import android.content.Context
import android.util.Log
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

    suspend fun startSession(): Boolean {
        // Wake BLE: despierta el subsistema de captura del sensor. Best-effort: si falla,
        // se continúa con SPP igualmente (sin el wake la cámara nunca arranca).
        Log.d(TAG, "startSession: wake BLE...")
        val woke = try {
            client.connectBleAndWake().await()
        } catch (t: Throwable) {
            Log.e(TAG, "startSession: error en wake BLE", t)
            false
        }
        Log.d(TAG, "startSession: wake BLE = $woke")
        if (woke) delay(800)

        Log.d(TAG, "startSession: conectando SPP...")
        if (!client.connectClassicAndStartReader().await()) {
            Log.e(TAG, "startSession: fallo al conectar SPP")
            return false
        }
        delay(500)
        Log.d(TAG, "startSession: enviando comando capture...")
        return client.sendCapture()
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
