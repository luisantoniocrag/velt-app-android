package com.velt.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * Prefijo con el que el sensor Velt se anuncia por Bluetooth.
 * NOTA: "OpenPalm" es el nombre de fábrica del hardware; debe coincidir literalmente para
 * descubrir/filtrar el dispositivo, por eso no forma parte del branding de la app.
 */
private const val DEVICE_NAME_PREFIX = "OpenPalm"

/** True si el nombre del dispositivo empieza por [DEVICE_NAME_PREFIX]. */
@SuppressLint("MissingPermission")
private fun BluetoothDevice.isVeltSensor(): Boolean =
    name?.startsWith(DEVICE_NAME_PREFIX, ignoreCase = true) == true

/**
 * Pantalla de gestión Bluetooth: lista dispositivos emparejados, busca y empareja nuevos,
 * y permite seleccionar el dispositivo SPP que usará el flujo de palma.
 *
 * Solo se muestran los dispositivos cuyo nombre empieza por el prefijo configurado.
 */
@SuppressLint("MissingPermission")
@Composable
fun BluetoothScreen(
    selectedAddress: String?,
    onDeviceSelected: (address: String, name: String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val adapter = remember {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    var isEnabled by remember { mutableStateOf(adapter?.isEnabled == true) }
    var paired by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var discovered by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var bonding by remember { mutableStateOf<Set<String>>(emptySet()) }
    var scanError by remember { mutableStateOf<String?>(null) }

    fun refreshPaired() {
        paired = adapter?.bondedDevices?.filter { it.isVeltSensor() } ?: emptyList()
    }

    fun startScan() {
        scanError = null
        discovered = emptyList()
        adapter?.cancelDiscovery()
        // En Android 11 y anteriores el descubrimiento exige los servicios de ubicación activos.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm?.isLocationEnabled != true) {
                scanError = "Activa la ubicación del sistema para buscar dispositivos."
                return
            }
        }
        val started = adapter?.startDiscovery() == true
        if (!started) {
            scanError = "No se pudo iniciar la búsqueda. Revisa los permisos de Bluetooth."
        }
    }

    // Receiver para descubrimiento, estado del adaptador y cambios de emparejamiento.
    DisposableEffect(Unit) {
        refreshPaired()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        @Suppress("DEPRECATION")
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        device?.let { d ->
                            if (d.isVeltSensor() &&
                                d.bondState != BluetoothDevice.BOND_BONDED &&
                                discovered.none { it.address == d.address }
                            ) {
                                discovered = discovered + d
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                        isScanning = true
                        discovered = emptyList()
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        isScanning = false
                    }
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        isEnabled = adapter?.isEnabled == true
                        if (isEnabled) refreshPaired()
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        @Suppress("DEPRECATION")
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        val bondState = intent.getIntExtra(
                            BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR
                        )
                        device?.let { d ->
                            when (bondState) {
                                BluetoothDevice.BOND_BONDING -> bonding = bonding + d.address
                                BluetoothDevice.BOND_BONDED -> {
                                    bonding = bonding - d.address
                                    discovered = discovered.filter { it.address != d.address }
                                    refreshPaired()
                                }
                                BluetoothDevice.BOND_NONE -> {
                                    bonding = bonding - d.address
                                    refreshPaired()
                                }
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // En Android 13+ (targetSdk 33+) registerReceiver exige declarar la exportación;
        // omitirlo lanza SecurityException y rompía el descubrimiento de dispositivos.
        ContextCompat.registerReceiver(
            context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { adapter?.cancelDiscovery() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Dispositivos Bluetooth", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        if (!isEnabled) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Bluetooth está desactivado. Actívalo desde los ajustes del sistema.",
                    color = Color(0xFFF44336),
                    modifier = Modifier.padding(16.dp)
                )
            }
            OutlinedButton(
                onClick = { isEnabled = adapter?.isEnabled == true; refreshPaired() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Reintentar") }
        } else {
            // Botón de búsqueda
            Button(
                onClick = {
                    if (isScanning) {
                        adapter?.cancelDiscovery()
                    } else {
                        startScan()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Buscando... (toca para detener)")
                } else {
                    Text("Buscar dispositivos")
                }
            }

            scanError?.let {
                Text(
                    it,
                    color = Color(0xFFF44336),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (paired.isNotEmpty()) {
                    item {
                        Text(
                            "Emparejados",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2196F3),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(paired) { device ->
                        DeviceRow(
                            name = device.name ?: "Desconocido",
                            address = device.address,
                            isSelected = device.address == selectedAddress,
                            isBonding = false,
                            actionLabel = if (device.address == selectedAddress) "Seleccionado" else "Usar",
                            onAction = { onDeviceSelected(device.address, device.name ?: device.address) }
                        )
                    }
                }

                val available = discovered.filter { it.bondState != BluetoothDevice.BOND_BONDED }
                if (available.isNotEmpty()) {
                    item {
                        Text(
                            "Disponibles",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF9E9E9E),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    items(available) { device ->
                        DeviceRow(
                            name = device.name ?: "Desconocido",
                            address = device.address,
                            isSelected = false,
                            isBonding = bonding.contains(device.address),
                            actionLabel = "Emparejar",
                            onAction = {
                                adapter?.cancelDiscovery()
                                device.createBond()
                            }
                        )
                    }
                }

                if (paired.isEmpty() && available.isEmpty()) {
                    item {
                        Text(
                            text = if (isScanning) {
                                "Buscando sensores \"$DEVICE_NAME_PREFIX\"..."
                            } else {
                                "No se encontraron dispositivos \"$DEVICE_NAME_PREFIX\". " +
                                    "Toca \"Buscar dispositivos\"."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                }
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Volver")
        }
    }
}

@Composable
private fun DeviceRow(
    name: String,
    address: String,
    isSelected: Boolean,
    isBonding: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Medium)
                Text(
                    address,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.size(8.dp))
            when {
                isBonding -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
                isSelected -> Text(
                    actionLabel,
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Bold
                )
                else -> OutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
