package com.nmaster23.rustdrop.android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
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
import com.nmaster23.rustdrop.android.ui.theme.RustdropAndroidTheme
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

val targetService: java.util.UUID = java.util.UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
val targetChar: java.util.UUID = java.util.UUID.fromString("12345678-1234-5678-1234-56789abcdef1")
const val REQUEST_ENABLE_BT = 1

class MainActivity : ComponentActivity() {
    var isScanning = false
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
        gattServerHandling()
        setContent {
            RustdropAndroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    UserInterface(
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
fun UserInterface(modifier: Modifier = Modifier, devices: List<BluetoothDevice>, onRefresh: () -> Unit = {}) {
    Column(modifier = modifier.padding(16.dp)) {
        Button(
            onClick = onRefresh
        ) {
            Text("Refresh Discovery")
        }
        devices.forEach { device ->
            // Try to get name, fallback to address
            val name = if (ActivityCompat.checkSelfPermission(
                    androidx.compose.ui.platform.LocalContext.current,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                device.name ?: device.address
            } else {
                device.address
            }
            
            Button(
                onClick = { /* Handle connection or selection */ },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(text = name)
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
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    )
}

fun MainActivity.bleAdvertising() {
    val bluetoothManager = getSystemService(BluetoothManager::class.java)
    val bluetoothAdapter = bluetoothManager?.adapter ?: return
    val advertiser = bluetoothAdapter.bluetoothLeAdvertiser

    val settings = android.bluetooth.le.AdvertiseSettings.Builder()
        .setAdvertiseMode(android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        .setTxPowerLevel(android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        .setConnectable(true)
        .build()

    val data = android.bluetooth.le.AdvertiseData.Builder()
        .setIncludeDeviceName(false)
        .addServiceUuid(android.os.ParcelUuid(targetService))
        .build()

    val scanResponse = AdvertiseData.Builder()
        .setIncludeDeviceName(true)
        .build()

    val callback = object : android.bluetooth.le.AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: android.bluetooth.le.AdvertiseSettings) {
            Log.i("BleAdvertising", "LE Advertise Started.")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("BleAdvertising", "LE Advertise Failed: $errorCode")
        }
    }

    if (ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ) == PackageManager.PERMISSION_GRANTED
    ) {
        advertiser?.startAdvertising(settings, data, scanResponse, callback)
    }

}

fun MainActivity.scanBleDevices() {
    if (ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        permissionHandling()
        return
    }
    val locationManager = getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
    val isLocationEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    
    if (!isLocationEnabled) {
        Toast.makeText(this, "Please enable Location Services", Toast.LENGTH_LONG).show()
    }

    val bluetoothManager = getSystemService(BluetoothManager::class.java)
    val bluetoothAdapter = bluetoothManager?.adapter ?: return
    val bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
    if (bluetoothLeScanner == null) {
        Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
        return
    }

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
            val device = result.device
            val deviceName = if (ActivityCompat.checkSelfPermission(this@scanBleDevices, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                device.name ?: "Unknown"
            } else {
                "Unknown (No Permission)"
            }
            Log.i("ScanBleDevices", "Found: ${device.address} ($deviceName)")
            if (discoveredDevices.none { it.address == device.address }) {
                discoveredDevices.add(device)
                Toast.makeText(this@scanBleDevices, "Found device: $deviceName", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e("ScanBleDevices", "Scan failed with error: $errorCode")
            Toast.makeText(this@scanBleDevices, "Scan failed: $errorCode", Toast.LENGTH_SHORT).show()
        }
    }

    if (!isScanning) {
        discoveredDevices.clear()
        handler.postDelayed({
            isScanning = false
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bluetoothLeScanner.stopScan(leScanCallback)
            }
            Log.i("ScanBleDevices", "Stopping BLE scan after period...")
        }, SCAN_PERIOD)
        isScanning = true
        bluetoothLeScanner.startScan(filters, settings, leScanCallback)
        Log.i("ScanBleDevices", "Starting BLE scan (Filtered for $targetService)...")
    } else {
        Log.i("ScanBleDevices", "Already scanning...")
    }
}

fun MainActivity.gattServerHandling() {
    var isReceivingFile = false
    var fileSize: Long = 0
    var bytesReceived: Long = 0
    var incomingFileName = ""
    val headerBuffer = ByteArrayOutputStream()
    var fileOutputStream: FileOutputStream? = null
    val bluetoothManager = getSystemService(BluetoothManager::class.java)
    val bluetoothAdapter = bluetoothManager?.adapter ?: return
    var gattServer: android.bluetooth.BluetoothGattServer? = null
    val gattServerCallback = object : android.bluetooth.BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d("GattServer", "Connection state changed: $newState")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(
                device,
                requestId,
                characteristic,
                preparedWrite,
                responseNeeded,
                offset,
                value
            )
            if (characteristic?.uuid == targetChar && value != null) {
                if (!isReceivingFile) {
                    headerBuffer.write(value)
                    val buffer = headerBuffer.toByteArray()
                    if (buffer.size >= 9) {
                        val byteSize = buffer.copyOfRange(0, 8)
                        fileSize = ByteBuffer.wrap(byteSize).order(ByteOrder.LITTLE_ENDIAN).long
                        val nameLen = buffer[8].toInt() and 0xff
                        val headerSize = 9 + nameLen
                        if (buffer.size >= headerSize) {
                            incomingFileName = String(buffer.copyOfRange(9, headerSize))
                            if (incomingFileName.isEmpty()) {
                                incomingFileName = "RustDrop_File_Error"
                            }
                            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            val outputFile = File(downloadDir, incomingFileName)
                            fileOutputStream = FileOutputStream(outputFile)
                            val remainingData = buffer.copyOfRange(headerSize, buffer.size)
                            if (remainingData.isNotEmpty()) {
                                fileOutputStream?.write(remainingData)
                                bytesReceived += remainingData.size
                            }
                            isReceivingFile = true
                            headerBuffer.reset()
                            Log.i("GattServer", "Started receiving file: $incomingFileName ($fileSize bytes)")
                        }
                    }
                } else {
                    fileOutputStream?.write(value)
                    bytesReceived += value.size
                    if (bytesReceived >= fileSize) {
                        fileOutputStream?.close()
                        fileOutputStream = null
                        isReceivingFile = false
                        bytesReceived = 0
                        headerBuffer.reset()
                    }
                }
                if (responseNeeded) {
                    if (ActivityCompat.checkSelfPermission(this@gattServerHandling, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    }
                }
            }
        }
    }

    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        return
    }
    bleAdvertising()
    gattServer = bluetoothManager.openGattServer(this, gattServerCallback)
    val service = BluetoothGattService(targetService, BluetoothGattService.SERVICE_TYPE_PRIMARY)
    val characteristic = BluetoothGattCharacteristic(targetChar, BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ)
    service.addCharacteristic(characteristic)
    gattServer?.addService(service)
    Log.i("GattServer", "GATT server started and service added.")
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