package com.nmaster23.rustdrop.android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.nmaster23.rustdrop.android.ui.theme.RustdropAndroidTheme
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.uuid.Uuid

val targetService: java.util.UUID = java.util.UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
const val REQUEST_ENABLE_BT = 1

class MainActivity : ComponentActivity() {
    val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach {
            Log.d("Permissions", "${it.key} = ${it.value}")
        }
    }
    val discoveredDevices = mutableStateListOf<BluetoothDevice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissionHandling()
        setContent {
            RustdropAndroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    userInterface(
                        modifier = Modifier.padding(innerPadding),
                        devices = discoveredDevices,
                        onRefresh = { scanBleDevices() }
                    )
                }
            }
        }
    }
}

@Composable
fun userInterface(modifier: Modifier = Modifier, devices: List<BluetoothDevice>, onRefresh: () -> Unit = {}) {
    Column(modifier = modifier) {
        Button(
            onClick = onRefresh
        ) {
            Text("Refresh Discovery")
        }
        devices.forEach { device ->
            Button(onClick = {}, modifier = Modifier.padding(top = 8.dp)) {
                Text(text = device.address)
            }
        }
    }
}

fun MainActivity.permissionHandling() {
    requestPermissionLauncher.launch(
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    )
}

fun MainActivity.scanBleDevices() {
    if (ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    val bluetoothManager = getSystemService(BluetoothManager::class.java)
    val bluetoothAdapter = bluetoothManager?.adapter ?: return
    val bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
    if (bluetoothLeScanner == null) {
        Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
        return
    }
    var scanning = false
    val handler = Handler(Looper.getMainLooper())
    val SCAN_PERIOD: Long = 10000
    if (!bluetoothAdapter.isEnabled) {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        return
    }
    val filters = mutableListOf<ScanFilter>()
    val filter = ScanFilter.Builder()
        .setServiceUuid(android.os.ParcelUuid(targetService))
        .build()
    filters.add(filter)

    val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.i("ScanBleDevices", "Found: ${result.device.address}")
            Toast.makeText(this@scanBleDevices, "Found: ${result.device.address}", Toast.LENGTH_SHORT).show()
            val device = result.device
            if (discoveredDevices.none { it.address == device.address }) {
                discoveredDevices.add(device)
            }
            gattHandling(result.device)
        }
    }

    if (ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
    ) {
        if (!scanning) {
            discoveredDevices.clear()
            handler.postDelayed({
                scanning = false
                bluetoothLeScanner?.stopScan(leScanCallback)
                Log.i("ScanBleDevices", "Stopping BLE scan after period...")
            }, SCAN_PERIOD)
            scanning = true
            bluetoothLeScanner?.startScan(filters, settings, leScanCallback)
            Log.i("ScanBleDevices", "Starting BLE scan...")
        } else {
            scanning = false
            bluetoothLeScanner.stopScan(leScanCallback)
        }
    }
}

fun MainActivity.gattServerHandling() {
    val bluetoothManager = getSystemService(BluetoothManager::class.java)
    val bluetoothAdapter = bluetoothManager?.adapter ?: return

    if (ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }

    val gattServerCallback = object : android.bluetooth.BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i("GattServer", "Device connected to our server: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i("GattServer", "Device disconnected from our server: ${device.address}")
            }
        }
    }

    val bluetoothGattServer = bluetoothManager.openGattServer(this, gattServerCallback)
    val service = BluetoothGattService(
        targetService,
        BluetoothGattService.SERVICE_TYPE_PRIMARY
    )
    bluetoothGattServer?.addService(service)
}

fun MainActivity.gattHandling(device: BluetoothDevice) {
    if (ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        Toast.makeText(this, "Bluetooth permission required", Toast.LENGTH_SHORT).show()
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
            1
        )
        return
    }
    val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("GattHandling", "GATT error: $status")
                if (ActivityCompat.checkSelfPermission(
                        this@gattHandling,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    gatt.close()
                }
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i("GattHandling", "Connected to GATT server.")
                if (ActivityCompat.checkSelfPermission(
                        this@gattHandling,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i("GattHandling", "Disconnected from GATT server.")
                gatt.close()
            } else {
                Log.w("GattHandling", "Other connection state: $newState")
                gatt.close()
            }
        }

        @RequiresPermission(value = Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("GattHandling", "Services discovered.")
            }
        }

        @RequiresPermission(value = Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("GattHandling", "Characteristic read successfully.")
            }
        }
    }
    if (ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    ) {
        device.connectGatt(this, false, gattCallback)
    }
}