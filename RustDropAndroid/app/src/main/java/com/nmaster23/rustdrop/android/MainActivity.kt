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
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ContentValues.TAG
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
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
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

val targetService: java.util.UUID = java.util.UUID.fromString("00001825-0000-1000-8000-00805f9b34fb")
val targetChar: java.util.UUID = java.util.UUID.fromString("00002ac5-0000-1000-8000-00805f9b34fb")
const val REQUEST_ENABLE_BT = 1

fun android.content.Context.hasBluetoothConnectPermission(): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

fun android.content.Context.hasBluetoothScanPermission(): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    } else {
        androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}

fun android.content.Context.hasBluetoothAdvertisePermission(): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

fun android.content.Context.hasMulticastPermission(): Boolean {
    return androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_MULTICAST_STATE) == PackageManager.PERMISSION_GRANTED
}

class MainActivity : ComponentActivity() {
    var isScanning = false

    var gattServer: android.bluetooth.BluetoothGattServer? = null
    var gattServerCallbackRef: android.bluetooth.BluetoothGattServerCallback? = null
    var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    var nsdRegistrationListener: android.net.nsd.NsdManager.RegistrationListener? = null
    var nsdDiscoveryListener: android.net.nsd.NsdManager.DiscoveryListener? = null
    var selectedDeviceForSending: BluetoothDevice? = null
    var fileDataToSend: ByteArray? = null
    var fileDataSendOffset = 0
    var targetCharacteristic: BluetoothGattCharacteristic? = null
    var currentOutputFile: File? = null
    var lastChunkSize = 0
    var negotiatedMtu = 23
    val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && selectedDeviceForSending != null) {
            val inputStream = contentResolver.openInputStream(uri)
            val fileBytes = inputStream?.readBytes() ?: return@registerForActivityResult
            val fileName = getFileName(uri) ?: "UnknownFile"
            val fileNameBytes = fileName.toByteArray(Charsets.UTF_8)
            val nameLen = fileNameBytes.size

            val headerBuffer = ByteBuffer.allocate(8 + 1 + nameLen)
            headerBuffer.order(ByteOrder.LITTLE_ENDIAN)
            headerBuffer.putLong(fileBytes.size.toLong())
            headerBuffer.put(nameLen.toByte())
            headerBuffer.put(fileNameBytes)

            val fullData = headerBuffer.array() + fileBytes
            fileDataToSend = fullData
            fileDataSendOffset = 0

            gattHandling(selectedDeviceForSending!!)
        }
    }

    private fun getFileName(uri: android.net.Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result.substring(cut + 1)
            }
        }
        return result
    }

    val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach {
            Log.d("Permissions", "${it.key} = ${it.value}")
        }
        val connectGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
        } else true
        val advertiseGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_ADVERTISE] == true
        } else true
        if (connectGranted && advertiseGranted) {
            gattServerHandling()
        }
    }
    val discoveredDevices = mutableStateListOf<BluetoothDevice>()
    val discoveredWifiDevices = mutableStateListOf<NsdServiceInfo>()
    val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            scanBleDevices()
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        gattServer?.close()
        gattServer = null
        val nsdManager = getSystemService(android.content.Context.NSD_SERVICE) as android.net.nsd.NsdManager
        nsdRegistrationListener?.let { nsdManager.unregisterService(it) }
        nsdDiscoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e("MainActivity", "Error stopping discovery on destroy", e)
            }
        }
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissionHandling()
        mdnsHandling()
        if (hasBluetoothConnectPermission()) {
            gattServerHandling()
        }
        setContent {
            RustdropAndroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    UserInterface(
                        modifier = Modifier.padding(innerPadding),
                        devices = discoveredDevices,
                        wifiDevices = discoveredWifiDevices,
                        onRefresh = { scanBleDevices() },
                        onDeviceClick = { device ->
                            selectedDeviceForSending = device
                            android.widget.Toast.makeText(this@MainActivity, "Select a file to send...", android.widget.Toast.LENGTH_SHORT).show()
                            filePickerLauncher.launch("*/*")
                        },
                        onWifiDeviceClick = { service ->
                            android.widget.Toast.makeText(this@MainActivity, "WiFi device: ${service.serviceName}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun UserInterface(
    modifier: Modifier = Modifier,
    devices: List<BluetoothDevice>,
    wifiDevices: List<NsdServiceInfo> = emptyList(),
    onRefresh: () -> Unit = {},
    onDeviceClick: (BluetoothDevice) -> Unit = {},
    onWifiDeviceClick: (NsdServiceInfo) -> Unit = {}
) {
    Column(modifier = modifier.padding(16.dp)) {
        Button(
            onClick = onRefresh
        ) {
            Text("Refresh Discovery")
        }

        if (devices.isNotEmpty()) {
            Text("Bluetooth Devices:", modifier = Modifier.padding(top = 16.dp))
        }
        devices.forEach { device ->
            val name = if (androidx.compose.ui.platform.LocalContext.current.hasBluetoothConnectPermission()) {
                device.name ?: device.address
            } else {
                device.address
            }

            Button(
                onClick = { onDeviceClick(device) },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(text = name)
            }
        }

        if (wifiDevices.isNotEmpty()) {
            Text("WiFi Devices:", modifier = Modifier.padding(top = 16.dp))
        }
        wifiDevices.forEach { service ->
            Button(
                onClick = { onWifiDeviceClick(service) },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(text = service.serviceName)
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
            Manifest.permission.BLUETOOTH_CONNECT
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

    val data = AdvertiseData.Builder()
        .setIncludeDeviceName(false)
        .addServiceUuid(ParcelUuid(targetService))
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

    if (hasBluetoothAdvertisePermission()) {
        advertiser?.startAdvertising(settings, data, scanResponse, callback)
    }
}

fun MainActivity.scanBleDevices() {
    if (!hasBluetoothScanPermission()) {
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

    if (!bluetoothAdapter.isEnabled) {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        enableBluetoothLauncher.launch(enableBtIntent)
        return
    }

    val bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
    if (bluetoothLeScanner == null) {
        Toast.makeText(this, "BLE Scanner not available", Toast.LENGTH_SHORT).show()
        return
    }
    val handler = Handler(Looper.getMainLooper())
    val SCAN_PERIOD: Long = 10000
    if (gattServer == null) {
        gattServerHandling()
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
            val deviceName = if (hasBluetoothConnectPermission()) {
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
            if (hasBluetoothScanPermission()) {
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
    if (!hasBluetoothConnectPermission()) {
        return
    }
    if (gattServer != null) {
        Log.i("GattServer", "GATT server already running, skipping re-creation.")
        return
    }
    var isReceivingFile = false
    var fileSize: Long = 0
    var bytesReceived: Long = 0
    var incomingFileName = ""
    val headerBuffer = ByteArrayOutputStream()
    var fileOutputStream: FileOutputStream? = null
    val bluetoothManager = getSystemService(BluetoothManager::class.java)
    val gattServerCallback = object : android.bluetooth.BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val deviceName = if (hasBluetoothConnectPermission()) {
                device.name ?: device.address
            } else {
                device.address
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i("GattServer", "Device connected: $deviceName")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            this@gattServerHandling,
                            "Desktop Connected to Phone!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    if (hasBluetoothConnectPermission()) {
                        gattServer?.connect(device, true)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i("GattServer", "Device disconnected: $deviceName")
                    // Reset transfer state on disconnect
                    isReceivingFile = false
                    bytesReceived = 0
                    headerBuffer.reset()
                    fileOutputStream?.close()
                    fileOutputStream = null
                }
            }
        }
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("GattServer", "Service registered. Starting advertising.")
                bleAdvertising()
            } else {
                Log.e("GattServer", "Failed to add service: $status")
            }
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
                                .replace(Regex("[^a-zA-Z0-9.\\-_]"), "")
                            if (incomingFileName.isEmpty()) {
                                incomingFileName = "RustDrop_File_Error"
                            }
                            val downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
                            if (downloadDir != null && !downloadDir.exists()) downloadDir.mkdirs()
                            val outputFile = File(downloadDir, incomingFileName)
                            currentOutputFile = outputFile
                            fileOutputStream = FileOutputStream(outputFile)
                            val remainingData = buffer.copyOfRange(headerSize, buffer.size)
                            if (remainingData.isNotEmpty()) {
                                fileOutputStream!!.write(remainingData)
                                bytesReceived += remainingData.size
                            }
                            headerBuffer.reset()
                            isReceivingFile = true
                            Log.i("GattServer", "Started receiving file: $incomingFileName ($fileSize bytes)")
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(this@gattServerHandling, "Receiving: $incomingFileName", Toast.LENGTH_SHORT).show()
                            }
                            if (bytesReceived >= fileSize) {
                                fileOutputStream!!.flush()
                                fileOutputStream!!.close()
                                isReceivingFile = false
                                bytesReceived = 0
                                outputFile.let { file ->
                                    MediaScannerConnection.scanFile(this@gattServerHandling, arrayOf(file.absolutePath), null, null)
                                    Log.i("GattServer", "File saved and scanned: ${file.absolutePath}")
                                }
                            }
                        }
                    }
                } else {
                    fileOutputStream?.write(value)
                    bytesReceived += value.size
                    if (bytesReceived >= fileSize) {
                        fileOutputStream?.flush()
                        fileOutputStream?.close()
                        isReceivingFile = false
                        bytesReceived = 0
                        headerBuffer.reset()
                        currentOutputFile?.let { file ->
                            MediaScannerConnection.scanFile(this@gattServerHandling, arrayOf(file.absolutePath), null, null)
                            Log.i("GattServer", "File saved and scanned: ${file.absolutePath}")
                        }
                    }
                }
                if (responseNeeded) {
                    if (hasBluetoothConnectPermission()) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    }
                }
            }
        }
    }

    if (!hasBluetoothConnectPermission()) {
        return
    }
    gattServerCallbackRef = gattServerCallback
    gattServer = bluetoothManager.openGattServer(this, gattServerCallback)
    val service = BluetoothGattService(targetService, BluetoothGattService.SERVICE_TYPE_PRIMARY)
    val characteristic = BluetoothGattCharacteristic(
        targetChar,
        BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        BluetoothGattCharacteristic.PERMISSION_WRITE
    )
    service.addCharacteristic(characteristic)
    gattServer?.addService(service)
    Log.i("GattServer", "GATT server started and service added.")
}

fun MainActivity.gattHandling(device: BluetoothDevice) {
    if (!hasBluetoothConnectPermission()) {
        Toast.makeText(this, "Bluetooth permission required", Toast.LENGTH_SHORT).show()
        val perm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(perm),
            1
        )
        return
    }

    fun sendNextChunk(gatt: BluetoothGatt) {
        val data = fileDataToSend ?: return
        val char = targetCharacteristic ?: return
        if (fileDataSendOffset >= data.size) {
            Log.i("GattHandling", "Finished sending file")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this@gattHandling, "File Sent!", Toast.LENGTH_SHORT).show()
            }
            gatt.close()
            fileDataToSend = null
            return
        }
        val chunkSize = minOf(negotiatedMtu - 3, data.size - fileDataSendOffset)
        val chunk = data.copyOfRange(fileDataSendOffset, fileDataSendOffset + chunkSize)
        lastChunkSize = chunkSize
        char.value = chunk
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (!gatt.writeCharacteristic(char)) {
            Log.e("GattHandling", "Failed to write characteristic")
        }
    }

    val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i("GattHandling", "Connected. Requesting MTU.")
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (hasBluetoothConnectPermission()) gatt.requestMtu(512)
                    }, 500)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i("GattHandling", "Disconnected (status=$status)")
                    gatt.close()
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@gattHandling, "Desktop Disconnected!", Toast.LENGTH_SHORT).show()
                    }
                }
                else -> Log.d("GattHandling", "State changed to $newState")
            }
        }
        @RequiresPermission(value = Manifest.permission.BLUETOOTH_CONNECT)
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            Log.i("GattHandling", "MTU negotiated: $negotiatedMtu")
            gatt.discoverServices()
        }
        @RequiresPermission(value = Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("GattHandling", "Services discovered.")
                val service = gatt.getService(targetService)
                targetCharacteristic = service?.getCharacteristic(targetChar)
                if (targetCharacteristic != null && fileDataToSend != null) {
                    sendNextChunk(gatt)
                } else {
                    Log.e("GattHandling", "Target characteristic not found or no file data.")
                    gatt.close()
                }
            }
        }

        @RequiresPermission(value = Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                fileDataSendOffset += lastChunkSize
                sendNextChunk(gatt)
            } else {
                Log.e("GattHandling", "Write failed: $status")
                gatt.close()
            }
        }
    }
    if (hasBluetoothConnectPermission()) {
        device.connectGatt(this, false, gattCallback)
    }
}

fun MainActivity.mdnsHandling() {
    val devicename = try {
        InetAddress.getLocalHost().hostName
    } catch (_: Exception) {
        android.os.Build.MODEL
    }.replace(Regex("[^a-zA-Z0-9]"), "")
    val serviceNameInfo = "rustdrop-$devicename"
    val serviceTypeInfo = "_rustdrop._tcp"
    val serviceInfo = NsdServiceInfo().apply {
        serviceName = "rustdrop-$devicename"
        serviceType = "_rustdrop._tcp"
        port = 5200
    }

    val registrationListener = object : android.net.nsd.NsdManager.RegistrationListener {
        override fun onServiceRegistered(registeredServiceInfo: NsdServiceInfo) {
            Log.i("mDNS", "Service registered: ${registeredServiceInfo.serviceName}")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e("mDNS", "Registration failed: $errorCode")
        }

        override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
    }
    nsdRegistrationListener = registrationListener

    val nsdManager = getSystemService(android.content.Context.NSD_SERVICE) as android.net.nsd.NsdManager
    val wifiManager = getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager

    if (hasMulticastPermission()) {
        multicastLock = wifiManager.createMulticastLock("RustDropMulticastLock").apply {
            setReferenceCounted(true)
            acquire()
        }
    }


    var discoveryListener: NsdManager.DiscoveryListener? = null
    var isDiscoveryActive = false
    fun startDiscovery() {
        if (isDiscoveryActive) return
        discoveryListener?.let {
            try {
                nsdManager.discoverServices(serviceTypeInfo, NsdManager.PROTOCOL_DNS_SD, it)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting discovery", e)
            }
        }
    }
    discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d(TAG, "Service discovery started")
            isDiscoveryActive = true
        }
        override fun onServiceFound(service: NsdServiceInfo) {
            Log.d(TAG, "Service discovery success$service")
            val typeMatched = service.serviceType.contains(serviceTypeInfo)
            when {
                !typeMatched ->
                    Log.d(TAG, "Unknown Service Type: ${service.serviceType}")
                service.serviceName == serviceNameInfo ->
                    Log.d(TAG, "Same machine: $serviceNameInfo")
                service.serviceName.contains("rustdrop") -> {
                    if (discoveredWifiDevices.none { it.serviceName == service.serviceName }) {
                        try {
                            nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                    Log.e(TAG, "Resolve failed: $errorCode")
                                }
                                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                    Log.i(TAG, "Resolve Succeeded. $serviceInfo")
                                    Handler(Looper.getMainLooper()).post {
                                        if (discoveredWifiDevices.none { it.serviceName == serviceInfo.serviceName }) {
                                            discoveredWifiDevices.add(serviceInfo)
                                        }
                                    }
                                }
                            })
                        } catch (e: Exception) {
                            Log.e(TAG, "Error resolving service", e)
                        }
                    }
                }
            }
        }
        override fun onServiceLost(service: NsdServiceInfo) {
            Log.e(TAG, "service lost: $service")
            Handler(Looper.getMainLooper()).post {
                discoveredWifiDevices.removeAll { it.serviceName == service.serviceName }
            }
        }
        override fun onDiscoveryStopped(serviceType: String) {
            Log.i(TAG, "Discovery stopped: $serviceType. Restarting...")
            isDiscoveryActive = false
            Handler(Looper.getMainLooper()).postDelayed({
                startDiscovery()
            }, 2000)
        }
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery failed: Error code:$errorCode")
            isDiscoveryActive = false
            try {
                nsdManager.stopServiceDiscovery(this)
            } catch (e: Exception) {}
            Handler(Looper.getMainLooper()).postDelayed({
                startDiscovery()
            }, 5000)
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Stop Discovery failed: Error code:$errorCode")
            isDiscoveryActive = false
            try {
                nsdManager.stopServiceDiscovery(this)
            } catch (e: Exception) {}
        }
    }
    nsdDiscoveryListener = discoveryListener
    nsdManager.registerService(serviceInfo, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, registrationListener)
    nsdManager.discoverServices(serviceTypeInfo, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
}