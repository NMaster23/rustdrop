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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nmaster23.rustdrop.android.ui.theme.RustDropAndroidTheme
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
import android.net.Uri
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
    var bleSendingHeader: ByteArray? = null
    var bleSendingUri: Uri? = null
    var bleSendingTotalSize: Long = 0L
    var bleSendingOffset = 0L
    var bleInputStream: java.io.InputStream? = null
    var targetCharacteristic: BluetoothGattCharacteristic? = null
    var currentOutputUri: android.net.Uri? = null
    var lastChunkSize = 0
    var lastChunk: ByteArray? = null
    var negotiatedMtu = 23
    var selectedWifiDevice: String? = null
    val incomingFileRequest = androidx.compose.runtime.mutableStateOf<String?>(null)
    var acceptCallback: (() -> Unit)? = null
    var rejectCallback: (() -> Unit)? = null

    val isReceivingDialogVisible = androidx.compose.runtime.mutableStateOf(false)
    val receivingProgress = androidx.compose.runtime.mutableStateOf<Float?>(null)
    var receivingStartTime = 0L

    fun startReceivingUI(isDeterminate: Boolean) {
        Handler(Looper.getMainLooper()).post {
            receivingStartTime = System.currentTimeMillis()
            receivingProgress.value = if (isDeterminate) 0f else null
            isReceivingDialogVisible.value = true
        }
    }

    fun updateReceivingUI(progress: Float) {
        Handler(Looper.getMainLooper()).post {
            receivingProgress.value = progress
        }
    }

    fun stopReceivingUI() {
        val duration = System.currentTimeMillis() - receivingStartTime
        val delay = if (duration < 1000L) 1000L - duration else 0L
        Handler(Looper.getMainLooper()).post {
            if (receivingProgress.value != null) {
                receivingProgress.value = 1f
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({
            isReceivingDialogVisible.value = false
        }, delay)
    }

    val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        if (selectedDeviceForSending != null) {
            val fileName = getFileName(uri) ?: "UnknownFile"
            val fileNameBytes = fileName.toByteArray(Charsets.UTF_8)
            val nameLen = fileNameBytes.size
            var exactSize = 0L
            var finalUri = uri
            try {
                val tempFile = File(cacheDir, "transfer_${System.currentTimeMillis()}.tmp")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buf = ByteArray(8192)
                        var read = input.read(buf)
                        while (read != -1) {
                            output.write(buf, 0, read)
                            read = input.read(buf)
                        }
                    }
                }
                exactSize = tempFile.length()
                finalUri = Uri.fromFile(tempFile)
            } catch (e: Exception) {
                exactSize = getFileSize(uri)
            }
            val fileSize = exactSize

            val headerBuffer = ByteBuffer.allocate(8 + 1 + nameLen)
            headerBuffer.order(ByteOrder.LITTLE_ENDIAN)
            headerBuffer.putLong(fileSize)
            headerBuffer.put(nameLen.toByte())
            headerBuffer.put(fileNameBytes)

            bleSendingHeader = headerBuffer.array()
            bleSendingUri = finalUri
            bleSendingTotalSize = fileSize
            bleSendingOffset = 0L
            bleInputStream?.close()
            bleInputStream = null

            gattHandling(selectedDeviceForSending!!)
        } else if (selectedWifiDevice != null) {
            sendFileWifi(selectedWifiDevice!!, uri)
        }
    }

    private fun getFileSize(uri: android.net.Uri): Long {
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (sizeIndex != -1 && cursor.moveToFirst()) {
                if (!cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
        if (size == 0L) {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                size = it.length
            }
        }
        return size
    }

    fun getFileName(uri: android.net.Uri): String? {
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
        wifiServer()
        if (hasBluetoothConnectPermission()) {
            gattServerHandling()
        }
        setContent {
            RustDropAndroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    UserInterface(
                        modifier = Modifier.padding(innerPadding),
                        devices = discoveredDevices,
                        wifiDevices = discoveredWifiDevices,
                        onRefresh = { scanBleDevices() },
                        onDeviceClick = { device ->
                            selectedDeviceForSending = device
                            selectedWifiDevice = null
                            android.widget.Toast.makeText(this@MainActivity, "Select a file to send...", android.widget.Toast.LENGTH_SHORT).show()
                            filePickerLauncher.launch("*/*")
                        },
                        onWifiDeviceClick = { service ->
                            selectedWifiDevice = service.host.hostAddress
                            selectedDeviceForSending = null
                            filePickerLauncher.launch("*/*")
                        }
                    )

                    val request = incomingFileRequest.value
                    if (request != null) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = {
                                rejectCallback?.invoke()
                                incomingFileRequest.value = null
                            },
                            title = { Text("Incoming File") },
                            text = { Text("Accept file: $request?") },
                            confirmButton = {
                                Button(onClick = {
                                    acceptCallback?.invoke()
                                    incomingFileRequest.value = null
                                }) { Text("Accept") }
                            },
                            dismissButton = {
                                Button(onClick = {
                                    rejectCallback?.invoke()
                                    incomingFileRequest.value = null
                                }) { Text("Reject") }
                            }
                        )
                    }

                    if (isReceivingDialogVisible.value) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { },
                            title = { Text("Receiving File...") },
                            text = {
                                androidx.compose.foundation.layout.Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = androidx.compose.ui.Alignment.Center
                                ) {
                                    val progress = receivingProgress.value
                                    if (progress != null) {
                                        androidx.compose.material3.LinearProgressIndicator(progress = progress)
                                    } else {
                                        androidx.compose.material3.LinearProgressIndicator()
                                    }
                                }
                            },
                            confirmButton = {}
                        )
                    }
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
    var fileOutputStream: java.io.OutputStream? = null
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
                    if (isReceivingFile) stopReceivingUI()
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
                if (responseNeeded && device != null) {
                    if (hasBluetoothConnectPermission()) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    }
                }
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
                            
                            fileOutputStream = createDownloadStream(incomingFileName)
                            val remainingData = buffer.copyOfRange(headerSize, buffer.size)
                            startReceivingUI(true)
                            if (remainingData.isNotEmpty() && fileOutputStream != null) {
                                fileOutputStream!!.write(remainingData)
                                bytesReceived += remainingData.size
                                updateReceivingUI(bytesReceived.toFloat() / fileSize.toFloat())
                            }
                            headerBuffer.reset()
                            isReceivingFile = true
                            Log.i("GattServer", "Started receiving file: $incomingFileName ($fileSize bytes)")
                            Handler(Looper.getMainLooper()).post {
                                incomingFileRequest.value = incomingFileName
                                acceptCallback = {
                                    incomingFileRequest.value = null
                                    Toast.makeText(this@gattServerHandling, "Receiving: $incomingFileName", Toast.LENGTH_SHORT).show()
                                }
                                rejectCallback = {
                                    incomingFileRequest.value = null
                                    isReceivingFile = false
                                    bytesReceived = 0
                                    fileOutputStream?.close()
                                    fileOutputStream = null
                                    Toast.makeText(this@gattServerHandling, "File rejected", Toast.LENGTH_SHORT).show()
                                }
                            }
                            if (bytesReceived >= fileSize) {
                                fileOutputStream?.flush()
                                fileOutputStream?.close()
                                isReceivingFile = false
                                bytesReceived = 0
                                Log.i("GattServer", "File saved to Downloads: $incomingFileName")
                                stopReceivingUI()
                            }
                        }
                    }
                } else {
                    fileOutputStream?.write(value)
                    bytesReceived += value.size
                    updateReceivingUI(bytesReceived.toFloat() / fileSize.toFloat())
                    if (bytesReceived >= fileSize) {
                        fileOutputStream?.flush()
                        fileOutputStream?.close()
                        isReceivingFile = false
                        bytesReceived = 0
                        headerBuffer.reset()
                        Log.i("GattServer", "File saved to Downloads: $incomingFileName")
                        stopReceivingUI()
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

    fun sendNextChunk(gatt: BluetoothGatt, isRetry: Boolean = false) {
        val char = targetCharacteristic ?: return
        val header = bleSendingHeader ?: return
        val hSize = header.size.toLong()

        if (bleSendingOffset >= hSize + bleSendingTotalSize) {
            Log.i("GattHandling", "Finished sending file")
            bleInputStream?.close(); bleInputStream = null
            Handler(Looper.getMainLooper()).post { Toast.makeText(this@gattHandling, "File Sent!", Toast.LENGTH_SHORT).show() }
            gatt.close()
            return
        }

        val chunk = if (isRetry && lastChunk != null) {
            lastChunk!!
        } else {
            val remaining = hSize + bleSendingTotalSize - bleSendingOffset
            val chunkSize = minOf((negotiatedMtu - 3).toLong(), 256L, remaining).toInt()
            val newChunk = if (bleSendingOffset < hSize) {
                val hRemaining = hSize - bleSendingOffset
                header.copyOfRange(bleSendingOffset.toInt(), bleSendingOffset.toInt() + minOf(chunkSize.toLong(), hRemaining).toInt())
            } else {
                if (bleInputStream == null) {
                    bleInputStream = contentResolver.openInputStream(bleSendingUri!!)
                    val skipAmount = bleSendingOffset - hSize
                    if (skipAmount > 0) {
                        bleInputStream?.skip(skipAmount)
                    }
                }
                val buf = ByteArray(chunkSize)
                val read = bleInputStream?.read(buf) ?: -1
                if (read <= 0) {
                    buf.fill(0)
                    buf
                } else if (read < chunkSize) {
                    buf.copyOfRange(0, read)
                } else {
                    buf
                }
            }
            lastChunk = newChunk
            newChunk
        }

        lastChunkSize = chunk.size
        char.value = chunk
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        var retries = 0
        var success = false
        while (!success && retries < 5) {
            success = gatt.writeCharacteristic(char)
            if (!success) {
                Log.e("GattHandling", "Failed to write, retrying... $retries")
                Thread.sleep(50)
                retries++
            }
        }
        if (!success) {
            Log.e("GattHandling", "Failed to write after retries")
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
                    bleInputStream?.close(); bleInputStream = null
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
                if (targetCharacteristic != null && bleSendingUri != null) {
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
                bleSendingOffset += lastChunkSize
                sendNextChunk(gatt)
            } else {
                Log.e("GattHandling", "Write failed: $status, retrying...")
                // Retry same chunk
                sendNextChunk(gatt, isRetry = true)
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

fun MainActivity.wifiServer() {
    Thread {
        try {
            val serverSocket = java.net.ServerSocket(5200)
            while (true) {
                serverSocket.accept().use { socket ->
                    val inputStream = socket.getInputStream()
                    val encLen = inputStream.read()
                    if (encLen <= 0) return@use
                    val nameBytes = ByteArray(encLen)
                    var read = 0
                    while (read < encLen) {
                        val r = inputStream.read(nameBytes, read, encLen - read)
                        if (r == -1) break
                        read += r
                    }
                    val rawName = String(nameBytes, Charsets.UTF_8)
                    val fileName = rawName.substringAfter("\r\n").substringBefore("\r\n")
                        .replace(Regex("[^a-zA-Z0-9.\\-_]"), "")
                        .ifEmpty { "wifi_transfer_${System.currentTimeMillis()}" }
                    
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var accepted = false
                    Handler(Looper.getMainLooper()).post {
                        incomingFileRequest.value = fileName
                        acceptCallback = {
                            accepted = true
                            latch.countDown()
                        }
                        rejectCallback = {
                            accepted = false
                            latch.countDown()
                        }
                    }
                    try { latch.await(30, java.util.concurrent.TimeUnit.SECONDS) } catch(e: Exception) {}
                    
                    if (accepted) {
                        startReceivingUI(false)
                        createDownloadStream(fileName)?.use { fos ->
                            inputStream.copyTo(fos)
                        }
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(this@wifiServer, "Received via WiFi: $fileName", Toast.LENGTH_SHORT).show()
                        }
                        stopReceivingUI()
                    } else {
                        Handler(Looper.getMainLooper()).post {
                            incomingFileRequest.value = null
                            Toast.makeText(this@wifiServer, "File rejected", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WifiServer", "Error: ${e.message}")
        }
    }.start()
}

fun MainActivity.sendFileWifi(ip: String, uri: Uri) {
    Thread {
        try {
            java.net.Socket(ip, 5200).use { socket ->
                val output = socket.getOutputStream()
                val fileName = getFileName(uri) ?: "file"
                val nameAsBytes = fileName.toByteArray(Charsets.UTF_8)
                val nameEncoded = "${nameAsBytes.size.toString(16)}\r\n$fileName\r\n0\r\n\r\n".toByteArray(Charsets.UTF_8)
                output.write(nameEncoded.size)
                output.write(nameEncoded)
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.copyTo(output)
                }
                output.flush()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@sendFileWifi, "WiFi File Sent!", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("WifiSend", "Error: ${e.message}")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this@sendFileWifi, "WiFi Send Failed", Toast.LENGTH_SHORT).show()
            }
        }
    }.start()
}

fun android.content.Context.createDownloadStream(fileName: String): java.io.OutputStream? {
    val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName) })
    return uri?.let { contentResolver.openOutputStream(it) }
}