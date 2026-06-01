package com.nmaster23.rustdrop.android

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RustDropAndroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        modifier = Modifier.padding(innerPadding),
                        onOpenFile = { openFile() },
                    )
                }
            }
        }
    }

    fun openFile() {
        openDocumentLauncher.launch(arrayOf("*/*"))
    }
}

@Composable
fun Greeting(modifier: Modifier = Modifier, onOpenFile: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            discoveredDevices.clear()
            startBleScan(context)
        } else {
            Toast.makeText(context, "Permissions required", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = selectedUri?.toString() ?: "No file selected",
            fontSize = 24.sp
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onOpenFile,
            modifier = Modifier.size(width = 400.dp, height = 150.dp)
        ) {
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
        Text("Discovered: ", fontSize = 20.sp)
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
                            connectToBleDevice(context, device, scope)
                        } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
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

fun startBleScan(context: Context) {
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter = bluetoothManager.adapter
    val scanner = bluetoothAdapter.bluetoothLeScanner

    if (scanner == null) {
        Toast.makeText(context, "BLE Scanner not available", Toast.LENGTH_SHORT).show()
        return
    }

    if (!bluetoothAdapter.isEnabled) {
        Toast.makeText(context, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
        return
    }
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val isLocationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            
    if (!isLocationEnabled) {
        Toast.makeText(context, "Please turn on System Location/GPS", Toast.LENGTH_LONG).show()
        return
    }

    val filter = ScanFilter.Builder()
        .setServiceUuid(ParcelUuid(targetService))
        .build()

    val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            discoveredDevices[device.address] = device
        }

        override fun onScanFailed(errorCode: Int) {
            Toast.makeText(context, "Scan failed: $errorCode", Toast.LENGTH_SHORT).show()
        }
    }

    val hasScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    } else {
        ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    if (hasScanPermission) {
        scanner.startScan(listOf(filter), settings, scanCallback)
        Toast.makeText(context, "BLE Scan started...", Toast.LENGTH_SHORT).show()
    }
}

fun connectToBleDevice(context: Context, device: BluetoothDevice, scope: kotlinx.coroutines.CoroutineScope) {
    val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                scope.launch {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Connected! Discovering services...", Toast.LENGTH_SHORT).show()
                    }
                }
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                scope.launch {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Disconnected", Toast.LENGTH_SHORT).show()
                    }
                }
                gatt.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(targetService)
                val characteristic = service?.getCharacteristic(targetChar)
                if (characteristic != null) {
                    scope.launch {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Target service and characteristic found!", Toast.LENGTH_LONG).show()
                        }
                        // Here you would implement the file sending logic over BLE
                        sendFileOverBle(context, gatt, characteristic)
                    }
                } else {
                    scope.launch {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Target characteristic not found", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        device.connectGatt(context, false, gattCallback)
    }
}

fun sendFileOverBle(context: Context, gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
    val uri = selectedUri ?: return
    val contentResolver = context.contentResolver
    
    try {
        val inputStream = contentResolver.openInputStream(uri) ?: return
        val fileBytes = inputStream.readBytes()
        inputStream.close()
        
        val fileName = getFileName(context, uri).toByteArray()
        val toSend = mutableListOf<Byte>()
        toSend.add(fileName.size.toByte())
        toSend.addAll(fileName.toList())
        toSend.addAll(fileBytes.toList())
        
        val data = toSend.toByteArray()
        
        // BLE chunked sending logic
        // For simplicity, we'll just send the first chunk here.
        // In a real app, you'd handle the MTU and write callbacks.
        
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val mtu = 512 // Default MTU is usually smaller, but let's assume we can request more or it's handled.
            // Simplified sending:
            characteristic.value = data.copyOfRange(0, minOf(data.size, 20)) // Standard BLE chunk size
            gatt.writeCharacteristic(characteristic)
        }
        
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

fun getFileName(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) result = cursor.getString(index)
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/')
        if (cut != null && cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result ?: "unknown_file"
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    RustDropAndroidTheme {
        Greeting(onOpenFile = {})
    }
}
