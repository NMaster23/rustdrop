package com.nmaster23.rustdrop.android

import java.io.File
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
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
import android.provider.OpenableColumns
import android.os.Environment
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import androidx.lifecycle.lifecycleScope

private var selectedUri by mutableStateOf<Uri?>(null)
private var selectedFileName by mutableStateOf<String?>(null)
private var selectedFileSize by mutableStateOf<Long?>(null)
private val discoveredDevices = mutableStateMapOf<String, BluetoothDevice>()

val targetService: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
val targetChar: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")

class MainActivity : ComponentActivity() {
    private var pendingDevice: BluetoothDevice? = null
    private val chunksToSend = LinkedList<ByteArray>()
    private val receiveBuffer = ByteArrayOutputStream()
    private var isReceiving = false
    private var saveTimer: Timer? = null
    
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        selectedUri = uri
        if (uri != null) {
            sendFile(uri)
            pendingDevice?.let { device ->
                pendingDevice = null
            }
        }
    }

    private var bleScanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null

    private val advertiseCallback = object : AdvertiseCallback() {}

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (checkPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    gatt.discoverServices()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(targetService)
                val characteristic = service?.getCharacteristic(targetChar)
                if (characteristic != null && selectedUri != null) {
                    val nameAsBytes = selectedFileName?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
                    val nameLength = nameAsBytes.size.toByte()
                    val fileAsBytes = contentResolver.openInputStream(selectedUri!!)?.readBytes() ?: byteArrayOf()
                    
                    val payload = ByteArray(1 + nameAsBytes.size + fileAsBytes.size)
                    payload[0] = nameLength
                    System.arraycopy(nameAsBytes, 0, payload, 1, nameAsBytes.size)
                    System.arraycopy(fileAsBytes, 0, payload, 1 + nameAsBytes.size, fileAsBytes.size)
                    
                    val chunk = 20
                    chunksToSend.clear()
                    for (i in payload.indices step chunk) {
                        val end = minOf(i + chunk, payload.size)
                        chunksToSend.add(payload.copyOfRange(i, end))
                    }
                    sendNextChunk(gatt, characteristic)
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == targetChar) {
                sendNextChunk(gatt, characteristic)
            }
        }

        private fun sendNextChunk(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (chunksToSend.isNotEmpty()) {
                val nextChunk = chunksToSend.poll()
                characteristic.value = nextChunk
                if (checkPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    gatt.writeCharacteristic(characteristic)
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "File sent successfully", Toast.LENGTH_SHORT).show()
                }
                if (checkPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    gatt.disconnect()
                }
            }
        }
    }

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
            if (responseNeeded && value != null) {
                if (checkPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            }
            if (value != null && characteristic?.uuid == targetChar) {
                receiveBuffer.write(value)
                isReceiving = true
                resetSaveFileTimer()
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

    fun sendFile(uri: Uri) {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                selectedFileName = cursor.getString(nameIndex)
                selectedFileSize = cursor.getLong(sizeIndex)
            }
        }

        val device = pendingDevice ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            device.connectGatt(this, false, gattClientCallback)
        } else {
            Toast.makeText(this, "Bluetooth Connect permission required", Toast.LENGTH_SHORT).show()
        }
    }

    fun openFileForDevice(device: BluetoothDevice) {
        pendingDevice = device
        openDocumentLauncher.launch(arrayOf("*/*"))
    }
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (bluetoothAdapter?.isEnabled == true) {
            startBle()
        }
    }

    private fun checkPermission(permission: String): Boolean {
        return ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private fun startBle() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show()
            return
        }

        if (!adapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                enableBtLauncher.launch(enableBtIntent)
            } else {
                Toast.makeText(this, "Grant Bluetooth Connect permission", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            if (gattServer == null) {
                gattServer = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).openGattServer(this, gattServerCallback)
                val service = BluetoothGattService(targetService, BluetoothGattService.SERVICE_TYPE_PRIMARY)
                val char = BluetoothGattCharacteristic(
                    targetChar,
                    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
                )
                service.addCharacteristic(char)
                gattServer?.addService(service)
            }
        }

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Please turn on GPS/Location", Toast.LENGTH_SHORT).show()
            return
        }
        
        bleScanner = adapter.bluetoothLeScanner
        bleAdvertiser = adapter.bluetoothLeAdvertiser
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            discoveredDevices.clear()
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(targetService)).build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            bleScanner?.startScan(listOf(filter), settings, scanCallback)
            
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) {
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
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bleScanner?.stopScan(scanCallback)
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) {
            bleAdvertiser?.stopAdvertising(advertiseCallback)
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            gattServer?.close()
        }
    }

    private fun resetSaveFileTimer() {
        saveTimer?.cancel()
        saveTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    if (isReceiving) {
                        processReceivedData()
                    }
                }
            }, 1000)
        }
    }

    private fun processReceivedData() {
        isReceiving = false
        val data = receiveBuffer.toByteArray()
        receiveBuffer.reset()
        
        if (data.size > 1) {
            val nameLen = data[0].toInt() and 0xFF
            if (data.size > nameLen) {
                val fileName = String(data, 1, nameLen, Charsets.UTF_8)
                val fileData = data.copyOfRange(nameLen + 1, data.size)
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, fileName)
                
                try {
                    FileOutputStream(file).use { it.write(fileData) }
                    println("Saved incoming file $fileName to ${file.absolutePath}")
                    runOnUiThread {
                        Toast.makeText(this, "File received: $fileName", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}

@Composable
fun Greeting(modifier: Modifier = Modifier, onStartDiscovery: () -> Unit) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.entries.filter { !it.value }.map { it.key }
        if (denied.isEmpty()) {
            onStartDiscovery()
        } else {
            Toast.makeText(context, "Denied: ${denied.joinToString()}", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(Unit) {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
        permissionLauncher.launch(permissions)
    }
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = {
                val permissions = arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                )
                permissionLauncher.launch(permissions)
            },
            modifier = Modifier.size(width = 250.dp, height = 80.dp)
        ) {
            Text("Refresh Discovery")
        }
        Spacer(modifier = Modifier.height(25.dp))
        selectedFileName?.let { name ->
            Text("Selected File: $name", fontSize = 16.sp)
            Spacer(modifier = Modifier.height(10.dp))
        }
        Text("Discovered Devices:", fontSize = 20.sp)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxSize().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(discoveredDevices.values.toList()) { device ->
                val deviceName = try { device.name } catch (_: SecurityException) { null }
                Button(
                    onClick = {
                        (context as? MainActivity)?.openFileForDevice(device)
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
        Greeting(onStartDiscovery = {})
    }
}
