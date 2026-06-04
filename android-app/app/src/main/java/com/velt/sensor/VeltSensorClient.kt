package com.velt.sensor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.util.Base64
import java.util.UUID

class VeltSensorClient(
    private val context: Context,
    private val sppDeviceName: String? = null
) {
    companion object {
        private const val TAG = "VeltSensorClient"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val WAKE_SERVICE_UUID: UUID = UUID.fromString("00001815-0000-1000-8000-00805F9B34FB")
        private val WAKE_CHAR_UUID: UUID = UUID.fromString("00001815-0000-1000-8000-00805F9B34FB")
        private val AUTOMATION_IO_DIGITAL_UUID: UUID = UUID.fromString("00002A56-0000-1000-8000-00805F9B34FB")
    }

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var gatt: BluetoothGatt? = null
    private var sppSocket: BluetoothSocket? = null
    private var sppReaderJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<Event>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    enum class HandPositionStatus {
        NO_HANDS, NONE, ONLY_Z, ONLY_XYZ, ONLY_XYZ_AND_YAW, ALL, AWAY_HANDS, UNKNOWN;

        companion object {
            fun fromState(state: Int?): HandPositionStatus = when (state) {
                0 -> NO_HANDS
                1 -> NONE
                2 -> ONLY_Z
                3 -> ONLY_XYZ
                4 -> ONLY_XYZ_AND_YAW
                5 -> ALL
                6 -> AWAY_HANDS
                else -> UNKNOWN
            }
        }

        fun isGoodForCapture(): Boolean = this == ALL || this == ONLY_XYZ_AND_YAW

        fun message(): String = when (this) {
            NO_HANDS -> "Sin manos detectadas"
            NONE -> "Coloca tu mano sobre el sensor"
            ONLY_Z -> "Acerca más tu mano"
            ONLY_XYZ -> "Casi listo, mantén la posición"
            ONLY_XYZ_AND_YAW -> "Buena posición, mantenla"
            ALL -> "¡Posición óptima!"
            AWAY_HANDS -> "Mano muy alejada"
            UNKNOWN -> "Posicionando..."
        }
    }

    sealed class Event {
        data class Ack(val cmd: String, val state: String) : Event()
        data class Capture(val biometric: ByteArray) : Event()
        data class Position(val state: Int?) : Event()
        data class Unknown(val raw: String) : Event()
        data class Error(val message: String, val cause: Throwable? = null) : Event()
    }

    /**
     * Conecta por BLE (GATT) y escribe 0x01 en la característica de WAKE para despertar el
     * subsistema de captura del sensor. Sin esto, el sensor responde los comandos SPP (ACK ok)
     * pero la cámara nunca arranca y no emite eventos de posición/captura.
     *
     * Es best-effort: registra el GATT real descubierto para diagnóstico y mantiene la conexión
     * BLE abierta durante la sesión (se cierra en [close]).
     */
    @SuppressLint("MissingPermission")
    fun connectBleAndWake(timeoutMs: Long = 10_000): Deferred<Boolean> = scope.async {
        val adapter = adapter ?: run {
            emitError("Bluetooth no disponible para wake BLE"); return@async false
        }
        val device = resolveDevice(adapter) ?: run {
            emitError("No hay dispositivo emparejado para wake BLE"); return@async false
        }
        Log.d(TAG, "🔵 Wake BLE: conectando GATT a ${device.name} (${device.address})")

        val done = CompletableDeferred<Boolean>()
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "🔵 BLE onConnectionStateChange status=$status newState=$newState")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "🔵 BLE conectado, descubriendo servicios...")
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (!done.isCompleted) done.complete(false)
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    emitError("Error descubriendo servicios BLE: status=$status")
                    if (!done.isCompleted) done.complete(false)
                    return
                }
                Log.d(TAG, "🔵 BLE servicios descubiertos: ${g.services.size}")
                g.services.forEach { svc ->
                    Log.d(TAG, "   📦 Servicio ${svc.uuid}")
                    svc.characteristics.forEach { ch ->
                        Log.d(TAG, "      🔧 Char ${ch.uuid} props=0x${Integer.toHexString(ch.properties)}")
                    }
                }

                val svc = g.getService(WAKE_SERVICE_UUID)
                if (svc == null) {
                    emitError("Servicio WAKE 0x1815 no encontrado en el dispositivo BLE")
                    if (!done.isCompleted) done.complete(false)
                    return
                }
                val writableMask = BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                val ch = svc.getCharacteristic(WAKE_CHAR_UUID)
                    ?: svc.getCharacteristic(AUTOMATION_IO_DIGITAL_UUID)
                    ?: svc.characteristics.firstOrNull { (it.properties and writableMask) != 0 }
                if (ch == null) {
                    emitError("Característica WAKE no encontrada en el servicio 0x1815")
                    if (!done.isCompleted) done.complete(false)
                    return
                }
                Log.d(TAG, "🔵 Escribiendo WAKE (0x01) en char ${ch.uuid}...")
                val ok = writeWake(g, ch)
                if (!ok && !done.isCompleted) {
                    emitError("No se pudo iniciar la escritura WAKE")
                    done.complete(false)
                }
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                val success = status == BluetoothGatt.GATT_SUCCESS
                Log.d(TAG, "🔵 BLE onCharacteristicWrite status=$status success=$success")
                if (!done.isCompleted) done.complete(success)
            }
        }

        gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        withTimeoutOrNull(timeoutMs) { done.await() } ?: false.also {
            Log.w(TAG, "🔵 Wake BLE: timeout tras ${timeoutMs}ms")
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeWake(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        val value = byteArrayOf(0x01)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.value = value
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                g.writeCharacteristic(ch)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectClassicAndStartReader(): Deferred<Boolean> = scope.async {
        val adapter = adapter
        if (adapter == null) {
            emitError("Bluetooth no disponible en este dispositivo")
            return@async false
        }

        val device = resolveDevice(adapter)
        if (device == null) {
            emitError("No hay dispositivo SPP emparejado. Empareja el sensor Velt primero.")
            return@async false
        }
        Log.d(TAG, "Dispositivo SPP seleccionado: ${device.name} (${device.address})")

        val maxRetries = 5
        val retryDelayMs = 2000L
        var attempt = 0
        var socket: BluetoothSocket? = null

        while (attempt < maxRetries && socket == null) {
            attempt++
            try {
                Log.d(TAG, "Intento de conexión SPP $attempt/$maxRetries")
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                adapter.cancelDiscovery()
                s.connect()
                socket = s
                Log.d(TAG, "✅ SPP conectado en intento $attempt")
            } catch (e: IOException) {
                Log.e(TAG, "❌ Intento $attempt falló: ${e.message}")
                if (attempt < maxRetries) {
                    delay(retryDelayMs)
                } else {
                    emitError("Error conectando SPP tras $maxRetries intentos: ${e.message}")
                    return@async false
                }
            }
        }

        val connected = socket ?: return@async false
        sppSocket = connected
        startReader(connected)
        true
    }

    private fun startReader(socket: BluetoothSocket) {
        sppReaderJob?.cancel()
        sppReaderJob = scope.launch {
            val input = BufferedInputStream(socket.inputStream)
            val buffer = ByteArray(8 * 1024)
            val sb = StringBuilder()
            Log.d(TAG, "📖 Lector SPP iniciado")
            try {
                while (isActive) {
                    val read = input.read(buffer)
                    when {
                        read < 0 -> {
                            emitError("Conexión SPP cerrada por el dispositivo")
                            break
                        }
                        read == 0 -> continue
                        else -> {
                            val chunk = String(buffer, 0, read, Charsets.UTF_8)
                            Log.d(TAG, "📡 SPP leídos $read bytes: ${chunk.take(300)}")
                            sb.append(chunk)
                            processCompleteJsons(sb)
                        }
                    }
                }
            } catch (e: IOException) {
                emitError("Error de lectura SPP: ${e.message}", e)
            }
            Log.d(TAG, "📕 Lector SPP finalizado")
        }
    }

    /** Extrae y procesa objetos JSON completos (llaves balanceadas) del buffer acumulativo. */
    private fun processCompleteJsons(buffer: StringBuilder) {
        while (true) {
            val startIdx = buffer.indexOf("{")
            if (startIdx < 0) {
                buffer.clear()
                return
            }
            if (startIdx > 0) buffer.delete(0, startIdx)

            var depth = 0
            var inString = false
            var escape = false
            var endIdx = -1
            for (i in 0 until buffer.length) {
                val c = buffer[i]
                when {
                    escape -> escape = false
                    c == '\\' && inString -> escape = true
                    c == '"' -> inString = !inString
                    !inString && c == '{' -> depth++
                    !inString && c == '}' -> {
                        depth--
                        if (depth == 0) { endIdx = i; break }
                    }
                }
            }

            if (endIdx < 0) return // JSON incompleto, esperar más datos

            val jsonStr = buffer.substring(0, endIdx + 1)
            buffer.delete(0, endIdx + 1)
            while (buffer.isNotEmpty() && (buffer[0] == '\r' || buffer[0] == '\n' || buffer[0] == ' ')) {
                buffer.deleteCharAt(0)
            }
            parseAndHandle(jsonStr)
        }
    }

    private fun parseAndHandle(jsonStr: String) {
        if (jsonStr.isBlank()) return
        Log.d(TAG, "📥 JSON recibido: ${jsonStr.take(300)}")
        try {
            val json = JSONObject(jsonStr)
            val event = json.optString("event")
            when {
                // El sensor hace eco de los comandos que enviamos ({"cmd":...}); se ignoran.
                json.has("cmd") && !json.has("event") -> {
                    Log.d(TAG, "↩️ Eco de comando (ignorado): $jsonStr")
                }
                event == "capture" && json.has("userdata") -> {
                    val data = base64Decode(json.getString("userdata"))
                    Log.d(TAG, "📸 Evento CAPTURE recibido (${data.size} bytes)")
                    emit(Event.Capture(data))
                }
                event == "picture_updated" -> {
                    // No es necesario para la verificación; se omite.
                }
                event == "position" -> {
                    val state = json.optIntOrNull("STATE")
                    Log.d(TAG, "🖐️ Evento POSITION recibido (STATE=$state)")
                    emit(Event.Position(state))
                }
                json.has("event") && json.has("state") -> {
                    Log.d(TAG, "✔️ Evento ACK recibido (event=${json.getString("event")}, state=${json.getString("state")})")
                    emit(Event.Ack(json.getString("event"), json.getString("state")))
                }
                else -> {
                    Log.w(TAG, "❓ Evento NO reconocido (no coincide con capture/position/ack): $jsonStr")
                    emit(Event.Unknown(jsonStr))
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "💥 JSON inválido: ${jsonStr.take(300)}", t)
            emit(Event.Error("JSON inválido: ${jsonStr.take(120)}", t))
        }
    }

    suspend fun sendCapture(): Boolean = sendCommand("""{"cmd":"capture"}""")
    suspend fun sendIdle(): Boolean = sendCommand("""{"cmd":"idle"}""")
    suspend fun sendSleep(): Boolean = sendCommand("""{"cmd":"sleep"}""")

    suspend fun setColor(
        rgbInt: Int,
        blinkEnabled: Boolean,
        blinkOnMs: Int,
        blinkOffMs: Int
    ): Boolean {
        val payload = JSONObject().apply {
            put("cmd", "setcolor")
            put("color", rgbInt)
            put("blink_en", if (blinkEnabled) 1 else 0)
            put("blink_on", blinkOnMs)
            put("blink_off", blinkOffMs)
        }.toString()
        return sendCommand(payload)
    }

    private suspend fun sendCommand(json: String): Boolean = withContext(Dispatchers.IO) {
        val socket = sppSocket ?: return@withContext false.also { emitError("SPP no conectado") }
        try {
            val out = BufferedOutputStream(socket.outputStream)
            out.write((json + "\r").toByteArray(Charsets.UTF_8))
            out.flush()
            Log.d(TAG, "📤 Comando enviado: $json")
            true
        } catch (t: Throwable) {
            emitError("Error enviando comando: $json", t)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveDevice(adapter: BluetoothAdapter): BluetoothDevice? {
        val bonded = adapter.bondedDevices ?: emptySet()
        if (sppDeviceName != null) {
            return bonded.firstOrNull { it.address.equals(sppDeviceName, ignoreCase = true) }
                ?: bonded.firstOrNull { it.name.equals(sppDeviceName, ignoreCase = true) }
        }
        return bonded.firstOrNull { it.name?.startsWith("OpenPalm", ignoreCase = true) == true }
            ?: bonded.firstOrNull()
    }

    private fun emit(e: Event) { _events.tryEmit(e) }
    private fun emitError(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        _events.tryEmit(Event.Error(msg, t))
    }

    @SuppressLint("MissingPermission")
    fun close() {
        sppReaderJob?.cancel()
        sppReaderJob = null
        sppSocket?.runCatching { close() }
        sppSocket = null
        gatt?.runCatching {
            disconnect()
            close()
        }
        gatt = null
        scope.coroutineContext.cancelChildren()
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key)) runCatching { getInt(key) }.getOrNull() else null

    private fun base64Decode(b64: String): ByteArray = try {
        Base64.getDecoder().decode(b64)
    } catch (t: Throwable) {
        android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
    }
}
