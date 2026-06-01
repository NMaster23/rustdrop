package com.nmaster23.rustdrop.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.nmaster23.rustdrop.android.ui.theme.RustDropAndroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

private var selectedUri by mutableStateOf<Uri?>(null)
private val discoveredDevices = mutableStateMapOf<String, BluetoothDevice>()

val targetService: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
val targetChar: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")

class MainActivity : ComponentActivity() {
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        selectedUri = uri
    }

    private var bleScanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null

    private val advertiseCallback = object : AdvertiseCallback() {}

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            if (responseNeeded) {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            }
            if (value != null && characteristic?.uuid == targetChar) {
                // Process the received bytes
                println("Received ${value.size} bytes from Rust")
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address
            if (address != null) {
                discoveredDevices[address] = device
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RustDropAndroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        modifier = Modifier.padding(innerPadding),
                        onOpenFile = { openFile() },
                        onStartDiscovery = { startBle() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBle()
    }

    fun openFile() {
        openDocumentLauncher.launch(arrayOf("*/*"))
    }
    private fun startBle() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            gattServer = bluetoothManager.openGattServer(this, gattServerCallback)
            val service = BluetoothGattService(targetService, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val char = BluetoothGattCharacteristic(
                targetChar,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(char)
            gattServer?.addService(service)
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Enable Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Please turn on GPS", Toast.LENGTH_SHORT).show()
            return
        }
        bleScanner = bluetoothAdapter.bluetoothLeScanner
        bleAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            discoveredDevices.clear()
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(targetService)).build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            bleScanner?.startScan(listOf(filter), settings, scanCallback)
            
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) {
                val advertiseSettings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .build()
                val advertiseData = AdvertiseData.Builder()
                    .addServiceUuid(ParcelUuid(targetService))
                    .build()
                val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
                bleAdvertiser?.startAdvertising(advertiseSettings, advertiseData, scanResponse, advertiseCallback)
            }
        }
    }
    @SuppressLint("MissingPermission")
    private fun stopBle() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bleScanner?.stopScan(scanCallback)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) {
                bleAdvertiser?.stopAdvertising(advertiseCallback)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                gattServer?.close()
            }
        } else {
            bleScanner?.stopScan(scanCallback)
            bleAdvertiser?.stopAdvertising(advertiseCallback)
            gattServer?.close()
        }
    }
}

@Composable
fun Greeting(modifier: Modifier = Modifier, onOpenFile: () -> Unit, onStartDiscovery: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            onStartDiscovery()
        } else {
            Toast.makeText(context, "Permissions required", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = selectedUri?.toString() ?: "No file selected", fontSize = 24.sp)
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onOpenFile, modifier = Modifier.size(width = 400.dp, height = 150.dp)) {
            Text("Select File")
        }
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = {
                val permissions = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    permissions.add(Manifest.permission.BLUETOOTH_SCAN)
                    permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                    permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
                }
                permissionLauncher.launch(permissions.toTypedArray())
            },
            modifier = Modifier.size(width = 250.dp, height = 80.dp)
        ) {
            Text("Start Discovery")
        }
        Spacer(modifier = Modifier.height(25.dp))
        Text("Discovered Devices:", fontSize = 20.sp)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxSize().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(discoveredDevices.values.toList()) { device ->
                val deviceName = try { device.name } catch (_: SecurityException) { null }
                Button(
                    onClick = {
                        val connectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                        } else true

                        if (connectPermission) {
                            scope.launch(Dispatchers.IO) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Connecting to ${device.address}...", Toast.LENGTH_SHORT).show()
                                }
                                device.connectGatt(context, false, object : BluetoothGattCallback() {
                                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                                            scope.launch(Dispatchers.Main) {
                                                Toast.makeText(context, "Connected via GATT!", Toast.LENGTH_SHORT).show()
                                            }
                                            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                                                gatt.discoverServices()
                                            }
                                        }
                                    }

                                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                                        if (status == BluetoothGatt.GATT_SUCCESS) {
                                            val service = gatt.getService(targetService)
                                            val characteristic = service?.getCharacteristic(targetChar)
                                            if (characteristic != null) {
                                                scope.launch(Dispatchers.Main) {
                                                    Toast.makeText(context, "Target Service Found!", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                })
                            }
                        }
                    }
                ) {
                    Text(text = deviceName ?: device.address)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    RustDropAndroidTheme {
        Greeting(onOpenFile = {}, onStartDiscovery = {})
    }
}
