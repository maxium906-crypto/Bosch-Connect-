package de.boschconnect.app

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.UUID

private val LDI_SERVICE = UUID.fromString("0000eb20-eaa2-11e9-81b4-2a2ae2dbcce4")
private val LDI_DATA = UUID.fromString("0000eb21-eaa2-11e9-81b4-2a2ae2dbcce4")
private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

data class RideData(
    val speed: Double? = null, val cadence: Int? = null,
    val riderPower: Int? = null, val battery: Int? = null, val odometerKm: Double? = null
)

class MainActivity : ComponentActivity() {
    private var gatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private val ride = mutableStateOf(RideData())
    private val status = mutableStateOf("Nicht verbunden")
    private val deviceName = mutableStateOf("Kein Fahrrad")
    private val scanResults = mutableStateListOf<BluetoothDevice>()
    private val visibleModules = mutableStateListOf("Geschwindigkeit", "Akku", "Leistung", "Trittfrequenz", "Kilometer")
    private val tab = mutableIntStateOf(0)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startScan() else status.value = "Bluetooth-Berechtigung fehlt"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        scanner = manager.adapter?.bluetoothLeScanner
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text("BOSCH CONNECT", style = MaterialTheme.typography.headlineSmall)
                        Text("Gen-5 Smart System · Live Data Interface")
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = tab.intValue == 0, onClick = { tab.intValue = 0 }, label = { Text("Dashboard") })
                            FilterChip(selected = tab.intValue == 1, onClick = { tab.intValue = 1 }, label = { Text("Fahrmodi") })
                            FilterChip(selected = tab.intValue == 2, onClick = { tab.intValue = 2 }, label = { Text("Module") })
                        }
                        Spacer(Modifier.height(12.dp))
                        when (tab.intValue) {
                            0 -> Dashboard(ride.value, status.value, deviceName.value, visibleModules.toList(), ::requestScan, ::disconnect)
                            1 -> ModesScreen()
                            else -> ModulesScreen(visibleModules.toList()) { name, enabled ->
                                if (enabled && name !in visibleModules) visibleModules.add(name)
                                if (!enabled) visibleModules.remove(name)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestScan() {
        val needed = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (needed.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(needed)
        } else startScan()
    }

    private fun startScan() {
        val s = scanner ?: run { status.value = "Bluetooth nicht verfügbar"; return }
        scanResults.clear()
        status.value = "Suche nach Bosch eBike …"
        val filterBuilder = ScanFilter.Builder()
        // Bosch LDI accessories are discovered through the Service Solicitation AD type.
        if (Build.VERSION.SDK_INT >= 29) filterBuilder.setServiceSolicitationUuid(android.os.ParcelUuid(LDI_SERVICE))
        else filterBuilder.setServiceUuid(android.os.ParcelUuid(LDI_SERVICE))
        val filter = filterBuilder.build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                if (scanResults.none { it.address == d.address }) scanResults.add(d)
                status.value = "Bosch-Gerät gefunden – antippen zum Verbinden"
            }
            override fun onScanFailed(errorCode: Int) { status.value = "Bluetooth-Suche fehlgeschlagen ($errorCode)" }
        }
        scanCallback = cb
        try { s.startScan(listOf(filter), settings, cb) }
        catch (_: SecurityException) { status.value = "Bluetooth-Zugriff nicht erlaubt" }
    }

    private fun connect(device: BluetoothDevice) {
        try {
            scanner?.stopScan(scanCallback)
            status.value = "Verbinde …"
            gatt?.close()
            gatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
                    runOnUiThread {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            status.value = "Verbunden"
                            deviceName.value = device.name ?: "Bosch eBike"
                            g.discoverServices()
                        } else {
                            status.value = "Nicht verbunden"
                            deviceName.value = "Kein Fahrrad"
                        }
                    }
                }
                override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
                    val service = g.getService(LDI_SERVICE)
                    val characteristic = service?.getCharacteristic(LDI_DATA)
                    if (characteristic == null) {
                        runOnUiThread { status.value = "Live-Data-Dienst nicht gefunden" }
                        return
                    }
                    try {
                        g.setCharacteristicNotification(characteristic, true)
                        characteristic.getDescriptor(CCCD)?.let { descriptor ->
                            if (Build.VERSION.SDK_INT >= 33) {
                                g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                g.writeDescriptor(descriptor)
                            }
                        }
                    } catch (_: Exception) { runOnUiThread { status.value = "Benachrichtigungen konnten nicht aktiviert werden" } }
                }
                override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                    if (characteristic.uuid == LDI_DATA) {
                        val decoded = ProtoLiveData.decode(value)
                        runOnUiThread { ride.value = decoded.merge(ride.value) }
                    }
                }
                @Deprecated("Legacy callback")
                override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    @Suppress("DEPRECATION")
                    characteristic.value?.let { bytes ->
                        val decoded = ProtoLiveData.decode(bytes)
                        runOnUiThread { ride.value = decoded.merge(ride.value) }
                    }
                }
            })
        } catch (_: SecurityException) { status.value = "Bluetooth-Verbindung nicht erlaubt" }
    }

    private fun disconnect() {
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null; ride.value = RideData(); status.value = "Getrennt"; deviceName.value = "Kein Fahrrad"
    }

    @Composable private fun Dashboard(data: RideData, state: String, name: String, modules: List<String>, onScan: () -> Unit, onDisconnect: () -> Unit) {
        Column {
            Text("$state · $name", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScan) { Text("Fahrrad suchen") }
                OutlinedButton(onClick = onDisconnect) { Text("Trennen") }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(modules) { module ->
                    val value = when(module) {
                        "Geschwindigkeit" -> data.speed?.let { "%.1f km/h".format(it) } ?: "-- km/h"
                        "Akku" -> data.battery?.let { "$it %" } ?: "-- %"
                        "Leistung" -> data.riderPower?.let { "$it W" } ?: "-- W"
                        "Trittfrequenz" -> data.cadence?.let { "$it rpm" } ?: "-- rpm"
                        "Kilometer" -> data.odometerKm?.let { "%.1f km".format(it) } ?: "-- km"
                        else -> "—"
                    }
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {
                            Text(module, style = MaterialTheme.typography.titleMedium)
                            Text(value, style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                }
                items(scanResults) { device ->
                    OutlinedButton(onClick = { connect(device) }, modifier = Modifier.fillMaxWidth()) {
                        Text((device.name ?: "Bosch eBike") + " · " + device.address)
                    }
                }
            }
        }
    }

    @Composable private fun ModesScreen() {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Fahrmodi", style = MaterialTheme.typography.titleLarge)
            Text("Hier kannst du die verfügbaren Modi ansehen. Das Ändern von Motorparametern wird von Boschs öffentlicher Live-Data-Schnittstelle nicht unterstützt.")
            listOf("OFF", "ECO", "TOUR", "AUTO", "eMTB", "SPORT", "TURBO").forEach { mode ->
                ElevatedCard(Modifier.fillMaxWidth()) { Text(mode, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
            }
            Text("Zum tatsächlichen Ändern freigegebener Fahrmodus-Einstellungen bitte die offizielle Bosch eBike Flow App verwenden. Verfügbarkeit hängt vom Fahrradmodell und Hersteller ab.")
        }
    }

    @Composable private fun ModulesScreen(modules: List<String>, onChange: (String, Boolean) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Dashboard-Module", style = MaterialTheme.typography.titleLarge)
            Text("Wähle aus, welche Kacheln im Dashboard erscheinen.")
            listOf("Geschwindigkeit", "Akku", "Leistung", "Trittfrequenz", "Kilometer").forEach { item ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item)
                    Switch(checked = item in modules, onCheckedChange = { onChange(item, it) })
                }
            }
            Text("Die Auswahl gilt bis zum Schließen der App (Speichern folgt in einer späteren Version).", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun RideData.merge(old: RideData) = RideData(
    speed ?: old.speed, cadence ?: old.cadence, riderPower ?: old.riderPower,
    battery ?: old.battery, odometerKm ?: old.odometerKm
)

/** Minimal protobuf wire decoder for the Bosch LiveData fields used here. */
private object ProtoLiveData {
    fun decode(bytes: ByteArray): RideData {
        var i = 0
        var speed: Double? = null; var cadence: Int? = null
        var power: Int? = null; var battery: Int? = null; var odo: Double? = null
        while (i < bytes.size) {
            val key = readVarint(bytes, i); i = key.second
            val field = (key.first shr 3).toInt()
            val wire = (key.first and 7).toInt()
            if (wire == 0) {
                val v = readVarint(bytes, i); i = v.second
                when (field) {
                    1 -> speed = v.first.toDouble() / 100.0
                    2 -> cadence = v.first.toInt()
                    5 -> power = v.first.toInt()
                    10 -> battery = v.first.toInt()
                    12 -> odo = v.first.toDouble() / 1000.0
                }
            } else {
                i = when (wire) {
                    1 -> i + 8
                    2 -> { val len = readVarint(bytes, i); len.second + len.first.toInt() }
                    5 -> i + 4
                    else -> bytes.size
                }.coerceAtMost(bytes.size)
            }
        }
        return RideData(speed, cadence, power, battery, odo)
    }
    private fun readVarint(b: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L; var shift = 0; var p = start
        while (p < b.size && shift < 64) {
            val x = b[p++].toInt() and 0xff
            result = result or ((x and 0x7f).toLong() shl shift)
            if (x and 0x80 == 0) break
            shift += 7
        }
        return result to p
    }
}
