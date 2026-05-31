package com.velt.sensor

/**
 * Configuración del sensor Velt y su bioserver (Fulcrum).
 *
 * Reemplaza CLIENT_ID, SHARED_SECRET y ENDPOINT con tus credenciales reales.
 */
object VeltSensorConfig {
    // Credenciales del servidor biométrico
    const val CLIENT_ID = "23547798-2dd1-4f89-bb76-75f457e55094"
    const val SHARED_SECRET = "facd292b-93ce-450c-98a4-3d99464c1653"
    const val ENDPOINT = "https://openpalm.io/admin-app/"

    // Dispositivo Bluetooth SPP del sensor.
    // null = usar el primer dispositivo emparejado (o el primero cuyo nombre coincida con el prefijo del sensor).
    val SPP_DEVICE_NAME: String? = null

    // Timeouts
    const val BLE_CONNECT_TIMEOUT_MS = 10_000L
    const val SPP_CONNECT_TIMEOUT_MS = 15_000L
    const val CAPTURE_TIMEOUT_MS = 45_000L
    const val VERIFY_TIMEOUT_MS = 15_000L
}
